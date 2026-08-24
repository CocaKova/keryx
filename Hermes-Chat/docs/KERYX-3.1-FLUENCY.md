# Keryx 3.1 — fluency

**Written 2026-08-23, against `keryx-3.0-absorption` @ `b3d8e8c` (511 tests green, build clean).**

3.0 answered *"can one app speak both wires?"* — yes, and it does. This plan answers the question
that came right after it, which is Jonny's, verbatim:

> systems overlap like the tool calling … it's no longer fluent in the matrix side and has two
> things showing now for tool calls … basically fluency of chat and how we present it to the user.
> ideally, we have the exact way we had it in Talaria for both.

3.0 was an *absorption*: every organ arrived and none was retired. The result is an app with two
transports, **five tool renderers, three reasoning renderers, and two live-turn mechanisms**, where
what you see depends on which door you came through. That is the whole bug. It is not a hundred
small bugs.

**This plan is about deleting, not adding.** Nothing on this page is a feature.

---

## 1. The inventory — what actually overlaps

Counted by reading every call site, not by memory.

### Tool calls — five renderers

| # | Renderer | Lines | Fed by | Shows on |
|---|---|---|---|---|
| 1 | `ToolTheaterRun` / `ToolTheaterRow` (`ToolTheater.kt`) | 450 | `ChatRenderItem.ToolRun` from `groupChatItems` | the transcript, **both doors** |
| 2 | `TheaterStage` / `TheaterRow` (`TheaterStage.kt`) | 419 | `LiveStream.theater` ← SSE `event: tool` | inside the Matrix streaming bubble, **Matrix only** |
| 3 | `ToolCalls` → `ToolTheaterRow` (`MessageContent.kt:481`) | 15 | `MessageParser.Segment.Tools` | inline in **any** bubble whose text has tool lines — Archive, `SessionLiveTurn`, `StreamingBubble`, telemetry rows |
| 4 | `console.tools` monospace tail (`SessionLiveTurn.kt:104`) | 8 | `ConsoleDelegate` raw lines | Sessions-tab Resume |
| 5 | `ToolActivityCard` (`ChatScreen.kt:1538`) | 100 | `Message.toolActivity` | **nowhere — dead** |

⚠️ **#5 is never populated.** `grep 'toolActivity ='` across the whole tree returns one hit, and it
is the render call itself. 100 lines of renderer, a field on the core `Message`, and a term in
ChatScreen's scroll `snapshotFlow` signature — all ghost.

### Reasoning — three renderers

| Renderer | Fed by | Shows on |
|---|---|---|
| `ReasoningCanvas` (`MessageContent.kt:398`) | `Segment.Thinking` (parsed 💭 / `<think>`) | Matrix bubbles + the live bubble, **both doors' streaming** |
| `ReasoningDisclosure` ("Thought for Ns") | `Message.reasoning` | **direct only** — `MatrixTransport` never sets the field |
| `RunReasoning` (`ToolTheater.kt:410`) | `ChatRenderItem.ToolRun.reasoning` | inside a run, **Matrix only** in practice |

So the same thought renders as a purple canvas on Matrix and as a grey disclosure on direct, and a
third way again when it lands inside a run. **Nothing here is transport-specific except by accident.**

### The live turn — two mechanisms

| | Matrix | Direct |
|---|---|---|
| carrier | `LiveStream` overlay + `TheaterState`, outside the message list | synthetic `Message`s **inside** the message list (`DirectTransport.publish()`) |
| tool display | `TheaterStage` in the bubble | `ToolTheaterRun` in the transcript |
| reasoning | `ReasoningCanvas` in the bubble | `ReasoningDisclosure` on the streaming message |
| throttle | `STREAM_DISPATCH_MS` / `STREAM_DISPATCH_CHARS` | **none — `publish()` per token** (§4.1) |
| ends by | handoff match → committed event replaces the overlay | `message.complete` folds the overlay into base |

### "The agent is working" — up to six simultaneous signals

`WorkingStatusBar` (top, pinned) · `WaitingIndicator` (bottom slot) · the streaming bubble's caret
+ `≈tok/s` · `TheaterStage` rows · `ToolTheaterRun(active = true)` · `FlightPlanStrip`. The 2.5
attention budget governs whether they *move*; nothing governs whether they *all exist at once*.

---

## 2. Where the two things come from

Ranked by how strongly the code implicates them. **Confirm on device before cutting** (§7) — the
first walk decides which of these Jonny is looking at, and #1 and #2 have different fixes.

**#1 — the Matrix live turn shows every call twice.** With `tool_progress: all` (the gateway
default, and what Jonny runs) Hermes narrates each call as a **real Matrix message**. Those sync in
during the turn, group into a `ToolRun`, and render as theater rows *in the transcript*. Meanwhile
the same calls arrive over the side-channel as `event: tool` frames and render again as
`TheaterStage` rows *inside the streaming bubble*, a few dp below. Two renderers, two vocabularies,
one turn.

The codebase already knows this failure mode and only guards half of it — `ChatViewModel`'s
`consumeStreamedSegment` clears the theater on a mid-turn segment commit with the comment *"the
committed segment carries its own parsed tool rows, so keeping the beats would show every call
twice."* Correct diagnosis, applied to segment commits only. Tool-progress messages take the same
path and are not covered.

**#2 — a bubble that contains tool lines renders them inline.** `MessageContent` maps
`Segment.Tools` straight to `ToolTheaterRow` (#3 above). Grouping normally lifts tool-bearing
messages into a run so this never fires in the main transcript — but it fires anywhere that renders
message text *without* grouping: the Archive, `SessionLiveTurn`, and the streaming bubble whenever a
model writes tool-shaped lines into its own prose.

**#3 — Talaria's shape is the answer, and Keryx already half-has it.** Talaria's `StreamingBubble`
has **no theater inside it**: reasoning canvas, prose, caret, `≈tok/s`, and nothing else. Its tool
calls live in the transcript as `StructuredToolRun` rows, live and committed alike — one renderer,
one place, one grammar. That is what "the exact way we had it in Talaria" means, and Keryx's direct
door already behaves exactly that way. **Only the Matrix door bolted a second stage on top.**

---

## 3. The thesis

> **The transcript is the theater. One grammar, live and committed, on both doors.**

The bubble carries what the agent is *saying*. The transcript carries what the agent is *doing*.
A tool call never appears inside a bubble, and never appears twice.

Concretely, after this plan the live turn on **both** doors is:

```
  ┌ transcript ────────────────────────────────┐
  │  … earlier turns …                          │
  │  ▸ Ran 3 tools        ← ToolTheaterRun,     │   ONE tool renderer
  │                          live then settled  │
  │  ┌ bubble ─────────────────────┐            │
  │  │ 💭 Thought for 4s  (fold)   │            │   ONE reasoning renderer
  │  │ the answer, streaming…   ▍  │            │
  │  └─────────────────────────────┘            │
  └─────────────────────────────────────────────┘
       WorkingStatusBar pinned above: "Reading a.txt · 0:12 · ≈48 tok/s"
```

`TheaterState` / `Theater.reduce` **survive and matter** — they are the event reducer both doors
need. They stop being a *renderer* and become a *producer*, exactly the demotion §2 of the
absorption plan did to `MessageParser`. Same move, one layer up.

---

## 4. Sequence

Each phase is independently shippable and independently walkable. Order is not negotiable: A before
B before C, because each deletes state the next one would otherwise have to preserve.

### Phase A — one tool grammar

**Status: DONE 2026-08-23. 522 tests green (511 + 11 new), build clean, ✅ DEVICE-WALKED on the
Matrix door** — a real tool-running turn (`terminal` → a *parallel* `skill_view` batch → `web_fetch`)
with a mid-turn `/steer`, watched live and then settled. Live: one run row in the transcript, and
the streaming bubble carrying reasoning + caret + `≈12 tok/s` and **nothing else**. Settled: one
run, the steer in place, the answer quoting it — no duplication at any point, and the handoff
matched on the first committed event. Net **−420 lines**.

⚠️ **Walked on Matrix only.** The direct door is untouched by construction (`liveStream` is null
there, so the fold is the identity — see `nothingLiveIsTheIdentity`), but that is an argument, not
a walk. Cross the door before 3.1 ships.

Deviations from the plan as written, all deliberate:

- **A1 landed in the grouping layer, not the message flow.** The purest form of "the theater is a
  producer" is to synthesize the beats as `Message`s the way `DirectTransport` does, and let the
  walk place them. That flow feeds handoff matching and the work-state machine — the most
  timing-sensitive code in the app, and the absorption plan's §2 already warned about shredding it
  twice. `withLiveTheater` gets the identical user-visible result as a pure, tested function over
  the grouped list, and touches neither.
- **A3 kept the file and renamed it.** `TheaterStage.kt` → `DelegationWings.kt` (419 → 236),
  carrying `DelegationWings`, `DelegationWing`, `Rail` and `Pulse`. Moving 250 surviving lines into
  `ToolTheater.kt` would have been a large diff for no functional gain.
- **A2 nearly ate a feature.** The live stage owned the "open this subagent" tap, and the run's
  own `DelegationWings` call passed `onOpen = null`. Deleting the stage would have silently made
  every wing untappable. `ToolTheaterRun` now takes `onOpenSubagent` — which also means wings are
  tappable on the **direct** door for the first time.
- **A4 gave the Archive an opt-in instead of a run.** The plan said group the Archive and render
  `ToolTheaterRun`. The Archive reads one stored message at a time against a search anchor — there
  is no run to lift into and no second renderer to collide with, so the rule that matters ("no tool
  rows inside a chat bubble") is already satisfied. It passes `inlineTools = true`; everything else
  takes the default `false`.
- **A4's console went further than "delete the tail".** `ConsoleDelegate` was minting its own
  `"⚙ tool: preview"` / `"✓ tool"` strings — a fifth vocabulary. It now reduces its `ToolStarted` /
  `ToolCompleted` events through `Theater.reduce`, and `SessionLiveTurn` renders `ToolTheaterRow`.
  One model, one renderer, one reducer.
- **The 2.4 workaround came out.** `consumeStreamedSegment` used to clear the theater on a mid-turn
  segment commit *because* the second renderer would otherwise double every call. With the dedup
  doing that job properly, clearing there was destroying the first segment's durations, verdicts and
  diffs on every steered turn. Removed, with the reasoning recorded in place.


**A1. Theater frames become a producer.** On the Matrix door, reduce `event: tool` frames into
`ToolCall`s that reach the transcript, deduped against the committed tool-progress messages by
`(name, context)` — the rule `dedupCalls` already implements *within* a run, lifted to span the
overlay/committed boundary. Two sources, one row:

- a call with a committed Matrix message → render that message's row, **enriched** live with the
  frame's duration / verdict / diff (this is what `structured` + `lastTurnBeats` already does, but
  only after `clearStream`; make it continuous);
- a call with **no** committed message (`tool_progress: off` / `new`, or the message hasn't synced
  yet) → a synthetic overlay row, retired the moment its committed twin lands.

This keeps the theater honest under every `tool_progress` setting instead of assuming `all`.

**A2. Delete the stage from the bubble.** `StreamingBubble` loses its `TheaterStage` call and
becomes Talaria's shape exactly: reasoning, prose, caret, `≈tok/s`. This is the change that ends
"two things showing."

**A3. Retire `TheaterStage.kt`.** Its one part worth keeping is `DelegationWings`, already made
`internal` and already the single wings renderer for both producers — move it to `ToolTheater.kt`
and delete the other ~330 lines. `TheaterRow`'s expiring-telemetry voice dies with it; §3 of the
absorption plan argued that voice was *deliberately lesser* because live telemetry must not
out-shout the answer. That argument was correct for a stage inside a bubble. It does not survive the
stage.

**A4. `MessageContent` stops rendering tool calls.** Add `inlineTools: Boolean = false`; the
`Segment.Tools` branch renders nothing by default. Then give the two surfaces that *legitimately*
need tool rows the real renderer instead of the inline one:
- `ArchiveScreen` → group its messages and render `ToolTheaterRun`;
- `SessionLiveTurn` → same, replacing the `console.tools` monospace tail (renderer #4 dies here).

**A5. Delete `ToolActivity`, `ToolActivityCard`, and the field's term in ChatScreen's scroll
signature.** Dead since before the absorption; verified above.

**Checkpoint:** exactly one composable in the tree draws a tool call. `grep -c ToolTheaterRow` over
`app/src/main` returns its definition plus one call site.

### Phase B — one reasoning grammar

**Status: DONE 2026-08-23. 530 tests green (522 + 8 new in `ReasoningLiftTest`), build clean.
⚠️ NOT yet device-walked** — the checkpoint (same turn, same reasoning chrome on both doors) is a
walk item; cross both doors on a thinking turn before 3.1 ships.

Deviations from the plan as written, both deliberate:

- **B1 lifts the field but does not strip the content.** `MatrixTransport.toMessage` fills
  `Message.reasoning` via the new `MessageParser.reasoningOf` — which goes through `parse()`, not
  `extractReasoning` directly, so the keryx-marker unwrap and the self-improvement-review gate keep
  protecting it (markers can live *inside* the reasoning; stripping the thought from the raw body
  would tear citations out of the content, and a review quoting "<thought>" must not be lifted at
  all). Content keeps every line as the stored truth; the parse stays the single owner of what
  counts as thought; renderers stop drawing `Segment.Thinking`. Every existing content-parse site
  (work label, drawer preview, TTS, Archive indexing) keeps working unchanged. The one new cost:
  "reasoning-only" can no longer be judged by blankness — `MessageParser.isReasoningOnly` judges by
  the parse, and the ChatScreen disclosure-only gate uses it.
- **B3 took the first option, not the preferred one.** The run keeps carrying its consolidated
  reasoning block, but `RunReasoning` is deleted and the block renders through
  `ReasoningDisclosure` (stateKey = the stable run id, so the block growing live doesn't re-collapse
  a reader — the exact trap `RunReasoning`'s not-keyed-on-text comment recorded). Emitting the
  thoughts as standalone disclosure rows instead would either scatter N "Thought" rows above one
  run card or mean synthesizing messages — and the consolidated block ("one inner monologue, never
  interleaved with the steps") was a deliberate design, not jumble. One fact-owner
  (`Message.reasoning`), one settled voice (`ReasoningDisclosure`), which is what the phase is for.

What landed: `MessageParser.reasoningOf`/`isReasoningOnly` (:core); `toMessage` fills the field
(HERMES senders only — a human quoting a think tag keeps their words); `MessageContent` grows
`inlineReasoning` (default false; the Archive is the only opt-in, same shape as `inlineTools`) and
its `Segment.Thinking` branch draws the canvas only while streaming; grouping's run block prefers
the field over re-gathering segments; `RunReasoning` deleted.

**B1. Both producers fill the field.** `MatrixTransport` sets `Message.reasoning` from what
`MessageParser` already extracts (`Segment.Thinking`, `REASON_CODE`, `THINK_TAG`) instead of leaving
it as a segment for the renderer to trip over. The parser keeps every line; it stops deciding *how
a thought looks*. Same demotion as A1.

**B2. One renderer, two states.** `ReasoningDisclosure` is the settled form on both doors
("Thought for Ns"). `ReasoningCanvas` survives **only** as the live, still-thinking form inside the
streaming bubble — auto-expanded while the model is purely thinking, folding to the disclosure the
moment answer tokens start. That is Talaria's rule and it is already written in Keryx's own comment
at `ChatScreen.kt:2202`; it just isn't what the committed path does.

**B3. `RunReasoning` becomes the same disclosure**, or the run stops carrying reasoning at all once
B1 puts it on the message. Prefer the latter — one owner per fact.

**Checkpoint:** the same turn, watched over Matrix and read back over direct, shows the same
reasoning chrome.

### Phase C — one "working" signal

**C1. The work label reads structure first.** `ChatViewModel.updateWorkStateFrom` parses
`last.content` and nothing else. On the direct door a tool message has `content = ""` and its work
in `toolCalls`, so the parse finds nothing and the banner says **"Working"** forever, where Matrix
says "Reading a.txt". Read `Message.toolCalls` / `Message.reasoning` first, fall back to the parse.

**C2. Same fix for the drawer/notification preview** (`ChatViewModel.kt:150`): on direct it renders
"💭 thinking…" for every tool message instead of "🛠 read_file", for the same reason.

**C3. Budget the liveness signals.** Three, not six, and each answers a different question:
*what* (the top bar's label + elapsed), *which tool* (the live run row), *still writing* (the
caret). `WaitingIndicator` fires only before the first token; `FlightPlanStrip` stays (it is a plan,
not a status). Anything left over is redundancy the user reads as jitter.

**Checkpoint:** the top bar names the running tool on both doors.

### Phase D — Matrix fluency debt

The regressions above are all "direct gained a shape Matrix never got." These are the reverse, and
they are what "no longer fluent in the matrix side" means beyond the double:

- `Message.reasoning` / `toolCalls` / `delegations` are never set by `MatrixTransport` — after A1
  and B1, three of Keryx's own core fields stop being direct-only.
- The `ToolRun` enrichment window is one turn deep and room-keyed (`lastTurnTheater`), so scrolling
  up mid-turn loses the live verdicts. Correct as designed (§ "the answer a week later is the file,
  not a phone's memory of it") — but after A1 the enrichment is continuous, so re-check the boundary
  holds.
- `SubagentSessionSheet`, reactions, and FTS now exist on both doors; the Archive still does not
  reach direct (no REST `ArchiveIndexer`). Listed in Phase F, not here — it is missing, not jumbled.

### Phase E — the optimizations

**E1. ⚠️ The direct door publishes per token, unthrottled.** `DirectTransport.streamDelta` is
`buffer.append(text); publish()`. `publish()` rewrites the whole `messages` list, which drives
`groupChatItemsIncremental` over the trailing block, which re-walks and re-parses the growing
streamed body. The Matrix door has throttled this since 1.18.3 (`STREAM_DISPATCH_MS` /
`STREAM_DISPATCH_CHARS`); the direct door never got the guard. At ~50 tok/s that is ~50 full
republishes per second against Matrix's ~10.

**This is the leading candidate for the open ~5% direct-door frame drop** that the 08-21 smoothness
pass measured and left attributed only as "GPU-side, uploader un-attributed." It is a hypothesis,
not a finding — **measure before and after**, and keep the perfetto composition-tracing setup
(absorption plan §6) as the instrument.

**E2. ⚠️ Three streaming-body parses per publish are cached, and shouldn't be.**
`ChatRenderItems.kt` calls `MessageParser.parse(m.content)` at lines 180, 378 and 396 with the
default `cacheable = true`. `MessageContent` deliberately passes `cacheable = !isStreaming` with the
comment explaining why — a streamed intermediate is parsed once, never seen again, and every
distinct growing body evicts a committed message from the 1,200-entry LRU. Grouping never got that
memo. On a long direct turn the cache fills with dead intermediates and the committed transcript
starts missing on every scroll. Pass `cacheable = !m.isStreaming` at all three sites. Cheap, and it
has its own test hook already (`MessageParser.parseUncachedCount`).

**E3. `toolAhead` is O(block²) in parses.** The lookahead at `ChatRenderItems.kt:392` re-parses
every message ahead of `p` for every `p` in the block. The LRU hides it today; E2 removes the LRU's
protection for exactly the streaming case where the block is longest. Hoist the per-message
classification (`isToolMessage` / `isTelemetryMessage` / `proseLength`) into one pass over the
block before the walk.

**E4. `ChatScreen.kt` is 2,569 lines** — larger than it was before the ViewModel was decomposed.
`StreamingBubble`, `MessageBubble`, `PendingSendBubble`, `TelemetryMessageRow`, `WorkingStatusBar`
and `WaitingIndicator` are all self-contained composables sitting in the screen file. Lift them into
`components/` **after** Phase A, when three of them have shrunk. Not before — moving code that is
about to be deleted is churn.

### Phase F — what is missing, not jumbled

Unchanged from the absorption plan's Phase 5, restated so this page is a complete work list:
Projects surface, model-catalog picker (`model.options`), wake word, REST-hydration
`ArchiveIndexer`. Plus the Phase 0 items that need Jonny: git history rewrite, release keystore,
cut Talaria 0.7.10 and freeze.

**These are not in scope for 3.1.** 3.1 ends when the app says one thing once.

---

## 5. What each phase deletes

| Phase | Deleted |
|---|---|
| A ✅ | `TheaterStage.kt`'s stage (183), `ToolActivityCard.kt` (100), `Message.toolActivity`, the inline tool path's default, `console.tools` and its hand-rolled vocabulary — **−420 net** |
| B | one of `ReasoningCanvas`'s two roles, `RunReasoning` (~40) |
| C | up to three redundant liveness signals |
| E | ~0 lines, ~3 real perf defects |

Net: **roughly 500 lines out, no features lost.** Every one of them exists because 3.0 kept both
halves of something.

---

## 6. Traps

⚠️ **`tool_progress` is a gateway setting with four values.** `off` / `new` / `all` / `verbose`
(default `all`). A fix that assumes the committed tool messages exist breaks on `off`; a fix that
assumes they don't shows everything twice on `all`. A1's dedup must be the *source of truth*, not an
optimization — and the walk must cover at least `off` and `all`.

⚠️ **The absorption plan's §3 table is stale in the other direction now.** It ranked Talaria's
`ToolTheater` above Keryx's `ToolGroup`; Phase 3 correctly found Keryx had already caught up and
dissolved `ToolGroup` instead. Do not re-read that table as current. **This page supersedes it for
anything about tool rendering.**

⚠️ **`TheaterState` is not `TheaterStage`.** Delete the renderer, keep the reducer. `Theater.reduce`
and its `TheaterTest` pins (announced-together = shared `batchId`, never `concurrent`) are the
absorption's real work and A1 depends on them.

⚠️ **The concurrency distinction must survive A1.** Matrix's side-channel infers grouping from
*announcement order*; the gateway reports *observed overlap*. A shared `batchId` is "one dispatch,"
never proof of concurrency. `TheaterTest` pins this — do not let the dedup collapse the two claims.

⚠️ **`atBottom`-style composition-scope reads are still the transcript's jank tax.** Any new value
that changes per token or per scroll goes through `snapshotFlow` or a deferred `() -> T`, never a
plain param or a `LaunchedEffect` key. E1 changes how often those values change; it does not change
the rule.

⚠️ **Handoff matching compares normalized prose.** `StreamHandoff.normalize` strips tool lines,
reasoning and markers on purpose. B1 moves reasoning off `content` on the Matrix path — verify the
normalize path still sees what it expects, or the overlay will sit beside its own committed copy for
the full 20 s sync grace.

⚠️ **511 tests are the floor.** `TheaterTest`, `ToolGroupingTest`, `MessageParserTest` and
`TranscriptBuilderTest` are precisely the suites this plan disturbs. A phase is not done until they
are green **and** the ones that pinned deleted behaviour have been rewritten rather than removed.

⚠️ **`git add -A` from `Hermes-Chat/` stages the whole repo.** Unchanged, still true, still how
three APKs got into history.

⚠️ **Device-walk each phase.** Matrix and direct, on a turn that actually runs tools. Three of the
findings on this page are structural certainties; **which pair Jonny is seeing is not**, and the
walk is what settles it. Traps from the 08-21 walk still apply: `adb` BACK finishes the login
activity (use ENTER), launch with explicit `am start -n`, and `run-as chat.keryx.app` reads
shared_prefs directly on the debug build (force-stop first).

---

## 7. The first move

Before writing any code: **one device walk, both doors, one tool-running turn each, screen-recorded.**

It costs twenty minutes and it decides A1 vs A4 — whether the doubled rows are the stage against the
transcript (#1) or the inline path against the run (#2). Every other finding on this page was read
straight out of the source and does not need confirming.

## The short version

3.0 absorbed everything and retired nothing. Five tool renderers, three reasoning renderers, two
live-turn mechanisms, and what the user sees depends on which door they came through.

Make the transcript the theater — Talaria's shape, which Keryx's direct door already has and its
Matrix door buried under a second stage. Demote the theater from renderer to producer, the way the
absorption already demoted the parser. One tool renderer, one reasoning renderer, three liveness
signals instead of six. Fix the direct door's missing stream throttle and the grouping walk's cache
thrash while the code is open.

Six phases, four of them deletions, none of them features.
