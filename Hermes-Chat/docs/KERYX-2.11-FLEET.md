# Keryx 2.11 — the fleet: many gateways, one phone

**Written 2026-09-06 against `main` = `67850f5` + the uncommitted 2.10.0 call-pace tree (vc88).
Built the same day: 2.11.0, vc89.**

Jonny: *"this is actually a good time to implement the feature into Keryx for multiple gateways.
Did you know that Hermes can connect multiple gateways now in the desktop app?"*

It does. `website/docs/user-guide/multi-connection-desktop.md` (installed 0.21.0 tree) describes
the Desktop's **Settings → Gateways** registry: a named list of Hermes backends — local, remote,
SSH, Cloud — with one **Primary**, a sidebar gateway selector once there are several, profiles
discovered per gateway, and everything in the Sessions workspace scoped to the active
`(gateway, profile)`. This document is the phone's version of that page.

---

## 1. What the Desktop does, and which parts a phone keeps

| Desktop rule | Keryx 2.11 |
|---|---|
| Registry of named connections (local / remote / SSH / cloud) | **Remote only.** A phone hosts no runtime (no *local*), opens no tunnels (no *SSH*), and Hermes Cloud discovery is a portal flow Keryx has no seat in. A gateway is a `hermes dashboard` URL and a credential — exactly the direct door, several times. |
| Every connection needs a unique device name, case-insensitive, ≤ 64 chars | Same. `Fleet.add` / `Fleet.rename` refuse the exact violation with the Desktop's wording. |
| Remote entries deduplicated on the normalised URL | Same. Signing in to a known URL is a re-login of its row (renamed if a name was typed), never a second row. |
| One connection is Primary; *Make primary* does not switch the workspace | Same, verbatim. |
| Removing a connection tears down its backends; the instance is untouched | Same: credentials, read-marks, hub cache, transcript cache and archive index leave the phone. The **active** gateway cannot be removed — switch first. |
| *At startup, return to Sessions on the last-used gateway* — **off** by default (opens on Primary) | Same toggle, same words, **on** by default. A phone's process is killed behind your back all day; landing on Primary after every one of those would read as the app forgetting where you were. A relaunch the app did to itself (a switch, an added gateway) always lands on the chosen row whatever the toggle says. |
| Test probes HTTP **and** WebSocket | Test probes REST (`/api/status`) then an authed call with **that** gateway's credential. It says "Reachable · hermes 0.21.0", "Reachable — not signed in yet", or why not. The WS leg is not probed; the verdict says what it checked. |
| Sidebar: one named gateway selector once there are several; none with one | Same: `FleetSelector` sits above the drawer's status strip only when `fleet.hasChoice`. |
| Switching is live; each `(gateway, profile)` has its own pooled backend | **A switch relaunches the process.** See §3. |
| Bot Mode may show the union roster across gateways, handles `@name-device` | **Not built.** The roster is the active gateway's. A union roster needs a second live transport (see §3); it is the natural next step once the relaunch switch has been walked. |
| Update all instances | Not built — Keryx has no update pipeline for a gateway. |

## 2. What changed

**`core/model/Fleet.kt`** — the pure half. `GatewayEntry(id, name, url)`; `Fleet(gateways,
primaryId, activeId, resumeLastGateway)` with `add / rename / remove / setPrimary / setActive /
bootTarget`, the URL normaliser, the host-derived default name (`spark.lan`, `spark.lan 2`), and a
JSON shape that heals dangling pointers. `FleetTest` — 10 tests.

**`SettingsRepository` / `SettingsRepositoryImpl`** — the storage half, and the whole trick:

- The direct door's seven credential keys (`direct_api_key`, `direct_auth_mode`, the native
  token pair and its expiry, `direct_logged_in`) are now read and written **under the active
  gateway's id** (`direct_api_key.<id>`). `DirectTransport`, `DirectAuth`, `GatewayRest`, the
  login flow — none of them changed; they always asked "the" credential and now get the
  active gateway's. `directGatewayUrl` is the fleet row's URL.
- The per-gateway **ledgers** are scoped the same way, on the direct door only: `last_room_id`,
  `temporary_session_ids`, the cron baseline / seen ids / pinned jobs, `bot_seen_at`,
  `pinned_bots`, `mission_events_cursor`, and Hermes Link's `gateway_url` / `gateway_api_key`.
  The hub snapshot cache is keyed per gateway too — `/api/status` is the same path on every
  gateway and a different answer. Matrix keeps the bare keys: one homeserver.
- `forGateway(id)` returns the repository **pinned** to one gateway's keys, whatever the active
  gateway is — how a non-active row is tested. `forgetGateway(id)` removes every `*.<id>` key.
- **Migration**: a 2.10 install has one gateway in bare keys. On first read the impl folds it
  into the fleet as the first (primary, active) row, named after its host, and **copies** its
  credentials and ledgers under the new id. Copies — the bare keys stay, so an older build on
  the same phone still finds its door (the Desktop leaves its legacy settings file the same
  way). Its id is remembered as `legacyGatewayId`.

**`ArchiveStore`** — one index per gateway (`keryx_archive-<id>.db`). The pre-fleet gateway keeps
`keryx_archive.db`, so an upgrade loses no saved message and no index; Matrix shares that file
exactly as before.

**`KeryxApp`** — `bootGateway()` runs before the transport is built: a switch relaunch lands on
the fleet's active row; a cold start follows `bootTarget()`. The transcript cache is one folder
per gateway. `purgeGatewayFiles(id)` is what a removal calls.

**`ChatViewModel`** — `fleet: StateFlow<Fleet>`, `switchGateway`, `renameGateway`,
`removeGateway`, `setPrimaryGateway`, `setResumeLastGateway`, `testGateway`.
`loginToGateway` grew a `name` and now registers the row **first**, makes it active so the
sign-in's credentials land under its id, and puts the fleet back exactly as it was if the
gateway never answers — a gateway that never signed in is not registered.

**UI** — `FleetSheets.kt`: `GatewayRegistry` (the Settings → Gateways card: rows with Active /
Primary pills, a menu of Switch to / Test / Rename / Make primary / Remove, "Add gateway"),
`AddGatewaySheet` (name, URL, token; gated gateways go through the system browser like the
login screen), `FleetSelector` (the drawer foot). The login screen's direct door gained a
**Name** field. Settings search knows "Gateways" and the start-up toggle.

## 3. Why a switch relaunches

Keryx builds one transport per process life; the ViewModel and its delegates (bots, cron, hub,
projects, missions, archive) hold that gateway's state, and the transcript cache and archive
are opened for it at boot. The door toggle (Matrix ↔ direct) has always relaunched for this
reason, and the relaunch path (`relaunchApp`: synchronous commit, flush barrier, exit) is
walked and device-proven. A gateway switch rides it. The cost is a one-second blink; the
alternative was teaching ~15 delegates to reset and re-scope live, on a tree that already has
nine uncommitted files from the call-pace pass. The Desktop's live switch rests on a per-agent
backend pool the phone does not have.

What a live switch would need, when it earns its keep: a `DirectTransport` per gateway (the
class is already self-contained — settings, scope, cache dir), the delegates taking their
transport from a `StateFlow` instead of a constructor value, and the archive store swapped
under `ArchiveDelegate`. The union bot roster falls out of the same work.

## 4. Traps

- ⚠️ **Credentials follow the ACTIVE id.** Anything that signs in must set the fleet's active
  row *before* the credential is written, and restore the previous row if the sign-in fails.
  `loginToGateway` does exactly that; a second sign-in path must too.
- ⚠️ The **start-up rule** must not be applied to a switch relaunch: `commitFleet(…,
  switchPending = true)` marks it, `consumeFleetSwitch()` clears it in `bootGateway()`.
- ⚠️ `SettingsCatalogTest` reads `SettingsDialog.kt`'s source for `SettingsRow.NAME` anchors:
  the registry card and the start-up switch are anchored **in that file**, not in
  `FleetSheets.kt`, on purpose.
- ⚠️ `forgetGateway` removes keys by suffix `.<id>`. Ids are ten lowercase alphanumerics, so
  `draft_<sessionId>` keys (unscoped, session ids are UUIDs) cannot collide with the suffix.
- ⚠️ **Hermes Link is per gateway, and the composer footer rides on it.** `ComposerFooter` renders
  nothing until the caps probe or a `usage` event has arrived over the link, so a gateway whose
  link 401s shows no model pill, no reasoning dial, no context ring — and the Hub says "Hermes Link
  failed". Device-caught on the Ascent (2026-09-06 21:30, gateway log: "rejected invalid API key").
  The link host now derives from the active gateway, and a row with no key inherits the fleet's
  (primary first) until its own is typed.
- ⚠️ The phone's TTS / STT server URLs are **global**, not per gateway — voice is a service,
  not a gateway. If the Ascent hosts one gateway and Spark 1 another, the voice URL is still
  whichever box runs Breeze.

## 5. Status

- Built 2026-09-06: `tools/ship.sh --release` **GREEN, 743 tests**, vc89 installed on the phone over
  adb `192.168.50.182:35061` (`dist/keryx-2.11.0-fleet.apk`).
- **NOT walked.** The walk: open Settings → Gateways (one row, migrated, named after its
  host, Active + Primary); Add gateway → a second dashboard URL; the drawer grows the selector;
  switch → relaunch → the other gateway's sessions; Test on each; Rename; Remove the non-active
  one; toggle the start-up rule and kill/relaunch.
- Next: the live switch + union bot roster (§3), once the walk says the relaunch switch feels
  right or wrong.
- **2.11.4 (vc95), 2026-09-11:** `tools/ship.sh --release` GREEN, 774 tests, installed over adb.
  A link inside `**bold**` was never tappable: the STRONG annotator in `MessageContent.kt`
  flattened the node to raw text and claimed it, so the renderer never visited the child link.
  `**https://…**` is the form agents write most. Now a bold or struck span with inline structure
  (link, code span, emphasis, autolink) is handed back to the library (`InlineStructure.kt`,
  `InlineStructureTest`). Walk: tap the bold URL in Sy's "Link test for 2.11.4" message; the
  bold code span and struck link in the same message must render, not print their markers.
- **2.11.5 (vc96), 2026-09-15:** two instruments that went quiet once the footer toggle went off.
  (1) The self-improvement review showed only with "Show telemetry" on: on the direct door it is
  a `review.summary` event (never a stored row) filed as a HERMES message, and the review has to
  be telemetry-shaped to reach the parser with chrome on — so the toggle that hides the runtime
  footer hid the agent's learning record with it. `showsTelemetryRow` (`ChatRenderItems.kt`,
  `TelemetryRowGateTest`) lets the review through under either setting. (2) The context ring
  drank only from `session.info` / `message.complete`, both turn-end events: a room opened in a
  fresh process, or after a gateway restart, sat dark until the next turn completed. `attach`
  now folds the resume ack's `info.usage` in, and when that is still dark (a cold-resumed agent
  has no prompt-token reading until its first call) asks `session.context_breakdown` once — the
  gateway's usage-anchored figure, the same payload the ring's tap sheet reads.
  `SessionMeta.seedGauge` (`ContextGaugeTest`) lights a dark gauge only; a completed turn's
  reading is never overwritten by a seed. Not a Keryx fault: the runtime footer
  (`model · 42% · ~/dir`) is a messaging-platform decoration — direct-door rows never carry one.
  Walk: with telemetry off, a turn that saves a memory shows its 💾 row; kill the app, reopen a
  session, the ring is lit before any message is sent.
- **2.11.6 (vc97), 2026-09-19:** the shade stops shouting. A running turn re-stamps its roster
  row every 5 s (`RosterOrder.TOUCH_SLACK_MS`), the notification watcher read every re-stamp as
  new activity, and each one posted a fresh HIGH-importance alert with whatever the latest row
  was — a buzz every few seconds for one turn, the same line stacked repeatedly. Now a turn is
  one thing: (1) **the run notice** — `AgentRunService`, a foreground service alive exactly
  while `DirectTransport.runActivities()` is non-empty, draws ONE silent ongoing line on its own
  LOW channel ("Agent running"): who is working, the tool that is out in the transcript's own
  vocabulary ("❯ Running npm test", via `ToolGrammar`), a chronometer, the agent's `todo` plan
  as a progress bar, a Stop action (`session.interrupt`); several runs are still one notice. It
  is also the keep-alive `anyAgentBusy` was written for and nothing ever read: the socket stays
  up so the answer lands when it is said. A foreground start the system refuses (turn begun
  elsewhere, app backgrounded) falls back to the same notice posted plainly. (2) **One alert per
  turn** — `AlertPolicy` (`RunNotice.kt`, `RunNoticeTest`): nothing alerts mid-turn, the same
  message never alerts twice (content key — a live row and its re-read carry different ids),
  and the turn's `End` event drives the alert (`TurnEvent.End` now fires AFTER the store folds
  the final message). (3) **Colour** — a message wears its agent's palette colour (same slot as
  the transcript and the sigil), the Gate wears warn, a failed turn wears bad and says so, the
  run notice is colorized in the working agent's deep tone; shade hues come from
  `KeryxStatus.shadeWarn/shadeBad` (PaperContrastTest's no-hand-painted-status rule holds).
  Walk: send a long tool-using prompt, leave the app → one coloured silent line ticking through
  tools, no buzzing; one alert when it finishes; Stop from the shade ends the turn quietly.
  (4) **Who** — no agent name is assumed anywhere: the worker and the alert's speaker are the
  label of the PROFILE that owns the session (`BotRoster.agentFor` over the install's own
  `profiles.list`, cached by `DirectTransport.agents()`; fetched once if a turn runs before the
  Bots door has), coloured by that profile's handle so a plain session and its Bot Chat are one
  voice. Roster not in yet → the notice names the room ("Working · <title>"), never a guess.
  ⚠️ Direct door only; a turn run by another client while this phone is not attached has no
  run notice (no events reach us) and alerts through the roster path, deduped.
- **2.11.9 (vc100), 2026-09-21:** the long-press bar got functional. (1) **Smart copy** — the
  bar's Copy now puts `MessageParser.extractKeryx(content).text` on the clipboard instead of the
  raw body, so pasted text equals what the eye saw: `⟦…⟧` markers gone, `⟦c1⟧` arriving as ⁽¹⁾
  (`BubblePassTest`). (2) **Two-row bar** — the 8-emoji strip over the icon strip with a hairline
  between; one wide row used to clip the tail icons (undo/delete) on narrow phones. (3) **Retry**
  beside the undo, same gate (direct door, newest agent reply, idle): `retryExchange()` captures
  the last user prompt *before* the fold, runs `session.undo`, re-sends on success — the Desktop's
  /retry, app-only, no gateway change. (4) The emoji stagger honours reduced motion (snaps, no
  eight frame-clock clients). Walk: long-press an agent reply — two-row bar; copy pastes clean
  prose; Retry on the newest reply takes the exchange back and re-answers; Battery Saver on, the
  emoji appear without the pop-in.
