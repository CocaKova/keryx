# Keryx 1.23 — "One Dream"

*The whole app becomes the place.*

Two threads, one release:

1. **Gateway customization grows up** — the Controls whitelist expands from 5 knobs into
   Missions-engine dials, display extras, compression dials, and an **Integrations panel**
   (toolset on/off toggles — built for the Home Assistant toolset before it exists).
2. **Coherency** — Keryx has a real design language (Cinzel wordmark + amber→gold sheen,
   dusk gradient, braille snake, breathing dot, two-accent amber/violet thread) but it only
   lives in the chat top bar, the drawer, and the Agent Hub. Everything else is stock
   Material 3 — the Missions board worst of all. This release extracts the language into a
   small design system and applies it **everywhere**, so the app reads as one dream instead
   of a dream with a settings app stapled to it.

Decisions locked with Jonny (2026-07-17): themed **vertical** Missions flow (no lane pager);
knob scope = Missions engine + Display extras + Compression dials + Integration toggles
(Agent-behavior knobs deliberately skipped); coherency sweep = **whole app**.

---

## Phase A — the Keryx design system

New `presentation/ui/design/KeryxDesign.kt` (tokens) + shared components. Nothing invents a
new look — everything is extracted from the surfaces that already feel right (Hub header,
wordmark, hub cards).

**Tokens**
- Radius scale: `chip 8` · `field 12` · `card 14` · `sheet/dialog 20` — replaces the ad-hoc
  6/8/12/16/20dp scatter.
- Card recipe: `surfaceVariant @ 0.35` fill + `outline @ 0.25` 1dp border (the Hub/Missions
  card that already works), with optional status tint + optional breathing border.
- Type roles: `SpaceTitle` (letter-spaced caps, the "AGENT HUB" voice), `SectionLabel`
  (small caps + dot + count), `MetaMono` (11sp monospace for ids/urls/ages).
- The dusk gradient (surface → tertiary 10% at the foot) as a shared Brush builder.

**Components**
- `KeryxSpace(title, liveSlot, actions, onClose)` — the full-screen space scaffold: dusk
  gradient, emblem slot (braille snake), letter-spaced title, breathing live slot, close X,
  `FlingTamer`-style scroll discipline. Hub, Missions, and Call all sit inside it.
- `KeryxSectionHeader(label, color, count)` — the one section voice everywhere.
- `KeryxCard(...)` — the card recipe; `breathing = true` animates a slow border/dot pulse
  (gated by `ReducedMotion`).
- `KeryxSheet` / dialog chrome — 20dp shell, consistent title row, for every
  ModalBottomSheet/Dialog in the app.
- Motion spec: one enter/exit (fade + slight rise) for spaces and sheets; one breathing
  animation helper. All respect `ReducedMotion`.

**Refit the Agent Hub onto KeryxSpace** — it's the donor of the pattern, so this should be
a visual near-no-op; it proves the scaffold before Missions lands on it.

## Phase B — Missions, reborn (themed vertical flow)

`MissionsScreen.kt` rebuilt on the system; behavior contracts kept: 20s RESUMED-gated poll,
no drag (the dispatcher owns transitions), unknown statuses get trailing sections, board
NOT cache-seeded, never seed `mission_events_cursor` with 0.

- **Space**: `KeryxSpace` with "MISSIONS" wordmark; live slot = breathing dot + "N running"
  board pulse (falls back to board name).
- **Lane-jump chips**: sticky horizontal chip row under the header — one chip per non-empty
  status with count; tap = `animateScrollToItem` to that section header.
- **Lanes**: each section gets a status-tinted left rail; `KeryxSectionHeader` for the label.
- **Cards**: `KeryxCard` — running cards breathe (pulsing dot + slow border shimmer),
  blocked cards carry an error tint, done cards dim. Keep bell/P#/fail-count/age metadata.
- **Detail sheet**: `KeryxSheet` chrome — status band, themed title, Result as an accent
  block, comments restyled as author-chip + bubble (the chat voice), alert switch kept.
- **Create dialog**: `KeryxSheet` chrome + shared chip style; triage/notify switches kept.
- **FAB** → themed accent container consistent with the space.
- Empty state (braille snake) stays — it was already right.
- Polish if cheap: pull-to-refresh on the board list.

## Phase C — gateway customization (plugin + Controls)

Server side: `~/.hermes/silas_ext/payloads/keryx_stream.py` `_CONFIG_KNOBS` grows, each knob
whitelisted/validated/clamped exactly like the existing 5, `KERYX_CONFIG_LOCKED` honored
per-key. **New `float` knob kind** (compression threshold) lands in plugin + app + tests.

| Section | Knobs (config path) | Notes |
|---|---|---|
| Missions | `kanban.dispatch_interval_seconds` (15–3600), `kanban.failure_limit` (1–10), `kanban.auto_decompose` (bool), `kanban.auto_decompose_per_tick` (1–10), `kanban.default_assignee`, `kanban.orchestrator_profile`, `kanban.dispatch_stale_timeout_seconds` (600–86400) | assignee/profile choices built from the routing map's profiles + blank |
| Display | `display.timestamps` (bool), `display.memory_notifications` (enum — read choices from hermes), `display.compact` (bool), `display.tool_progress` (enum) | verify enum choice lists against hermes source at build time |
| Compression | `compression.threshold` (float 0.3–0.9), `compression.protect_last_n` (10–200), `compression.hygiene_hard_message_limit` (100–2000) | clamped hard; bad values degrade marathons |

**Integrations panel (the HA ask)** — new routes, not knobs:
- `GET /keryx/integrations` → toolset registry (`/v1/toolsets` internals) ∪
  `agent.disabled_toolsets`, each `{name, enabled, core}`.
- `PUT /keryx/integrations` → toggle ONE toolset by rewriting `agent.disabled_toolsets`.
  Only names the gateway itself reports are accepted (structural whitelist); a small CORE
  set (e.g. terminal/files) is refuse-to-disable; applies-scope = "next session".
- When the Home Assistant toolset lands, its toggle appears with zero new code.
- ⚠ **Must check reapply.py's config-guard first** (the lean-Matrix-toolset guard from the
  toolset audit): phone toggles must not fight the guard or get silently reverted — either
  scope the guard to its own keys or exempt `disabled_toolsets`.

App side: `ControlsTab` regrouped with `KeryxSectionHeader`s — Reasoning / Brain / Missions
/ Display / Compression / Integrations / Gateway / Logs. Float knob = stepper/slider + save.
Missions section is also reachable from the Missions space (small gear in the header).

## Phase D — whole-app coherency sweep

Visual-only diffs, one surface per commit, revertable:
SettingsDialog, NewChatSheet, SkillForgeSheet, PetPickerSheet, SessionPruneDialog,
QuickRoomsDeck, NavigationDrawer polish, CallScreen onto KeryxSpace, and every stray
AlertDialog → KeryxSheet chrome. Radii → token scale, headers → SectionHeader, dialogs →
sheet chrome, transitions → the one motion spec. **No behavior changes in this phase.**

## Phase E — ship

- Tests: knob fixture parsing incl. float kind; integrations JSON parsing; lane-jump
  section-index logic as pure functions.
- Version 1.23.0 (vc43), tag, GitHub release APK.
- Plugin payload: edit in `silas_ext/payloads/`, then **manual `cp` to
  `~/.hermes/hermes-agent/gateway/keryx_stream.py`** (reapply only WARNS on drift) +
  gateway restart **when idle** (SILAS room gets the shutdown notice).
- Public keryx-stream mirror stays behind until upstream #65077 resolves — unchanged.

## Standing gotchas (carry-over)

- minSdk 24, no java.time — `HubJson.isoToMillis` is the sanctioned ISO parser.
- R8 is ON — anything reflective needs keep rules (the JNA/E2EE lesson); pure Compose is fine.
- adb to the phone is DEAD again (30010 = pairing port) — 1.22.3 is still awaiting sideload;
  1.23.0 supersedes it, one sideload catches both.
- Gateway error dialects differ per route family; `apiCall` handles both — keep it that way
  for the new routes.
