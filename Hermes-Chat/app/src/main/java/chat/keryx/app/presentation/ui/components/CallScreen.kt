package chat.keryx.app.presentation.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import chat.keryx.app.audio.CallController
import chat.keryx.app.presentation.ChatViewModel
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Call (1.22) — Keryx's grand opus surface: a full-screen voice conversation with the
 * agent. Everything on screen breathes with the call's state: the dusk field drifts, the orb
 * pulses with YOUR voice while listening, orbits braille while the agent thinks, and pulses
 * with equalizer bars while it speaks. Half-duplex; tap the orb to interrupt the agent.
 */
@Composable
fun CallScreen(
    viewModel: ChatViewModel,
    roomName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var permitted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permitted = granted; denied = !granted }
    LaunchedEffect(Unit) { if (!permitted) askPermission.launch(Manifest.permission.RECORD_AUDIO) }

    val controller = remember { CallController(context, viewModel) }
    DisposableEffect(Unit) { onDispose { controller.end() } }
    LaunchedEffect(permitted) { if (permitted) controller.start() }

    val ui by controller.ui.collectAsState()
    val micLevel by controller.micLevel.collectAsState()
    var mutedUi by remember { mutableStateOf(false) }

    // Call timer, ticking only while composed.
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ui.startedAt) {
        while (true) {
            elapsed = (System.currentTimeMillis() - ui.startedAt) / 1000
            kotlinx.coroutines.delay(1_000)
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF060A0C),
                        0.6f to Color(0xFF07131A),
                        1f to accent2.copy(alpha = 0.16f)
                            .let { it.copy(red = it.red * 0.4f, green = it.green * 0.4f, blue = it.blue * 0.6f) },
                    ),
                ),
        ) {
            DriftField(accent, accent2)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().padding(24.dp),
            ) {
                Spacer(Modifier.height(28.dp))
                Text(
                    "KERYX · CALL",
                    fontSize = 12.sp, letterSpacing = 6.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.55f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    roomName,
                    fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "%d:%02d".format(elapsed / 60, elapsed % 60),
                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.45f),
                )

                Spacer(Modifier.weight(1f))

                when {
                    denied -> Text(
                        "Keryx needs the microphone for a call.",
                        color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                    else -> CallOrb(
                        phase = ui.phase,
                        micLevel = micLevel,
                        accent = accent,
                        accent2 = accent2,
                        onTap = { controller.interrupt() },
                    )
                }

                Spacer(Modifier.height(26.dp))
                Text(
                    when (ui.phase) {
                        CallController.Phase.LISTENING -> "listening"
                        CallController.Phase.TRANSCRIBING -> "hearing you…"
                        CallController.Phase.THINKING -> "thinking"
                        CallController.Phase.SPEAKING -> "tap the orb to interrupt"
                        CallController.Phase.MUTED -> "muted"
                        CallController.Phase.ENDED -> "call ended"
                    },
                    fontSize = 12.sp, letterSpacing = 2.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )

                Spacer(Modifier.weight(1f))

                // The exchange, whispered: what it heard from you, what it's saying back.
                if (ui.heard.isNotBlank()) {
                    Text(
                        "“${ui.heard}”",
                        fontSize = 13.sp, color = accent.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (ui.speaking.isNotBlank() && ui.phase == CallController.Phase.SPEAKING) {
                    Text(
                        ui.speaking,
                        fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ui.error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center)
                }

                Spacer(Modifier.height(28.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Mute — the mic stays yours.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (mutedUi) 0.25f else 0.08f))
                            .clickable {
                                mutedUi = !mutedUi
                                controller.setMuted(mutedUi)
                            },
                    ) {
                        Icon(
                            if (mutedUi) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (mutedUi) "Unmute" else "Mute",
                            tint = Color.White,
                        )
                    }
                    // End call.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE0524D))
                            .clickable { controller.end(); onDismiss() },
                    ) {
                        Icon(
                            Icons.Default.Call, contentDescription = "End call",
                            tint = Color.White,
                            modifier = Modifier.rotate(135f).size(30.dp),
                        )
                    }
                }
                Spacer(Modifier.height(34.dp))
            }
        }
    }
}

/** The centerpiece: one orb, four moods. */
@Composable
private fun CallOrb(
    phase: CallController.Phase,
    micLevel: Float,
    accent: Color,
    accent2: Color,
    onTap: () -> Unit,
) {
    val breath = rememberInfiniteTransition(label = "orbBreath")
    val slow by breath.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "orbSlow",
    )
    val spin by breath.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000)),
        label = "orbSpin",
    )
    // Listening: the orb rides YOUR voice. Everything else: a slow breath.
    val live = animateFloatAsState(
        targetValue = when (phase) {
            CallController.Phase.LISTENING, CallController.Phase.TRANSCRIBING -> 0.15f + micLevel * 0.85f
            CallController.Phase.SPEAKING -> 0.35f + slow * 0.4f
            CallController.Phase.THINKING -> 0.2f + slow * 0.2f
            else -> 0.1f
        },
        animationSpec = tween(120),
        label = "orbLive",
    ).value

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(280.dp)) {
        // Halo rings expand with energy.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            for (ring in 0..2) {
                val t = ((live + ring * 0.33f) % 1f)
                drawCircle(
                    color = accent.copy(alpha = (1f - t) * 0.22f),
                    radius = r * (0.55f + t * 0.45f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                )
            }
        }
        // The orb body: a slowly rotating dusk gradient, scaled by energy.
        Box(
            modifier = Modifier
                .size(190.dp)
                .scale(0.92f + live * 0.1f)
                .rotate(spin)
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(
                            accent.copy(alpha = 0.85f),
                            accent2.copy(alpha = 0.75f),
                            accent.copy(alpha = 0.35f),
                            accent2.copy(alpha = 0.85f),
                            accent.copy(alpha = 0.85f),
                        ),
                    ),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                ),
        )
        // Inner face: dark disc so the state glyph reads.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(Color(0xE6081015)),
        ) {
            when (phase) {
                CallController.Phase.THINKING, CallController.Phase.TRANSCRIBING ->
                    BrailleSnakeAnimation(
                        modifier = Modifier.size(74.dp),
                        color = accent, color2 = accent2,
                        snakeLength = 12, periodMillis = 2600, glyphSize = 13f,
                    )
                CallController.Phase.SPEAKING -> SpeakingBars(accent, accent2)
                CallController.Phase.MUTED -> Icon(
                    Icons.Default.MicOff, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(44.dp),
                )
                else -> Icon(
                    Icons.Default.Mic, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f + micLevel * 0.5f),
                    modifier = Modifier.size(44.dp).scale(1f + micLevel * 0.25f),
                )
            }
        }
    }
}

/** Five bars, phased sine waves — the agent's voice made visible. */
@Composable
private fun SpeakingBars(accent: Color, accent2: Color) {
    val t by rememberInfiniteTransition(label = "bars").animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(900)),
        label = "barsT",
    )
    Canvas(Modifier.size(width = 92.dp, height = 60.dp)) {
        val barW = 8.dp.toPx()
        val gap = (size.width - barW * 5) / 4
        for (i in 0 until 5) {
            val h = size.height * (0.3f + 0.7f * (0.5f + 0.5f * sin(t + i * 1.1f)))
            val x = i * (barW + gap) + barW / 2
            drawLine(
                brush = Brush.verticalGradient(listOf(accent, accent2)),
                start = Offset(x, size.height / 2 - h / 2),
                end = Offset(x, size.height / 2 + h / 2),
                strokeWidth = barW,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** The dusk field: sparse braille-dust drifting upward forever. Cheap — one transition,
 *  ~40 dots, no allocation per frame. */
@Composable
private fun DriftField(accent: Color, accent2: Color) {
    data class Mote(val x: Float, val y: Float, val r: Float, val phase: Float, val warm: Boolean)
    val motes = remember {
        val rnd = Random(7)
        List(42) {
            Mote(rnd.nextFloat(), rnd.nextFloat(), 1.2f + rnd.nextFloat() * 2.2f,
                rnd.nextFloat() * (2 * Math.PI).toFloat(), rnd.nextBoolean())
        }
    }
    val t by rememberInfiniteTransition(label = "drift").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24_000)),
        label = "driftT",
    )
    Canvas(Modifier.fillMaxSize()) {
        motes.forEach { m ->
            val y = ((m.y - t * (0.05f + m.r * 0.03f)) % 1f + 1f) % 1f
            val alpha = 0.10f + 0.14f * (0.5f + 0.5f * sin(m.phase + t * (2 * Math.PI).toFloat()))
            drawCircle(
                color = (if (m.warm) accent else accent2).copy(alpha = alpha),
                radius = m.r * density,
                center = Offset(m.x * size.width, y * size.height),
            )
        }
    }
}
