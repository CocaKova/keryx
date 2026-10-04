# Third-party code in Keryx

Source and assets copied into this repository (as opposed to dependencies pulled from a package
registry), with where each came from and what was changed. Keryx itself is MIT
([LICENSE](../LICENSE)); the pieces below keep their own licenses, whose full texts are in
[LICENSES/](../LICENSES/).

**When you add something here:** anything ported, copied or bundled (code, regexes, fonts, icons,
images) gets an entry below in the same change, a header in the file itself (author, source URL,
upstream path at a commit, license, what was modified), and its license text in `LICENSES/` if
it isn't there yet.

## Kai math renderer

- **From:** [SimonSchubert/Kai](https://github.com/SimonSchubert/Kai),
  `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/ui/markdown/math/` at
  `3818b2fccc0fb9bc977ab198b8bdb4a647dbab47`
- **License:** Apache-2.0, [LICENSES/Apache-2.0.txt](../LICENSES/Apache-2.0.txt) (header kept on
  every ported file, each marked as modified)
- **Taken:** `MathAtom.kt`, `MathParser.kt`, `MathRenderer.kt`, `MathSymbols.kt`: a LaTeX math
  parser and a pure-Compose typesetter (fractions, radicals, scripts, big operators with limits,
  `\left…\right`, accents, matrices and `cases`/`aligned`, font styles).
- **Now at:** `Hermes-Chat/app/src/main/java/chat/keryx/app/presentation/ui/components/math/`
- **Changed:** package moved; `kotlinx.collections.immutable` replaced with plain `List`; the AST
  types made `internal`; the public `MathFormula` entry replaced by an internal
  `MathFormulaContent` that takes an explicit colour; inline scripts sized relative to their base
  (`em`) instead of a fixed `12.sp`. Keryx's own entry point is `MathBlock.kt`.

## Cinzel typeface

- **From:** [NDISCOVER/Cinzel](https://github.com/NDISCOVER/Cinzel) by Natanael Gama, version 2.000
- **License:** SIL Open Font License 1.1,
  [LICENSES/OFL-1.1-Cinzel.txt](../LICENSES/OFL-1.1-Cinzel.txt)
- **Now at:** `Hermes-Chat/app/src/main/res/font/cinzel.ttf` (the KERYX wordmark)
- **Changed:** nothing; the file is bundled as released.

## Hermes Agent desktop conventions

- **From:** [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent) `apps/desktop`
  and `web`, by Nous Research
- **License:** MIT, [LICENSES/MIT-Hermes-Agent.txt](../LICENSES/MIT-Hermes-Agent.txt)
- **Taken:** small behaviours kept identical so Keryx and Hermes Desktop agree: the `MEDIA:` line and
  tag regexes (desktop `chat-messages.ts`), the reasoning-effort scale, labels and option list
  (desktop and web `reasoning-effort.ts`), and the per-tool verbs and glyphs behind the tool rows
  (desktop `TOOL_META`, by way of Talaria's `ToolTheater`).
- **Now at:** `core/.../model/MediaTags.kt`, `ReasoningEffort.kt`, `ToolGrammar.kt` (each names its
  upstream source in its header)
- **Changed:** rewritten in Kotlin; the media regex stops a bare path at a closing quote.
