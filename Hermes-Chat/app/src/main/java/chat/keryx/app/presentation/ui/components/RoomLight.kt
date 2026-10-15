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
fun roomLightRaw(name: String): Color = ROOM_LIGHTS[roomLightIndex(name)]

/** Which of the eight lights a room wears. Stable by name — see the warning above. */
internal fun roomLightIndex(name: String): Int = (name.hashCode() and 0x7FFFFFFF) % ROOM_LIGHTS.size

/**
 * A room's tinted plate (3.0 redesign): the same eight hue families in the same order as
 * [ROOM_LIGHTS], so no room changes colour, pitched to Keryx's paper and void instead of
 * Material 300s. On paper a soft ground under a deep monogram; on the void a deep ground under a
 * light one. Every pair clears 4.5:1 (RoomTintTest): the monogram is ink, not a smudge.
 */
data class RoomTint(val ground: Color, val ink: Color)

internal val ROOM_TINTS_PAPER = listOf(
    RoomTint(Color(0xFFF2D7D0), Color(0xFF8A2F22)), // red → clay
    RoomTint(Color(0xFFD6E1F0), Color(0xFF2A4A78)), // blue → slate
    RoomTint(Color(0xFFD4E6DD), Color(0xFF245A45)), // green → verdigris
    RoomTint(Color(0xFFF6DCC8), Color(0xFF8A3500)), // orange → amber
    RoomTint(Color(0xFFE4DBF7), Color(0xFF4E2F9C)), // purple → violet
    RoomTint(Color(0xFFCFE6E4), Color(0xFF1F5A57)), // teal → sea
    RoomTint(Color(0xFFDCDDF2), Color(0xFF383C86)), // indigo → dusk
    RoomTint(Color(0xFFF3D6E3), Color(0xFF84284F)), // pink → rose
)

internal val ROOM_TINTS_VOID = listOf(
    RoomTint(Color(0xFF3A1E18), Color(0xFFF2A598)),
    RoomTint(Color(0xFF172539), Color(0xFF9DBDEB)),
    RoomTint(Color(0xFF16302A), Color(0xFF8FD1B5)),
    RoomTint(Color(0xFF3B2214), Color(0xFFFFB27A)),
    RoomTint(Color(0xFF26193F), Color(0xFFC4B2FF)),
    RoomTint(Color(0xFF123230), Color(0xFF86D3CD)),
    RoomTint(Color(0xFF1E2045), Color(0xFFAEB2F2)),
    RoomTint(Color(0xFF3A1628), Color(0xFFF2A0C4)),
)

/** [name]'s plate for the current theme. */
@Composable
fun roomTint(name: String): RoomTint {
    val onVoid = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return (if (onVoid) ROOM_TINTS_VOID else ROOM_TINTS_PAPER)[roomLightIndex(name)]
}

/**
 * The same light, for drawing *on* the background rather than under a monogram: the room-switch
 * wake, the light sweep, a project's tint.
 *
 * Until 3.0 this was the raw Material 300 darkened by a fixed 40% on parchment (raw, they measured
 * 1.55:1 to 3.19:1 there; darkened, 4.07:1 at worst). Now it is [roomTint]'s ink, which was chosen
 * for contrast on both grounds in the first place, so the wake, the sweep and the monogram are one
 * colour per room.
 */
@Composable
fun roomLight(name: String): Color =
    // Since 3.0 the light is the plate's own monogram ink, so the wake that crosses the room is
    // exactly the colour of the letter you tapped. The inks clear the 3:1 asked above on both
    // grounds with room to spare (deep on paper, light on the void).
    roomTint(name).ink

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
