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
{"phase":"sub",   "kind":"start|tool|text|complete|progress", "child":"k", "name":…, "preview":…}
```

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

A subagent never reports when one of its own calls *ended*; the next one starting is the only
signal, and `subagent.complete` closes whatever it left open. Two concurrent subagents are kept
apart by their `child` key.

`subagent.text` / `.thinking` / `.progress` / `.spawn_requested` are dropped: a running commentary
that would outpace the phone and drown the rows that say what actually happened.

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

The beats clear on a mid-turn segment commit, since that commit carries its own parsed tool rows.

## Verified

Live against the real gateway, room "The Study", 2026-08-19: start/end frames with correct name,
preview, `ok`, and `ms`; `result` present on the failing call and absent on the succeeding one.
The subagent path shares the same emit and is covered by unit tests, but has not been seen on a
real delegation.

28 unit tests (`TheaterTest`). Not yet walked on device.
