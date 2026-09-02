package chat.keryx.app.presentation.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Keryx's one icon family. Every control glyph the chrome draws — doors, top bar, composer,
 * bubble actions, space headers — comes from here, so a surface never mixes a Material icon
 * with a hand-drawn one (2.6.2 design pass: the composer had four vocabularies on one row).
 *
 * Harvested whole from Talaria's `TalariaGlyphs` (itself drawn after the Hermes desktop's
 * icon set) and extended with the doors Keryx has that Talaria never did. A 24-unit viewport,
 * round stroked paths at 1.9, tinted by the caller — the black here is a placeholder the
 * `Icon` tint replaces.
 */
object KeryxGlyphs {

    private fun draw(
        name: String,
        strokes: List<String>,
        fills: List<String> = emptyList(),
        strokeWidth: Float = 1.9f,
    ): ImageVector {
        val b = ImageVector.Builder(
            name = "keryx.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (d in strokes) {
            b.addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        for (d in fills) {
            b.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        }
        return b.build()
    }

    /** Sidebar toggle — an editor's left-panel frame, not a hamburger. */
    val Sidebar: ImageVector by lazy {
        draw(
            "sidebar",
            listOf(
                "M5.5,5 h13 a2,2 0 0 1 2,2 v10 a2,2 0 0 1 -2,2 h-13 a2,2 0 0 1 -2,-2 v-10 a2,2 0 0 1 2,-2 z",
                "M9.75,5 V19",
            ),
        )
    }

    /** New session — desktop's new-chat glyph is a little robot. Ours too. */
    val Robot: ImageVector by lazy {
        draw(
            "robot",
            listOf(
                "M12,4.75 V7.5",
                "M7.5,7.5 h9 a2,2 0 0 1 2,2 v6 a2,2 0 0 1 -2,2 h-9 a2,2 0 0 1 -2,-2 v-6 a2,2 0 0 1 2,-2 z",
                "M9.6,11.4 v2.2",
                "M14.4,11.4 v2.2",
                "M3.5,11.5 v3",
                "M20.5,11.5 v3",
            ),
        )
    }

    /** Compact — both halves of the transcript pressing toward one line. */
    val Compact: ImageVector by lazy {
        draw(
            "compact",
            listOf(
                "M5,12 H19",
                "M9,5.5 L12,8.5 L15,5.5",
                "M9,18.5 L12,15.5 L15,18.5",
            ),
        )
    }

    /** Gateway — a live pulse line (desktop's healthy-gateway Activity glyph). */
    val Pulse: ImageVector by lazy {
        draw(
            "pulse",
            listOf("M3.5,12 h4 L10,5.5 L14,18.5 L16.5,12 h4"),
        )
    }

    /** Models — a stack of planes. */
    val Stack: ImageVector by lazy {
        draw(
            "stack",
            listOf(
                "M12,4.5 L20,8.9 L12,13.3 L4,8.9 Z",
                "M4,12.4 L12,16.8 L20,12.4",
                "M4,15.7 L12,20.1 L20,15.7",
            ),
        )
    }

    /** Kanban — three lanes, work at different depths. */
    val Board: ImageVector by lazy {
        draw(
            "board",
            listOf(
                "M3.6,4.5 h4.7 v10.2 h-4.7 Z",
                "M9.7,4.5 h4.7 v6.2 h-4.7 Z",
                "M15.8,4.5 h4.7 v13.6 h-4.7 Z",
            ),
        )
    }

    /** Cron — a stopwatch. */
    val Watch: ImageVector by lazy {
        draw(
            "watch",
            listOf(
                "M12,7.4 a5.8,5.8 0 1 1 -0.001,0 z",
                "M12,13.2 V10.2",
                "M12,13.2 H14.4",
                "M12,4.4 V6.2",
                "M9.9,4.4 h4.2",
            ),
        )
    }

    /** Archive — the box. */
    val Archive: ImageVector by lazy {
        draw(
            "archive",
            listOf(
                "M4.5,5.5 h15 a1,1 0 0 1 1,1 v2 a1,1 0 0 1 -1,1 h-15 a1,1 0 0 1 -1,-1 v-2 a1,1 0 0 1 1,-1 z",
                "M5.5,9.5 V17.5 a1.5,1.5 0 0 0 1.5,1.5 h10 a1.5,1.5 0 0 0 1.5,-1.5 V9.5",
                "M10,12.75 h4",
            ),
        )
    }

    /** Projects — a folder with a tucked tab, the workspace the sessions live in. */
    val Folder: ImageVector by lazy {
        draw(
            "folder",
            listOf(
                "M3.5,7 a1.5,1.5 0 0 1 1.5,-1.5 h4.2 l2,2.3 h7.8 a1.5,1.5 0 0 1 1.5,1.5 v8.2 a1.5,1.5 0 0 1 -1.5,1.5 h-14 a1.5,1.5 0 0 1 -1.5,-1.5 z",
            ),
        )
    }

    /** Settings — slider rails with beads. */
    val Sliders: ImageVector by lazy {
        draw(
            "sliders",
            listOf(
                "M4.5,7 H19.5", "M14.5,5.1 a1.9,1.9 0 1 1 -0.001,0 z",
                "M4.5,12 H19.5", "M9,10.1 a1.9,1.9 0 1 1 -0.001,0 z",
                "M4.5,17 H19.5", "M15.5,15.1 a1.9,1.9 0 1 1 -0.001,0 z",
            ),
        )
    }

    val Search: ImageVector by lazy {
        draw(
            "search",
            listOf(
                "M10.8,5.5 a5.3,5.3 0 1 1 -0.001,0 z",
                "M14.9,14.9 L19.5,19.5",
            ),
        )
    }

    /** Send — desktop sends with an arrow-up in a filled circle, never a paper plane. */
    val ArrowUp: ImageVector by lazy {
        draw(
            "arrowUp",
            listOf(
                "M12,19 V5.5",
                "M6.8,10.7 L12,5.5 L17.2,10.7",
            ),
            strokeWidth = 2.1f,
        )
    }

    /** Stop — a plain filled square; the fill is the icon. */
    val StopSquare: ImageVector by lazy {
        draw(
            "stopSquare",
            strokes = emptyList(),
            fills = listOf(
                "M9.25,7.75 h5.5 a1.5,1.5 0 0 1 1.5,1.5 v5.5 a1.5,1.5 0 0 1 -1.5,1.5 h-5.5 a1.5,1.5 0 0 1 -1.5,-1.5 v-5.5 a1.5,1.5 0 0 1 1.5,-1.5 z",
            ),
        )
    }

    val Mic: ImageVector by lazy {
        draw(
            "mic",
            listOf(
                "M12,4.25 a2.4,2.4 0 0 1 2.4,2.4 V11 a2.4,2.4 0 0 1 -4.8,0 V6.65 a2.4,2.4 0 0 1 2.4,-2.4 z",
                "M7,11.25 a5,5 0 0 0 10,0",
                "M12,16.25 V19.5",
            ),
        )
    }

    val Plus: ImageVector by lazy {
        draw("plus", listOf("M12,5.5 V18.5", "M5.5,12 H18.5"))
    }

    val Close: ImageVector by lazy {
        draw("close", listOf("M6.5,6.5 L17.5,17.5", "M17.5,6.5 L6.5,17.5"))
    }

    val Image: ImageVector by lazy {
        draw(
            "image",
            listOf(
                "M6,5.5 h12 a2,2 0 0 1 2,2 v9 a2,2 0 0 1 -2,2 h-12 a2,2 0 0 1 -2,-2 v-9 a2,2 0 0 1 2,-2 z",
                "M9,8.6 a1.4,1.4 0 1 1 -0.001,0 z",
                "M4.5,15.8 L9.6,10.9 L13.2,14.3 L15.8,11.9 L19.6,15.5",
            ),
        )
    }

    val FileClip: ImageVector by lazy {
        draw(
            "fileClip",
            listOf(
                "M7,4.5 h6.5 L18,9 V19.5 H7 Z",
                "M13.5,4.5 V9 H18",
            ),
        )
    }

    val Calendar: ImageVector by lazy {
        draw(
            "calendar",
            listOf(
                "M6,6.5 h12 a1.5,1.5 0 0 1 1.5,1.5 v10 a1.5,1.5 0 0 1 -1.5,1.5 h-12 a1.5,1.5 0 0 1 -1.5,-1.5 v-10 a1.5,1.5 0 0 1 1.5,-1.5 z",
                "M4.5,10.75 H19.5",
                "M8.5,4.5 V8",
                "M15.5,4.5 V8",
            ),
        )
    }

    val Sun: ImageVector by lazy {
        draw(
            "sun",
            listOf(
                "M12,8.6 a3.4,3.4 0 1 1 -0.001,0 z",
                "M12,4 V5.9", "M12,18.1 V20", "M4,12 H5.9", "M18.1,12 H20",
                "M6.34,6.34 L7.7,7.7", "M16.3,16.3 L17.66,17.66",
                "M17.66,6.34 L16.3,7.7", "M7.7,16.3 L6.34,17.66",
            ),
        )
    }

    val Moon: ImageVector by lazy {
        draw(
            "moon",
            listOf("M20,14.5 A8.3,8.3 0 1 1 9.5,4 A6.6,6.6 0 0 0 20,14.5 Z"),
        )
    }

    /** System theme — a half-lit disc. */
    val AutoTheme: ImageVector by lazy {
        draw(
            "autoTheme",
            strokes = listOf("M12,4.5 a7.5,7.5 0 1 1 -0.001,0 z"),
            fills = listOf("M12,4.5 a7.5,7.5 0 0 1 0,15 z"),
        )
    }

    val Kebab: ImageVector by lazy {
        draw(
            "kebab",
            strokes = emptyList(),
            fills = listOf(
                "M12,4.1 a1.4,1.4 0 1 1 -0.001,0 z",
                "M12,10.6 a1.4,1.4 0 1 1 -0.001,0 z",
                "M12,17.1 a1.4,1.4 0 1 1 -0.001,0 z",
            ),
        )
    }

    val Download: ImageVector by lazy {
        draw(
            "download",
            listOf(
                "M12,4.5 V14.5",
                "M7.5,10 L12,14.5 L16.5,10",
                "M5,19.25 H19",
            ),
        )
    }

    val Share: ImageVector by lazy {
        draw(
            "share",
            listOf(
                "M6,9.7 a2.3,2.3 0 1 1 -0.001,0 z",
                "M17,3.7 a2.3,2.3 0 1 1 -0.001,0 z",
                "M17,15.7 a2.3,2.3 0 1 1 -0.001,0 z",
                "M8.1,11 L14.9,7.1",
                "M8.1,13 L14.9,16.9",
            ),
        )
    }

    val Reply: ImageVector by lazy {
        draw(
            "reply",
            listOf(
                "M9.5,6.5 L4.5,11.5 L9.5,16.5",
                "M4.5,11.5 H12.5 a7,7 0 0 1 7,7 v0.75",
            ),
        )
    }

    val Copy: ImageVector by lazy {
        draw(
            "copy",
            listOf(
                "M10,4.5 h8 a1.5,1.5 0 0 1 1.5,1.5 v8 a1.5,1.5 0 0 1 -1.5,1.5 h-8 a1.5,1.5 0 0 1 -1.5,-1.5 v-8 a1.5,1.5 0 0 1 1.5,-1.5 z",
                "M14.5,15.5 V18 a1.5,1.5 0 0 1 -1.5,1.5 H6 A1.5,1.5 0 0 1 4.5,18 V10 A1.5,1.5 0 0 1 6,8.5 H8.5",
            ),
        )
    }

    val Bookmark: ImageVector by lazy {
        draw("bookmark", listOf("M7,4.75 H17 V19.25 L12,15.6 L7,19.25 Z"))
    }

    val BookmarkFilled: ImageVector by lazy {
        draw(
            "bookmarkFilled",
            strokes = listOf("M7,4.75 H17 V19.25 L12,15.6 L7,19.25 Z"),
            fills = listOf("M7,4.75 H17 V19.25 L12,15.6 L7,19.25 Z"),
        )
    }

    /** A pushpin — the gateway's keep flag on a session. Head, shoulders, needle. */
    val Pin: ImageVector by lazy {
        draw(
            "pin",
            listOf(
                "M8.5,2.75 H15.5",
                "M9.75,2.75 V9 L6.75,12 V13.75 H17.25 V12 L14.25,9 V2.75",
                "M12,13.75 V21.25",
            ),
        )
    }

    val PinFilled: ImageVector by lazy {
        draw(
            "pinFilled",
            strokes = listOf("M8.5,2.75 H15.5", "M12,13.75 V21.25"),
            fills = listOf("M9.75,2.75 H14.25 V9 L17.25,12 V13.75 H6.75 V12 L9.75,9 Z"),
        )
    }

    val Trash: ImageVector by lazy {
        draw(
            "trash",
            listOf(
                "M5,7 H19",
                "M9.5,7 V4.75 H14.5 V7",
                "M6.5,7 L7.4,19.4 H16.6 L17.5,7",
                "M10,10.5 V16", "M14,10.5 V16",
            ),
        )
    }

    val Volume: ImageVector by lazy {
        draw(
            "volume",
            listOf(
                "M4.5,9.5 H7.5 L12,5.5 V18.5 L7.5,14.5 H4.5 Z",
                "M15,9 a4.5,4.5 0 0 1 0,6",
                "M17.5,6.5 a8,8 0 0 1 0,11",
            ),
        )
    }

    val Play: ImageVector by lazy {
        draw("play", listOf("M9,6.5 L18,12 L9,17.5 Z"))
    }

    /** Connection — Settings' link/plug row. */
    val Plug: ImageVector by lazy {
        draw(
            "plug",
            listOf(
                "M9,4.5 V9", "M15,4.5 V9",
                "M6.5,9 H17.5 V12 a5.5,5.5 0 0 1 -11,0 Z",
                "M12,17.5 V19.75",
            ),
        )
    }

    /** Identity — Settings' account row. */
    val Person: ImageVector by lazy {
        draw(
            "person",
            listOf(
                "M12,5.5 a3.5,3.5 0 1 1 -0.001,0 z",
                "M5,19.5 a7,7 0 0 1 14,0",
            ),
        )
    }

    /** Appearance — Settings' theme row: a paint swatch card. */
    val Swatch: ImageVector by lazy {
        draw(
            "swatch",
            listOf(
                "M12,4.5 a7.5,7.5 0 1 0 0.001,15.2 c1.4,0 1.9,-1 1.4,-2 c-0.6,-1.2 0.2,-2.4 1.6,-2.4 h1.9 a2.6,2.6 0 0 0 2.6,-2.6 A7.9,7.9 0 0 0 12,4.5 z",
                "M8.3,9.2 a1.1,1.1 0 1 1 -0.001,0 z",
                "M12.6,7.6 a1.1,1.1 0 1 1 -0.001,0 z",
            ),
        )
    }

    /** Privacy — Settings' lock row. */
    val Lock: ImageVector by lazy {
        draw(
            "lock",
            listOf(
                "M7,11 h10 a1.5,1.5 0 0 1 1.5,1.5 v6 a1.5,1.5 0 0 1 -1.5,1.5 H7 a1.5,1.5 0 0 1 -1.5,-1.5 v-6 A1.5,1.5 0 0 1 7,11 z",
                "M8.5,11 V8.25 a3.5,3.5 0 0 1 7,0 V11",
            ),
        )
    }

    /** Diagnostics — Settings' debug row: a probe scope. */
    val Scope: ImageVector by lazy {
        draw(
            "scope",
            listOf(
                "M12,5.5 a6.5,6.5 0 1 1 -0.001,0 z",
                "M12,4 V6.5", "M12,17.5 V20", "M4,12 H6.5", "M17.5,12 H20",
                "M12,10.9 a1.1,1.1 0 1 1 -0.001,0 z",
            ),
        )
    }

    /** Sign out. */
    val Exit: ImageVector by lazy {
        draw(
            "exit",
            listOf(
                "M10,4.5 H6.5 A1.5,1.5 0 0 0 5,6 V18 a1.5,1.5 0 0 0 1.5,1.5 H10",
                "M15,8.5 L18.5,12 L15,15.5",
                "M18.5,12 H9.5",
            ),
        )
    }

    /** Voice/interface — Settings dictation row. */
    val Wave: ImageVector by lazy {
        draw(
            "wave",
            listOf(
                "M4.5,10.5 V13.5", "M8.25,7.5 V16.5", "M12,4.75 V19.25",
                "M15.75,8.5 V15.5", "M19.5,11 V13",
            ),
        )
    }

    /** Steer — desktop's mid-turn redirect (a steering wheel, per its SteeringWheel icon). */
    val Steer: ImageVector by lazy {
        draw(
            "steer",
            strokes = listOf(
                "M12,5 a7,7 0 1 1 -0.001,0 z",
                "M12,10.2 V5",
                "M13.6,12.9 L18.1,15.6",
                "M10.4,12.9 L5.9,15.6",
            ),
            fills = listOf("M12,10.2 a1.8,1.8 0 1 1 -0.001,0 z"),
        )
    }

    val ChevronRight: ImageVector by lazy {
        draw("chevronRight", listOf("M9.5,6.5 L15,12 L9.5,17.5"))
    }

    val ChevronDown: ImageVector by lazy {
        draw("chevronDown", listOf("M6.5,9.5 L12,15 L17.5,9.5"))
    }

    // ---- Keryx's own doors and verbs (not in Talaria) --------------------------------------

    /** Shipyard — a git branch: trunk, one fork, three commits. */
    val GitBranch: ImageVector by lazy {
        draw(
            "gitBranch",
            listOf(
                "M7,6.5 V17.5",
                "M17,8.5 a5.5,5.5 0 0 1 -5.5,5.5 H7",
            ),
            fills = listOf(
                "M7,4.2 a1.9,1.9 0 1 1 -0.001,0 z",
                "M7,17.5 a1.9,1.9 0 1 1 -0.001,0 z",
                "M17,6.2 a1.9,1.9 0 1 1 -0.001,0 z",
            ),
        )
    }

    /** Workshop — a wrench. */
    val Wrench: ImageVector by lazy {
        draw(
            "wrench",
            listOf(
                "M14.2,5.2 a4.2,4.2 0 0 0 -3.4,6.1 L5,17.1 a1.6,1.6 0 0 0 2.3,2.3 l5.8,-5.8 a4.2,4.2 0 0 0 6.1,-3.4 l-2.6,2.6 -2.6,-0.5 -0.5,-2.6 z",
            ),
        )
    }

    /** Call — a handset. */
    val Phone: ImageVector by lazy {
        draw(
            "phone",
            listOf(
                "M6.2,4.6 h2.6 l1.4,3.6 -1.9,1.4 a10.5,10.5 0 0 0 5.8,5.8 l1.4,-1.9 3.6,1.4 v2.6 a1.6,1.6 0 0 1 -1.7,1.6 A14,14 0 0 1 4.6,6.3 a1.6,1.6 0 0 1 1.6,-1.7 z",
            ),
        )
    }

    /** New session — a speech bubble with a plus inside it. */
    val NewChat: ImageVector by lazy {
        draw(
            "newChat",
            listOf(
                "M5.5,5.5 h13 a2,2 0 0 1 2,2 v8 a2,2 0 0 1 -2,2 h-7.5 l-4,3.2 v-3.2 h-1.5 a2,2 0 0 1 -2,-2 v-8 a2,2 0 0 1 2,-2 z",
                "M12,8.6 V14.4",
                "M9.1,11.5 H14.9",
            ),
        )
    }

    /** Refresh — one open arc with an arrowhead. */
    val Refresh: ImageVector by lazy {
        draw(
            "refresh",
            listOf(
                "M18.5,12 a6.5,6.5 0 1 1 -1.9,-4.6",
                "M18.8,4.6 V8.4 H15",
            ),
        )
    }

    /** Warning — a triangle with a bang. */
    val Warning: ImageVector by lazy {
        draw(
            "warning",
            listOf(
                "M12,4.6 L20.2,18.6 H3.8 Z",
                "M12,9.6 V13.6",
            ),
            fills = listOf("M12,15.6 a1,1 0 1 1 -0.001,0 z"),
        )
    }

    /** Temporary — an hourglass. */
    val Hourglass: ImageVector by lazy {
        draw(
            "hourglass",
            listOf(
                "M7,4.5 h10",
                "M7,19.5 h10",
                "M8.2,4.5 v2.6 a3.8,3.8 0 0 0 1.8,3.2 L12,12 l-2,1.7 a3.8,3.8 0 0 0 -1.8,3.2 v2.6",
                "M15.8,4.5 v2.6 a3.8,3.8 0 0 1 -1.8,3.2 L12,12 l2,1.7 a3.8,3.8 0 0 1 1.8,3.2 v2.6",
            ),
        )
    }

    /** Pinned — a five-point star. */
    val Star: ImageVector by lazy {
        draw(
            "star",
            listOf("M12,4.6 l2.2,4.6 5,0.7 -3.6,3.5 0.9,5 -4.5,-2.4 -4.5,2.4 0.9,-5 -3.6,-3.5 5,-0.7 z"),
        )
    }

    /** Done — a check. */
    val Check: ImageVector by lazy {
        draw("check", listOf("M5.5,12.5 L10,17 L18.5,7.5"), strokeWidth = 2.1f)
    }

    /** Menu — the drawer handle: three short rules, the middle one shorter. */
    val Menu: ImageVector by lazy {
        draw(
            "menu",
            listOf("M4.5,7 H19.5", "M4.5,12 H15.5", "M4.5,17 H19.5"),
            strokeWidth = 2.0f,
        )
    }

    /** Model routes — a plane stack was Talaria's; Keryx keeps it under its own name. */
    val Routes: ImageVector get() = Stack
}
