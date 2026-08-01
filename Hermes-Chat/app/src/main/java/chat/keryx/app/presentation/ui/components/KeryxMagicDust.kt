package chat.keryx.app.presentation.ui.components

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Magic sand streams (2.0, third iteration — Jonny: "think magic/sand STREAMS"): not scattered
 * grains but coherent rivulets. A few emitters wander slowly along the shape's outline, each
 * exhaling a dense trail of *fine* motes that share one smoothly-swaying flow direction — the
 * eye reads ribbons of sand pouring off the bubble, not confetti. Motes are velocity-stretched
 * into sub-dp streaks (that stretch is what makes flowing sand look like flowing sand), washed
 * with starlight so they read as light-shot dust rather than solid paint, and a breath of
 * gravity arcs every stream downward at its end.
 *
 * Battery contract unchanged: fixed pool, zero allocation per mote, physics stepped only while
 * [active] or while the last motes finish falling, nothing at all under reduced motion. Place
 * BEFORE `clip()` — the sand lives outside the shape.
 */
@Composable
fun Modifier.keryxMagicDust(
    active: Boolean,
    shape: Shape,
    grains: Int = 96,
): Modifier {
    val reduced by rememberReducedMotion()
    val enabled = active && !reduced
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val density = LocalDensity.current.density
    val pool = remember(grains) { Array(grains) { Grain() } }
    val emitters = remember { Array(3) { Emitter() } }
    // Outline geometry, published by the draw cache (it owns the PathMeasure) and read by the
    // physics loop to spawn ON the edge with a true outward normal. Both run on the UI thread.
    val geo = remember { DustGeo() }
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(enabled) {
        if (!enabled && pool.none { it.alive }) return@LaunchedEffect
        val rnd = Random(SystemClock.uptimeMillis())
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        var last = 0L
        var simT = 0f
        emitters.forEachIndexed { i, e ->
            if (!e.inited) {
                e.inited = true
                e.t = i / 3f + rnd.nextFloat() * 0.15f
                e.drift = (0.015f + rnd.nextFloat() * 0.02f) * (if (i % 2 == 0) 1f else -1f)
                e.omega = 0.8f + rnd.nextFloat() * 0.6f
                e.phase = rnd.nextFloat() * 6.28f
                e.side = if (i % 2 == 0) 1f else -1f
                e.flowing = i == 0 // one stream leads, the others join staggered
                e.stateUntil = rnd.nextFloat() * 1.6f
            }
        }
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                simT += dt
                for (g in pool) {
                    if (!g.alive) continue
                    g.life += dt
                    if (g.life >= g.maxLife) {
                        g.alive = false
                        continue
                    }
                    g.vy += 12f * density * dt // gentle: horizontal ribbons sag, never nosedive
                    g.ox += g.vx * dt
                    g.oy += g.vy * dt
                }
                val measure = geo.measure
                if (enabled && measure != null && geo.length > 0f) {
                    for (e in emitters) {
                        e.t = ((e.t + e.drift * dt) % 1f + 1f) % 1f
                        if (simT >= e.stateUntil) {
                            e.flowing = !e.flowing
                            // Longer rests than flows overlap allows: mostly ONE ribbon at a
                            // time, occasionally two — the attention budget applies to sand too.
                            e.stateUntil = simT +
                                if (e.flowing) 1.6f + rnd.nextFloat() * 1.2f
                                else 1.0f + rnd.nextFloat() * 1.4f
                        }
                        if (!e.flowing) continue
                        // The stream's shared direction: mostly HORIZONTAL (wind-blown sand,
                        // Jonny's call), leaning outward from whichever side of the bubble the
                        // mouth sits on, with a slow pendulum sway. Coherence is the trick.
                        measure.getPosTan(e.t * geo.length, pos, tan)
                        var nx = tan[1]
                        var ny = -tan[0]
                        if (nx * (pos[0] - geo.cx) + ny * (pos[1] - geo.cy) < 0f) {
                            nx = -nx
                            ny = -ny
                        }
                        val side = when {
                            nx > 0.25f -> 1f
                            nx < -0.25f -> -1f
                            else -> e.side // top/bottom edges: keep the emitter's own wind
                        }
                        var dx = side * 0.9f + nx * 0.2f
                        var dy = ny * 0.22f + 0.06f
                        val dn = sqrt(dx * dx + dy * dy)
                        dx /= dn
                        dy /= dn
                        val sway = sin(simT * e.omega + e.phase) * 0.3f
                        val sdx = dx * cos(sway) - dy * sin(sway)
                        val sdy = dx * sin(sway) + dy * cos(sway)
                        e.carry += 16f * dt
                        while (e.carry >= 1f) {
                            e.carry -= 1f
                            val g = pool.firstOrNull { !it.alive } ?: break
                            val speed = (24f + rnd.nextFloat() * 16f) * density
                            g.alive = true
                            g.anchor = e.t
                            g.ox = 0f
                            g.oy = 0f
                            g.vx = sdx * speed + (rnd.nextFloat() - 0.5f) * 8f * density
                            g.vy = sdy * speed + (rnd.nextFloat() - 0.5f) * 5f * density
                            g.life = 0f
                            g.maxLife = 0.55f + rnd.nextFloat() * 0.3f
                            g.sizePx = (0.45f + rnd.nextFloat() * 0.5f) * density
                            g.mix = rnd.nextFloat()
                            g.glint = rnd.nextFloat() < 0.12f
                        }
                    }
                }
                tick = now
            }
            if (!enabled && pool.none { it.alive }) break
        }
    }

    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val edgePath = Path().apply { addOutline(outline) }
        geo.measure = android.graphics.PathMeasure(edgePath.asAndroidPath(), false)
        geo.length = geo.measure?.length ?: 0f
        geo.cx = size.width / 2f
        geo.cy = size.height / 2f
        val pos = FloatArray(2)
        val maxStreak = 4.5f * density
        onDrawWithContent {
            drawContent()
            tick // frame-clock read: each physics step invalidates only this draw
            val measure = geo.measure ?: return@onDrawWithContent
            for (g in pool) {
                if (!g.alive) continue
                val fadeIn = (g.life / (0.1f * g.maxLife)).coerceAtMost(1f)
                val fadeOut = ((1f - g.life / g.maxLife) / 0.5f).coerceAtMost(1f)
                val a = (0.5f * fadeIn * fadeOut).coerceIn(0f, 1f)
                if (a <= 0.01f) continue
                measure.getPosTan(g.anchor * geo.length, pos, null)
                val hx = pos[0] + g.ox
                val hy = pos[1] + g.oy
                // Accent-tinted, starlight-washed: sand shot through with light, not paint.
                val base = lerp(lerp(accent, accent2, g.mix), Starlight, 0.35f)
                // Velocity-stretched streak: the mote's last ~50ms of travel, capped short.
                var tx = g.vx * 0.05f
                var ty = g.vy * 0.05f
                val len = sqrt(tx * tx + ty * ty)
                if (len > maxStreak) {
                    val s = maxStreak / len
                    tx *= s
                    ty *= s
                }
                drawLine(
                    color = base.copy(alpha = a),
                    start = Offset(hx, hy),
                    end = Offset(hx - tx, hy - ty),
                    strokeWidth = g.sizePx,
                )
                if (g.glint) {
                    drawCircle(
                        lerp(base, Color.White, 0.6f).copy(alpha = a),
                        radius = g.sizePx * 1.4f,
                        center = Offset(hx, hy),
                    )
                }
            }
        }
    }
}

/**
 * A one-shot sigh of magic sand (2.0): every increment of [tick] releases a soft mist bloom and
 * a scatter of *fine* grains from the rim of whatever this fills — the send button exhaling the
 * message. Tuned to whisper, not pop: grains are sub-dp starlight-washed motes that barely clear
 * the button before sifting out, and the mist is a translucent accent breath, not a splash.
 * Draws nothing and costs nothing between puffs. Fill the parent (`Modifier.matchParentSize()`).
 */
@Composable
fun KeryxPuffBurst(tick: Int, modifier: Modifier = Modifier, grains: Int = 18) {
    val reduced by rememberReducedMotion()
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val density = LocalDensity.current.density
    val pool = remember { Array(grains * 2) { Grain() } }
    var frame by remember { mutableLongStateOf(0L) }
    var mistBorn by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(tick) {
        if (tick == 0 || reduced) return@LaunchedEffect
        val rnd = Random(SystemClock.uptimeMillis())
        var spawned = 0
        for (g in pool) {
            if (spawned >= grains) break
            if (g.alive) continue
            // Born ON the button's rim, leaving along its own radius with a soft upward lean.
            val angle = rnd.nextFloat() * (Math.PI * 2).toFloat()
            val rim = 21f * density
            val speed = (14f + rnd.nextFloat() * 26f) * density
            g.alive = true
            g.ox = cos(angle) * rim
            g.oy = sin(angle) * rim
            g.vx = cos(angle) * speed
            g.vy = sin(angle) * speed - 10f * density
            g.life = 0f
            g.maxLife = 0.35f + rnd.nextFloat() * 0.35f
            g.sizePx = (0.5f + rnd.nextFloat() * 0.8f) * density
            g.mix = rnd.nextFloat()
            g.glint = rnd.nextFloat() < 0.3f
            spawned++
        }
        mistBorn = -2L // sentinel: stamp with the first frame time below
        var last = 0L
        while (pool.any { it.alive }) {
            withFrameNanos { now ->
                if (mistBorn == -2L) mistBorn = now
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                for (g in pool) {
                    if (!g.alive) continue
                    g.life += dt
                    if (g.life >= g.maxLife) {
                        g.alive = false
                        continue
                    }
                    g.vy += 42f * density * dt
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
        // The mist: one translucent breath swelling just past the rim and dissolving.
        if (mistBorn > 0L) {
            val mistAge = ((frame - mistBorn) / 1e9f)
            val mistLife = 0.55f
            if (mistAge in 0f..mistLife) {
                val p = mistAge / mistLife
                val mistAlpha = (1f - p) * (1f - p) * 0.16f
                val mistRadius = (22f + 16f * p) * density
                drawCircle(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(
                            lerp(accent, accent2, 0.4f).copy(alpha = mistAlpha),
                            lerp(accent, accent2, 0.4f).copy(alpha = mistAlpha * 0.4f),
                            Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = mistRadius,
                    ),
                    radius = mistRadius,
                    center = Offset(cx, cy),
                )
            }
        }
        // The motes: fine, starlight-washed, gone in a breath.
        for (g in pool) {
            if (!g.alive) continue
            val fadeOut = ((1f - g.life / g.maxLife) / 0.65f).coerceAtMost(1f)
            val a = (0.55f * fadeOut).coerceIn(0f, 1f)
            val center = Offset(cx + g.ox, cy + g.oy)
            val base = lerp(lerp(accent, accent2, g.mix), Starlight, 0.55f)
            val color = if (g.glint) lerp(base, Color.White, 0.6f) else base
            drawCircle(color.copy(alpha = a * 0.22f), radius = g.sizePx * 2f, center = center)
            drawCircle(color.copy(alpha = a), radius = g.sizePx, center = center)
        }
    }
}

/** One mote of the pool. Offsets are pixels relative to its anchor point on the outline. */
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

/** One wandering stream mouth on the outline. [side] is its wind direction on flat edges. */
private class Emitter {
    var t = 0f
    var drift = 0f
    var omega = 1f
    var phase = 0f
    var side = 1f
    var stateUntil = 0f
    var flowing = false
    var carry = 0f
    var inited = false
}

/** Outline geometry bridge: written by the draw cache, read by the spawn loop. UI thread only. */
private class DustGeo {
    var measure: android.graphics.PathMeasure? = null
    var length = 0f
    var cx = 0f
    var cy = 0f
}

/** The glint tint — pale violet starlight, one step off white. */
private val Starlight = Color(0xFFE2D9F3)
