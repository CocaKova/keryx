# Keryx 2.10 — the Nous pass

**Written 2026-09-05 against `main` = `8ecce6a` (2.9.4, vc84). A plan for review, not a commitment.
Nothing below is built.**

Jonny's brief: *"another overall pass on Keryx, but specifically the way Nous Research would do
it. Look at what the official Nous people committed, take those ideas and put them into Keryx.
An overall glow-up for quality of life and use — the settings at the bottom of the collapsible
sidebar, and how everything is formatted and presented. Also features for performance, QoL."*

Two steps, as asked. **Step 1** is the study: what the Nous core team actually shipped on their
own client surfaces, and the design rules they hold themselves to. **Step 2** is the translation:
what each of those becomes on a phone, in Keryx's own grammar, ordered so every phase ships on its
own.

---

## Step 1 — what Nous did

### Where the ideas come from

`origin/main` of `NousResearch/hermes-agent`, 2026-07-20 → 2026-09-05. The installed gateway is
`v2026.8.31` (0.21.0); tip is 5,337 commits past it. Authors by volume over the window: Teknium
(~8,900), Brooklyn Nicholson (~2,700, nearly all `apps/desktop`), kshitijk4poor (~1,750),
ethernet, Ben Barclay. "The way Nous would do it" therefore means two things concretely:

1. **Hermes Desktop** (`apps/desktop`, Electron) — the client the core team iterates on daily.
   Its `DESIGN.md` is the written design contract; its sidebar, settings, status bar and error
   card are the reference implementations.
2. **The web dashboard** (`web/`) and the **TUI** — same ideas, thinner.

Read: `apps/desktop/DESIGN.md`, `apps/desktop/src/app/settings/*`, `apps/desktop/src/app/chat/sidebar/*`,
`website/docs/user-guide/desktop.md`, `agent/error_surface.py`, `tui_gateway/methods_session.py`.

### The design contract (DESIGN.md, condensed to what applies to a phone)

| Nous rule | What it says |
|---|---|
| **Flat, not boxed** | No card-in-card, no divider borders inside a panel. Group with whitespace and one hairline. |
| **One primitive per concern** | One Button, one Switch, one SearchField, one SegmentedControl, one ConfirmDialog, one Loader, one ErrorState, one EmptyState. Migrate onto them, never fork. |
| **Tokens, not literals** | Every colour/stroke/shadow is a token. |
| **Style lives in the primitive** | Call sites pass a variant, not overrides. |
| **Intent before automation** | Surface actions and previews; never open a pane, move focus or navigate because a tool produced something. |
| **Immediate feedback** | Direct manipulation updates the view first; persistence reconciles after and **rolls back visibly on failure**. |
| **Chat is the home surface** | Transcript + composer stay primary; everything else complements. |
| **Pages vs overlays** | Durable destinations (Chat, Skills, Messaging, Artifacts) live in shell chrome. Short tasks (Settings, Command Center, Cron, Profiles) are overlays that return you where you were. |
| **One action, one home** | Keyboard, palette and visible affordances invoke the *same* action and state. |
| **Tooltips only when hover teaches something new** | Never on menu triggers or close buttons. |
| **Rows** | `ListRow` = label / description / trailing control. Flat, flush-left, no per-row indentation, no dividers unless the list genuinely needs them. |
| **Choice controls** | `SegmentedControl` for small mutually-exclusive sets (colour mode, tool view). Replaces radio piles and pill rows. |
| **Loading/empty/error** | Never the literal "Loading…". One `ErrorState` look everywhere. |
| **Confirmation** | One `ConfirmDialog`, opens focused on Confirm, owns pending → done → close and the inline error. |

Keryx already agrees with most of this in spirit (`KeryxDesign.kt`, the attention budget, "the
tap is the consent"). Where it disagrees is *settings*: `SettingsDialog.kt` is card-in-card on a
dusk sky, section pages are seven dense cards, and there is no search.

### Sidebar (desktop `chat/sidebar/*`) — what they built

- **Date groups** — Today / Yesterday / Last week / Older, each **collapsible**, collapse state
  persisted; a hand-picked order applies *within* a group, never across.
- **Filter menu** — status (needs-input · running · idle · draft), archived, profile scope, PR
  state; when filters narrow the list, the pinned set and the flat list both honour them.
- **Unread count on the sidebar toggle** — the hamburger carries the count of finished sessions
  you haven't looked at.
- **Archive** (`session.set_hidden`) — leaves the default list, stays resumable; an "Archived
  chats" page in Settings lists them.
- **Cron jobs section** in the sidebar; **Projects → repo → worktree lane** grouping.
- **Session row actions** — copy id, rename, move to project, appearance (colour), mark unread,
  archive, delete; **search sessions by id**.
- **Virtualised list** past a threshold; **load-more row** for older pages.
- **Footer** (dashboard `SidebarFooter`): one thin row — gateway version left, org mark right.
  **Status strip** (`SidebarStatusStrip`) above it.
- **Command palette (⌘K / ⌘P)** — every page, every settings section, every session by title or
  id, model, theme, colour mode, terminal, restart gateway, update: one field, everything.

### Settings (desktop `settings/*`) — what they built

- Nav: Appearance · Notifications · Billing · Providers (Accounts / Keys / Custom endpoints /
  Local models) · Gateway · Keyboard · Tools & Keys · Plugins · Archived chats · About — plus the
  config-backed pages (Model, Workspace, Safety, Memory & Context, Voice, Chat, Advanced).
- **Settings search** — a schema-driven catalog of every field, credential, plugin and appearance
  control; typing in the hub (or ⌘K) jumps to the row and **highlights it** (`use-deep-link-highlight`).
- **"Applies to" scope chip** — with ≥2 profiles, config-backed pages get a chip row selecting
  which profile the edits target; resets on profile switch so edits can't silently land elsewhere.
- **Appearance** — one theme list with a mode toggle inside it; re-seed any theme's accent from
  one colour; **Message Bubble transparency slider**; intro-splash toggle; UI scale; tool view;
  **Reopen last chat on launch** (`display.resume_last_session`).
- Voice provider fields read the profile's STT/TTS config; **client-direct voice** uses the
  active profile's keys straight from the client, no audio relay.

### Chat surface — what they built

- **Status bar**: context-usage meter → tap for a **Context Usage** breakdown by category
  (`session.context_breakdown`: system prompt, tools, skills, memory, MCP, conversation); **cache
  hit rate** and **tokens/sec** (off by default, right-click to show); per-field show/hide; a
  **per-session YOLO toggle**.
- **Failed turns name the failing layer** (`agent/error_surface.py`, present in the installed
  0.21.0): provider · endpoint · streaming · auth · billing · gateway · runtime · disk, with
  `retryable`. The card offers **Retry** (hidden when deterministic), **Switch provider**,
  **Open logs**, **Send diagnostics**, **Copy error details**. Older backends still get the
  generic card with Retry / logs / copy.
- **Conversation timeline rail** — one marker per prompt; open it, tap one, jump there.
- **Find in page** (⌘F) over the rendered transcript.
- **Composer history** (↑ in an empty composer) and **queue editing** — Stop while turns are
  queued pauses the queue and expands it above the composer; send, edit, delete entries.
- **Rotating task-oriented composer placeholder**; **⌘L focuses the composer from anywhere**.
- **Model picker**: type-to-fuzzy-filter; catalogs refresh every 20 min and the gateway keeps
  them warm; keyless providers count as authenticated.
- **Download button on preview file cards**; markdown table column widths persist and drag to resize.
- **In-app tips** (opt-out, paced in hours) and **guided tours** with a themed spotlight.
- **Needs-attention badge** for background bot failures; **Stop** for a running group-chat round.
- `session.undo` (take back the last turn), `session.branch`, `rollback.*`.

### Performance — what they built

- **Durable transcript-tail cache** — a session's tail is cached so a wake **paints at ~0 ms**
  before the runtime boots.
- **Idle renderers stop burning CPU on infinite CSS animations.**
- Streaming-status invalidation scoped **below the message root**; settled-preview derivation
  moved off the root; React Compiler on.
- Cron and delegate-child transcripts kept **out of the trigram FTS index**; 39 pure-read DB
  methods routed off the writer lock.
- Tool-search core-tool deferral: desktop schemas 13.4K → 6.9K (−49%).

### Working method — how they ship

- One behaviour per commit; the message says *why* and names the invariant. Every fix lands with
  the test that would have caught it ("invariant tests", not incident tests —
  [[feedback_no_incident_hardcoding]] already says this).
- A design contract kept **with** the code: change a primitive, change its entry in the same commit.
- A whole-codebase simplification pass with **zero behaviour change** (−34% LOC, every god file
  decomposed) done as its own commit, separate from features.

---

## Step 2 — what it becomes in Keryx

### Ground rules for this pass

1. **Keryx keeps its own grammar.** Doors, the dusk sky, the attention budget, "tap is consent",
   the herald's lexicon. We borrow Nous's *structure and discipline*, not its skin.
2. **Direct door first, Matrix never broken.** Every gateway-backed feature gates on a capability
   or a probe and degrades to today's behaviour when the RPC is absent. The Matrix door gets the
   drawer and settings work for free; it does not get the gateway features.
3. **Installed gateway is the floor.** Everything gateway-side below exists in `v2026.8.31`
   (checked: `error_surface`, `session.context_breakdown`, `session.usage`, `session.set_hidden`,
   `session.undo`). No new SILAS_EXT payloads, no reapply entries.
4. **Every phase ships on its own** through `tools/ship.sh` (GREEN or it doesn't go), each on
   `main`, per [[feedback_slow_version_cadence]]: the whole pass is one minor, **2.10.0**; phases
   are commits, not releases.
5. **Measure before optimising.** Phase D starts with numbers, or it doesn't start.

⚠️ **Before the first commit:** the tree carries the 09-05 call-polish change set (14 files,
+1103/−230, `PcmPlayer.kt`, `PcmUpsampler.kt` + test, edits to `ChatScreen.kt`, `HermesApp.kt`,
`NavigationDrawer.kt`, `ChatViewModel.kt`). Phase A and C touch the same files. It has to land
(it is on the phone already per memory) or be stashed first — never interleaved.

### Phase A — the drawer (the thing Jonny pointed at)

**Today.** A 300 dp drawer: header (emblem, wordmark, pet, user id, theme, +), the "Jump to…"
field (title filter + gateway deep search), the room list (Invites / Pinned deck / list / "In
transcripts"), then a divider and a **FlowRow of up to nine doors** at four per row — three rows
on the direct door — with Settings last.

**The problem, in Nous terms.** Two kinds of thing share one grid: *places you read daily*
(Missions, Runs, Bots, Projects, Shipyard, Archive) and *the machine* (Gateway, Workshop,
Settings). "Pages vs overlays." The footer is a third of the drawer and grows a row per feature.

**A1. Footer → a status strip + settings.** Replace the bottom row(s) with one thin row, the
dashboard `SidebarFooter` + `SidebarStatusStrip` collapsed into one:

```
┌──────────────────────────────────┐
│ ◉ ⚡Keryx  🐾            ☾  ＋    │  header, unchanged
│ [🔍 Jump to…               ]     │
│ PINNED                           │
│  ▸ Point video       2m          │
│ TODAY                        ▾   │
│  ▸ Sy voice call     14m  ●3     │
│ YESTERDAY                    ▸   │  collapsed, count on the chevron
│ LAST WEEK                    ▾   │
│  ▸ …                             │
│──────────────────────────────────│
│ 📋 Missions  ⏱ Runs ●2  🤖 Bots  │  places, one row, badged
│ 📁 Projects  ⎇ Shipyard 🗄 Arch. │  (only the doors the gateway serves)
│──────────────────────────────────│
│ ● qwen38-27b · hermes 0.21.0  ⚙  │  status strip: link dot · model · version · Settings
└──────────────────────────────────┘
```

- The strip's left side **is the Gateway door** (tap → Gateway place); the dot is the same
  link-health dot the top bar carries (one grammar). Workshop moves into the Gateway place's
  tabs where its knobs already live conceptually, and stays reachable from the palette (A3).
- Places keep the `DrawerDoor` tile, badges, and the "next door costs a list entry" property.
- Version comes from `hub.health`; model from `reasoningCaps.model` — both already hot.

**A2. Date groups, collapsible.** Today / Yesterday / This week / Older section headers over the
list (after Pinned), each collapsible with the chevron; collapse state in prefs; a collapsed
group shows its count and unread on the header. `RoomProfile.timestamp` already exists; grouping
is a pure function → `core` + unit test. Pinned stays the deck it is.

**A3. The palette: "Jump to…" learns the rest.** The field already matches titles and runs deep
search. Add rows for: **doors** ("Runs", "Shipyard"), **settings rows** ("bubble", "haptics",
"push") that open Settings on that row, highlighted (Phase B), **slash commands** from
`commands.catalog` (already fetched for the composer palette), **session ids**. Sections labelled
"Places · Settings · Commands · Sessions · In transcripts". One field, everything — Nous's ⌘K,
without the keyboard.

**A4. Filter chips** under the field, direct door: **Needs you** (pending approval / clarify /
`⟦keryx:ask⟧` outstanding) · **Running** · **Unread** · **Archived**. Chips narrow the deck and
the list alike (the desktop invariant). "Needs you" is the herald's-desk idea from the roadmap's
2.7 section, delivered as a filter instead of a screen.

**A5. Archive.** Row menu gains **Archive** (`session.set_hidden`); an archived row is
resumable from the Archived chip or from Settings → Sessions → Archived. Row menu also gains
**Copy session id**. (Matrix: Archive = leave-with-undo? No — Matrix keeps today's menu.)

**A6. Unread on the hamburger.** The chat top bar's drawer glyph carries the count of rooms with
unread finished turns, the desktop's "unread count on the sidebar toggle". The count already
exists per row.

Effort: A1 + A2 + A6 small; A3 medium (a catalog type + three sources); A4 + A5 small-medium.
Tests: grouping, palette ranking, filter predicates in `core`.

### Phase B — Settings (flat, searchable, restructured)

**Today.** Hub of nine rows → a section page of one or more `SettingsCard`s on the dusk sky,
each card a rounded box holding switch rows, text fields, chips and paragraphs of caption.

**B1. Flat, not boxed.** Retire `SettingsCard` as a visual. A section page is: section header
(title + one-line description) → `ListRow`s (label / caption / trailing control) separated by
whitespace, one hairline between *groups*, none between rows. The sky stays; the boxes go. Same
`SettingsSwitchRow` primitive, restyled once. Text fields become rows whose trailing control is
the value; tap → inline edit. Captions ≤ 1 line; anything longer moves into a `ⓘ` sheet.

**B2. Search.** A `SearchField` at the top of the hub (the Knobs tab already has "Find a
setting…" with the exact filter law: label · description · key). Typing filters every row across
every section into one list, grouped by section; tapping a hit opens the section **scrolled to
the row, highlighted for a beat** (`use-deep-link-highlight`). The same catalog feeds A3.

**B3. Restructure** (the desktop nav, in Keryx's words):

| Now | Proposed | Why |
|---|---|---|
| Account, Transport | **Account** (who + which door) | unchanged |
| Agent (Brain readout, Presence) | **Agent** — Brain readout, *approval mode* (`approvals.mode`, direct door), telemetry, mission alerts, **Reopen last chat on launch** | the desktop puts session behaviour here |
| Companion | **Companion** | unchanged |
| Connection · Hermes Link | **Gateway** (direct door) / **Connection** (Matrix) | on the direct door they are the same thing; two rows for one wire confuses |
| Voice | **Voice** + **"Use the gateway's voice settings"** (reads the profile's stt/tts config via `config.get` and fills the fields; Breeze stays direct-streamed, RTF≈1 needs it) | desktop reads profile voice keys |
| Appearance (bubbles, size, accents) | **Appearance** — theme mode `SegmentedControl` (System / Light / Dark, the header toggle stays), bubble style, **bubble opacity slider**, text size, accents, loading animation, haptics, motion | desktop keeps all "how it looks and feels" in one place; Interface dissolves into it |
| Interface | *(merged into Appearance)* | |
| Privacy & Security (+Senses) | **Privacy & Security** | unchanged |
| Diagnostics | **About** — versions (app, gateway), crash log share/clear, **Copy diagnostics** (versions + door + last error, redacted) | the desktop's About + "copy error details" |
| — | **Sessions** — Archived chats (from A5), prune (existing `SessionPruneDialog`) | new home for session hygiene |

**B4. Immediate feedback, honest rollback.** Gateway-backed switches (telemetry via
config, approval mode, mission alerts) flip instantly, reconcile, and **snap back with a one-line
reason** on failure — no toast that says "saved" before it is.

**B5. One `ConfirmDialog`.** Sign out, switch transport, delete session, prune, undo-turn all use
one composable with the desktop's beat (pending → done → close, inline error).

Effort: B1 + B3 medium (mostly moving code, `SettingsDialog.kt` is 1,269 lines — decompose into
one file per section while at it, the Nous god-file rule); B2 small on top of A3; B4/B5 small.

### Phase C — chat quality of life

Ranked by value on a phone; each independent.

**C1. Failed turns name the failing layer.** The gateway already sends `error_surface`
`{layer, code, retryable}` on a failed turn; `DirectTransport` currently renders `status=="error"`
as text. Parse the descriptor into an **error card**: title from the layer ("The provider
refused" / "The stream dropped" / "The gateway couldn't start the agent"), the message, and
actions: **Retry** (hidden when `retryable=false`), **Gateway log** (the existing
`GatewayLogViewer`), **Copy details**. Old-gateway fallback: generic card with Retry + Copy.
Small; high value — this is the "which layer broke" question from the handoff.

**C2. Context breakdown.** Tap the context ring → a sheet from `session.context_breakdown`:
categories as a stacked bar + list, `context_used / context_max`, model. Plus a **cost line**
from `session.usage` when the gateway reports it. Small.

**C3. Jump to a prompt.** Long chats: a "timeline" sheet (from the top-bar overflow, or
long-press the jump-to-now chip) listing the user prompts with relative time; tap → scroll to
that turn. The desktop's timeline rail. Small-medium (turn index exists in `TranscriptBuilder`).

**C4. Find in this chat.** A find bar over the rendered transcript (the Archive already searches
history, but across rooms and into a context window). Match count, next/prev, highlight. Medium.

**C5. Queue editing.** `busy_input = queue` means a message sent while the agent works waits.
Show queued messages as a strip above the composer with edit / delete; Stop pauses the queue.
Medium; needs the gateway to expose the queue or Keryx to hold it locally (check first).

**C6. Take back the last turn.** Message long-press → **Undo this turn** (`session.undo`), with
confirm; refused while a turn runs (the gateway says so). Small.

**C7. Composer recall.** Long-press the send button → last five prompts. Small.

**C8. Needs-attention on Bots.** The Bots door badge distinguishes *news* from *failed*
(desktop's needs-attention). Small.

**C9. Per-session approval mode** chip beside the reasoning menu (`approvals.mode`), direct door,
with the same warning copy the desktop uses. Small, security-sensitive — confirm on YOLO.

Skipped on purpose: markdown column drag (no mouse), tips/tours (attention budget says no
ornament that isn't asked for), rotating placeholder (cute, not useful), keyboard shortcuts
(hardware keyboards are rare here).

### Phase D — performance, measured

Numbers first: `adb shell dumpsys gfxinfo chat.keryx.app` (jank %, p95 frame), cold-start via
`am start -W`, room-open-to-first-paint via a debug timestamp, drawer open frame cost, and RSS.
Record in the plan doc, then:

**D1. Transcript-tail cache.** Persist the last page of each session's transcript on disk
(`TranscriptPages` already pages); opening a room paints the cached tail **before** the fetch,
then reconciles — the desktop's "paint at ~0 ms". Medium.

**D2. Offscreen ornament audit.** The drawer already gates on `drawerVisible`; verify the sky,
magic dust, cloud, pet and snake all stop when a place/sheet covers the chat, when the app is
backgrounded, and in Battery Saver. Fix what doesn't. Small, if the audit is honest.

**D3. Model catalog TTL.** `ModelPickerSheet` caches the catalog with a 20-minute TTL and
refreshes in the background on open (desktop: 20 min, gateway keeps warm). Small.

**D4. Recomposition scoping.** Streaming updates should invalidate the streaming bubble, not the
list; check with the Compose compiler metrics + Layout Inspector recomposition counts on a live
turn. Fix the top offenders only. Medium, bounded.

**D5. Archive indexer off the hot path.** Confirm FTS indexing never runs on the main thread and
skips cron/delegate transcripts (the desktop keeps those out of FTS). Small.

### Phase E — the simplification pass (last, separate, zero behaviour change)

Nous did −34% LOC as one commit with every god file decomposed. Keryx's: `ChatViewModel.kt`
2,299 · `ChatScreen.kt` 1,881 · `SettingsDialog.kt` 1,269 (B does this one) ·
`NavigationDrawer.kt` 1,235 (A does this one) · `MessageParser.kt` 1,136. Decompose the
remaining two by concern, tests unchanged, ship-gate GREEN, **no feature in the same commit**.

---

## Order and shape

| Phase | Ships as | Depends on | Size |
|---|---|---|---|
| A drawer | 4–6 commits | call-polish landed | M |
| B settings | 4–6 commits | A3 catalog | M |
| C chat QoL | one commit each | — | S each, C4/C5 M |
| D perf | measure commit + one per fix | — | S–M |
| E simplify | 2 commits | A, B done | M |

Tag **v2.10.0** after A + B + C1/C2 walk on the phone; the rest ride as patches or fold in before
the tag, Jonny's call.

## Decisions Jonny owns

1. **Footer direction** — A1 (status strip + places row) vs. keeping the nine-door grid and only
   re-ordering it.
2. **Workshop** folds into the Gateway place (A1) or keeps its door.
3. **Version** — 2.10.0 for the pass, or is this the 3.0 milestone.
4. **The uncommitted call polish** — commit as-is first (it's on the phone), or stash until walked.
5. **Phase C scope** — which of C1–C9 are wanted; C1 + C2 + C6 are the recommendation.
6. **Matrix door** gets A + B only (no gateway RPC features). Acceptable?

## Traps noted while reading

- `session.set_hidden` hides a session *and its lineage*; a compaction continuation of an archived
  session stays archived. The row must say so.
- `session.undo` refuses under a running turn; the menu item must read the session status, not
  the app's own `awaitingReply`.
- `error_surface` is attached by `_emit_terminal_turn_error`; a gateway older than 08-21 sends no
  descriptor — the card must render without one.
- `approvals.mode` reads the managed overlay; a raw `config.get` on `approvals.mode` can disagree
  with it. Use the RPC, not the knob.
- The palette catalog (A3/B2) must be **static per build for settings rows** — a row that only
  exists when a capability is present must carry its gate, or search will open an empty page.

---

## Status — 2026-09-05, late

Jonny's decisions: status strip (1); Gateway and Workshop merged, done properly (2 → hub-and-spoke);
2.10.0 (3); commit the call polish first (4, `4f8f6b2`); C1 + C2 + C6 (5); Matrix door gets A + B
only where it makes sense (6). Plus the long-cron-bubble readability bug he hit — fixed first
(`7667c39`, `readableGradient`: one ink for every stop).

**Built, on `main`, ship gate GREEN (721 tests), version 2.10.0 (vc85), NOT tagged, NOT walked:**

| Phase | Landed | Left / deferred |
|---|---|---|
| A | shelves (`2e1bb0c`), status strip + places row + Gateway hub-and-spoke with Workshop folded in (`365c5e6`), drawer-button unread badge (`4be8809`), lenses Needs you · Running · Unread · Archived, Archive/Restore/Copy id, palette with places + commands + settings + session ids (`a565950`) | — |
| B | flat groups, hub search with scroll-and-glow anchors, `SettingsCatalog` + source-reading test, Gateway page (direct) = Connection + Hermes Link, Interface → Appearance "Feel", About with Copy diagnostics, Sessions (archived + prune), Reopen-last-chat toggle, `KeryxSegmented` (`0b94251`) | B4 rollback (nothing in Settings writes to the gateway — moot); B5 one ConfirmDialog (deferred to E); bubble opacity slider **dropped on purpose** — a translucent fill over the sky defeats the contrast law just restored; `SettingsDialog.kt` decomposition (E) |
| C | C1 failure card from `error_surface` (+ generic fallback), C2 context breakdown sheet on the ring, C6 undo last exchange with confirm (`c37d6f7`) | C3 jump-to-prompt, C4 find in chat, C5 queue editing, C7 composer recall, C8 bot needs-attention, C9 approval-mode chip — not asked for; resume-snapshot `inflight.error_surface` on reconnect not parsed yet (live frame only) |
| D | D1 transcript-tail cache (`666450d`), D2 covered sky rests (`ab0ad69`), D3 catalog TTL 20 min (`579bb88`); D5 verified by reading: both indexers run on `Dispatchers.IO` | **Measurements not taken** — no phone on the wire for gfxinfo / cold-start; D4 recomposition scoping needs Layout Inspector on device |
| E | — | not started: decompose `ChatViewModel.kt` (2,4xx) / `ChatScreen.kt` / `SettingsDialog.kt` / `MessageParser.kt`, zero behaviour change |

**Walk list for Jonny (what to look at on the phone):** drawer shelves fold/unfold and persist ·
status strip words + tap → Gateway landing → each spoke and back · lenses, Archive then Archived
→ Restore · type "runs", "/comp", "haptic", a session id into Jump to… · Settings search "vibrate"
lands lit · Appearance segmented rows · a forced failure (bad model) renders the card with Retry ·
tap the ring · long-press the newest reply → Take it back · a cold open paints the last page before
the spinner would have.

⚠️ Traps found while building: `git log --grep` on `~/.hermes/hermes-agent` matches the giant
squash commit `722acd66f6` for nearly any phrase — grep subjects, not bodies. The Settings
`SettingsAnchor` glow relies on `positionInRoot` minus the scroll column's root Y captured with
`scrollState.value` — if the page ever gains a sticky header, re-measure.

### Phase D — the numbers (2026-09-05, Jonny's phone, 1440×3120 @600dpi, wireless adb :33273)

Same phone, same session, back to back; 2.9.4 (vc84) first, then 2.10.0 (vc85) installed over it.

| Measure | 2.9.4 | 2.10.0 | Read |
|---|---|---|---|
| Cold start, `am start -W` TotalTime, ×3 | 223 · 181 · 181 ms | 182 · 179 · 172 ms | same — the app was never slow to start |
| PSS after cold start + 6 s | 218 MB | 166 MB | −52 MB; one sample each, take as "not worse" |
| Idle on the chat floor, frames in 5 s | — | 124 (≈25 fps) | the sky shader, by design, on the floor |
| Idle CPU on the floor (`top`, one sample) | 21% | 31% | single samples of a 25-fps shader; noise |
| Scroll, 12 swipes: frames · janky · p50 · p90 | 1013 · 4.6% · 13 ms · 17 ms | 1030 · 5.2% · 15 ms · 17 ms | same within noise (different content under the finger) |
| Idle with a place covering the floor | — | **not measured** | the dump showed Jonny's terminal in the foreground; input injection stopped there — walk it: open Settings, the floor's sky should hold still (`dumpsys gfxinfo` frames ≈ 0 in 5 s) |

What this says: 2.10 did not regress start, scroll or memory; the two wins it claims — the covered
sky resting (D2) and the transcript painting from disk (D1) — are the two this run could not
observe without the screen, so they stay "changed, not live-verified" until the walk.
