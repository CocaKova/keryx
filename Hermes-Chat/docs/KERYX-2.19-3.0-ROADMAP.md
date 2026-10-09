# Keryx roadmap: 2.19 "Current" → 3.0 "The Herald"

## Context
Keryx is at v2.18.0 (vc128, 10-06) plus `eb15425` (the todo→todo_list Flight Plan fix, not released). Jonny wants 3.0 to be a big release to be proud of: a tutorial that feels intuitive, plus performance, polish, refined art and animation. Before that, 2.19 does the Hermes catch-up.

Decided 10-08:
- **3.0 headline:** the agent drives the tour.
- **Art:** refine the existing identity, not a new look.
- **Audience:** a public launch. 3.0 assumes a stranger with stock Hermes and no Sy.

The scan found the `todo` rename was not a one-off. Hermes renamed five tools on 08-29 (`e16ad33a9d`). It also now hides several tools behind a `tool_call` bridge and ships a `labels[]` field so that clients stop parsing tool names. Keryx parses names. So 2.19 is mostly "stop breaking on every `hermes update`". 3.0 is the launch.

Ground rules that apply to both releases:
- One minor release per milestone, fixes as patches.
- Build on VIRDARA via `ship.sh`. Release asset = `ship.sh --release` from a clean clone. Ship gate must be GREEN.
- Push, tag and release only when Jonny says go.
- Credit SOP. No session lines in git.

---

## 2.19 "Current": Hermes parity + groundwork for 3.0

### A. Drift fixes
| Fix | Where |
|---|---|
| Add `cronjob_manage`, `process_manage` (also keep the old names as aliases) | `core/.../model/ToolGrammar.kt:46,70,155`, `core/.../protocol/TranscriptBuilder.kt:223-230` |
| Render `labels[]` (`{kind,app,action,emoji,text}`) on `tool.start/complete`; fall back to the name only when there are no labels. This fixes `tool_call`-bridged `session_search`, `image_generate` and the others on stock configs | `DirectTransport.kt` tool path, `ToolGrammar`, `ToolTheater.kt:113` |
| Drive the Flight Plan from the `todo.updated` event (full snapshot + `revision`), with the tool-result parse as a fallback | `TodoPlan.kt:38`, DirectTransport event switch |
| Decline Desktop-only requests (`tour`, `preview.*`, `terminal.read`, `window.read`) with **4404**, not -32601. Today the phone can kill Desktop's tour or preview on a shared session | `transport/direct/GatewayRpc.kt:327` |
| Add `steer` to `DisplayKind` and render it as a steer chip, not a plain user bubble | `core/.../DisplayKind.kt` |
| Group compressed continuations by `_lineage_root_id` on `session.list`; key rows on `message_uid` | sessions list, Archive |
| ✅ Done (`abe1798`): one row per conversation via `_lineage_ids`, old ids forward to the tip, forks (`_branched_from`) nest under their parent. ⏭ `message_uid` deferred to 3.0: keying saves on it also means "open in context" has to find a message by uid | |
| Grammar entries for `kanban_*`, `computer_use`, `browser_*`, `video_analyze`, `x_search`, `ha_*`. Remove dead entries (`list_files`, `edit_file`, `session_search_recall`) | `ToolGrammar.kt` |
| Handle `approval.cancelled` and `tool.output_risk` (show a warning band on the tool row) | DirectTransport, `ApprovalCard` |

### B. Drift tripwire (so the next rename can't break things silently)
- `tools/hermes_drift.py`: reads the live Hermes registry (`model_tools` alias table + `registry`) and `apps/shared/src/gateway-contract.openrpc.json`, then diffs them against every tool name, method and event Keryx matches on. Keryx exports that list from a single `KnownWire.kt` table, which replaces scattered string literals.
- ✅ Done. Built as a declared table rather than a refactor: `KnownWire.kt` lists the names, and two tests keep it complete (`KnownWireTest` against ToolGrammar, `KnownWireSourceTest` against every dotted literal in the app). The script also checks each tool's preview argument against its schema. Its first run caught `clarify.questions`, `delegate_task.tasks` and `skill_manage.operations`, all blank previews before this fix.
- Run it in the ship gate. A mismatch is AMBER with a readable diff.
- Add it to the post-`hermes update` checklist in memory.

### C. Backlog quick wins
- On cold start, resume a session even when it's missing from the list (for example a cron run).
- A turn that continues within seconds no longer wears the INTERRUPTED card.
- Delete the dead `GroupChats.working` path. Fix the retire-twice title 400 and the `_`-profile room join.
- Fix the stale header in `proguard-rules.pro` (it says minify is off).

### D. Groundwork for 3.0
- **Measure:** a macrobenchmark module plus a generated Baseline Profile, run on the VIRDARA emulator. Record cold start, time to first message and frame drops while streaming (known: direct door ~5%). Write the numbers down in `docs/PERF-BASELINE.md` as the targets 3.0 has to beat.
- Turn on Compose compiler stability reports and commit the first report.
- **Tour spike:** decide how the tour reaches the phone (see 3.0 §1).
- ✅ **Decided (2026-10-09): option (a), a keryx-stream `keryx_tour` tool.** Plugins register tools with a `check_fn` (`hermes_cli/plugins.register_tool`), so the tool can be offered only while a Keryx client is attached, on stock Hermes, without an upstream change. To watch in 3.0: a tool that comes and goes changes the tool list, and that list is part of the prompt cache. Prefer "registered while keryx-stream is enabled, answers 'no phone attached'" over flapping, unless the cache cost measures as nothing.
- ✅ Compose stability reports (opt-in `-Pkeryx.composeReports`) and `docs/PERF-BASELINE.md` with the method, the first report and the 3.0 targets. Device numbers are pending: the phone was offline at build time; `tools/perf_baseline.sh` fills them in. ⏭ The macrobenchmark module and Baseline Profile move to 3.0 §2, next to the speed work they measure.

### E. keryx-stream 0.6
- ⏭ Moved to 3.0. It is a plugin release, independent of the APK, and its kanban hook push wants the 3.0 tour work in the same plugin release.
- Bump GAPS.md to Hermes 0.21.6.
- One test confirming hooks see the inner tool name for `tool_call`-bridged calls.
- Push kanban changes via the `kanban_task_*` hooks instead of Keryx polling `kanban_db`.

**2.19 status (2026-10-09):** 2.19.0 vc130 built (`0c9b286`), gate GREEN 1238, drift script CLEAN. A–D done as noted above; E moved to 3.0. Not pushed, tagged or released.

**2.19 is done when:** gate GREEN, drift script clean against live Hermes, on a stock config (no `tool_search` opt-out) cron/process/image tools render with verbs, PERF-BASELINE written. Release 2.19.0. Fixes go out as 2.19.x.

---

## 3.0 "The Herald": the public launch

### 1. Guided by the herald (headline)
One tour engine, three ways in:
1. **First-run tutorial.** The built-in tour, "quick" or "full", runs from the app's own steps. Works before any gateway is connected.
2. **Contextual first-visit hints.** One short spotlight the first time each space opens (Missions, Runs, Bots, Fleet, Tap-In, Artifacts). Seen flags go in `SettingsRepositoryImpl`. "Replay tutorial" lives in Settings.
3. **The agent points.** Ask "where do I set a cron?" and Hermes's `gui_tour` highlights the real button on the phone. The tour speaks Hermes's own protocol (`targets` / `show` / `start` / `next` / `prev` / `stop`, popover `title/text/side`, stable selectors), so a tour the agent writes and the built-in tour look the same.

**Engine (app side):**
- `Modifier.tourTarget(id, stable = true)` registers bounds in a `TourRegistry`.
- `TourOverlay` dims the screen, cuts out the target, places a popover, and offers Next/Prev/Skip. It respects ReducedMotion and TalkBack (it reads the popover).
- `targets` answers from the registry. Any target off-screen navigates there through `KeryxNav` first.

**Transport (decided in the 2.19 spike):** Hermes offers `gui_tour` only to `platform == "desktop"` sessions (`tui_gateway/server.py:1979`). Keryx sessions are `tui`. Two options:
- **(a) Preferred, stock-reproducible.** keryx-stream registers a `keryx_tour` tool (same schema as `gui_tour`). It pushes a tour frame to the phone over `/keryx/stream` and waits for the phone's answer on a new `/keryx/tour/respond`, with the same probe-then-full-deadline ladder Hermes uses. It is offered only while a Keryx client is attached.
- **(b)** An upstream proposal to let a client declare a surface that earns `desktop_ui`. Jonny authors that himself, only if (a) proves awkward.

**First run for a stranger:**
- Pair by QR: `hermes keryx pair` (a keryx-stream CLI) prints a QR with the URL and a scoped token, and the login screen gets a scan button. Manual entry stays.
- A **demo door**: a scripted, offline sample session (tool theater, Flight Plan, an artifact, an approval) so someone can feel the app before they own a gateway. The tutorial runs on it.
- Wording assumes stock Hermes ("your agent"), never Sy.

### 2. Speed (targets come from 2.19's PERF-BASELINE)
- **Cold start:** move `KeryxApp.onCreate` work (`KeryxApp.kt:131-188`: archive indexer, four observers, HandsSync, transport connect) off the main thread or make it lazy. Ship the Baseline Profile.
- **Streaming:** 0 frames dropped on the direct door.
  - Mark models `@Immutable`/`@Stable` per the stability report.
  - Hoist the ~180-line message item lambda (`ChatScreen.kt:739`) into keyed, skippable composables.
  - Cut `ChatScreen`'s 53 `collectAsState` calls down to a few screen-state flows.
- **Polling:** replace the 34 ad-hoc loops with one lifecycle-aware `Pulse` scheduler (shared ticks, all paused in background, one battery budget). Measure the background download rate against the 75 KB/h line from 2.13.10.
- **Breaking up big files:** split the files that slow every change.
  - `DirectTransport.kt` (3957 lines) → session, turn, events and requests pieces.
  - `ChatViewModel.kt` (3218 lines) → delegates.
  - No behaviour change, tests kept green.

### 3. Art & motion: refine, same identity
- Redraw the launcher icon and wordmark sharper (`tools/kerykeion_icon.py`, `KeryxWordmark.kt`). Add a splash animation from icon to wordmark.
- **One type system:** fold the Material `Typography` (`theme/Type.kt`) into `KeryxType`. Move the 41 raw `Color(0x…)` literals into tokens (CallScreen stays as it is, on purpose).
- **Motion language** in `KeryxMotion`: shared-element transitions between drawer and spaces, predictive back on every pushed screen, one easing and duration set, and a short "herald arrives" moment on first launch.
- Refresh the thinking styles and the Braille snake. Illustrated empty states for each space instead of one-liners.
- A check on every surface that floats over the transcript: it needs a floor and has to pass a contrast test (the 2.11.8 rule).

### 4. Polish
- **Turn action sheet** (asked for 09-21): copy raw/plain, regenerate, fork (`session.branch`), undo last turn, details.
- **Visible compaction** ("never know when compression is happening").
- Accessibility pass: TalkBack labels, 200% font scale, touch targets.
- Fix taps on details/cards/svg that work on the emulator but not on Jonny's phone. Repro on the device first.
- In-app "What's new in 3.0", run as a tour.

### 5. Ready for the public
- **Release keystore.** Today the build host's debug key signs everything. Changing keys forces one uninstall/reinstall, which wipes app data. Do it once, at 3.0, behind a settings export/import so nobody loses their gateways. Jonny's call on timing.
- README hero shots plus a short tutorial clip (reuse the keryx-ad pipeline), docs refresh, privacy note, credits near the top.
- Releases: `3.0.0-beta.N` as GitHub pre-releases → 3.0.0. Then an announcement worth tagging Teknium.

### Later (3.1+)
Rewind (`rollback.list/diff/restore`), phone as a tool host, live voice (`stt.streaming`), wake word branch, group notifications in background, editing room members.

---

## Verification
- **Each release:**
  - `ship.sh` gate GREEN, `hermes_drift.py` clean.
  - Release APK checked: `aapt2` package `chat.keryx.app`, apksigner digest (it changes at 3.0 with the new key).
  - Installed on the phone, no crashes in `dumpsys activity exit-info`.
- **2.19:**
  - On a test profile with stock `tool_search` deferral, a turn that schedules a cron and generates an image shows proper verbs and labels.
  - The Flight Plan updates from `todo.updated`.
  - With Desktop on the same session, its tour is not killed by the phone.
- **3.0:**
  - Fresh install with no gateway: the demo door and tutorial run end-to-end.
  - QR pair against a stock Hermes on VIRDARA.
  - Asking the agent "where are my crons?" spotlights Runs on the phone.
  - Macrobenchmark beats PERF-BASELINE on cold start and frame drops. Background KB/h no worse than 2.13.10.
  - TalkBack walk through the tutorial.
