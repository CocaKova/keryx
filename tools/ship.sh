#!/usr/bin/env bash
# ship.sh — the gate between "it compiles" and "it is a build".
#
# One verb for an agent to call. It runs the stages, then prints a verdict block that is meant
# to be read by a model as much as by a person:
#
#   ── KERYX SHIP VERDICT ─────────────
#   VERDICT: GREEN
#   ...
#   ───────────────────────────────────
#
# The vocabulary matters more than the stages. There are three verdicts, and the difference
# between two of them is the whole reason this file exists:
#
#   GREEN  every stage asked for passed.
#   RED    a stage failed. The code is wrong. Do not ship, do not retry blindly.
#   AMBER  a stage could not be run — no device, no network. NOTHING WAS LEARNED about the
#          code. An agent that reads AMBER as RED reverts good work; one that reads it as
#          GREEN ships untested work. It is neither.
#
# Usage:
#   tools/ship.sh                 unit tests + debug APK
#   tools/ship.sh --smoke         ... and the on-device canary (installs on the target — see below)
#   tools/ship.sh --release       ... assemble release instead of debug
#   tools/ship.sh --detach        run in the background, write build/ship/status; poll that
#                                 (Hermes' code_execution timeout is 300s; a cold build is longer)
#
# --smoke INSTALLS chat.keryx.app (debug) on the connected device, replacing what is there.
# It is off by default for that reason.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$REPO_ROOT/Hermes-Chat"
OUT="$PROJECT/build/ship"
ENV_SH="$HOME/android-buildenv/env.sh"
# env.sh puts the SDK's platform-tools on PATH, and that adb is an x86_64 binary that cannot
# run on this arm64 host. The system adb is the working one; name it outright.
ADB="/usr/bin/adb"

SMOKE=0; RELEASE=0; DETACH=0; FORCE=0
for a in "$@"; do case "$a" in
  --smoke) SMOKE=1 ;;
  --release) RELEASE=1 ;;
  --detach) DETACH=1 ;;
  --force) FORCE=1 ;;   # run the canary even though the phone is in use
  -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
  *) echo "unknown argument: $a" >&2; exit 2 ;;
esac; done

mkdir -p "$OUT"
LOG="$OUT/ship.log"

if [ "$DETACH" = 1 ]; then
  args=(); [ "$SMOKE" = 1 ] && args+=(--smoke); [ "$RELEASE" = 1 ] && args+=(--release)
  echo "RUNNING" > "$OUT/status"
  nohup "${BASH_SOURCE[0]}" "${args[@]}" >"$LOG" 2>&1 &
  echo "detached pid $! — poll $OUT/status, read $LOG"
  exit 0
fi

: > "$LOG"
say()  { printf '%s\n' "$*" | tee -a "$LOG"; }
step() { printf '\n── %s\n' "$*" | tee -a "$LOG"; }

STAGES=(); FAILED=(); SKIPPED=()
record() { STAGES+=("$1"); case "$2" in fail) FAILED+=("$1");; skip) SKIPPED+=("$1");; esac; }

# Runs a gradle task, streaming to the log, and records pass/fail.
gradle_stage() {
  local name="$1"; shift
  step "$name"
  if ./gradlew "$@" >>"$LOG" 2>&1; then
    say "  ok"; record "$name" pass; return 0
  else
    say "  FAILED — see $LOG"; record "$name" fail; return 1
  fi
}

# ── preflight ──────────────────────────────────────────────────────────────────
step "preflight"
if [ ! -f "$ENV_SH" ]; then say "  missing $ENV_SH"; exit 2; fi
# shellcheck disable=SC1090
source "$ENV_SH"
if ! git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  # ~/workspace/keryx-dev is a non-repo copy stuck at 1.0; building there wastes an hour.
  say "  $REPO_ROOT is not a git checkout — wrong tree?"; exit 2
fi
# Gradle does not use $ADB — it reaches for $ANDROID_HOME/platform-tools/adb itself, and the
# one the SDK ships is an x86_64 binary that cannot execute on this arm64 host ("Could not
# determine adb version" / "Could not create ADB Bridge"). Checked here so the failure names
# its own fix instead of arriving as a device error.
SDK_ADB="${ANDROID_HOME:-}/platform-tools/adb"
if [ "$SMOKE" = 1 ] && ! "$SDK_ADB" version >/dev/null 2>&1; then
  say "  the SDK's adb cannot run on this host: $SDK_ADB"
  say "  fix:  mv '$SDK_ADB' '$SDK_ADB.x86_64.dead' && ln -sfn $ADB '$SDK_ADB'"
  exit 2
fi

BRANCH="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"
HEAD_SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
DIRTY="$(git -C "$REPO_ROOT" status --porcelain | wc -l | tr -d ' ')"
VERSION="$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$PROJECT/app/build.gradle.kts" | head -1)"
VCODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$PROJECT/app/build.gradle.kts" | head -1)"
say "  branch $BRANCH @ $HEAD_SHA (${DIRTY} uncommitted) — $VERSION (vc$VCODE)"
cd "$PROJECT" || exit 2

# ── unit ───────────────────────────────────────────────────────────────────────
gradle_stage "core unit tests"  :core:jvmTest
gradle_stage "app unit tests"   :app:testDebugUnitTest

# ── assemble ───────────────────────────────────────────────────────────────────
if [ "$RELEASE" = 1 ]; then
  gradle_stage "assemble release" :app:assembleRelease
  APK="$(ls -t "$PROJECT"/app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
else
  gradle_stage "assemble debug" :app:assembleDebug
  APK="$(ls -t "$PROJECT"/app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)"
fi

# ── on-device canary ───────────────────────────────────────────────────────────
DEVICE=""
if [ "$SMOKE" = 1 ]; then
  step "on-device canary"
  DEVICE="$("$ADB" devices 2>/dev/null | awk '/\tdevice$/{print $1; exit}')"
  if [ -z "$DEVICE" ]; then
    # No emulator is possible on this host: Google ships the Android emulator for linux-x64
    # only, and this machine is arm64. The canary needs a real phone on adb.
    say "  no device on adb — canary SKIPPED (this is AMBER, not a failure)"
    record "on-device canary" skip
  else
    # The instrumentation runner launches, destroys and force-stops MainActivity between
    # test classes. Tapping the app during a run makes it vanish on open — indistinguishable
    # from a crash, and it has already cost one debugging detour. If the screen is on, someone
    # is holding the phone: say so and stop, rather than making their app look broken.
    SCREEN_ON="$("$ADB" -s "$DEVICE" shell dumpsys power 2>/dev/null | grep -o 'mWakefulness=[A-Za-z]*' | head -1)"
    if [ "$SCREEN_ON" = "mWakefulness=Awake" ] && [ "$FORCE" != 1 ]; then
      say "  device is awake — someone is using it. The canary force-stops the app between"
      say "  test classes, which looks like a crash-on-open to whoever is holding it."
      say "  Re-run with --force, or wait for the screen to sleep."
      record "on-device canary" skip
      DEVICE=""
    else
    # The instrumentation runner lives on the PHONE. If gradle dies here — killed, interrupted,
    # network dropped — Android happily keeps the test process running, and it goes on launching
    # its test activity: the app appears to flicker open and closed to whoever is holding it.
    # This already happened twice on 2026-09-03. Whatever exit this script takes from here,
    # the on-device runner is stopped.
    stop_runner() {
      "$ADB" -s "$DEVICE" shell am force-stop chat.keryx.app.debug.test >/dev/null 2>&1
      "$ADB" -s "$DEVICE" shell am force-stop chat.keryx.app.debug      >/dev/null 2>&1
    }
    trap 'stop_runner' EXIT INT TERM
    MODEL="$("$ADB" -s "$DEVICE" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

    say "  target $DEVICE ($MODEL)"
    if ./gradlew :app:connectedDebugAndroidTest >>"$LOG" 2>&1; then
      say "  ok"; record "on-device canary" pass
    else
      say "  FAILED — see $LOG"; record "on-device canary" fail
    fi
    stop_runner
    trap - EXIT INT TERM
    say "  device runner stopped"
    fi
  fi
fi

# ── verdict ────────────────────────────────────────────────────────────────────
if   [ ${#FAILED[@]}  -gt 0 ]; then VERDICT=RED
elif [ ${#SKIPPED[@]} -gt 0 ]; then VERDICT=AMBER
else VERDICT=GREEN; fi

# Test counts come from the reports gradle just wrote, not from a number typed here.
count_tests() {
  local n=0
  for d in "$PROJECT"/core/build/test-results/jvmTest \
           "$PROJECT"/app/build/test-results/testDebugUnitTest \
           "$PROJECT"/app/build/outputs/androidTest-results/connected; do
    [ -d "$d" ] || continue
    n=$(( n + $(grep -ho 'tests="[0-9]*"' "$d"/*.xml 2>/dev/null | grep -o '[0-9]*' | paste -sd+ | bc 2>/dev/null || echo 0) ))
  done
  echo "$n"
}
TESTS="$(count_tests)"

{
  printf '\n── KERYX SHIP VERDICT ─────────────────────────────\n'
  printf 'VERDICT:  %s\n' "$VERDICT"
  printf 'BRANCH:   %s @ %s%s\n' "$BRANCH" "$HEAD_SHA" "$([ "$DIRTY" -gt 0 ] && echo " (+$DIRTY uncommitted)")"
  printf 'VERSION:  %s (vc%s)\n' "$VERSION" "$VCODE"
  printf 'TESTS:    %s\n' "$TESTS"
  printf 'STAGES:   %s\n' "${STAGES[*]:-none}"
  [ ${#FAILED[@]}  -gt 0 ] && printf 'FAILED:   %s\n' "${FAILED[*]}"
  [ ${#SKIPPED[@]} -gt 0 ] && printf 'SKIPPED:  %s\n' "${SKIPPED[*]}"
  [ -n "$DEVICE" ] && printf 'DEVICE:   %s\n' "$DEVICE"
  [ -n "${APK:-}" ] && [ -f "${APK:-}" ] && printf 'APK:      %s\n' "$APK"
  printf 'LOG:      %s\n' "$LOG"
  case "$VERDICT" in
    GREEN) printf 'MEANING:  every stage asked for passed.\n' ;;
    RED)   printf 'MEANING:  a stage failed. The code is wrong — read LOG, fix, re-run.\n' ;;
    AMBER) printf 'MEANING:  a stage could not run. Nothing was learned about the code.\n'
           printf '          Do NOT revert on AMBER and do NOT ship on AMBER.\n' ;;
  esac
  printf '───────────────────────────────────────────────────\n'
} | tee -a "$LOG"

echo "$VERDICT" > "$OUT/status"
case "$VERDICT" in GREEN) exit 0 ;; AMBER) exit 3 ;; RED) exit 1 ;; esac
