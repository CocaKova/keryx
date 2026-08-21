package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.ToolGrammar

/** The add/remove pair, in the two colours the panel uses — resolved once, shared by both. */
data class DiffColors(
    val add: Color,
    val addBg: Color,
    val addFg: Color,
    val remove: Color,
    val removeBg: Color,
    val removeFg: Color,
)

@Composable
fun diffColors(): DiffColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val green = if (dark) Color(0xFF55A583) else Color(0xFF1F8A65)
    val red = if (dark) Color(0xFFE75E78) else Color(0xFFCF2D56)
    return DiffColors(
        add = green,
        addBg = green.copy(alpha = 0.12f),
        addFg = if (dark) Color(0xFF96C7B2) else Color(0xFF17614A),
        remove = red,
        removeBg = red.copy(alpha = 0.12f),
        removeFg = if (dark) Color(0xFFF09BAB) else Color(0xFF93203C),
    )
}

/**
 * "+40 −3" — what an edit actually did, which is the one thing a tool row for a write tool was
 * never able to say. Counted from the whole diff by the gateway, so it stays true even when the
 * panel below it is cut to fit the wire.
 */
@Composable
fun DiffStat(added: Int, removed: Int, modifier: Modifier = Modifier) {
    if (added == 0 && removed == 0) return
    val c = diffColors()
    Row(modifier = modifier) {
        if (added > 0) {
            Text(
                "+$added",
                color = c.add,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
        if (added > 0 && removed > 0) Spacer(Modifier.width(4.dp))
        if (removed > 0) {
            Text(
                "−$removed",
                color = c.remove,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * The edit itself: git chrome stripped, the +/− gutter marker stripped (the colour says it),
 * changes read by a 2dp left border and a 12% tint, a blank row between hunks.
 *
 * Bounded and scrollable in both directions — a diff is the one tool payload that is genuinely
 * wide, and wrapping code lines makes them unreadable in a different way than truncating does.
 */
@Composable
fun DiffPanel(
    diff: String,
    truncated: Boolean,
    baseColor: Color,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 190.dp,
) {
    val lines = remember(diff) { ToolGrammar.diffLines(diff) }
    if (lines.isEmpty()) return
    val c = diffColors()
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            lines.forEach { line ->
                when (line.kind) {
                    ToolGrammar.DiffKind.GAP -> Spacer(Modifier.padding(vertical = 3.dp))
                    else -> {
                        val (bg, border, fg) = when (line.kind) {
                            ToolGrammar.DiffKind.ADD -> Triple(c.addBg, c.add, c.addFg)
                            ToolGrammar.DiffKind.REMOVE -> Triple(c.removeBg, c.remove, c.removeFg)
                            else -> Triple(Color.Transparent, Color.Transparent, baseColor.copy(alpha = 0.6f))
                        }
                        Row(Modifier.background(bg).height(IntrinsicSize.Min)) {
                            Box(Modifier.width(2.dp).fillMaxHeight().background(border))
                            Text(
                                line.text.ifEmpty { " " },
                                color = fg,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        // Say when the view is partial. A diff that silently stops reads as a diff that ended.
        if (truncated) {
            Text(
                "… diff truncated",
                color = baseColor.copy(alpha = 0.35f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
