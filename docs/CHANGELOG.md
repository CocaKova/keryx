# Changelog

Newest first. Built from `git tag`, `git log`, and the version headers in the build-plan notes, with
each entry's facts cross-checked against the shipped source. Some versions never got a tag; those are
marked, and their numbers come from the version header in their own plan doc plus the commit that names
them.

## 2.13.6 · versionCode 108

- The agent's questions reach the phone again. Since hermes `ebe8cda8` (2026-09-13) a clarify,
  approval, sudo or secret prompt is a JSON-RPC request *from* the gateway — a frame with a
  string id (`srq-…`) that wants a response frame back — and the gateway only sends one to a
  client that has said `client.capabilities {server_requests: true}`. Keryx parsed only integer
  ids, so the frame fell on the floor, and it never advertised, so the gateway did not even wait:
  every clarify came back empty in 20 ms, and the agent read that as "you skipped it" (Sy's 09-24
  session, three times over). The socket now tells the gateway on every `gateway.ready` that it
  answers, routes string-id frames to the cards, answers with response frames, takes a batch of
  questions one card at a time (`clarify.lock` per question, "2 of 4" in the header), honours
  `request.cancel`, and replays the questions still open on reconnect (`session.resume`'s
  `open_requests`). Desktop-only bridges (terminal read, browser preview, vault prompts, the
  tour) are declined at once with `-32601` so the agent is not stalled for the deadline. The
  old `*.request` / `*.respond` pair still works for a gateway older than the change.

## 2.13.5 · versionCode 107

- Stop settles the turn. The gateway's `message.complete` is the end of a turn whatever the
  last row looks like; a stopped turn folds as a thought with no answer, which the message walk
  read as mid-run and held the banner and the thinking disclosure for the whole long quiet
  window. The direct door now settles on the completion itself, and a Stop that gets no
  completion at all (a turn interrupted in its build window) seals the stream locally after 4 s.
- The pill tells the truth about a sticky pick. With sticky model on (the default: a new chat
  opens on the model you picked last), the switch ran a second after the session was made, and
  the pill kept the pre-switch model — "it showed qwen, then used grok". An applied switch now
  writes the session's model at once, and the opening says so: "Opened on grok-4.5 — your last
  pick (sticky model, in Settings)".

## 2.13.4 · versionCode 106

- A reply made of `- key: value` bullets no longer folds into the tool run above it. The
  glyph-less tool line (`terminal: "…"`, Hermes' emoji-less repeat) now needs one quoted span
  matched to its own closing mark and never sits under a bullet; a line such as
  `` - package: `a` → PyPI `b` + engine `c` `` — first and last characters backticks — had passed
  as a fully quoted argument, and one such line made the whole answer a "tool message". A tool
  glyph must also be a real symbol now, so `# note:` and `> quote:` stay prose. Parser bug from
  the Matrix era, surfaced by the shape of one reply; not a 2.13 regression.

## 2.13.3 · versionCode 105

- The Quick Settings note survives its own unlock on Android 8–10. There a PIN or pattern unlock
  is the system's confirm-credential screen, a separate activity; the note was `noHistory`, so
  being covered by it finished the note. It now finishes itself on leaving, except to unlock.
- A `MEDIA:` path may hold an apostrophe (`/home/sy/o'brien.png`); only a trailing one is the
  tag's closing quote. 2.13.1's quote fix had cut such paths at the apostrophe.

## 2.13.2 · versionCode 104

- A new session no longer opens on "Compressing context". The agent's turn-1 notice that it had
  lowered the compression threshold ("⚠ Compression model … Auto-lowered …") reaches the phone
  tagged `compacting` by the gateway, and no `ready` follows it. A warning or error is never
  compaction progress now, whatever its tag; a turn ending clears the status line; Tap-In's
  headline takes a compaction only, not any status line that happened to stick.
- Tap-In's Mind region fades its older thought by masking the text, not by painting a
  `surface`-coloured band over the dusk sky — the band read as a bar across the cloud.
- The home-screen widget no longer freezes on a stale frame ("working" after the turn ended):
  Glance recomposes a live session on refresh without re-reading, so the facts now come in
  through a flow the card collects.
- Retry re-sends the newest prompt only when it is plain text, and only when the undo actually
  took a turn back. An uncaptioned image no longer lets an older question stand in for it.
- A helper's steer / stop / tail are bound to the room the sheet was opened from, and the sheet
  closes when the room changes under it. A wing the gateway never named (no `subagent_id`) can
  be watched but not addressed.
- A watched helper's session is let go (`session.close`) once the helper has landed and the last
  reader stops — never while it flies, since closing finalizes the session it is writing to.
- The artifact viewer hands off only links a finger tapped, to `http`, `https` or `mailto`; a
  script or meta-refresh can no longer fire `tel:`, `market:` or an app deep link. It reads the
  gateway client per load, so Reload follows a gateway switch.
- `~/out/page.html` in prose no longer makes a dead `/out/page.html` chip.

## 2.13.1 · versionCode 103

- A `MEDIA:` mention is only a hand-off when its value looks like an address (`/…`, `~/…`, a
  drive, a URL). An agent describing the convention — "`MEDIA:<absolute path>`", "in MEDIA:
  form" — had grown dead file chips under its reply (device, the artifact test).

## 2.13.0 · versionCode 102

- Steer from inside Tap-In: a bar under the space while the turn runs — type to steer, hold to
  queue, empty to stop — the chat composer's grammar, in the view built for watching.
- A helper's mind: tap a flying crew card on the direct door and the sheet opens on the child's
  own session (the gateway's watch window mirrors its thinking, words and tools), with what ran
  before folded above. Under it, a word in that helper's ear (`subagent.steer`) and a square that
  stops that helper alone (`subagent.interrupt`), with a confirm. Before this the card could only
  show tool names — the parent wire drops a child's text on purpose. See
  [features/tap-in.md](features/tap-in.md).
- Home screen: a widget (link, last session and reply, the run in flight; tap → the session, or
  Tap-In while it runs) and a Quick Settings tile that floats a one-line note to the agent over
  whatever you were doing. See [features/home-screen.md](features/home-screen.md).
- Artifact viewer: an `.html` the agent wrote — sent as media, or named by path on the direct door
  — opens in a fenced WebView inside the app, with save, share and open-with. See
  [features/spaces.md](features/spaces.md#artifact).

## 2.12.0 · versionCode 101

- Tap-In: tap the working banner, long-press the newest run, or tap the run notice, and the turn
  in flight opens full screen — headline, mind, crew, rail, instruments. A projection of what the
  transcript already holds; nothing new on the wire. See [features/tap-in.md](features/tap-in.md).
- The crew: every helper a turn sends out is a card — kind, role, model, tools, elapsed, tokens, the
  line it is on while it flies, the summary it lands with — and a door onto its own window.

## 2.11.9 · no tag (versionCode 100)

- The long-press bar copies what the eye saw (markers gone, cite refs as superscripts), stacks into
  two rows so undo and delete stop clipping on narrow phones, and gains Retry beside Undo — the
  Desktop's `/retry`: take the exchange back, say it again. The emoji stagger honours reduced motion.

## 2.11.8 · versionCode 99

- The composer stands on its own floor: a scrolled-up transcript no longer reads through the
  placeholder and the model row. Its ground is held to AA by the same contrast test as the flight plan.
- On the direct door a finished reply no longer fades out beside its own copy. A live turn's rows keep
  one name from first token to the re-read (`LiveTurnIds`), and no longer parse as gateway row ids.
- The documentation section: this `docs/` folder, the screenshots, and a README that leads into them.

## 2.11.7 · no tag (versionCode 98)

- A slash command no longer leaves the room reading `steer`, and the command palette sits on the
  composer.
- The README sends people to the plugin that works, not the patch that stopped applying.
- CI sets up the Android SDK without the package that no longer exists on runners.
- The shade stops shouting: one run notice, one alert per turn, a colour that means something.
- Builds run on a build host, and the signing key travels with them.
- The reasoning dial learns DeepSeek-V4.1: graded local templates share one table.

## 2.11.6 · tag `v2.11.6`

- The review shows with the footer off, and the ring is lit when a room opens.
- A link inside bold opens: structured bold and strike go back to the renderer.
- A new chat opens on a model the gateway still serves, and the GLM dial is real.
- A report you read stays in Runs; a report you answer joins the list.
- A subagent's work is kept, and a room follows a turn it did not start.

## 2.11.0

- **Fleet**, multi-gateway on one phone: a named registry of gateway rows, one Primary, one active,
  dedup on the normalised URL, unique device names, the selector only when there is a choice, and a
  cold-start rule that resumes the last-used gateway by default (the phone's answer to a process the OS
  kills all day). Per-gateway credential and ledger scoping so rows do not cross-contaminate.
- Call-pace work carried from the 2.10 pass.

## 2.10.0 · no tag

The Nous pass. The settings go flat, searchable and fewer; a settings row cannot be anchored without
being searchable. The machine gets one door and the drawer gets a floor. The drawer learns to filter,
archive and jump. The archive reads across sessions on the direct door. Two god files become six. The
numbers the phase asked for were measured. A failed turn says which layer broke, the ring says what
fills it, and the last exchange can be taken back. The Gate speaks: a stopped agent, answerable from
the shade. A call that can be heard. Instruments stop covering each other, and the accent becomes a
light instead of an ink. The palette was split out of `ChatScreen` with zero behaviour change.

## 2.9.x · tags `v2.9.0`, `v2.9.3`

- The roster gets shelves; one ink for every stop of the sunset; the first walk written down.
- Project sessions stay in Projects, the roster reaches back, and a new session knows its model.
- GIF handling settled: tap the picture to get its address, copy the GIF not its address, the address
  of a GIF is a GIF.
- The floor rises to where the code already stood.

## 2.8.x · no tags (2.8.0, 2.8.1, 2.8.2, 2.8.3 known from plan-doc headers and commits)

- **Bots (2.8.0)**: a bot is a profile, each one tap from its forever-chat; the Bots door with an
  active-now strip, activity-ordered roster in each bot's own light, search, pin-to-top tiles, new agent
  with clone-from, routines. The canonical Bot Chat opens by exact title on the bot's own profile. Every
  session call names its profile on the wire. `/new` reroutes to `/compact` inside a forever-chat, and
  `@`-mention chips plus the identification note let a bot hand off.
- **The hands (2.8.1)**: `⟦keryx:do|kind|args⟧` tiles performing phone acts on a tap, in the bubble and on
  the lock-screen notice, through system intents only, the tap as the whole consent model. A self-sorting
  model picker drawn from a pure tested plan (this machine first, cloud logins flat and flagship-first,
  aggregators split by lab with the tail folded, recents, search, prices, free-tier gating). One motion
  pass on shared springs. Two bugs closed: a tapped scheduled run stops double-notifying, and a new
  session from the drawer lands in it.
- **Instruments (2.8.2)**: the flight plan stands on a real floor at 0.94 surface alpha with a hairline
  edge, held to WCAG AA over the worst backdrop each theme can produce; the drawer orders by the newest
  activity the app knows of from either side, with 5 s slack so a token stream never rebuilds the roster;
  the context ring lights on the direct door off the gateway's own usage.
- **2.8.3**: the working banner survives a compaction. The hold is open-ended and re-armed when the
  compaction ends, so quiet is counted from the moment work actually stopped, bounded only so a lost
  `done` cannot strand the banner forever. A mid-stream drop deliberately does not lift the hold.
- Long messages stop lagging on the swipe: a settled body's markdown tree is parsed once, warmed off the
  main thread, and served from a content-keyed cache.

## 2.6.x · tags `v2.6.0`, `v2.6.1` (2.6.2 has no tag)

- The direct door stops speaking Matrix: sessions pin on the gateway, unread is the gateway's read
  watermark, drawer previews stop hydrating fifty live agents, the top-bar plus opens a real session,
  and one `DoorLexicon` per door says room or session everywhere the noun appears.
- The reasoning dial belongs to the session: `/keryx/capabilities` scopes to one brain and reads the
  provider's own effort tables, so a session gets its model's ladder rather than the local brain's.
- The Runs door keeps what you keep: a run pins on the gateway (the desktop's keep flag), a Pinned shelf
  above the arrivals rail, one long-press menu on every row, and the page asks the API server for
  `source=cron` so 150 rows are 150 runs.
- Cron tiles: a scheduled job pins to the top of the session list the way a Quick Room does, with the
  deck as a pure function of cards, ledger and unread.
- The forty-first report says what changed: each job card reads "since last run · +3 new · 1 updated ·
  2 gone", with lines compared by key and near-matches counted as updated. The ambient void's accent
  pools move into the dusk-sky shader so the OLED stops showing a hard edge.
- Code fences stop crashing the app: the highlighted-code composable's second horizontal scroller was
  measured at infinite width, which Compose refuses; the colour is now the tokenizer's spans laid over
  Keryx's own text in Keryx's own scroller (`e1ae954`).

## 2.5.x · tags `v2.5.0` through `v2.5.6`

- The ship gate lands: three verdicts, with AMBER meaning a stage could not run so nothing was learned.
  The on-device canary covers the crash class the JVM cannot see.
- The `Math` unicode pass survives Android's regex engine: ICU rejects a bare `}` the JVM accepts, the
  converter's class-init threw, and every rendered message killed the app with a green test suite
  (`fd8bf29`).
- Compression and payloads: every `end` tool frame carries its result, clipped from the middle, with
  `result_len` the unclipped size. The compaction status mirror gets its own frame kind.
- The design pass, the direct-door pass, and the steer/picker control pass.

## 2.4.0 · tag `v2.4.0`

- **The tool theater**: each tool as it starts, how long it took, what failed and why, calls fired in one
  turn grouped on a rail. Subagents get their own wings (goal, model, tool count, duration, token cost,
  summary). Deliberately quiet, since the committed reply renders the same calls a moment later.

## 2.3.x · no tag

- **The Council**: several agents, one room, each its own light. A stable hue and sigil per agent,
  carried by the bubble rim, the name and the spinner. A relayed agent renders as an attributed notice;
  a turn nobody asked for is an *arrival*.

## 2.1.1 / 2.0 · tag `v2.1.1`, no 2.0 tag

- **The dream rebuild.** A single nav spine over the chat floor with a gesture-scrubbed transition, one
  attention budget for ornaments, the haptic grammar, the assistant doorway, the share target, and the
  local-first crash log.

## 1.x line · tags `v1.15.1` through `v1.26.2`

- Dual-tier streaming: a transient SSE side-channel with one committed Matrix message per turn, and a
  smart-throttled edit fallback.
- The agent-output parser: folded reasoning canvases, grouped tool run cards, Action Output cards, quiet
  telemetry footers.
- Markdown that holds up: GFM grids, scrollable code with copy, healed fences.
- Hermex-native controls: the reasoning menu, the slash palette with recents, steer, the link-health dot.
- Actionable notifications and the hands markers, with the tap as consent.
- The self-sorting model picker, off the gateway's catalog.
- Self-contained push with no distributor app, `event_id_only` payloads.
- The share-sheet target with MSC2530 captions.
- The Archive: a local FTS4 index, date jump, media gallery, live context window.
- The Missions board, the Skill Forge, the session pruner, toolset toggles, the pet endpoints, the Run
  Console, the Gateway/Workshop panels, and the fleet's precursor plumbing.

## Non-version tags

- `pre-rebase/keryx-2.7-wake-word` — the wake-word tree before the rebase; 2.7 itself carries no tag.
- `archive/hub-hermes-update` — the hub's hermes-update panel at its archive point.
