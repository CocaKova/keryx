package chat.keryx.app.presentation.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/**
 * A room's own light.
 *
 * "Colour is light, light means life" (2.2) and "each life has its own colour" (2.3). A room that
 * is not a council still gets a hue of its own, so a drawer of seven rooms is seven lives rather
 * than seven identical circles.
 *
 * This existed twice — byte-identical palettes and lookup functions in NavigationDrawer.kt and
 * QuickRoomsDeck.kt — which was harmless while both only drew circles. It stopped being harmless
 * the moment a third caller wanted the same light for something else: a room-switch wake that
 * carried a *different* colour from the circle you tapped would break the very thing it exists to
 * say. One source, so the light that travels is provably the light you came from.
 */
private val ROOM_LIGHTS = listOf(
    Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFFFB74D),
    Color(0xFFBA68C8), Color(0xFF4DB6AC), Color(0xFF7986CB), Color(0xFFF06292),
)

/**
 * The raw hue, for a filled avatar where the colour is the *ground* and a monogram sits on it.
 *
 * ⚠️ Stable by name and hash — changing the palette's length or order re-colours every room in
 * the drawer, which reads to a user as the app forgetting who is who.
 */
fun roomLightRaw(name: String): Color =
    ROOM_LIGHTS[(name.hashCode() and 0x7FFFFFFF) % ROOM_LIGHTS.size]

/**
 * The same light, for drawing *on* the background rather than under a monogram.
 *
 * These hues are Material 300s — pitched to be a ground with white on top, not a mark on paper.
 * Left raw they measure 1.55:1 to 3.19:1 on parchment, so a room's wake would be a smear of
 * nothing in light mode. Darkened by a fixed 40% they clear 4.07:1 at worst, comfortably past the
 * 3:1 WCAG asks of a graphical object even after the wake's own alpha.
 *
 * A fixed factor rather than a second hand-tuned palette on purpose: the hue is the identity, the
 * ground decides only how hard it is pressed.
 */
@Composable
fun roomLight(name: String): Color {
    val raw = roomLightRaw(name)
    val onVoid = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (onVoid) raw else Color(
        red = raw.red * 0.60f,
        green = raw.green * 0.60f,
        blue = raw.blue * 0.60f,
        alpha = raw.alpha,
    )
}

/**
 * A room's light laid down as an **opaque plate** — the ground a monogram or a sigil sits on.
 *
 * These are Material 300s, "pitched to be a ground with white on top" — which is true of the hue
 * and false of what the avatars actually painted. Both the drawer row and the Quick Rooms deck
 * drew the plate at 70–95% ALPHA and then set the monogram in hard `Color.White`. On the void
 * that survives; on parchment the wash pulls the plate up toward the paper and the letter goes
 * with it — the eight deck plates measured **1.53:1 to 2.45:1**, and the drawer's 15sp bold
 * initial (small text by WCAG's reckoning, so a 4.5 bar) about 1.4:1. Every room in the drawer
 * was a coloured coin with a smudge on it.
 *
 * Composited here instead, so the plate is a real colour that [contrastColorFor] can be asked
 * about. Same appearance, one honest number underneath it.
 */
@Composable
fun roomPlate(base: Color, alpha: Float): Color =
    base.copy(alpha = alpha).compositeOver(MaterialTheme.colorScheme.surface)
