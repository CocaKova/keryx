# Tap-In

The agent, going — full screen. The transcript tells you the agent is working quietly on purpose:
one row per tool call, a working banner, a breathing dot. Tap-In is the other register, for the
moments you want to watch the work rather than wait for the answer. Same facts, same grammar, same
colours, drawn with room.

## Ways in, ways out

- **Tap the working banner** (the cloud that says `Reading a.txt · 0:42`). It wears a `⤢` while it
  is a door.
- **Long-press the newest run** (the `Ran 5 tools` / `Running…` header in the transcript). This works
  after the turn too, and opens the run frozen.
- **Tap the run notice in the shade** (`Agent running`). The room opens already tapped in.
- **Back** closes it. The transcript never moved.

Tap-In does not close itself when the turn lands: the instruments freeze, the headline says `Landed`,
and you step out when you are done.

## What is on the screen

Top to bottom:

1. **Headline.** The gateway's own status line when it has one (a context compaction above all),
   else the open tool call in the present tense (`Reading a.txt`), else `Waiting on 2 helpers`,
   else the working label. Under it the turn clock, and the live token rate when the side-channel
   is streaming.
2. **Mind.** The reasoning stream, tailed to its last six lines, older thought fading upward.
   Absent when the model sends no reasoning — nothing is faked.
3. **Crew.** One card per helper the turn sent out — a subagent, a reviewer, a researcher — flying
   first, then landed in the order they went out. Each card: a glyph for the kind of helper (read
   off its goal), its role (the goal's first clause), the model, tools so far, elapsed, tokens; the
   newest line it sent while it flies; the summary it came back with once it lands. A fan-out shows
   `2 of 5 landed`. A card with a record behind it is a door: tap it for the child's own window
   (its live trail while flying, its stored session once landed — the same sheet the wing opens).
4. **Rail.** The tool timeline, oldest first, hung from a hairline with a bead per call. The row is
   the transcript's own tool row — same glyph, same verb, same verdict, the same folds for output
   and diff — so nothing is re-worded. The bead breathes on the open call.
5. **Saying.** The answer so far, once the first answer token has landed.

A **steer bar** sits under the list while the turn runs, on the direct door: type and the wheel
sends it as a steer (the agent reads it on its next step, nothing is interrupted), hold the wheel
to queue it for after the turn, leave the field empty and the wheel becomes the square — stop.
The same grammar as the chat composer mid-turn; the bar goes when the turn lands.

An instrument row sits under it: the context ring with used-of-max, tool count, failures,
crew landed-of-total, and the model.

## A helper's mind

Tap a flying crew card on the direct door and the sheet opens on the child's **own** session, not
the parent's wire: what ran before you opened it (folded), what it is thinking as it streams,
what it has done, what it has said. Under it, a steer bar of its own — a word in that one
helper's ear (`subagent.steer`; the in-flight tool is never cut) and a square that stops that
helper alone, with a confirm (`subagent.interrupt`; the turn that sent it keeps going and reads
whatever it had). "It didn't take it" is an honest answer: a helper spawned by a turn another door
still owns, or one already wrapping up, declines the note rather than pretending to hear it.

Once the helper lands the sheet is the stored transcript, as before. On the Matrix door the sheet
stays what it was: the trail while flying, the transcript after.

## Where it comes from

Nothing new leaves the gateway. Tap-In is a projection of what the chat screen already holds —
the rendered transcript items (both doors are reconciled there), the live stream's text and
reasoning, the `status.update` line, the `usage` frame — cut back to the last thing you said.
The projection is pure Kotlin (`TapIn.project`, `TurnSlice.of`) and held to the mock corpus by
`TapInTest`.

Two wire facts shape it, both from [streaming.md](streaming.md): a child's raw assistant text is
dropped on the **parent's** wire, so a crew card shows the child's activity line and summary; the
child's mind comes from resuming the child's own session id, which the gateway serves as a watch
window that mirrors the child's frames as native deltas. And tool ends correlate to starts by
order, so the rail is FIFO like the transcript's run.

## Motion

Under reduced motion (Battery Saver, or the system setting) the headline snaps instead of sliding,
the mind's fade is dropped, the crew cards stop shimmering, and the beads stop breathing. The
clocks still tick — they are data.
