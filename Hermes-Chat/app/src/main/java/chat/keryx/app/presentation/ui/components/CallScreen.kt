package chat.keryx.app.presentation.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import chat.keryx.app.audio.CallController
import chat.keryx.app.presentation.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Call (1.22) — Keryx's grand opus surface: a full-screen voice conversation with the
 * agent. Everything on screen breathes with the call's state.
 *
 * Choreography:
 *  - **Entering.** The surface fades up from the chat, the aurora field wakes, the orb blooms
 *    in on a spring with a single shockwave ring rolling out to the edges, the header settles
 *    down from above and the controls rise from below — about a second, staggered, one motion.
 *  - **Living.** The aurora drifts on slow Lissajous paths and takes the colour of the phase;
 *    the orb rides YOUR voice while listening (rings ripple out with your energy), orbits
 *    satellites while the agent thinks, and wears a waveform ring while it speaks.
 *  - **Leaving.** Sound stops at the tap; the orb collapses, the controls drop, the surface
 *    fades — then the dialog is dismissed. The chat never appears through a hard edge.
 *
 * Half-duplex; tap the orb to interrupt the agent. Reduced motion skips every choreography and
 * stills every loop; the reactive half (the orb on your voice) stays.
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

    // The ViewModel keeps the live call across rotation; the screen only attaches to it. It ends
    // on the red button or back — never because the Activity was rebuilt under us.
    val controller = remember { viewModel.callController(context) }
    LaunchedEffect(permitted) { if (permitted) controller.start() }

    val ui by controller.ui.collectAsState()
    val micLevel by controller.micLevel.collectAsState()
    val voiceRaw by controller.voiceLevel.collectAsState()
    // -1 = no meter (mp3 path); the orb breathes on its own then. Smoothed a touch so the bars
    // don't twitch on every 20 ms slot.
    val voiceMetered = voiceRaw >= 0f
    val voiceLevel by animateFloatAsState(
        targetValue = if (voiceMetered) voiceRaw else 0f,
        animationSpec = tween(70),
        label = "voiceLevel",
    )
    var mutedUi by remember { mutableStateOf(controller.isMuted()) }
    val haptics = LocalKeryxHaptics.current
    val reduced by rememberReducedMotion()

    // Call timer, ticking only while composed.
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ui.startedAt) {
        while (true) {
            elapsed = (System.currentTimeMillis() - ui.startedAt) / 1000
            delay(1_000)
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    // The phase, as a colour. Everything tinted — aurora, glow, rings, captions — follows it.
    val tint by animateColorAsState(
        targetValue = when (ui.phase) {
            CallController.Phase.LISTENING -> accent
            CallController.Phase.TRANSCRIBING -> lerp(accent, accent2, 0.5f)
            CallController.Phase.THINKING -> accent2
            CallController.Phase.SPEAKING -> lerp(accent, SpeakingWarm, 0.55f)
            CallController.Phase.MUTED, CallController.Phase.ENDED -> MutedSteel
        },
        animationSpec = tween(650),
        label = "callTint",
    )

    // ── Entering / leaving ───────────────────────────────────────────────────────
    val intro = remember { Animatable(0f) }   // surface, header, captions
    val bloom = remember { Animatable(0f) }   // orb scale, spring overshoot
    val wave = remember { Animatable(0f) }    // the one shockwave ring
    val rise = remember { Animatable(0f) }    // controls
    val outro = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) {
            intro.snapTo(1f); bloom.snapTo(1f); rise.snapTo(1f)
            return@LaunchedEffect
        }
        launch { intro.animateTo(1f, tween(720, easing = FastOutSlowInEasing)) }
        launch { delay(110); bloom.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 150f)) }
        launch { delay(240); wave.animateTo(1f, tween(1_250, easing = CubicBezierEasing(0.05f, 0.7f, 0.2f, 1f))) }
        launch { delay(360); rise.animateTo(1f, tween(640, easing = FastOutSlowInEasing)) }
    }
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            viewModel.endCall() // sound stops at the tap; the picture takes its bow after
        }
    }
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        if (!reduced) outro.animateTo(1f, tween(380, easing = FastOutLinearInEasing))
        onDismiss()
    }

    // A Dialog, sized by hand. A Compose Dialog window is WRAP_CONTENT by default, so
    // `fillMaxSize` inside it came out half-height with the chat showing below a hard edge;
    // setting the window itself to MATCH_PARENT (and letting it draw under the system bars) is
    // what makes it the whole screen. Its own window also puts it above everything in the app.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }
    val workLabel by viewModel.workLabel.collectAsState()
    val awaiting by viewModel.awaitingReply.collectAsState()
    androidx.compose.ui.window.Dialog(
        onDismissRequest = close,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                w.setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                )
                w.setDimAmount(0f)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            }
        }
        val surfaceAlpha = min(1f, intro.value * 1.6f) * (1f - outro.value)
        // Energy: how alive the picture is right now — your voice while listening, the agent's
        // breath while speaking. The aurora and the orb's glow both drink from it.
        val energy by animateFloatAsState(
            targetValue = when (ui.phase) {
                CallController.Phase.LISTENING, CallController.Phase.TRANSCRIBING -> 0.15f + micLevel * 0.85f
                CallController.Phase.SPEAKING -> if (voiceMetered) 0.2f + voiceLevel * 0.7f else 0.55f
                CallController.Phase.THINKING -> 0.35f
                else -> 0.08f
            },
            animationSpec = tween(140),
            label = "callEnergy",
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = surfaceAlpha }
                // Swallow taps so nothing underneath (the chat) reacts while the call is up.
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .background(
                    // Opaque all the way down. The last stop used to carry 16 % alpha, which
                    // (with no dim behind the window) let the chat show through the bottom
                    // 40 % of the call — the "hard cut" halfway down the screen.
                    Brush.verticalGradient(
                        0f to Color(0xFF04070A),
                        0.55f to Color(0xFF06111A),
                        1f to lerp(Color(0xFF06111A), tint.copy(alpha = 1f), 0.16f),
                    ),
                ),
        ) {
            AuroraField(accent = accent, accent2 = accent2, tint = tint, energy = energy, wake = intro.value)
            Shockwave(progress = wave.value, color = tint)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().padding(24.dp),
            ) {
                Spacer(Modifier.height(28.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        translationY = -28.dp.toPx() * (1f - intro.value)
                        alpha = intro.value
                    },
                ) {
                    Text(
                        "KERYX · CALL",
                        fontSize = 11.sp, letterSpacing = 6.sp, fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        roomName,
                        fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "%d:%02d".format(elapsed / 60, elapsed % 60),
                        fontSize = 12.sp, color = Color.White.copy(alpha = 0.42f),
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                }

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
                        voiceLevel = if (voiceMetered) voiceLevel else -1f,
                        accent = accent,
                        accent2 = accent2,
                        tint = tint,
                        scale = bloom.value * (1f - outro.value * 0.9f),
                        onTap = {
                            haptics.commit()
                            controller.interrupt()
                        },
                    )
                }

                Spacer(Modifier.height(30.dp))
                val caption = when (ui.phase) {
                    CallController.Phase.LISTENING -> "listening"
                    CallController.Phase.TRANSCRIBING -> "hearing you…"
                    // The chat's own work label already names the tool in the shared
                    // grammar ("Reading notes.md", "Running terminal") — say it here too.
                    CallController.Phase.THINKING ->
                        if (awaiting && workLabel != "Working") workLabel.lowercase() else "thinking"
                    CallController.Phase.SPEAKING -> "tap the orb to interrupt"
                    CallController.Phase.MUTED -> "muted"
                    CallController.Phase.ENDED -> "call ended"
                }
                Crossfade(
                    targetState = caption,
                    animationSpec = tween(if (reduced) 0 else 320),
                    label = "caption",
                    modifier = Modifier.graphicsLayer { alpha = intro.value },
                ) { text ->
                    Text(
                        text,
                        fontSize = 12.sp, letterSpacing = 2.5.sp,
                        color = tint.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.weight(1f))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer { alpha = intro.value },
                ) {
                    if (awaiting && workLabel != "Working" && ui.phase == CallController.Phase.SPEAKING) {
                        Text(
                            workLabel.lowercase(),
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                        )
                    }
                    // The exchange, whispered: what it heard from you, what it's saying back.
                    Crossfade(
                        targetState = ui.heard,
                        animationSpec = tween(if (reduced) 0 else 400),
                        label = "heard",
                    ) { heard ->
                        if (heard.isNotBlank()) {
                            Text(
                                "“$heard”",
                                fontSize = 13.sp, color = accent.copy(alpha = 0.9f),
                                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    val spoken = if (ui.phase == CallController.Phase.SPEAKING) ui.speaking else ""
                    Crossfade(
                        targetState = spoken,
                        animationSpec = tween(if (reduced) 0 else 300),
                        label = "speaking",
                    ) { text ->
                        if (text.isNotBlank()) {
                            Text(
                                text,
                                fontSize = 13.sp, color = Color.White.copy(alpha = 0.78f),
                                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }
                    ui.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.graphicsLayer {
                        translationY = 60.dp.toPx() * (1f - rise.value) + 90.dp.toPx() * outro.value
                        alpha = rise.value * (1f - outro.value)
                    },
                ) {
                    // Mute — the mic stays yours.
                    GlassButton(
                        diameter = 60.dp,
                        fill = Color.White.copy(alpha = if (mutedUi) 0.26f else 0.08f),
                        halo = if (mutedUi) MutedSteel else null,
                        contentDescription = if (mutedUi) "Unmute" else "Mute",
                        onClick = {
                            haptics.commit()
                            mutedUi = !mutedUi
                            controller.setMuted(mutedUi)
                        },
                    ) {
                        Icon(
                            if (mutedUi) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }
                    // End call.
                    GlassButton(
                        diameter = 76.dp,
                        fill = EndRed,
                        halo = EndRed,
                        contentDescription = "End call",
                        onClick = {
                            haptics.press()
                            close()
                        },
                    ) {
                        Icon(
                            Icons.Default.Call, contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.rotate(135f).size(32.dp),
                        )
                    }
                }
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}

// ── Palette ────────────────────────────────────────────────────────────────────
private val SpeakingWarm = Color(0xFFF4B56E)
private val MutedSteel = Color(0xFF7C8A98)
private val EndRed = Color(0xFFE0524D)

/** The centerpiece: one orb, five moods, layered like glass over light. */
@Composable
private fun CallOrb(
    phase: CallController.Phase,
    micLevel: Float,
    /** The agent's voice leaving the speaker, 0..1; -1 = no meter, breathe instead. */
    voiceLevel: Float,
    accent: Color,
    accent2: Color,
    tint: Color,
    scale: Float,
    onTap: () -> Unit,
) {
    // A call already costs a lit screen, an open mic and playing audio, so stilling the orb saves
    // comparatively little — but the budget is not a cost calculation, it is a promise. The orb's
    // reactive half survives regardless: [micLevel] is not a frame clock, so under Battery Saver
    // the orb still rides your voice, it just stops breathing and turning underneath it.
    val reduced by rememberReducedMotion()
    val slow: Float
    val spin: Float
    val fast: Float
    if (!reduced) {
        val breath = rememberInfiniteTransition(label = "orbBreath")
        slow = breath.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "orbSlow",
        ).value
        spin = breath.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing)),
            label = "orbSpin",
        ).value
        fast = breath.animateFloat(
            initialValue = 0f, targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
            label = "orbFast",
        ).value
    } else {
        slow = 0.5f
        spin = 0f
        fast = 0f
    }
    val metered = voiceLevel >= 0f
    // Listening: the orb rides YOUR voice. Speaking: it rides the agent's, straight off the
    // play head. Everything else: a slow breath.
    val live by animateFloatAsState(
        targetValue = when (phase) {
            CallController.Phase.LISTENING, CallController.Phase.TRANSCRIBING -> 0.15f + micLevel * 0.85f
            CallController.Phase.SPEAKING -> if (metered) 0.12f + voiceLevel * 0.88f else 0.35f + slow * 0.4f
            CallController.Phase.THINKING -> 0.2f + slow * 0.2f
            else -> 0.1f
        },
        animationSpec = tween(120),
        label = "orbLive",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(stiffness = 700f),
        label = "orbPress",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ORB_FIELD)
            .graphicsLayer { scaleX = scale * press; scaleY = scale * press },
    ) {
        // Field: glow, then whatever the phase wears around the orb.
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val orbR = ORB_SIZE.toPx() / 2f
            val glowR = orbR * (1.45f + live * 0.35f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(tint.copy(alpha = 0.22f + live * 0.30f), tint.copy(alpha = 0f)),
                    center = c, radius = glowR,
                ),
                radius = glowR, center = c,
            )
            when (phase) {
                CallController.Phase.LISTENING, CallController.Phase.TRANSCRIBING -> {
                    // Ripples: born at the rim, dying at the field's edge, faster with your voice.
                    val flow = spin / 360f * 2.2f + live * 0.8f
                    for (ring in 0 until 4) {
                        val t = ((flow + ring * 0.25f) % 1f + 1f) % 1f
                        drawCircle(
                            color = tint.copy(alpha = (1f - t) * (0.12f + live * 0.30f)),
                            radius = orbR * (1.02f + t * 0.5f),
                            center = c,
                            style = Stroke(width = (2.2f - t * 1.4f).dp.toPx()),
                        )
                    }
                }
                CallController.Phase.SPEAKING -> {
                    // The voice ring: a circle bent by three harmonics, turning with the words.
                    // With a meter, the bend IS the voice: silence between words rounds it out.
                    val amp = if (metered) 0.25f + voiceLevel * 1.4f else 1f
                    val path = Path()
                    val n = 120
                    for (i in 0..n) {
                        val a = i.toFloat() / n * (2 * PI).toFloat()
                        val r = orbR * (
                            1.13f + live * 0.05f +
                                amp * (
                                    0.045f * sin(3f * a + fast * 2f) +
                                        0.030f * sin(5f * a - fast * 3f) +
                                        0.018f * sin(8f * a + fast)
                                    )
                            )
                        val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                    drawPath(
                        path,
                        brush = Brush.sweepGradient(listOf(accent, tint, accent2, tint, accent), center = c),
                        style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round),
                        alpha = 0.85f,
                    )
                    drawPath(
                        path,
                        color = tint.copy(alpha = 0.22f),
                        style = Stroke(width = 9.dp.toPx()),
                    )
                }
                CallController.Phase.THINKING -> {
                    // Satellites: three thoughts in orbit, each on its own ring and pace.
                    for (i in 0 until 3) {
                        val a = Math.toRadians((spin * (1.3f + i * 0.45f) + i * 120f).toDouble()).toFloat()
                        val r = orbR * (1.2f + i * 0.1f)
                        val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
                        drawCircle(
                            color = tint.copy(alpha = 0.08f),
                            radius = r, center = c, style = Stroke(width = 1.dp.toPx()),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0f)),
                                center = p, radius = 14.dp.toPx(),
                            ),
                            radius = 14.dp.toPx(), center = p,
                        )
                        drawCircle(color = Color.White.copy(alpha = 0.9f), radius = (3.2f - i * 0.5f).dp.toPx(), center = p)
                    }
                }
                CallController.Phase.MUTED -> drawCircle(
                    color = tint.copy(alpha = 0.16f), radius = orbR * 1.12f, center = c,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                CallController.Phase.ENDED -> Unit
            }
        }
        // The body: a slowly rotating dusk gradient…
        Box(
            modifier = Modifier
                .size(ORB_SIZE)
                .rotate(spin)
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(
                            accent.copy(alpha = 0.9f),
                            tint.copy(alpha = 0.8f),
                            accent2.copy(alpha = 0.75f),
                            accent.copy(alpha = 0.4f),
                            accent2.copy(alpha = 0.9f),
                            accent.copy(alpha = 0.9f),
                        ),
                    ),
                ),
        )
        // …with a second, counter-turning veil for depth (two sheets of colour sliding past
        // each other read as a lit sphere, one sheet reads as a disc).
        Box(
            modifier = Modifier
                .size(ORB_SIZE)
                .rotate(-spin * 0.6f)
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            tint.copy(alpha = 0.55f),
                            Color.Transparent,
                            accent2.copy(alpha = 0.4f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        // Glass: a highlight off-centre, and a hairline rim.
        Canvas(Modifier.size(ORB_SIZE)) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0f)),
                    center = Offset(size.width * 0.32f, size.height * 0.27f),
                    radius = size.width * 0.55f,
                ),
                radius = size.width / 2f, center = center,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.26f),
                radius = size.width / 2f - 0.75.dp.toPx(),
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }
        // Inner face: dark disc so the state glyph reads.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ORB_FACE)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF0C141B).copy(alpha = 0.90f), Color(0xFF04080C).copy(alpha = 0.97f)),
                    ),
                ),
        ) {
            when (phase) {
                CallController.Phase.THINKING, CallController.Phase.TRANSCRIBING ->
                    BrailleSnakeAnimation(
                        modifier = Modifier.size(74.dp),
                        color = accent, color2 = accent2,
                        snakeLength = 12, periodMillis = 2600, glyphSize = 13f,
                        progress = true,
                    )
                CallController.Phase.SPEAKING -> SpeakingBars(accent, tint, fast, voiceLevel)
                CallController.Phase.MUTED -> Icon(
                    Icons.Default.MicOff, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(44.dp),
                )
                else -> Icon(
                    Icons.Default.Mic, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f + micLevel * 0.5f),
                    modifier = Modifier
                        .size(44.dp)
                        .graphicsLayer { scaleX = 1f + micLevel * 0.25f; scaleY = 1f + micLevel * 0.25f },
                )
            }
        }
        // Touch, last so it sits over every layer: the whole orb is the interrupt button.
        Box(
            modifier = Modifier
                .size(ORB_SIZE)
                .clip(CircleShape)
                .clickable(interactionSource = interaction, indication = null, onClick = onTap),
        )
    }
}

private val ORB_FIELD = 320.dp
private val ORB_SIZE = 200.dp
private val ORB_FACE = 152.dp

/** Seven bars, mirrored — the agent's voice made visible. With a meter ([level] ≥ 0) the
 *  bars follow the sound leaving the speaker, each with a little spread of its own so a held
 *  vowel still shimmers; without one they ride two harmonics on the clock. */
@Composable
private fun SpeakingBars(accent: Color, tint: Color, t: Float, level: Float) {
    Canvas(Modifier.size(width = 104.dp, height = 60.dp)) {
        val bars = 7
        val barW = 7.dp.toPx()
        val gap = (size.width - barW * bars) / (bars - 1)
        for (i in 0 until bars) {
            val k = i - bars / 2
            val env = 1f - 0.12f * kotlin.math.abs(k)          // taller in the middle
            val wave = if (level >= 0f) {
                level * (0.8f + 0.2f * sin(t * 4f + i * 1.3f))
            } else {
                0.5f + 0.5f * sin(t * 2f + i * 0.9f) * 0.7f + 0.3f * sin(t * 3.3f - i * 1.7f)
            }
            val h = size.height * env * (0.12f + 0.88f * wave.coerceIn(0f, 1f))
            val x = i * (barW + gap) + barW / 2
            drawLine(
                brush = Brush.verticalGradient(listOf(tint, accent)),
                start = Offset(x, size.height / 2 - h / 2),
                end = Offset(x, size.height / 2 + h / 2),
                strokeWidth = barW,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** A glass disc that answers a press by shrinking, with an optional breathing halo. */
@Composable
private fun GlassButton(
    diameter: Dp,
    fill: Color,
    halo: Color?,
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val reduced by rememberReducedMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(stiffness = 700f),
        label = "buttonPress",
    )
    val breath = if (!reduced && halo != null) {
        rememberInfiniteTransition(label = "halo").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "haloT",
        ).value
    } else 0.5f
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(diameter * 1.5f)) {
        if (halo != null) {
            Canvas(Modifier.fillMaxSize()) {
                val r = diameter.toPx() / 2f * (1.15f + breath * 0.25f)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(halo.copy(alpha = 0.30f + breath * 0.15f), halo.copy(alpha = 0f)),
                        center = center, radius = r,
                    ),
                    radius = r, center = center,
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(diameter)
                .graphicsLayer { scaleX = press; scaleY = press }
                .clip(CircleShape)
                .background(fill)
                .drawBehind {
                    // Gradient coordinates are pixels: the highlight sits up-left, like light.
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0f)),
                            center = Offset(size.width * 0.3f, size.height * 0.25f),
                            radius = size.width * 0.9f,
                        ),
                        radius = size.width / 2f, center = center,
                    )
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = contentDescription,
                    onClick = onClick,
                ),
        ) {
            content()
        }
    }
}

/** One ring, rolling out from the orb to the edges as the call opens. */
@Composable
private fun Shockwave(progress: Float, color: Color) {
    if (progress <= 0f || progress >= 1f) return
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2f, size.height * 0.47f)
        val r = ORB_SIZE.toPx() / 2f + progress * max(size.width, size.height) * 0.75f
        val fade = 1f - progress
        drawCircle(
            color = color.copy(alpha = 0.45f * fade * fade),
            radius = r, center = c,
            style = Stroke(width = (2f + 26f * fade).dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.35f * fade * fade),
            radius = r, center = c,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/**
 * The aurora: three soft lobes of colour drifting on slow Lissajous paths under a field of
 * braille-dust, with a vignette holding the edges dark. The middle lobe wears the phase's colour
 * and brightens with [energy], so the whole screen leans toward whoever is talking.
 * Cheap — one transition, three gradients, ~40 dots, no allocation per frame.
 */
@Composable
private fun AuroraField(accent: Color, accent2: Color, tint: Color, energy: Float, wake: Float) {
    data class Mote(val x: Float, val y: Float, val r: Float, val phase: Float, val warm: Boolean)
    val motes = remember {
        val rnd = Random(7)
        List(42) {
            Mote(rnd.nextFloat(), rnd.nextFloat(), 1.2f + rnd.nextFloat() * 2.2f,
                rnd.nextFloat() * (2 * PI).toFloat(), rnd.nextBoolean())
        }
    }
    // 42 motes over a full screen: the most expensive ornament on the call, and the least missed.
    val reduced by rememberReducedMotion()
    val t = if (!reduced) {
        rememberInfiniteTransition(label = "drift").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(36_000, easing = LinearEasing)),
            label = "driftT",
        ).value
    } else 0.37f
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val tau = (2 * PI).toFloat()
        fun lobe(color: Color, cx: Float, cy: Float, r: Float, a: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(color.copy(alpha = a * wake), color.copy(alpha = 0f)),
                    center = Offset(cx, cy), radius = r,
                ),
                radius = r, center = Offset(cx, cy),
            )
        }
        lobe(accent, w * (0.22f + 0.12f * sin(t * tau)), h * (0.28f + 0.08f * cos(t * tau * 0.7f)), w * 0.8f, 0.20f + energy * 0.08f)
        lobe(accent2, w * (0.80f + 0.10f * cos(t * tau * 0.8f + 1f)), h * (0.66f + 0.10f * sin(t * tau * 0.6f)), w * 0.9f, 0.18f)
        lobe(tint, w * 0.5f, h * 0.47f, w * 0.6f, 0.08f + energy * 0.18f)
        motes.forEach { m ->
            val y = ((m.y - t * (0.05f + m.r * 0.03f)) % 1f + 1f) % 1f
            val alpha = (0.10f + 0.14f * (0.5f + 0.5f * sin(m.phase + t * tau))) * wake
            drawCircle(
                color = (if (m.warm) accent else accent2).copy(alpha = alpha),
                radius = m.r * density,
                center = Offset(m.x * w, y * h),
            )
        }
        drawRect(
            brush = Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                center = Offset(w / 2f, h * 0.45f), radius = max(w, h) * 0.78f,
            ),
        )
    }
}
