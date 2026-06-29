package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val DiamondShape = GenericShape { size, _ ->
    moveTo(size.width / 2f, 0f)
    lineTo(size.width, size.height / 2f)
    lineTo(size.width / 2f, size.height)
    lineTo(0f, size.height / 2f)
    close()
}

private data class Placed(val node: MermaidParser.Node, val x: Float, val y: Float)
private data class Layout(
    val placed: Map<String, Placed>,
    val width: Float,
    val height: Float,
    val cellW: Float,
    val cellH: Float,
)

/**
 * Renders a Mermaid flowchart natively on a translucent, borderless canvas with softly pulsing
 * accent edges — matching the Keryx dream aesthetic. Falls back to the raw code block for diagram
 * types the parser doesn't model.
 */
@Composable
fun MermaidDiagram(code: String, baseColor: Color) {
    val accent = MaterialTheme.colorScheme.primary
    val graph = remember(code) { MermaidParser.parse(code) }
    if (graph == null) {
        // Unsupported diagram → show the source so nothing is lost.
        Text(
            text = code,
            color = baseColor.copy(alpha = 0.8f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(baseColor.copy(alpha = 0.06f))
                .padding(10.dp),
        )
        return
    }

    val layout = remember(graph) { layout(graph) }

    // Breathing glow on the connection lines.
    val pulse by rememberInfiniteTransition(label = "mermaidPulse").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "mermaidPulseAlpha",
    )

    Box(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.05f))
            .horizontalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        Box(modifier = Modifier.size(layout.width.dp, layout.height.dp)) {
            // Edges + arrowheads behind the nodes.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val halfW = layout.cellW / 2f
                val halfH = layout.cellH / 2f
                graph.edges.forEach { e ->
                    val a = layout.placed[e.from] ?: return@forEach
                    val b = layout.placed[e.to] ?: return@forEach
                    val ac = Offset((a.x + halfW).dp.toPx(), (a.y + halfH).dp.toPx())
                    val bc = Offset((b.x + halfW).dp.toPx(), (b.y + halfH).dp.toPx())
                    val hw = halfW.dp.toPx()
                    val hh = halfH.dp.toPx()
                    val start = border(ac, hw, hh, bc)
                    val end = border(bc, hw, hh, ac)
                    val col = accent.copy(alpha = pulse)
                    drawLine(col, start, end, strokeWidth = 2.dp.toPx())
                    drawArrowHead(end, start, col, 9.dp.toPx())
                }
            }
            // Nodes.
            layout.placed.values.forEach { p ->
                val shape = when (p.node.shape) {
                    MermaidParser.NodeShape.RECT -> RoundedCornerShape(6.dp)
                    MermaidParser.NodeShape.ROUND -> RoundedCornerShape(14.dp)
                    MermaidParser.NodeShape.STADIUM -> RoundedCornerShape(percent = 50)
                    MermaidParser.NodeShape.CIRCLE -> CircleShape
                    MermaidParser.NodeShape.DIAMOND -> DiamondShape
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset(p.x.dp, p.y.dp)
                        .size(layout.cellW.dp, layout.cellH.dp)
                        .clip(shape)
                        .background(accent.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = p.node.label,
                        color = baseColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Edge labels, positioned near each edge midpoint.
            graph.edges.forEach { e ->
                val label = e.label ?: return@forEach
                val a = layout.placed[e.from] ?: return@forEach
                val b = layout.placed[e.to] ?: return@forEach
                val midX = (a.x + b.x) / 2f + layout.cellW / 2f
                val midY = (a.y + b.y) / 2f + layout.cellH / 2f
                Box(
                    modifier = Modifier
                        .offset((midX - label.length * 3.2f).dp, (midY - 8).dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(label, color = baseColor.copy(alpha = 0.8f), fontSize = 10.sp)
                }
            }
        }
    }
}

/** Grid layout: ranks become rows (vertical) or columns (horizontal); nodes uniform-sized cells. */
private fun layout(graph: MermaidParser.Graph): Layout {
    val ranks = MermaidParser.rank(graph)
    val longest = graph.nodes.maxOfOrNull { it.label.length } ?: 4
    val cellW = (longest.coerceAtMost(20) * 7f + 28f).coerceIn(72f, 184f)
    val cellH = 44f
    val laneGap = 18f
    val rankGap = 58f
    val placed = LinkedHashMap<String, Placed>()
    val byId = graph.nodes.associateBy { it.id }

    if (graph.direction == MermaidParser.Direction.VERTICAL) {
        val maxRow = ranks.maxOfOrNull { it.size } ?: 1
        val width = maxRow * cellW + (maxRow - 1).coerceAtLeast(0) * laneGap
        ranks.forEachIndexed { r, row ->
            val rowW = row.size * cellW + (row.size - 1).coerceAtLeast(0) * laneGap
            val x0 = (width - rowW) / 2f
            row.forEachIndexed { c, id ->
                val node = byId[id] ?: return@forEachIndexed
                placed[id] = Placed(node, x0 + c * (cellW + laneGap), r * (cellH + rankGap))
            }
        }
    } else {
        val maxCol = ranks.maxOfOrNull { it.size } ?: 1
        val height = maxCol * cellH + (maxCol - 1).coerceAtLeast(0) * laneGap
        ranks.forEachIndexed { r, col ->
            val colH = col.size * cellH + (col.size - 1).coerceAtLeast(0) * laneGap
            val y0 = (height - colH) / 2f
            col.forEachIndexed { c, id ->
                val node = byId[id] ?: return@forEachIndexed
                placed[id] = Placed(node, r * (cellW + rankGap), y0 + c * (cellH + laneGap))
            }
        }
    }
    // Bound the canvas to the actual node extents so nothing is clipped or unreachable when panning.
    val width = (placed.values.maxOfOrNull { it.x + cellW } ?: cellW).coerceAtLeast(cellW)
    val height = (placed.values.maxOfOrNull { it.y + cellH } ?: cellH).coerceAtLeast(cellH)
    return Layout(placed, width, height, cellW, cellH)
}

/** Point on the border of a node box (centered at [center], half-extents [hw]/[hh]) along the ray
 *  toward [towards]. */
private fun border(center: Offset, hw: Float, hh: Float, towards: Offset): Offset {
    val dx = towards.x - center.x
    val dy = towards.y - center.y
    if (dx == 0f && dy == 0f) return center
    val sx = if (dx != 0f) hw / abs(dx) else Float.MAX_VALUE
    val sy = if (dy != 0f) hh / abs(dy) else Float.MAX_VALUE
    val t = min(sx, sy)
    return Offset(center.x + dx * t, center.y + dy * t)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowHead(
    tip: Offset,
    from: Offset,
    color: Color,
    len: Float,
) {
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    val spread = 0.45f
    val p = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - len * cos(angle - spread), tip.y - len * sin(angle - spread))
        lineTo(tip.x - len * cos(angle + spread), tip.y - len * sin(angle + spread))
        close()
    }
    drawPath(p, color)
}
