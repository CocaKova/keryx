# Keryx 2.6.2 — cron pins: the Runs door keeps what you keep (2026-09-02)

Jonny: "it would be really cool if we could pin cron job outputs in the gateway side of it.
It needs to look beautiful and navigate beautiful."

## 1. What was missing

A scheduled report is read once and buried under the next forty. The Runs door (2.6.0)
answered "what landed since I looked" with the arrivals rail; it had no answer to "where is
the one I want to come back to" — the quarterly plan the Monday brief produced, the arXiv
sweep that found the paper. Those runs also aged out: the gateway's auto-archive sweep hides
stale sessions, and a run you cared about disappeared from the door on the sweep's clock.

## 2. The gateway already had the flag

The api server (`gateway/platforms/api_server.py`, the Hermes Link) serves the Desktop
sidebar's durable keep flag on every session row and accepts it on PATCH:

| route | what |
|---|---|
| `GET /api/sessions?source=cron&limit=150` | list rows carry `pinned`; the SINGULAR `source` filter is honoured (the plural `sources` is the dashboard router's and is ignored here — verified live both ways); `include_pinned` is the list's default, so a kept run that has aged out of the window is back-filled |
| `PATCH /api/sessions/{id} {"pinned": true}` | sets the keep flag; pinned sessions are exempt from `sessions.auto_archive` (`hermes_state.py` `set_session_pinned`) |

So "pin a run" is `pinned=true` on its session. **No server change.** The pin lives on the
gateway: it survives a reinstall, shows in every client, and keeps the transcript.

## 3. What changed

**:core** — `CronRun.pinned` (server truth, never a phone ledger); `CronJobCard.pinnedCount`;
`CronPins` — `of(cards)` (the shelf, newest first), `ids(cards)`, `withPin(cards, id, pinned)`
(a value flip that touches one run and nothing else, so an optimistic move and its revert are
both exact). `CronPinsTest` pins the rules, including that pinning never touches unread.

**Client** — `HubSession.pinned` parsed (absent = false, older gateways keep working;
`HubJsonTest`); `cronSessions` asks the gateway for `source=cron` so the page is 150 runs, not
150 rows of which some are runs (the client-side filter stays as the belt to that brace);
`sessionPin(id, pinned)` PATCHes.

**HubDelegate** — `CronBoard.pinned` / `isPinned` / `isRun`; `cronSetPinned(runId, pinned)`:
flips the shelf NOW, PATCHes, refreshes on success (the list confirming and back-filling),
flips back and says why on refusal. The screen never shows a pin the server doesn't hold.

**Runs door** (`RunsSpace.kt`):
- **Pinned shelf** above the arrivals rail — what you chose to keep, before what merely
  arrived. Ink on paper rather than accent-tinted on purpose: news asks to be read; the shelf
  has been read and is here to be found again. Each row: job identity bar, job name, a DATE
  past 24 h ("Aug 12", not "21d ago"), the report's own headline and two lines of lead, a
  filled pin. Header folds the shelf; past five, "Show all N".
- **One run menu everywhere** a run is a row (shelf, rail, card list): long-press → *Pin — keep
  this run* / *Unpin*, *Mark read* (only when new), *Open*. Haptic on the press, same as the
  drawer.
- Pinned rows in card lists wear a small filled pin; a job card shows a quiet pin count next
  to its "N new" chip (a count, not a badge — a pin is a decision already made).

**Top bar** (`HermesApp.kt`) — over an open run (a session the Runs board knows, never a
conversation) a pin glyph sits with the call button: read the brief, tap the pin. Filled and
accent-tinted when kept.

**Glyphs** — `KeryxGlyphs.Pin` / `PinFilled` (a pushpin: head, shoulders, needle) in the one
icon family. The Bookmark glyph stays the Archive's (kept MESSAGES); a pin is a kept SESSION.

## 4. Walk (Jonny, on device)

1. Runs door → long-press a run in a card → *Pin — keep this run* → shelf appears on top with
   the run's headline; toast "Pinned — kept on the gateway".
2. Open that run (direct door: as a room) → the top bar shows a filled pin → tap → unpinned,
   shelf row gone, card row loses its glyph.
3. Pin ≥ 6 runs → shelf shows five + "Show all N"; header tap folds it.
4. Desktop parity: a run pinned here shows pinned in the Hermes Desktop sidebar's session
   list and vice-versa (same flag).
5. Kill Hermes Link (Settings) → long-press → Pin → toast says the link is off, nothing flips.

## 5. The diff feed (same evening)

Jonny: "let's go with the diff feed, but I also don't know what that is."

**What it is.** A daily job produces forty near-identical reports; the reader's question on the
forty-first is "what does it say that yesterday's didn't". Each job card now answers that in
one line under the headline — `since last run · +3 new · 1 updated · 2 gone` (or `same as last
run`, dimmed) — and, opened, lists the new (`+`), changed (`~`) and gone (`−`) lines in the
report's own words. Arrivals-rail rows wear the short badge (`+3`, `~2`, `−1`) beside the clock.

**How.** `core/model/CronDelta.kt` — `CronDeltaCalc.compute(previous, latest)`: the two newest
reports' substantive lines (fences, rules, bracketed machine lines and markdown chrome dropped —
the digest's shape rules) compared by a normalised key: lower-cased, dates / clock times /
"N ago" folded OUT (a timestamp is not news), numbers kept IN ("3 PRs" → "5 PRs" is news). Exact
key = kept; leftovers with ≥ 0.6 word-set overlap = the same line changed in place (UPDATED,
latest wording shown), the rest = added / removed. Lines under 12 chars never make news.
Deterministic and local on purpose — a brain call per card per poll would answer differently
each time and be unavailable exactly when the gateway is busy. `CronDeltaTest` ×8.

`HubDelegate` caches the REPORT text per run (`cronReport`) and derives both digest and delta
from it, so a card that has shown its headline has paid for half its delta already.

## 6. The hard line in the sky (same evening)

Jonny: "the colors in the background go from a darker shade to a lighter shade from the left to
the right of the screen in a really harsh way, like a hard line" — and, minutes later, "a big
box is moving across the screen".

Root: the ambient void's two accent pools were a separate `Canvas` of Compose radial gradients
painted OVER the dithered sky shader. Skia gradients are not dithered; the gaussian tail stepped
through the last two 8-bit levels above black in ~80 px bands, and on an OLED a two-level step
next to black is an edge — the pool's boundary read as a hard line, and since the pool drifts on
a 150 s triangle, as "a box moving". The 08-19 fix dithered the shader and softened the stops;
it never touched the layer the line was in.

Fix: `AmbientVoid.kt` deleted; the pools are drawn inside `KeryxDuskSky`'s AGSL (gaussian in
screen-width units, `uPhase` uniform, same drift, still stilled by Battery Saver), so the whole
backdrop is float until the one dither. The dither is now triangular (two hashes, ±1 LSB) instead
of a flat 0.8 LSB, and the `fract(sin(…))` hash is replaced by a sine-free one — on mobile GPUs
`sin()` of pixel-scale arguments degrades into structure, and structured dither is not dither.
