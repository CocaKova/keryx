package chat.keryx.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * Quiet text that stays readable (3.0 accessibility pass).
 *
 * Android's accessibility checker, run over the demo in both grounds (A11yCanaryTest), found the
 * theme's own colours sound (secondary ink 5.24:1 on parchment) and the failures in what was
 * built on them: secondary ink faded to 50-70% for quiet labels. The "Thought" line measured
 * 2.04:1 and the drawer's previews 2.7:1 on paper, 3.5:1 on the void. [readable] keeps the fade
 * where the ground allows it and stops it where it doesn't: it moves the colour toward black on
 * a light ground, or white on a dark one, only as far as the ground needs, so a quiet label that
 * already reads keeps its exact look.
 *
 * The accent as text has its own pass, [chat.keryx.app.presentation.ui.components.keryxAccentInk].
 */
object KeryxInk {

    /** WCAG AA for body text (4.5:1), with a hair of headroom for the grounds that are a
     *  gradient or a tint a shade off the one passed in. */
    const val BODY = 4.6f

    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    /** [fg] on [ground] (opaque; a translucent [fg] is flattened onto it first), nudged until it
     *  reaches [atLeast] against it. */
    fun readable(fg: Color, ground: Color, atLeast: Float = BODY): Color = readable(fg, listOf(ground), atLeast)

    /** The same, held to the worst of several [grounds]. Quiet text sits on the page and on the
     *  sheets and cards laid over it, and they differ: on the void a sheet is lighter than the
     *  page, on parchment the page is darker than a leaf. The answer is one opaque colour. */
    fun readable(fg: Color, grounds: List<Color>, atLeast: Float = BODY): Color {
        val bgs = grounds.map { it.copy(alpha = 1f) }
        val ink = if (fg.alpha < 1f) fg.compositeOver(bgs.first()) else fg
        fun worst(c: Color) = bgs.minOf { contrast(c, it) }
        if (worst(ink) >= atLeast) return ink
        val toward = if (contrast(Color.Black, bgs.first()) >= contrast(Color.White, bgs.first())) Color.Black else Color.White
        var t = 0.05f
        while (t < 1f) {
            val c = lerp(ink, toward, t)
            if (worst(c) >= atLeast) return c
            t += 0.05f
        }
        return toward
    }
}

/** Quiet text that still reads: the secondary ink at [alpha], held to 4.5:1 on the page and the
 *  surfaces laid over it, or on [on] when the text sits on a ground of its own (a translucent
 *  tint is laid over each of them first). */
@Composable
@ReadOnlyComposable
fun quietInk(alpha: Float = 1f, on: Color? = null): Color {
    val cs = MaterialTheme.colorScheme
    // On the void the cards, the composer and the drawer are all lighter than the page, the
    // lightest of them the variant (#1D1D28): the checker found quiet text passing on black and
    // 4.2:1 on those. On parchment the page is the darkest common ground already.
    val bases = if (cs.background.luminance() < 0.5f) listOf(cs.background, cs.surface, cs.surfaceVariant)
    else listOf(cs.background, cs.surface)
    val grounds = when {
        on == null -> bases
        on.alpha < 1f -> bases.map { on.compositeOver(it) }
        else -> listOf(on)
    }
    return KeryxInk.readable(cs.onSurfaceVariant.copy(alpha = alpha), grounds)
}
