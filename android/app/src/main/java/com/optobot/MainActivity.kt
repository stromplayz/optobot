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

class MainActivity : ComponentActivity() {

    private lateinit var voice: OptoVoice
    private lateinit var engine: OptoInferenceEngine
    private var hasAudioPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = OptoVoice(applicationContext)
        engine = OptoInferenceEngine(applicationContext)

        hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        voice.init {
            // TTS ready
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

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING, RECORDING, CONFIRMING }

data class ConfirmRequest(val path: String, val content: String, val message: String)

@Composable
private fun VoiceScreen(
    voice: OptoVoice,
    engine: OptoInferenceEngine,
    hasAudioPermission: Boolean,
) {
    var state by remember { mutableStateOf(VoiceState.IDLE) }
    var userTranscript by remember { mutableStateOf("") }
    var aiText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Tap to start") }
    var loadProgress by remember { mutableStateOf(0f) }
    var isLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Load the model on first composition
    LaunchedEffect(Unit) {
        engine.load(object : OptoInferenceEngine.Listener {
            override fun onLoadProgress(p: Float) {
                loadProgress = p
                statusMessage = "Loading model ${Math.round(p * 100)}%"
            }
            override fun onToken(token: String, fullText: String) {
                aiText = fullText
            }
            override fun onComplete(fullText: String) {
                aiText = fullText
                // Speak the response
                state = VoiceState.SPEAKING
                statusMessage = "Speaking..."
                voice.speak(fullText) {
                    state = VoiceState.IDLE
                    statusMessage = "Tap to talk"
                }
            }
            override fun onError(message: String) {
                aiText = "Error: $message"
                state = VoiceState.IDLE
                statusMessage = "Tap to talk"
            }
        })
        isLoaded = engine.isReady
        if (isLoaded) {
            statusMessage = "Tap or say \"Hey Opto!\""
        }
    }

    // Wake word listener (runs in background)
    LaunchedEffect(isLoaded) {
        if (isLoaded) {
            voice.startWakeWordListener {
                if (state == VoiceState.IDLE) {
                    scope.launch(Dispatchers.Main) {
                        state = VoiceState.LISTENING
                        statusMessage = "Listening..."
                        startListening(voice, engine, state, { state = it }, { userTranscript = it }, { aiText = it }, { statusMessage = it }, scope)
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAF7F2))
            .pointerInput(state) {
                detectTapGestures(
                    onTap = {
                        if (state == VoiceState.IDLE && isLoaded) {
                            state = VoiceState.LISTENING
                            statusMessage = "Listening..."
                            startListening(voice, engine, state, { state = it }, { userTranscript = it }, { aiText = it }, { statusMessage = it }, scope)
                        } else if (state == VoiceState.SPEAKING) {
                            voice.stopSpeaking()
                            state = VoiceState.IDLE
                            statusMessage = "Tap to talk"
                        }
                    },
                    onLongPress = {
                        if (state == VoiceState.RECORDING) {
                            val transcript = voice.stopRecording()
                            if (transcript.isNotBlank()) {
                                userTranscript = "Recording: $transcript"
                                state = VoiceState.THINKING
                                statusMessage = "Thinking..."
                                engine.chat(transcript, null, object : OptoInferenceEngine.Listener {
                                    override fun onLoadProgress(p: Float) {}
                                    override fun onToken(token: String, fullText: String) { aiText = fullText }
                                    override fun onComplete(fullText: String) {
                                        aiText = fullText
                                        state = VoiceState.SPEAKING
                                        statusMessage = "Speaking..."
                                        voice.speak(fullText) {
                                            state = VoiceState.IDLE
                                            statusMessage = "Tap to talk"
                                        }
                                    }
                                    override fun onError(message: String) {
                                        aiText = "Error: $message"
                                        state = VoiceState.IDLE
                                        statusMessage = "Tap to talk"
                                    }
                                })
                            } else {
                                state = VoiceState.IDLE
                                statusMessage = "Tap to talk"
                            }
                        } else if (state == VoiceState.IDLE || state == VoiceState.LISTENING) {
                            voice.startRecording()
                            state = VoiceState.RECORDING
                            statusMessage = "Recording..."
                        }
                    },
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

            Text(statusMessage, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1A14), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = when (state) {
                    VoiceState.IDLE -> "Tap to talk • Long-press to record"
                    VoiceState.LISTENING -> "Speak now"
                    VoiceState.THINKING -> "On-device LFM2.5-230M generating"
                    VoiceState.SPEAKING -> "Tap to interrupt"
                    VoiceState.RECORDING -> "Long-press to stop"
                    VoiceState.CONFIRMING -> "Tap Yes or No"
                },
                fontSize = 13.sp, color = Color(0xFF6B5D4C), textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))

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

            if (aiText.isNotBlank() && state == VoiceState.IDLE) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(color = Color(0xFFFFFFFF), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("OptoBot: ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE85D2F))
                        Text(aiText, fontSize = 15.sp, color = Color(0xFF1F1A14))
                    }
                }
            }
        }
    }
}

private fun startListening(
    voice: OptoVoice,
    engine: OptoInferenceEngine,
    currentState: VoiceState,
    setState: (VoiceState) -> Unit,
    setUserText: (String) -> Unit,
    setAiText: (String) -> Unit,
    setStatus: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    voice.listenOnce(
        onResult = { text ->
            if (text.isNotBlank()) {
                setUserText(text)
                setState(VoiceState.THINKING)
                setStatus("Thinking...")
                engine.chat(text, null, object : OptoInferenceEngine.Listener {
                    override fun onLoadProgress(p: Float) {}
                    override fun onToken(token: String, fullText: String) { setAiText(fullText) }
                    override fun onComplete(fullText: String) {
                        setAiText(fullText)
                        setState(VoiceState.SPEAKING)
                        setStatus("Speaking...")
                        voice.speak(fullText) {
                            setState(VoiceState.IDLE)
                            setStatus("Tap to talk")
                        }
                    }
                    override fun onError(message: String) {
                        setAiText("Error: $message")
                        setState(VoiceState.IDLE)
                        setStatus("Tap to talk")
                    }
                })
            } else {
                setState(VoiceState.IDLE)
                setStatus("Tap to talk")
            }
        },
        onError = { msg ->
            setState(VoiceState.IDLE)
            setStatus("Tap to talk")
        }
    )
}

// ─── AI Eye ───

@Composable
private fun AIEye(state: VoiceState, modifier: Modifier = Modifier) {
    val accent = Color(0xFFE85D2F)
    val danger = Color(0xFFC24A2F)
    val warning = Color(0xFFC57A1E)
    val eyeColor = when (state) {
        VoiceState.RECORDING -> danger
        VoiceState.CONFIRMING -> warning
        else -> accent
    }

    val infiniteTransition = rememberInfiniteTransition(label = "eye")
    val blink by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == VoiceState.IDLE) 0.05f else if (state == VoiceState.THINKING) 0.18f else 0.85f,
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
            VoiceState.CONFIRMING -> 0.65f
            VoiceState.IDLE -> 0.25f
        },
        animationSpec = infiniteRepeatable(tween(500, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "glow",
    )
    val waveScale by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1.8f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "wave",
    )
    val spin by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "spin",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "pulse",
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.9f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "pulseAlpha",
    )

    Canvas(modifier = modifier) {
        val canvasSize = this.size.minDimension
        val eyeRadius = canvasSize * 0.35f

        drawCircle(color = eyeColor.copy(alpha = glow * 0.3f), radius = eyeRadius * 1.4f, center = this.center)

        if (state == VoiceState.LISTENING) {
            for (i in 0..2) {
                drawCircle(
                    color = eyeColor.copy(alpha = (1f - (waveScale - 0.4f) / 1.4f).coerceIn(0f, 0.6f) * (1f - i * 0.2f)),
                    radius = eyeRadius * (waveScale + i * 0.3f),
                    center = this.center,
                    style = Stroke(width = 3f),
                )
            }
        }

        if (state == VoiceState.RECORDING) {
            drawCircle(color = danger.copy(alpha = pulseAlpha), radius = eyeRadius * pulseScale * 1.3f, center = this.center, style = Stroke(width = 3f))
        }

        if (state == VoiceState.THINKING) {
            for (i in 0..5) {
                val angle = (spin + i * 60) * (Math.PI.toFloat() / 180f)
                drawCircle(
                    color = accent, radius = 5f,
                    center = Offset(this.center.x + eyeRadius * 1.2f * kotlin.math.cos(angle), this.center.y + eyeRadius * 1.2f * kotlin.math.sin(angle)),
                )
            }
        }

        drawCircle(color = Color.White, radius = eyeRadius, center = this.center)
        drawCircle(color = eyeColor, radius = eyeRadius, center = this.center, style = Stroke(width = 5f))

        val irisScale = blink.coerceIn(0.05f, 1f)
        drawCircle(color = eyeColor, radius = eyeRadius * 0.55f * irisScale, center = this.center)
        drawCircle(color = Color(0xFF1F1A14), radius = eyeRadius * 0.28f * irisScale, center = this.center)

        if (state == VoiceState.CONFIRMING) {
            drawCircle(color = warning.copy(alpha = 0.4f), radius = eyeRadius, center = this.center)
        }
    }
}
