package chat.keryx.app.presentation.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * The ambient void (2.0 "The Dream Rebuild"): two vast accent glows adrift in the dark behind
 * everything, moving so slowly the eye never catches them mid-step — the room only ever *feels*
 * alive, it is never seen breathing.
 *
 * Deliberately not an infinite-transition client: the drift covers a few dozen pixels per
 * *minute*, so redrawing at 60fps would be pure compositing waste. A 4 Hz tick is far below
 * anything perceptible at this speed and lets the frame clock rest between steps. Battery Saver
 * stills it entirely at mid-drift.
 */
@Composable
fun AmbientVoid(modifier: Modifier = Modifier) {
    val reduced by rememberReducedMotion()
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary

    var phase by remember { mutableFloatStateOf(0.5f) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        val periodMs = 150_000f
        val born = SystemClock.uptimeMillis()
        while (true) {
            val t = ((SystemClock.uptimeMillis() - born) % periodMs.toLong()) / periodMs
            // Triangle wave: drift out, drift home, never a seam.
            phase = if (t < 0.5f) t * 2f else 2f - t * 2f
            delay(250)
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.radialGradient(
                listOf(accent.copy(alpha = 0.07f), Color.Transparent),
                center = Offset(w * (0.16f + 0.10f * phase), h * (0.10f + 0.07f * phase)),
                radius = (w * 0.95f).coerceAtLeast(1f),
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(accent2.copy(alpha = 0.06f), Color.Transparent),
                center = Offset(w * (0.86f - 0.14f * phase), h * (0.80f - 0.06f * phase)),
                radius = (w * 0.90f).coerceAtLeast(1f),
            ),
        )
    }
}
