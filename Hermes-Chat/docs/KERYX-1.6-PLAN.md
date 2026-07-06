# Keryx 1.6 — "The Hook on Hermes"

Feature update: hitch Keryx onto the Hermes gateway's full API surface, bringing the deep
integrations of the Hermes Agent Desktop app to the phone. Chat stays the center; 1.6 adds the
*operations* layer around it — missions (kanban), scheduled jobs, session telemetry, skills, and
system status.

## What the gateway already serves (verified live 2026-07-06)

All on the same host + `API_SERVER_KEY` Bearer auth Keryx already stores for streaming:

| Surface | Endpoints | State |
|---|---|---|
| Detailed health | `GET /health/detailed` — gateway state + per-platform connection states | ready |
| Scheduled jobs | `GET/POST /api/jobs`, `PATCH/DELETE /api/jobs/{id}`, `POST …/pause`, `…/resume`, `…/run` | ready |
| Sessions | `GET /api/sessions`, `GET …/{id}`, `GET …/{id}/messages`, `PATCH`, `DELETE`, `POST …/fork` | ready |
| Skills | `GET /v1/skills` | ready |
| Toolsets | `GET /v1/toolsets` (per-platform enable state + tool lists) | ready |
| Models / caps | `GET /v1/models`, `GET /v1/capabilities` | ready |
| Kanban board | **none** — tasks live in `~/.hermes/kanban.db` (tools + dispatcher only) | needs plugin routes |

Kanban schema (SQLite): `tasks` (id, title, body, assignee, status, priority, created_by,
timestamps, result, failure telemetry), `task_comments`, `task_events`, `task_runs`, `task_links`,
`task_attachments`, `kanban_notify_subs`.

## Feature set

### 1. Missions — the kanban board (flagship)
A drawer destination showing the agent's task board as swimlane columns
(Backlog / In&nbsp;Progress / Blocked / Done).

- Task cards: title, assignee (profile chip, same component as the 1.3.1 top-bar chip), priority,
  age, failure badge when `consecutive_failures > 0`.
- Task detail sheet: body, live status, comments thread (post a comment), result text, run history.
- Create task: title + body + assignee composer ("hand SILAS a mission from the couch").
- Watch: completed/blocked transitions surface as app notifications (poll `task_events` since-id;
  reuse the existing notification channel).

**Server work (keryx-stream plugin, same install.py pattern):**
- `GET /keryx/kanban/board` — columns with task summaries (read-only SQLite; WAL-safe read).
- `GET /keryx/kanban/task/{id}` — full task + comments + recent events.
- `POST /keryx/kanban/task` — create (INSERT mirroring `kanban_create` tool semantics).
- `POST /keryx/kanban/task/{id}/comment` — comment as `jonny@keryx`.
- `GET /keryx/kanban/events?since=<rowid>` — incremental poll for the watcher.
State *transitions* (complete/block/claim) stay agent-side on purpose — the dispatcher owns those.

### 2. Agent Hub → Gateway console
Grow the 1.3.0 sheet into a tabbed hub (still reachable from the link-health dot):

- **Status**: `/health/detailed` — per-platform connection dots (matrix/discord/…), gateway state,
  version; the existing model + reasoning rows stay.
- **Jobs**: `/api/jobs` list with next-fire countdown; pause/resume toggle; "Run now"; delete with
  confirm. Job creation v1 = name + schedule + prompt text.
- **Sessions**: `/api/sessions` — per-room cards: model, message/tool-call counts, token usage.
  Tap → session detail (`…/messages` transcript preview). This is the "what has it been doing all
  day" view.
- **Skills & Toolsets**: read-only browsers of `/v1/skills` and `/v1/toolsets` — what SILAS can do
  and which tool groups are live per platform.

### 3. Glue
- All panels degrade gracefully: 404/timeout → "gateway doesn't offer this" row, never a crash
  (same pattern as `/keryx/capabilities`).
- Battery: fetch on open + explicit pull-refresh only; the kanban watcher polls only while the
  Missions screen is resumed (lifecycle-aware), with one opt-in background check via WorkManager
  (15 min floor) for task-completion notifications.
- Cache last snapshot per panel (DataStore) so screens render instantly offline.

## Phasing

| Phase | Scope | Size | Status |
|---|---|---|---|
| A | Plugin: `/keryx/kanban/*` routes + pytest + README wire docs | server, ~1 session | ✅ shipped (v1.4.0) |
| B | App: nav shell (drawer destinations Missions / Hub tabs), API client (`HermesGatewayClient` grows typed calls + JSON models) | ~1 session | ✅ shipped (v1.4.0) |
| C | Missions board UI + task detail + create/comment | the big one | ✅ shipped (v1.4.0) |
| D | Hub tabs: Status, Jobs, Sessions, Skills/Toolsets | ~1–2 sessions | ✅ shipped (v1.5.0) — tabbed AgentHubSheet.kt, HubJson parsers + unit tests; `/v1/toolsets` has no per-platform view, Tools tab shows the api_server surface and says so |
| E | Notifications (task events), offline cache, polish, 1.6.0 release | ~1 session | next |

## Risks / notes

- **REINSTALL-FRAGILE**: new plugin routes ride the same install.py; `hermes update` still requires
  re-run (already standard practice).
- **Security**: the API key on the phone now reaches job creation and session transcripts — the
  biometric app lock (v1.1.0) is the gate; keep the key out of logs and never render it in the Hub.
- **Schema drift**: kanban.db is hermes-agent internal; pin reads to the columns above and fail
  soft on missing ones (SELECT by name, tolerate NULL).
- **Empty board**: kanban currently has 0 tasks — Missions must look intentional when empty
  (call-to-action: "Give SILAS a mission").
