# Keryx 2.0 — "The Dream Rebuild"

The biggest milestone since 1.0: the app becomes a *place* — surreal to look at, liquid to
touch, and structured to grow. Two halves, one project: the magic and the skeleton that
makes the magic (and everything after it) possible.

## Principles

1. **Fluidity first.** Springs over tweens. Gestures over taps where a gesture is natural:
   predictive back with the page physically following the finger, swipe-to-dismiss spaces,
   drags that settle with momentum. Nothing teleports; everything has mass. The bar is
   "feels like iOS," achieved with Compose physics.
2. **Keryx is its own thing.** gardenofnull donates a *sensibility* — void depth, glow
   accents, surreal restraint — not its skin. Keryx keeps its identity: the dusk gradient,
   the braille-snake, the letter-spaced voice, the user-tintable two-accent system
   (amber × dusk-violet by default). 2.0 turns that identity up to dreamlike; it does not
   replace it with the website's palette.
3. **Small magic × big magic.** Headline moves (page transitions, ambient depth) carry the
   first impression; micro-enchantments carry the daily feel: the streaming bubble growing
   with a soft aurora shimmer while tokens arrive, the send button releasing a puff, a
   space settling into place with a glow-breath. Every enchantment respects reduced motion
   and battery saver (`rememberReducedMotion` is already the app-wide gate).
4. **Every commit shippable.** No big-bang branch. Each phase lands as a series of commits
   where the app builds, tests pass, and the phone could take the APK that night.

## Phase 1 — The skeleton (navigation)

Today: full-screen surfaces are `Dialog`s toggled by scattered `mutableStateOf(false)`
booleans (HermesApp: hub, call; drawer: settings, missions, archive; plus sheet targets).
No back stack, no transition control (Dialogs pop), no deep links, predictive back is
whatever the Dialog dismiss does.

Build a small **owned navigation layer** (`KeryxNav`, ~200 lines) rather than adopting
Navigation Compose:

- `sealed interface KeryxDest` — typed destinations (`Chat` root, `Archive`, `Missions`,
  `Hub(tab)`, `Settings(section)`, …). Adding a page = adding a type.
- A `SnapshotStateList<KeryxDest>` back stack owned by one `KeryxNavState`; `push`, `pop`,
  `replace`. Chat is the permanent root.
- One `KeryxNavHost` composable renders the stack with **our** transition (the sink/rise
  void transition, Phase 2) via `AnimatedContent` + `PredictiveBackHandler`
  (androidx.activity), so the back gesture *scrubs* the transition — the leaving page
  follows the finger and springs home if released early. This is the fluidity flagship
  and the reason not to use Navigation Compose: we own the gesture end-to-end with zero
  dependency weight (R8 stays lean).
- Deep links: `MainActivity` maps intents (`keryx://archive`, assist intent, future
  widgets) to an initial stack. This is the doorway Phase 5 walks through.
- Migration is mechanical and incremental: one surface per commit moves from
  `if (showX) Dialog { … }` to a destination; `KeryxSpace` loses its `Dialog` wrapper
  (becomes a plain full-size surface) and gains swipe-edge dismissal for free via the
  shared host. Sheets (SkillForge, NewChat, pickers) stay sheets — they're transient
  chrome, not places.

## Phase 2 — The look (visual foundation)

- **Depth**: rebuild the void. Deeper background scale (near-OLED-black base, elevated
  glass surfaces), the existing amber aurora joined by a slow **ambient drift** — two or
  three vast radial glows in the accents at ~4–6% alpha, drifting over minutes (Canvas,
  one infinite transition, paused under reduced motion).
- **Type**: bundle an OFL display serif (Cinzel or a better-fitting sibling) for wordmark,
  space titles, and section voice only — body stays the system sans. The display face is
  what tips "app" into "artifact."
- **Effects vocabulary** (all in `KeryxDesign.kt`, tokens not one-offs):
  - `shimmerBorder()` — conic sweep ring brush for active/breathing cards (the running
    mission, the live turn), replacing flat border pulses.
  - Glow shadows: accent-tinted soft shadows on floating elements.
  - `rune divider` equivalent: the section hairline gets a faint gradient + center glyph.
- **Motion defaults**: a `KeryxMotion` token set — one spring spec family (stiffness/damping
  tuned soft), the arcane easing curve for non-spring cases, standard durations. All
  existing `tween(300)`-style one-offs migrate to tokens.
- **Page transition**: sink-into-the-void / rise-out-of-it (translate + blur + dim),
  driven by the nav host and scrubbed by predictive back.
- User accent overrides keep working — every new effect derives from the two accent slots.

## Phase 3 — Micro-enchantments

- **Streaming bubble**: while a reply is growing, its edge carries a slow aurora shimmer
  (sweep along the border between the two accents) and new text fades in with a soft rise;
  the shimmer exhales once and stills when the turn completes. THE signature detail.
- Send: the message lifts off the composer with a spring and a tiny accent puff.
- Space arrival: content staggers in (fade-in-up, 40ms steps, first open only).
- Archive catch-up / mission completion: a single glow-breath, not a toast.
- **Haptic grammar**: one tick vocabulary (light tick on gesture commit, soft double on
  completion), behind the existing haptics setting.

## Phase 4 — Organization (information architecture)

With real pages: Chat / Archive / Agent / Settings as first-class destinations. The Hub's
tabs (Status, Controls, Jobs, Sessions, Skills, Tools) become sub-destinations that can
grow into pages. Settings regrouped into categories (Connection, Appearance, Voice,
Notifications, Security) on its own page stack — the drawer slims down to rooms + doors.

## Phase 5 — The assistant doorway

Keryx as a selectable Android assist app: `ACTION_ASSIST` activity → deep link into a
quick-ask composer (voice-ready) over whatever the user was doing. Minimal viable first;
a full `VoiceInteractionService` overlay session is a later deepening, not 2.0 scope.

## Non-goals for 2.0

- No feature additions beyond the assistant doorway (agent-facing feature batch is its own
  future milestone).
- No Navigation Compose / Hilt / module split — the skeleton stays small and owned.
- No light-theme redesign beyond keeping it functional; the dream look is dark-first.

## Release

Version 2.0.0 (versionCode continues linearly). Same delivery path: GitHub release APK,
debug-cert signing, adb install when the phone is reachable.
