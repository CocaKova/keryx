# Keryx performance baseline (2.19.0)

The numbers 3.0 has to beat, measured before any speed work. Each 3.0 change re-runs the same
measurements and reports against this page.

## How to measure

- **Cold start and drawer frames:** `tools/perf_baseline.sh` measures the installed release app
  over adb. It runs `am start -W` after a force-stop seven times and takes the median, then flings
  the session drawer and reads `dumpsys gfxinfo`. It doesn't install or change anything.
- **Streaming frames:** `tools/perf_stream.sh` does this pass on its own. By hand: `adb shell dumpsys gfxinfo chat.keryx.app reset`, send a
  prompt that streams for about 30 s with tools, then `dumpsys gfxinfo chat.keryx.app`. Read
  "Janky frames" and the 90th/99th percentiles. A known reference: the direct door dropped
  about 5% of frames while streaming (2.17 notes).
- **Which composables a turn costs:** build the benchmark type (`./gradlew :app:assembleBenchmark`:
  release, profileable, composition tracing), `adb install -r` it over the release build (same
  key, so the login stays), then `tools/perf_trace.sh out.pftrace` and
  `tools/perf_composables.py out.pftrace`. Use the VIRDARA emulator for this: its frame times
  are meaningless (92% of each frame waits on the software GPU), but the main thread's
  composition work is real CPU. Compare builds per streaming tick (divide by ChatScreen's count).
- **Compose stability:** `./gradlew :app:compileReleaseKotlin -Pkeryx.composeReports` writes the
  compiler's reports to `app/build/compose_compiler/`. Build on the build host, never on Spark 1.

## Device numbers

Measured on 2.19.1 (versionCode 139, the release APK), Galaxy SM-S948U, Android 17,
2026-10-09. The phone was off the LAN for 2.19.0, and 2.19.1's speed fixes were to live turns,
not startup.

- **Cold start:** `LaunchState: COLD`. The 7 runs took 267, 175, 185, 165, 135, 116 and 123 ms.
  They get faster as the system warms its caches, so compare medians from runs done the same
  way.
- **Drawer frames** (Menu button, then 8 flings of the session list; 120 Hz display): 892 frames,
  4 janky (0.45%). Frame times are 12 ms at the 50th percentile, 15 at the 90th and 17 at the
  99th. The legacy counter says 163 janky (18.27%). It judges each frame against a fixed budget,
  while the modern one only counts frames that missed their own deadline. The target uses the
  modern count. A first attempt opened the drawer with an edge swipe, which is BACK under
  gesture navigation, so it measured the launcher. The script now taps Menu and stops with
  exit 3 (AMBER) if Keryx loses focus or fewer than 60 frames render.
- **Streaming frames** (`tools/perf_stream.sh`, 2026-10-10): a new session, one prompt that ran
  2 terminal tools and then streamed about 3,000 characters for about 38 s. 3,061 frames, 190
  janky (6.21%, legacy 5.72%). Frame times are 10/12/19 ms at the 50/90/99th percentiles. 47
  frames had a slow UI thread, 7 were slow to issue draw commands, and none missed vsync or had
  slow bitmap uploads. So the cost is composition on the main thread, not the GPU. This is one
  run, and it agrees with the 2.17 note of about 5%.

| Measure | 2.19.1 | 3.0 target |
|---|---|---|
| Cold start, median `TotalTime` | 165 ms | −30% |
| Drawer fling, janky frames | 0.45% (99th pct 17 ms) | < 2%; hold it, cut the 99th pct |
| Streaming, janky frames | 6.21% (one run, p99 19 ms; 2.17 note ~5%) | 0 dropped on the direct door |
| Background download | 75 KB/h (2.13.10) | no worse |

## Where a streaming tick goes (emulator trace, 2026-10-10)

From `tools/perf_trace.sh` on the benchmark build, VIRDARA emulator. A tick is one transcript
publish: about 10 a second on the direct door. ChatScreen recomposes once per tick.

| Composable (2.19.1, a 51 s turn with reasoning) | Runs | Total incl. children |
|---|---|---|
| `ChatScreen` (the whole screen body) | 69 | 681 ms (self 284) |
| `RoomRow`, the drawer's rows behind the closed drawer | 354 | 511 ms |
| `MessageContent`, the streaming reply's markdown | 48 | 327 ms |
| `ReasoningDisclosure` | 178 | 301 ms |
| `AnimatedVisibility` | 580 | 274 ms |

- **The screen body:** 4–4.5 ms per tick on the emulator. Most of it is re-running the screen's
  53 `collectAsState` and ~40 effects (`collectAsState` and `LaunchedEffect` each ran about
  4,000 times in one turn).
- **The streaming bubble:** 5–6 ms per tick, re-parsing the whole reply's markdown each time.
- **Drawer rows:** about 5 per tick on 2.19.1, about 2 after perf step 1 (rows now compare by
  equality).
- **Message rows:** about 1 bubble per tick recomposes (the streaming one) both before and after
  step 2. A fresh session has one row of yours. Step 2's fix (callbacks no longer capture
  `messages`) shows in a long chat with many of your own rows on screen.

## After perf steps 1–5 (emulator trace, 2026-10-10)

The same streamed turn on the benchmark build, both with reasoning (2.19.1 a 51 s turn, now a
44 s turn with 22 s of thinking). Runs per second of turn:

| Composable | 2.19.1 | Steps 1–5 |
|---|---|---|
| `ChatScreen` | 1.35 | 0.39 |
| `RoomRow` (behind the closed drawer) | 6.94 | 0.30 |
| `CloudBanner` (the working cloud) | 5.67 | 0.18 |
| `ReasoningDisclosure` | 3.49 | 1.39 |
| `MarkdownParagraph` | 5.31 | 1.32 |
| `AnimatedVisibility` | 21.3 | 15.0 |
| `collectAsState` | 151 | 55 |

The emulator draws about 6 frames a second. The rows that fell most (`RoomRow`, `CloudBanner`,
the thinking dot, the tool run's border) were recomposing once per frame, so on a 120 Hz phone
they ran up to 120 times a second. Expect the device win to be larger than this table.
Milliseconds per tick aren't compared here: they move with how much of a turn is thinking versus
text, and one turn is never the same as the next. The phone's gfxinfo pass is the measure.

Not from this work: on the emulator, message text shows faintly through the composer when a
bubble sits behind it. 2.19.1 does the same. It goes to the redesign section.

## Compose stability (release, 2026-10-09)

Strong skipping is on, so an unstable parameter doesn't stop a composable from skipping. It is
compared by identity instead of equality, though. Every fresh list the transport emits holds new
instances, so a row whose data didn't change still recomposes.

| | |
|---|---|
| Restartable composables | 2005 |
| Skippable | 1265 (63%) |
| Arguments known unstable | 183 of 38,480 |
| Arguments of uncertain stability | 784 |
| Classes inferred unstable | 95 of 311 |

The unstable parameters that matter are the core models the chat and drawer draw: `Message`
(9 composables), `BotProfile` (6), `CronJobCard` (5), `Chart` (5), `ModelCatalog` (4),
`ToolCall` (3), `RoomProfile` (3). They live in the `core` KMP module, which the Compose
compiler doesn't process, so it can't see that they are immutable. They are: `val`-only data
classes holding read-only collections (checked 2026-10-09).

**First 3.0 change:** add a stability configuration file declaring `chat.keryx.core.model.**`
(and `kotlinx.serialization.json.JsonElement`) stable. Then re-measure. Equal data then skips by
equality, which is what the message list and drawer need during a stream.
