# Keryx 2.6.2 — the direct-door pass: sessions stop pretending to be rooms (2026-09-01)

Jonny: "please review and fix the gateway side of Keryx. I say this starting from long pressing
a session and it says pin to the quick rooms: clearly a matrix side feature."

Grounded in a source inventory of every surface the direct door shares with the Matrix door,
plus the gateway's own contract read from `hermes_cli/web_routers/sessions.py` and
`hermes_state.py` (list rows, PATCH fields, the read watermark).

## 1. What was wrong

The direct door reuses the Matrix drawer, and the drawer spoke Matrix at both doors. Some of it
was words; some of it was the wrong mechanism wearing the right words.

| surface | said / did | on the direct door that meant |
|---|---|---|
| room long-press | "Pin to Quick Rooms" → phone-local ledger | a pin the gateway never sees, while the gateway already keeps a durable pin per session (Desktop parity) that Keryx parsed and ignored |
| drawer rows | `unreadCount` only | never unread: the gateway serves a per-session `unread` (read watermark vs. last activity) that Keryx ignored; `markRead` was a no-op |
| drawer row preview | `transport.getMessages(id, 8)` per row | a REST transcript page **and a `session.resume`** for every row the drawer laid out — a drawer of fifty sessions was fifty live agents on the gateway |
| top bar "New session" | sent `/new` | reset the OPEN session in place; the only true new-session entry was the drawer plus |
| empty pane | "Select a room to begin" | no session yet and nothing to tap |
| Missions "Alert when this ends" | subscribed `platform=matrix, chat_id=<open row>` | a Matrix delivery filed against a gateway session id — an alert that never lands, kept until the task ends |
| Hub "New scheduled job" | "Deliver to this room" → `matrix:<open row>` | a job whose output goes nowhere |
| Settings ▸ Connection | homeserver URL, agent Matrix IDs, heralds, push gateway, re-auth | none of it exists on this door |
| Settings ▸ Hermes Link / Privacy | "falls back to Matrix sync", E2EE switch | the link is the API server behind the spaces here; E2EE is a room property |
| headers, share sheet, hub | Rooms · Quick Rooms · No rooms yet · Share to a room · Open a room… | the noun for a thing this door does not have |

## 2. What changed

**One lexicon per door.** `core/model/DoorLexicon.kt` — a value object (`noun`, `plural`,
headers, pin verbs + hint, empty lines, share title, hub line) chosen once from the transport
(`ChatViewModel.lexicon`) and threaded to the drawer, the empty pane, the share sheet and the
hub. `DoorLexiconTest` pins the rule: the direct lexicon never says "room", the Matrix one never
says "session". A third door someday is one more constructor.

**Pinning is the gateway's pin on the direct door.** `RoomProfile.pinned` carries the server
flag; `GatewayCapabilities.pinSession` PATCHes it (`DirectTransport` moves the row optimistically,
then the list refresh is the gateway agreeing; a failure refreshes back and toasts).
`ChatViewModel.pinnedRoomIds` is one set the UI reads: on direct it is the pinned rows of the
roster, on Matrix the phone's ledger as before. The phone ledger is **not consulted** on direct —
it would drift the moment Desktop pinned something. The long-press verb is "Pin" / "Unpin" with
one line under it, *Kept on the gateway — never auto-archived*, because that is what the flag
means (`sessions.auto_archive` skips pinned rows). The deck header says **Pinned**.

**Unread is the gateway's watermark.** `RoomProfile.unread` + `hasUnread` (either signal).
`DirectTransport.markRead` stamps `unread=false` once per newest message while a session is
open (keyed on the message id, so a recomposition of the same tail is not a write); the row
flips back to unread on its own when the agent, a cron continuation or another client touches
the session after that — the gateway derives it from timestamps, no message-path write. The row
wears a **dot**, not a count: the gateway knows *that*, not *how much*, and a "1" would be a
lie. The deck's breathing halo reads the same flag. **Mark as unread** joins the long-press menu
(Desktop parity), offered only for rows that are read and not open.

**Previews cost nothing.** `DirectTransport.peekPreview` answers from a store that is already
hydrated (a session you opened this process life → its newest line through `previewOf`), else
the ViewModel falls back to the row's own `preview` (the gateway's recognition line, from the
list call that already happened). Nothing in the drawer hydrates or resumes anymore.

**The drawer re-pulls the roster on open** (`refreshRoster`, no-op on Matrix): pins and read
marks set from Desktop, a cron's new row, a compaction's new id — the drawer opening is when
you want to know.

**New session means a new session.** Direct door: the top-bar glyph opens the same
`NewChatSheet` as the drawer plus (offered even with no session open — it is how the first one
gets made), and the empty pane grows a *New session* button. Matrix keeps `/new` on the glyph
(rooms are profiles there; resetting the room's session is the right verb) with its live-turn
confirm.

**Honest gating.** `MissionsDelegate.alertRoom()` is null on the direct door and the switch
explains why (*Mission alerts land in a Matrix room — switch doors to use them*); the cron
dialog gets no room on direct, so jobs run `local`. Settings ▸ Connection on direct shows the
gateway URL (read-only, with the way to change it) and the self-signed switch — nothing else;
Hermes Link is titled for what it does here (*the API server behind Missions, Runs, Shipyard and
the Gateway space — chat itself streams over the direct connection*); the E2EE row and its hub
badge are gone on direct; the Account card shows the gateway, not "No homeserver set".

**Share sheet** sorts by whichever pin the door has (`room.pinned || id in ledger`) and says
*Share to a session*.

## 3. Gateway side

No server change. Everything rides the dashboard REST the gateway already exposes:
`GET /api/sessions` rows carry `pinned` and `unread`; `PATCH /api/sessions/{id}` takes
`pinned` and `unread` (true = explicitly unread, false = read up to now). `GatewayRest.patchSession`
gained the `unread` field; `SessionRow` gained `unread`. `keryx_stream.py` untouched
(`tools/check-payload-sync.sh` still the release gate).

## 4. Traps

- ⚠️ A session created this process life and never messaged has no gateway row: pin / read
  PATCHes 404 (logged, toasted for pin). Deliberate upstream behaviour (no DB row until the
  first prompt) — the row exists locally only.
- ⚠️ `markRead` is one PATCH per newest message per session. Do not call it from anything that
  fires per token or per recomposition; the stamp map is the only guard.
- ⚠️ The gateway's `unread` is NULL-watermark = read for rows never marked. A fresh install
  badges nothing until you open sessions — that is the gateway's design, not a missing fetch.
- ⚠️ `archiveSession` is wired on the seam with **no UI on purpose**: Keryx has no un-archive
  and the Archive door already means something else (kept messages). An exit with no return
  is not a feature yet.
- ⚠️ `DoorLexicon` is chosen at ViewModel construction. A door crossing relaunches the process
  (`commitTransportDoor`), so that is correct — do not cache it anywhere that outlives one.

## 5. Status

- Built 09-01 evening; app 396 + core 189 tests green (+6 `DoorLexiconTest`).
- Debug APK INSTALLED on the phone 20:51 CT (vc74 rebuild, lastUpdateTime is the tell); repo-root
  `keryx-2.6.2-debug.apk` refreshed. Not walked on device (Jonny's phone).
- Walk (direct door): drawer headers *Pinned* / *Sessions*; long-press → Pin (hint) / Mark as
  unread / Rename / Move to project / Delete; pin a session → it moves to the deck and Desktop
  shows it pinned; mark one unread → dot + halo, open it → gone; drawer previews appear with no
  gateway `session.resume` lines in the access log; top-bar plus → New session sheet; empty
  pane button; Missions alert row reads the switch-doors line; Settings ▸ Connection shows the
  gateway; Hermes Link title; no E2EE row. Matrix door: unchanged (Quick Rooms, /new glyph,
  invites, leave, avatar).

## 6. 09-02 — the Runs door counts (Jonny: "the cron button doesn't have any notification label")

- `DrawerDoor` takes a `badge: Int`; the Runs door passes `hub.cron.data.unread.total` — the SAME
  ledger the Runs place's arrivals rail reads (install baseline + opened-run ids), so the door and
  the rail never disagree. Pill = the room list's unread pill shrunk to the icon's corner, `99+` cap
  (`DoorBadge.label`, tested).
- The board was only fetched while the Runs place polled, so the drawer now calls `refreshCron()`
  on open (gated on `reasoningCaps != null`, the door's own gate).
- ⚠️ Layout: the icon `Box` is pinned to 22dp and the badge uses `requiredHeight`/`requiredWidthIn`
  so it overflows the corner without growing the tile — the first cut let the badge add height and
  the Runs label sat lower than its neighbours. Text trims font padding (`includeFontPadding=false`,
  `LineHeightStyle.Trim.Both`) or the 9sp count rides a 14dp line and the circle is a lozenge.
- Release APK INSTALLED on the phone 09-02 05:31 CT (vc74 rebuild), drawer verified over ADB: `1`
  on Runs, labels level.
