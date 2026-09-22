# Keryx 2.13 — "Hands"

*The phone stops being a window onto the agent and becomes a hand on it.*

Jonny (2026-09-22, after accepting 2.12.0 Tap-In): "let's do 1, 3, 4 and the artifact
viewer." Item 1 (share sheet) turned out to already exist (`ShareActivity`, since 2.x —
`docs/features/controls.md` § Share sheet), so the batch is three parts. One release for the
batch, per the cadence rule: `2.13.0`, versionCode 102.

His design question on steer: "I'm assuming that's sub agents? I could only see tool calls in
the sub agents crew card, so I wouldn't know what to steer." Correct observation, and it is a
wire rule, not a UI oversight — see Part A. The answer is a watch window.

---

## Part A — Steer, from inside Tap-In, and the crew's minds

### Gateway facts (verified 2026-09-22 in the running checkout, `~/.hermes/hermes-agent`)

| Fact | Where |
|---|---|
| `session.steer {session_id, text}` → `{"status":"queued"\|"rejected"}`; lands after the current tool batch as an `[OUT-OF-BAND USER MESSAGE]` row; never cuts the turn | `tui_gateway/methods_session.py:2122`, `agent/agent_runtime_helpers.py:3401` |
| `session.redirect {session_id, text}` → `"redirected"`; 4010 when the agent can't; queues during the agent-build window | `methods_session.py:2109-2128` |
| `session.interrupt` stops the WHOLE turn, wipes the server queue, kills owned delegations, denies pending approvals | `session_lifecycle.py:590-645` |
| Every child has its **own stored session id**, on every relayed event as `child_session_id` | `tools/delegate_tool.py:233-263`, `delegate_tool_progress.py:322` |
| `session.resume` into a child = **lazy watch window**: the gateway mirrors the child's `subagent.thinking/text/tool/complete` into native `message.start` / `reasoning.delta` / `message.delta` / `tool.start`+`tool.complete` / `message.complete` on the child's live sid | `methods_session.py:732-756`, `agent_callbacks.py:34-77` |
| `prompt.submit` into a live child → 4009 "subagent still running" (after it lands, the window upgrades to a real session) | `methods_prompt.py:539` |
| `subagent.steer {session_id: parent live sid, subagent_id, text}` → `"queued"\|"rejected"`; the in-flight tool is never cut; "queued" ≠ "delivered" (`missed_steer` on the parent if the child was past its last batch) | `methods_session.py:2145-2160` |
| `subagent.interrupt {session_id, subagent_id}` → `{found}` — stops ONE helper | `methods_subagents.py:42-63` |
| `subagent.tail {session_id, subagent_id}` → last 16 KiB of the child's live transcript | `methods_subagents.py:66-89` |
| Authority = the parent session's **live transport slot at check time** (a re-resumed phone holds it); a child spawned by a turn another door still owns answers `rejected`/4001 | `tools/delegate_tool_registry.py:107-122` |

Why the card only showed tools: keryx-stream rule 4 (`docs/features/streaming.md`) drops a
child's assistant text on the parent wire on purpose, and `subagent.thinking` is used only as
the transient activity line (`DirectTransport.kt:1061`, rationale `Theater.kt:36`). The mind
is not on the parent sid at all — it is on the child's, behind a resume.

### What already exists on the phone

- `DirectTransport.getMessages(sessionId)` = hydrate (REST, fails soft for an unflushed child)
  + `attach` (`session.resume`, lazy shape already tolerated) + follow. Handing it a
  `child_session_id` **is** the watch window; the mirrored stream lands in a normal
  `SessionStore` (reasoning → `Message.reasoning`, text → `content`, tools → `toolCalls`).
- `steerTurn` / `queueMessage` / `interruptTurn` on `ChatViewModel` (2.6.2), composer grammar
  "type to steer · hold to queue · empty = stop".
- `SubagentSessionSheet` — trail while flying, REST transcript once landed.
- Tap-In (2.12): `TapInScreen` regions, `CrewCard`, host wiring.

### Build

1. **Transport** (`DirectTransport`): `steerCrew(session, subagentId, text)`,
   `stopCrew(session, subagentId)`, `crewTail(session, subagentId)`. `subagent_id` = the
   `Delegation.key` the events already carry. `session_id` = the parent's live sid (`attach`).
2. **Crew mind** — `SubagentSessionSheet` gains a third source, between "trail" and "stored":
   while `run.running && run.sessionId.isNotBlank()` on the direct door it collects
   `transport.getMessages(run.sessionId)` and renders the child's **thinking** (tail-followed,
   the same `ReasoningDisclosure` voice), its **saying**, and its tools as theater rows; the
   trail stays as the fallback when the mirror has produced nothing yet. `subagent.tail`
   seeds an "earlier" block for a window opened mid-flight. A composer at the foot: send =
   `subagent.steer` (toast "Steered <role>" / "This helper answers to another door"),
   ■ = `subagent.interrupt` with confirm. Matrix door: read-only, as now.
3. **Tap-In composer** — a `TapInComposer` at the foot of `TapInScreen` (above instruments):
   same grammar as the chat composer, driving `steerTurn` / `queueMessage` (hold) /
   `interruptTurn` (empty). The saying region shrinks to make room; the keyboard pushes the
   space, not the transcript. Direct door only (`canInterruptTurn`); on Matrix it is hidden.
4. Tests: `TapInTest` additions for composer state; a `CrewMind` projection test (thinking /
   saying / tools from a `Message` list, seed merge, "nothing yet" fallback).

## Part B — A widget and a tile

No Glance, no `TileService` anywhere in the app yet (manifest audited); both are new.

- **Widget** (`androidx.glance:glance-appwidget`): one size class that scales. Shows the
  gateway state (live / offline), the newest session's title + last reply preview (via
  `peekLatest` — no live agent per row), "N helpers flying" when a run is active, and the
  agent-running state. Tap → the session (`EXTRA_ROOM_ID`); while a run is live, tap → Tap-In
  (`EXTRA_TAP_IN`). Refreshed by a `WorkManager` periodic job (15 min floor) **and** on demand
  from the run notice path so it never lags a live turn. Data is read through the existing
  transport, never a second socket.
- **Tile** (`android.service.quicksettings.TileService`): "Note to Sy" — opens a small
  `NoteActivity` (excludeFromRecents, dialog theme) with a one-line composer that sends to
  the pinned/last session over the same `sendMessage` path the share sheet uses. Tile state
  mirrors the gateway link. Requires `BIND_QUICK_SETTINGS_TILE`.
- Both honour the theme tokens (`KeryxStatus.shade*`), no emoji glyphs (device rule from 0.4.0).

## Part C — Artifact viewer

The agent writes HTML (mockups, reports, the Salt Creek kind). Today an `.html` path in a
reply is a `MEDIA:` file card that opens externally, or plain text.

- **Fetch**: dashboard REST `GET /api/fs/read-text?path=<abs>` (token-gated, returns `text`,
  `language`, `mimeType`) and `/api/fs/download` for bytes — any absolute path the agent
  names, no root constraint beyond the sensitive-path denylist (`web_routers/files.py`).
  `GatewayRest.readText(path)`.
- **Detect**: `MEDIA:<path>.html` lines (already split into media messages) plus a bare
  absolute `/…/*.html` path in assistant text on the direct door → `MediaKind.ARTIFACT`
  card ("Open · <name>").
- **Render**: `ArtifactSpace` route (`artifact?path=`) — a `WebView` with JavaScript on,
  `allowFileAccess=false`, no `addJavascriptInterface`, `loadDataWithBaseURL(null,…)` so
  relative fetches can't reach the phone; network allowed so CDN libraries in a mock still
  load. Action bar: save (MediaStore), share (FileProvider), open-with. Back returns to the
  chat where you were.
- Matrix door: the file must come through the room as media; the card opens the same space.

## Release

`versionCode 102`, `2.13.0`. Doc pages: `docs/features/tap-in.md` (composer + crew mind),
new `docs/features/hands.md` (widget, tile, artifact viewer), CHANGELOG top entry, README
row. Build `tools/ship.sh --release --detach` on VM 106 — never gradle on Spark 1 while the
brain is up. GREEN → push `main`, tag `v2.13.0`, GitHub pre-release with the APK.

## Left out on purpose

- Redirect (`session.redirect`) — steer + stop cover it; redirect's semantics (drop the model's
  partial answer, keep context) deserve its own UI word before it gets a button.
- Steering from the Matrix door — no authority path.
- Writable Runs (cron from the phone) — next batch; `cron.manage` only exposes add/remove/
  pause/resume, the REST `/api/cron/jobs` surface is the richer one.
