package com.optobot.native

import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.util.concurrent.atomic.AtomicLong

/**
 * OptoBotModule — React Native bridge to the native inference engine.
 *
 * Surface exposed to TypeScript (see src/native/OptoBotNative.ts):
 *   load()                          → Promise<void>
 *   isReady()                       → Promise<boolean>
 *   chat(prompt, systemPrompt?)    → Promise<number>  (events stream the reply)
 *   stop()                          → void
 *   release()                       → void
 *   listFiles(subdir?)              → Promise<FileEntry[]>
 *   readFile(relPath)               → Promise<string>
 *   writeFile(relPath, content)     → Promise<void>
 *   deleteFile(relPath)             → Promise<void>
 *   mkdir(relPath)                  → Promise<void>
 *   toolsGranted()                  → Promise<boolean>
 *
 * Events emitted to JS:
 *   "opto:loadProgress"  { progress: number }
 *   "opto:token"         { piece: string, full: string }
 *   "opto:done"          { full: string }
 *   "opto:error"         { message: string }
 *
 * Notes:
 *  - We use one engine per app instance (singleton-ish).
 *  - We forward engine lifecycle to the listener; no per-call allocation.
 */
class OptoBotModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val engine = OptoInferenceEngine(reactContext)
    private val fileTools = OptoFileTools(reactContext)
    private val callCounter = AtomicLong(0)

    override fun getName(): String = "OptoBot"

    override fun getConstants(): Map<String, Any> = mapOf(
        "EVENT_LOAD_PROGRESS" to OptoConfig.EVENT_LOAD_PROGRESS,
        "EVENT_TOKEN" to OptoConfig.EVENT_TOKEN,
        "EVENT_DONE" to OptoConfig.EVENT_DONE,
        "EVENT_ERROR" to OptoConfig.EVENT_ERROR,
        "MAX_CONTEXT_TOKENS" to OptoConfig.MAX_CONTEXT_TOKENS,
        "MAX_NEW_TOKENS" to OptoConfig.MAX_NEW_TOKENS
    )

    @ReactMethod
    fun load(promise: Promise) {
        val ctx = reactApplicationContext
        val listener = buildListener(ctx)
        Thread {
            try {
                engine.load(listener)
                promise.resolve(null)
            } catch (t: Throwable) {
                promise.reject("OPTO_LOAD_FAIL", t.message, t)
            }
        }.start()
    }

    @ReactMethod
    fun isReady(promise: Promise) {
        promise.resolve(engine.isReady)
    }

    @ReactMethod
    fun chat(prompt: String, systemPrompt: String?, promise: Promise) {
        val ctx = reactApplicationContext
        val listener = buildListener(ctx)
        val id = callCounter.incrementAndGet()
        try {
            engine.chat(prompt, systemPrompt, listener)
            promise.resolve(id)
        } catch (t: Throwable) {
            promise.reject("OPTO_CHAT_FAIL", t.message, t)
        }
    }

    // ---- File tools ----

    @ReactMethod
    fun listFiles(subdir: String?, promise: Promise) {
        Thread {
            try {
                val entries = fileTools.list(subdir ?: "")
                val arr = Arguments.createArray()
                for (e in entries) {
                    arr.pushMap(Arguments.createMap().apply {
                        putString("name", e.name)
                        putDouble("size", e.size.toDouble())
                        putDouble("modifiedAt", e.modifiedAt.toDouble())
                        putBoolean("isDir", e.isDir)
                    })
                }
                promise.resolve(arr)
            } catch (t: Throwable) {
                promise.reject("OPTO_FS_FAIL", t.message, t)
            }
        }.start()
    }

    @ReactMethod
    fun readFile(relPath: String, promise: Promise) {
        Thread {
            try {
                promise.resolve(fileTools.read(relPath))
            } catch (t: Throwable) {
                promise.reject("OPTO_FS_FAIL", t.message, t)
            }
        }.start()
    }

    @ReactMethod
    fun writeFile(relPath: String, content: String, promise: Promise) {
        Thread {
            try {
                fileTools.write(relPath, content)
                promise.resolve(null)
            } catch (t: Throwable) {
                promise.reject("OPTO_FS_FAIL", t.message, t)
            }
        }.start()
    }

    @ReactMethod
    fun deleteFile(relPath: String, promise: Promise) {
        Thread {
            try {
                fileTools.delete(relPath)
                promise.resolve(null)
            } catch (t: Throwable) {
                promise.reject("OPTO_FS_FAIL", t.message, t)
            }
        }.start()
    }

    @ReactMethod
    fun mkdir(relPath: String, promise: Promise) {
        Thread {
            try {
                fileTools.mkdir(relPath)
                promise.resolve(null)
            } catch (t: Throwable) {
                promise.reject("OPTO_FS_FAIL", t.message, t)
            }
        }.start()
    }

    @ReactMethod
    fun toolsGranted(promise: Promise) {
        // OS-level: sandboxed storage is always available.
        // User-level consent is enforced by the TS onboarding screen.
        promise.resolve(true)
    }

    @ReactMethod
    fun stop() {
        engine.stop()
    }

    @ReactMethod
    fun release() {
        engine.release()
    }

    // ─────────────────────────────────────────────────────────────────
    // OptoReach — on-device port of agent-reach HTTP channels.
    // All calls are synchronous on a background thread. Each returns
    // a ReadableMap: { ok: boolean, content: string, source: string, error: string }
    // ─────────────────────────────────────────────────────────────────

    private fun resolveReach(r: OptoReach.ReachResult, promise: Promise) {
        val map = Arguments.createMap()
        when (r) {
            is OptoReach.ReachResult.Ok -> {
                map.putBoolean("ok", true)
                map.putString("content", r.content)
                map.putString("source", r.source)
            }
            is OptoReach.ReachResult.Error -> {
                map.putBoolean("ok", false)
                map.putString("error", r.message)
            }
        }
        promise.resolve(map)
    }

    @ReactMethod
    fun reachWeb(url: String, promise: Promise) {
        Thread {
            try { resolveReach(OptoReach.web(url), promise) }
            catch (t: Throwable) { promise.reject("OPTO_REACH_FAIL", t.message, t) }
        }.start()
    }

    @ReactMethod
    fun reachSearch(query: String, promise: Promise) {
        Thread {
            try { resolveReach(OptoReach.search(query), promise) }
            catch (t: Throwable) { promise.reject("OPTO_REACH_FAIL", t.message, t) }
        }.start()
    }

    @ReactMethod
    fun reachYouTube(input: String, promise: Promise) {
        Thread {
            try { resolveReach(OptoReach.youtube(input), promise) }
            catch (t: Throwable) { promise.reject("OPTO_REACH_FAIL", t.message, t) }
        }.start()
    }

    @ReactMethod
    fun reachRSS(url: String, promise: Promise) {
        Thread {
            try { resolveReach(OptoReach.rss(url), promise) }
            catch (t: Throwable) { promise.reject("OPTO_REACH_FAIL", t.message, t) }
        }.start()
    }

    @ReactMethod
    fun reachGitHub(repoInput: String, promise: Promise) {
        Thread {
            try { resolveReach(OptoReach.github(repoInput), promise) }
            catch (t: Throwable) { promise.reject("OPTO_REACH_FAIL", t.message, t) }
        }.start()
    }

    @ReactMethod
    fun reachWiki(query: String, promise: Promise) {
        Thread {
            try { resolveReach(OptoReach.wiki(query), promise) }
            catch (t: Throwable) { promise.reject("OPTO_REACH_FAIL", t.message, t) }
        }.start()
    }

    @ReactMethod
    fun reachArxiv(query: String, promise: Promise) {
        Thread {
            try { resolveReach(OptoReach.arxiv(query), promise) }
            catch (t: Throwable) { promise.reject("OPTO_REACH_FAIL", t.message, t) }
        }.start()
    }

    @ReactMethod
    fun reachBooks(query: String, promise: Promise) {
        Thread {
            try { resolveReach(OptoReach.books(query), promise) }
            catch (t: Throwable) { promise.reject("OPTO_REACH_FAIL", t.message, t) }
        }.start()
    }

    @ReactMethod
    fun addListener(eventName: String) { /* no-op, required for emit subscription */ }

    @ReactMethod
    fun removeListeners(count: Int) { /* no-op */ }

    private fun buildListener(ctx: ReactApplicationContext) = object : OptoInferenceEngine.Listener {
        override fun onLoadProgress(p: Float) {
            emit(ctx, OptoConfig.EVENT_LOAD_PROGRESS, Arguments.createMap().apply {
                putDouble("progress", p.toDouble())
            })
        }
        override fun onToken(token: String, fullText: String) {
            emit(ctx, OptoConfig.EVENT_TOKEN, Arguments.createMap().apply {
                putString("piece", token)
                putString("full", fullText)
            })
        }
        override fun onComplete(fullText: String) {
            emit(ctx, OptoConfig.EVENT_DONE, Arguments.createMap().apply {
                putString("full", fullText)
            })
        }
        override fun onError(message: String) {
            Log.e(TAG, "engine error: $message")
            emit(ctx, OptoConfig.EVENT_ERROR, Arguments.createMap().apply {
                putString("message", message)
            })
        }
    }

    private fun emit(ctx: ReactContext, name: String, body: ReadableMap) {
        ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            ?.emit(name, body)
    }

    companion object { private const val TAG = "OptoBotModule" }
}
