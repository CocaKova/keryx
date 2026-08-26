# Keryx 2.5.7 — the wait has a name, and the tool has an answer

**Status: built 2026-08-26; gateway half verified against the agent's real emitters; app half
built + unit-tested, NOT yet walked on device (phone off the LAN at build time).**

Three things Jonny asked for in one breath, which turned out to be two diseases and a config line.

## 1. "I never know when compression is happening"

A Matrix turn that crosses the context threshold goes quiet for as long as the summary model
takes. The agent core announces it (`agent/conversation_compression.py` templates →
`agent._emit_status` → `status_callback("lifecycle", …)`), and `gateway/run.py` swallows the
announcement on chat platforms by design (`_TELEGRAM_NOISY_STATUS_RE`; the opt-in
`compression.progress_notices` re-posts it as a *room message*, which is the wrong shape — this
is a state, not a bubble). The side-channel carried nothing. So on the Matrix door the cloud
said "Working · 3:40" and meant nothing by it.

The direct door was *nearly* there: `status.update` arrives, `DirectTransport` stores it in
`statusFlow`, and **nothing read that flow** — `sessionStatus(id)` had no consumer. And the
gateway only re-tags the one line carrying `COMPACTION_STATUS_MARKER` ("Compacting context");
the pre-API / preflight / retry / idle lines arrive as plain `lifecycle`, so `isCompacting` was
false for most of them anyway.

**Gateway** — `event: status` on the side-channel (`_attach_status_mirror`, keryx_stream.py).
`{"kind":"compacting","text":…,"tokens":N}` while it runs, `{"kind":"ready"}` when
`_compress_context` returns (success or raise — nothing else announces the end, and the next
frame would otherwise be the first token of the next call, a prefill later).

⚠️ **run.py assigns `agent.status_callback` AFTER calling `attach_reasoning_callback`**, so the
mirror cannot chain that callback — it would be overwritten every turn. It wraps the *emitters*
on the instance (`_emit_status`, `_emit_warning`, `_compress_context`); an instance attribute
shadows the class method and survives whatever run.py does afterwards. Tagged (`_keryx_inner`)
and unwrapped on re-attach, because the agent is cached across turns.

**App** — `SessionStatus.of(kind, text)` (core) classifies by the agent's template glyphs when
the tag is generic; both doors land in `ChatViewModel.sessionStatus` (Matrix `Event.Status`
merged with the direct transport's flow). While it holds, the working cloud reads
**🗜 Compressing context (~123k tokens) · 2:14** in place of the verb it is not doing, and the
no-reply timer is re-armed — compaction is the turn *working*, not the agent gone quiet. Any
token, `stop`, `ready` or stream clear ends it.

## 2. "I don't see the tool payloads or failures"

Two halves, both real:

- The side-channel sent `result` **only on a failure** (2.4's rule: "a success's output is the
  answer's raw material, it lands in the committed message"). It never does — the committed
  Matrix text carries tool *names* and a display argument, not tool output. And the syntax
  oracle is not a tool at all: it is a `transform_tool_result` hook that appends its verdict to
  a `write_file` / `patch` / `execute_code` **result** — so its diagnosis lived in exactly the
  payload the phone never received.
- `ToolTheaterRow` drew **no result at all**, not even the failure's — `beat.result` was set on a
  failed row and never read, `Theater.reason()` had no caller. The direct door's `ToolCall.result`
  had been carried since 3.0 and was equally undrawn.

Now every `end` frame carries `result` (post-hook, so a plugin's verdict is in it), clipped to
2,400 chars **from the middle** — head *and* tail kept, `⋯ N chars elided ⋯` between — because
the tail is where an appended verdict lives; `result_len` says how big it really was. The row
shows a failure's reason (`Theater.reason`, error-coloured, under the title) and a `▸ output`
fold for the full payload on every call that has one, live or committed-and-enriched, either door.

## 3. "execute_code is disabled"

Config, not code: `platform_toolsets.matrix` in `~/.hermes/config.yaml` never listed
`code_execution` (the CLI list does). It was removed on purpose on 2026-06-19 when the 35B
brain was funnelling everything through it *because `terminal` had been dropped* — that root
cause has been fixed (explicit `terminal` in the list) and the brain is the 27B. Added
`code_execution` back, explicitly, in the matrix list. Gateway + dashboard restarted.

## Verified

- Gateway: `tests/test_status_mirror.py` (6) — compaction lines classify, `ready` follows
  `_compress_context` even on raise, re-attach ×5 never nests, a 5 KB result keeps the oracle's
  tail. Full plugin suite 315 pass; the 2 `test_config_knobs[max_turns]` failures pre-date this
  work (upstream changed the default).
- App: `SessionStatusTest` (4, core) + `TheaterTest` (31) green; `assembleDebug` builds.
- Device walk: **pending** — phone was off the LAN. Walk both doors: send something that trips
  compaction (or `/compress`), watch the cloud; open a run, tap `▸ output` on a `write_file`
  with a deliberate syntax error and read the oracle's verdict.
