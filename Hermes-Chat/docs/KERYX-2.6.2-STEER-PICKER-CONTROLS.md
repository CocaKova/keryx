# Keryx 2.6.2 — the send button steers, the picker tells the truth, the knobs get shelves (2026-09-01)

Three asks from Jonny's first walk of 2.6.1, all polish on surfaces that already existed:

1. "I would like the Talaria movement of steering. So instead of reasoning-and-steer — you type
   the message and the send button changes to steer, and also long press for queue."
2. "Model provider and picker. I like it, but if a provider isn't authorized or logged in yet,
   it shouldn't show. I added grok/x recently so that will be a good test."
3. "Let's see if we can categorize some settings in the gateway config in Keryx to make it a
   bit more workable and nice to view."

## 1. The send button IS the submit tree

**Was:** steering lived behind the reasoning pill (`Steer the agent…` → prefilled `/steer ` in
the composer). A verb hidden in a menu about something else; nobody found it. Queue had no
affordance at all on the direct door, and stop had none anywhere — the RPC verbs
(`session.steer`, `prompt.submit queued=true`, `session.interrupt`) sat on `DirectTransport`
with zero callers.

**Now — Talaria's C6 tree, harvested whole.** While a turn runs the primary circle changes glyph
and verb:

| composer | button | verb |
|---|---|---|
| text | **steer** (wheel) | direct: `session.steer`; declined → queued. Matrix: `/steer <text>` |
| text, long-press | **queue** (stack) | direct: `prompt.submit queued=true`. Matrix: `/queue <text>` |
| attachment / compacting / approval or blocking card up | **queue** | same |
| empty | **stop** (square) | direct only: `session.interrupt`. Long-press = the hint toast |
| `/command` | plain send | slash commands keep their console path, mid-turn or not |

"A turn runs" is every live sign, not just our own awaiting flag: `awaitingReply || liveStream
!= null || typingAgentIds.isNotEmpty() || liveTurnSigns || compacting`. A turn steered from the
desktop, entered mid-flight, or running across a relaunch never set our flag — and a plain send
against it on the direct door would interrupt the work (the exact silent damage `queued=true`
exists to prevent).

The placeholder teaches (`Type to steer this turn — ■ stops`), the footer line carries
`↪ steers the turn · hold to queue` while steerable, and the haptic tick fires on steer/queue/
stop like it does on send. New glyphs in `KeryxGlyphs.kt` (Steer / Stack / StopSquare, harvested
from `TalariaGlyphs`). `ReasoningMenu` lost its Steer row and `onSteer` plumbing.

⚠️ **Matrix door has no stop.** The gateway's `/stop` kills background processes, not the turn,
and no room verb interrupts — so on Matrix the empty-composer state stays a plain (dimmed) send.
`ChatViewModel.canInterruptTurn` = `direct != null` is the gate. `/steer` and `/queue` are
gateway `CommandDef`s with `busy_policy="dispatch"`, so they land on every platform regardless
of the operator's `busy_input_mode` (which is `queue` on SILAS — a plain message would have
queued anyway; on an `interrupt` gateway it would have killed the turn).

## 2. The picker shows what you signed into

**Found on the live gateway:** `GitHub Copilot` sat in the catalog as `authenticated: true` with
17 models — backed by a credential-pool entry the gateway seeded from the `gh` CLI
(`source: gh_cli`), never a Hermes login. The payload carries nothing that distinguishes that
row from Gemini's (`source: manual`) or Grok's (`manual:device_code`), so the phone could not
filter it. The gateway CAN: `explicit_only` — the desktop chat picker's dialect —
runs `is_provider_explicitly_configured`, which counts active_provider, `model.provider`,
provider env keys, and pool entries from explicit flows, and **deliberately excludes
`gh_cli` / `claude_code` / `qwen-cli`** borrowed tokens. Keyless providers (opencode-free)
stay by design; MoA stays only with a user-written preset.

- **Direct door:** `DirectTransport.modelOptions` now sends `explicit_only: true` (was false).
- **Matrix door:** the API server's own `GET /api/model/options` hardcodes
  `include_unconfigured=True` and ignores `explicit_only` (it exists for programmatic clients
  that want the universe). New plugin route **`GET /keryx/model/options`** answers in the
  explicit-only dialect; `HermesStreamClient.modelOptions` asks it first and falls back to
  `/api/model/options` on a 404 (older plugin). `GatewayError` grew `httpStatus` so the
  fallback branches on the code, not the message text.
- `ModelCatalog.usable` (authenticated ∧ models non-empty) is unchanged and still the app-side
  gate — the unauthenticated `configured-current` skeleton for `custom` never shows.

Verified live after the gateway restart (09-01 ~15:10 CT): `/keryx/model/options` →
anthropic · openai-api · **xai-oauth (Grok, 13 models)** · gemini · opencode-free · silas-brain.
Copilot and MoA gone. Jonny's test case (Grok added recently) is in; the one he never logged
into is out.

## 3. Gateway settings, shelved

**Was:** 69 knobs in 13 groups as one flat scroll under the reasoning dial and the brains list.

**Now:** the knobs section is a row plan (`buildControlRows`, pure, tested) the LazyColumn
paints:

- **Head:** "Gateway settings · 69", **Expand all / Collapse all**, a search box that cuts
  across every group by label, description or key, and a **chip rail** (`Behavior · 2`,
  `Display · 7`, …) — a tap opens that group and scrolls to its header.
- **Groups** are collapsible cards (name, count, one-line blurb from `GROUP_BLURBS`, chevron),
  collapsed by default, order = the deliberate `GROUP_ORDER` list with unknown groups after it
  alphabetically. Expanded set + query survive rotation (`rememberSaveable`; the set rides as
  a comma list because `Set` has no default Saver).
- **Search** shows only matching knobs and opens every group that has one; no hit → "Nothing
  matches". `KnobRow` untouched.

Reasoning dial, brain picker, log viewer and raw editor are unchanged in behaviour — they just
became rows of the same plan.

## 4. Traps

- **Talaria harvest, third time:** the glyphs came over verbatim (vector paths carry no tint;
  `PaperContrastTest`'s emerald/amber ban is about colours, not shapes).
- **`awaitingReply` is a 240 s guess on Matrix** (`NO_REPLY_MS`) — an agent that never answers
  keeps the composer in steer mode until it clears. Same as Talaria; not chased.
- **The chip rail's jump scrolls by index into the plan** — every row type must stay in the
  plan (`ControlRow`) or the index drifts; that is why the error line and footer are rows.
- **`/keryx/model/options` needs a gateway restart** — done on SILAS 09-01. Elsewhere the 404
  fallback keeps the picker working (with the wider list) until the plugin is updated.

## Status
- versionCode 74 / 2.6.2 — NOT released, NOT tagged (patch bump; fold more before cutting).
- 573 tests green (`:app:testDebugUnitTest` + `:core:allTests`; +6 `ControlRowsTest`),
  `assembleDebug` OK → repo-root `keryx-2.6.2-debug.apk` (md5 34d57904…).
- Payload synced to all 3 `keryx_stream.py` copies (`tools/check-payload-sync.sh` OK), gateway
  restarted, route verified.
- ⚠️ Phone off adb (no device on LAN/tailnet during the session) — **NOT walked**. Walk list:
  steer (tap mid-turn → "Steered" toast + local echo), hold → queue, empty → stop, Matrix
  `/steer` echo, picker lists Grok and not Copilot on BOTH doors, Controls chips jump +
  collapse + search + rotation.
