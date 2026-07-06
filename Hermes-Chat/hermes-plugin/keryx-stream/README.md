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
event: stop      data: {}                       # turn complete; channel closes
event: ping      data: {}                       # 20 s keepalive
```

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
```

All accept `?board=<slug>` to target a non-default board. `assignee` is required on create (the
dispatcher only spawns assigned tasks); `triage: true` parks the task spec-first instead of letting
the dispatcher pick it up. Phone-originated writes are authored as `keryx` server-side —
caller-supplied authors are rejected by design (a forged author reads as a system directive in
future worker context). Gateways without a kanban board return errors the app treats as
"missions unavailable"; route registration is centralized in `register_keryx_routes`, so new
routes ship with the module copy and never need a fresh api_server patch.

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
