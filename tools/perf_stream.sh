#!/usr/bin/env bash
# One streaming-frames pass on the phone: new chat, one prompt with tools, gfxinfo over the turn.
# No uiautomator dumps while the turn streams (a dump is an a11y walk and would add jank).
# Same measure as docs/PERF-BASELINE.md "Streaming frames". Starts a new session from the top bar
# (direct door), so it adds one short test chat to the list. The end of the turn is read from the
# brain's vLLM /metrics (BRAIN_METRICS, default localhost:8000), because a uiautomator poll
# during the turn would itself cost frames.
# Usage: tools/perf_stream.sh   (phone on adb, unlocked, hands off for 1–2 minutes)
set -uo pipefail
PKG=chat.keryx.app
PROMPT='Use the terminal to run uptime and then df -h and then write about 400 words on the history of the caduceus symbol'

dump() { adb shell uiautomator dump /sdcard/k.xml >/dev/null 2>&1; adb shell cat /sdcard/k.xml; adb shell rm -f /sdcard/k.xml; }
center() {  # $1 = xml, $2 = attribute regex (content-desc="..." or class="...")
  local b; b=$(grep -oE "$2[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" <<<"$1" | head -1 \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | tr -c '0-9' ' ')
  [ -n "$b" ] || return 1
  read -r x1 y1 x2 y2 <<<"$b"; echo "$(((x1+x2)/2)) $(((y1+y2)/2))"
}
tap() { adb shell input tap $1 $2; }

adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 2
ui=$(dump)
if grep -q 'content-desc="Close navigation menu"' <<<"$ui"; then adb shell input keyevent KEYCODE_BACK; sleep 1; ui=$(dump); fi
# On the direct door the new session lives in the top bar over the chat, not in the drawer.
xy=$(center "$ui" 'content-desc="New session[^"]*"') || { echo "no New session button" >&2; exit 3; }
tap $xy; sleep 2.5
xy=$(center "$(dump)" 'class="android.widget.EditText"') || { echo "no composer field" >&2; exit 3; }
tap $xy; sleep 0.8
adb shell input text "$(sed 's/ /%s/g' <<<"$PROMPT")"; sleep 0.8
adb shell input keyevent 111; sleep 0.8   # ESC hides the IME
xy=$(center "$(dump)" 'content-desc="Send"') || { echo "no Send button" >&2; exit 3; }

running() { curl -s -m 3 "${BRAIN_METRICS:-http://localhost:8000/metrics}" | awk '/^vllm:num_requests_running/{print int($2)}'; }
adb shell dumpsys gfxinfo $PKG reset >/dev/null
t0=$(date +%s); tap $xy
seen=0; idle=0
while :; do
  sleep 2; r=$(running); now=$(( $(date +%s) - t0 ))
  if [ "${r:-0}" -gt 0 ]; then seen=1; idle=0; else idle=$((idle+2)); fi
  [ $seen -eq 1 ] && [ $idle -ge 10 ] && break       # 10 s quiet after the brain worked
  [ $seen -eq 0 ] && [ $now -ge 60 ] && { echo "the brain never got the request" >&2; exit 3; }
  [ $now -ge 240 ] && break
done
echo "turn: ~$(( $(date +%s) - t0 - idle )) s of streaming"
focus=$(adb shell dumpsys window | grep -m1 mCurrentFocus)
case "$focus" in *"$PKG"*) ;; *) echo "Keryx lost focus during the turn; not measured" >&2; exit 3;; esac
adb shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile|Number Missed Vsync|Number Slow UI thread|Number Slow bitmap|Number Slow issue draw|Number Frame deadline missed" | sed 's/^/  stream: /'
