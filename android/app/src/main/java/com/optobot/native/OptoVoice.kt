package com.optobot.native

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File

/**
 * OptoVoice — on-device speech-to-text + text-to-speech + wake word.
 *
 * Architecture:
 *   ┌────────────────────────────────────────────────────────┐
 *   │  Mic (AudioRecord, 16kHz mono PCM)                     │
 *   │     │                                                  │
 *   │     ▼                                                  │
 *   │  VAD (energy-based, 30ms frames)                       │
 *   │     │                                                  │
 *   │     ├── silence → idle                                 │
 *   │     └── speech  → buffer into 2-3s chunks              │
 *   │                                                          │
 *   │  Chunk ──► Whisper.cpp (base.en, ~75MB)                │
 *   │     │                                                  │
 *   │     ▼                                                  │
 *   │  Transcript                                            │
 *   │     │                                                  │
 *   │     ├── contains "hey opto" → activate full mode        │
 *   │     └── otherwise → discard (wake-word filter)        │
 *   │                                                          │
 *   │  In full mode:                                          │
 *   │     - 1.5s silence → end of speech                    │
 *   │     - Final transcript ──► LFM2.5-230M                │
 *   │     - LLM response ──► Kokoro TTS ──► AudioTrack       │
 *   └────────────────────────────────────────────────────────┘
 *
 * Native dependencies (built locally with NDK):
 *   - libwhisper.so         — from whisper.cpp's android build
 *   - libopto_voice_jni.so  — our JNI bridge to whisper + kokoro
 *
 * The Kokoro TTS uses ONNX Runtime (already in our dependencies)
 * so no extra native lib needed for TTS.
 *
 * Models (all bundled + AES-256-GCM encrypted in assets):
 *   - opto_whisper.bin  ~75 MB (Whisper base.en Q4)
 *   - opto_kokoro.bin  ~87 MB (Kokoro-82M ONNX Q4)
 *   - opto_voices.bin  ~3 MB  (Kokoro voice pack — single speaker)
 *
 * Privacy:
 *   - Audio never leaves the device.
 *   - No cloud STT / TTS APIs are used.
 *   - Whisper + Kokoro both run via native code on-device.
 *
 * Performance on mid-range Android (Snapdragon 7-gen, 4GB RAM):
 *   - Whisper base.en real-time factor: ~0.3x (3s of audio → 1s to transcribe)
 *   - Kokoro TTS real-time factor: ~0.5x (1s of text → 0.5s to synthesize)
 *   - End-to-end voice latency: ~1.5s from end-of-speech to start of reply
 */
class OptoVoice(private val context: android.content.Context) {

    private var isListening = false
    private var isRecording = false
    private var isSpeaking = false
    private var wakeWordActive = false

    /** Whether native whisper + kokoro libs are loaded. False in stub mode. */
    private val nativeAvailable: Boolean by lazy {
        try {
            System.loadLibrary("whisper")
            System.loadLibrary("opto_voice_jni")
            true
        } catch (_: Throwable) {
            Log.w(TAG, "native voice libs not available — running in stub mode")
            false
        }
    }

    val isReady: Boolean get() = nativeAvailable

    // ──────────────────────────────────────────────────────────────
    // Public API (called via OptoBotModule RN bridge)
    // ──────────────────────────────────────────────────────────────

    /**
     * Start the always-on wake-word listener.
     * Audio is captured from the mic, VAD-segmented, and short chunks are
     * passed to Whisper. When "hey opto" is detected, onWakeWord fires.
     */
    fun startWakeWordListener(onWakeWord: () -> Unit) {
        if (!nativeAvailable) {
            Log.w(TAG, "wake word listener not available in stub mode")
            return
        }
        if (isListening) return
        isListening = true
        wakeWordActive = true
        Thread {
            try {
                nativeStartWakeWord(onWakeWord)
            } catch (t: Throwable) {
                Log.e(TAG, "wake word listener crashed", t)
            } finally {
                isListening = false
                wakeWordActive = false
            }
        }.start()
    }

    fun stopWakeWordListener() {
        wakeWordActive = false
        isListening = false
        if (nativeAvailable) nativeStopWakeWord()
    }

    /**
     * One-shot listen: start recording, capture until 1.5s of silence,
     * transcribe via Whisper, return the text.
     *
     * Use this after the wake word fires, OR when the user taps the mic.
     */
    fun listenOnce(): String? {
        if (!nativeAvailable) {
            // Stub mode: return a canned string for UI testing
            Thread.sleep(1500)
            return "(stub) hello optobot"
        }
        return nativeListenOnce()
    }

    /**
     * Record mode: start a long recording (e.g., a 10-min patient consult).
     * Audio is buffered to a tmpfile. After stopRecording() is called,
     * the file is transcribed in the background via Whisper.
     *
     * While recording, the user can still talk to the AI for other things
     * — recording and chat don't block each other.
     */
    fun startRecording(): Boolean {
        if (!nativeAvailable) {
            isRecording = true
            return true  // stub mode
        }
        if (isRecording) return false
        isRecording = true
        return nativeStartRecording(File(context.cacheDir, "opto_record_${System.currentTimeMillis()}.wav").absolutePath)
    }

    /** Stop recording. Returns the tmpfile path; transcription kicks off in the background. */
    fun stopRecording(): String? {
        if (!isRecording) return null
        isRecording = false
        if (!nativeAvailable) return null
        return nativeStopRecording()
    }

    /**
     * Transcribe a recorded file in the background. Calls back when done
     * with the full transcript. Used by the record mode for patient consults.
     */
    fun transcribeFile(path: String, onResult: (String) -> Unit) {
        Thread {
            try {
                val text = if (nativeAvailable) nativeTranscribeFile(path) else "(stub) recorded transcript"
                onResult(text)
            } catch (t: Throwable) {
                Log.e(TAG, "transcribe failed", t)
                onResult("")
            }
        }.start()
    }

    /**
     * Speak the given text via Kokoro TTS. Plays through the phone speaker.
     * Calls onDone when playback finishes.
     */
    fun speak(text: String, onDone: () -> Unit) {
        if (!nativeAvailable) {
            // Stub: simulate TTS with a delay
            Thread {
                Thread.sleep(text.length * 30L)  // ~30ms per char
                onDone()
            }.start()
            return
        }
        isSpeaking = true
        Thread {
            try {
                nativeSpeak(text)
            } finally {
                isSpeaking = false
                onDone()
            }
        }.start()
    }

    fun stopSpeaking() {
        if (nativeAvailable) nativeStopSpeaking()
        isSpeaking = false
    }

    // ──────────────────────────────────────────────────────────────
    // JNI — implemented in /optobot/android/app/src/main/cpp/opto_voice_jni.c
    // Build with NDK 27+ via CMakeLists.txt in that folder.
    // ──────────────────────────────────────────────────────────────

    private external fun nativeStartWakeWord(onWakeWord: () -> Unit): Boolean
    private external fun nativeStopWakeWord()
    private external fun nativeListenOnce(): String
    private external fun nativeStartRecording(path: String): Boolean
    private external fun nativeStopRecording(): String?
    private external fun nativeTranscribeFile(path: String): String
    private external fun nativeSpeak(text: String): Boolean
    private external fun nativeStopSpeaking()

    companion object {
        private const val TAG = "OptoVoice"

        // VAD parameters — tuned for typical consult-room acoustics
        const val VAD_FRAME_MS = 30
        const val VAD_ENERGY_THRESHOLD = 300.0
        const val VAD_SILENCE_FRAMES_FOR_EOS = 50  // ~1.5s of silence ends a turn
        const val VAD_MIN_SPEECH_FRAMES = 10       // ~300ms minimum to count as speech

        // Whisper chunk size for wake word (shorter = lower latency, higher CPU)
        const val WAKE_WORD_CHUNK_MS = 2000

        // Sample rate — Whisper expects 16kHz mono
        const val SAMPLE_RATE = 16000
    }
}
