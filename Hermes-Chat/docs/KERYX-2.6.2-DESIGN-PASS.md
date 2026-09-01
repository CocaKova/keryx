# Keryx 2.6.2 — the design pass: one icon family, coloured tool log, a renderer that keeps up (2026-09-01)

Jonny: "the next pass will be strictly design of icons, placement, and gesture navigation. Tool
call log visuals and color coding. Lastly, check to make sure the renderer can do everything the
Frontier AI web chats can when rendering content."

Grounded in a device survey (chat floor, drawer, Gateway space captured over adb on port 37491,
2.6.2 vc74 installed 17:13 CT) plus a full source inventory of every icon, control placement and
gesture handler.

## 1. Icons — one family

**Found:** four vocabularies on the composer row alone (Material `Add`/`Mic`/`Send`, the three
hand-drawn `KeryxGlyphs`, text carets `▾`, emoji `⚡💭` in the model menu); Material icons of
different weights across the door tiles (Shipyard `Construction` and Workshop `Handyman` were the
same crossed tools twice); `Dns` used for both the Gateway door and Settings ▸ Connection; emoji
`🖼📎` in the attachment preview beside a Material `Close`; three different delete icons.

**Now:** `KeryxGlyphs.kt` is the whole family — Talaria's set harvested verbatim (53 glyphs,
24-unit viewport, 1.9 round strokes, tinted by the caller) plus Keryx's own: `GitBranch`
(Shipyard), `Wrench` (Workshop), `Phone` (Call), `NewChat` (bubble with a plus), `Refresh`,
`Warning`, `Hourglass`, `Star`, `Check`, `Menu`. Every surface a hand touches daily draws from
it:

| surface | glyphs |
|---|---|
| top bar | Sidebar (menu) · NewChat · Phone |
| drawer header | Sun/Moon/AutoTheme (theme) · Plus (new chat) · Search |
| drawer doors | Board · Archive · Folder · GitBranch · Watch · Pulse · Wrench · Sliders |
| room badges | Hourglass (temporary) · Star (pinned) |
| composer | Plus (attach, rotates to ×) · Mic · ArrowUp / Steer / Stack / StopSquare |
| attach bloom, preview, reply bar | Image · FileClip · Close |
| bubble actions | Reply · Copy · Bookmark/BookmarkFilled · Volume/StopSquare · Trash |
| space headers | Close · Refresh |
| jump-to-newest chip | ChevronDown |

Model-menu capability tags are words now (`fast · thinks`, mono), not emoji. Send is the
desktop's arrow-up, never a paper plane among hand-drawn glyphs. Door glyphs grew 19 → 22 dp.

**Not touched (deliberate):** the typographic marks that ARE the app's grammar — `✓ ✕ ·`
verdicts, `▾ ▸` disclosures, `⑂` wings, `✦` skills, the tool glyph set in `ToolGrammar` — and
the deeper surfaces (Settings section icons, Archive media tab, Missions, Call). Those are a
second sweep once this one is walked.

## 2. Placement

- **Theme toggle left the door grid.** It was the one tile that took you nowhere, styled as a
  door. It sits in the drawer header beside the new-chat plus, cycling Dark → Light → System with
  the matching glyph. The grid is destinations only: eight doors, four a row, two rows.
- Everything else stays where the earlier passes put it: composer footer = model pill · reasoning
  pill · steer hint · context ring; top bar = link-health dot · new session · call.

## 3. Gesture navigation — audited, one finding

The map, from source (all live on 2.6.2):

| gesture | where | does |
|---|---|---|
| drag right, anywhere on the floor | chat | opens the drawer (`ModalNavigationDrawer`, plus a nested-scroll assist over code blocks / wide tables) |
| swipe left on a bubble | chat | reply (56 dp, haptic at commit) |
| long-press a bubble | chat | reactions + action bar |
| double-tap a bubble | chat | ❤️ |
| long-press the send circle | composer | queue (while steerable) / hint (while stop) |
| long-press a room / avatar / Quick Room tile | drawer | room menu / set photo / pin |
| swipe between tabs | Gateway, Workshop | pager |
| pinch, pan, double-tap | media viewer | zoom |
| system back | spaces | predictive back with live arrival scrub; nested back in Projects / Shipyard / Settings |

**Finding:** a swipe from the very left screen edge is Android's back gesture, not ours — it
dropped the app to the previous task on the walk. Nothing to fix in Keryx (the drawer already
opens on a drag from anywhere on the floor); it is recorded so nobody "fixes" it by claiming the
system edge. No new gestures were added: every door already has one, and a gesture that only a
doc explains is not navigation.

## 4. The tool log — colour coding

**Was:** the run row was ink-on-ink. Verdict ✓ at 45 % of the text colour, ✕ in the theme's
error red, the tool glyph at 55 %. A run of twelve calls was a grey list.

**Now:** two colour systems, kept apart on purpose:

- **Verdicts wear `KeryxStatus`** — ✓ in `good` (green, 80 %), ✕ in `bad`, unknown `·` stays
  faint ink. The same green/red every status dot in the app uses, so "failed" is one colour
  everywhere.
- **Glyphs wear their family (`KeryxToolTint`)** — `ToolGrammar.familyOf` puts every tool in one
  of eight: SHELL gold, FILES slate, EDIT amber, WEB teal, MIND violet (memory, skills, recall,
  todo), MEDIA rose, PEOPLE magenta (delegate, clarify, cron), OTHER faded ink. Void and paper
  sets, chosen by the ground like `KeryxStatus`; every paper hue clears 4.5:1 on parchment and
  every void hue on black (`PaperContrastTest` pins both maps and that they cover the same
  families). The run header's collapsed glyph strip is tinted per glyph too, so the run's shape
  reads before it is opened.

Skill saves keep their accent `✦`. Diff stats, durations, `▸ output` / `▸ diff` folds unchanged.

## 5. Renderer parity — what the web chats do, and where Keryx stood

Checked against the 0.35 renderer's actual class list (pulled from the AAR), not its README.

| feature | before | now |
|---|---|---|
| tables | own Compose grid ✓ | unchanged |
| code blocks | mono, copy button, no language, no colour | **language tag** in the corner + **syntax highlighting** (`highlights` via the renderer's `-code` module, pastel theme keyed to the ground); unknown languages fall back to plain mono |
| inline images `![alt](url)` | drew NOTHING (default transformer is a no-op) | **coil3** transformer wired (`-coil3` module + `coil-network-okhttp`) |
| `~~strikethrough~~` | printed the tildes (GFM flavour parses it, renderer has no element) | **annotator** draws it with a line-through |
| task lists `- [ ]` | renderer has `MarkdownCheckBox` under the GFM flavour | unchanged (already worked) |
| LaTeX `$…$`, `$$…$$`, `\(…\)`, `\[…\]` | raw TeX | **`MathUnicode`** (core, tested): Greek, operators, super/subscripts, simple fractions and roots → readable Unicode; block math becomes its own `⟦ … ⟧` paragraph. Skips fences and code spans; `$5 and $6` stays money. Not KaTeX — nested fractions and matrices come out flat |
| mermaid | own parser + Compose layout ✓ (subset) | unchanged |
| links, autolinks | ✓ (2.6.0/2.6.1) | unchanged |
| headings, lists, quotes, rules | ✓ | unchanged |
| footnotes, `<details>`, raw HTML, spoilers | not rendered | **still not** — intellij-markdown's GFM flavour has no footnote or HTML-tree support; the web chats mostly sanitise HTML away anyway. Recorded as the known gap |

New dependencies: `multiplatform-markdown-renderer-code` and `-coil3` (both 0.35.0, same
Kotlin 2.1 line), `io.coil-kt.coil3:coil-compose` + `coil-network-okhttp` 3.1.0.

## 6. Traps

- **Renderer add-ons must stay on the SAME version as `-m3`** (0.35.0) — mixed versions crash
  at runtime on `MarkdownComponentModel` shape changes.
- **`MarkdownHighlightedCode` takes positional args** in 0.35 (no `highlightsBuilder` name).
- **The math pass runs before `closeDanglingFences`** so an unclosed fence mid-stream still
  protects its `$` from being read as math.
- **Compose `Color.value` is a ULong** — unpack with `(value shr 32).toLong() and 0xFFFFFFFF`
  in JVM tests (the contrast test does).
- **`Icons.*` imports linger** in several files as warnings; stripping them is a lint sweep, not
  this pass.
- Left-edge swipe = system back (see §3).

## Status
- Tests: see the commit; +5 `MathUnicodeTest` (core), +1 tool-tint contrast test (app).
- ⚠️ NOT walked: the phone locked mid-capture. Walk list: door tiles + header theme toggle,
  top bar glyphs, composer row, bubble action bar, a tool run (tinted glyphs, green ✓), a code
  fence with a language, an inline image, `~~x~~`, `$E=mc^2$`.
