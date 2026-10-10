#!/usr/bin/env bash
# Perfetto trace of one streamed turn: new session, one prompt with tools, trace over the turn.
# Needs the benchmark build type installed (`./gradlew :app:assembleBenchmark`: release, profileable,
# composition tracing), so the trace names the composables that ran. Same applicationId and key as
# release: `adb install -r` over a logged-in release build keeps the login. Starts a new session
# from the top bar, so it leaves one short test chat. The end of the turn is read from the brain's
# vLLM /metrics (BRAIN_METRICS). Analyse with tools/perf_composables.py (pip install perfetto).
# Usage: ANDROID_SERIAL=<serial> tools/perf_trace.sh out.pftrace
set -uo pipefail
PKG=chat.keryx.app
OUT="${1:?out file}"
PROMPT='Use the terminal to run uptime and then df -h and then write about 400 words on the history of the caduceus symbol'

dump() {  # retried: a dump taken mid-animation on the emulator sometimes writes nothing
  local i; for i in 1 2 3 4; do
    adb shell uiautomator dump /sdcard/k.xml >/dev/null 2>&1
    if adb shell ls /sdcard/k.xml >/dev/null 2>&1; then adb shell cat /sdcard/k.xml; adb shell rm -f /sdcard/k.xml; return; fi
    sleep 1.5
  done
}
center() {
  local b; b=$(grep -oE "$2[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" <<<"$1" | head -1 \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | tr -c '0-9' ' ')
  [ -n "$b" ] || return 1
  read -r x1 y1 x2 y2 <<<"$b"; echo "$(((x1+x2)/2)) $(((y1+y2)/2))"
}
tap() { adb shell input tap $1 $2; }

adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 3
# Composition tracing (benchmark build): androidx.tracing.perfetto writes composable names to the
# track_event source once its receiver is told to (result 1 = enabled).
adb shell am broadcast -a androidx.tracing.perfetto.action.ENABLE_TRACING -n $PKG/androidx.tracing.perfetto.TracingReceiver 2>/dev/null | grep -o 'data=.*' | head -1
ui=$(dump)
if grep -q 'content-desc="Close navigation menu"' <<<"$ui"; then adb shell input keyevent KEYCODE_BACK; sleep 1; ui=$(dump); fi
xy=$(center "$ui" 'content-desc="New session[^"]*"') || { echo "no New session button" >&2; exit 3; }
tap $xy; sleep 3
xy=$(center "$(dump)" 'class="android.widget.EditText"') || { echo "no composer field" >&2; exit 3; }
tap $xy; sleep 1
# Short chunks: a slow emulator drops the tail of a long `input text`.
for w in $PROMPT; do adb shell input text "${w}%s"; done; sleep 1
adb shell input keyevent 111; sleep 1
xy=$(center "$(dump)" 'content-desc="Send"') || { echo "no Send button" >&2; exit 3; }

cat > /tmp/k.cfg <<'EOF'
buffers: { size_kb: 131072 fill_policy: RING_BUFFER }
data_sources: { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch"
  atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "input"
  atrace_apps: "chat.keryx.app" } } }
data_sources: { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
data_sources: { config { name: "android.surfaceflinger.frametimeline" } }
data_sources: { config { name: "track_event" track_event_config { enabled_categories: "*" } } }
duration_ms: 180000
EOF
adb push /tmp/k.cfg /data/local/tmp/k.cfg >/dev/null
adb shell rm -f /data/misc/perfetto-traces/k.pftrace
pid=$(adb shell "cat /data/local/tmp/k.cfg | perfetto --txt -c - -o /data/misc/perfetto-traces/k.pftrace --background" | tr -dc '0-9')
[ -n "$pid" ] || { echo "perfetto did not start" >&2; exit 3; }
sleep 2

running() { curl -s -m 3 "${BRAIN_METRICS:-http://localhost:8000/metrics}" | awk '/^vllm:num_requests_running/{print int($2)}'; }
t0=$(date +%s); tap $xy
seen=0; idle=0
while :; do
  sleep 2; r=$(running); now=$(( $(date +%s) - t0 ))
  if [ "${r:-0}" -gt 0 ]; then seen=1; idle=0; else idle=$((idle+2)); fi
  [ $seen -eq 1 ] && [ $idle -ge 10 ] && break
  [ $seen -eq 0 ] && [ $now -ge 60 ] && { echo "the brain never got the request" >&2; adb shell kill $pid; exit 3; }
  [ $now -ge 170 ] && break
done
echo "turn: ~$(( $(date +%s) - t0 - idle )) s"
adb shell kill $pid; sleep 4
adb pull /data/misc/perfetto-traces/k.pftrace "$OUT" >/dev/null && ls -la "$OUT"
