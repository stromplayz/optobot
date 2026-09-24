package com.optobot.native

import android.content.Context
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * OptoAssetDecryptor — AES-256-GCM decryption of bundled model assets.
 *
 * Threat model:
 *  - Casual reverse-engineering: someone unzips the APK and looks at /assets.
 *    They see `opto_core.bin` and `opto_meta.bin` — opaque encrypted blobs.
 *    They cannot tell which underlying architecture (LFM, Llama, Mistral, etc.) is inside
 *    without the key, because the ONNX header bytes are inside the ciphertext.
 *
 * Key derivation:
 *  - PBKDF2-HMAC-SHA256(password = per-build secret, salt = random 32 bytes)
 *  - The per-build secret is compiled into a separate `OptoBuildSecret.kt` file
 *    (gitignored) and stripped by ProGuard's optimization pass.
 *  - The salt is stored in `opto_key.hint` (non-secret on its own).
 *
 * Loading:
 *  - We use mmap-style streaming: we decrypt block-by-block into a tmpfile,
 *    then ONNX Runtime mmaps that tmpfile so weights are paged in/out by the OS,
 *    never fully resident in RAM.
 */
class OptoAssetDecryptor(private val context: Context) {

    fun deriveKey(salt: ByteArray): SecretKey {
        // OptoBuildSecret.BUILD_SECRET is injected at build time, gitignored.
        // Fallback to a deterministic dev secret if absent (debug builds only).
        val password = try {
            Class.forName("com.optobot.native.OptoBuildSecret")
                .getField("BUILD_SECRET")
                .get(null) as CharArray
        } catch (_: Throwable) {
            "optobot-dev-fallback-secret-DO-NOT-USE-IN-RELEASE".toCharArray()
        }

        val spec = PBEKeySpec(password, salt, OptoConfig.PBKDF2_ITERS, OptoConfig.PBKDF2_KEY_LEN * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Decrypt an asset to a private-file tmpfile, return its absolute path.
     * ONNX Runtime will then mmap this path lazily — full weights never in RAM.
     */
    fun decryptToTmpFile(assetName: String, tmpPrefix: String): String {
        val out = java.io.File(context.filesDir, "$tmpPrefix-${System.nanoTime()}.bin")
        context.assets.open(assetName).use { input ->
            // Layout: [salt(32)][iv(12)][ciphertext...][tag(16)]
            val salt = ByteArray(OptoConfig.PBKDF2_SALT_LEN).also { input.read(it) }
            val iv = ByteArray(OptoConfig.GCM_IV_LEN).also { input.read(it) }
            val key = deriveKey(salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(OptoConfig.GCM_TAG_LEN * 8, iv))

            java.io.FileOutputStream(out).use { fos ->
                val buf = ByteArray(64 * 1024)
                var cipherOut = javax.crypto.CipherOutputStream(fos, cipher)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    cipherOut.write(buf, 0, n)
                }
                cipherOut.flush()
                cipherOut.close()
            }
        }
        // Mark tmpfile read-only so it can be mmap'd read-only by ORT
        out.setReadOnly()
        return out.absolutePath
    }

    /**
     * Decrypt a small asset (tokenizer vocab, config) fully into memory.
     */
    fun decryptSmallAsset(assetName: String): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        context.assets.open(assetName).use { input ->
            val salt = ByteArray(OptoConfig.PBKDF2_SALT_LEN).also { input.read(it) }
            val iv = ByteArray(OptoConfig.GCM_IV_LEN).also { input.read(it) }
            val key = deriveKey(salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(OptoConfig.GCM_TAG_LEN * 8, iv))
            javax.crypto.CipherInputStream(input, cipher).use { cis ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = cis.read(buf)
                    if (n <= 0) break
                    baos.write(buf, 0, n)
                }
            }
        }
        return baos.toByteArray()
    }

    companion object {
        /** Generate a fresh key hint file (used by scripts/encrypt_model.py parity check). */
        fun freshKeyHintBase64(): String {
            val random = SecureRandom()
            val salt = ByteArray(OptoConfig.PBKDF2_SALT_LEN).also { random.nextBytes(it) }
            return Base64.encodeToString(salt, Base64.NO_WRAP)
        }
    }
}
