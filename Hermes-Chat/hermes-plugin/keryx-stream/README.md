# Keryx Stream — dual-tier live streaming for Keryx ⚡

The server half of Keryx's streaming architecture. Standard Matrix `m.replace` edit-streaming
bloats the homeserver database and hammers sync; this patch gives Keryx a **side-channel** instead.

## Architecture

**Tier 1 — SSE side-channel (primary).** Keryx opens a transient
`GET /keryx/stream?platform=matrix&chat_id=<room>` subscription on the gateway's existing API
server (`:8642`, Bearer-authed with `API_SERVER_KEY`) right before sending a command. While the
subscriber is attached:

- every assistant token delta is mirrored live to the app (`event: delta`);
- homeserver protocol edits are **suppressed** — the room gets exactly one final committed message;
- `event: stop` ends the turn; the app holds its overlay until the final Matrix event syncs, then
  swaps seamlessly.

**Tier 2 — smart-throttled protocol edits (fallback, opt-in).** No subscriber attached (app
closed, side-channel unreachable): by default Matrix simply receives the single final message.
Set `KERYX_STREAM_FALLBACK_EDITS=1` in the gateway's environment to instead fall back to native
`m.replace` edit-streaming throttled by the standard streaming config (`edit_interval: 1.2` /
`buffer_threshold: 60` ≈ 1 edit per 1.2 s / 60 chars). Left off by default because clients that
don't collapse `m.replace` fallbacks render the edit stream as duplicate bubbles.

The switch is evaluated live per flush, so a subscriber attaching mid-turn immediately silences
protocol edits and vice versa.

## Install (REINSTALL-FRAGILE)

```bash
python3 install.py            # idempotent; verifies compiles; anchored patches
systemctl --user restart hermes-gateway.service
```

`hermes update` wipes the patches — **re-run `install.py` afterwards.** If an anchor is not found
the script says so and touches nothing else.

Patched files in `~/.hermes/hermes-agent/` — all insertions are anchored and wrapped so a missing
hook fails soft:
- `gateway/keryx_stream.py` (new — hub, SSE handler, delta coalescing)
- `gateway/stream_consumer.py` (delta/segment/stop mirror + edit-suppression tier switch)
- `gateway/platforms/api_server.py` (`/keryx/stream` + `/keryx/capabilities` routes)

Optional niceties (reasoning + steering; harmless if your brain doesn't use them):
- `agent/agent_init.py` (map `/reasoning` onto a local brain's `enable_thinking` switch)
- `gateway/run.py` (fold the reasoning block into the streamed final message)
- `agent/chat_completion_helpers.py` (let `/steer` break a live text stream at the steer point)

Plus one config change (`~/.hermes/config.yaml`):

```yaml
streaming:
  enabled: true
  transport: auto
  edit_interval: 1.2
  buffer_threshold: 60
```

## Wire protocol

```
event: delta     data: {"text": "…incremental tokens (may contain <think> blocks — client filters)…"}
event: reasoning data: {"text": "…live reasoning/thinking deltas, ahead of the answer tokens…"}
event: segment   data: {}                       # text → tool → text boundary
event: tool      data: {"text": "{…JSON…}"}     # tool + subagent lifecycle (see below)
event: usage     data: {"text": "{…JSON…}"}     # {"used","max","model"} — context occupancy
event: stop      data: {}                       # turn complete; channel closes
event: ping      data: {}                       # 20 s keepalive
```

`usage` reports the turn's context occupancy from `last_prompt_tokens` — the final API call's
prompt size, which IS the model's current occupancy, unlike the cumulative `session_*` counters.
It must be published **before** `stop`: the subscription is transient and the reader hangs up at
the stop frame, so anything after it is never delivered.

`tool` carries the whole tool and subagent vocabulary as JSON inside the same `{"text": …}`
envelope, so what the agent is *doing* is visible mid-turn instead of a spinner:

```
{"phase":"start", "name":"read_file", "preview":"SOUL.md"}
{"phase":"end",   "name":"read_file", "ok":true,  "ms":113}
{"phase":"end",   "name":"read_file", "ok":false, "ms":85, "result":"File not found: …"}
{"phase":"diff",  "name":"patch", "body":"…ANSI-coloured unified diff…"}
{"phase":"sub",   "kind":"start|tool|complete|thinking|progress|spawn_requested",
                  "child":"sa-0-cf0971a4", "goal":…, "model":…, "task_index":0, "task_count":1,
                  "tool_count":2, "status":"completed", "duration_seconds":38.15,
                  "input_tokens":…, "output_tokens":…, "api_calls":…, "files_written_n":…,
                  "summary":…, "child_session_id":…}
```

Four rules the frames follow, each load-bearing for a client:

- **`result` rides only on a failure.** A successful call's output is the answer's raw material and
  arrives in the committed message moments later, rendered properly. A failure is the one case
  where the mid-turn glimpse is the whole point.
- **`tool.completed` carries no call id**, so starts and ends correlate by *order*. Models batch
  calls (two `read_file`s can both open before either closes), and the executor emits completions
  in the order it emitted starts — so an end closes the **oldest** open row. FIFO, not a stack;
  the tool name is a tiebreak, not a key.
- **File lists are sent as counts** (`files_read_n` / `files_written_n`), not paths. A client
  renders "2 written" and a 40-path array per completion is a lot of socket for a number.
- **`subagent.text` is dropped.** A watch window can drink from a child's assistant stream; a phone
  on a transient socket cannot. The `tool` / `thinking` / `progress` kinds carry the activity line
  instead.

Parallelism is *observed*, not announced: a call still open when another opens marks the overlap,
but that is an observation about when calls were **announced**, not how the runtime ran them — so
a client should say "2 in one turn", never "in parallel". `diff` frames ride their own phase
because the progress callback fires before the completion one; ⚠️ their body is 24-bit-ANSI
coloured, so strip escapes **before** classifying lines by leading character or every `+`/`−`
count reads zero.

One event type rather than five keeps the frame alphabet small, and clients already ignore unknown
event types — so this is backward compatible in both directions: an old app on a new gateway
ignores `tool`, and a new app on an unpatched gateway simply never sees one and falls back to
rendering the committed message's parsed tool rows.

`delta` and `reasoning` text are **append-only** within their own channel: a single frame may carry
one token or many. A fast brain emits tokens faster than a remote client drains them, so the handler
coalesces whatever is queued into as few frames as possible — consecutive same-type frames merge,
a type crossover flushes (reasoning never bleeds into answer bytes) — bounding the write rate to
the drain rate so the bounded per-subscriber queue can't overflow and drop tokens (a dropped token
would break the client's byte-exact handoff). Concatenation is associative, so the coalesced stream
is identical to the per-token stream.

`reasoning` frames come from the agent core's `reasoning_callback` (structured
`reasoning_content` deltas), registered per-turn by the run.py patch. Clients that don't know the
event type ignore it; servers without the patch simply never emit it.

## Kanban (Missions) endpoints

Keryx 1.6's Missions board rides the agent's own kanban database
(`hermes_cli.kanban_db` — same code path as the `kanban_*` tools, so WAL handling, schema
migrations, and the event log all behave identically). Reads and additive writes only; state
*transitions* (complete/block/claim) stay agent-side — the dispatcher owns those.

```
GET  /keryx/kanban/board                     tasks grouped by raw status + counts
GET  /keryx/kanban/task/{id}                 full task + comments + last 50 events
POST /keryx/kanban/task                      {"title", "assignee", "body"?, "priority"?,
                                              "triage"?: bool, "goal_mode"?: bool}
POST /keryx/kanban/task/{id}/comment         {"body"}
GET  /keryx/kanban/events?since=<cursor>     incremental event poll; returns {"events", "cursor"}
GET  /keryx/kanban/subs                      notify subscriptions; returns {"subs": [...]}
POST /keryx/kanban/task/{id}/subscribe       {"platform"?: "matrix", "chat_id", "thread_id"?}
POST /keryx/kanban/task/{id}/unsubscribe     same body; returns {"subscribed": false, "removed"}
```

All accept `?board=<slug>` to target a non-default board. `assignee` is required on create (the
dispatcher only spawns assigned tasks); `triage: true` parks the task spec-first instead of letting
the dispatcher pick it up. Phone-originated writes are authored as `keryx` server-side —
caller-supplied authors are rejected by design (a forged author reads as a system directive in
future worker context). Gateways without a kanban board return errors the app treats as
"missions unavailable"; route registration is centralized in `register_keryx_routes`, so new
routes ship with the module copy and never need a fresh api_server patch.

**Subscriptions (1.8 real-time alerts):** a subscribed `(platform, chat_id)` receives the
gateway kanban-notifier's native push message when the task hits a terminal state
(completed/blocked/gave_up/crashed/timed_out/…) — for Matrix that's a real room message, so the
app needs no polling. The notifier deletes subs itself once a task is genuinely done; treat a
vanished sub as "task ended". Requires the gateway notifier loop
(`kanban.dispatch_in_gateway`) to be enabled. The 15-min `MissionAlertsWorker` poll remains the
fallback for comments/non-terminal events.

## Skill Forge endpoints (1.8)

Read/write `SKILL.md` over the gateway's own `skill_manager_tool` machinery — same frontmatter
validation, atomic write, and security-scan-with-rollback as the agent's `skill_manage` tool.

```
GET  /keryx/skills/{name}    {"name", "category", "content", "files": [...], "readonly"}
PUT  /keryx/skills/{name}    {"content"} → {"ok", "message", "note"}; 400 = validation/scan
                             message verbatim; 403 = skill lives in a read-only external dir
POST /keryx/skills           {"name", "content", "category"?} → {"ok", "path"}
```

Every successful write drops the gateway's skills-prompt LRU, so **new** sessions see the edit
immediately (running sessions keep their cached prompt — no restart needed). A one-deep undo is
kept at `SKILL.md.bak` beside the skill.

## Session prune endpoint (1.8)

Thin wrapper over hermes-agent's `hermes sessions prune` (upstream only exposes it on the
dashboard web server, which many installs keep disabled). Deletes **ended** sessions only.

```
POST /keryx/sessions/prune   body mirrors the dashboard SessionPrune model:
                             {"older_than_days"?: 90, "started_before/after"?, "title_like"?,
                              "min/max_messages|tokens|cost"?, "source"?, "include_archived"?: false,
                              "dry_run"?: false}
                             dry-run → {"ok", "removed": 0, "matched", "oldest_started_at",
                                        "newest_started_at", "sessions": [≤50 sample]}
                             wet     → {"ok", "removed"}
```

Bare prune defaults to 90 days; any attribute filter suppresses the default unless
`older_than_days` is sent explicitly (same rule as the CLI/dashboard).

## Toolset toggle endpoints (1.16)

Platform-aware replacement for the read-only core `/v1/toolsets`, which reports the
**api_server** platform's enablement — not the platform the agent Keryx chats with actually
runs on (`platform_toolsets.matrix` by default). Reads and writes go through the same hermes
helpers as the desktop dashboard (`_get_platform_tools` / `_save_platform_tools`), and an edit
is live on the agent's next turn — the gateway re-resolves platform toolsets per turn.

```
GET /keryx/toolsets           ?platform=matrix (default) → {"platform", "canToggle": true,
                              "data": [{"name", "label", "description", "enabled",
                              "configured", "locked", "tools": [...]}]}
PUT /keryx/toolsets/{name}    {"enabled": bool, "platform"?: "matrix"} →
                              {"ok", "name", "enabled", "platform"}
                              400 = unknown toolset; 403 = locked/forbidden by the operator
```

Operator pins via env (comma-separated toolset names; read fresh per request):
`KERYX_TOOLSETS_LOCKED` cannot be disabled from the app, `KERYX_TOOLSETS_FORBIDDEN` cannot be
enabled. Both report `locked: true` in the GET so clients grey the switch out. The `no_mcp`
sentinel survives writes (the desktop picker's save-clears-it consent rule doesn't apply to a
phone toggle of one toolset).

## Pet endpoints (1.10/1.11)

The petdex mascot from the Hermes desktop app, served to the phone. Pets stay configured
server-side (`display.pet.enabled` / `.slug` — the same config the desktop and TUI read), so every
surface shows the same pet. All fail-open: no pet configured (or any engine hiccup) reports
`{"enabled": false}` and the client simply draws nothing.

```
GET  /keryx/pet              active pet render payload: {"enabled", "slug", "displayName",
                             "mime", "spritesheetBase64", "spritesheetRevision", "frameW/H",
                             "framesPerState", "loopMs", "stateRows", "framesByRow"}
                             ?meta=1 → just {"enabled", "slug", "displayName",
                             "spritesheetRevision"} — cheap probe to skip an unchanged ~2MB sheet
GET  /keryx/pets             adoptable list: installed pets merged with the petdex.dev catalog;
                             {"enabled", "active", "pets": [{"slug", "displayName", "installed",
                             "curated", "generated", "spritesheetUrl"}]}
                             ?localOnly=1 skips the remote manifest (and warms it) so installed
                             pets render instantly — two-phase load like the desktop picker
POST /keryx/pet/select       {"slug"} → install from petdex if needed + persist display.pet.*
                             (the desktop picker's exact path); 502 with the store's message on
                             a failed adopt
GET  /keryx/pet/thumb        ?slug=&url=  small idle-frame PNG {"ok", "slug", "thumbBase64"},
                             cropped + disk-cached server-side; `url` (petdex CDN only — never an
                             arbitrary host) lets not-yet-installed pets preview
```

Render notes for clients: rows are named in `stateRows` top→bottom (Codex 9-row and legacy 8-row
taxonomies both appear in the wild; alias `wave`→`waving`, `run`→`running`, `jump`→`jumping`).
Honor `framesByRow` over `framesPerState` — sheets are ragged, and stepping into a short row's
transparent padding reads as the pet blinking out. One loop of `framesPerState` frames spans
`loopMs`, whatever the row's real length.

## Tests

```bash
python3 -m pytest tests/          # coalescing is byte-exact and order-preserving; no gateway needed
```

## App-side setup

Keryx → Settings → **Hermes Link**: Gateway URL `http://<spark-host>:8642`, API key =
`API_SERVER_KEY` from `~/.hermes/.env`, toggle *Live token streaming* on.

## Verify

```bash
KEY=$(grep ^API_SERVER_KEY= ~/.hermes/.env | cut -d= -f2)
curl -N -H "Authorization: Bearer $KEY" \
  "http://127.0.0.1:8642/keryx/stream?platform=matrix&chat_id=%21room%3Ahost"
# → 200 text/event-stream, pings every 20 s; deltas appear when that room's agent turn runs
```

## Upstream

The side-channel is also proposed for the Hermes gateway itself in
[NousResearch/hermes-agent#57091](https://github.com/NousResearch/hermes-agent/pull/57091).
If that lands, a future `hermes` release may ship it natively; until then (and for any build that
doesn't carry it), this repo is the standalone, install-on-top version.
