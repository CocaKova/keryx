package chat.keryx.app.presentation.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn glyphs for the states Material's icon set has no word for. Harvested from
 * Talaria (its composer's submit-tree glyphs): a 24-unit viewport, round stroked paths,
 * tinted by the caller — the black here is a placeholder the `Icon` tint replaces.
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

    /** Steer — a wheel with three spokes and a hub: turn the running turn, don't stop it. */
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

    /** Queue — layered sheets: this one waits under the current turn. */
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
}
