# Keryx 2.3 — "The Council"

The official Hermes app will be a *terminal to your gateway*. Keryx is not a terminal; it
is a **room**. 2.3 leans all the way into the one thing a Matrix room can do that a gateway
socket never can: many people, many agents, many gateways, a history that outlives any
process, and messages that travel *both* directions without anyone opening an app.

2.3 ships on top of 2.2's light system ("color is light, light means life"). The new rule:
**each life has its own color.**

## The additions (in build order)

| # | Addition | One line |
|---|---|---|
| 1 | **Heraldry** | every agent account gets a stable hue + the kerykeion sigil; bubble rim, name, caduceus and typing all carry it |
| 2 | **Bot Mode exchange (B22 port)** | `Message from 🤖 X` / `hermes -p X chat -q` render as attributed notices, never as the human speaking |
| 3 | **Arrival** | an agent turn that nobody asked for is visibly an *arrival*, not a reply |
| 4 | **Senses** | the phone tells the agent what it knows (battery, local time, coarse place) — in-band, E2EE, opt-in |
| 5 | **Kerykeion icon** | the herald's staff, gilded hairline on matte void, with a monochrome layer |
| 6 | **The Council (infra)** | one Matrix account per profile, own gateway, mention-gated; one room where they all sit |
| 7 | **Continuity copy** | README/positioning: the room is the truth, not the session |

---

## 1. Heraldry — per-agent light

**Problem.** `senderTypeOf` (`ChatRepositoryImpl.kt:667`) knows exactly one agent
(`SettingsRepository.agentMatrixId`); every other non-self sender is `OTHER`; bubbles branch
only on `isMine`; the 2.2 effects all read `MaterialTheme.colorScheme.primary/tertiary`.

**Design.**
- `agentMatrixId` becomes a **set** (comma/newline separated, backward compatible — a single
  id still works). Any sender in the set is `SenderType.HERMES`. Settings → Agent shows a
  "Heralds" list.
- `Heraldry(key, name, accent, accent2)` — `domain/model/Heraldry.kt`. `accent` derives from a
  stable hash of the localpart over a curated gold-family palette (gilt, ember, verdigris,
  dusk-violet, rose-gold, ice) so two heralds never share a hue in a room of ≤6; the user can
  override per herald in Settings (`herald_accent_<localpart>`). The *primary* agent (first in
  the set) keeps the user's theme accents so a 1:1 room looks exactly like 2.2.
- `bubbleAppearance(isMine, style, accent = cs.primary, accent2 = cs.tertiary)` — the gilded
  rim of a herald's bubble is its hue. `MessageBubble` resolves heraldry from `message.senderId`.
- Sender label in group rooms: herald name in its hue, preceded by the `☤` sigil glyph.
- `HermesThinkingAnimation(style, accent, accent2)` — the caduceus serpents tint to whoever is
  thinking. `TypingState.agentTyping` gains `agentIds: List<String>`; the working bar shows
  one sigil per thinking herald.
- Drawer / Quick Rooms: a council room (≥2 heralds among members) shows a stacked row of
  sigils in member hues instead of the monogram.

**Non-goals.** Avatars (Keryx never resolved per-sender avatars; the sigil *is* the avatar).

## 2. Bot Mode exchange — B22 port

Talaria's `AgentDelivery` / `AgentDeliveryCommand` (pure Kotlin, ported verbatim):
- receiver regex `^(?:Message from (?:🤖\s*)?NAME(?:\s*\(@handle\))?:\s*|\[Message from agent 'X'\]\s*)BODY$`
  → `Message.agentDelivery`, set in `TimelineEvent.toMessage` (`ChatRepositoryImpl.kt:608`).
  Rendered by `AgentDeliveryNotice` above the bubble; body behind an expander; sender shown in
  that herald's hue if known.
- sender half: in `ToolCallCard` (`ToolGroup.kt:60`, the single funnel) a `terminal` call whose
  args match `(?:^|[;&|]\s*|\bhermes\s+)-p\s+X\s+chat\b[\s\S]*?-q\s+["']Message from` renders as
  "Messaging X…" / "Messaged X" (`AgentDeliverySentNotice`). Failed calls keep the terminal row.
  Keryx has no `call.result`; the reply arrives as the next fenced note, so reply extraction is
  the `session_id:` boundary cut applied to the following `ToolRunEntry.Note` when present.
- `dedupCalls` exempts deliveries.

## 3. Arrival

**Definition.** A `HERMES` message is an *arrival* when the previous message in the room is
not mine AND is older than 20 min (or there is none) — i.e. nobody asked. Cron check-ins and
telemetry are excluded (they're already low-contrast rows).

**Render.** `ChatRenderItem.Arrival(message)` emitted by `walkRange` before the bubble: a
hairline divider in the herald's hue with the sigil and "`<name> · unprompted · 10:47`"; the
bubble itself gets one `keryxLightSweep` pass when it first composes (attention budget: the
sweep is the one focal effect; sand/dust yield for that beat).

**Notification.** `KeryxApp.observeForNotifications` stamps the title "`☤ <name>`" (instead of
the room name) when the message qualifies — the herald arrives at your lock screen.

## 4. Senses

**Principle.** The herald carries news back. Opt-in per sense, off by default, nothing leaves
the phone unless the user sends a message; the data rides *inside* the user's own message body
as a marker, so it is E2EE-wrapped like everything else and needs **no gateway change**.

- Marker: `⟦keryx:sense|battery=22%·charging|local=23:10 CDT|at=Austin TX (±1 km)⟧` appended to
  outgoing text (at most once per 30 min per room, and immediately when a value changed class —
  charging flipped, place changed). Keryx strips it from the user's own bubble (marker protocol
  v1 already masks code spans; ME-side strip is new).
- Senses v1: **battery** (BatteryManager, no permission), **local time + zone** (no
  permission), **coarse place** (`ACCESS_COARSE_LOCATION`, `LocationManager` network provider,
  rounded to ~1 km, `Geocoder` locality when available). Calendar next-event is v1.1.
- Settings → new "Senses" card: three switches + "last sent" line + a "send now" row.
- Agent side needs nothing; the marker reads as plain text. SILAS's prompt can be told what the
  marker means (one line in the personality block) — out of this repo.

## 5. Kerykeion icon

The herald's staff (κηρύκειον) *is* the caduceus — the 2.2 spinner already is the icon.
Adaptive icon: background `#0B0A12` matte void; foreground = gilded hairline kerykeion (staff,
two serpents crossing twice, small wings, orb) in `#F0B429`→`#E55A00` gradient strokes;
`<monochrome>` layer = the same vector single-colour for Android 13+ themed icons. Legacy
PNGs (API 24/25) regenerated from the same geometry (PIL). Notification small icon becomes the
staff silhouette.

## 6. The Council — infrastructure (hermes side, outside the app)

> ## ⚰️ ABANDONED 2026-08-19 — do not rebuild
>
> Built, walked, and then **reverted in full to baseline** on Jonny's call: *"I can't think of
> a use case where just Silas and you wouldn't be enough."* The Council room was deleted and
> purged from Synapse the same day.
>
> **Why it could not work as designed.** The gateway builds its system prompt **once per
> process** and reuses it across every multiplexed secondary profile, so all four heralds wore
> whichever identity happened to take the first council turn after startup — proof: once Theo
> answered first, every other herald deflected its *own* name. The prompt is then frozen into
> `sessions.system_prompt_hash` and survives restarts. `_agent_home()` is never reached on that
> path. Fixing it means a real change to the gateway's prompt lifecycle, not configuration.
>
> **What was reverted:** `hermes-herald@.service` (removed), all four `profiles/*/SOUL.md`
> (restored from backup — the council clause made them go silent in their *own* rooms),
> `HERMES_MULTIPLEX_ROUTING_ONLY=true`, `matrix.require_mention: false`, and the temporary
> `system_prompt.py` instrumentation. Persona rooms verified working afterwards.
>
> **Left inert** (costs nothing, unused under routing-only): the `@milo @theo @sterling @juno`
> Synapse accounts, the Matrix identity block + `MATRIX_ALLOWED_USERS=@jonny` in
> `profiles/*/.env`, and `~/.hermes/council/heralds.env`.
>
> ⚠️ **The app-side work is unaffected.** §§1–5 and 7 never depended on this; Heraldry degrades
> to exactly 2.2's look when only one herald is configured. The plan below is kept as a record
> of what was tried.

Facts (verified 08-19): all five profiles shared `@silas:silas.local`; routing-only multiplex
stamps `source.profile` per *room*; upstream Matrix adapter already anticipates multi-bot rooms
(`thread_require_mention` "prevents infinite reply loops in multi-agent shared rooms"); replies
are `m.text`; unauthorized senders in group rooms are *silently ignored*; pairing codes only in
DMs.

Built 08-19:
- Synapse accounts `@milo @theo @sterling @juno` (display names set), creds in
  `~/.hermes/council/heralds.env` (0600).
- Room **The Council** `!ahioBjhkajIlugScnV:silas.local` — @silas + the four heralds joined,
  @jonny invited (accept in Keryx). Topic: "Address one by name; they answer only when called."
- `profiles/<herald>/.env` rewritten (backups in `~/.hermes/council/backup-env-*`): own
  `MATRIX_USER_ID/ACCESS_TOKEN`, `MATRIX_ALLOWED_USERS` = jonny + silas + all heralds,
  `MATRIX_ALLOWED_ROOMS` = the council, `MATRIX_REQUIRE_MENTION=true`,
  `MATRIX_THREAD_REQUIRE_MENTION=true`, Discord/Telegram/api_server removed (those were
  byte-copies of the default's and would double-poll).
- ~~Each herald runs as its own process: `hermes -p <herald> gateway run` (systemd user unit
  `hermes-herald@.service`).~~ **WRONG — corrected 08-19.** The default gateway runs as a
  **profile multiplexer** (`gateway.multiplex_profiles: true`) and already serves every profile
  under `profiles/`, heralds included. A second gateway per herald refuses to start: *"The default
  gateway is running as a profile multiplexer and already serves profile 'milo' … would
  double-bind its platforms."* The `hermes-herald@.service` unit was written, proved to crash-loop
  against that guard, and **removed**. Do not re-add it.

  ⚠️ **But a restart alone is NOT enough** (proved on device 08-19). `~/.hermes/.env:64` sets
  `HERMES_MULTIPLEX_ROUTING_ONLY=true`, a SILAS_EXT patch. The gateway then logs:

      Multiplex: routing-only mode — 6 profile(s) registered on the shared adapters
      (api_server, homeassistant, matrix, webhook); no secondary connections started.

  **One** Matrix connection exists (`@silas`, device HERMES_GATEWAY, 9 rooms) and profiles are
  routed by room. The herald accounts `@milo/@theo/@juno/@sterling` are NEVER logged in, so a
  message addressed to Milo in the council gets silence — verified by posting one and waiting.
  (`@milo`'s device `last_seen` updating is NOT evidence of life; that is just our own API calls
  with its token.)

  Without the env var, the upstream branch creates and connects a real adapter per profile under
  that profile's HERMES_HOME + credentials. Resolved platform sets: `milo` = {matrix,
  homeassistant} — neither binds a port, so no `SecondaryPortBindingConfigError`. ⚠️ But
  `mc-builder` uses `MATRIX_USER_ID=@silas` and `trial` has no Matrix creds at all, so both would
  hit the "two profiles polling the same bot token" collision check and be refused — which is
  precisely what routing-only was patched in to avoid. Flipping the flag globally therefore fixes
  the council and risks the existing shared-`@silas` room routing.

  **The clean fix is a per-profile split** in `SILAS_EXT_ROUTING_ONLY_REGISTER`: profiles that own
  distinct Matrix credentials get real secondary connections; shared-credential profiles keep
  routing-only registration. Not yet written — Jonny's call.

  ⚠️ Two mechanisms now coexist. `platforms.matrix.room_profile_map` is the OLD routing-only
  scheme — one `@silas` connection where the *room* picks the profile (The Forge → milo, The Study
  → theo, The Ledger → sterling, True North → juno). The council instead uses per-herald
  *accounts*. They don't collide: each herald's `MATRIX_ALLOWED_ROOMS` is the council alone, so a
  herald adapter ignores the room its room_profile_map entry already covers.
- Sy's gating — **staged 08-19, needs the restart.** `matrix.require_mention: true` is now in
  `~/.hermes/config.yaml`. `free_response_rooms` is deliberately left **empty**: the adapter skips
  the mention gate entirely for DMs (`if not is_dm:`) and `_resolve_room_identity` treats
  *member_count <= 2* as the primary DM signal regardless of `m.direct` (404 for Sy anyway). Sy is
  in 9 rooms and The Council is the only one with >2 members, so this changes his behaviour in
  exactly one room and Jonny never has to @ him anywhere else.
  ⚠️ If a 1:1 room ever gains a third member it becomes gated too — add it to `free_response_rooms`.

Loop safety: mention-gating on every herald; each herald's SOUL gets a council clause ("answer
the one who called you; never @-call another herald unless Jonny asked for a relay").
**Done 08-19** — a `## The Council` section was appended to all four SOULs (backups at
`SOUL.md.bak-council-*`): answer only when called, never @-mention another herald, stay in your
seat, be brief, you are still yourself here.

## 7. Continuity — positioning

README "Highlights" gains: *The room is the truth* (history lives in Matrix and on the phone —
works when the gateway is down, same conversation from phone/desktop/Archive) and *The
Council* (several agents, one room, each its own light). Framing only; the code is 1–6.

---

## Status (2026-08-19)

| # | Addition | State |
|---|----------|-------|
| 1 | Heraldry | **built** — bubble rim, sender label, spinner, working-bar sigils, Settings "Heralds" list with per-herald picker. One sub-bullet deferred: the drawer / Quick Rooms council sigil row (see below). 19 tests. |
| 2 | Bot Mode exchange | **built** — `AgentDelivery` + `AgentDeliveryCommand` ported, receiving notice + sent notice, reply cut at the `session_id:` boundary of the following note, `dedupCalls` exempts deliveries. 24 tests. |
| 3 | Arrival | **built** — `ChatRenderItem.Arrival`, hairline mark in the herald's hue, one light sweep on the bubble, `☤ <name>` notification title. 13 tests. |
| 4 | Senses | **built** — card in Settings → Privacy & Security, marker appended on the send path, stripped from my own bubble in the repository, plus the plan's "send now" row (implemented as *drop the throttle*, since Senses never sends on its own). 24 tests. |
| 5 | Kerykeion icon | **built** — and the generator now lives at `Hermes-Chat/tools/kerykeion_icon.py`; re-running it reproduces every committed PNG and vector byte-for-byte. |
| 6 | The Council (infra) | ⚰️ **abandoned** 08-19 — built, then reverted to baseline in full on Jonny's call; the room is deleted and purged. Root cause and the revert list are in §6. **Do not rebuild.** The app-side additions do not depend on it. |
| 7 | Continuity copy | **built** — README gains "The room is the truth" and "The Council". |

298 unit tests pass; `assembleDebug` is clean. **Not yet walked on device** — the phone was off
ADB (wireless-debugging port dead) when this landed, so nothing here has been seen on glass.

### Deferred, needs a decision

**§1's drawer / Quick Rooms sigil row.** A council room is supposed to show stacked sigils instead
of the monogram, which means knowing whether ≥2 heralds are among its members. `Session` and
`RoomProfile` carry no member list and `ChatRepository` exposes only `ensureMembersLoaded(roomId)`
— no accessor. So it is either (a) force a member sync for every room in the drawer (Matrix
lazy-loads members; real cost on a long list) or (b) read already-loaded members only, so sigils
appear on rooms you have opened and not on the rest. That trade is Jonny's call, not the
implementer's. Everything else in §1 is wired.

## Sequence & shipping

Patch cadence rule: 2.2 (gilded void + composer) is already on the phone uncommitted; 2.3 is a
milestone minor (Jonny: council = milestone). Order: 1 → 2 → 5 → 3 → 4 → 6 → 7; each step
compile-clean; device walk after 1+2+5, again after 3+4.
