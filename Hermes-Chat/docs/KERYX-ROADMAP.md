# Keryx — roadmap

**Written 2026-08-19, after 2.4 walked on device. Nothing here is committed to; the ordering is
the argument.**

## Where it stands

Four movements landed in nineteen days. 2.0 gave the app a visual language, 2.1 gave the Hub a
voice back, 2.3 gave a room full of agents separate lives, 2.4 showed what the agent is *doing*
while it does it — tools, verdicts, durations, diffs, subagent wings.

All four made Keryx a better **window**. You can now watch an agent work on a real machine from a
phone, in more detail than the machine's own terminal shows, and read the same turn back a week
later out of a local FTS index.

What you cannot do from that window is **act on the work**. Three concrete holes, each of which is
already a seam somewhere in the stack:

- The room cannot unblock the agent. A dangerous command posts a text message with an emoji
  legend and waits 300 seconds; Keryx renders it as chat. The Hub's console path has a real
  approval gate (`ConsoleApproval`, `POST /v1/runs/{id}/approval`) — the room, the surface that is
  supposedly the truth, does not.
- The work the agent produced is unreachable. The gateway has a complete git review surface
  (`hermes_cli/web_git.py`: diff, stage, commit, push, PR) and Keryx touches none of it. You watch
  an agent write four files and then walk to a desk to look at them.
- The agent does not exist when the app is closed. There is no widget, no ongoing activity, no
  cross-room "this needs you". A five-minute approval window and a notification you have to be
  looking for are not compatible.

The through-line for the next three: **the window becomes a control room.** Consent, consequence,
presence.

---

## 0. Ship what is already built — 2.4.0

`versionName` is **2.1.1** and `versionCode` **55**. 2.2, 2.3 and 2.4 are committed and walked;
the gilded void, the Council and the tool theater are in nobody's hands, including the phone in
Jonny's pocket running a DEBUG build.

Cut **2.4.0** (one release, three minors of notes) before starting anything below. Per
[[feedback_slow_version_cadence]] minors are for milestones and these are three of them; the
release notes write themselves out of the three plan docs.

⚠️ The gateway half of 2.4 (`gateway/keryx_stream.py`) is a SILAS_EXT payload. A release APK in
the wild that expects `tool` frames from a gateway that has been reinstalled without the payload
degrades to 2.3 behaviour silently — which is correct, but say so in the notes.

---

## 1. 2.5 — The Gate

*The agent asks. You are not at the desk. It waits five minutes and dies.*

### Why this stands out

Every AI chat app on a phone shows you what a model said. Keryx would be the place where an agent
running unattended on a real machine **asks permission and gets an answer from a lock screen**.
That is not a nicer transcript, it is the app becoming load-bearing: without it, the turn fails.

It is also the natural close of 2.4. The theater made the means visible; a veto is what visibility
is *for*.

### The seam that already exists

`tools/approval.py` is a first-class async gate with a client-facing registry:
`register_gateway_notify(session_key, cb)`, `get_pending_gateway_approval`,
`resolve_gateway_approval(session_key, choice)`, `ack_gateway_approval`. Six platform adapters
already drive it (Matrix, Slack, Discord, Telegram, Teams, Feishu). The choice vocabulary is
`once` · `session` · `always` · `deny`.

Matrix's rendering of it (`plugins/platforms/matrix/adapter.py:send_exec_approval`) is a text
message — `⚠️ **Dangerous command requires approval**`, a fenced command, a reaction legend — plus
seeded reactions ✅ 🌀 ♾️ ❎ and a 300 s expiry (`_approval_timeout_seconds`, default at
adapter.py:1326).

### The two halves

**App, the honest path.** `Message.kt` / `MessageParser.kt` get an approval kind; the card renders
the command in the code-block treatment that already exists, the four scopes as tiles (the
`⟦keryx:ask⟧` decision tiles in `KeryxMarkers.kt` are the right visual, already built), and a
countdown from the expiry. Answering sends the reaction via `ChatRepository.react` — the transport
that doesn't pollute the room — with `!approve` / `!deny` text as the fallback the prompt itself
documents. `KeryxNotifications` posts it at high priority with the four scopes as actions, through
the existing `NotificationActionReceiver`.

⚠️ Detecting it from the message text is sniffing a header string, and
[[feedback_no_incident_hardcoding]] applies: the invariant is "the gateway is blocked on a
decision", not "the string `⚠️ **Dangerous command requires approval**`". Text detection is the
compatibility floor — it has to work against an unpatched gateway — but it should not be the
primary path.

**Gateway, the right path.** `keryx_stream.py` calls `register_gateway_notify` for the turn's
session and emits an `approval` frame on `/keryx/stream`, in the shape 2.4 established: phase,
command, description, the allowed scopes, `expires_at`, `request_id`. `POST /keryx/approval`
resolves it. No text sniffing, no reaction round-trip, and the resolve is idempotent because the
registry already dedupes by request id.

Then the room card and the console card are one component driven by one model — the same move
`ToolGrammar` made for tool rows in 2.4, and for the same reason: two surfaces describing one
event will drift apart within a version.

⚠️ The prompt can be resolved by another client, or expire, while the card is on screen. The
registry's `list_gateway_approvals` is the truth; a card whose request id is gone renders as
*answered elsewhere*, never as a live button.

**Effort:** small-to-medium. One frame type, one endpoint, one card, one notification. Both halves
are additive and each degrades cleanly without the other.

---

## 2. 2.6 — The Forge

*Review the agent's work and ship it, from a phone.*

### Why this stands out

This is the flagship. "My agent wrote it, I read the diff on the couch, hit ship, and it's a PR"
is a sentence no other Matrix client and — as far as anything in this stack has seen — no other
phone agent client can say. It is also exactly how this project already works: SILAS commits on
the Spark, and the review currently requires a desk.

### The seam that already exists

`hermes_cli/web_git.py` is a pure library, 30 KB, no FastAPI in it: `repo_status`, `review_list`,
`review_diff`, `review_stage`/`unstage`/`revert`, `review_commit(message, push)`, `review_push`,
`review_commit_context`, `review_ship_info`, `review_pr_list`, `review_create_pr`, `worktree_*`.
The dashboard's `/api/git/*` routes are a thin wrapper over it (`hermes_cli/web_routers/git.py`)
— and they live on the **dashboard** app behind cookie auth, not on the `:8642` API server Keryx
talks to. So this is a new `/keryx/git/*` surface in `keryx_stream.py` over the same library, not
a proxy.

2.4 already did the hard rendering half. The diff panel, the 24-bit-ANSI strip, the `+n −n`
counting from the pre-clip body, the `┊ review diff` / `a/… → b/…` chrome skipping — all of it
exists and is tested (`TheaterTest`). A Forge diff view is that renderer given a whole file
instead of a tool row.

### Shape

A Hub tab, or its own screen off the drawer — the Hub is already five tabs and a sheet, and this
is a *place*, not a panel. Repo picker (the gateway knows its worktrees), changed-file list with
per-file `+n −n`, tap a file for the full diff, stage/unstage/revert per file, a commit sheet
pre-filled from `review_commit_context`, then push, then `create_pr`. `review_ship_info` and
`review_pr_list` give the "already open as #N" state so the button says the true thing.

### Traps worth writing down before starting

- ⚠️ **This surface writes to disk and to GitHub.** Every other `/keryx/*` endpoint is a read or a
  config poke. Revert and force-adjacent operations need a confirm that names the file, and
  `review_revert` on an uncommitted file destroys work no git object holds. Consider shipping
  read + stage + commit first and revert never, or last.
- ⚠️ The gateway runs as the agent's own user with the agent's `gh` credentials. A phone that can
  open a PR is a phone that can open a PR **as Jonny**. The API key is the only gate; `keryx.git`
  should be independently switchable off in gateway config, defaulting off.
- ⚠️ Diffs are unbounded. A 4-line file was the 2.4 test; a 3000-line generated file over a phone
  socket needs a server-side clip with an honest "clipped at N lines" (no silent truncation).
- ⚠️ Payload fragility again: this is a large addition to a REINSTALL-FRAGILE module. It may be
  the point at which the git surface deserves to be its own payload/plugin file with its own
  reapply entry, rather than growing `keryx_stream.py` past 3000 lines.

**Effort:** medium. The gateway half is mostly plumbing over tested functions; the app half is a
new screen but a known renderer.

---

## 3. 2.7 — The Presence

*The agent exists when the app is closed.*

### Why this stands out

The Gate has a five-minute fuse. Push notifications are already there, but a notification is a
thing you missed. What no agent client does is **live on the home screen**: a Glance widget
carrying the pet (`PetSprite.kt`, `/keryx/pet` — already built, currently only visible inside the
app), the current theater beat if a turn is running, the last arrival, and a dot per room that
wants something. Tap it and you are in that room at that moment.

Paired with an **ongoing activity** while a turn runs — the foreground-service notification
machinery already exists in `BuiltinPushService` (`setOngoing`, `FOREGROUND_SERVICE_SPECIAL_USE`
already in the manifest) — the phone stops being a place you check and becomes a place the agent
is. That is Keryx's whole thesis ("the room is the truth") extended one screen outward, and it is
the piece a desktop client structurally cannot copy.

### Shape

- Glance widget (`androidx.glance`), sizes: pet-only, pet + activity line, pet + activity + rooms.
- Ongoing activity for a live turn, fed by the same `LiveStream.theater` state the stage draws,
  collapsing to the newest beat.
- A **herald's desk** — one list across rooms of things waiting on *you*: pending approvals,
  `⟦keryx:ask⟧` decisions, arrivals not yet read, failed turns. `MissionsScreen` already owns the
  kanban half of "what is outstanding"; this is its sibling, or its second section.

### Traps

- ⚠️ Widgets update on the system's clock, not yours. Battery Saver must still the pet exactly as
  it stills every other ornament (the 2.0 attention budget is law), and the update path must not
  hold a socket open to keep a sprite animated.
- ⚠️ A widget renders in the launcher's process. Nothing from an E2EE room's plaintext belongs in
  it beyond what a notification already carries — `event_id_only` is the standing rule and a
  widget preview line is content.
- ⚠️ The desk is a second source of truth about unread state. It reads from the Archive index and
  the room state; it must never keep its own.

**Effort:** medium. Glance is new surface for this codebase; the state it needs all exists.

---

## Considered and parked

**The Eye — camera and screen into the agent's vision.** `vision` is enabled on the toolset
(cli + matrix, guard-durable) and the share sheet already lands images as MSC2530-captioned
turns, so "show the agent what you are looking at" is a shutter button and a caption, not a
feature. Genuinely mobile-only, cheap, and it wants no gateway work at all. Parked *above* its
weight — this is the strongest candidate to fold into 2.6 or 2.7 as a side item rather than a
movement of its own. ⚠️ >4 images in one prompt kills the turn ([[reference_vision_lab_traps]]).

**The phone as a tool host.** The inverse channel: the agent calls a tool that executes on the
phone (read a notification, take a photo, report location — `ACCESS_COARSE_LOCATION` is already in
the manifest). The most differentiating idea on this page and the least ready: it needs a durable
phone→gateway socket (the SSE side-channel is per-turn), a tool registration path, and a consent
model considerably more serious than The Gate's. Revisit after 2.5 exists, because The Gate is its
consent model.

**Desktop / Windows port.** Feasibility proven 2026-08-05, jpackage via GH Actions, ⚠️ no
linux-aarch64 libolm. Parked on strategy, not difficulty: see below.

**Persisting the theater.** Deliberately rejected in 2.4 and still right — the answer to "what did
that edit change" a week later is the file.

**Webhooks → Spire, A2A, watchdog-stall text.** The remainder of the v0.20 build-on list. Real,
small, none of them things a user would notice. Fold into whichever release is nearest.

---

## Two questions the roadmap can't answer

**Keryx and Talaria are converging.** Talaria is a native Android Hermes client talking straight to
the gateway (`source:"tui"`, WS turn events, its own `Delegation` model that 2.4 deliberately
matched field-for-field). Keryx is a Matrix client that has grown a gateway side-channel, a Hub
that drives `/v1/runs`, a sessions browser and — in 2.6 — a git surface. Both will soon show tool
theater, subagents and approvals over two different transports, maintained by one person.

The Gate makes this sharper, not looser: it has to be built twice, or built once in a place both
can use. Worth deciding *before* 2.5, not after: does Keryx stay the Matrix herald and Talaria own
the direct path, do they merge, or does one of them become the other's transport?

**The payload tax.** Eight reapply entries, `keryx_stream.py` at ~3000 lines living as a
copy-on-write payload, and the de-anchoring blocker is upstream PR #90367 (chat identity in the
stream hooks). 2.6 roughly doubles the gateway-side code. If that PR lands, most of this becomes
a plugin instead of a payload; if it doesn't, 2.6 should carry its own file rather than growing
the fragile one.

---

## The short version

Ship **2.4.0** this week. Then **The Gate** (small, closes 2.4, makes the app load-bearing),
then **The Forge** (the flagship — review and ship agent work from a phone, on top of 2.4's diff
renderer), then **The Presence** (the agent on the home screen). Fold **The Eye** in wherever it
fits; it is nearly free. Decide the Keryx/Talaria question before The Gate, because The Gate is
the first feature that would otherwise be written twice.
