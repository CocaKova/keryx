package chat.keryx.core.protocol

/**
 * LaTeX math → Unicode, for a chat that has no KaTeX. The frontier web chats typeset `$…$`
 * and `$$…$$`; a phone bubble without a math engine used to print the raw TeX. This turns the
 * common subset into readable text — Greek letters, operators, super/subscripts, simple
 * fractions and roots — and leaves anything it doesn't know as-is, so nothing is lost.
 *
 * Deliberately a text transform, not a renderer: it runs before markdown parsing on the
 * message body, so inline math lands in the prose as ordinary characters and block math
 * becomes a centered mono line. Code spans and fences are skipped — a `$` inside code is
 * a shell variable, not a formula.
 */
object MathUnicode {

    private val GREEK = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ",
        "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "ϕ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    )

    private val SYMBOLS = mapOf(
        "times" to "×", "cdot" to "·", "pm" to "±", "mp" to "∓", "div" to "÷",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "sum" to "∑", "prod" to "∏",
        "int" to "∫", "oint" to "∮", "sqrt" to "√",
        "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
        "Leftarrow" to "⇐", "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "mapsto" to "↦",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆", "cup" to "∪",
        "cap" to "∩", "emptyset" to "∅", "forall" to "∀", "exists" to "∃", "neg" to "¬",
        "land" to "∧", "lor" to "∨", "wedge" to "∧", "vee" to "∨",
        "ldots" to "…", "cdots" to "⋯", "dots" to "…", "quad" to "  ", "qquad" to "    ",
        "," to " ", ";" to " ", "!" to "", " " to " ",
        "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋",
        "lceil" to "⌈", "rceil" to "⌉", "hbar" to "ℏ", "ell" to "ℓ", "degree" to "°",
        "prime" to "′", "circ" to "∘", "star" to "⋆", "bullet" to "•", "angle" to "∠",
        "perp" to "⊥", "parallel" to "∥", "therefore" to "∴", "because" to "∵",
        "left" to "", "right" to "", "displaystyle" to "", "mathrm" to "", "text" to "",
        "textbf" to "", "mathbf" to "", "mathit" to "", "operatorname" to "",
    )

    private val SUPERSCRIPT = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶',
        '7' to '⁷', '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽',
        ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ', 'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ',
        'e' to 'ᵉ', 'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'j' to 'ʲ', 'k' to 'ᵏ', 'l' to 'ˡ',
        'm' to 'ᵐ', 'o' to 'ᵒ', 'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
        'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ', 'T' to 'ᵀ',
    )

    private val SUBSCRIPT = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆',
        '7' to '₇', '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍',
        ')' to '₎', 'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ',
        'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ', 'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ',
        't' to 'ₜ', 'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ',
    )

    /** `$$…$$` on its own lines, or `\[…\]`. */
    private val BLOCK = Regex("""(?s)(?<![\\$])\$\$(.+?)\$\$|\\\[(.+?)\\\]""")

    /** `$…$` with no space just inside the dollars (so "$5 and $6" stays money), or `\(…\)`. */
    private val INLINE = Regex("""(?<![\\$\w])\$(?!\s)([^$\n]+?)(?<!\s)\$(?![\w$])|\\\((.+?)\\\)""")

    private val FRAC = Regex("""\\(?:frac|dfrac|tfrac)\{([^{}]*)}\{([^{}]*)}""")
    private val SQRT = Regex("""\\sqrt\{([^{}]*)}""")
    private val SUP_BRACE = Regex("""\^\{([^{}]*)}""")
    private val SUB_BRACE = Regex("""_\{([^{}]*)}""")
    private val SUP_ONE = Regex("""\^(\S)""")
    private val SUB_ONE = Regex("""_(\S)""")
    private val COMMAND = Regex("""\\([a-zA-Z]+|[,;! ])""")
    private val FENCE = Regex("""(?m)^\s*(```|~~~)""")

    /** Whether [text] carries anything this transform would touch — cheap gate for the hot path. */
    fun hasMath(text: String): Boolean =
        text.contains("$$") || text.contains("\\[") || text.contains("\\(") || INLINE.containsMatchIn(text)

    /**
     * Rewrite every math span in [text] to Unicode. Fenced code blocks and inline code spans
     * are left untouched. Block math becomes its own paragraph wrapped in `⟦ ⟧` markers on a
     * single line (renderers draw it centered mono via [BLOCK_OPEN]/[BLOCK_CLOSE]).
     */
    fun render(text: String): String {
        if (!hasMath(text)) return text
        // Split on fences so code never gets touched; odd segments are inside a fence.
        val parts = splitFences(text)
        return parts.joinToString("") { (inFence, chunk) ->
            if (inFence) chunk else renderProse(chunk)
        }
    }

    const val BLOCK_OPEN = "⟦"
    const val BLOCK_CLOSE = "⟧"

    private fun splitFences(text: String): List<Pair<Boolean, String>> {
        val out = mutableListOf<Pair<Boolean, String>>()
        var inFence = false
        var buf = StringBuilder()
        for (line in text.split("\n")) {
            val fence = FENCE.containsMatchIn(line)
            if (fence) {
                if (!inFence) {
                    out += false to buf.toString(); buf = StringBuilder()
                    inFence = true
                    buf.append(line).append('\n')
                } else {
                    buf.append(line).append('\n')
                    out += true to buf.toString(); buf = StringBuilder()
                    inFence = false
                }
            } else {
                buf.append(line).append('\n')
            }
        }
        out += inFence to buf.toString()
        // The split appended a trailing newline the source may not have had.
        if (!text.endsWith("\n") && out.isNotEmpty()) {
            val (f, last) = out.last()
            out[out.lastIndex] = f to last.removeSuffix("\n")
        }
        return out
    }

    private fun renderProse(chunk: String): String {
        // Inline code spans are protected the same way: swap them out, transform, swap back.
        val spans = mutableListOf<String>()
        val protectedText = Regex("`[^`\n]+`").replace(chunk) { m ->
            spans += m.value; "\u0000${spans.size - 1}\u0000"
        }
        var out = BLOCK.replace(protectedText) { m ->
            val body = (m.groups[1] ?: m.groups[2])?.value.orEmpty()
            "\n\n$BLOCK_OPEN ${tex(body).trim()} $BLOCK_CLOSE\n\n"
        }
        out = INLINE.replace(out) { m ->
            val body = (m.groups[1] ?: m.groups[2])?.value.orEmpty()
            tex(body).trim()
        }
        return Regex("\u0000(\\d+)\u0000").replace(out) { m -> spans[m.groupValues[1].toInt()] }
    }

    /** One TeX span → Unicode. Public for tests. */
    fun tex(src: String): String {
        var s = src.replace("\n", " ")
        // Structures first (they contain commands of their own).
        repeat(3) { s = FRAC.replace(s) { m -> frac(tex(m.groupValues[1]), tex(m.groupValues[2])) } }
        s = SQRT.replace(s) { m -> "√(" + tex(m.groupValues[1]) + ")" }
        s = s.replace("\\sqrt", "√")
        // Commands: greek, symbols; unknown commands keep their name without the backslash.
        s = COMMAND.replace(s) { m ->
            val name = m.groupValues[1]
            GREEK[name] ?: SYMBOLS[name] ?: name
        }
        // Scripts: braced groups, then single characters.
        s = SUP_BRACE.replace(s) { m -> script(m.groupValues[1], SUPERSCRIPT, "^") }
        s = SUB_BRACE.replace(s) { m -> script(m.groupValues[1], SUBSCRIPT, "_") }
        s = SUP_ONE.replace(s) { m -> script(m.groupValues[1], SUPERSCRIPT, "^") }
        s = SUB_ONE.replace(s) { m -> script(m.groupValues[1], SUBSCRIPT, "_") }
        // Leftover braces are grouping only.
        s = s.replace("{", "").replace("}", "")
        // TeX puts no spaces around binary operators; readers want them.
        s = s.replace(Regex("""\s*([=<>≤≥≠≈→±×])\s*"""), " $1 ")
        return s.replace(Regex(" {2,}"), " ").trim()
    }

    private fun frac(a: String, b: String): String {
        val simple = Regex("""^[\w.]+$""")
        return if (simple.matches(a) && simple.matches(b)) "$a/$b" else "($a)/($b)"
    }

    private fun script(body: String, table: Map<Char, Char>, fallback: String): String {
        val inner = tex(body)
        return if (inner.all { it in table }) inner.map { table.getValue(it) }.joinToString("")
        else if (inner.length == 1) fallback + inner
        else "$fallback($inner)"
    }
}
