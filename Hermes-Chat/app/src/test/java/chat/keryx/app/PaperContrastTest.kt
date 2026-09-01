package chat.keryx.app

import chat.keryx.app.presentation.ui.components.KeryxToolTint
import chat.keryx.core.model.Heralds
import chat.keryx.app.presentation.ui.components.roomLightRaw
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Light mode's arithmetic (2.5 "the paper redo").
 *
 * Light was never designed — it was stock Material white while the dark side got 2.0's whole
 * visual language, and the gap was not a matter of taste. Measured against the light background,
 * **all six** council herald hues scored between 1.37:1 and 1.50:1–2.50:1 while scoring 8:1 to
 * 14.7:1 on black; `KeryxStatus.idle` was a flat 40% white at **1.04:1**, meaning a disconnected
 * platform and a healthy one were the same pixel; and the secondary text colour sat at 3.12:1,
 * under the 4.5:1 body text needs.
 *
 * So the palette is checked, not eyeballed. Every colour the paper theme prints identity or state
 * in must clear WCAG AA against the parchment it sits on, and this test is where that is decided —
 * a hue nudged for looks that drops below the line fails here rather than on a device.
 */
class PaperContrastTest {

    // --- WCAG 2.1 relative luminance ------------------------------------------------------------

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    private fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** theme/Color.kt — kept here as literals on purpose: if someone retunes the paper, this test
     *  must be updated deliberately, and updating it means re-reading the numbers below. */
    private val paper = 0xFFF6F2EAL
    private val paperSurface = 0xFFFDFBF6L
    private val ink = 0xFF1F1B14L
    private val fadedInk = 0xFF6B6459L
    private val void = 0xFF000000L

    private val AA = 4.5

    @Test
    fun `ink and faded ink are readable on parchment`() {
        assertTrue("ink on paper", contrast(ink, paper) >= 7.0)
        // The one that was actually failing before: #8E8E93 on #FAFAFA measured 3.12:1.
        assertTrue(
            "faded ink is body text and must clear AA, got ${contrast(fadedInk, paper)}",
            contrast(fadedInk, paper) >= AA,
        )
        assertTrue("faded ink on a leaf", contrast(fadedInk, paperSurface) >= AA)
    }

    @Test
    fun `every herald is legible on the ground it is printed on`() {
        for ((i, pair) in Heralds.PALETTE_PAPER.withIndex()) {
            val c = contrast(pair.first, paper)
            assertTrue("Paper herald $i scores $c on parchment", c >= AA)
        }
        for ((i, pair) in Heralds.PALETTE.withIndex()) {
            val c = contrast(pair.first, void)
            assertTrue("Void herald $i scores $c on black", c >= AA)
        }
    }

    @Test
    fun `the two council palettes stay index-for-index`() {
        // A herald's slot is assigned once and must mean the same life on either ground. If these
        // ever differ in length, a theme flip silently re-costumes the room.
        assertEquals(Heralds.PALETTE.size, Heralds.PALETTE_PAPER.size)
    }

    @Test
    fun `a herald's second colour is darker than its first on both grounds`() {
        // accent2 is the shadow of accent — rims, gradients and the sigil's tail depend on it
        // reading as the deeper of the pair.
        for ((i, pair) in (Heralds.PALETTE + Heralds.PALETTE_PAPER).withIndex()) {
            assertTrue(
                "Pair $i has an accent2 no darker than its accent",
                luminance(pair.second) < luminance(pair.first),
            )
        }
    }

    @Test
    fun `the void palette really is unusable on paper`() {
        // Pinning the reason this work happened. If a future palette change makes the void hues
        // readable on parchment too, PALETTE_PAPER has become dead weight and should be removed
        // rather than maintained in parallel.
        val worst = Heralds.PALETTE.maxOf { contrast(it.first, paper) }
        assertTrue(
            "The void palette now reads on paper ($worst:1) — PALETTE_PAPER may be redundant",
            worst < AA,
        )
    }

    @Test
    fun `status colours carry their meaning on parchment`() {
        // KeryxDesign.kt's paper set. Idle is allowed to be the quietest, but never invisible —
        // it measured 1.04:1 as 40% white, which is what "invisible" looks like as a number.
        val good = 0xFF307D33L
        val warn = 0xFF94651FL
        val bad = 0xFFC63F3AL
        val idle = 0xFF4A4438L // the opaque ink behind paperIdle's 55% alpha
        for ((name, c) in listOf("good" to good, "warn" to warn, "bad" to bad)) {
            assertTrue("$name scores ${contrast(c, paper)} on parchment", contrast(c, paper) >= AA)
        }
        assertTrue("idle must be quiet, not absent", contrast(idle, paper) >= 3.0)
    }

    @Test
    fun `tool family tints are legible on the ground they are printed on`() {
        // 2.6.2 tool-log colour coding: a family's glyph must read on parchment at AA and on
        // the void at AA, and the two maps must cover the same families index-for-index.
        assertEquals(KeryxToolTint.PAPER.keys, KeryxToolTint.VOID.keys)
        for ((family, c) in KeryxToolTint.PAPER) {
            val argb = (c.value shr 32).toLong() and 0xFFFFFFFFL
            val score = contrast(argb or 0xFF000000L, paper)
            assertTrue("$family paper tint scores $score on parchment", score >= AA)
        }
        for ((family, c) in KeryxToolTint.VOID) {
            val argb = (c.value shr 32).toLong() and 0xFFFFFFFFL
            val score = contrast(argb or 0xFF000000L, void)
            assertTrue("$family void tint scores $score on black", score >= AA)
        }
    }

    @Test
    fun `nothing paints a status colour by hand`() {
        // Ten sites used to hold the void hues as literals — session dots, tool verdicts, platform
        // enabled/disabled — which is why light mode failed in exactly the places that report
        // state. The paper set only helps if everything goes through KeryxStatus.
        //
        // CallScreen is the one honest exception: it is a dark dialog whatever the theme says, so
        // it must NOT ask a theme-aware token what colour to be. See KeryxStatus's own warning.
        val exempt = setOf("KeryxDesign.kt", "CallScreen.kt")
        val hues = listOf("0xFF4CAF50", "0xFFE8A33D", "0xFFE0524D")
        val offenders = File("src/main/java/chat/keryx/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in exempt }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, l) -> hues.any { it in l } }
                    .map { (i, l) -> "${f.name}:${i + 1}  ${l.trim()}" }
            }
            .toList()
        assertTrue(
            "Status colours painted by hand bypass the paper palette and fail WCAG on " +
                "parchment — use KeryxStatus:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `a room's light is stable and survives being pressed onto paper`() {
        // The hue IS the room's identity in the drawer, the deck, and now the switch wake, so it
        // must not wander between launches.
        val name = "The Study"
        assertEquals(roomLightRaw(name), roomLightRaw(name))

        // roomLight() darkens by a fixed 40% on parchment. These are Material 300s — pitched as a
        // ground with white on top, not as a mark on paper — and raw they measure 1.55:1 to
        // 3.19:1 there. WCAG asks 3:1 of a graphical object; the wake also carries its own alpha,
        // so headroom matters.
        val names = listOf("a", "b", "c", "d", "e", "f", "g", "h", "The Study", "Clocktower")
        for (n in names) {
            val raw = roomLightRaw(n)
            val pressed = 0xFF000000L or
                ((raw.red * 0.60f * 255f).toLong() shl 16) or
                ((raw.green * 0.60f * 255f).toLong() shl 8) or
                (raw.blue * 0.60f * 255f).toLong()
            val c = contrast(pressed, paper)
            assertTrue("Room light for '$n' scores $c on parchment", c >= 3.0)
        }
    }

    @Test
    fun `the room palette is defined once`() {
        // It lived twice, byte-identical, in the drawer and the deck — harmless while both only
        // drew circles, and not harmless the moment the switch wake wanted the same light. A wake
        // carrying a different colour from the circle you tapped breaks the one thing it says.
        val defs = File("src/main/java/chat/keryx/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().contains("Color(0xFFE57373)") }
            .map { it.name }
            .toList()
        assertEquals("The room palette should exist in RoomLight.kt only, found: $defs", 1, defs.size)
    }
}
