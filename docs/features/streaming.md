# Streaming

The dual-tier design, what travels on each wire, and how to read the frames. For the install steps
see [gateway-setup.md](../gateway-setup.md); the vocabulary lives in [concepts.md](../concepts.md).

## Why two tiers

Plain Matrix edit-streaming (`m.replace` on every chunk) bloats the homeserver database and hammers
sync. Keryx keeps the room at one final message per turn and draws the tokens from a side-channel
instead. The room stays small, the phone stays live, and a client that knows nothing about the
side-channel still sees a normal single message.

## Tier 1, the side-channel

A transient SSE subscription on the gateway's API server, opened right before the command is sent and
closed once the final Matrix event has synced. One URL, per turn and per room:

```
GET /keryx/stream?platform=matrix&chat_id=<room-id>
```

Bearer-authed with the same token the app uses everywhere, served by the plugin's own HTTP server (its
own port; see [gateway-setup.md](../gateway-setup.md)). The answer is `200` with
`text/event-stream`.

### The frames

Each `data:` line is one JSON object in a `{"text": …}` envelope; the nested payload is JSON again for
the structured kinds.

| `event:` | payload | meaning |
|---|---|---|
| `delta` | `{"text": "…"}` | incremental answer tokens |
| `reasoning` | `{"text": "…"}` | thinking deltas, ahead of the answer |
| `segment` | `{}` | a text/tool boundary |
| `tool` | `{"text": "{…}"}` | one theater beat (below) |
| `usage` | `{"text": "{used,max,model}"}` | context occupancy at the finish line |
| `status` | `{"text": "{kind,text}"}` | lifecycle line, compaction above all |
| `stop` | `{"text": "<full final>"}` | turn complete, channel closes |
| `ping` | `{}` | keepalive, every 20 s |

Unknown event types are ignored on purpose, so a newer gateway can add beats without a new APK.

`usage.used` is the last API call's prompt size, which is the model's actual occupancy, rather than a
cumulative session counter. It has to be published *before* `stop`, since the subscription is transient
and the reader hangs up at the stop frame. It is what fills the context ring.

`status` covers the lines a messaging platform would swallow. `kind` is `compacting`, `lifecycle`,
`warning`, or `ready`. Compaction matters most because it is minutes of silence with nothing else on
the wire, and nothing else announces its end; `ready` fires when the compression call returns, on
success or raise alike.

### The tool beats

`tool` carries the whole theater vocabulary in one event type. The `phase` field discriminates:

```
{"phase":"start", "name":"read_file", "preview":"README.md"}
{"phase":"end",   "name":"read_file", "ok":true, "ms":113, "result":"…", "result_len":812}
{"phase":"diff",  "name":"patch", "body":"…ANSI-coloured unified diff…"}
{"phase":"sub",   "kind":"start|tool|complete|thinking|progress|spawn_requested", "child":"sa-0-…", …}
```

Four rules are load-bearing:

1. Every `end` carries its `result`, clipped from the middle with head and tail kept and an elision
   marker between, because an appended verdict lives at the tail. `result_len` is the unclipped size.
2. Ends correlate to starts by **order**, first-in-first-out, because there is no call id on the wire.
3. File lists ride as counts (`files_read_n`, `files_written_n`), not paths.
4. A child's raw assistant text is dropped; a phone on a transient socket cannot drink from a watch
   window. The `thinking` and `progress` kinds carry the activity line instead.

`sub` frames also bring the cost numbers the wings show: `duration_seconds`, `input_tokens`,
`output_tokens`, `api_calls`, `tool_count`, and the `summary` a completed child returned.

### Coalescing

A fast brain emits tokens quicker than a remote client drains them, so the handler merges whatever is
queued into as few frames as possible. Consecutive same-type frames merge; a type crossover flushes,
so reasoning bytes never bleed into answer bytes. Concatenation is associative, which means the
coalesced stream is byte-identical to a per-token stream. That is why a `delta` frame may carry one
token or many.

## Tier 2, the fallback

No subscriber attached, because the app closed or the channel was unreachable. Two possible answers:

- The room gets the single final message. This is the default.
- With `KERYX_STREAM_FALLBACK_EDITS=1` on the gateway, the room gets throttled `m.replace` edit
  streaming instead, paced by `streaming.edit_interval` / `streaming.buffer_threshold` (1.2 s / 60
  chars in the sample block). It is off by default because clients that do not collapse
  `m.replace` render the edits as duplicate bubbles.

The switch is evaluated live per flush, so an attachment mid-turn silences protocol edits at once.

## What is not on the wire

Both of these read as "no live stream" and neither is a fault.

- A turn started outside the dashboard process, for example a `hermes chat` one-shot, shows committed
  turns only. The side-channel belongs to the runtime that is executing the turn.
- A second WebSocket client that resumes or submits on the same session takes the event transport from
  the phone. Reopen the app to get it back.

## A Hermes update can drop it

The plugin is installed on top of the core tree. A `hermes update` or reinstall rewrites that tree,
so after one, re-run the plugin's installer and `hermes gateway restart`. The app still works during
that window, on committed turns only. The full sequence is in [gateway-setup.md](../gateway-setup.md).
