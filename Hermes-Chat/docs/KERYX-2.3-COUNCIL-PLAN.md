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
- Each herald runs as its own process: `hermes -p <herald> gateway run` (systemd user unit
  `hermes-herald@.service`). The main @silas gateway is untouched; Sy sits in the council as
  the default-profile route. ⚠️ Sy has `require_mention: false` globally → he answers every
  council message until `matrix.require_mention: true` + `free_response_rooms` (the seven
  existing rooms) lands at the next gateway restart (config prepared; Jonny-gated restart).

Loop safety: mention-gating on every herald; each herald's SOUL gets a council clause ("answer
the one who called you; never @-call another herald unless Jonny asked for a relay").

## 7. Continuity — positioning

README "Highlights" gains: *The room is the truth* (history lives in Matrix and on the phone —
works when the gateway is down, same conversation from phone/desktop/Archive) and *The
Council* (several agents, one room, each its own light). Framing only; the code is 1–6.

---

## Sequence & shipping

Patch cadence rule: 2.2 (gilded void + composer) is already on the phone uncommitted; 2.3 is a
milestone minor (Jonny: council = milestone). Order: 1 → 2 → 5 → 3 → 4 → 6 → 7; each step
compile-clean; device walk after 1+2+5, again after 3+4.
