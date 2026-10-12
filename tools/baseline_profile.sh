#!/usr/bin/env bash
# baseline_profile.sh — re-record Keryx's Baseline Profile (3.0 perf §4).
#
# Runs the :baselineprofile journey (open, scroll the transcript, open and scroll the drawer)
# on a signed-in TEST device and writes app/src/main/baselineProfiles/baseline-prof.txt, which
# every release build compiles in. Re-record after big UI changes; commit the diff.
#
#   ANDROID_SERIAL=127.0.0.1:5557 tools/baseline_profile.sh
#
# 1. builds the profileGen app (release without R8, so rules carry source names) and the test
#    APK, on the build host when ~/.config/keryx/build-host names one (as ship.sh does)
# 2. installs both: the app goes over chat.keryx.app with the same key, so the login stays
# 3. runs BaselineProfileGenerator (~8 min on the VIRDARA emulator) and pulls the profile
#
# Use the emulator, not your phone: the device is left on the profileGen build (no R8, bigger,
# slower), to be replaced by the next normal install. The journey types and sends nothing.
# Exit: 0 written, 1 failed, 3 no device.
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$REPO_ROOT/Hermes-Chat"
OUT="$APP/app/src/main/baselineProfiles/baseline-prof.txt"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
TASKS=(:app:assembleProfileGen :baselineprofile:assembleProfileGen)
APKS=(app/build/outputs/apk/profileGen/app-profileGen.apk
      baselineprofile/build/outputs/apk/profileGen/baselineprofile-profileGen.apk)
TEST_PKG=chat.keryx.baselineprofile
MEDIA=/storage/emulated/0/Android/media/$TEST_PKG

adb get-state >/dev/null 2>&1 || { echo "no device (set ANDROID_SERIAL)" >&2; exit 3; }

HOST="$(tr -d '[:space:]' < "${KERYX_BUILD_HOST_FILE:-$HOME/.config/keryx/build-host}" 2>/dev/null)"
if [ -n "$HOST" ]; then
  echo "── build on $HOST"
  # Same sync as remote-ship.sh. The host signs with this machine's key (remote-ship.sh hands it
  # over); a host-minted key makes the install below fail as UPDATE_INCOMPATIBLE.
  rsync -a --delete --exclude=build/ --exclude=.gradle/ --exclude=.kotlin/ --exclude=dist/ \
    --exclude=backups/ --exclude='*.apk' --exclude=local.properties \
    "$REPO_ROOT/" "$HOST:workspace/keryx/" || { echo "rsync to $HOST failed" >&2; exit 1; }
  ssh "$HOST" "source ~/android-buildenv/env.sh; cd ~/workspace/keryx/Hermes-Chat && ./gradlew -q ${TASKS[*]}" \
    || { echo "build failed on $HOST" >&2; exit 1; }
  for a in "${APKS[@]}"; do scp -q "$HOST:workspace/keryx/Hermes-Chat/$a" "$TMP/" || exit 1; done
else
  echo "── build here"
  (cd "$APP" && ./gradlew -q "${TASKS[@]}") || exit 1
  for a in "${APKS[@]}"; do cp "$APP/$a" "$TMP/"; done
fi

echo "── install"
adb install -r "$TMP/app-profileGen.apk" | tail -1
adb install -r -t "$TMP/baselineprofile-profileGen.apk" | tail -1
adb shell rm -rf "$MEDIA" >/dev/null 2>&1

echo "── record (several passes of the journey; ~8 min on the emulator)"
# EMULATOR is suppressed: a profile records which code runs, and that is the same on an emulator.
log=$(adb shell am instrument -w -r -e class chat.keryx.baselineprofile.BaselineProfileGenerator \
  -e androidx.benchmark.suppressErrors EMULATOR "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" 2>&1)
grep -q 'OK (1 test)' <<<"$log" || { echo "$log" | tail -25 >&2; echo "recording failed" >&2; exit 1; }

# benchmark 1.5 writes the journey as a startup profile (includeInStartupProfile); a baseline-
# prof file, when there is one, holds the same rules.
src=$(adb shell ls "$MEDIA" | tr -d '\r' | grep -E '^BaselineProfileGenerator_generate-(baseline|startup)-prof\.txt$' | sort | head -1)
[ -n "$src" ] || { echo "no profile file in $MEDIA" >&2; exit 1; }
adb pull "$MEDIA/$src" "$TMP/prof.txt" >/dev/null || exit 1
lines=$(wc -l < "$TMP/prof.txt"); own=$(grep -c 'Lchat/keryx/app/' "$TMP/prof.txt")
# A journey that never got past the launcher records a few hundred framework lines.
[ "$lines" -ge 5000 ] && [ "$own" -ge 500 ] || { echo "profile too small ($lines rules, $own Keryx): did the journey reach the chat?" >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"; cp "$TMP/prof.txt" "$OUT"
echo "── wrote $lines rules ($own in chat.keryx.app) to ${OUT#"$REPO_ROOT"/}"
