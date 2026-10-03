package chat.keryx.core.protocol

/**
 * LaTeX math → Unicode, for a chat that has no KaTeX. The frontier web chats typeset `$…$`
 * and `$$…$$`; a phone bubble without a math engine used to print the raw TeX. This turns the
 * common subset into readable text — Greek letters, operators, super/subscripts, simple
 * fractions and roots, matrices flattened to a line — and leaves anything it doesn't know as
 * its bare name, so nothing is lost.
 *
 * Deliberately a text transform, not a renderer: it runs before markdown parsing on the
 * message body, so inline math lands in the prose as ordinary characters. Display math the
 * bubble can lift out on its own line is typeset by the app (MathBlock); whatever display math
 * is left in prose gets the same Unicode treatment, on its own paragraph when it stood on its
 * own line. Code spans and fences are skipped — a `$` inside code is a shell variable, not a
 * formula.
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
        "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋",
        "lceil" to "⌈", "rceil" to "⌉", "hbar" to "ℏ", "ell" to "ℓ", "degree" to "°",
        "prime" to "′", "circ" to "∘", "star" to "⋆", "bullet" to "•", "angle" to "∠",
        "perp" to "⊥", "parallel" to "∥", "therefore" to "∴", "because" to "∵",
        "implies" to "⟹", "iff" to "⟺", "impliedby" to "⟸", "mid" to "∣", "ast" to "∗",
        "leqslant" to "≤", "geqslant" to "≥", "ll" to "≪", "gg" to "≫", "simeq" to "≃",
        "cong" to "≅", "setminus" to "∖", "oplus" to "⊕", "otimes" to "⊗", "odot" to "⊙",
        "top" to "⊤", "bot" to "⊥", "vdots" to "⋮", "ddots" to "⋱", "aleph" to "ℵ",
        "Re" to "ℜ", "Im" to "ℑ", "wp" to "℘", "colon" to ":", "gets" to "←",
        "longrightarrow" to "⟶", "longleftarrow" to "⟵", "Longrightarrow" to "⟹",
        "supset" to "⊃", "supseteq" to "⊇", "ni" to "∋", "nexists" to "∄", "varnothing" to "∅",
        "lnot" to "¬", "dagger" to "†", "triangle" to "△", "square" to "□", "checkmark" to "✓",
        "lbrace" to "{", "rbrace" to "}", "vert" to "|", "Vert" to "‖", "backslash" to "\\",
        "bigcup" to "⋃", "bigcap" to "⋂", "coprod" to "∐", "iint" to "∬", "iiint" to "∭",
        "varpi" to "ϖ", "varrho" to "ϱ", "varsigma" to "ς", "Upsilon" to "Υ",
        // Sizing and style switches carry no glyph of their own.
        "left" to "", "right" to "", "middle" to "", "big" to "", "Big" to "", "bigg" to "",
        "Bigg" to "", "bigl" to "", "bigr" to "", "Bigl" to "", "Bigr" to "", "biggl" to "",
        "biggr" to "", "Biggl" to "", "Biggr" to "", "displaystyle" to "", "textstyle" to "",
        "scriptstyle" to "", "limits" to "", "nolimits" to "",
    )

    /** Commands that wrap one argument and keep it, minus the styling a phone can't show. */
    private val KEEP_ARG = setOf(
        "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "boldsymbol", "bm", "operatorname",
        "textbf", "textit", "textsf", "texttt", "emph", "underbrace", "overbrace",
    )

    /** `\text{…}` and friends: the argument is prose, spaces and all, never math. */
    private val TEXT_ARG = setOf("text", "textrm", "mbox", "textnormal")

    /** Accent → the combining mark laid on the argument. [OVER_EACH] marks every character. */
    private val ACCENTS = mapOf(
        "hat" to '\u0302', "widehat" to '\u0302', "check" to '\u030C', "tilde" to '\u0303',
        "widetilde" to '\u0303', "vec" to '\u20D7', "dot" to '\u0307', "ddot" to '\u0308',
        "acute" to '\u0301', "grave" to '\u0300', "breve" to '\u0306',
        "bar" to '\u0304', "overline" to '\u0305', "underline" to '\u0332',
    )
    private val OVER_EACH = setOf("bar", "overline", "underline")

    private val DOUBLE_STRUCK = mapOf(
        'A' to "𝔸", 'B' to "𝔹", 'C' to "ℂ", 'D' to "𝔻", 'E' to "𝔼", 'F' to "𝔽", 'G' to "𝔾",
        'H' to "ℍ", 'I' to "𝕀", 'J' to "𝕁", 'K' to "𝕂", 'L' to "𝕃", 'M' to "𝕄", 'N' to "ℕ",
        'O' to "𝕆", 'P' to "ℙ", 'Q' to "ℚ", 'R' to "ℝ", 'S' to "𝕊", 'T' to "𝕋", 'U' to "𝕌",
        'V' to "𝕍", 'W' to "𝕎", 'X' to "𝕏", 'Y' to "𝕐", 'Z' to "ℤ", '1' to "𝟙", 'k' to "𝕜",
    )

    private val CALLIGRAPHIC = mapOf(
        'A' to "𝒜", 'B' to "ℬ", 'C' to "𝒞", 'D' to "𝒟", 'E' to "ℰ", 'F' to "ℱ", 'G' to "𝒢",
        'H' to "ℋ", 'I' to "ℐ", 'J' to "𝒥", 'K' to "𝒦", 'L' to "ℒ", 'M' to "ℳ", 'N' to "𝒩",
        'O' to "𝒪", 'P' to "𝒫", 'Q' to "𝒬", 'R' to "ℛ", 'S' to "𝒮", 'T' to "𝒯", 'U' to "𝒰",
        'V' to "𝒱", 'W' to "𝒲", 'X' to "𝒳", 'Y' to "𝒴", 'Z' to "𝒵",
    )

    /** Single-character escapes: `\{` is a brace, `\%` a percent sign, `\,` a thin space. */
    private val ESCAPES = mapOf(
        '{' to "{", '}' to "}", '%' to "%", '&' to "&", '_' to "_", '$' to "$", '#' to "#",
        ',' to " ", ';' to " ", ':' to " ", ' ' to " ", '!' to "", '|' to "‖",
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

    /** `$$…$$`, or `\[…\]`. */
    private val BLOCK = Regex("""(?s)(?<![\\$])\$\$(.+?)\$\$|\\\[(.+?)\\\]""")

    /** `$…$` with no space just inside the dollars (so "$5 and $6" stays money), or `\(…\)`. */
    private val INLINE = Regex("""(?<![\\$\w])\$(?!\s)([^$\n]+?)(?<!\s)\$(?![\w$])|\\\((.+?)\\\)""")

    // ⚠️ Every literal `}` is escaped: Android's ICU regex rejects a bare one ("Syntax error
    // near index 33") while the JVM's accepts it — the unit tests passed and the app died at
    // class-init on the phone (2.6.2, 09-01). Keep every pattern here ICU-clean. The TeX itself
    // is read by a scanner below, not by regexes, so nested braces cost nothing.
    private val FENCE = Regex("""(?m)^\s*(```|~~~)""")
    private val OPERATOR_SPACING = Regex("""\s*([=<>≤≥≠≈→±×⟹⟺∣])\s*""")
    private val MULTI_SPACE = Regex(" {2,}")
    private val CODE_SPAN = Regex("`[^`\n]+`")
    private val CODE_SLOT = Regex("\u0000(\\d+)\u0000")
    // Brackets hug their contents; braces keep their air so `{ x if x ≥ 0; …` reads as cases.
    private val OPEN_GAP = Regex("""([(\[⟨]) +""")
    private val CLOSE_GAP = Regex(""" +([)\]⟩])""")

    /** Whether [text] carries anything this transform would touch — cheap gate for the hot path. */
    fun hasMath(text: String): Boolean =
        text.contains("$$") || text.contains("\\[") || text.contains("\\(") || INLINE.containsMatchIn(text)

    /**
     * Rewrite every math span in [text] to Unicode. Fenced code blocks and inline code spans
     * are left untouched. Display math that stood on its own line stays its own paragraph;
     * display math in the middle of a sentence stays in the sentence.
     */
    fun render(text: String): String = try {
        renderUnsafe(text)
    } catch (_: Throwable) {
        // A regex dialect surprise must degrade to raw TeX, never to a dead app.
        text
    }

    private fun renderUnsafe(text: String): String {
        if (!hasMath(text)) return text
        // Split on fences so code never gets touched; odd segments are inside a fence.
        val parts = splitFences(text)
        return parts.joinToString("") { (inFence, chunk) ->
            if (inFence) chunk else renderProse(chunk)
        }
    }

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
        val protectedText = CODE_SPAN.replace(chunk) { m ->
            spans += m.value; "\u0000${spans.size - 1}\u0000"
        }
        var out = BLOCK.replace(protectedText) { m ->
            val body = (m.groups[1] ?: m.groups[2])?.value.orEmpty()
            val rendered = tex(body).trim()
            // On its own line it was meant as a display: keep it a paragraph of its own, or
            // markdown would fold it into the sentences around it. Mid-sentence it stays put.
            val before = protectedText.substring(0, m.range.first).substringAfterLast('\n')
            val after = protectedText.substring(m.range.last + 1).substringBefore('\n')
            if (before.isBlank() && after.isBlank()) "\n\n$rendered\n\n" else rendered
        }
        out = INLINE.replace(out) { m ->
            val body = (m.groups[1] ?: m.groups[2])?.value.orEmpty()
            tex(body).trim()
        }
        return CODE_SLOT.replace(out) { m -> spans[m.groupValues[1].toInt()] }
    }

    /** One TeX span → Unicode. Public for tests and for the app's display-math fallback. */
    fun tex(src: String): String {
        val s = Scanner(src.replace('\n', ' ')).sequence(stopAtBrace = false)
        // TeX puts no spaces around binary operators; readers want them.
        val spaced = MULTI_SPACE.replace(OPERATOR_SPACING.replace(s, " $1 "), " ")
        return CLOSE_GAP.replace(OPEN_GAP.replace(spaced, "$1"), "$1").trim()
    }

    /**
     * A small recursive reader over one TeX span. Arguments are brace-matched, so nesting is
     * free (`\frac{x^{2}}{y}`), and nothing here can throw on malformed input: an unclosed
     * brace closes at the end, a stray `}` is dropped, an unknown command keeps its name.
     */
    private class Scanner(val src: String) {
        var i = 0

        fun sequence(stopAtBrace: Boolean): String {
            val out = StringBuilder()
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '}' -> { if (stopAtBrace) return out.toString(); i++ }
                    c == '{' -> { i++; out.append(sequence(stopAtBrace = true)); if (i < src.length) i++ }
                    c == '^' || c == '_' -> {
                        i++
                        val arg = argument()
                        // `^\circ` is how TeX spells a degree sign.
                        if (c == '^' && arg.trim() == "∘") { out.append('°'); continue }
                        out.append(if (c == '^') script(arg, SUPERSCRIPT, "^") else script(arg, SUBSCRIPT, "_"))
                    }
                    c == '\\' -> out.append(command())
                    c == '~' -> { out.append(' '); i++ }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }

        /** One argument: a braced group, a command, or a single character. Empty at the end. */
        fun argument(): String {
            while (i < src.length && src[i] == ' ') i++
            if (i >= src.length) return ""
            return when (src[i]) {
                '{' -> { i++; val s = sequence(stopAtBrace = true); if (i < src.length) i++; s }
                '\\' -> command()
                '}' -> ""
                else -> src[i++].toString()
            }
        }

        /** A braced group read verbatim — `\text{if }` keeps its words and spaces as written. */
        fun verbatim(): String {
            while (i < src.length && src[i] == ' ') i++
            if (i >= src.length || src[i] != '{') return argument()
            i++
            val start = i
            var depth = 1
            while (i < src.length) {
                if (src[i] == '{') depth++
                if (src[i] == '}') { depth--; if (depth == 0) break }
                i++
            }
            val body = src.substring(start, i)
            if (i < src.length) i++
            return body
        }

        /** `[n]` right here, or null. */
        fun optional(): String? {
            var j = i
            while (j < src.length && src[j] == ' ') j++
            if (j >= src.length || src[j] != '[') return null
            val close = src.indexOf(']', j)
            if (close < 0) return null
            i = close + 1
            return Scanner(src.substring(j + 1, close)).sequence(stopAtBrace = false)
        }

        fun command(): String {
            i++ // the backslash
            if (i >= src.length) return ""
            val first = src[i]
            if (!first.isLetter()) {
                i++
                if (first == '\\') return " " // a line break outside an environment
                return ESCAPES[first] ?: first.toString()
            }
            val start = i
            while (i < src.length && src[i].isLetter()) i++
            val name = src.substring(start, i)
            return when {
                name == "frac" || name == "dfrac" || name == "tfrac" || name == "cfrac" -> {
                    val a = argument(); val b = argument(); frac(a.trim(), b.trim())
                }
                name == "binom" || name == "dbinom" || name == "tbinom" -> {
                    val a = argument(); val b = argument(); "C(${a.trim()}, ${b.trim()})"
                }
                name == "sqrt" -> {
                    val index = optional()?.trim()
                    // Only a braced radicand is an argument; a bare \sqrt prints the sign.
                    var j = i
                    while (j < src.length && src[j] == ' ') j++
                    val radicand = if (j < src.length && src[j] == '{') argument().trim() else null
                    val sign = if (index.isNullOrEmpty()) "√" else script(index, SUPERSCRIPT, "") + "√"
                    when {
                        radicand == null -> sign
                        radicand.length == 1 -> sign + radicand
                        else -> "$sign($radicand)"
                    }
                }
                name == "mathbb" -> argument().map { DOUBLE_STRUCK[it] ?: it.toString() }.joinToString("")
                name == "mathcal" || name == "mathscr" -> argument().map { CALLIGRAPHIC[it] ?: it.toString() }.joinToString("")
                name in TEXT_ARG -> verbatim()
                name in KEEP_ARG -> argument()
                name in ACCENTS -> accent(argument(), ACCENTS.getValue(name), name in OVER_EACH)
                name == "begin" -> environment(verbatim().trim())
                name == "end" -> { verbatim(); "" }
                name == "left" || name == "right" -> {
                    // `\left.` is an invisible delimiter.
                    if (i < src.length && src[i] == '.') i++
                    ""
                }
                else -> GREEK[name] ?: SYMBOLS[name] ?: name
            }
        }

        /** `\begin{env}…\end{env}`, flattened to one readable line. */
        fun environment(env: String): String {
            if (env == "array" || env == "tabular") verbatim() // the column spec
            val bodyStart = i
            var depth = 1
            var bodyEnd = src.length
            val open = "\\begin{$env}"
            val close = "\\end{$env}"
            while (i < src.length) {
                if (src.startsWith(open, i)) { depth++; i += open.length; continue }
                if (src.startsWith(close, i)) {
                    depth--
                    if (depth == 0) { bodyEnd = i; i += close.length; break }
                    i += close.length; continue
                }
                i++
            }
            val body = src.substring(bodyStart, bodyEnd)
            val rows = splitTopLevel(body, "\\\\").map { row -> splitTopLevel(row, "&").map { tex(it) } }
                .filter { row -> row.any { it.isNotBlank() } }
            val base = env.removeSuffix("*")
            fun joinRows(cell: (List<String>) -> String) = rows.joinToString("; ", transform = cell)
            return when (base) {
                "cases", "dcases" -> "{ " + joinRows { cells ->
                    val head = cells.first()
                    val rest = cells.drop(1).joinToString(" ").trim()
                    when {
                        rest.isEmpty() -> head
                        CONDITION_WORD.containsMatchIn(rest) -> "$head $rest"
                        else -> "$head, $rest"
                    }
                }
                "aligned", "align", "alignat", "gathered", "gather", "split", "eqnarray" ->
                    joinRows { it.joinToString(" ") }
                else -> {
                    val inner = joinRows { it.joinToString(" ") }
                    when (base) {
                        "pmatrix" -> "($inner)"
                        "bmatrix" -> "[$inner]"
                        "Bmatrix" -> "{$inner}"
                        "vmatrix" -> "|$inner|"
                        "Vmatrix" -> "‖$inner‖"
                        else -> inner
                    }
                }
            }
        }

        /** Split [body] on [sep] (`\\\\` or `&`) where it sits outside every brace group. */
        fun splitTopLevel(body: String, sep: String): List<String> {
            val out = mutableListOf<String>()
            val rowBreak = sep == "\\\\"
            var depth = 0
            var last = 0
            var j = 0
            while (j < body.length) {
                val c = body[j]
                if (rowBreak && depth == 0 && body.startsWith("\\\\", j)) {
                    out += body.substring(last, j); j += 2; last = j; continue
                }
                // An escape or a command's first letter is never a separator: `\&` is an ampersand.
                if (c == '\\' && j + 1 < body.length) { j += 2; continue }
                if (c == '{') depth++
                if (c == '}') depth--
                if (!rowBreak && depth == 0 && body.startsWith(sep, j)) {
                    out += body.substring(last, j); j += sep.length; last = j; continue
                }
                j++
            }
            out += body.substring(last)
            return out
        }
    }

    private val CONDITION_WORD = Regex("""^(if|otherwise|for|when|else|where)\b""")

    private fun accent(arg: String, mark: Char, overEach: Boolean): String {
        val base = arg.trim()
        if (base.isEmpty()) return mark.toString()
        if (overEach) return base.map { "$it$mark" }.joinToString("")
        return base + mark
    }

    /** A fraction part that reads unambiguously without brackets: no spaces, no operators. */
    private val SIMPLE_PART = Regex("""^[^\s+\-−*/=<>±()]+$""")

    private fun frac(a: String, b: String): String =
        if (b.isEmpty()) a
        else if (SIMPLE_PART.matches(a) && SIMPLE_PART.matches(b)) "$a/$b" else "($a)/($b)"

    private fun script(body: String, table: Map<Char, Char>, fallback: String): String {
        val inner = body.trim()
        if (inner.isEmpty()) return ""
        return if (inner.all { it in table }) inner.map { table.getValue(it) }.joinToString("")
        else if (inner.length == 1) fallback + inner
        else "$fallback($inner)"
    }
}
