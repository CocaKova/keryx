# Keryx 2.4.0

*Three movements in one release. 2.2 gave the app its surfaces, 2.3 gave a room full of agents
separate lives, 2.4 showed you what the agent is actually doing.*

**⚠️ Reinstall the gateway plugin.** The tool theater needs the gateway half:
`python3 Hermes-Chat/hermes-plugin/keryx-stream/install.py`, then restart the gateway. Without it
the app degrades cleanly to 2.3 behaviour — the theater simply never receives a frame — but you
get none of the new mid-turn view.

---

## The tool theater

A turn used to show a spinner, then a finished answer with its tool calls parsed back out of the
committed text. Everything the agent did in between — twelve calls, a delegated subagent, a
failure it recovered from — was invisible while it happened.

Now you see each tool as it starts, how long it took, and what failed and why. Calls fired in one
turn group on a rail. A failure shows its **reason**, dug out of the result envelope, instead of
the JSON it arrived in.

**Subagents get their own wings** — goal, model, tool count, duration, token cost, files touched,
and the summary each one came back with. Tap a landed wing to read the child's whole transcript.
A delegated child is not a session you can resume and its relay is never stored, so this is the
only window onto it.

**Edits show their diff.** A `patch` or `edit` renders `+2 −1` with the same diff the CLI would
have printed, expandable inline.

It is deliberately quiet — monospace, low alpha, one line per beat, six at a time with the
remainder counted. The committed reply renders the same calls properly a moment later; a theater
that competed with the answer would be shouting about the means while the end arrives.

**One vocabulary.** The live rows and the committed transcript now speak the same verbs, glyphs
and targets: `read_file` is `▤ Read SOUL.md` wherever it is drawn. Before this they read as two
different features describing the same call.

## The Council

Several agents, one room, each its own light. Every agent account gets a stable hue off a
gold-family palette and the herald's `☤` sigil, carried by its bubble rim, its name, its spinner
and its working bar. Settings → Agent grows a Heralds list with a per-herald override. A 1:1 room
looks exactly as it did.

- **Relays read as relays.** One agent passing another's message renders as an attributed notice
  above the bubble, never as the courier speaking.
- **Arrivals.** A turn nobody asked for — no message of yours in the last 20 minutes — gets a
  hairline mark in the herald's hue, one light sweep, and a `☤ <name>` notification title. It is
  an arrival, not a reply.
- **The drawer and Quick Rooms** wear the staff instead of a lettered monogram. Rooms with two or
  more heralds show a stacked row, one staff per hue; a single-herald room takes the *room's* hue,
  because otherwise every agent room comes out the same colour — and it replaces a monogram that
  said nothing (The Study, The Office, True North, The Ledger and The Forge all reduce to "T").
- **Senses** — opt-in, in-band, E2EE: the phone can tell the agent its battery, local time and
  coarse place. The marker is stripped from your own bubble, so it never shows in the transcript.

## The gilded void

Matte surfaces and gilt hairline rims. A caduceus spinner. A send ritual. An AGSL dusk sky whose
light keeps its own clock. A composer that carries the model and reasoning pills and a context
ring, fed by a new `usage` frame that reports the turn's true context occupancy. One light sweep
across navigation.

New app icon: the kerykeion, gilded hairline on matte void, with a monochrome layer. Its
generator lives in the repo (`tools/kerykeion_icon.py`) and reproduces every committed asset
byte-for-byte.

## Fixes

- **The friendly-verb trap.** The gateway prints tool lines two ways — one names the tool
  (`read_file: "a.txt"`), the other is human-phrased progress (`📖 Reading a.txt`). The parser
  matched the second kind and filed the call under **"Reading"**, a verb standing where a tool id
  belongs. Every enriched fact the side-channel carried — duration, real verdict, diff stats — was
  then dropped silently on exactly the turns the agent narrated that way.
- **A ✓ now means *seen* to succeed.** Most tool lines carry no verdict in their text and the row
  printed ✓ for all of them, so a turn's one failure was distinguishable only by luck. Unknown
  gets its own faint mark.
- **Batched calls no longer swap verdicts.** Ends correlate to starts FIFO; closing newest-first
  handed one call's success to another and its failure back.
- **Reasoning effort** resolves from `model:` before the legacy `agent:` spot, so the picker
  reflects the real global setting, and Mistral-native tokenizers (which accept only none/high)
  declare an honest binary switch instead of four levels.
- **Three settings knobs were showing stale defaults** when the key was absent from `config.yaml`
  — max turns (90 → 500), concurrent subagents (3 → 10) and subagent turns (50 → 250) — so the
  phone reported a value Hermes had moved on from.
- Command palette dismisses after a manual send.

## Under it

377 app unit tests, 311 plugin tests. The `tool` frame is one event type carrying the whole tool
and subagent vocabulary as JSON — an old app ignores it and a new app on an unpatched gateway
never sees one, so it is compatible in both directions. Wire protocol is documented in
[`hermes-plugin/keryx-stream/README.md`](../hermes-plugin/keryx-stream/README.md); the design and
its traps in [`docs/KERYX-2.4-THEATER.md`](KERYX-2.4-THEATER.md) and
[`docs/KERYX-2.3-COUNCIL-PLAN.md`](KERYX-2.3-COUNCIL-PLAN.md).
