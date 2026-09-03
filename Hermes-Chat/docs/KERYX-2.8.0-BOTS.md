# Keryx 2.8.0 — Bot Mode, the long-message lag, and agent-shaped notifications (2026-09-03)

Jonny: "bot mode for Hermes in Keryx? the way that the desktop app did it? in my head this
would be a button to navigate into that persistent session … Please build V1 or more … super
flush experience. And also … whenever I swipe to go up in a conversation, if there's a super
long message, there's a little bit of lag … I also believe that the notifications can be
reworked to be better oriented for an agent as well if one is sending messages."

Branch `keryx-2.8-bots` off `main` (`c628e4a`, the 2.6.2 tree). versionCode 76 / 2.8.0
(2.7.0 = vc75 lives on `keryx-2.7-wake-word`; whichever merges second rebases its version).
642 tests green (210 `:core:allTests` + 432 `:app:testDebugUnitTest`; +17 `BotsTest`,
+4 `AgentNoticeTest`, +5 `MarkdownCacheTest`, +4 `MentionTokenTest`). Repo-root
`keryx-2.8.0-{debug,release}.apk`. ⚠️ NOT walked on device — Jonny's phone.

## 0. What changed upstream since August (why this translates now)

Bot Mode is no longer the separate Electron plugin. It moved in-tree
(`apps/desktop/src/plugins/hermes-bots/plugin.js`, default-on), and — the part that matters —
the **gateway grew the primitives** on 0.20.6 (the install serving Keryx today, `a5995f4a5e`):

| primitive | where | what |
|---|---|---|
| canonical "Bot Chat" | `hermes_state.py` `CANONICAL_BOT_CHAT_TITLE` | one session per profile titled exactly `Bot Chat`; the TITLE is the identity, resolved by exact lookup |
| `session.list {profile, title, include_hidden}` | `methods_session.py:194` | indexed WHERE title = ? — window-free; resurrects an accidentally archived one |
| `session.create {profile, title, hidden, follow_profile_config}` | `methods_session.py:14` | born hidden; runtime follows the profile's CURRENT config, never a stale row pin |
| `session.title` | `methods_session.py:1296` | materializes the lazy row NOW under the canonical name (closes the auto-titler race) |
| `session.resume {profile}`, REST `?profile=` (list/messages/DELETE) + body `profile` (PATCH) | server.py, `web_routers/sessions.py` | one socket / one REST base serves every local profile |
| `profiles.list` → `canonical_session`, `ui_meta`, `has_avatar`, `bot_mode_protocol` | `methods_profiles.py:22` | the roster, with each profile's forever-chat resolved server-side |
| `profiles.configure {ui_meta:{"hermes-bots":…}, description}` | `:749` | ui_meta merges KEY-wise — writing `hermes-bots` replaces the whole block |
| `profiles.create`, `profiles.get_asset` | `:339`, `:1107` | new profile (fresh / clone_from); avatar as a data URL |
| `message_agent` tool + teammate protocol | `tools/bot_mode_dm.py`, `tools/bot_mode_probe.py` | injected ONLY into a canonical Bot Chat, ONLY while some profile carries `ui_meta['hermes-bots']` |
| `[bot:<name>] …` | cron | a bot's routines are plain jobs with that prefix |

**Live probe passed 09-03** (`scratchpad/probe.py`, self-minted basic-auth bearer → ws-ticket):
`profiles.list` returned the 7 profiles with `default` already holding a canonical chat (the
08-17 hand test, 6 messages); `session.list {profile:"theo", title:"Bot Chat"}` → `[]`;
`session.create {profile:"trial", title:"Bot Chat", hidden, follow_profile_config}` →
`info.profile_name = "trial"`; `session.close` clean. REST `/api/sessions?profile=theo` → theo's
rows. So a non-desktop client CAN open a bot's forever-chat over the dashboard socket.

⚠️ `dashboard.basic_auth.password` (plaintext) in config.yaml is STALE against `password_hash`
— password-login 401s. The probe minted its own token with `_sign({sub,kind:"access",exp},
secret)` from the plugin's own signer (`_resolve_secret` → raw UTF-8 of the config string).

## 1. Bot Mode in Keryx — the shape

**A Bot is a profile. Keryx is a UI over that primitive** (exactly the desktop's stance):
nothing new on the gateway, everything visible from the CLI.

- **Bots door** (drawer, direct door only, `KeryxGlyphs.Robot`, badge = bots with news).
  `BotsSpace.kt`: "Active now" strip (busy turn OR last word inside 90 s — the desktop's
  `ACTIVE_WINDOW_S`), search past 5 bots, one row per profile (face, label, `main` chip for the
  default profile, pin glyph, routine count, preview-or-role line, when · model · "not armed"),
  news dot, breathing rim while working. Long-press: Open chat · Pin to top of sessions ·
  Edit name & role · Routines · Hide/Unhide. Eye toggle in the bar once anything is hidden. `+`
  = New agent.
- **Tap = the forever-chat.** `DirectTransport.openBotChat`: registry lookup by title
  (`include_hidden`), else create hidden + `follow_profile_config` + eager `session.title`
  (adopt-on-"already in use"), kickoff prompt for a newborn ("Hey, tell me about yourself!").
  **Fails CLOSED**: a lookup error is an error, never "no chat yet" — the one way to fork a
  bot's forever-chat is a swallowed miss, and the desktop learned that the hard way (#92687).
- **Profile threading.** `profileOf` (stored id → profile) in DirectTransport; `session.resume`,
  REST messages/PATCH/DELETE all name it. Launch profile (default) sends nothing — the gateway's
  `_profile_home` returns None for its own home anyway.
- **Bot chats as rooms.** They are hidden rows in OTHER profiles' stores; the session list never
  carries them. `BotsDelegate.publishRows` → `DirectTransport.publishBotRows` (source `bot`,
  name = label, `heraldIds = [profile name]`) merged into `getRooms()`; the drawer's Sessions
  list FILTERS them (the Bots door is their list); the floor can select/restore them; the
  notification watcher sees them move. A pinned bot is a deck tile (source `bot-tile`, phone
  ledger `pinnedBots`) — "at the top of the session list", same grammar as cron tiles.
- **The floor in a Bot Chat:** top bar shows the bot's sigil in its light, its label and a "Bot
  Chat" chip. `/new` and `/reset` reroute to `/compact` with a toast (the forever-chat rule);
  the same profile's regular sessions keep `/new`.
- **@mentions.** Typing `@` in a Bot Chat (only where `message_agent` exists — roster
  `messagingArmed`) offers chips from the roster (`MentionChips`, `MentionToken`); on send the
  ViewModel appends the desktop's identification note (`BotRoster.mentionNote`) so the agent
  knows exactly whom the tag means and hands off with `message_agent`, never forwarding the
  user's words verbatim. Renamed bots stay taggable by title slug (`tags`).
- **Bot messaging armed/off card** at the foot of the roster: reads the gateway's gate
  (`bot_mode_protocol` && any managed profile); "Turn on bot messaging" writes a `hermes-bots`
  block (`title` = label, `created` = now) to every unmanaged profile via `profiles.configure`.
  Every write-back starts from the bot's RAW block (`BotProfile.meta`) — the gateway replaces
  the key, so anything less wipes the desktop's shape/colour/groups.
- **New agent / Edit:** name (slug-validated, collision-checked), title, role, Start-from
  (fresh / clone a bot). Create → configure → open with kickoff. Edit = title + description.
- **Routines sheet:** the bot's `[bot:<name>]` jobs (schedule, paused, next) → Runs door.
- **Unread** = the phone's per-bot "looked at" stamp (`botSeenAt`) vs `canonical.last_active`
  — the gateway's watermark never sees a hidden cross-profile row. Stamped on open, on send.
- **Roster cadence:** on login, then a 60 s pulse (bot chats live outside the launch profile,
  so `sessions.changed` never covers them); 15 s while the Bots door is open; drawer open.
  `profiles.list` walks skill trees per profile — never per keystroke.
- **Colour:** `botLightFor` — palette slot by `stableHash(name)` (default profile = your
  accents), the same slot the notification shade draws, so a bot is one colour everywhere.

Pure half, all tested in `:core`: `model/Bots.kt` (`BotProfile`, `BotChatRef`,
`BotRosterSnapshot`, `BotRoster` rules, `BotsJson` parse/patch), `model/AgentNotice.kt`.

### Not in V1 (deliberately)
Group chats (a desktop-side orchestrator, 3-round turn-taking — plugin JS, not gateway),
cross-connection relay, generated avatars / blob faces / pets, profile delete (no RPC on the
socket; CLI `hermes profile delete`), the `ui_meta_expected_revisions` optimistic lock.

## 2. The long-message lag

Root: the markdown renderer parsed a body when its bubble composed, and a LazyColumn disposes
every row that scrolls off — so a long answer was RE-PARSED on the UI thread every time it
scrolled back in (full intellij-markdown pass + three regex passes + the code tokenizer).
Fixes (`MarkdownCache.kt`, `MarkdownWarmer.kt`, `MessageContent.kt`, `CodeHighlighting.kt`):

1. **`remember(head)`** around the pre-render chain (TeX→Unicode, dangling fences, autolinks):
   a recomposition that changes nothing about the text (a reaction landing, the TTS pulse) no
   longer runs three regex passes over the body.
2. **`MarkdownCache`**: settled bodies ≥ 600 chars are parsed once into the library's own
   `State.Success` and served through `ParsedMarkdownState` (a `MarkdownState` whose flow never
   moves) — a re-entry is a map lookup. LRU 160, content-keyed. Streaming bodies never touch it.
3. **`MarkdownWarmer`**: `ChatScreen` warms every settled long agent body in the loaded window
   on `Dispatchers.Default` the moment it is known, keyed exactly as the bubble will ask
   (`MarkdownCacheTest.warmerKeysExactlyWhatTheBubbleAsksFor` pins that).
4. **`CodeHighlighting.spans`** LRU (256) by (grammar, ground, code) — a fence scrolled back in
   no longer re-tokenizes.

⚠️ Not measured on device (no phone). Layout of a giant `Text` is still paid per entry; if
lag remains after this, the next lever is slicing a long message into several lazy items.

## 3. Notifications, agent-shaped

`AgentNotices.compose` (core, tested) decides conversation / speaker / key / line / relayed:
- a Bot Chat speaks AS the bot (`☤ Theo`), a plain session as its agent under the session's
  name, a `Message from 🤖 Juno` delivery makes Juno the speaker, relayed (`Juno → Theo`).
- `KeryxNotifications.notifyMessage(notice)` is a **MessagingStyle conversation**: the speaker
  is a `Person` (bot flag, sigil icon in its palette colour), lines STACK per room (6), the
  conversation title is whose chat it is, everything grouped under one Keryx summary, `Reply to
  <speaker>` inline, ⟦keryx:ask⟧ buttons, and **Mark read** on the direct door (gateway
  watermark via `NotificationActionReceiver.ACTION_MARK_READ`). Your own reply from the shade
  joins the stack as you.
- `KeryxApp.observeForNotifications` on the direct door now uses `DirectTransport.peekLatest`
  (a REST page through `TranscriptBuilder`, no store, no `session.resume`) — the old path
  hydrated AND attached one live agent per notified session (the 2.6.2 drawer trap, again).
- Matrix push path (`KeryxPushService`) composes the same notice.

## 4. Traps

- ⚠️⚠️ `profiles.configure` ui_meta merges KEY-wise: always write the WHOLE `hermes-bots` block
  from `BotProfile.meta`. `BotsJson.metaPatch` does; nothing else may write it.
- ⚠️ The registry lookup MUST fail closed (see §1). Do not "default to create" on an RPC error.
- ⚠️ `session.list` with `title` answers a DIFFERENT row shape (`resolved_id`, `root_title`) than
  the windowed list — `BotsJson.canonicalFromList` reads both; open `resolvedId` (lineage tip).
- ⚠️ The PATCH names its profile in the BODY; GET/DELETE in the query. Mirrored in `GatewayRest`.
- ⚠️ `message_agent` exists ONLY in canonical Bot Chats on installs with ≥1 managed profile —
  the mention note and chips are gated on `messagingArmed`, else the agent is told nothing.
- ⚠️ Jonny's install had ZERO managed profiles on 09-03 (no `hermes-bots` anywhere): bots
  can chat but not message each other until the card's button (or the desktop) arms them.
- ⚠️ A newborn's `openBotChat` sets the store `hydrated = true` (nothing to fetch); the intro
  turn streams in over the attached socket.
- ⚠️ `botLightFor`, not `heraldLightFor`, for bots: with no herald ids configured the latter
  paints every bot in the theme accent.
- ⚠️ MessagingStyle `Person.key` for bots = `bot:<name>`; the icon hashes the BARE name so shade
  and roster agree.

## 5. Walk (Jonny, on device)

1. Drawer → **Bots** door (badge); roster lists the 7 profiles; `default` wears `main`.
2. Tap Theo → floor opens "Theo · Bot Chat" (sigil + chip); first tap on a never-opened bot
   creates it — on `default` it adopts the 08-17 chat (6 messages) instead of minting.
3. Type `/new` there → toast "This chat never resets — compacting instead"; a regular session
   still resets.
4. Roster card: "Turn on bot messaging" → arms 7 profiles → card turns green; then in Theo's
   chat type `@` → chips; send `@juno ping` → Theo hands off with `message_agent`; Juno's
   reply lands attributed ("Message from 🤖 …" notice) and, if you are elsewhere, as a
   `Juno → Theo` notification with Juno's sigil.
5. Long-press a bot → Pin to top of sessions → tile in the drawer deck; tap opens its chat.
6. `+` → New agent (name `scout`, title "Scout", role) → it introduces itself.
7. Notifications: leave a bot chat, have it answer → conversation-style card, `☤ Theo`, lines
   stack, Reply / Mark read; the shade groups under Keryx.
8. Scroll up through a long answer — smoother than 2.6.2? (the only measurement there is).
