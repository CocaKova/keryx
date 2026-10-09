# Changelog

Newest first. Built from `git tag`, `git log`, and the version headers in the build-plan notes, with
each entry's facts cross-checked against the shipped source. Some versions never got a tag; those are
marked, and their numbers come from the version header in their own plan doc plus the commit that names
them.

## 2.18.1 · versionCode 129

- The Flight Plan strip shows again. Hermes renamed its `todo` tool to `todo_list` (the old name
  stays as an alias, and a deferred call arrives through `tool_call`), and Keryx was still
  matching the exact name `todo`, so new sessions never showed their plan. All three names are
  recognised now.

## 2.18.0 · versionCode 128

Group chats, and Bot Chats that read like conversations. Ships together with 2.17.3, which was
never released on its own.

**Group chats**
- Bots has a Group chats section: each room with its members' sigils and newest line, plus New
  group chat (a name and 2 to 6 of your bots). These are the gateway's hosted rooms, so a room
  keeps going with the app closed.
- A room is one conversation. Your words sit on the right; each member's sit on the left in its
  own light, as markdown. Long-press a message to reply in its thread.
- "@juno ..." sends to just the members you name; chips fill in the handles, and @all reaches
  everyone.
- "... is thinking" shows from your message until the room settles, with the chat's thinking
  animation. Stop cancels the room's work. A member's command approval is answered in the room
  (Allow once / Deny), and a turn that ended unclear can be retried.
- Rename, and Disband (asks first).
- The gateway does not push room events, so an open room checks for news every 1.5 s while
  anyone is working and every 5 s when quiet, and not at all once you leave it or Keryx goes to
  the background. No notifications for rooms yet.

**Bot Chats**
- When one bot messages another, the chat shows "Messaged @milo" and the words sent, on the page
  instead of folded into the tool run. The reply comes back as a message from that bot, not as a
  background-process notice. A message still waiting for an answer keeps the gateway's own notice
  instead of posing as a reply.
- Fix: a message relayed from another bot drew an empty bubble on the direct door.
- Fix: a bot's reply came through empty because the delivery printed its session id after the
  answer, not before.

## 2.17.3 · versionCode 125 (not released on its own; ships in 2.18.0)

**Sessions as any agent**
- The top-bar + starts the new session as the agent of the session you are in. Holding + opens
  the sheet with an Agent picker. Long-press a bot in Bots for New side session.
- Sessions an app started on another agent show up in the drawer wearing that agent's sigil.
- Keryx remembers which agent holds each session, so a relaunch reopens it in the right place.
- Fix: a bot's chat that compacted came back empty, because the new tip was read from the wrong
  agent's store.

**Bot Chats**
- /new or /reset in a Bot Chat asks: Compact (same conversation, clean context) or Fresh Bot
  Chat. It used to quietly compact.
- A fresh Bot Chat retires the old one as "Bot Chat · retired <when>". Long-press a bot for
  Sessions to find its past Bot Chats, side sessions and runs.
- A reply that is only a silence marker ([SILENT], NO_REPLY, ...) shows nothing and raises no
  notification when another bot or a background job started the turn. A turn you started always
  shows its answer.

**Empty-chat starters**
- Fix: the starter prompts in an empty chat could not be tapped.
- Reminder chips come first: missions that need you, chats with unread replies, and a routine
  due within 12 hours.
- Optional prompts written from your own recent chats (needs keryx-stream's
  `/keryx/suggestions`). Off by default: on a paid cloud model each refresh is a small billed
  call. Settings > Agent > Suggestions in empty chats.

## 2.17.2 · versionCode 124

- Keryx no longer gets closed out from under you. Android's automatic cloud backup tried to copy
  all of Keryx's storage to Google Drive, hit the 25 MB per-app limit, and then (as Android always
  does when a backup ends) killed the process, even with Keryx open on screen. Seen on the device
  on 10-04: `Transport quota exceeded for package: chat.keryx.app`, then `Killing agent host
  process`, then the window died. Keryx now opts out of cloud backup. Your conversations live on
  the gateway, so nothing is lost, and the gateway login token no longer gets copied to Drive.

## 2.17.1 · versionCode 123

Fixes from the first day on 2.17.0.

- Much less data while Keryx is open. Every "the session store moved" signal re-read fifty
  conversations and a hundred scheduled runs (about a quarter of a megabyte), and a turn running
  anywhere sends one every couple of seconds, so a phone watching Sy work pulled megabytes a
  minute (10–20 MB per two hours on screen, measured on the device). Now a signal reads the top
  eight of each and merges them into the list already held, at most every four seconds; the full
  read runs at most every ninety seconds, which is what still catches deletions and archives.
- Watching a session that moves elsewhere (a run, the desktop, another client) reads only the
  rows that just appeared instead of the whole 120-row page each time it grows.
- The model and reasoning pills come back after the gateway restarts; they used to stay hidden
  until the app was reopened.
- Opening a collapsible block or a thought keeps its header on screen. The chat is anchored to
  the bottom, so a section growing pushed the line you tapped off the top, which read as the tap
  doing nothing. A collapsible block also stays open when it scrolls away and back.
- The context ring sits beside the reasoning pill instead of directly under the send/stop
  button, where reaching for it mid-turn could land on Stop.
- Edge cases from a hostile-input pass, all now in the on-device canary: a half-written
  fraction (`\frac{1}{`) crashed the math layout; it draws an empty denominator. A code fence
  inside a collapsible block closed the block early and swallowed the rest of the message;
  nested fences and longer (````) outer fences work. Chart numbers past ±10¹⁵ read as gaps
  (a chart of nothing else shows as code) and an overflowing axis can no longer throw. A
  palette keeps its good colours when one line is a typo. A slice under half a percent reads
  "<1%". A CSV byte-order mark no longer lands in the first header.

## 2.17.0 · versionCode 122

"Beautiful to be in." A rendering pass: what the chat already drew looks finished, the paragraph
being written no longer arrives raw, and the agent has new things to draw with.

**The renderer**
- The markdown renderer moves from 0.35 to 0.45 (Kotlin 2.4.10, compileSdk 37, AGP 9.3.3, Gradle
  9.7.1; the app module builds on Java 21 because the renderer and the highlighter now ship Java 21
  bytecode). GitHub alerts (`> [!NOTE]` and friends) render as callouts.
- Every markdown slot is themed from the bubble's own ink: quotes, lists, links (leaning toward the
  accent but always legible on the bubble), inline code on a rounded pill, dividers.
- The paragraph still streaming is formatted as it types, with its fade kept: bold, code, links,
  headings, bullets and quotes appear styled and their markers hidden, instead of raw asterisks that
  snapped into place when the paragraph finished.
- Tables size each column to its content, honour `:--:` alignment, keep a pipe inside code or `\|`
  in its cell, carry links, italics, code and inline math, stripe their rows, and sort by a tap on a
  header (numbers as numbers).
- A settled thought reads as light markdown.

**Math**
- Display math is typeset: `$$…$$` or `\[…\]` on its own lines and ` ```math ` fences go through a
  pure-Compose engine ported from Kai (Apache-2.0, see THIRD-PARTY.md): stacked fractions, radicals,
  limits, matrices, `cases`. It replaces the `⟦ … ⟧` paragraph 2.6.2 left behind, which nothing ever
  drew, so readers saw the brackets.
- The inline Unicode transform reads nested braces (`\frac{-b \pm \sqrt{b^2-4ac}}{2a}`),
  `\mathbb`/`\mathcal`, accents, escapes, environments and a wider symbol set, and still leaves
  `$5 and $10` and `$HOME` alone.
- The 2.16 changelog said no light math renderer exists for Compose; that was wrong.

**Rich blocks the agent can write**
- ` ```chart ` (bar, horizontal bar, line, area, pie, donut from a small JSON spec, drawn on Canvas
  with a one-time draw-in), ` ```diff `, ` ```timeline `, ` ```progress `, ` ```swatch `, ` ```card `,
  ` ```details `, ` ```svg `, ` ```csv ` / ` ```tsv `. A body that doesn't parse, or a fence still
  streaming in, shows as a code block. keryx-stream teaches the agent the list in its own prompt
  section.
- The Hermes desktop app's `::preview{file=…}` line reads as the file's name, with the artifact chip
  under the reply.

**Lighter**
- Mermaid lines no longer breathe on an infinite animation that recomposed every visible diagram on
  every frame; a diagram scrolls sideways only when it is wider than the bubble, and a diagram type
  the parser doesn't model shows as a proper code block.
- The markdown annotator is remembered instead of rebuilt on every recomposition; the parse-ahead
  warmer skips bodies it has already warmed; the footer check no longer makes the per-tick parse
  quadratic.
- The skill viewer renders through the chat's own path instead of the async overload that flashed
  an empty box.

## 2.16.1 · versionCode 121

Fixes from the first walk of 2.16.0.

- Tap-In no longer crashes. Its arrival out of the working cloud rode a spring that overshoots,
  and past the end the rounded corner went below zero, which Compose refuses. The grow is
  clamped to 0..1.
- The working cloud is clean again. Its glow, rim and fill are each two dozen overlapping
  circles under a pill, painted translucent one shape at a time, so every overlap stacked: a ring
  of ghost bumps past the rim and a box behind the label. 2.16.0's clearing added two more such
  passes, which is what read as after-effects. Each pass is now painted opaque in its own layer
  and faded as one piece, the gradients run across the whole shape, and the clearing is gone.
- The live rate reads in tokens from the first turn: "≈ N tok/s" through the default 4 chars per
  token until a real count calibrates the ratio, never "chars/s".
- The reasoning menu is smaller: capped at 220 dp wide, the model line on one row, shorter rows,
  so it sits by its pill instead of hanging off the side. X-High's five-block ladder no longer
  wraps a block onto a second line.

## 2.16.0 · versionCode 120

"The herald hears everything." Two rounds of work: everything that moves now reads something
real, and the gateway's unused verbs reach the phone.

**Honest numbers**
- Tokens per second tell the truth. Live, in the working cloud and Tap-In, the character rate is
  read through a chars-per-token ratio measured against the gateway's own output counter on an
  earlier single-call turn, marked "≈", and it falls toward zero while the model stalls. Before
  any real count has calibrated it, the readout stays in chars/s. A finished turn shows its real
  rate beside the clock (the counter's move over the time the text was flowing). The context
  sheet reads the gateway's own average. The "≈ tok/s" under the Matrix live bubble, which was
  characters divided by four, is gone.
- The context sheet reads the run numbers the gateway was already sending: prefix cache, tok/s
  average, compactions, latency per call, the session's token totals; the mid-turn usage tick is
  handled, so the ring moves during a turn.

**The gateway's other hands**
- Goal strip: a session's standing goal rides the instrument rail under the flight plan (turns
  used of the max, gates going green, the verdict stamped) with pause, resume and clear;
  Tap-In shows it above its headline. Needs `session.control.*` (stock Hermes); a gateway
  without it shows nothing.
- Hold Steer while a turn runs: Queue, Ask aside (`prompt.btw`: answered in a framed aside under
  the chat that never enters the history) or Redirect (`session.redirect`: the reply starts over
  with your correction; retired on an agent that cannot).
- Branch from here: any saved line can start a new session holding the history up to it
  (`session.branch`), opened with the room-switch dissolve.
- Edit & resend: your own message goes back in the composer; on your newest message the
  exchange is taken back first.
- A dropped socket replays the frames it missed (`session.events.since`) instead of re-reading
  the transcript, and reloads only when the gateway says the ring was truncated.

**Places**
- Runs makes and changes jobs: a schedule picker instead of a raw cron line, a delivery-targets
  picker instead of a raw `matrix:<room>` string, blueprints, and each job's run history. The
  Hub's Jobs spoke shares the same editor.
- Missions: a new mission takes a priority and goal mode; the board has search and owner chips;
  a card's title, brief, priority and owner can be edited after it is made (where the
  dashboard's kanban plugin answers). Cards whose face changed since you last closed the board
  take one pass of light when it opens.
- The Hub reads what the agent remembers (Memory spoke) and can update Hermes again (Update
  spoke: check, receipt, start behind a confirm, follow the log, the operator's preflight).
- A session can be exported (Markdown or JSON) to the share sheet from the drawer.
- Tap-In is a layer on the nav stack, not its own Dialog window: the back gesture scrubs it
  predictively, and it grows out of the working cloud's bounds. Its rail follows new calls while
  you are at the bottom, and the answer renders as markdown.

**Chat**
- One type scale (`KeryxType`) across the app, with a guard test; its smallest step is 11 sp.
- A model switch is a divider naming the model, not two agent bubbles. A command's exit code is
  a chip beside its output instead of a JSON envelope.
- Up to six photos or files at once, or a shot from the system camera; the direct door sends
  them as one turn. Find in chat. Select text from any message. An empty session offers a few
  ways in. Every reasoning level has one spelling on every surface.
- An artifact opened by path loads its CSS, scripts, images and sibling pages from its own
  folder on the gateway, and nothing outside that folder.
- Themed notices replace system Toasts: they sit above the composer (and Tap-In's steer bar),
  never repeat themselves, and the reversible verbs offer Undo.
- TalkBack hears the streaming reply, the working cloud and an approval waiting on you.
- LaTeX stays the Unicode transform: no light renderer exists for Compose.

**Motion that reads something**
- The sand under a streaming reply pours at the live token rate and stops on a stall.
- The working cloud stands on its own floor and wears the running tool's colour on its rim.
- Three short ticks when an approval, question or sudo prompt lands, distinct from the turn's
  two.
- The context ring is gilded inside while the prefix cache is warm (≥ 80 %), drains toward empty
  over the typical compaction length while one runs, and snaps back with one tick.
- A running session's drawer row breathes in its own light; a row that turns unread takes one
  pass of light.

**Platform**
- The locked phone cannot approve a command from the shade: Approve, Always and This session ask
  for the device unlock (Android 12+); Deny stays one tap.
- `keryx://` links (session, tapped-in session, new chat, composer, Missions or a card, Runs,
  Archive, Projects, Bots), launcher shortcuts (New chat, Missions, Runs), and conversation
  shortcuts for sessions that notify or are opened, so notifications land in the People section.
- Android 16: the run notice asks to be a Live Update and draws the flight plan as progress
  segments (system permitting; the Samsung Now Bar is unverified).
- The widget says how many requests wait on you, and the picker shows a real preview.

**Not in 2.16**
- Switching gateways still restarts the process: the transport, its coroutines and the app's
  notification observers are built once per process; doing it in place needs a scoped transport
  with a shutdown and observer restarts, which needs a device walk to trust.
- The wake word stays on its branch.

## 2.15.0 · versionCode 119

- Mission alerts cross gateways. The background watcher only ever checked the gateway the app
  was standing on, so a mission that finished, blocked or gave up on any other gateway in your
  list stayed silent until you switched to it, and switching away froze the old gateway's place
  in its event feed. The watcher now checks every gateway you have added, each against its own
  feed, at the same 15-minute floor; one gateway being down no longer holds up the rest. With
  more than one gateway the alert names the gateway it came from, and tapping it switches there
  and opens the card. Message notifications still follow the active gateway only: one live
  connection, not one per gateway.
- The same task id on two gateways gets two alerts instead of one replacing the other.
- An event more than a day old is taken as history and consumed without ringing, so a gateway
  picked up from an old place in its feed does not replay last month's completions.

## 2.14.1 · versionCode 118

Hotfix.

- A chat you have read stays read. When a reply streamed in, the read mark was sent as soon as
  the reply started and never again, because the finished reply kept the same message id. The
  gateway then saw the answer land after your last read, and the session showed unread until the
  app was restarted, however many times you opened it. The mark now waits for the reply to
  finish, and opening a session the gateway still calls unread always marks it read.

## 2.14.0 · versionCode 117

Missions becomes a place you can act from, and Runs a place you can fix from. Carries 2.13.11
(compaction) and 2.13.12 (paste images), neither of which was released alone.

- Missions says what it needs. A blocked card used to arrive as a red dot. The board now has a
  pinned "Needs you" lane, and each card shows its ask, whose move it is and a diagnostics badge.
  The sheet opens on "What it needs from you", with the ask and an answer box: Reply & unblock,
  or Approve / Request changes for a card in review. Below that are the runs, the diagnostics
  (the rules `hermes kanban diag` runs), parent/child chips that walk the graph, and a folded
  event log. A mission alert in the shade carries the block reason.
- Your moves. A "Your moves" row under the ask offers what the card's status allows: Start it,
  Unblock (no reply needed), Stop this run, Hand to…, Pause (with a reason the next run reads),
  Mark done, Archive. Anything that ends a run, closes a card or needs a reason or a profile asks
  first. The toast says where the card landed, or the gateway's refusal in its own words.
- Sweep the board: long-press to select several cards, archive them together, or "Clear done".
- A needs-you orb on the drawer door and a badge on the top-bar menu count the cards waiting on
  you. While the app is open, the board and mission alerts are checked every 60 s, not only by
  the 15-minute background worker; a mission notice opens its card.
- A run card opens that worker's transcript, read-only and live, with Open in chat.
- Runs sorts itself: Needs attention, Recent, Quiet, with search, filter chips and a Cards/List
  switch (List once there are more than 12 jobs). Script-only jobs fold into a "Background
  scripts" shelf unless they are failing. A job's sheet shows the whole error with its one-line
  gist, and offers Copy, Ask agent to fix (a new chat with the brief filled in), Run now,
  Pause/Resume, Pin and Delete.
- A stopped turn stands the composer down. After Stop, every later message went out as a steer
  and never landed, because one live-turn sign was never cleared.
- A pasted image becomes the attachment (2.13.12): long-press Paste, the keyboard's clipboard
  strip, a keyboard GIF or sticker, or a drag from another app lands in the same chip as a
  gallery pick.
- The working cloud has its scallops back (2.12's clip cut them off), and the composer shows a
  rim on focus and a shimmer while a turn runs.
- Fixes: a notice clears when you return to its chat; the archive sweep skips sessions the
  gateway no longer has; sheets stop bouncing on a fling.
- Gateway: Missions' ask, verdicts and diagnostics need keryx-stream 0.4.0. Your moves and the
  worker transcript need keryx-stream 0.5.0; on 0.4.0 a move answers "not found".

## 2.13.12 · versionCode 114 (not released alone; shipped in 2.14.0)

- A pasted image becomes the attachment. See 2.14.0.

## 2.13.11 · versionCode 113 (not released alone; shipped in 2.14.0)

- A chat follows its own compaction. Compaction ends a session and continues it under a new
  id, and two gaps kept the phone on the old one:
  - Opening a compacted chat resumed its continuation on the gateway, which says so in the
    reply (`session_key`); Keryx filed it under the old id anyway. The turn streamed into the
    new session while the transcript was read from the old one, so a message you sent vanished
    on the next refresh and came back only when the turn ended.
  - A compaction noticed mid-turn was announced inside the app and nothing listened, so the
    open chat kept the old session. It now moves to the continuation in place, turn and all.
- The context ring measures the way to compaction. It was drawn against the model's whole
  window, so it read about 60% the moment a session compacted. With a gateway that reports its
  trigger (`usage.compact_at`, a SILAS gateway patch), a full ring means the next call compacts,
  and the figure reads "31k to compaction". A stock gateway gets the ring it always had.
- The ring moves during a turn. Its reading arrived only when a turn ended, so a long agent run
  showed its starting number the whole way through. The open chat re-reads it the moment the
  number can have moved: when a model response lands (a tool starts), when a compaction ends,
  and just after you send; every 15 s as a floor.
- After a compaction the ring drops at once. The gateway stops reporting a reading until the next
  model call answers, so the ring held the full reading that triggered the compaction for that
  whole call. The gateway now reports its own estimate of the compacted size in that gap
  (`SILAS_POST_COMPACTION_ESTIMATE`).
- The compaction banner says how big the job is and how long it usually takes: "Compressing
  context (~141k tokens) · usually ~41 s". A compaction is one summarizing call with no progress
  of its own, so the phone keeps the last five durations it watched per model and shows the
  median, and says nothing until it has one.
- A compaction leaves a mark. The summary row at the top of a continued session was an agent
  bubble several thousand words long when the gateway stored it as the assistant. It is a
  divider now, "🗜 Context compacted · 09:56", and a tap opens the summary.

## 2.13.10 · versionCode 112

- Quiet in the pocket. Measured on 2.13.9: sitting in the background with no agent running,
  Keryx downloaded about 25 MB an hour, around the clock. Two causes, both gone:
  - The session list was re-downloaded every minute. The gateway's own heartbeat looked like a
    session change; that is fixed on the gateway, and Keryx now folds a burst of change
    notices into one refresh — every 2 s on screen, every 30 s off screen, and the moment you
    come back.
  - The last open chat kept polling every 20 s while the app was off screen, each poll the
    full session record. It pauses off screen and checks the instant you return.
- A reconnect re-reads only the chat you have open (and any whose turn was running). Other
  chats you touched this session reload when you open them, instead of all at once.
- The Runs and Bots places stop their fast polls when the app leaves the screen.
- The command palette sits clear of the text box. Typing `/` put its bottom 8dp inside the
  composer: the composer's height was measured inside its own padding, and the palette never
  added that padding back.

## 2.13.9 · versionCode 111

- A mission alert reports into a conversation, never into machinery. 2.13.8 subscribed
  whatever row was last open; the first alert Jonny set bound to the mission's own worker
  transcript, which he had just been reading — a report that would land where nobody looks.
  The gateway door now skips worker, cron, subagent, bot and one-shot rows for the newest
  conversation.
- "Alert when this ends" on the gateway door also arms the phone. A chat subscription only
  reaches the shade while that chat is open; the background mission watcher (Settings ▸
  Mission alerts, every 15 minutes) is what rings with the app closed — and it was off.
  Switching a card's alert on, or creating a mission with notify, turns it on and says so.

## 2.13.8 · versionCode 110

- Mission alerts work on the gateway door. The switch had been off there on the belief that
  no adapter could reach a gateway session; the gateway's own per-session poller can — it
  reads subscriptions keyed `platform=tui, chat_id=<session id>` and hands the event to that
  session as a turn, so the agent reports the mission's end in the chat you last had open
  (at once if it is open, on its next resume if not). The Matrix door is unchanged.
- A pinned cron job and a pinned bot are gateway rows, and now only sit in the gateway
  roster. The hub keeps them across doors, so a pinned job tile ("Bi-Weekly Date Night")
  showed at the top of the Matrix list too, where its tap opened nothing.

## 2.13.7 · versionCode 109

- A photo you sent comes back as a photo. The gateway stores a composer image as the caption
  plus one `@image:<path>` line per file; Keryx lifted only the agent's `MEDIA:` tags into
  media bubbles, so a reloaded session showed your caption with an absolute path under it
  where the picture had been. Those lines are now lifted the same way (thumbnail on your
  side, fetched over `/api/files/download`); a sentence *about* the convention stays prose.
  The live echo bubble was already right; only the reload was wrong.

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
