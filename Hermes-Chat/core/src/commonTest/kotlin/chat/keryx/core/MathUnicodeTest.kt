package chat.keryx.core

import chat.keryx.core.model.ToolGrammar
import chat.keryx.core.protocol.MathUnicode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MathUnicodeTest {

    @Test
    fun `inline math becomes readable unicode`() {
        assertEquals("E = mc²", MathUnicode.tex("E = mc^2"))
        assertEquals("α + β ≤ γ", MathUnicode.tex("\\alpha + \\beta \\leq \\gamma"))
        assertEquals("x₁ + x₂", MathUnicode.tex("x_1 + x_2"))
        assertEquals("∑ᵢ aᵢ", MathUnicode.tex("\\sum_i a_i"))
        assertEquals("a/b", MathUnicode.tex("\\frac{a}{b}"))
        assertEquals("(a+1)/(2b)", MathUnicode.tex("\\frac{a+1}{2b}"))
        assertEquals("√(x² + y²)", MathUnicode.tex("\\sqrt{x^2 + y^2}"))
        assertEquals("f: X → Y", MathUnicode.tex("f\\colon X \\to Y").replace("colon", ":"))
    }

    @Test
    fun `dollars in prose stay money and code is never touched`() {
        val money = "It costs $5 and $6 more"
        assertEquals(money, MathUnicode.render(money))
        val code = "run `echo \$HOME` and\n```sh\nprint \$x^2\n```\nthen \$x^2\$"
        val out = MathUnicode.render(code)
        assertTrue(out.contains("`echo \$HOME`"))
        assertTrue(out.contains("print \$x^2"))
        assertTrue(out.endsWith("then x²"))
    }

    @Test
    fun `block math is its own paragraph with markers`() {
        val src = "Energy:\n\$\$E = mc^2\$\$\nas shown."
        val out = MathUnicode.render(src)
        assertTrue(out.contains("\n\n⟦ E = mc² ⟧\n\n"), out)
        assertEquals("⟦ ∫ f(x) dx ⟧", MathUnicode.render("\\[\\int f(x)\\,dx\\]").trim())
    }

    @Test
    fun `unknown commands survive by name and the gate is cheap`() {
        assertEquals("foo(x)", MathUnicode.tex("\\foo(x)"))
        assertFalse(MathUnicode.hasMath("plain prose, no math here"))
        assertTrue(MathUnicode.hasMath("inline \$x\$"))
    }

    @Test
    fun `every named tool has a family and unknown tools fall to OTHER`() {
        assertEquals(ToolGrammar.Family.SHELL, ToolGrammar.familyOf("terminal"))
        assertEquals(ToolGrammar.Family.EDIT, ToolGrammar.familyOf("write_file"))
        assertEquals(ToolGrammar.Family.WEB, ToolGrammar.familyOf("browser_click"))
        assertEquals(ToolGrammar.Family.MIND, ToolGrammar.familyOf("memory"))
        assertEquals(ToolGrammar.Family.OTHER, ToolGrammar.familyOf("something_new"))
    }
}
