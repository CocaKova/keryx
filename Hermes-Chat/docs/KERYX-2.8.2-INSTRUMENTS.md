# Keryx 2.8.2 — three instruments that had stopped telling the truth

Built 2026-09-03 on branch `keryx-2.8-bots` (vc78 / 2.8.2, on top of 2.8.1). 667 unit
tests green. Repo-root `keryx-2.8.2-{debug,release}.apk`. ⚠️ Not walked on a phone.

Jonny's ask (09-03, late): *"In the flight plan of Keryx the text is almost unreadable because
it's transparent and the text is white — may be only light mode, make sure it's not that way
for dark mode. Newer sessions or more recent active sessions aren't put at the top, leading to
digging. The context window amount/indicator is no longer in Keryx."*

---

## 1. The flight plan floated on a 3% tint

`FlightPlanStrip` is pinned over the transcript (`zIndex(1f)` in `ChatScreen`): the messages
scroll **under** it. Its floor was `onSurface.copy(alpha = 0.03f)` — a tint of the text colour,
not a surface — so whatever bubble was scrolling beneath became the strip's background. White
text over a light bubble in the void theme, ink over a dark bubble on paper. Both themes; light
just showed it more.

**Fix:** the strip stands on the theme surface at `FlightPlanFloor.ALPHA = 0.94` (the same
0.92-ish surface the composer chips and pills already use), with a 1dp hairline on its lower
edge that says "this does not scroll". Every colour on it was already an `onSurface` token, so
both themes are covered by the one change.

**Test:** `PaperContrastTest.flight plan floor keeps its text readable over any transcript` —
composites the floor over the worst backdrop each theme can produce (the other theme's ink, a
full-accent bubble, the dusk violet), lays the strip's faintest running-plan text over that, and
holds the pair to WCAG AA. The alphas live in `FlightPlanFloor` as pure constants so the test
measures the real numbers.

## 2. The roster's order was the wire's promise, not the app's

Everything server-side checked out live: `/api/sessions?order=recent` returns
newest-`last_active`-first (verified with the exact query the app sends), and a `state.db`
touch reached a WS client as `sessions.changed` in 0.2 s. The drawer already re-pulls on open.
What the app did NOT do is own the order: `getRooms()` was `bots + pending + server` in
arrival order, and the only thing that moved a row was the next successful list pull — floored
at one broadcast per 2 s server-side, then a REST round trip, and missed outright while a
backgrounded socket sleeps. A chat you were speaking into sat where the last pull left it.

**Fix — the invariant "the drawer orders by the newest activity the app knows of, whichever
side reported it":**
- `core/model/RosterOrder` (pure, tested): `byActivity` (stable sort, newest first, unstamped
  rows sink), `withLocalStamps` (a phone-side stamp wins only when newer — the server's answer
  is never moved backwards), `stamp` (5 s slack so a turn's per-token stream never rebuilds the
  roster per delta; the same map comes back inside the slack, callers skip the emit on identity).
- `DirectTransport._localStamps`: stamped in `sendMessage` (you spoke here) and beside
  `markBusy` on `message.*`/`tool.*` traffic (the agent is speaking here). `getRooms()` is now
  `bots + byActivity(withLocalStamps(pending + server))`. Bots keep the head — the Bots door
  orders them itself.
- `MainActivity.onStart` → `viewModel.refreshRoster()`: coming back to the foreground re-pulls
  the roster (the same cheap call the drawer makes on open; a no-op before the transport is up
  and on Matrix, where sync keeps the list live).

## 3. The context ring only ever drank from the Matrix side channel

`KeryxContextRing` was fed by `ChatViewModel._contextUsage`, which had ONE writer: the Hermes
Link SSE `usage` frame (`HermesStreamClient.Event.Usage`, keyed by Matrix platform/chat_id). On
the direct door the transport already folded the gateway's own `usage.context_used/context_max`
(rides `session.info` and `message.complete`) into `SessionMeta` — and nothing read it. So on a
gateway session the ring never lit; once the phone lived on the direct door, "the indicator is
gone".

**Fix:** `SessionMeta.contextGauge` (a real reading or null — never a half-reading, never the
gateway's `-1` post-compaction sentinel; tested in `ContextGaugeTest`), and `contextUsage` is
now `combine(sideChannel, currentRoom.flatMapLatest { direct.sessionMeta(it).map(gauge) })`
with the open room's direct reading winning. `ComposerFooter` already filtered by room id, so
the ring appears in the same place with the same colours (accent → amber past ¾ → red past 9/10,
tap for the exact "84k / 128k").

Gateway-side note: `_get_usage` sends the pair only when the compressor's `last_prompt_tokens`
is real (built-in compressor: always, once a turn ran). A brain whose engine never reports
prompt tokens would leave the ring dark on purpose — that is "unknown", not a bug here.

---

## Files

- `app/.../components/FlightPlanStrip.kt` — floor + hairline + `FlightPlanFloor` constants
- `app/src/test/.../PaperContrastTest.kt` — the floor test (+ an `over()` compositor)
- `core/.../model/RosterOrder.kt` + `core/src/commonTest/.../RosterOrderTest.kt`
- `app/.../transport/direct/DirectTransport.kt` — `_localStamps`, `touchSession`, ordered `getRooms()`
- `app/.../MainActivity.kt` — foreground roster pull
- `core/.../model/Cockpit.kt` — `SessionMeta.contextGauge` + `ContextGaugeTest.kt`
- `app/.../presentation/ChatViewModel.kt` — `contextUsage` drinks from both doors
- `app/build.gradle.kts` — vc78 / 2.8.2

## Walk (Jonny)

1. Ask for a numbered plan; the strip under the top bar reads in both themes while the
   transcript scrolls under it. Toggle theme mid-plan.
2. Open an old session from deep in the drawer, send one line, open the drawer: it is at the
   top before the answer finishes. Background the app while Desktop talks in another session,
   come back, open the drawer: that one is at the top.
3. After one turn on a gateway session, the ring sits at the right end of the composer footer;
   tap it for the figure. Compaction: the ring drops after the summary.
