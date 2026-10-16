package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The loading animations Settings offers (3.0: Quill and Constellation joined, Dots and the ASCII
 * wave retired), by their stored names. A retired or unknown name reads as the Caduceus.
 */
val THINKING_STYLES = listOf("Caduceus", "Quill", "Constellation", "Braille", "Ember Drift")

fun thinkingStyleOf(saved: String?): String = saved?.takeIf { it in THINKING_STYLES } ?: "Caduceus"

/**
 * What the open turn is doing, in the gateway's own words: the label the working cloud wears
 * ("Thinking", "Reading importer.py"). The chat provides it so a waiting bubble says the same
 * thing instead of a made-up quip (3.0). Null = the bubble says nothing (a group room, a preview).
 */
val LocalWorkLabel = staticCompositionLocalOf<StateFlow<String>?> { null }

@Composable
fun HermesThinkingAnimation(
    style: String = "Caduceus",
    modifier: Modifier = Modifier,
    /** Whoever is thinking tints the spinner. Defaults to the user's own theme accents; a herald
     *  in a council room passes its own, so you can tell who is working by the colour (2.3 §1). */
    accent: Color? = null,
    accent2: Color? = null,
    /** What to say beside the spinner; null = the turn's own label ([LocalWorkLabel]), if any. */
    label: String? = null,
) {
    // Subtle pulsing alpha on the spinner — decoration wrapped around it, so Battery Saver stills
    // it and holds full opacity instead of the dim end of the breath. The words beside it stay
    // at full strength: a label that breathes down to 60% fails contrast half the time.
    //
    // The spinners themselves do NOT still (Jonny, on device: "make spinners the exception"). The
    // distinction the app draws: motion that carries information keeps moving, motion that
    // decorates stops. A spinner IS the information — freeze it and the app reads as hung, and no
    // amount of saved frames is worth a user force-quitting an agent mid-turn.
    val reduced by rememberReducedMotion()
    val alpha = if (!reduced) {
        rememberInfiniteTransition(label = "snake_spin").animateFloat(
            initialValue = 0.6f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha_pulse"
        ).value
    } else 1.0f

    // The turn's own words. Before 3.0 this was a random quip ("ALIGNING TENSORS") that could
    // contradict the cloud right above it.
    val workFlow = LocalWorkLabel.current
    val said = label ?: workFlow?.collectAsState()?.value
    val primaryColor = accent ?: MaterialTheme.colorScheme.primary
    val secondColor = accent2 ?: MaterialTheme.colorScheme.tertiary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Box(Modifier.alpha(alpha)) {
            when (thinkingStyleOf(style)) {
                "Braille" -> BrailleSpinner(primaryColor, secondColor)
                "Quill" -> QuillSpinner(primaryColor, secondColor)
                "Constellation" -> ConstellationSpinner(primaryColor, secondColor)
                "Ember Drift" -> EmberDriftSpinner(primaryColor, secondColor)
                else -> CaduceusSpinner(primaryColor, secondColor)
            }
        }
        if (!said.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = said,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = KeryxType.body,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun BrailleSpinner(primaryColor: Color, accent2: Color = primaryColor) {
    // A perfect, constant-length (3 dots) snake traveling the perimeter of a 2x3 Braille grid
    val frames = listOf(
        listOf(0, 3, 4), // ⠩
        listOf(3, 4, 5), // ⠸
        listOf(4, 5, 2), // ⠴
        listOf(5, 2, 1), // ⠦
        listOf(2, 1, 0), // ⠇
        listOf(1, 0, 3)  // ⠙
    )
    var frameIndex by remember { mutableStateOf(0) }

    // battery-saver-exempt: a spinner's motion is its message.
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            frameIndex = (frameIndex + 1) % frames.size
        }
    }

    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(10.dp, 16.dp)) {
            val dotRadius = 1.5.dp.toPx()
            
            // 2x3 Grid positions mimicking Braille
            val positions = listOf(
                Offset(dotRadius, dotRadius),                                // 0: Top-Left
                Offset(dotRadius, size.height / 2),                          // 1: Mid-Left
                Offset(dotRadius, size.height - dotRadius),                  // 2: Bot-Left
                Offset(size.width - dotRadius, dotRadius),                   // 3: Top-Right
                Offset(size.width - dotRadius, size.height / 2),             // 4: Mid-Right
                Offset(size.width - dotRadius, size.height - dotRadius)      // 5: Bot-Right
            )
            
            val activeDots = frames[frameIndex]

            // The 3-dot snake grades head→tail from accent 1 into accent 2.
            activeDots.forEachIndexed { order, dot ->
                drawCircle(
                    color = androidx.compose.ui.graphics.lerp(primaryColor, accent2, order / 2f),
                    radius = dotRadius,
                    center = positions[dot]
                )
            }
        }
    }
}

/**
 * The herald's mark: two serpents — one in each accent — winding around an invisible staff while
 * the reply is dreamed up. Depth is faked honestly: whichever strand is on the near side of the
 * staff at a given x is drawn last, thicker and at full strength; the far side thins and dims.
 */
@Composable
fun CaduceusSpinner(primaryColor: Color, accent2: Color) {
    // battery-saver-exempt: a spinner's motion is its message.
    val phase by rememberInfiniteTransition(label = "caduceus").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "caduceus_phase"
    )
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(22.dp, 14.dp)) {
            val mid = size.height / 2f
            val amp = size.height * 0.36f
            // One and a half crossings across the width reads as a braid, not a single X.
            val k = (2f * PI.toFloat() * 1.5f) / size.width
            val steps = 22
            val stepW = size.width / steps
            val strands = listOf(primaryColor to 0f, accent2 to PI.toFloat())
            fun yOf(x: Float, off: Float) = mid + amp * sin(k * x - phase + off)
            listOf(false, true).forEach { front ->
                strands.forEach { (color, off) ->
                    for (i in 0 until steps) {
                        val x0 = i * stepW
                        val x1 = (i + 1) * stepW
                        // cos is the strand's z: positive = near side of the staff.
                        val near = cos(k * (x0 + x1) / 2f - phase + off) >= 0f
                        if (near == front) {
                            drawLine(
                                color = color.copy(alpha = if (front) 1f else 0.40f),
                                start = Offset(x0, yOf(x0, off)),
                                end = Offset(x1, yOf(x1, off)),
                                strokeWidth = (if (front) 1.8.dp else 1.2.dp).toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                        }
                    }
                    // The serpent's head leads at the right edge, with a faint halo.
                    val head = Offset(size.width, yOf(size.width, off))
                    drawCircle(color.copy(alpha = 0.25f), radius = 3.2.dp.toPx(), center = head)
                    drawCircle(color, radius = 1.7.dp.toPx(), center = head)
                }
            }
        }
    }
}
