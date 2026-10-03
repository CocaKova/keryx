package chat.keryx.app.presentation.ui.components.math

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.keryx.app.presentation.ui.components.KeryxType
import chat.keryx.core.protocol.MathUnicode

/**
 * Display math (`$$…$$`, `\[…\]` on its own) typeset as real math: fractions stacked, radicals
 * with a vinculum, limits under the sum, matrices in their brackets. The engine is Kai's
 * renderer (see THIRD-PARTY.md), plain Compose — no WebView, no fonts to ship.
 *
 * Never throws during composition: the parse runs once per source inside runCatching, and a
 * formula the parser can't make anything of falls back to the Unicode transform the inline path
 * uses, centered in mono — readable, never raw TeX and never a crash.
 *
 * Scrolls sideways only when the formula is wider than the bubble: a scrollable that can't
 * scroll still claims every horizontal drag over it (the app's swipe rule).
 */
@Composable
fun MathBlock(tex: String, color: Color, modifier: Modifier = Modifier) {
    val atom = remember(tex) { parseOrNull(tex) }
    val scroll = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.horizontalScroll(scroll, enabled = scroll.maxValue > 0)) {
            if (atom != null) {
                MathFormulaContent(
                    atom = atom,
                    display = true,
                    baseSize = KeryxType.body * 1.15f,
                    color = color,
                )
            } else {
                val fallback = remember(tex) { runCatching { MathUnicode.tex(tex) }.getOrDefault(tex) }
                Text(
                    text = fallback,
                    color = color,
                    fontSize = KeryxType.body,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    softWrap = false,
                )
            }
        }
    }
}

/** Whether [tex] parses into something worth typesetting. Pure; never throws. */
fun mathParses(tex: String): Boolean = parseOrNull(tex) != null

private fun parseOrNull(tex: String): MathAtom? = runCatching {
    if (tex.isBlank()) return@runCatching null
    val atom = MathParser.parse(tex)
    when {
        atom is Group && atom.atoms.isEmpty() -> null
        // The parser's last-resort fallback hands the whole source back as one raw symbol:
        // that is a parse failure, not a formula.
        atom is Sym && atom.text == tex && tex.length > 1 -> null
        else -> atom
    }
}.getOrNull()
