/*
 * Ported from Kai by Simon Schubert — https://github.com/SimonSchubert/Kai
 * Upstream: composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/ui/markdown/math/MathAtom.kt @ 3818b2fccc0fb9bc977ab198b8bdb4a647dbab47
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 *
 * Modified for Keryx: package moved; kotlinx.collections.immutable replaced with plain List
 */
package chat.keryx.app.presentation.ui.components.math

import androidx.compose.runtime.Immutable

/**
 * Minimal LaTeX math AST. Deliberately scoped to the subset of commands that show up in
 * real LLM output: fractions, scripts, radicals, big operators (with limits), delimiters,
 * a handful of font styles, and a symbol lookup for greek letters and operators.
 *
 * Unknown commands degrade to a literal [Sym] carrying the raw `\name` text — nothing in
 * the renderer crashes on malformed input.
 */
@Immutable
internal sealed interface MathAtom

/** Single typeset glyph: a letter, digit, operator symbol, or mapped LaTeX command. */
@Immutable
internal data class Sym(val text: String, val kind: SymKind = SymKind.ORDINARY) : MathAtom

internal enum class SymKind {
    /** Variable letters — rendered italic. */
    VARIABLE,

    /** Digits, punctuation, unit-like glyphs — rendered upright. */
    ORDINARY,

    /** Binary operators (+, -, ·, ×) — upright with symmetric spacing. */
    BIN_OP,

    /** Relation operators (=, <, >, ≤, ≈, →) — upright with symmetric spacing. */
    REL_OP,

    /** Function names like sin, cos, log, lim — upright, not italic. */
    FUNCTION,

    /** Opening delimiter that doesn't stretch (e.g. a bare `(` without \left). */
    OPEN,

    /** Closing delimiter. */
    CLOSE,

    /** Punctuation — upright, no extra spacing. */
    PUNCT,
}

@Immutable
internal data class Group(val atoms: List<MathAtom>) : MathAtom

@Immutable
internal data class Frac(val num: MathAtom, val den: MathAtom, val drawBar: Boolean = true) : MathAtom

/** Subscript/superscript attached to [base]. One or both of [sub]/[sup] may be present. */
@Immutable
internal data class Script(val base: MathAtom, val sub: MathAtom?, val sup: MathAtom?) : MathAtom

@Immutable
internal data class Radical(val index: MathAtom?, val radicand: MathAtom) : MathAtom

/**
 * Big operator like ∑, ∫, ∏, ⋃. In display mode [sub]/[sup] are typeset above/below the
 * operator (limits); in inline mode they fall through to [Script] positioning. When
 * [alwaysLimits] is true (e.g. `\lim`), limits are used even inline.
 */
@Immutable
internal data class LargeOp(
    val symbol: String,
    val sub: MathAtom? = null,
    val sup: MathAtom? = null,
    val alwaysLimits: Boolean = false,
) : MathAtom

/** `\left X ... \right Y` — brackets stretch to the height of [content]. */
@Immutable
internal data class Delim(val left: String, val right: String, val content: MathAtom) : MathAtom

@Immutable
internal data class Styled(val style: MathStyle, val atoms: List<MathAtom>) : MathAtom

/**
 * `\hat{x}`, `\bar{x}`, `\vec{v}`, `\tilde{y}`, `\dot{x}`, `\ddot{x}` — single-glyph accent
 * centered above [base]. The widening variants (`\overline`, `\widehat`, `\widetilde`) stretch
 * to match the base's width instead.
 */
@Immutable
internal data class Accent(val base: MathAtom, val kind: AccentKind) : MathAtom

internal enum class AccentKind {
    HAT,
    BAR,
    VEC,
    TILDE,
    DOT,
    DDOT,
    OVERLINE,
    WIDEHAT,
    WIDETILDE,
}

/**
 * A 2D grid of cells from environments like `pmatrix`, `cases`, or `aligned`. Rows are
 * separated by `\\` and cells by `&` in the source; cells may themselves be arbitrary math.
 */
@Immutable
internal data class Matrix(
    val rows: List<List<MathAtom>>,
    val delim: MatrixDelim,
    val alignMode: MatrixAlign = MatrixAlign.CENTERED,
) : MathAtom

internal enum class MatrixDelim(val left: String, val right: String) {
    NONE("", ""),
    PAREN("(", ")"),
    BRACKET("[", "]"),
    BRACE("{", "}"),
    VBAR("|", "|"),
    DBLVBAR("‖", "‖"),
    CASES("{", ""),
}

internal enum class MatrixAlign {
    /** All cells horizontally centered — default for pmatrix / bmatrix / matrix / vmatrix. */
    CENTERED,

    /** All cells left-aligned — used by `cases`. */
    LEFT,

    /** Odd columns right-aligned, even columns left-aligned — `aligned` / `align`. */
    ALIGN_RL,
}

internal enum class MathStyle {
    /** `\text{...}` — upright, rendered as ordinary text with spaces preserved. */
    TEXT,

    /** `\mathbf{...}` — bold upright. */
    BOLD,

    /** `\boldsymbol{...}` — bold italic, used for bold greek letters and bold variables. */
    BOLD_ITALIC,

    /** `\mathit{...}` — italic (default for letters, but useful to force it). */
    ITALIC,

    /** `\mathrm{...}` — upright roman. */
    ROMAN,

    /** `\mathbb{...}` — double-struck (via Unicode mapping where available). */
    DOUBLE_STRUCK,

    /** `\mathcal{...}` — calligraphic (best-effort Unicode mapping). */
    CALLIGRAPHIC,
}

/** Horizontal spacing: \, \: \; \! \quad \qquad, measured in em. */
@Immutable
internal data class Space(val emWidth: Float) : MathAtom
