package com.optobot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.optobot.native.OptoInferenceEngine
import com.optobot.native.OptoVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var voice: OptoVoice? = null
    private var engine: OptoInferenceEngine? = null
    private var hasAudioPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check mic permission
        hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Do NOT initialize models here — that would load 652MB of models
        // on the main thread and crash. We initialize lazily when the user taps.
        try {
            voice = OptoVoice(applicationContext)
            engine = OptoInferenceEngine(applicationContext)
        } catch (t: Throwable) {
            // If construction fails, we still show the UI — just nothing works
            android.util.Log.e("OptoBot", "Model init construction failed", t)
        }

        setContent {
            OptoBotTheme {
                VoiceScreen(
                    voice = voice,
                    engine = engine,
                    hasAudioPermission = hasAudioPermission,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { voice?.release() } catch (_: Throwable) {}
        try { engine?.release() } catch (_: Throwable) {}
    }
}

@Composable
private fun OptoBotTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFE85D2F),
        onPrimary = Color.White,
        background = Color(0xFFFAF7F2),
        onBackground = Color(0xFF1F1A14),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1F1A14),
        secondary = Color(0xFFF2EDE5),
        onSecondary = Color(0xFF6B5D4C),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

enum class VoiceState { IDLE, LOADING, LISTENING, THINKING, SPEAKING, RECORDING, ERROR }

@Composable
private fun VoiceScreen(
    voice: OptoVoice?,
    engine: OptoInferenceEngine?,
    hasAudioPermission: Boolean,
) {
    var state by remember { mutableStateOf(VoiceState.IDLE) }
    var userTranscript by remember { mutableStateOf("") }
    var aiText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Tap to start") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var llmLoaded by remember { mutableStateOf(false) }
    var voiceLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Load ONLY the LLM on first tap, NOT on app start
    // Voice models load when user actually taps to speak

    // This is the tap handler — everything runs on background threads
    fun handleTap() {
        if (state != VoiceState.IDLE) return
        if (engine == null || voice == null) {
            errorMessage = "Models not initialized"
            state = VoiceState.ERROR
            statusMessage = "Error: models unavailable"
            return
        }

        scope.launch(Dispatchers.Main) {
            try {
                // Step 1: Load the LLM if not already loaded
                if (!llmLoaded) {
                    state = VoiceState.LOADING
                    statusMessage = "Loading AI model..."
                    withContext(Dispatchers.IO) {
                        engine.load(object : OptoInferenceEngine.Listener {
                            override fun onLoadProgress(p: Float) {
                                statusMessage = "Loading AI model ${Math.round(p * 100)}%"
                            }
                            override fun onToken(token: String, fullText: String) {}
                            override fun onComplete(fullText: String) {}
                            override fun onError(message: String) {}
                        })
                    }
                    llmLoaded = engine.isReady
                    if (!llmLoaded) {
                        errorMessage = "AI model failed to load"
                        state = VoiceState.ERROR
                        statusMessage = "Model load failed. Tap to retry."
                        return@launch
                    }
                }

                // Step 2: Load voice models if not already loaded
                if (!voiceLoaded) {
                    state = VoiceState.LOADING
                    statusMessage = "Loading voice models..."
                    withContext(Dispatchers.IO) {
                        voice.init { /* ready */ }
                        delay(500) // give init a moment
                    }
                    voiceLoaded = voice.isReady
                }

                // Step 3: Start listening
                state = VoiceState.LISTENING
                statusMessage = "Listening... (speak now)"

                withContext(Dispatchers.IO) {
                    voice.listenOnce(
                        onResult = { text ->
                            scope.launch(Dispatchers.Main) {
                                if (text.isBlank()) {
                                    state = VoiceState.IDLE
                                    statusMessage = "Didn't catch that. Tap to try again."
                                    return@launch
                                }
                                userTranscript = text
                                state = VoiceState.THINKING
                                statusMessage = "Thinking..."

                                // Send to LLM
                                engine.chat(text, null, object : OptoInferenceEngine.Listener {
                                    override fun onLoadProgress(p: Float) {}
                                    override fun onToken(token: String, fullText: String) {
                                        aiText = fullText
                                    }
                                    override fun onComplete(fullText: String) {
                                        aiText = fullText
                                        if (fullText.isNotBlank() && voiceLoaded) {
                                            state = VoiceState.SPEAKING
                                            statusMessage = "Speaking..."
                                            voice.speak(fullText) {
                                                scope.launch(Dispatchers.Main) {
                                                    state = VoiceState.IDLE
                                                    statusMessage = "Tap to talk"
                                                }
                                            }
                                        } else {
                                            state = VoiceState.IDLE
                                            statusMessage = "Tap to talk"
                                        }
                                    }
                                    override fun onError(message: String) {
                                        scope.launch(Dispatchers.Main) {
                                            errorMessage = message
                                            state = VoiceState.ERROR
                                            statusMessage = "Error: $message"
                                        }
                                    }
                                })
                            }
                        },
                        onError = { msg ->
                            scope.launch(Dispatchers.Main) {
                                state = VoiceState.IDLE
                                statusMessage = "Mic error. Tap to retry."
                            }
                        }
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.e("OptoBot", "Tap handler crashed", t)
                errorMessage = t.message ?: "Unknown error"
                state = VoiceState.ERROR
                statusMessage = "Error: ${t.message?.take(50)}"
            }
        }
    }

    fun handleLongPress() {
        scope.launch(Dispatchers.Main) {
            try {
                if (state == VoiceState.RECORDING) {
                    val transcript = voice?.stopRecording() ?: ""
                    if (transcript.isNotBlank()) {
                        userTranscript = "Recording: $transcript"
                        state = VoiceState.THINKING
                        statusMessage = "Thinking..."
                        withContext(Dispatchers.IO) {
                            engine?.chat(transcript, null, object : OptoInferenceEngine.Listener {
                                override fun onLoadProgress(p: Float) {}
                                override fun onToken(token: String, fullText: String) {
                                    aiText = fullText
                                }
                                override fun onComplete(fullText: String) {
                                    aiText = fullText
                                    scope.launch(Dispatchers.Main) {
                                        if (fullText.isNotBlank() && voiceLoaded) {
                                            state = VoiceState.SPEAKING
                                            statusMessage = "Speaking..."
                                            voice?.speak(fullText) {
                                                scope.launch(Dispatchers.Main) {
                                                    state = VoiceState.IDLE
                                                    statusMessage = "Tap to talk"
                                                }
                                            }
                                        } else {
                                            state = VoiceState.IDLE
                                            statusMessage = "Tap to talk"
                                        }
                                    }
                                }
                                override fun onError(message: String) {
                                    scope.launch(Dispatchers.Main) {
                                        state = VoiceState.ERROR
                                        statusMessage = "Error: $message"
                                    }
                                }
                            })
                        }
                    } else {
                        state = VoiceState.IDLE
                        statusMessage = "Tap to talk"
                    }
                } else if (state == VoiceState.IDLE || state == VoiceState.LISTENING) {
                    if (!hasAudioPermission) {
                        statusMessage = "Microphone permission needed"
                        return@launch
                    }
                    state = VoiceState.RECORDING
                    statusMessage = "Recording..."
                    withContext(Dispatchers.IO) {
                        voice?.startRecording()
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("OptoBot", "Long press handler crashed", t)
                state = VoiceState.ERROR
                statusMessage = "Error: ${t.message?.take(50)}"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAF7F2))
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { handleTap() },
                    onLongPress = { handleLongPress() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text("OptoBot", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1A14))
            Spacer(modifier = Modifier.height(8.dp))

            AIEye(state = state, modifier = Modifier.size(240.dp))
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                statusMessage,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1F1A14),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = when (state) {
                    VoiceState.IDLE -> if (llmLoaded) "Tap to talk • Long-press to record" else "Tap to load models"
                    VoiceState.LOADING -> "Loading on-device models..."
                    VoiceState.LISTENING -> "Speak now"
                    VoiceState.THINKING -> "On-device LFM2.5-230M generating"
                    VoiceState.SPEAKING -> "Tap to interrupt"
                    VoiceState.RECORDING -> "Long-press to stop"
                    VoiceState.ERROR -> "Tap to retry"
                },
                fontSize = 13.sp,
                color = Color(0xFF6B5D4C),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Transcript
            if (userTranscript.isNotBlank()) {
                Surface(color = Color(0xFFF2EDE5), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("You: ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE85D2F))
                        Text(userTranscript, fontSize = 15.sp, color = Color(0xFF1F1A14))
                        if (aiText.isNotBlank() && (state == VoiceState.THINKING || state == VoiceState.SPEAKING)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("OptoBot: ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE85D2F))
                            Text(aiText, fontSize = 15.sp, color = Color(0xFF1F1A14))
                        }
                    }
                }
            }

            // AI response (after completion)
            if (aiText.isNotBlank() && state == VoiceState.IDLE) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(color = Color(0xFFFFFFFF), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("OptoBot: ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE85D2F))
                        Text(aiText, fontSize = 15.sp, color = Color(0xFF1F1A14))
                    }
                }
            }

            // Error display
            if (state == VoiceState.ERROR && errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(color = Color(0xFFFFE0E0), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Error: ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC24A2F))
                        Text(errorMessage ?: "", fontSize = 14.sp, color = Color(0xFF1F1A14))
                    }
                }
            }
        }
    }
}

// ─── AI Eye ───

@Composable
private fun AIEye(state: VoiceState, modifier: Modifier = Modifier) {
    val accent = Color(0xFFE85D2F)
    val danger = Color(0xFFC24A2F)
    val eyeColor = when (state) {
        VoiceState.RECORDING -> danger
        VoiceState.ERROR -> Color(0xFFC24A2F)
        VoiceState.LOADING -> Color(0xFFC57A1E)
        else -> accent
    }

    val infiniteTransition = rememberInfiniteTransition(label = "eye")
    val blink by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == VoiceState.IDLE) 0.05f else if (state == VoiceState.THINKING || state == VoiceState.LOADING) 0.18f else 0.85f,
        animationSpec = infiniteRepeatable(tween(if (state == VoiceState.IDLE) 2500 else 800, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "blink",
    )
    val glow by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = when (state) {
            VoiceState.LISTENING -> 0.9f
            VoiceState.RECORDING -> 0.85f
            VoiceState.SPEAKING -> 0.75f
            VoiceState.THINKING -> 0.55f
            VoiceState.LOADING -> 0.5f
            VoiceState.ERROR -> 0.3f
            VoiceState.IDLE -> 0.25f
        },
        animationSpec = infiniteRepeatable(tween(500, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "glow",
    )
    val spin by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "spin",
    )

    Canvas(modifier = modifier) {
        val canvasSize = this.size.minDimension
        val eyeRadius = canvasSize * 0.35f

        drawCircle(color = eyeColor.copy(alpha = glow * 0.3f), radius = eyeRadius * 1.4f, center = this.center)

        if (state == VoiceState.THINKING || state == VoiceState.LOADING) {
            for (i in 0..5) {
                val angle = (spin + i * 60) * (Math.PI.toFloat() / 180f)
                drawCircle(
                    color = accent, radius = 5f,
                    center = Offset(
                        this.center.x + eyeRadius * 1.2f * kotlin.math.cos(angle),
                        this.center.y + eyeRadius * 1.2f * kotlin.math.sin(angle),
                    ),
                )
            }
        }

        drawCircle(color = Color.White, radius = eyeRadius, center = this.center)
        drawCircle(color = eyeColor, radius = eyeRadius, center = this.center, style = Stroke(width = 5f))

        val irisScale = blink.coerceIn(0.05f, 1f)
        drawCircle(color = eyeColor, radius = eyeRadius * 0.55f * irisScale, center = this.center)
        drawCircle(color = Color(0xFF1F1A14), radius = eyeRadius * 0.28f * irisScale, center = this.center)
    }
}
