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
import com.optobot.native.OptoReach
import com.optobot.native.OptoVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MainActivity — Compose-based voice-first UI.
 *
 * Architecture: pure Kotlin + Compose, no React Native.
 *
 * The UI renders:
 *   1. An animated AI Eye (Canvas-based) that reflects the current state
 *   2. A status label below the eye
 *   3. A transcript strip (user's input + AI's response)
 *   4. A confirmation card when the AI wants to write a file
 *
 * Interaction:
 *   - Tap the eye → start listening (OptoVoice.listenOnce)
 *   - Long-press → start/stop record mode
 *   - "Hey Opto!" wake word → automatically starts listening
 *   - TTS plays the AI's response automatically
 */
class MainActivity : ComponentActivity() {

    private lateinit var voice: OptoVoice
    private var hasAudioPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = OptoVoice(applicationContext)

        // Request mic permission
        hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            OptoBotTheme {
                VoiceScreen(
                    voice = voice,
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

// ─── Voice State ───

enum class VoiceState {
    IDLE, LISTENING, THINKING, SPEAKING, RECORDING, CONFIRMING
}

data class ConfirmRequest(
    val path: String,
    val content: String,
    val message: String,
)

@Composable
private fun VoiceScreen(
    voice: OptoVoice,
    hasAudioPermission: Boolean,
) {
    var state by remember { mutableStateOf(VoiceState.IDLE) }
    var userTranscript by remember { mutableStateOf("") }
    var aiText by remember { mutableStateOf("") }
    var recordSeconds by remember { mutableStateOf(0) }
    var confirmRequest by remember { mutableStateOf<ConfirmRequest?>(null) }
    var statusMessage by remember { mutableStateOf("Tap or say \"Hey Opto!\"") }
    val scope = rememberCoroutineScope()

    // Record timer
    LaunchedEffect(state) {
        if (state == VoiceState.RECORDING) {
            val start = System.currentTimeMillis()
            while (state == VoiceState.RECORDING) {
                recordSeconds = ((System.currentTimeMillis() - start) / 1000).toInt()
                delay(1000)
            }
        }
    }

    // Auto-start wake word listener (stub mode fires periodically)
    LaunchedEffect(Unit) {
        voice.startWakeWordListener {
            if (state == VoiceState.IDLE) {
                state = VoiceState.LISTENING
                statusMessage = "Listening..."
                scope.launch(Dispatchers.IO) {
                    val transcript = voice.listenOnce()
                    if (!transcript.isNullOrBlank()) {
                        userTranscript = transcript
                        state = VoiceState.THINKING
                        statusMessage = "Thinking..."
                        // Simulate AI response
                        delay(1500)
                        aiText = generateMockResponse(transcript)
                        state = VoiceState.SPEAKING
                        statusMessage = "Speaking..."
                        voice.speak(aiText) {
                            state = VoiceState.IDLE
                            statusMessage = "Tap or say \"Hey Opto!\""
                        }
                    } else {
                        state = VoiceState.IDLE
                        statusMessage = "Tap or say \"Hey Opto!\""
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
                        if (state == VoiceState.IDLE && confirmRequest == null) {
                            state = VoiceState.LISTENING
                            statusMessage = "Listening..."
                            scope.launch(Dispatchers.IO) {
                                val transcript = voice.listenOnce()
                                if (!transcript.isNullOrBlank()) {
                                    userTranscript = transcript
                                    state = VoiceState.THINKING
                                    statusMessage = "Thinking..."
                                    delay(1500)
                                    aiText = generateMockResponse(transcript)
                                    // Check for write intent
                                    val writeIntent = checkWriteIntent(aiText)
                                    if (writeIntent != null) {
                                        confirmRequest = writeIntent
                                        state = VoiceState.CONFIRMING
                                        statusMessage = "Confirm?"
                                    } else {
                                        state = VoiceState.SPEAKING
                                        statusMessage = "Speaking..."
                                        voice.speak(aiText) {
                                            state = VoiceState.IDLE
                                            statusMessage = "Tap or say \"Hey Opto!\""
                                        }
                                    }
                                } else {
                                    state = VoiceState.IDLE
                                    statusMessage = "Tap or say \"Hey Opto!\""
                                }
                            }
                        } else if (state == VoiceState.SPEAKING) {
                            voice.stopSpeaking()
                            state = VoiceState.IDLE
                            statusMessage = "Tap or say \"Hey Opto!\""
                        }
                    },
                    onLongPress = {
                        if (state == VoiceState.RECORDING) {
                            // Stop recording
                            voice.stopRecording()
                            state = VoiceState.IDLE
                            statusMessage = "Tap or say \"Hey Opto!\" — transcribing..."
                        } else if (state == VoiceState.IDLE || state == VoiceState.LISTENING) {
                            // Start recording
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
            // Header
            Text(
                text = "OptoBot",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1A14),
            )
            Spacer(modifier = Modifier.height(8.dp))

            // AI Eye
            AIEye(state = state, modifier = Modifier.size(240.dp))
            Spacer(modifier = Modifier.height(24.dp))

            // Status label
            Text(
                text = statusMessage,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1F1A14),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Hint
            Text(
                text = when (state) {
                    VoiceState.IDLE -> "Tap to talk • Long-press to record"
                    VoiceState.LISTENING -> "Speak now — silence ends turn"
                    VoiceState.THINKING -> "On-device LFM2.5-230M is reasoning"
                    VoiceState.SPEAKING -> "Tap to interrupt"
                    VoiceState.RECORDING -> "Long-press to stop and transcribe"
                    VoiceState.CONFIRMING -> "Tap Yes or No"
                },
                fontSize = 13.sp,
                color = Color(0xFF6B5D4C),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Transcript strip
            if (userTranscript.isNotBlank()) {
                Surface(
                    color = Color(0xFFF2EDE5),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("You: ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE85D2F))
                        Text(userTranscript, fontSize = 15.sp, color = Color(0xFF1F1A14))
                        if (aiText.isNotBlank() && state == VoiceState.SPEAKING) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("OptoBot: ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE85D2F))
                            Text(aiText, fontSize = 15.sp, color = Color(0xFF1F1A14))
                        }
                    }
                }
            }

            // Confirmation card
            confirmRequest?.let { req ->
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = Color(0xFFFFF8E1),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFC57A1E)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Confirm action", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC57A1E))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(req.message, fontSize = 15.sp, color = Color(0xFF1F1A14))
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    // Write the file (stub: just clear)
                                    confirmRequest = null
                                    state = VoiceState.IDLE
                                    statusMessage = "Saved."
                                    scope.launch {
                                        delay(1500)
                                        statusMessage = "Tap or say \"Hey Opto!\""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE85D2F)),
                                modifier = Modifier.weight(1f),
                            ) { Text("Yes", fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = {
                                    confirmRequest = null
                                    state = VoiceState.IDLE
                                    statusMessage = "Tap or say \"Hey Opto!\""
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("No", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

// ─── AI Eye — Canvas-based animated visual ───

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
        animationSpec = infiniteRepeatable(
            animation = tween(if (state == VoiceState.IDLE) 2500 else 800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
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
        animationSpec = tween(500, easing = EaseInOutCubic),
        label = "glow",
    )
    val waveScale by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "wave",
    )
    val spin by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "spin",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "pulse",
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "pulseAlpha",
    )

    Canvas(modifier = modifier) {
        val canvasSize = this.size.minDimension
        val eyeRadius = canvasSize * 0.35f

        // Glow
        drawCircle(
            color = eyeColor.copy(alpha = glow * 0.3f),
            radius = eyeRadius * 1.4f,
            center = this.center,
        )

        // Sound waves (listening state)
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

        // Pulse ring (recording state)
        if (state == VoiceState.RECORDING) {
            drawCircle(
                color = danger.copy(alpha = pulseAlpha),
                radius = eyeRadius * pulseScale * 1.3f,
                center = this.center,
                style = Stroke(width = 3f),
            )
        }

        // Spinner dots (thinking state)
        if (state == VoiceState.THINKING) {
            for (i in 0..5) {
                val angle = (spin + i * 60).toRadians()
                val dotOffset = Offset(
                    this.center.x + eyeRadius * 1.2f * cos(angle),
                    this.center.y + eyeRadius * 1.2f * sin(angle),
                )
                drawCircle(
                    color = accent,
                    radius = 5f,
                    center = dotOffset,
                )
            }
        }

        // Eye shape (outer)
        drawCircle(
            color = Color.White,
            radius = eyeRadius,
            center = this.center,
        )
        drawCircle(
            color = eyeColor,
            radius = eyeRadius,
            center = this.center,
            style = Stroke(width = 5f),
        )

        // Iris + pupil (scaled by blink for blink animation)
        val irisScale = blink.coerceIn(0.05f, 1f)
        drawCircle(
            color = eyeColor,
            radius = eyeRadius * 0.55f * irisScale,
            center = this.center,
        )
        drawCircle(
            color = Color(0xFF1F1A14),
            radius = eyeRadius * 0.28f * irisScale,
            center = this.center,
        )

        // Confirming question mark
        if (state == VoiceState.CONFIRMING) {
            drawCircle(
                color = warning.copy(alpha = 0.4f),
                radius = eyeRadius,
                center = this.center,
            )
        }
    }
}

private fun Float.toRadians(): Float = this * (Math.PI.toFloat() / 180f)
private fun cos(rad: Float): Float = kotlin.math.cos(rad)
private fun sin(rad: Float): Float = kotlin.math.sin(rad)

// ─── Mock AI response generator (stub mode) ───

private fun generateMockResponse(userInput: String): String {
    val input = userInput.lowercase()
    return when {
        "glaucoma" in input -> "Glaucoma is a group of eye conditions that damage the optic nerve, often caused by abnormally high pressure inside your eye. It's one of the leading causes of blindness for people over 60. Early detection is key — I'd recommend a comprehensive dilated eye exam and tonometry. Want me to search for the latest treatment guidelines?"
        "diabet" in input -> "Diabetic retinopathy is a complication of diabetes caused by damage to the blood vessels of the light-sensitive tissue at the back of the eye (retina). It can develop in anyone with type 1 or type 2 diabetes. The longer you have diabetes and the less controlled your blood sugar, the more likely you are to develop this complication. I'd recommend annual dilated eye exams for any diabetic patient."
        "soap" in input || "note" in input -> "I can help you draft a SOAP note. SOAP stands for Subjective, Objective, Assessment, and Plan. Tell me the patient's presenting complaint, any findings from your exam, your assessment, and your plan — I'll structure it into a clean note. Should I save it as a .md file?"
        "save" in input || "write" in input || "create" in input -> "I'll save that as a note for you. Let me create the file now."
        "search" in input || "find" in input || "latest" in input -> "I don't have current data on that in my weights — try running /search <query> and I'll summarize what I find. For example, /search latest glaucoma treatment 2026."
        else -> "I heard you say: \"$userInput\". I'm running in stub mode right now — the real on-device LFM2.5-230M model would give you a grounded response. On a real device, I'd also search the web for current info if needed."
    }
}

private fun checkWriteIntent(text: String): ConfirmRequest? {
    val patterns = listOf(
        Regex("""(?i)(save|write|create|store).*?(note|file|soap|record|patient)"""),
        Regex("""(?i)I('ll| will)\s+(save|write|create|store)"""),
        Regex("""(?i)saved\s+(the|as)"""),
    )
    if (patterns.any { it.containsMatchIn(text) }) {
        val filename = "note-${System.currentTimeMillis()}.md"
        return ConfirmRequest(
            path = filename,
            content = text,
            message = "Save this as $filename?",
        )
    }
    return null
}
