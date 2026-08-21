# Keryx 3.0 — the absorption

**Written 2026-08-21. The decision the roadmap deferred is made: Keryx absorbs Talaria. One app,
two transports, one quality bar. This is the plan for getting there.**

The roadmap's closing question was *"does Keryx stay the Matrix herald and Talaria own the direct
path, do they merge, or does one of them become the other's transport?"* — and it argued the
answer should come before The Gate, because The Gate was the first feature that would otherwise be
written twice.

It was written twice. Talaria has `ApprovalCard.kt`; Keryx doesn't. That is the last argument this
plan needs.

---

## 1. What the merge actually is

Not two apps being reconciled. **One app that was forked and drifted**, with each half growing
organs the other lacks.

```
                        Keryx        Talaria
ChatScreen.kt            2,498        2,496
ChatViewModel.kt         2,638        3,794
HermesStreamClient.kt    1,727        1,662
SettingsDialog.kt        1,162        1,051
ArchiveScreen.kt           740          718
MessageContent.kt          598          642
ArchiveStore.kt            301          301
TtsController.kt           243          243
NoiseFloor.kt               51           51
VoiceRecorder.kt            61           61
```

The last two are **byte-identical** once the package and brand names are normalised.
`TtsController.kt` differs by two lines. `ArchiveStore.kt` by sixty-eight, all schema drift.

Of 41 Keryx components and 44 Talaria ones, 23 share a name exactly and four more differ only by
the brand prefix (`KeryxDesign` / `TalariaDesign`, `KeryxMarkers` / `TalariaMarkers`, …).

The domain models were already aligned **by hand, deliberately, in both directions**:

- `ToolGrammar.kt` says *"Ported from Talaria's `ToolTheater` (itself a port of the desktop's
  TOOL_META)"*.
- `Theater.kt` says *"Deliberately the same shape Talaria reads over its WS `turnEvents`"*.
- Talaria's `Message.kt` still carries Keryx's `// mxc:// for media messages` comment on a field
  that can never hold an mxc URI, because Matrix does not exist on that side.

Talaria's `Message` is a **strict superset** of Keryx's — same fields, same order, same comments,
plus `toolCalls`, `delegations`, `reasoning`, `reasoningSeconds`. Talaria's `Delegation` and
Keryx's differ in exactly two fields (`sessionId` vs `childSessionId`; `filesRead: Int` vs
`List<String>`).

**So this is not a merge of two designs. It is the removal of an accidental fork.**

---

## 2. The architectural thesis

> **One `Message`. One `ToolCall`. One renderer. Two producers.**

Today the two halves solve the same problem in incompatible ways:

| | how a tool call reaches the screen |
|---|---|
| **Keryx** | committed Matrix text → `MessageParser` → `ChatRenderItem.ToolRun` → `ToolGroupCard` |
| **Talaria** | gateway events / REST rows → `Message.toolCalls: List<ToolCall>` → `ToolTheater` |

Keryx *has* to parse, because a Matrix room carries text and nothing else. That is a real
constraint, not a shortcoming. But it made **rendering quality a property of the transport**, which
is the thing to end.

The fix is to demote the parser from *renderer-feeder* to *producer*:

```
  Matrix transport ─→ MessageParser ─┐
                                     ├─→ Message.toolCalls ─→ ToolTheater
  Direct transport ─→ event reducer ─┘
```

`MessageParser` keeps every line of its 990 and every one of its 641 test lines. It stops deciding
how a call *looks* and starts deciding what a call *is*. After that, a turn you watched over a
socket and a turn you read back out of an E2EE room a week later render identically, because
they are the same data.

**`ArchiveStore` already proves the pattern works.** It is 301 lines on both sides and effectively
identical; the two `ArchiveIndexer`s are 329 (Matrix timeline) and 109 (REST hydration). One store,
two producers. The theater is the same shape, and so is everything else on this page.

---

## 3. Which side holds the quality bar

The instruction is that the harvest arrives at Keryx's craft standard, not as a paste. That cuts
both ways, and per domain the answer is not always Keryx. Where it isn't, saying so is the point —
otherwise the merge quietly ships the *worse* half of a feature that already exists twice.

| Domain | Bar | Why |
|---|---|---|
| **Tool card** | **Talaria** `ToolTheater` (939) | Faithful port of the desktop grammar: transparent rows, no borders at rest, **success is silent**, braille breathe spinner, category-clause summaries ("Explored 3 files, ran 5 commands"), `$` transcript, colour-gutter diff, nested args disclosure. Keryx's `ToolGroup` (964) is the older "dream-aesthetic Sandbox Card" — filled bubbles. Talaria kept its own `ToolGroup` shrunk to 296 as the legacy fallback, which is the tell: it moved on and Keryx didn't. |
| **Tool vocabulary** | **Keryx** `ToolGrammar` (278) | The refined second pass over Talaria's inline `TOOL_META`. 27 verb triples, `PATH_TOOLS`, `TARGETLESS`, a documented target-extraction rule. Extracted as an object; Talaria's is inline in the renderer. |
| **Live stage** | **Keryx** `TheaterStage` (417) | Deliberately *lesser* — scaffold voice, monospace, one line per beat, because live telemetry expires and must not out-shout the answer. Correct as-is; do not upgrade it into the card. |
| **Diff rendering** | **Keryx** `ToolDiffPanel` (150) | The 24-bit-ANSI strip and the `+n −n` count-from-pre-clip-body, covered by `TheaterTest`. Talaria hit the same bug (`inline_diff` lines are `ESC[38;2;…m+line`) and its comment records it; Keryx's is the tested implementation. |
| **Message model** | **Talaria** | Strict superset. Take it whole. |
| **Reasoning** | **Talaria** | A structured `reasoning` field plus `ReasoningDisclosure` / `ReasoningPill`. Keryx parses `💭` markers back out of text and gathers them into `ChatRenderItem.ToolRun.reasoning` — clever, and unnecessary once the field exists. |
| **Delegation** | **union** | Keryx's `sessionId` (what "open this subagent" opens) + Talaria's `filesRead/filesWritten: List<String>`. Everything else already matches. |
| **Sessions / history** | **Talaria** | REST hydration of real gateway sessions. Keryx's `Session` is vestigial — see §6. |
| **Archive index** | **Keryx** `ArchiveIndexer` (329) | E2EE forces the phone to be the only place the plaintext exists; the index is the Keryx idea and the Keryx implementation. `ArchiveStore` is shared already. |
| **Heraldry** | **Keryx** | Nothing in Talaria. Many agents, each its own light. Survives the abandoned Council. |
| **Hub architecture** | **Keryx** `HubSpace` | A panel is a **value, not an index** — one registry, one `refresh` per panel, first-visit/poll/button all call the same function. Talaria's `AgentHubSheet` (1,365) is the six-tabs-and-a-`when` shape Keryx already escaped. Every harvested space re-lands as a `HubPanel`. |
| **Kanban** | **Keryx** `MissionsScreen` (780) | vs `KanbanSpace` (680). Vertical status sections, lane-jump chips, unknown statuses keep trailing sections. Needs a bake-off, but Keryx is ahead on the phone-shape argument. |
| **Voice call** | **Talaria** `CallController` (415) | vs Keryx's 273. Talaria has endpointing, device-verified 08-18. `CallScreen` itself is a wash (420 / 427). `NoiseFloor` and `VoiceRecorder` are byte-identical; `TtsController` differs by two lines. |
| **Wake word** | **Talaria** | Six files, ~940 lines, on-device "hey hermes". Keryx has none. |
| **Cron** | **Talaria** `CronSpace` (584) | Only implementation, and its argument is right: scheduled runs are a different *kind* of thing from a chat — you don't converse with the Daily Brief, you read it. |
| **Approvals** | **Talaria** `ApprovalCard` (99) | Only implementation. **99 lines.** The Gate the roadmap sized as a movement is a hundred-line card once the frame exists. |
| **Projects / Models / Gateway status** | **Talaria** | Only implementations (506 / — / —). |
| **Design language** | **Keryx** | The attention budget, the light-mode WCAG pass, the eclipse mark, `KeryxHaptics`, `ReducedMotion`. The rule that *motion carrying information keeps moving and motion that decorates stops* is Keryx's and governs everything harvested. |
| **Push** | **Keryx** | Self-contained ntfy WebSocket, UnifiedPush auto-detected, `event_id_only`. |

---

## 4. Target shape

```
keryx/
  settings.gradle.kts          include(":app", ":core")
  core/                        KMP — commonMain / androidMain / jvmMain / iosMain
    domain/model/              Message · ToolCall · Delegation · Session · Kanban ·
                               Cron* · Pet · ModelCatalog · TodoPlan · Heraldry · …
    protocol/                  ToolGrammar · MessageParser · KeryxMarkers · MermaidParser ·
                               TurnEvent reducers · ArchiveQuery
    transport/                 interface ChatTransport   ← the seam
  app/
    transport/matrix/          Trixnity
    transport/direct/          gateway WS + REST
    presentation/              ONE ui tree
```

`:core` is **seeded from Talaria's `shared/`**, which is already KMP with `iosMain` and `jvmMain`
source sets wired and 5,486 lines in `commonMain`. Both projects are on Kotlin 2.0.0 with the same
`libs.versions.toml` conventions, and Trixnity is itself KMP — so the module boundary costs almost
nothing to erect.

### The seam

Keryx's `ChatRepository` cannot be the seam as-is. It is Matrix all the way through:
`joinRoomByAddress`, `setRoomAvatar`, `getInvites`, `react(emoji)`, `mediaBytes(eventId)`,
`login(username, password)`.

Split it, and do **not** reduce it to a lowest common denominator:

- **`ChatTransport`** — what both genuinely satisfy: the room/thread list, the message flow, send,
  reply, stream, attachments, media bytes, typing, read markers, history-around-an-event.
- **`MatrixCapabilities?`** — invites, reactions, avatars, room creation, membership, redaction.
  Non-null on Matrix, null on direct.

The UI then asks *"does this transport have reactions?"* instead of pretending every transport has
everything and failing quietly on the one that doesn't.

---

## 5. Sequence

Each phase ends somewhere shippable. Nothing here is a big-bang rewrite.

### Phase 0 — Guardrails

**Status: half done (2026-08-21).** The payload-sync script and CI have landed, plus two things
this phase did not anticipate: the gradle wrapper jar was gitignored, so a fresh clone of the
public repo could not build at all; and the version catalog declared coroutines 1.10.2 while the
app hardcoded 1.8.0, so the runtime and the tests ran different builds of the same library. Both
fixed. **Still open, and both need Jonny:** the history rewrite (force-push, moves six release
tags) and the release keystore (one-way door).

Before a long stretch of large refactors, not after.

- `tools/check-payload-sync.sh` — the "md5sum the three `keryx_stream.py` copies before any
  release" protocol is a human ritual standing where a script belongs. Encode the invariant.
  (All three are in sync at `5eff524e` as of 2026-08-21 — lock it in now, not after the next drift.)
- CI. There is no `.github/` at all, and 5,057 lines of Kotlin tests plus 1,097 of gateway pytest
  run only when someone remembers.
- A real release keystore. Six public releases have shipped debug-signed; the day a real key
  appears, every existing install needs an uninstall to take the update. That population only grows.
- `git filter-repo` the history: a 72 MB backup tarball and ~280 MB of APKs sit in a 101 MiB pack.
  ⚠️ Rewriting moves every tag SHA and there are six published releases pointing at them.

**Also: cut Talaria 0.7.10 and freeze it.** The speak leg is unverified on device and the
`MEDIA:<path>` commit is unpushed — close that loop, ship it, then Talaria is a **donor, not a
product**. Bug fixes only from that day. Everything new lands in Keryx. Trying to keep both moving
through the merge is how the merge fails.

### Phase 1 — `:core` — no behaviour change

**Status: done for the Keryx half (2026-08-21).** `:core` exists, is KMP (jvm + the iOS pair),
holds seven models and three parsers, owns 146 of the 404 tests, and rejects `import android.*`
at compile time. `:app` consumes it.

⚠️ **Deviation from the plan as written, deliberate.** This said to seed `:core` *from Talaria's
`shared/`*. It was seeded from Keryx's own pure models instead, and the Talaria union is now the
first half of Phase 3 rather than the back half of Phase 1. Three reasons, all of which held up:
Keryx's seven domain models turned out to be *already* pure (zero android/java imports between
them), so there was nothing to port; seeding from Keryx means no foreign package, naming or
identity ever enters the module and has to be walked back; and it meant the module could be
proved — built, tested, and its purity rule verified by deliberately breaking it — before any
merge risk was taken on. Talaria's models now land *into* an established module rather than
arriving as one.

- ~~Create `:core` from Talaria's `shared/`~~ → created from Keryx's own; repackage
  `cc.gardenofnull.talaria` → `chat.keryx.core` when the Talaria half lands.
- Union the models: `Message` (Talaria's, whole), `ToolCall`, `Delegation` (§3), `Session`
  (Talaria's meaning — see §6 first).
- Move `ToolGrammar` in as the single vocabulary; delete Talaria's inline `TOOL_META`.
- Move the pure parsers in: `MessageParser`, `KeryxMarkers`, `MermaidParser`, `Heraldry`,
  `AgentDelivery`, `ArchiveQuery`, the `TurnEvent` reducers.
- Move their tests with them. This is where two test suites become one.

**Checkpoint:** Keryx builds on `:core` and behaves identically. Nothing user-visible.

### Phase 2 — the seam, and the god objects

- **Kill Keryx's vestigial `Session`** (§6) and rename `sessionId` → `roomId` through the Matrix
  path. This must happen before anything imports Talaria's `Session`.
- `ChatRepository` → `ChatTransport` + `MatrixCapabilities`.
- `ChatRepositoryImpl` → `MatrixTransport`.
- **Decompose `ChatViewModel` here** — not before, not after. It is 2,638 lines, 117 public
  functions and 74 `MutableStateFlow`s, already hand-sectioned into 23 labelled regions (Pet picker,
  Missions, Hub, Controls, Session pruner, Skill Forge, Skill trash, Raw config, Run console, Call,
  Archive…). Each region is a feature-scoped delegate waiting to be lifted. It is forced in this
  phase anyway, because the ViewModel touches `ChatRepository` in over a hundred places.

**Checkpoint:** identical behaviour, on an interface, with a ViewModel under 800 lines.

### Phase 3 — one theater, two producers

The phase that satisfies the quality directive literally.

- `Message` gains `toolCalls`, `delegations`, `reasoning`, `reasoningSeconds`.
- Port `ToolTheater` in as the **one** tool renderer, fed from `Message.toolCalls`.
- Rewire `MessageParser` to emit `List<ToolCall>` instead of `ChatRenderItem.ToolRun`.
- Fold `ToolBeat` into `ToolCall` (`status = EXECUTING`); `TheaterStage` keeps its distinct
  expiring-telemetry voice and its own model of the turn.
- Keryx's `ToolDiffPanel` becomes `ToolTheater`'s diff gutter — the ANSI strip and the counting are
  the tested ones.
- Delete both `ToolGroup.kt`s (964 + 296).

**Checkpoint:** a Matrix turn and a direct turn render identically, and the reasoning disclosure
works on Matrix for the first time.

### Phase 4 — the direct transport — **3.0**

- `DirectTransport : ChatTransport`, ported from `GatewayChatRepository` (2,145) and Talaria's
  `HermesStreamClient` (1,662).
- The login screen gains a second door: **a Matrix account, or a gateway URL and an API key.**
- On the direct path, gateway sessions and projects present as the room list.

**Checkpoint:** Keryx runs with no homeserver. This is the version that has a reason to exist for
someone who isn't Jonny — the current install path reads *stand up a Matrix homeserver, run a
hermes-agent gateway, then patch a 3,385-line payload into it*, and the public repo has 0 stars.

### Phase 5 — the harvest

Cron, Projects, Models, Gateway status, Approvals, the flight-plan strip, the wake word, `MEDIA:`
hand-offs — each re-landing as a `HubPanel` or a Keryx space, under the attention budget, wearing
the herald hues, with `KeryxHaptics` and `ReducedMotion` honoured. **Not pasted.**

Going the other way for free: Archive, Heraldry, Missions and the diff renderer become available on
the direct path.

### Phase 6

Archive the Talaria repo. Resume the roadmap — Forge, Presence, Ledger — on one codebase.

---

## 6. Traps

⚠️⚠️ **"Session" means two different things.** — *resolved 2026-08-21; kept here because the
reasoning is what stops it coming back.* Keryx's `Session(id, roomId, title, timestamp)` is
**vestigial**: every construction in the codebase is `Session(room.id, room.id, room.name, 0L)`,
`getSessions(roomId)` returns a one-element list containing the room itself, and `sessionId` is
always a Matrix room id (`ChatRepositoryImpl:141` — `val roomId = RoomId(sessionId)`). It is dead
weight from the Hermes-Chat predecessor. Talaria's `Session` is a *real gateway session*. Delete
Keryx's and free the word **before** `:core` lands, or the merged app will carry two meanings for
one noun in 29,656 lines.

⚠️ **The theater renderer flows Talaria → Keryx, not the reverse.** The instinct is that Keryx
holds every bar. On the tool *card* it doesn't; it is a generation behind. Porting Keryx's
`ToolGroup` forward would ship the worse half of a feature that already exists twice.

⚠️ **Talaria can say "in parallel"; Keryx can only say "in one turn".** Talaria's gateway reports
observed overlap (`ToolCall.concurrent`); Keryx's side-channel infers grouping from announcement
order (`ToolBeat.concurrent`, and its comment is explicit that this is about *announcement, not
execution*). The unified `ToolCall` must keep both claims distinguishable — a shared `batchId` is
"one dispatch", never proof of concurrency. Collapsing them loses a true distinction.

⚠️ **`keryx_stream.py` doubles in Phase 4-5.** It is already 3,385 lines as a reinstall-fragile
copy-on-write payload behind 8 reapply entries, and it drifted twice in two days — 315 lines on the
19th, 413 more on the 20th, **both times the repo was the copy that was behind**, which means work
is happening on the live file. Split by subsystem before growing it, and give any new surface its
own payload. The real de-anchor is upstream PR #90367 (chat identity in the stream hooks).

⚠️ **The repo is still named after the predecessor.** `rootProject.name = "HermesChat"` and the app
lives in `Hermes-Chat/`. Rename during Phase 1 while the whole tree is moving anyway, not later.

⚠️ **`git add -A` from `Hermes-Chat/` stages the whole repo.** Three test APKs got in that way and
needed a filter-branch. During a merge of this size, that will happen again unless the history
rewrite in Phase 0 lands first.

⚠️ **The Council is dead** (abandoned 2026-08-19). Heraldry survives it and is worth every line;
the council mechanism is not to be rebuilt.

⚠️ **Approvals are still not a feature Jonny wants.** `ApprovalCard` arrives as merge dowry at 99
lines because it already exists — not because The Gate came back. Don't build a movement around it.

---

## 7. What this buys

- **Stop writing everything twice.** Roughly half of ~75,000 lines of Kotlin is duplicated work.
- **A homeserver stops being mandatory** — the single largest barrier between Keryx and anyone
  else using it.
- **Every feature reaches every transport.** Archive on direct. Cron on Matrix. Theater on both,
  at one quality.
- **The desktop port unblocks.** It was parked on `no linux-aarch64 libolm`; the direct path needs
  no olm at all.
- **The phone as a tool host stops being blocked.** It needed a durable phone→gateway socket and
  the SSE side-channel is per-turn. Talaria's WS `turnEvents` channel is durable. After Phase 4 it
  is a consent-model problem, not a plumbing one — and that is the most differentiating idea in
  the stack.

---

## The short version

Ship 0.7.10 and freeze Talaria. Put the guardrails in. Build `:core` from Talaria's `shared/`. Kill
the vestigial `Session`, split the transport seam, break up the ViewModel. Then one theater with two
producers — the phase that makes quality a property of the app instead of the transport. Then the
direct transport, and that is 3.0. Then harvest, at Keryx's standard, into Keryx's shapes.

Six phases, five of them shippable on their own, and only one of them is new features.
