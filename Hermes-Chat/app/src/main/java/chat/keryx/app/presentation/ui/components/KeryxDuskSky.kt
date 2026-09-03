package chat.keryx.app.presentation.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay

/**
 * The whole-screen backdrop, with one identity per world (2.2):
 *
 * DARK — the dusk sky: an AGSL shader breathing an aurora in accent 1 across the top on slow
 * value-noise, accent 2 welling up from the foot, and sparse embers drifting upward through the
 * black. Time ticks at ~25fps (the drift is minutes-slow; chasing the display's frame rate would
 * be compositing waste — the pools' drift is slower still), and freezes under
 * reduced motion: the sky keeps its weather, it just stops moving.
 *
 * The ambient void rides in the same shader (2.6.2): two vast accent pools adrift behind
 * everything, drifting a few dozen pixels per MINUTE on a 150 s triangle so the room only ever
 * feels alive and is never seen breathing. They used to be a separate Canvas of Compose radial
 * gradients painted over the sky — and that was the hard line. A Skia gradient is not dithered:
 * its gaussian tail steps through the last two 8-bit levels above black in ~80 px bands, and on
 * an OLED a two-level step next to black is a visible edge (Jonny, 08-19 and again 09-02: "a
 * darker shade to a lighter shade from left to right, a hard line separating them"). Drawn here,
 * the pools are float all the way down and pass through the one dither with everything else.
 *
 * LIGHT — paper-and-ink daylight: a warm still ground with per-pixel tooth (grain you feel more
 * than see) and one breath of accent at the very top. No aurora, no embers, no pools, no
 * animation — in daylight the gilt bubble edge is the only glow the app allows itself.
 *
 * Pre-Android-13 devices get static gradient equivalents of both.
 */
@Composable
fun Modifier.keryxDuskSky(): Modifier {
    val bg = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val dark = bg.luminance() < 0.5f
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return background(
            if (dark) Brush.verticalGradient(
                0.0f to lerp(bg, accent, 0.26f),
                0.30f to bg,
                0.82f to bg,
                1.0f to lerp(bg, accent2, 0.14f),
            ) else Brush.verticalGradient(
                0.0f to lerp(bg, accent, 0.06f),
                0.18f to bg,
                1.0f to bg,
            )
        )
    }
    var timeSec by remember { mutableFloatStateOf(0f) }
    // The pools' drift: mid-way at rest, so Battery Saver stills them mid-drift, not at an end.
    var phase by remember { mutableFloatStateOf(0.5f) }
    if (dark) {
        val reduced by rememberReducedMotion()
        LaunchedEffect(reduced) {
            if (reduced) return@LaunchedEffect
            val born = SystemClock.uptimeMillis()
            val periodMs = 150_000f
            while (true) {
                val now = SystemClock.uptimeMillis()
                timeSec = (now - born) / 1000f
                // Triangle wave: drift out, drift home, never a seam.
                val t = ((now - born) % periodMs.toLong()) / periodMs
                phase = if (t < 0.5f) t * 2f else 2f - t * 2f
                delay(40)
            }
        }
    }
    val shader = remember { RuntimeShader(DUSK_SKY_AGSL) }
    return drawWithCache {
        shader.setFloatUniform("uRes", size.width, size.height)
        shader.setFloatUniform("uTime", timeSec)
        shader.setFloatUniform("uPhase", phase)
        shader.setFloatUniform("uDark", if (dark) 1f else 0f)
        shader.setColorUniform("uBase", bg.toArgb())
        shader.setColorUniform("uAccent", accent.toArgb())
        shader.setColorUniform("uAccent2", accent2.toArgb())
        val brush = ShaderBrush(shader)
        onDrawBehind { drawRect(brush) }
    }
}

private const val DUSK_SKY_AGSL = """
uniform float2 uRes;
uniform float uTime;
uniform float uPhase;
uniform float uDark;
layout(color) uniform half4 uBase;
layout(color) uniform half4 uAccent;
layout(color) uniform half4 uAccent2;

// Sine-free hash (Hoskins). The fract(sin(big)) hash this replaced degrades on mobile GPUs
// once its argument climbs into the thousands — sin() there is only accurate near zero — and
// pixel coordinates and the ember grid's time term both get there fast. When the dither's hash
// stops being noise it stops being dither, and the bands it was hiding come back, structured.
float hash(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * vnoise(p);
        p = p * 2.03 + 11.3;
        a *= 0.5;
    }
    return v;
}

half4 main(float2 frag) {
    float2 uv = frag / uRes;
    float3 col = uBase.rgb;

    if (uDark > 0.5) {
        float t = uTime * 0.02;

        // Amber aurora: a noise-shaped curtain breathing along the top edge.
        float n = fbm(float2(uv.x * 2.2 + t, uv.y * 3.0 - t * 0.6));
        float topGlow = exp(-uv.y * 3.2) * (0.55 + 0.45 * n);
        col = mix(col, uAccent.rgb, topGlow * 0.42);

        // Dusk violet welling up from the foot on its own slower weather.
        float n2 = fbm(float2(uv.x * 1.7 - t * 0.7, uv.y * 2.4 + t));
        float botGlow = exp(-(1.0 - uv.y) * 4.0) * (0.40 + 0.50 * n2);
        col = mix(col, uAccent2.rgb, botGlow * 0.32);

        // The void's pools — accent 1 adrift near the top-left, accent 2 near the foot-right,
        // each a gaussian in screen-width units (the old Canvas stops approximated this curve;
        // exp() IS it, with no last band to see). Distances in pixels so the pools stay round.
        float2 c1 = float2(0.10 + 0.28 * uPhase, 0.08 + 0.10 * uPhase) * uRes;
        float d1 = length(frag - c1) / (uRes.x * 0.78);
        col = mix(col, uAccent.rgb, 0.11 * exp(-3.2 * d1 * d1));
        float2 c2 = float2(0.92 - 0.30 * uPhase, 0.85 - 0.12 * uPhase) * uRes;
        float d2 = length(frag - c2) / (uRes.x * 0.74);
        col = mix(col, uAccent2.rgb, 0.09 * exp(-3.2 * d2 * d2));

        // Embers: ~2.4% of grid cells carry a spark; scrolling the grid against time lifts them.
        float2 g = float2(uv.x * 26.0, uv.y * 46.0 + uTime * 0.55);
        float2 cell = floor(g);
        float h = hash(cell);
        float2 jitter = (float2(hash(cell + 7.7), hash(cell + 3.3)) - 0.5) * 0.6;
        float spark = smoothstep(0.12, 0.0, length(fract(g) - 0.5 + jitter));
        float alive = step(0.976, h) * (0.35 + 0.65 * fract(h * 91.7));
        float twinkle = 0.5 + 0.5 * sin(uTime * (1.5 + h * 3.0) + h * 40.0);
        col += uAccent.rgb * spark * alive * twinkle * 0.85 * smoothstep(0.15, 0.75, uv.y);
    } else {
        // Paper: still, warm, with tooth — and one breath of accent where the sky would be.
        float grain = vnoise(frag * 0.9);
        col *= 1.0 - 0.035 * grain;
        col = mix(col, uAccent.rgb, exp(-uv.y * 6.0) * 0.07);
    }

    // Dither. Every colour above is computed in float and then crushed to 8 bits per channel, and
    // a wide, shallow gradient crosses a step every few hundred pixels — which the eye sharpens
    // into a hard vertical edge that is not in the maths at all (Jonny, 08-19: "a big portion of
    // the left is lighter and the right is a hard cut to a darker tone" — measured at TWO of 255).
    // Triangular (two hashes summed, ±1 LSB) rather than a flat sub-quantum: a rectangular
    // dither narrower than a step leaves the step's centre line intact, the triangular one
    // scatters every step fully across a band and the edge stops existing. Keyed on the pixel
    // only, never on time, so it is a fixed grain and not a shimmer. Everything the backdrop
    // draws now passes through this line — the pools included, which is the point.
    //
    // ⚠️ It gets more visible, not less, as the accent gets more saturated: the same step in a
    // deep orange sky reads far harder than in the blue this was designed against.
    col += (hash(frag) + hash(frag + 0.5) - 1.0) * (1.0 / 255.0);

    return half4(col, 1.0);
}
"""
