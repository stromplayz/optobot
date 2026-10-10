package com.optobot.native

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * OptoVoice — on-device STT + TTS using Whisper + Kokoro.
 *
 * STT: OptoWhisper (Whisper base.en ONNX Q4)
 * TTS: OptoKokoro (Kokoro-82M ONNX Q4)
 *
 * Both run on ONNX Runtime — no native libs, no NDK build, no API keys.
 * All models are pre-bundled + encrypted in the APK.
 *
 * Wake word: implemented as a lightweight VAD loop that records 2-second
 * chunks and passes them to Whisper. When "hey opto" is detected in the
 * transcript, the callback fires.
 *
 * Record mode: continuous recording to a buffer, transcribed via Whisper
 * when stopped.
 */
class OptoVoice(private val context: Context) {

    private val whisper = OptoWhisper(context)
    private val kokoro = OptoKokoro(context)

    @Volatile private var wakeWordActive = false
    @Volatile private var isListening = false
    @Volatile private var isRecording = false
    private val recordBuffer = StringBuilder()

    val isReady: Boolean get() = whisper.isReady && kokoro.isReady

    /** Load both Whisper and Kokoro models in background. Non-blocking. */
    fun init(onReady: () -> Unit) {
        Thread {
            try {
                // Load Whisper first (smaller, needed for STT)
                whisper.load()
                if (whisper.isReady) {
                    Log.i(TAG, "Whisper loaded OK")
                } else {
                    Log.w(TAG, "Whisper not ready — voice input disabled")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Whisper load failed", t)
            }
            try {
                // Load Kokoro (for TTS)
                kokoro.load()
                if (kokoro.isReady) {
                    Log.i(TAG, "Kokoro loaded OK")
                } else {
                    Log.w(TAG, "Kokoro not ready — voice output disabled")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Kokoro load failed", t)
            }
            onReady()
        }.start()
    }

    // ─── One-shot listen (STT via Whisper) ───

    /**
     * Start listening for a single utterance. Records audio with VAD,
     * transcribes via Whisper, returns text.
     *
     * If Whisper isn't loaded, falls back to a simple "no speech" result
     * instead of crashing.
     */
    fun listenOnce(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!whisper.isReady) {
            // Don't crash — just report that STT isn't available
            Log.w(TAG, "listenOnce: Whisper not ready, returning empty")
            onError("Voice recognition not available")
            return
        }
        isListening = true
        whisper.listenOnce(
            onResult = { text ->
                isListening = false
                onResult(text)
            },
            onError = { msg ->
                isListening = false
                onError(msg)
            }
        )
    }

    fun stopListening() {
        isListening = false
    }

    // ─── TTS via Kokoro ───

    fun speak(text: String, onDone: () -> Unit) {
        if (!kokoro.isReady) {
            Log.w(TAG, "Kokoro not ready, skipping speak")
            onDone()
            return
        }
        kokoro.speak(text, onDone)
    }

    fun stopSpeaking() {
        kokoro.stopSpeaking()
    }

    // ─── Wake word (Whisper-based) ───

    fun startWakeWordListener(onWakeWord: () -> Unit) {
        if (!whisper.isReady) {
            Log.w(TAG, "Whisper not ready, wake word disabled")
            return
        }
        wakeWordActive = true
        Thread {
            while (wakeWordActive) {
                try {
                    Thread.sleep(1000)
                    if (!wakeWordActive || isListening || isRecording) continue
                    // Record a 2-second burst and transcribe
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var heard = ""
                    whisper.listenOnce(
                        onResult = { text ->
                            heard = text
                            latch.countDown()
                        },
                        onError = { latch.countDown() }
                    )
                    latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
                    if (wakeWordActive) {
                        val h = heard.lowercase()
                        if (h.contains("hey opto") || h.contains("hey opta") || h.contains("hey obo") || h.contains("hi opto")) {
                            onWakeWord()
                        }
                    }
                } catch (_: Throwable) {
                    Thread.sleep(2000)
                }
            }
        }.start()
    }

    fun stopWakeWordListener() {
        wakeWordActive = false
    }

    // ─── Record mode (for patient consults) ───

    fun startRecording(): Boolean {
        if (!whisper.isReady) return false
        isRecording = true
        recordBuffer.clear()
        // Record in 30s chunks, transcribe each chunk, append to buffer
        Thread {
            while (isRecording) {
                val latch = java.util.concurrent.CountDownLatch(1)
                whisper.listenOnce(
                    onResult = { text ->
                        if (text.isNotBlank()) {
                            recordBuffer.append(text).append(" ")
                        }
                        latch.countDown()
                    },
                    onError = { latch.countDown() }
                )
                try { latch.await(40, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
            }
        }.start()
        return true
    }

    fun stopRecording(): String {
        isRecording = false
        return recordBuffer.toString().trim()
    }

    // ─── Cleanup ───

    fun release() {
        stopWakeWordListener()
        stopListening()
        stopSpeaking()
        whisper.release()
        kokoro.release()
    }

    companion object {
        private const val TAG = "OptoVoice"
    }
}
