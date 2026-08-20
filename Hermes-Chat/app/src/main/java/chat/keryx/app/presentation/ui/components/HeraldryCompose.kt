package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import chat.keryx.app.domain.model.Heraldry
import chat.keryx.app.domain.model.Heralds
import chat.keryx.app.domain.model.RoomSigil

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
        // The council dresses for the ground it stands on: the void palette is unreadable on
        // parchment and vice versa. Slot assignment is untouched, so a herald keeps its identity
        // across a theme flip — only the ink it is printed in changes.
        palette = if (cs.background.luminance() < 0.5f) Heralds.PALETTE else Heralds.PALETTE_PAPER,
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

/** A herald's accent by bare key ("milo"), for places that hold keys rather than MXIDs. */
@Composable
fun heraldAccentFor(key: String): Color = heraldLightFor(key, key).accent

/**
 * A room avatar that says "an agent lives here": the herald's staff in place of the lettered
 * monogram. See [RoomSigil] for why a single-herald room wears the ROOM's hue and a council
 * wears its members'.
 *
 * Drawn only when the room actually has a herald among its loaded members — otherwise the two
 * call sites keep their own monogram, unchanged. [base] is the caller's own avatar colour for
 * this room, so the drawer and the deck each keep their palette.
 */
@Composable
fun RoomSigilAvatar(
    sigil: RoomSigil,
    base: Color,
    size: Dp,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val shell = modifier
        .size(size)
        .clip(CircleShape)
    when (sigil) {
        is RoomSigil.None -> Unit
        is RoomSigil.Single -> Box(
            contentAlignment = Alignment.Center,
            modifier = shell.background(base.copy(alpha = if (highlighted) 0.95f else 0.75f)),
        ) {
            Text(
                text = Heralds.SIGIL,
                color = Color.White,
                fontSize = (size.value * 0.46f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        is RoomSigil.Stack -> Box(
            contentAlignment = Alignment.Center,
            // A council gets the void, not a room hue — the members ARE the colour here, and a
            // tinted plate behind several accents muddies all of them.
            modifier = shell.background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (highlighted) 0.95f else 0.7f)
            ),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(size * 0.02f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sigil.heraldKeys.forEach { key ->
                    Text(
                        text = Heralds.SIGIL,
                        color = heraldAccentFor(key),
                        fontSize = (size.value * (if (sigil.heraldKeys.size >= 3) 0.30f else 0.38f)).sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
