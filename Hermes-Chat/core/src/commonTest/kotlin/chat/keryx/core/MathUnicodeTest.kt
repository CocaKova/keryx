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
    fun `display math on its own line is its own paragraph and never shows markers`() {
        val src = "Energy:\n\$\$E = mc^2\$\$\nas shown."
        val out = MathUnicode.render(src)
        assertTrue(out.contains("\n\nE = mc²\n\n"), out)
        assertFalse(out.contains("⟦") || out.contains("⟧"), out)
        assertEquals("∫ f(x) dx", MathUnicode.render("\\[\\int f(x)\\,dx\\]").trim())
        // Mid-sentence display math stays in the sentence.
        assertEquals("inline x² mid", MathUnicode.render("inline \$\$x^2\$\$ mid"))
    }

    @Test
    fun `currency and shell variables are never math`() {
        for (s in listOf(
            "It cost \$5 and then \$10.",
            "Prices: \$20, \$30 and up",
            "Raise from \$5/\$10 blinds",
            "Set \$HOME and \$PATH first",
        )) assertEquals(s, MathUnicode.render(s))
        assertEquals("so x² + y² = z² holds", MathUnicode.render("so \$x^2 + y^2 = z^2\$ holds"))
    }

    @Test
    fun `arguments are brace-matched so nesting works`() {
        assertEquals("x²/y", MathUnicode.tex("\\frac{x^{2}}{y}"))
        assertEquals("x = (-b ± √(b²-4ac))/(2a)", MathUnicode.tex("x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}"))
        assertEquals("(1)/(1/2)", MathUnicode.tex("\\frac{1}{\\frac{1}{2}}"))
        assertEquals("³√x", MathUnicode.tex("\\sqrt[3]{x}"))
        assertEquals("ⁿ√(a+b)", MathUnicode.tex("\\sqrt[n]{a+b}"))
        assertEquals("C(n, k)", MathUnicode.tex("\\binom{n}{k}"))
        // Malformed input degrades, never throws.
        assertEquals("1", MathUnicode.tex("\\frac{1}{"))
    }

    @Test
    fun `font commands map or keep their argument`() {
        assertEquals("ℝⁿ", MathUnicode.tex("\\mathbb{R}^n"))
        assertEquals("x ∈ ℕ, ℤ, ℚ, ℂ", MathUnicode.tex("x \\in \\mathbb{N}, \\mathbb{Z}, \\mathbb{Q}, \\mathbb{C}"))
        assertEquals("ℒ(f)", MathUnicode.tex("\\mathcal{L}(f)"))
        assertEquals("v + dx", MathUnicode.tex("\\mathbf{v} + \\mathrm{d}x"))
        assertEquals("sgn(x)", MathUnicode.tex("\\operatorname{sgn}(x)"))
        assertEquals("if x", MathUnicode.tex("\\text{if } x"))
    }

    @Test
    fun `accents become combining marks`() {
        assertEquals("x\u0302", MathUnicode.tex("\\hat{x}"))
        assertEquals("x\u0304", MathUnicode.tex("\\bar{x}"))
        assertEquals("v\u20D7", MathUnicode.tex("\\vec{v}"))
        assertEquals("y\u0303", MathUnicode.tex("\\tilde{y}"))
        assertEquals("x\u0307 + x\u0308", MathUnicode.tex("\\dot{x} + \\ddot{x}"))
        assertEquals("A\u0305B\u0305", MathUnicode.tex("\\overline{AB}"))
    }

    @Test
    fun `escapes print their character`() {
        assertEquals("{x ∣ x > 0}", MathUnicode.tex("\\{x \\mid x > 0\\}"))
        assertEquals("50% & _ $ #", MathUnicode.tex("50\\% \\& \\_ \\$ \\#"))
        assertEquals("90°", MathUnicode.tex("90^\\circ"))
    }

    @Test
    fun `the wider symbol set`() {
        assertEquals("A ⟹ B ⟺ C", MathUnicode.tex("A \\implies B \\iff C"))
        assertEquals("a ∗ b ≪ c ≫ d ≃ e ≅ f", MathUnicode.tex("a \\ast b \\ll c \\gg d \\simeq e \\cong f"))
        assertEquals("A ∖ B ⊕ C ⊗ D", MathUnicode.tex("A \\setminus B \\oplus C \\otimes D"))
        assertEquals("⊤ ⊥ ⋮ ⋱ ℵ ℜ ℑ ℘", MathUnicode.tex("\\top \\bot \\vdots \\ddots \\aleph \\Re \\Im \\wp"))
        assertEquals("a ≤ b ≥ c", MathUnicode.tex("a \\leqslant b \\geqslant c"))
        assertEquals("a ∼ b", MathUnicode.tex("a \\sim b"))
        assertEquals("sin x + log y", MathUnicode.tex("\\sin x + \\log y"))
    }

    @Test
    fun `environments flatten to one readable line`() {
        assertEquals("(a b; c d)", MathUnicode.tex("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}"))
        assertEquals("[1 0; 0 1]", MathUnicode.tex("\\begin{bmatrix} 1 & 0 \\\\ 0 & 1 \\end{bmatrix}"))
        assertEquals("det |a b; c d|", MathUnicode.tex("\\det \\begin{vmatrix} a & b \\\\ c & d \\end{vmatrix}"))
        assertEquals(
            "f(x) = { x if x ≥ 0; -x otherwise",
            MathUnicode.tex("f(x) = \\begin{cases} x & \\text{if } x \\ge 0 \\\\ -x & \\text{otherwise} \\end{cases}"),
        )
        assertEquals("{ 1, x > 0; 0, x ≤ 0", MathUnicode.tex("\\begin{cases} 1 & x > 0 \\\\ 0 & x \\le 0 \\end{cases}"))
        assertEquals("a = b + c; d = e", MathUnicode.tex("\\begin{aligned} a &= b + c \\\\ d &= e \\end{aligned}"))
        assertEquals("1 2; 3 4", MathUnicode.tex("\\begin{array}{cc} 1 & 2 \\\\ 3 & 4 \\end{array}"))
        for (src in listOf("\\begin{pmatrix} a \\end{pmatrix}", "\\begin{cases} x \\end{cases}")) {
            assertFalse(MathUnicode.tex(src).contains("begin"), src)
        }
    }

    @Test
    fun `sizing commands vanish`() {
        assertEquals("(a/b)", MathUnicode.tex("\\left( \\frac{a}{b} \\right)"))
        assertEquals("(x)", MathUnicode.tex("\\Big( x \\Big)"))
        assertEquals("df/dx |ₓ₌₀", MathUnicode.tex("\\left. \\frac{df}{dx} \\right|_{x=0}"))
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
