# Keryx 2.12 — "Tap-In"

*Tap the running agent and step inside the turn.*

Jonny's idea (2026-09-22, while watching a `/review` run from the phone): the transcript
tells you the agent is working, but it tells you quietly — one theater row per call, a
status banner, a breathing dot. Sometimes you want the opposite: a full-screen, beautifully
drawn view of the agent *going*. Tap in, watch, step back out; the transcript is untouched.

Sequenced **after 2.11.9** (bubble bar: smart copy, two-row popup, retry). Feature, so a
minor: `2.12.0`.

---

## What already exists (build on it, don't duplicate it)

Every fact the view needs is already reduced client-side; nothing new has to leave the
gateway, so **zero re-apply patches** and no keryx-stream change.

| Fact | Where it already lives |
|---|---|
| Tool calls: name, preview, verdict, ms, clipped result, `patch` diff body | `Theater.reduce` → `TheaterState.beats` (core), drawn by `ToolTheater.kt` |
| Subagent wings: goal, model, tool count, duration, tokens, summary; `thinking`/`progress` activity line | `ToolRunEntry.Delegated`, same reducer (`phase:"sub"`) |
| Thinking, live | `reasoning` deltas → `stream.reasoning` (`TurnRenderers.kt`) |
| Answer text, live | `stream.text` |
| Context fill, tokens, api calls | `usage` → `KeryxContextRing`, footer telemetry |
| What the agent says it is doing | `status.update` banner (`ChatViewModel.kt:782`) |
| Review verdict, when the turn is a review | `review.summary` (`DirectTransport.kt:944`) |
| Flight plan | `FlightPlanStrip.kt` |
| "Agent running" notice, already tappable | `KeryxNotifications.kt:153` `tapIntent(context, roomId)` |

Wire limits that shape the design (`docs/features/streaming.md`): a child's raw assistant
text is dropped on the wire, so a subagent pane shows its activity line and cost, never a
transcript; ends correlate to starts by order, so the rail is FIFO like the theater already
is.

## The view

One screen, `TapInScreen`, over the chat (full-screen route, not a sheet — nothing floats
over the transcript, so the floor+contrast rule from 2.11.8 does not bite). Dark ground, the
existing dusk gradient, the wordmark language from `KeryxDesign.kt`. Four regions, top to
bottom:

1. **Headline** — the `status.update` line, large, with elapsed time ticking and the
   breathing dot. When there is no status line, the current tool's name + preview.
2. **Mind** — the reasoning stream as a soft ticker: last ~6 lines, older lines fading
   upward. Hidden entirely when the model sends no reasoning (nothing to fake).
3. **Rail** — the tool timeline as a vertical rail: glyph, monospace name, preview, live
   ms counter on the open call, verdict tick on closed ones. A `patch` beat expands to the
   `ToolDiffPanel` inline. Subagent wings render as cards on the rail with a small progress
   arc (tool count so far) and their activity line.
4. **Instruments** — a single row: context ring, tokens in/out, api calls, tool count,
   elapsed. Same numbers the footer shows, just drawn with room.

For a review turn: `review.summary`, when it lands, replaces the headline with the verdict
and the rail stops ticking. The screen does not close itself — the user steps out.

## Ways in, ways out

- Tap the live theater rail or the status banner while a turn runs → tap in.
- Tap the "Agent running" notification → chat opens **already tapped in** (extend
  `tapIntent` with an extra; the chat route reads it once).
- Back gesture, or swipe down from the headline → out. The transcript never moved.
- When the turn ends while tapped in: instruments freeze, headline shows the final line,
  the rail stays readable. A `lastTurnTheater` already exists (`ChatViewModel.kt:1322`) —
  tapping a *folded* theater row after a turn may open the same screen in its frozen state,
  a free replay.

## Rules to keep

- Reduced motion: no ticker fade, no arc animation, counters still tick (they are data).
- Nothing in here is a second vocabulary for the same call (3.1 §A2 stands): the screen
  draws the same `TheaterState` the transcript draws, only larger. One reducer, two views.
- Every string that the theater already prints comes from the shared grammar; do not
  re-word tool names or verdicts here.
- Status hues only via `KeryxStatus.shade*`.

## Tests (JVM)

- Reducer unchanged: `TapInState` is a pure projection of `TheaterState` + `stream` +
  `usage` (a `from(...)` function), tested on the mock corpus: headline picks status line,
  else open call; rail order = FIFO; review summary replaces headline.
- Notification extra round-trips into "opened tapped-in".
- Reduced motion flag reaches the ticker.
- Frozen state after turn end equals the last live state.

## Order of operations

1. `TapInState.from(...)` projection + tests.
2. `TapInScreen` composable, regions 1–4, on the design tokens.
3. Ways in (rail, banner, notification extra) and out (back, swipe).
4. Frozen replay from a folded theater row.
5. `versionCode` +1, `2.12.0`, doc page `docs/features/tap-in.md`, CHANGELOG. Build via
   `tools/ship.sh --detach` (VM 106), `--smoke` when the phone is on the LAN.

---

## Part B — "The Crew": every helper, presented

Jonny (2026-09-22): the review run is subagent-based; present the subagent — whatever aux it
may be — cleanly and beautifully, not as a footnote on the rail.

### What the phone already held (verified before building)

`Delegation` in `core/model/Theater.kt` is a complete record per child: `goal`,
`taskIndex/taskCount`, `model`, `depth`, `state`, the live `activity` line, a `trail` of the
child's own beats, `startedAtMs`, `toolCount`, `summary`, duration, tokens, api calls, files.
The wings (`DelegationWings.kt`) draw it at 9–10sp under a hairline, and a wing with a record
already opens `SubagentSessionSheet` — the live trail while flying, the stored child session
once landed. So the gap was presentation, not data, and no store was added.

### What 2.12.0 built (`presentation/tapin/`)

- `TapIn.kt` — the projection: `TapInState`, `CrewMember` (role = goal's first clause, glyph
  from the kind of helper the goal names), headline precedence, sorting (flying first), clocks.
  Pure Kotlin, `TapInTest` on the mock corpus.
- `TurnSlice.kt` — the current turn cut out of the rendered items, newest-first back to the
  last user message; both doors are already reconciled there. Structured beats enrich by
  position via `Theater.align`, never by guess.
- `TapInScreen.kt` — the standalone `KeryxSpace`: headline + clock, mind (tail, top fade),
  crew deck (`KeryxCard`s, shimmer while flying, summary once landed, tap → the existing
  sheet), rail (the transcript's own `ToolTheaterRow` hung from beads), saying, instruments.
- `TapInHost.kt` — collects the chat's flows, ticks, projects, hosts the helper sheet inside
  the space's own window.
- Doors: `WorkingStatusBar(onTapIn)` (tap, wears `⤢`), `ToolTheaterRun(onTapIn)` (long-press,
  newest run only), the run notice (`EXTRA_TAP_IN`, its own request code so
  `FLAG_UPDATE_CURRENT` cannot cross the two notices' extras; `ChatViewModel.requestTapIn`).

### Left for later

- The deck inside the chat itself (today the wings stay as they were; the deck lives in Tap-In).
- A persisted crew record for turns watched before a reinstall — not needed while a landed
  child's stored session is the fuller record anyway.
