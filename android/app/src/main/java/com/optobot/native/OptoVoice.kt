package com.optobot.native

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * OptoVoice — on-device STT + TTS using Android's built-in engines.
 *
 * STT: android.speech.SpeechRecognizer
 *   - Free, no API key
 *   - On modern Android (12+), uses on-device recognition
 *   - Older versions may use Google's cloud recognizer (still free)
 *
 * TTS: android.speech.tts.TextToSpeech
 *   - Free, no API key
 *   - Uses the device's installed TTS engine (Google TTS, Samsung TTS, etc.)
 *   - Works offline on most modern devices
 *
 * No native libs, no NDK build, no whisper.cpp, no Kokoro — just the
 * Android platform APIs. This means the APK works on every Android
 * device without any extra dependencies.
 *
 * Wake word: not built-in to Android. We approximate it by running
 * SpeechRecognizer in a loop and checking if the transcript contains
 * "hey opto". This is battery-intensive, so we only do it when the
 * app is in the foreground.
 */
class OptoVoice(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var ttsReady = false
    private var isListening = false
    private var isRecording = false

    @Volatile private var wakeWordCallback: (() -> Unit)? = null
    @Volatile private var wakeWordActive = false

    val isReady: Boolean get() = ttsReady

    /** Initialize TTS. Call once on app start. */
    fun init(onReady: () -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
                Log.i(TAG, "TTS ready")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
            onReady()
        }
    }

    // ─── One-shot listen (STT) ───

    /**
     * Start listening for a single utterance. Returns the transcript
     * via the callback. Uses Android's SpeechRecognizer.
     */
    fun listenOnce(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available on this device")
            return
        }

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "no speech detected"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "audio error"
                    else -> "recognition error: $error"
                }
                isListening = false
                onError(msg)
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                Log.i(TAG, "STT result: $text")
                onResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        isListening = true
        recognizer?.setRecognitionListener(listener)
        recognizer?.startListening(intent)
    }

    fun stopListening() {
        isListening = false
        recognizer?.stopListening()
    }

    // ─── TTS ───

    /** Speak text via Android's built-in TTS engine. */
    fun speak(text: String, onDone: () -> Unit) {
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready, skipping speak")
            onDone()
            return
        }
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onDone() }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "opto_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    // ─── Wake word (simplified) ───

    /**
     * Start a wake-word loop. Periodically listens for "hey opto".
     * This is a simplified version — real wake word detection would
     * need a dedicated model (like openWakeWord or Porcupine).
     */
    fun startWakeWordListener(onWakeWord: () -> Unit) {
        wakeWordCallback = onWakeWord
        wakeWordActive = true
        Thread {
            while (wakeWordActive) {
                try {
                    Thread.sleep(2000)
                    if (!wakeWordActive) break
                    // Quick listen burst
                    if (!isListening && !isRecording) {
                        val triggered = arrayOf(false)
                        listenOnce(
                            onResult = { text ->
                                if (text.lowercase().contains("hey opto") || text.lowercase().contains("hey opta") || text.lowercase().contains("hey obo")) {
                                    triggered[0] = true
                                    onWakeWord()
                                }
                            },
                            onError = { /* ignore, retry next cycle */ }
                        )
                        Thread.sleep(5000)  // wait for listen to complete
                    }
                } catch (_: Throwable) {
                    Thread.sleep(3000)
                }
            }
        }.start()
    }

    fun stopWakeWordListener() {
        wakeWordActive = false
        wakeWordCallback = null
    }

    // ─── Record mode (for patient consults) ───

    /**
     * Start recording. Uses SpeechRecognizer in continuous mode —
     * collects transcript chunks until stopRecording is called.
     */
    private val recordBuffer = StringBuilder()

    fun startRecording(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return false
        isRecording = true
        recordBuffer.clear()
        // Start a loop of listen bursts
        Thread {
            while (isRecording) {
                val gotResult = java.util.concurrent.CountDownLatch(1)
                listenOnce(
                    onResult = { text ->
                        recordBuffer.append(text).append(" ")
                        gotResult.countDown()
                    },
                    onError = { gotResult.countDown() }
                )
                try { gotResult.await(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
            }
        }.start()
        return true
    }

    fun stopRecording(): String {
        isRecording = false
        stopListening()
        return recordBuffer.toString()
    }

    // ─── Cleanup ───

    fun release() {
        stopWakeWordListener()
        stopListening()
        stopSpeaking()
        try { recognizer?.destroy() } catch (_: Throwable) {}
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        recognizer = null
        tts = null
    }

    companion object {
        private const val TAG = "OptoVoice"
    }
}
