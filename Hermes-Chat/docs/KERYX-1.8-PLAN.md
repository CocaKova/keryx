# Keryx 1.8 — "Closing the Loop"

Feature update: turn 1.6's read-mostly windows into round trips. Mission alerts stop polling and
go real-time over Matrix push, the Skill Forge finally opens (view *and edit* SILAS's skills from
the phone), and the freshly-landed `hermes sessions prune` gets a safe phone UI. Chat stays the
center; 1.8 makes the operations layer bidirectional.

## What the gateway already has (verified 2026-07-07)

Same host, same `API_SERVER_KEY` Bearer auth Keryx already stores:

| Surface | Mechanism | State |
|---|---|---|
| Mission push alerts | `kanban_watchers.py` notifier loop (5s) delivers terminal task events (`completed/blocked/gave_up/crashed/timed_out/status/archived/unblocked`) as a **native Matrix message** to any `(platform, chat_id)` subscribed in `kanban_notify_subs`; subs auto-removed when the task ends | ready — but **no /keryx route** to manage subs |
| Skill storage | `~/.hermes/skills/<name>/SKILL.md` (flat or `<category>/<name>/`); `tools/skill_manager_tool.py` has `_find_skill` / `_edit_skill` (frontmatter validation, atomic write, scan+rollback) / `_create_skill` | ready — no HTTP surface |
| Skill prompt cache | `agent/prompt_builder.py:clear_skills_system_prompt_cache()` busts the never-revalidating LRU; running sessions keep their prompt, **new sessions** pick up edits — no gateway restart needed | ready |
| Session pruner | `hermes_state.py:prune_sessions` / `list_prune_candidates` (new upstream: bulk-delete **ended** sessions by filters, 90-day default). HTTP endpoint exists only on the **disabled** dashboard server (`web_server.py POST /api/sessions/prune`) | needs a /keryx wrapper |

The old `hermes-plugin/keryx/` draft (`!keryx` chat-command handler) is **retired unbuilt** — HTTP
routes in keryx-stream are simpler and match everything else the app does.

## Feature set

### 1. Real-time mission alerts (flagship)
Replace "the phone finds out within 15 minutes" with "the phone buzzes when the mission ends."

- Task detail sheet: "Alert when this ends" toggle — subscribes this room to the task's terminal
  events. Bell glyph on subscribed cards.
- Create-mission dialog: "Notify me when it ends" switch (default on) — chains a subscribe after
  create.
- Delivery is a normal Matrix message into the subscribed room → existing sync → existing
  notification channel. **Zero new notification plumbing.**
- `MissionAlertsWorker` (15-min poll) stays as the coarse fallback: subs fire only terminal kinds,
  and only while the gateway notifier loop is enabled.

**Server work (keryx-stream, same install.py):** `GET /keryx/kanban/subs`,
`POST /keryx/kanban/task/{id}/subscribe`, `POST …/unsubscribe` — thin wrappers over
`kanban_db.add_notify_sub / list_notify_subs / remove_notify_sub`.

### 2. Skill Forge
The `SkillDistilledPill` (1.6) finally opens something.

- Skill detail sheet: SKILL.md rendered as markdown (same renderer as chat); Edit toggle swaps to
  a monospace editor; Save writes back through the gateway's own validation (frontmatter + security
  scan), busts the skill prompt cache, and tells you "takes effect for new sessions."
- Entry points: Skills tab rows in the Agent Hub + "Open in Skill Forge →" on distilled-skill pills
  in chat.
- Skills living outside `~/.hermes/skills` (external dirs) are view-only (`readonly` flag, 403 on
  write).

**Server work:** `GET /keryx/skills/{name}`, `PUT /keryx/skills/{name}` (+ `.bak` one-deep undo),
`POST /keryx/skills` (create).

### 3. Session pruner
The Hub's Sessions tab grows a "Prune…" flow for the new upstream bulk-delete.

- Filters (age preset chips, optional max-messages, include-archived) → **dry-run preview**
  ("N sessions match · oldest … · newest …" + sample titles) → destructive confirm that restates
  the count → biometric gate when the app lock is enabled.
- Only ended sessions are ever touched (upstream guarantee); active work is safe.

**Server work:** `POST /keryx/sessions/prune` mirroring the dashboard `SessionPrune` body
(`older_than_days` default 90, attribute filters suppress the default, `dry_run`), calling
`hermes_state` in-process.

## Phasing

| Phase | Scope | Size | Status |
|---|---|---|---|
| 0 | This plan doc | — | ✅ |
| A | Plugin: subs + skills + prune routes, pytest, README wire docs, one install/restart cycle | server, ~1 session | ✅ shipped |
| B | App: real-time mission alerts (client calls, bell toggle, create-switch, worker demoted to fallback) → **v1.7.0** | ~1 session | ✅ shipped (v1.7.0) |
| C | App: Skill Forge (sheet, editor, pill + Skills-tab entry points) | ~1 session | ✅ shipped |
| D | App: session pruner dialog + biometric gate, polish → **v1.8.0 release** | ~1 session | ✅ shipped (v1.8.0) |

## Risks / notes

- **Notifier may be dormant**: if `kanban.dispatch_in_gateway` is off, subs never fire — verify via
  gateway logs; the poll worker stays as the net.
- **Sub drift**: the watcher deletes subs itself when a task ends — the bell state must tolerate
  disappearing subs (refresh with the board).
- **Auth blast radius**: the phone key now reaches permanent session deletion and prompt-adjacent
  skill writes — biometric gate + count-restating confirm; keep the key out of logs (1.6 rule).
- **REINSTALL-FRAGILE**: unchanged — re-run `install.py` after every `hermes update`.
- **Schema drift**: `kanban_notify_subs` reads pinned to named columns, fail soft (1.6 armor).
