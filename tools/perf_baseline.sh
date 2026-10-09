#!/usr/bin/env bash
# perf_baseline.sh — the numbers 3.0 has to beat (Keryx 2.19, docs/PERF-BASELINE.md).
#
# Measures the INSTALLED release app on the connected device, over adb:
#   cold start   `am start -W` after a force-stop, N runs: TotalTime (ms) per run, then median
#   frames       `dumpsys gfxinfo` after the drawer is opened and the session list flung,
#                reset first: janky-frame %, and the 50/90/99th percentile frame times
#
# Nothing is installed or changed beyond starting and stopping the app; your login stays.
# Streaming jank needs a live turn, so it is a separate, manual pass (see the doc).
#
# Usage: tools/perf_baseline.sh [runs=7]
set -uo pipefail
PKG=chat.keryx.app
RUNS="${1:-7}"
ADB="${ADB:-adb}"

"$ADB" get-state >/dev/null 2>&1 || { echo "no device on adb" >&2; exit 3; }
echo "device:  $("$ADB" shell getprop ro.product.model | tr -d '\r') · Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r')"
echo "app:     $("$ADB" shell dumpsys package $PKG | grep -m1 versionName | tr -d ' \r')"

times=()
for i in $(seq 1 "$RUNS"); do
  "$ADB" shell am force-stop $PKG
  sleep 2
  t=$("$ADB" shell am start -W -n $PKG/.MainActivity 2>/dev/null | grep -m1 TotalTime | tr -dc '0-9')
  times+=("$t"); echo "  cold start $i: ${t} ms"
  sleep 4
done
median=$(printf '%s\n' "${times[@]}" | sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}')
echo "cold start median: ${median} ms (${RUNS} runs)"

# Frames: open the drawer with an edge swipe, fling the list a few times, read gfxinfo.
"$ADB" shell dumpsys gfxinfo $PKG reset >/dev/null
read -r W H < <("$ADB" shell wm size | tail -1 | grep -oE '[0-9]+x[0-9]+' | tr 'x' ' ')
"$ADB" shell input swipe 5 $((H/2)) $((W*3/4)) $((H/2)) 250
sleep 1
for _ in 1 2 3 4; do
  "$ADB" shell input swipe $((W/3)) $((H*3/4)) $((W/3)) $((H/4)) 120; sleep 0.6
  "$ADB" shell input swipe $((W/3)) $((H/4)) $((W/3)) $((H*3/4)) 120; sleep 0.6
done
"$ADB" shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile" | sed 's/^/  drawer: /'
"$ADB" shell input keyevent KEYCODE_BACK
