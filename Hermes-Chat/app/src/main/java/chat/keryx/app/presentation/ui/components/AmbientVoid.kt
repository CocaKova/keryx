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
import androidx.compose.ui.graphics.luminance
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
    // Daylight rule (2.2): glow means darkness. In light theme the room is paper-and-ink and the
    // gilt bubble edge is the only light — accent pools on white read as stains, not depth.
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) return
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
        // Visible pools, not a wash: the original 3–4% alphas at near-screen radius flattened
        // into an imperceptible tint over the amber app gradient — nobody ever saw the void
        // breathe. Tighter radii give each glow an actual silhouette; the alphas sit just above
        // the aurora so the pools read as depth without crowding the focal effects.
        drawRect(
            Brush.radialGradient(
                listOf(accent.copy(alpha = 0.11f), Color.Transparent),
                center = Offset(w * (0.10f + 0.28f * phase), h * (0.08f + 0.10f * phase)),
                radius = (w * 0.62f).coerceAtLeast(1f),
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(accent2.copy(alpha = 0.09f), Color.Transparent),
                center = Offset(w * (0.92f - 0.30f * phase), h * (0.85f - 0.12f * phase)),
                radius = (w * 0.58f).coerceAtLeast(1f),
            ),
        )
    }
}
