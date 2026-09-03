# Keryx 2.8.1 — the hands, the picker that sorts itself, the motion pass, two bugs

Built 2026-09-03 on branch `keryx-2.8-bots` (vc77 / 2.8.1, on top of 2.8.0 Bot Mode which is
NOT yet walked). 657 unit tests green (642 + 15). Repo-root `keryx-2.8.1-{debug,release}.apk`.
⚠️ Not walked on a phone — Jonny's phone was not reachable this session.

Jonny's ask (09-03 evening): *"Categorization — model picker. Beautiful animations and designs
at every turn — the side navigation pane, model picker etc. the details. Something that makes
Keryx stand apart on the mobile phone — the ability to control the phone like Google. Bug:
clicking a cron sends a notification about that message when I'm already in it. New session
doesn't put me into that session because the side nav is still present."*

---

## 1. The two bugs

### Tapping a run notified you about the run you just tapped
**Root cause** (KeryxApp `observeForNotifications` + `ChatViewModel.openSessionById`): a cron
run is not in the roster (`/api/sessions` excludes `source=cron`), so opening one calls
`adoptSession`, which publishes a `RoomProfile` stamped `now` into `_pendingNew`. The roster
flow emits, the watcher sees a room whose timestamp jumped from nothing to now, and asks
`isForeground && openRoomId == room.id` — but `openRoomId` was set by a **flow collector** in
MainActivity, one dispatch behind `selectRoom`. Race lost every time; the peek returned the
run's report; the notification fired for the thing on screen.

**Fix — encode the invariant "the room being opened is never news":**
- `ChatViewModel(onOpenRoomChanged:)` — one synchronous hook, invoked from the ONE writer
  `setCurrentRoom()` (every `_currentRoom.value =` went through it). MainActivity passes
  `{ app.openRoomId = it }`; its collector now only clears the shade entry.
- `openSessionById` claims the id through the hook BEFORE adopting, so the roster emission
  that follows already finds it "on screen".
- The watcher re-checks `openRoomId` AFTER the peek (a network round trip during which the
  user may have opened the very room), and logs `opened while peeking`.

### New session from the drawer left you in the drawer
`NewChatSheet` only had `onDismiss`; the drawer's `showNewChat = false` closed the sheet and
nothing closed the drawer. `NewChatSheet(onCreated:)` fires once on a successful create/join
(a cancel is only `onDismiss`); `NavigationDrawerContent(onConversationCreated:)` threads it;
HermesApp closes the drawer. The ViewModel's `openRoomById` → `pendingOpenRoomId` path was
already selecting the new session; the drawer was just standing in front of it.

---

## 2. The model picker (`ModelPickerSheet.kt`, `core/model/ModelPicker.kt`)

Was: a stock `DropdownMenu` of provider headers + raw model ids. Is: a `KeryxSheet`.

**Core, pure, tested (`ModelPickerTest`, 9 tests):**
- `ModelCatalog.parse` now keeps what the gateway already sends and the app threw away:
  `pricing{input,output,cache,free,discount_percent,was_*}`, `featured_models`,
  `unavailable_models` (Nous free tier), `free_tier`, `total_models`, `is_user_defined`,
  `api_url`, `auth_type`, `can_disable_reasoning`. `ModelChoice.lab` / `.shortName` split a
  `vendor/model` id. `ModelPricing.compact("$3.00") = "$3"`.
- `ModelPicker.plan(catalog, recents, query, expanded)` → sections in this order:
  **Recent** (resolved against the LIVE catalog, current model excluded) →
  **LOCAL** (`api_url` on loopback / RFC-1918 / 100.64/10 tailnet / `.local .lan .ts.net
  .internal` / bare hostname, or a local-runtime slug) →
  **CLOUD** (single-lab logins, one flat group folded past 6, flagship-first as the gateway
  orders) → **AGGREGATOR** (≥2 labs among the ids: split by lab, the gateway's
  `featured_models` shown and the tail counted behind "N more") → **VIRTUAL** (MoA) last.
  A query flattens everything: every fold opens, only matches remain (model, lab, provider
  names; terms AND). A fold that would hide ONE row shows it instead. Local rows never fold.
- `ModelPicker.pushRecent` = the phone ledger (`SettingsRepository.recentModels`,
  `provider|model` keys, written by `ModelDelegate` on an applied or deferred switch and on the
  Matrix `/model` command).

**Sheet:** current-brain card (kind dot breathing, name slides up on a switch, provider ·
kind · lab line, tags/price line, Refresh glyph that turns while loading; card shimmers while
the catalog reads) → search field → **section rail** (one chip per section with the kind's
colour; the lit chip follows the list's top row; tap = jump) → the LazyColumn with stable keys
so a fold animates open via `animateItem` → **Machines** (the Spire brain roster) kept as its
own foot section, never mixed with routes. Rows: kind dot (current breathes), short name,
meta line `fast · thinks · $3 / $15 · free · −30% · paid tier`, Star for featured, Check for
current; unavailable rows dim and don't take a tap. The composer pill shows `shortName` and
rises into place on a change.

Kind colours: LOCAL = `KeryxStatus.good`, CLOUD = primary, AGGREGATOR = tertiary, VIRTUAL =
idle.

---

## 3. The motion pass

New vocabulary in `KeryxDesign.kt` (all springs from `KeryxMotion`):
- `keryxReveal()/keryxConceal()` — the one reveal pair (fade+expand on `settle`, fade+shrink
  on `leave`). Applied to every bare-default `AnimatedVisibility`: the four composer bars
  (approval / blocking / reply / attachment), `ReasoningDisclosure`, `ToolTheater`,
  `MessageContent` raw toggle, both `KeryxMarkers` disclosures, `AgentDeliveryNotice`,
  `NewChatSheet`; `TurnRenderers` turn header slide re-specced to the springs.
- `keryxPop()/keryxVanish()` — chips, badges, tiles (fade + scale from .86).
- `Modifier.keryxPressScale(interactionSource)` / `Modifier.keryxPressable(onClick)` — the
  surface sinks to 0.965 under the finger and springs back; stills under reduced motion.
  Applied: drawer rows, drawer doors, picker rows/chips/folds, hands tiles.
- `KeryxMotion.settleSize/leaveSize/settleInt/leaveInt` — the same springs typed for
  expand/shrink and slide transitions.
- `KeryxSheet` — an arrival breath (alpha .4→1, 10dp lift, `settle`) behind the sheet's own
  slide, and the drag handle drawn as an accent→dusk hairline instead of Material's grey pill.
  Every sheet in the app inherits it.

Drawer (`NavigationDrawer.kt`, `HermesApp.kt`):
- `DrawerShape` = 24dp on the edge that meets the room; the outer host sheet wears it too
  (transparent, so the two nested `ModalDrawerSheet`s stop double-painting) and the scrim is
  the void cast with 14% accent at 55% instead of Material grey.
- Theme glyph turns over (`AnimatedContent`, pop/vanish) instead of snapping.
- `RoomRow`: selection wash `animateColorAsState`; press scale; unread pill and unread dot
  pop in/out; rows `animateItem()` when activity reorders the list (`modifier` param added).
- `DrawerDoor`: press scale; badge pops in/out as itself and the count inside rolls up
  (`AnimatedContent` slide); the last real count is kept through the exit so "0" never shows.
- Composer model pill: name rises on a change.

`haptics.completion()` — defined in 2.0, never fired — now lands when a turn you waited on
ends: only when the wait ran ≥ 1.5 s (`COMPLETION_TICK_MIN_MS`, an instant echo gets no
ceremony) and only for the room on screen (a room switch mid-wait resets without a tick).

---

## 4. The hands — ⟦keryx:do⟧ (the stand-apart piece, V1)

The roadmap has named "the phone as a tool host" the most differentiating idea in the stack
twice and parked it on plumbing + consent. V1 takes the half that needs neither: the agent
**proposes** a phone action in its text, the phone renders it as a tile, and **the tap is the
consent**. No gateway change, works on both doors, degrades to nothing on clients without hands.

**Grammar (agent-emitted, like ask):** `⟦keryx:do|<kind>|<arg>|<arg>…⟧`, anywhere in the body,
up to 4 per message, code spans are mentions. Kinds (`core/model/PhoneAction.kt`, checked):

| kind | args | phone |
|---|---|---|
| `url` | https link | ACTION_VIEW |
| `dial` | number | ACTION_DIAL (dialer opens, you press call) |
| `sms` | number, body? | ACTION_SENDTO smsto: |
| `email` | to, subject?, body? | ACTION_SENDTO mailto: |
| `calendar` | title, start ISO?, end ISO?, where? | ACTION_INSERT event (date-only = all-day; no end = +1 h) |
| `alarm` | HH:MM (24 h), label? | ACTION_SET_ALARM, SKIP_UI (the tap is the confirm) |
| `timer` | `90` / `90s` / `10m` / `1h30m` / `1:30`, label? | ACTION_SET_TIMER, SKIP_UI |
| `navigate` | place/address | geo:0,0?q= |
| `search` | query | ACTION_WEB_SEARCH |
| `play` | query | MEDIA_PLAY_FROM_SEARCH |
| `open` | app name | launcher activity by label (exact, then contains) |
| `copy` | text | clipboard (no intent) |
| `torch` | on/off | CameraManager.setTorchMode |
| `share` | text | ACTION_SEND chooser |

A marker that doesn't parse (unknown kind, bad args, an alarm at 25:99) stays **literal text**
— the honest failure: whoever reads the transcript sees what was asked for.

**App:** `hands/PhoneHands.kt` (`intentFor` for Intent-shaped kinds — so the same Intent rides
a notification button as a `PendingIntent.getActivity` — and `perform`, which never throws and
answers with the one line to toast when nothing on the phone can do it). `HandsTiles` in
`KeryxMarkers.kt` (the decision-tile family, dusk→amber, the action's glyph in the badge, a
trailing word for where it lands: OPENS / APP / CLIPBOARD / PHONE / SHARE). Notification: when
no ⟦keryx:ask⟧ is pending, up to 2 hands buttons ride the card. Manifest: `SET_ALARM` +
`<queries>` for the launcher and every intent the hands send (package visibility on 30+).

**Agent side:** `~/.hermes/silas_ext/wiki_context.py` `build_hands_protocol()` (re-exec'd every
turn — LIVE, no restart; backup in the session scratchpad). SILAS is told the kinds, the
exact-token rule, "real values never placeholders", and that nothing happens without the tap.

**V2 (the roadmap's real tool host, not built):** the agent CALLS a `phone` tool over the
direct-door WS (durable since 3.0 Phase 4) and the phone answers — read a notification, take
a photo, report location, screen context via a `VoiceInteractionService` assist session —
behind a per-kind grant model (The Gate's shape). Wake word (2.7, unmerged) + hands + Call
mode = "hey hermes, navigate to Roger's" with a spoken confirm. Also worth its own line:
Keryx as the ROLE_ASSISTANT app (long-press power) — the ACTION_ASSIST door exists today and
only focuses the composer.

---

## 5. Files

Core: `ModelCatalog.kt` (enriched), `ModelPicker.kt` (new), `PhoneAction.kt` (new),
`MessageParser.kt` (DO_MARK, `Keryx.hands`, `Segment.Hands`, `phoneActions`), tests
`ModelPickerTest.kt`, `PhoneActionTest.kt`.
App: `ModelPickerSheet.kt` (new), `hands/PhoneHands.kt` (new), `KeryxDesign.kt` (motion
vocabulary + sheet), `KeryxMarkers.kt` (HandsTiles), `MessageContent.kt`, `ChatRenderItems.kt`,
`ChatScreen.kt` (pill → sheet, bars, completion tick), `NavigationDrawer.kt`, `HermesApp.kt`,
`NewChatSheet.kt`, `ChatViewModel.kt`, `MainActivity.kt`, `KeryxApp.kt`, `ModelDelegate.kt`,
`SettingsRepository(.Impl).kt` (`recentModels`), `KeryxNotifications.kt`, the six reveal sites,
`AndroidManifest.xml`, `build.gradle.kts` (vc77 / 2.8.1).

## 6. Walk (Jonny)

1. Runs door → tap a run that landed after the app opened → NO notification for it.
2. Drawer plus → New session → the drawer slides away and the new session is on screen.
3. Model pill → sheet: Recent shelf (after one switch), silas-brain under a green LOCAL
   dot, Anthropic flat, Nous split by lab with "N more"; search "sonnet"; pick → pill rises,
   toast, reasoning ladder re-probes. Machines at the foot still swap the brain.
4. Drawer: press a row/door (sinks), toggle theme (glyph turns), unread pill pops, badge
   count rolls. Any sheet: contents rise into place.
5. Ask SILAS "navigate me to the H-E-B on Mueller" / "set an alarm for 6:45 called flight"
   / "open Spotify" → tiles → tap → maps / clock / Spotify. Lock the phone, have SILAS send
   one → the notification carries the button.
6. A reply that took a few seconds → two soft beats when it lands (haptics on).

## 7. Traps
- ⚠️ `AnimatedVisibility` inside a `Box` inside a `Column`: the `ColumnScope` extension wins
  the implicit receiver and refuses — call `androidx.compose.animation.AnimatedVisibility`
  explicitly (the door badge).
- ⚠️ A `Set<String>` is not `rememberSaveable`; the picker keeps its open folds as one
  `\u001F`-joined string.
- ⚠️ Kotlin source must spell the separator as the escape `'\u001F'`: a raw control byte in
  a char literal compiles but no diff tool will show it.
- ⚠️ `ModelChoice.lab` is `""` on single-namespace providers; the aggregator test is "≥2
  non-empty labs", so a provider mixing prefixed and bare ids is still an aggregator.
- ⚠️ The hands never auto-run. If V2 adds Call-mode auto-run, it must be a per-kind grant,
  never a global switch.
