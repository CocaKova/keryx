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

## One language, live and committed

The first cut shipped the theater beside the existing text-parsed `ToolGroupCard`, and on device
they read as two different features describing the same call — a boxed gradient card under a
monospace hairline row (Jonny: *"the tool call log and the new tool call show kind of fight"*).

`domain/model/ToolGrammar.kt` is the fix: one verb/glyph/target vocabulary that both surfaces
speak. `read_file` is `▤ Read SOUL.md` wherever it is drawn, live or in the transcript, and a run
collapses to the same sentence ("Wrote c.kt, explored 2 files, ran ls"). The committed card lost
its gradient fill for a hairline; only the border still carries the "working" breath.

One divergence from Talaria: a **single**-call run uses the tool's own verb rather than its
category's — "Read SOUL.md", not "Explored SOUL.md". The category grammar exists to count a
crowd, and there is no crowd.

## Diff stats

`tool.completed` carries the tool's *result*, which for an edit is a success envelope, not a
diff — the diff only exists by comparing the file against what it was before the call. The agent's
own display layer already does exactly that, so the gateway borrows it (`capture_local_edit_snapshot`
at start, `render_edit_diff_with_delta` at completion) and the app shows the same diff the CLI
would have printed. It rides its own `phase: "diff"` frame because the progress callback fires
*before* the complete one, so the `end` frame is already gone by then.

⚠️ The rendered lines are ANSI-coloured 24-bit (`ESC[38;2;…m+line ESC[0m`). Anything classifying
by leading character sees an escape byte and counts zero forever — strip first, then classify, on
both sides. Counts come from the whole diff before clipping, so "+2 −1" stays true when the panel
is cut.

## The committed transcript

Durations, real verdicts and diffs exist only in the side-channel, never in the message text. So
the record of the turn just watched is held per room, one deep, and attached to the newest tool
run: `Theater.align` pairs parsed names to beats **positionally**, and a mismatch at position *i*
leaves that row un-enriched rather than guessing — a row with fewer facts is right, a row with
another call's diff is not. It dies with the process, and history then renders exactly as it did
before: same grammar, fewer facts. Persisting it would mean a second store of tool results, and
the answer to "what did that edit change" a week later is the file, not a phone's memory of it.

## Opening a subagent

A wing showed a goal, a rollup and a summary — enough to know it worked and nothing about how
(Jonny: *"there's no way of seeing the subagent session"*). The gateway relays `child_session_id`;
the first cut dropped it. It now rides the `sub` frames, a landed wing is underlined and tappable,
and `SubagentSessionSheet` opens the child's own transcript through the existing
`GET /api/sessions/{id}/messages`. Read-only: the Sessions tab owns resuming, and the verb here is
"show me", not "carry on".

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

**Diffs** — a real `patch` on a 4-line file: `+2 −1`, with the ANSI-coloured body, the
`┊ review diff` banner and the `a/… → b/…` header the chrome rules have to skip. That exact
payload is now a test.

60 unit tests (`TheaterTest`, `ToolGrammarTest`). Everything above is verified on the wire, from
the gateway side; 2.3 and the first theater cut have been walked on device, this pass has not.
