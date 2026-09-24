package com.optobot.native

import android.content.Context
import android.util.Log
import java.io.File

/**
 * OptoFileTools — file ops scoped to app-private storage.
 *
 * Security notes:
 *  - We only allow relative paths under the app's files dir.
 *  - We refuse any path that contains ".." or starts with "/".
 *  - All files live under /data/data/com.optobot/files/opto_notes/
 *  - Nothing on shared storage, no MediaStore, no SAF. Patient PII stays
 *    inside the app sandbox and is removed when the app is uninstalled.
 *
 * Tools are gated by userStore.toolsGranted (set during onboarding).
 * The RN bridge checks `toolsGranted()` before exposing any tool method.
 */
class OptoFileTools(private val context: Context) {

    private val root: File by lazy {
        File(context.filesDir, "opto_notes").apply { if (!exists()) mkdirs() }
    }

    /** Resolve and validate a relative path under root. Throws on traversal attempts. */
    private fun resolve(relPath: String): File {
        require(relPath.isNotBlank()) { "path is empty" }
        require(!relPath.startsWith("/")) { "absolute paths not allowed" }
        require(!relPath.contains("..")) { "traversal not allowed" }
        val parts = relPath.split("/").filter { it.isNotEmpty() }
        var cur = root
        for (p in parts.dropLast(1)) {
            cur = File(cur, p).apply { if (!exists()) mkdirs() }
        }
        return File(cur, parts.last())
    }

    data class Entry(val name: String, val size: Long, val modifiedAt: Long, val isDir: Boolean)

    fun list(subdir: String = ""): List<Entry> {
        val target = if (subdir.isBlank()) root else resolve(subdir)
        if (!target.exists() || !target.isDirectory) return emptyList()
        return target.listFiles().orEmpty()
            .sortedByDescending { it.lastModified() }
            .map {
                Entry(
                    name = it.relativeTo(root).path,
                    size = if (it.isDirectory) 0L else it.length(),
                    modifiedAt = it.lastModified(),
                    isDir = it.isDirectory
                )
            }
    }

    fun read(relPath: String): String {
        val f = resolve(relPath)
        if (!f.exists()) throw IllegalArgumentException("file not found: $relPath")
        return f.readText()
    }

    fun write(relPath: String, content: String) {
        val f = resolve(relPath)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    fun delete(relPath: String) {
        val f = resolve(relPath)
        if (f.exists()) {
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
    }

    fun mkdir(relPath: String) {
        val f = resolve(relPath)
        if (!f.exists()) f.mkdirs()
    }

    companion object { private const val TAG = "OptoFileTools" }
}
