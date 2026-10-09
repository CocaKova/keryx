# Keryx performance baseline (2.19.0)

The numbers 3.0 has to beat, measured before any speed work. Each 3.0 change re-runs the same
measurements and reports against this page.

## How to measure

- **Cold start and drawer frames:** `tools/perf_baseline.sh` measures the installed release app
  over adb. It runs `am start -W` after a force-stop seven times and takes the median, then flings
  the session drawer and reads `dumpsys gfxinfo`. It doesn't install or change anything.
- **Streaming frames (manual):** `adb shell dumpsys gfxinfo chat.keryx.app reset`, send a
  prompt that streams for about 30 s with tools, then `dumpsys gfxinfo chat.keryx.app`. Read
  "Janky frames" and the 90th/99th percentiles. A known reference: the direct door dropped
  about 5% of frames while streaming (2.17 notes).
- **Compose stability:** `./gradlew :app:compileReleaseKotlin -Pkeryx.composeReports` writes the
  compiler's reports to `app/build/compose_compiler/`. Build on the build host, never on Spark 1.

## Device numbers

Pending: the phone was off the LAN when 2.19.0 was built (2026-10-09). Fill in from
`tools/perf_baseline.sh` on the first install.

| Measure | 2.19.0 | 3.0 target |
|---|---|---|
| Cold start, median `TotalTime` | – | −30% |
| Drawer fling, janky frames | – | < 2% |
| Streaming, janky frames | ~5% (2.17 note) | 0 dropped on the direct door |
| Background download | 75 KB/h (2.13.10) | no worse |

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
