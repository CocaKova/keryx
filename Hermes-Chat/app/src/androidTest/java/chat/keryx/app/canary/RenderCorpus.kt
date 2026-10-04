package chat.keryx.app.canary

import chat.keryx.app.presentation.ui.components.CodeHighlighting

/**
 * The bodies the canary renders on a real device.
 *
 * Two crashes shipped in 2.6.2 that every JVM test on this repo was structurally unable to
 * see, because both need Android to fail:
 *
 *  - a bare `}` in a regex character class, which the JVM accepts and Android's ICU engine
 *    rejects — `MathUnicode`'s class-initializer threw, and every rendered message killed
 *    the app;
 *  - the renderer's own highlighted-code composable, which nests a second `horizontalScroll`
 *    inside Keryx's — Compose refuses the infinite width that hands the inner one at measure
 *    time, so every fence tagged with a grammar the tokenizer knew died as it scrolled in.
 *
 * So the corpus is not a list of the two bodies that broke. It is generated from the app's
 * own tables, so a tag or a transform added later is covered the day it is added and nobody
 * has to remember to add a case here:
 *
 *  - [fences] walks [CodeHighlighting.knownTags] — every fence tag the app claims a grammar
 *    for, aliases included. Add `"ps1" to "powershell"` to the alias map and this grows by one.
 *  - [math] sweeps the shapes `MathUnicode` transforms, braces and all, including malformed
 *    input, because the failure was in *loading* the transform, not in any one formula.
 *  - [prose] covers the GFM and renderer-parity surface (tables, strikethrough, images,
 *    mermaid, markers) and the long-body path that goes through `MarkdownCache`.
 */
object RenderCorpus {

    /** A body per fence tag the app maps onto a grammar — the crash was per-tag. */
    val fences: List<Case> = CodeHighlighting.knownTags.sorted().map { tag ->
        Case(
            name = "fence:$tag",
            body = """
                Here is a fence tagged `$tag`, with a long line that must overflow the bubble
                horizontally so the code block's own scroller is measured rather than skipped.

                ```$tag
                $LONG_CODE_LINE
                x = 1
                ```
            """.trimIndent(),
        )
    }

    /** A fence tagged with something no grammar answers to still has to render as plain text. */
    val unknownFences: List<Case> = listOf("", "notalanguage", "text", "…", "c++/cli").map { tag ->
        Case("fence-unknown:${tag.ifEmpty { "<blank>" }}", "```$tag\n$LONG_CODE_LINE\n```")
    }

    /** The math surface — the class-init failure fires on the first body that touches it. */
    val math: List<Case> = listOf(
        Case("math:inline-greek", "The angle ${'$'}\\alpha${'$'} meets ${'$'}\\beta${'$'} at ${'$'}\\theta${'$'}."),
        Case("math:block-frac", "$$\\frac{a+b}{c-d}$$"),
        Case("math:nested-braces", "$$\\sqrt{\\frac{x^{2n}}{y_{i+1}}}$$"),
        Case("math:unbalanced-close", "A stray }} brace and ${'$'}x^{2${'$'} left open."),
        Case("math:unbalanced-open", "A stray {{ brace and ${'$'}\\frac{1}{${'$'} left open."),
        Case("math:operators", "$$\\sum_{i=0}^{n} x_i \\leq \\int_0^\\infty f(t)\\,dt \\Rightarrow \\infty$$"),
        Case("math:dollars-in-code", "Shell: `echo ${'$'}HOME` and `${'$'}{PATH}` are not formulae.\n\n```bash\necho ${'$'}USER ${'$'}{HOME}\n```"),
        Case("math:currency", "It cost ${'$'}5 and then ${'$'}10, which is not math."),
    )

    /** GFM, renderer parity, the phone-act markers, and the cached long-body path. */
    val prose: List<Case> = listOf(
        Case("gfm:table", "| door | state |\n| --- | ---: |\n| Runs | 3 new |\n| Bots | idle |"),
        Case("gfm:strikethrough", "This is ~~struck~~ and this is **black** and *slanted*."),
        Case("gfm:nested-lists", "- one\n  - two\n    - three\n      1. four\n      2. five\n\n> a quote\n> > nested"),
        Case("gfm:links-and-image", "[a link](https://example.invalid/x) and an image:\n\n![alt](https://example.invalid/nope.png)"),
        Case("render:mermaid", "```mermaid\ngraph TD\n  A[Spec] --> B[Code]\n  B --> C{Green?}\n  C -->|yes| D[Ship]\n  C -->|no| B\n```"),
        Case("render:marker-ok", "Tap to act: ⟦keryx:do|navigate|https://example.invalid⟧"),
        Case("render:marker-malformed", "Broken markers stay literal: ⟦keryx:do|⟧ and ⟦keryx:do⟧ and ⟦keryx:do|navigate"),
        Case("render:empty", ""),
        Case("render:whitespace-only", "\n\n   \n\n"),
        Case("render:long-body", buildString {
            // Past MarkdownCache.MIN_CHARS so the cached-tree path is the one under test.
            append("# A long answer\n\n")
            repeat(40) { i ->
                append("Paragraph $i with **bold**, `code`, ~~struck~~ text and a [link](https://example.invalid/$i).\n\n")
            }
            append("```kotlin\nval x = listOf(1, 2, 3).map { it * 2 }\n```\n")
        }),
    )

    /**
     * Every fence language the app draws natively (2.17), generated from [RichBlocks.LANGS] plus
     * the parser's own math/table/svg tags: a sample body that parses, and a broken one that must
     * fall back to a code block. A new rich language is covered the day it is added.
     */
    val rich: List<Case> = (chat.keryx.core.protocol.RichBlocks.LANGS + setOf("math", "latex", "csv", "tsv", "svg"))
        .sorted()
        .flatMap { lang ->
            listOf(
                Case("rich:$lang", "Before.\n\n```$lang\n${richSample(lang)}\n```\n\nAfter."),
                Case("rich-broken:$lang", "```$lang\n{ not [ valid\n```"),
                Case("rich-open:$lang", "Streaming:\n```$lang\n${richSample(lang).lines().first()}"),
            )
        } + listOf(
            Case("rich:display-math", "Energy:\n\n$$\n\\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}\n$$\n\nand \\[ \\sum_{i=0}^{n} x_i \\]"),
            Case("rich:display-math-matrix", "$$\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}$$"),
            Case("rich:alerts", "> [!NOTE]\n> A note.\n\n> [!WARNING]\n> Careful **now**.\n\n> [!CAUTION]\n> Last one."),
            Case("rich:preview-directive", "Built it.\n::preview{file=\"/tmp/widget.html\"}"),
            Case("rich:table-aligned", "| a | b | c |\n|:--|:-:|--:|\n| `x\\|y` | **2** | [l](https://example.invalid) |\n| 3 | 1 | 2 |\n| 1 | 3 | 1 |"),
        )

    /** Hostile and degenerate inputs for the 2.17 blocks: everything must draw or fall back. */
    val edge: List<Case> = listOf(
        Case("edge:chart-huge", "```chart\n{\"type\":\"bar\",\"labels\":[\"a\",\"b\"],\"values\":[1e308,-1e308]}\n```"),
        Case("edge:chart-tiny", "```chart\n{\"type\":\"line\",\"values\":[1e-300,2e-300,3e-300]}\n```"),
        Case("edge:chart-zeros", "```chart\n{\"type\":\"bar\",\"labels\":[\"a\",\"b\"],\"values\":[0,0]}\n```"),
        Case("edge:chart-one-point", "```chart\n{\"type\":\"area\",\"labels\":[\"only\"],\"values\":[7]}\n```"),
        Case("edge:chart-gaps", "```chart\n{\"type\":\"line\",\"values\":[null,3,null,null,5,null]}\n```"),
        Case("edge:chart-200", "```chart\n{\"type\":\"line\",\"values\":[" + (1..200).joinToString(",") + "]}\n```"),
        Case("edge:chart-labels", "```chart\n{\"type\":\"hbar\",\"title\":\"" + "Very long title ".repeat(12) + "\",\"labels\":[\"🌧️ rain\",\"" + "x".repeat(120) + "\",\"日本語\"],\"values\":[3,2,1],\"unit\":\"%\"}\n```"),
        Case("edge:chart-stacked-negative", "```chart\n{\"type\":\"bar\",\"stacked\":true,\"labels\":[\"a\",\"b\"],\"series\":[{\"values\":[3,-2]},{\"values\":[-1,4]}]}\n```"),
        Case("edge:donut-sliver", "```chart\n{\"type\":\"donut\",\"labels\":[\"big\",\"tiny\"],\"values\":[1000000,0.0001]}\n```"),
        Case("edge:details-nested", "```details\nOuter\n```kotlin\nval x = 1\n```\n```details\nInner\nbody\n```\n```\nAfter."),
        Case("edge:details-empty", "```details\nTitle only\n```"),
        Case("edge:svg-hostile", "```svg\n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"40\"><script>alert(1)</script><image href=\"https://example.invalid/x.png\" width=\"10\" height=\"10\"/><rect width=\"100\" height=\"40\" fill=\"red\"/></svg>\n```"),
        Case("edge:svg-broken", "```svg\n<svg><rect width=\n```"),
        Case("edge:svg-giant", "```svg\n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100000\" height=\"100000\"><rect width=\"100000\" height=\"100000\"/></svg>\n```"),
        Case("edge:card-hostile", "```card\ntitle: X\nurl: javascript:alert(1)\nimage: http://example.invalid/a.png\n" + "field: v\n".repeat(30) + "```"),
        Case("edge:progress-odd", "```progress\nOver: 150%\nZero: 0/0\nNeg: -5%\n```"),
        Case("edge:timeline-long", "```timeline\n" + (1..60).joinToString("\n") { "2026-01-${(it % 28) + 1} · Event $it — " + "detail ".repeat(10) } + "\n```"),
        Case("edge:table-wide", "| " + (1..30).joinToString(" | ") { "col$it" } + " |\n|" + "---|".repeat(30) + "\n| " + (1..30).joinToString(" | ") { "v$it" } + " |"),
        Case("edge:math-broken", "$$\n\\frac{1}{\n$$\n\nand $$\\begin{cases} x & y \\end{pmatrix}$$"),
        Case("edge:math-deep", "$$" + "\\frac{1}{".repeat(30) + "x" + "}".repeat(30) + "$$"),
        Case("edge:diff-long", "```diff\n" + (1..300).joinToString("\n") { if (it % 2 == 0) "+added line $it" else "-removed line $it" } + "\n```"),
        Case("edge:everything-open", "```chart\n{\"type\":\"bar\""),
    )

    private fun richSample(lang: String): String = when (lang) {
        "chart" -> """{"type":"bar","title":"Tokens","labels":["Mon","Tue","Wed"],"series":[{"name":"in","values":[3,5,2]},{"name":"out","values":[1,2,4]}],"unit":"k"}"""
        "diff", "patch" -> "--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n-old line\n+new line\n same"
        "timeline" -> "- [x] 2026-09-01 · Shipped 2.16\n- [ ] 2026-10-03 · Rendering pass — charts and math"
        "progress" -> "Build: 60%\nTests: 3/5"
        "swatch", "colors", "colours", "palette" -> "ink: #1B1A17\npaper: #F4EFE6\naccent: #C4572A"
        "card" -> "title: Keryx 2.17\nsubtitle: rendering\nurl: https://example.invalid\nversion: 122\nbody: Charts, math and more."
        "details", "collapse" -> "Why it matters\nBecause **pictures** beat prose sometimes.\n\n- one\n- two"
        "math", "latex" -> "\\int_0^\\infty e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}"
        "csv" -> "name,score\nAda,3\n\"Lovelace, A\",5"
        "tsv" -> "name\tscore\nAda\t3"
        "svg" -> """<svg xmlns="http://www.w3.org/2000/svg" width="120" height="60"><rect width="120" height="60" rx="8" fill="#C4572A"/><circle cx="30" cy="30" r="18" fill="#F4EFE6"/></svg>"""
        else -> "x"
    }

    /** Everything, in a stable order. */
    val all: List<Case> = fences + unknownFences + math + prose + rich + edge

    data class Case(val name: String, val body: String)

    private const val LONG_CODE_LINE =
        "// a deliberately long line so the code block overflows and its horizontal scroller is measured, not skipped ------------------------------------"
}
