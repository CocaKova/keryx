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
 * be compositing waste — same budget philosophy as AmbientVoid's 4 Hz), and freezes under
 * reduced motion: the sky keeps its weather, it just stops moving.
 *
 * LIGHT — paper-and-ink daylight: a warm still ground with per-pixel tooth (grain you feel more
 * than see) and one breath of accent at the very top. No aurora, no embers, no animation — in
 * daylight the gilt bubble edge is the only glow the app allows itself.
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
    if (dark) {
        val reduced by rememberReducedMotion()
        LaunchedEffect(reduced) {
            if (reduced) return@LaunchedEffect
            val born = SystemClock.uptimeMillis()
            while (true) {
                timeSec = (SystemClock.uptimeMillis() - born) / 1000f
                delay(40)
            }
        }
    }
    val shader = remember { RuntimeShader(DUSK_SKY_AGSL) }
    return drawWithCache {
        shader.setFloatUniform("uRes", size.width, size.height)
        shader.setFloatUniform("uTime", timeSec)
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
uniform float uDark;
layout(color) uniform half4 uBase;
layout(color) uniform half4 uAccent;
layout(color) uniform half4 uAccent2;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
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
    // A sub-quantum of noise per pixel scatters each step across a band and the edge stops
    // existing. Keyed on the pixel only, never on time, so it is a fixed grain and not a shimmer.
    //
    // ⚠️ It gets more visible, not less, as the accent gets more saturated: the same step in a
    // deep orange sky reads far harder than in the blue this was designed against.
    col += (hash(frag) - 0.5) * (1.6 / 255.0);

    return half4(col, 1.0);
}
"""
