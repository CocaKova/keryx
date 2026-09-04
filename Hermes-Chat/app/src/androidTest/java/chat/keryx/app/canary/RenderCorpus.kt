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

    /** Everything, in a stable order. */
    val all: List<Case> = fences + unknownFences + math + prose

    data class Case(val name: String, val body: String)

    private const val LONG_CODE_LINE =
        "// a deliberately long line so the code block overflows and its horizontal scroller is measured, not skipped ------------------------------------"
}
