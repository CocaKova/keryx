# Keryx 2.4 — the tool theater

**Status: built 2026-08-19, gateway half verified live, app half NOT yet walked on device.**

## The problem

A Matrix turn showed a spinner. Then, some minutes later, a finished answer appeared with its
tool calls parsed back out of the committed text by `MessageParser`. Everything the agent did in
between — twelve tool calls, a delegated subagent, a failure it recovered from — was invisible
while it was happening and only legible afterwards, in reverse-engineered form.

The gateway was never the blocker it was assumed to be. The agent core has fired
`tool_progress_callback` all along (`agent/tool_executor.py`, and `tools/delegate_tool.py` relays
a child's events through the same hook). The Keryx side-channel simply never listened: it carried
`delta`, `reasoning`, `segment`, `usage` and `stop`, and nothing about tools.

## The wire

One new SSE event type, `tool`, on the existing `/keryx/stream` channel. The payload is JSON
inside the same `{"text": …}` envelope every other frame uses:

```
{"phase":"start", "name":"read_file", "preview":"SOUL.md"}
{"phase":"end",   "name":"read_file", "ok":true, "ms":113}
{"phase":"end",   "name":"read_file", "ok":false, "ms":85, "result":"File not found: …"}
{"phase":"sub",   "kind":"start|tool|complete|thinking|progress|spawn_requested",
                  "child":"sa-0-cf0971a4", "goal":…, "model":…, "task_index":0, "task_count":1,
                  "tool_count":2, "status":"completed", "duration_seconds":38.15,
                  "input_tokens":…, "output_tokens":…, "api_calls":…, "files_written_n":…,
                  "summary":…}
```

Every `subagent.*` frame carries the same identity block and adds what only it knows —
deliberately **the same field set Talaria's `Delegation` model consumes**, so a delegation reads
as the same thing on both clients instead of each inventing its own half-view. `subagent.text`
(the child's assistant stream, relayed per delta) is dropped on both, for the same reason: a
watch window can drink from that, a phone on a transient SSE socket cannot. The wing's activity
line is fed by `tool` / `thinking` / `progress` instead.

`files_read` / `files_written` are sent as **counts** (`_n`), not paths: the wing renders
"2 written" and never the list, and a 40-path array per completion is a lot of socket for a
number.

One event type rather than five keeps the frame alphabet small, and an older app ignores unknown
event types already — so this is backward compatible in both directions.

**`result` rides only on a failure.** A successful call's output is the answer's raw material; it
arrives in the committed message a moment later, rendered properly. Pushing a few hundred bytes
of escaped JSON per call to a phone to display nothing is waste. A failure is the one case where
the mid-turn glimpse is the whole point.

## Correlation — the part that is actually hard

`tool.completed` **carries no call id**, so starts and ends are correlated by order.

⚠️ **A model batches calls.** Observed live on 2026-08-19: `read_file` A and `read_file` B both
opened before either closed, then both landed. The executor emits completions in the same order
it emitted the starts, so an end closes the **oldest** open row — FIFO, not a stack. Closing
newest-first handed A's success to B and B's failure to A, which is worse than showing nothing.
The tool name is a tiebreak, not a key.

Parallel is **observed, not announced**. A call still open when another opens marks both ends of
that overlap. But that is an observation about when calls were *announced*, not how the runtime
ran them — so the renderer says "**2 in one turn**", never "in parallel". Talaria can make the
stronger claim because its gateway sends a `concurrent` flag; this channel doesn't, and the
weaker claim is the one that survives.

A delegation is not a tool row. `delegate_task` itself returns almost immediately (45 ms in the
verified trace) while the child runs for another 38 seconds, so the wings are their own section
with their own lifecycle, keyed by `subagent_id` (falling back to `task-<index>` for older
emitters that omit it). An unknown `kind` still folds its identity in, so a later gateway adding
one cannot blank a wing.

## The two halves

**Gateway** — `gateway/keryx_stream.py`, `_attach_tool_callbacks`. ⚠️ REINSTALL-FRAGILE: the whole
module is a SILAS_EXT payload (`~/.hermes/silas_ext/payloads/keryx_stream.py`, reapply.py entry
"gateway: keryx_stream.py present"). Edit the live file, then copy it to the payload.

It attaches from inside `attach_reasoning_callback`, which `gateway/run.py` already calls as the
single per-turn attach point — so **no new hook and no reapply.py change** was needed.

Two traps it has to respect, both because `run.py` assigns `tool_progress_callback` immediately
before calling us and the agent is *cached across turns*:

1. It **chains** rather than overwrites — whatever the gateway wired (live status, log mode) still
   runs, and runs even if the mirror half raised.
2. The wrapper is tagged (`_keryx_inner`) and unwrapped before re-wrapping. Without that, every
   turn would nest one more layer for the life of the process.

**App** — `domain/model/Theater.kt` is the pure reducer (no Compose, no Matrix), driven by
`HermesStreamClient.Event.Tool`, held on `LiveStream.theater`, drawn by `TheaterStage` between the
reasoning canvas and the answer, because that is where it happened. Deliberately scaffold voice —
monospace, low alpha, one line per beat, tail-windowed to six with the remainder counted. The
committed message renders the same calls properly a moment later; a theater that competed with
the answer would be shouting about the means while the end arrives.

The exception is a delegation, which gets Talaria's fuller treatment: a hairline rail, one wing
per child with its goal (1-based index in a fan-out), a meta line of model · tools · duration ·
tokens · files, and a tail that shows what it is doing while it flies and the summary it came
back with once it lands — tappable, because for a fan-out reporting back that summary IS the
work, and the only place the child's result exists on this screen. A delegated child is not a
session you can open and its relay is never persisted, so this live view is the only window
onto it.

The beats clear on a mid-turn segment commit, since that commit carries its own parsed tool rows.

## Verified

Live against the real gateway, 2026-08-19.

**Tools** — start/end frames with correct name, preview, `ok` and `ms`; `result` present on a
failing `read_file` and absent on the succeeding one. Also the batching trace that forced FIFO
correlation.

**Delegation** — a real `delegate_task` in a `default`-profile room. Every field arrived:
`goal`, `model`, `task_index`/`task_count`, `depth`, `tool_count`, `status: "completed"`,
`duration_seconds: 38.15`, the token rollup, `api_calls`, the file counts and the child's
`summary`. The parent polled with `sleep` while the child worked, so the concurrent path ran too.

⚠️ **Rooms are profiles.** `platforms.matrix.room_profile_map` routes each room to an agent
profile, and profiles have different toolsets — The Study is **theo**, which has no delegation
tool, so the first delegation attempts came back "I have no delegate_task" and read like a bug in
this work. It was the wrong room. `default` (Sy) rooms are The Office, Clocktower and
Jonny & SILAS.

36 unit tests (`TheaterTest`). The app half is **not yet walked on device** — everything above is
the wire, verified from the gateway side.
