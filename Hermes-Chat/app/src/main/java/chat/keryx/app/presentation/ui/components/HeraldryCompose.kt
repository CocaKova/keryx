package chat.keryx.app.presentation.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import chat.keryx.app.domain.model.Heraldry
import chat.keryx.app.domain.model.Heralds

/** The configured heralds, provided once near the root so any bubble can resolve a sender's light. */
data class HeraldConfig(
    /** Configured agent ids in order; the first is the primary herald. */
    val ids: List<String> = emptyList(),
    /** localpart -> "#RRGGBB" user overrides. */
    val overrides: Map<String, String> = emptyMap(),
) {
    val council: Boolean get() = ids.size > 1
}

val LocalHeraldConfig = compositionLocalOf { HeraldConfig() }

/** A herald's resolved light, in Compose colors. */
data class HeraldLight(
    val name: String,
    val accent: Color,
    val accent2: Color,
    /** The primary herald keeps the user's theme accents, so a 1:1 room looks exactly like 2.2. */
    val primary: Boolean,
)

private fun parseHex(hex: String): Long? = try {
    val v = hex.removePrefix("#")
    if (v.length == 6) 0xFF000000L or v.toLong(16) else null
} catch (_: Exception) { null }

/**
 * Resolve the light for one sender. Reads the provided [HeraldConfig] and the live theme accents,
 * so an override, a palette hue and the user's own accents all arrive through the same call.
 */
@Composable
fun heraldLightFor(senderId: String, senderName: String): HeraldLight {
    val cfg = LocalHeraldConfig.current
    val cs = MaterialTheme.colorScheme
    val h: Heraldry = Heralds.resolve(
        senderId = senderId,
        senderName = senderName,
        ids = cfg.ids,
        overrides = cfg.overrides.mapNotNull { (k, v) -> parseHex(v)?.let { k to it } }.toMap(),
        themeAccent = argbOf(cs.primary),
        themeAccent2 = argbOf(cs.tertiary),
    )
    return HeraldLight(
        name = h.name,
        accent = Color(h.accentArgb.toInt()),
        accent2 = Color(h.accent2Argb.toInt()),
        primary = h.primary,
    )
}

/** Compose Color -> 0xAARRGGBB long, the form the pure heraldry layer speaks. */
private fun argbOf(c: Color): Long {
    val a = (c.alpha * 255f + 0.5f).toLong() and 0xFF
    val r = (c.red * 255f + 0.5f).toLong() and 0xFF
    val g = (c.green * 255f + 0.5f).toLong() and 0xFF
    val b = (c.blue * 255f + 0.5f).toLong() and 0xFF
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** The herald's staff, inline, in that herald's light. */
@Composable
fun HeraldSigil(
    light: HeraldLight,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
    alpha: Float = 1f,
) {
    Text(
        text = Heralds.SIGIL,
        color = light.accent.copy(alpha = alpha),
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}
