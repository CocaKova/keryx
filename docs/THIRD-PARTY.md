# Third-party code in Keryx

Source copied into this repository (as opposed to dependencies pulled from a package registry),
with where it came from and what was changed.

## Kai math renderer

- **From:** [SimonSchubert/Kai](https://github.com/SimonSchubert/Kai),
  `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/ui/markdown/math/` at
  `3818b2fccc0fb9bc977ab198b8bdb4a647dbab47`
- **License:** Apache-2.0 (header kept on every ported file)
- **Taken:** `MathAtom.kt`, `MathParser.kt`, `MathRenderer.kt`, `MathSymbols.kt`: a LaTeX math
  parser and a pure-Compose typesetter (fractions, radicals, scripts, big operators with limits,
  `\left…\right`, accents, matrices and `cases`/`aligned`, font styles).
- **Now at:** `Hermes-Chat/app/src/main/java/chat/keryx/app/presentation/ui/components/math/`
- **Changed:** package moved; `kotlinx.collections.immutable` replaced with plain `List`; the AST
  types made `internal`; the public `MathFormula` entry replaced by an internal
  `MathFormulaContent` that takes an explicit colour; inline scripts sized relative to their base
  (`em`) instead of a fixed `12.sp`. Keryx's own entry point is `MathBlock.kt`.
