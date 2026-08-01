package chat.keryx.app.presentation.ui.components

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import kotlin.random.Random

/**
 * Magic dust (2.0): grains of enchanted sand rising off a shape's edge — the streaming reply's
 * "still being dreamed" mark, in the user's own two accents with the odd starlight glint. Each
 * grain lifts from a random point on the outline, drifts on a small arc (a breath of gravity
 * pulls it back down, which is what makes it read as *sand* and not confetti), and sifts away.
 *
 * Battery contract: a fixed pool (no allocation per grain), physics stepped only while [active]
 * or while the last grains finish falling, nothing at all under reduced motion. Place BEFORE
 * `clip()` in the modifier chain — the dust must live *outside* the shape; grains crossing the
 * fill vanish behind it, which reads as emerging from behind the bubble.
 */
@Composable
fun Modifier.keryxMagicDust(
    active: Boolean,
    shape: Shape,
    grains: Int = 42,
): Modifier {
    val reduced by rememberReducedMotion()
    val enabled = active && !reduced
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val density = LocalDensity.current.density
    val pool = remember(grains) { Array(grains) { Grain() } }
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(enabled) {
        if (!enabled && pool.none { it.alive }) return@LaunchedEffect
        val rnd = Random(SystemClock.uptimeMillis())
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                var aliveCount = 0
                for (g in pool) {
                    if (!g.alive) continue
                    g.life += dt
                    if (g.life >= g.maxLife) {
                        g.alive = false
                        continue
                    }
                    g.vy += 26f * density * dt
                    g.ox += g.vx * dt
                    g.oy += g.vy * dt
                    aliveCount++
                }
                if (enabled) {
                    // Keep roughly two thirds of the pool aloft, a couple of grains per frame.
                    var toSpawn = minOf(2, (grains * 2 / 3) - aliveCount)
                    for (g in pool) {
                        if (toSpawn <= 0) break
                        if (g.alive) continue
                        g.alive = true
                        g.anchor = rnd.nextFloat()
                        g.ox = 0f
                        g.oy = 0f
                        g.vx = (rnd.nextFloat() * 30f - 15f) * density
                        g.vy = -(8f + rnd.nextFloat() * 26f) * density
                        g.life = 0f
                        g.maxLife = 0.8f + rnd.nextFloat() * 0.9f
                        g.sizePx = (0.8f + rnd.nextFloat() * 1.5f) * density
                        g.mix = rnd.nextFloat()
                        g.glint = rnd.nextFloat() < 0.16f
                        toSpawn--
                    }
                }
                tick = now
            }
            // Streaming over: let the airborne grains finish their fall, then go quiet.
            if (!enabled && pool.none { it.alive }) break
        }
    }

    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val edgePath = Path().apply { addOutline(outline) }
        val measure = android.graphics.PathMeasure(edgePath.asAndroidPath(), false)
        val length = measure.length
        val pos = FloatArray(2)
        onDrawWithContent {
            drawContent()
            tick // frame-clock read: each physics step invalidates only this draw
            for (g in pool) {
                if (!g.alive) continue
                val fadeIn = (g.life / (0.12f * g.maxLife)).coerceAtMost(1f)
                val fadeOut = ((1f - g.life / g.maxLife) / 0.45f).coerceAtMost(1f)
                val a = (0.9f * fadeIn * fadeOut).coerceIn(0f, 1f)
                if (a <= 0.01f) continue
                measure.getPosTan(g.anchor * length, pos, null)
                val center = Offset(pos[0] + g.ox, pos[1] + g.oy)
                val base = lerp(accent, accent2, g.mix)
                val color = if (g.glint) lerp(base, Starlight, 0.75f) else base
                drawCircle(color.copy(alpha = a * 0.28f), radius = g.sizePx * 2.4f, center = center)
                drawCircle(color.copy(alpha = a), radius = g.sizePx, center = center)
            }
        }
    }
}

/**
 * A one-shot puff of magic sand (2.0): every increment of [tick] flings a handful of grains
 * outward-and-up from the center of whatever this fills, and they arc back down and fade — the
 * composer's send button releasing the message. Draws nothing and costs nothing between puffs.
 * Fill the parent (e.g. `Modifier.matchParentSize()`) over the thing that puffs.
 */
@Composable
fun KeryxPuffBurst(tick: Int, modifier: Modifier = Modifier, grains: Int = 14) {
    val reduced by rememberReducedMotion()
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val density = LocalDensity.current.density
    val pool = remember { Array(grains * 2) { Grain() } }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(tick) {
        if (tick == 0 || reduced) return@LaunchedEffect
        val rnd = Random(SystemClock.uptimeMillis())
        var spawned = 0
        for (g in pool) {
            if (spawned >= grains) break
            if (g.alive) continue
            val angle = rnd.nextFloat() * (Math.PI * 2).toFloat()
            val speed = (36f + rnd.nextFloat() * 54f) * density
            g.alive = true
            g.ox = 0f
            g.oy = 0f
            g.vx = kotlin.math.cos(angle) * speed
            g.vy = kotlin.math.sin(angle) * speed - 24f * density // upward bias
            g.life = 0f
            g.maxLife = 0.45f + rnd.nextFloat() * 0.4f
            g.sizePx = (0.9f + rnd.nextFloat() * 1.4f) * density
            g.mix = rnd.nextFloat()
            g.glint = rnd.nextFloat() < 0.2f
            spawned++
        }
        var last = 0L
        while (pool.any { it.alive }) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                for (g in pool) {
                    if (!g.alive) continue
                    g.life += dt
                    if (g.life >= g.maxLife) {
                        g.alive = false
                        continue
                    }
                    g.vy += 90f * density * dt
                    g.ox += g.vx * dt
                    g.oy += g.vy * dt
                }
                frame = now
            }
        }
    }

    androidx.compose.foundation.Canvas(modifier) {
        frame // frame-clock read
        val cx = size.width / 2f
        val cy = size.height / 2f
        for (g in pool) {
            if (!g.alive) continue
            val fadeOut = ((1f - g.life / g.maxLife) / 0.6f).coerceAtMost(1f)
            val a = (0.95f * fadeOut).coerceIn(0f, 1f)
            val center = Offset(cx + g.ox, cy + g.oy)
            val base = lerp(accent, accent2, g.mix)
            val color = if (g.glint) lerp(base, Starlight, 0.75f) else base
            drawCircle(color.copy(alpha = a * 0.28f), radius = g.sizePx * 2.2f, center = center)
            drawCircle(color.copy(alpha = a), radius = g.sizePx, center = center)
        }
    }
}

/** One grain of the pool. Offsets are pixels relative to its anchor point on the outline. */
private class Grain {
    var anchor = 0f
    var ox = 0f
    var oy = 0f
    var vx = 0f
    var vy = 0f
    var life = 0f
    var maxLife = 1f
    var sizePx = 0f
    var mix = 0f
    var glint = false
    var alive = false
}

/** The glint tint — pale violet starlight, one step off white. */
private val Starlight = Color(0xFFE2D9F3)
