# Concepts

The vocabulary this app uses, defined once, so the feature pages can stay short. These names come
from the source's own doc comments and the settings rows.

## Doors

A **door** is the transport the app is standing on. There are two, and one is active at a time.

- **Matrix** — the app joins a homeserver with the Matrix SDK. Rows are rooms; a room has members,
  an avatar, and invites. The multi-agent **council** look belongs to this door.
- **Direct** — the app talks to the gateway's own JSON-RPC over `WS /api/ws` plus its REST surface.
  Rows are gateway sessions, and the gateway's native projects, the runs list and the bot roster
  only appear here.

The noun follows the door and never crosses over: rooms on Matrix, sessions on the direct door. When
the UI says "session" you are on the direct door; "room" means Matrix. The settings rows that exist
on one door only are simply not offered on the other, so search never opens an empty page.

## Rooms and sessions

A **room** (Matrix) is the unit of conversation on the homeserver. A **session** (direct door) is
the unit the gateway stores, with its own id, model, message count and token totals. Both carry the
same rendered turn structure. On the Matrix door the drawer's pinned deck is headed *Quick Rooms*,
and pinning is a shortcut. On the direct door the header is *Pinned* and the pin is the gateway's own
keep flag: a pinned session is exempt from the gateway's auto-archive sweep and reads as pinned on
every surface, not only this phone.

## The council

Several agents in one room, each with its own hue and sigil. A room with more than one herald is a
council: the members' colours are the room's colour, the bubbles wear their rim in each agent's own
tone, and the spinner follows the same. An agent relaying for another renders as an attributed notice
rather than as the courier speaking, and a turn nobody addressed is marked as an *arrival* rather
than as an answer.

## The two streaming tiers

Live text is a side-channel, not the room's own traffic.

**Tier 1**, the SSE side-channel. The app opens a transient `GET /keryx/stream?platform=matrix&
chat_id=<room>` subscription on the gateway's API server right before sending a command. While it is
attached, token deltas and the tool beats mirror over it live, protocol edits are suppressed, and
the room gets exactly one final committed message. The subscription is per-turn and per-room; it
closes on the `stop` frame.

**Tier 2**, the fallback. No subscriber attached, so Matrix just carries the single final message,
or throttled `m.replace` edits if the gateway set `KERYX_STREAM_FALLBACK_EDITS=1`. The switch is
evaluated per flush, so an attachment mid-turn silences protocol edits immediately.

Practical reading: the same room can show a live stream on one turn and the whole answer at once on
the next. Both are correct. See [features/streaming.md](features/streaming.md).

## The frame alphabet

The side-channel speaks a handful of named events, and unknown ones are ignored so a newer gateway
can add beats without a new APK. `delta` is answer text, `reasoning` is the thinking stream,
`segment` marks a text-to-tool boundary, `tool` carries the theater beats, `usage` reports context
occupancy, `status` carries lifecycle lines such as compaction, `stop` ends the turn, `ping` keeps
the NAT alive. Both text channels are append-only within their own kind; the gateway coalesces queued
frames so a fast brain cannot overflow the queue, and a type crossover flushes.

## Foreign-follow

A gateway serves many doors at once: this app, a browser tab, a terminal, cron. When a turn arrives
through another door, the phone's own live subscription is idle, because it attached to a different
runtime and that run did not start on it. So the app follows the session's pulse instead: the row's
message count, its last-activity stamp, and whether the gateway labelled it as working. A count that
grew means re-read the transcript. A count that shrank (an undo, an in-place compaction) is a new
baseline. A turn this phone is running itself is not foreign and does not trigger a re-read. That
is why a room can stay open and still catch up on a turn you started elsewhere.

## Instruments

The small non-chat readouts on the chat floor.

- The **context ring**: an arc that travels as the model's window fills, thin at empty and bold as
  compaction nears, accent then amber past three quarters then red. Tap it for the exact figure,
  in the form `84k / 128k`.
- The **flight plan**: the agent's own plan list, pinned under the top bar. It stays static while the
  transcript scrolls under it and ticks itself off as work lands. Collapsed it is one line, the count
  plus the step in flight.
- The **link-health dot**: breathes while tokens flow, dims when idle, goes red when the gateway is
  unreachable.

## The quiet shade

Notifications that can be answered without opening the app. An approval gets its buttons straight
from the gateway's own verbs; a blocking question gets buttons when it has at most three choices and
is not multi-select, otherwise it is tap-through. Credentials are the exception on purpose: a sudo or
secret answer is a password, and passwords do not ride the lock-screen surface, so those notices are
tap-only and the in-app card owns the answer. Every notice carries the lifetime after which the shade
should let it go, because a button that outlived the wait would resolve nothing server-side.

## The markers

The agent can put structure into its own text with single-line markers, and each is a parse, not a
keyword sniff. `⟦keryx:ask|A|B⟧` becomes one-tap option buttons. `⟦keryx:do|<kind>|<arg>|<arg>⟧`
becomes a tile that performs a phone action on a tap. `⟦keryx:voice⟧` marks a spoken-call reply. An
unknown kind or a malformed argument is not an action; the marker then stays literal text, so you can
still read what was asked and see that nothing ran. The tap is the consent: no marker runs by itself.

## Phone vs gateway

On the phone: the settings rows, the archive's full-text index (built locally, because an encrypted
room cannot be searched server-side; the phone is the only place the plaintext exists), the console's
run registry, and the crash log.

On the gateway: the transcript itself, sessions and their pins, the kanban board, skills, pets,
toolsets, the model catalog, and the reasoning dial's configured value. Most hub panels cache the
gateway's last answer so they read offline, and a panel whose route 404s is hidden rather than
stubbed.

## The dream look

One attention budget governs the ornaments: a single focal effect at a time, everything else as a
whisper. Ambient glows drift at minutes per pass, magic sand pours off the reply still being written,
and the back gesture scrubs a page transition under your finger. Battery Saver stills every ornament.
Haptics come in two weights, a single light tick for arrivals and folds, a double for commits and
errors.
