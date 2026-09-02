# Keryx 2.7.0 — the ear opens: "hey Hermes" on the phone (2026-09-02)

The last Phase F item from `KERYX-3.0-ABSORPTION.md` §5. Jonny: "what's next for Keryx? the wake
word yeah?" → "sounds good. let's do it!" Built on the branch `keryx-2.7-wake-word` while the
2.6.2 walk and cut wait for the phone (Jonny away, wireless debugging off), so `main` still cuts
2.6.2 on its own.

Harvested whole from Talaria 0.7.9 (D4 + the zero-network ear), re-landed under Keryx's names
and doors. **Not pasted** in the one place that mattered: the ear exists on the DIRECT DOOR
ONLY, and the code says so at every seam rather than hoping.

## 1. What it is

Say "hey hermes" at a phone lying on the desk; it chimes and opens the Call, screen off or not.

Two ears, one switch:

| mode | where detection runs | what leaves the phone | gateway lease |
|---|---|---|---|
| **Detect on this phone** (default) | openWakeWord via LiteRT, the gateway's own three `.tflite` files bundled in `assets/wake/` | nothing until the phrase fires | none — no `wake.*` traffic at all |
| **Stream to the gateway** (desktop's way) | the gateway's detector (`tools/wake_word.py`) | 16 kHz PCM in `wake.feed` batches, only while the room has sound | `wake.start` with `surface: gui, client_capture: true`; transport-bound owner |

Both share the mic (`WakeMic`), the energy gate (`WakeEnergyGate` + the Call's `NoiseFloor`),
the battery policy (`WakePolicy`), the mic-type foreground service (`WakeEarService`), the
chime (`WakeChime`) and the summon path (controller → `ChatViewModel.callSummon` → `HermesApp`
walks home and opens the Call, in a fresh session when `start_new_session` says so).

## 2. Where it landed

**:core** `model/WakeWord.kt` — the pure half, verbatim from Talaria's `:shared` (KMP-clean
already): wire shapes (`WakeStartResult`/`WakeStatus`/`WakeStopResult`/`WakeDetection`),
`WakeProtocol` constants, `WakeReconcile` (desktop's `resumeWakeAfterVoice` table),
`WakeFrameQueue`, `WakePcm`, `WakeEnergyGate`, `WakePolicy`, `WakePipeline` (openWakeWord's
streaming math, verified equal to the reference), `WakeScoreGate` (gateway's 0.6 / 3 frames /
2 s). Tests: `core/.../WakeWordTest.kt` (20, ported to kotlin.test).

**:app**
- `audio/WakeWordController.kt` — the ear's brain. Rides `DirectTransport` (its one socket:
  `linkState()`, `gatewayRequest()`, `wakeDetections`). Consent is layered: the phone's opt-in
  (`SettingsRepository.wakeWordEnabled`) and the gateway's `wake_word.enabled` flipped with
  `persist:true` on the same gesture; passive paths (reconnect, post-call) never persist.
- `audio/WakeMic.kt` (AudioRecord 16 kHz, 48/44.1 k fallback + box downsample),
  `WakeFeeder.kt` (streaming mode), `LocalWakeEngine.kt` + `LocalWakeDetector.kt` (on-device),
  `WakeChime.kt` (synthesized E5→B5, no asset).
- `notify/WakeEarService.kt` — MICROPHONE-type FGS; ongoing notification with "Stop listening";
  the "heard you" full-screen intent when the app is backgrounded. Starts only while the app is
  visible (Android 12+/14+ rule) — refuses, never throws.
- `DirectTransport` — `wake.detected` (global frame, empty session id) → `wakeDetections`.
  `gatewayRequest` already existed from the absorption with zero callers; it has callers now.
- `KeryxApp.wakeWord` — built only when `transport is DirectTransport`; null on Matrix.
  `ForegroundTracker.onActivityStarted` → `appVisible()` (the one moment a mic service may start).
- `MainActivity.handleWakeSummonIntent` — `setShowWhenLocked` + `setTurnScreenOn` on the ear's
  launch; the biometric lock still gates the chat underneath.
- `ChatViewModel` — `wake` param (nullable), `callSummon` counter + `callSummonNewSession`,
  `wakeUi`, `setWake*`, `onCallStarted/onCallEnded` (CallScreen's DisposableEffect calls both:
  the ear yields the mic before `CallAudio` opens it, reclaims after).
- `HermesApp` — `showCall` HOISTED out of the top bar (two doors open it now); the summon
  collector walks home, closes the drawer, and opens the Call — or toasts "set STT/TTS" when
  `voice.callReady()` is false (the ear can hear without them; it cannot answer).
- `SettingsDialog` — Voice ▸ **Hey Hermes** card, rendered only when `wakeUi != null` (direct
  door). Switch asks RECORD_AUDIO first; status line speaks the controller's `notice`; mode
  switch; three policy switches (charging / not on cellular / 4 h idle).
- Settings prefs: `wake_word_enabled` (false), `wake_on_device` (true), `wake_only_charging`
  (true), `wake_not_cellular` (true), `wake_idle_hours` (4).
- Manifest: `FOREGROUND_SERVICE_MICROPHONE`, `USE_FULL_SCREEN_INTENT`, the service.
- Build: `litert` 1.4.0; **`ndk.abiFilters = arm64-v8a`** (LiteRT natives ×4 ABIs would triple
  the APK; every phone this sideloads to is arm64 — debug APK 62 → 48 MB, release 18.9 → 16.5 MB);
  R8 keep for `org.tensorflow.lite.**`. versionCode 75 / 2.7.0.

## 3. Traps (carried from Talaria, plus Keryx's own)

- ⚠️ **Direct door only.** The lease is bound to the gateway WebSocket and the summon opens the
  Call, which needs Keryx's STT/TTS endpoints. On Matrix `KeryxApp.wakeWord` is null, the
  Settings card does not exist, and nothing in the controller can run. Do not "add it to
  Matrix" without a transport that owns a gateway socket.
- ⚠️ **Android 14+: a mic FGS may only START while the app is visible.** `WakeEarService.start`
  returns false from the background; the controller then waits for `appVisible()`. This is why
  `setEnabled(true)` brings the service up on the gesture, before arming.
- ⚠️ The Call and the ear cannot both hold `AudioRecord`. `pauseForVoice()` stops the feeder /
  local detector BEFORE `CallAudio` opens; `resumeAfterVoice()` reconciles after (3 spaced
  retries against `wake.status` in streaming mode — the server pauses itself on detection and a
  lost race leaves the ear silently off).
- ⚠️ Streaming mode's first `wake.start` may lazy-install the engine on the gateway — 180 s
  timeout, notice says so. The live gateway (0.20.6) has `wake_word.enabled: false`; the first
  gesture-arm flips it with `persist:true`. On-device mode never touches it.
- ⚠️ `WakeEnergyGate` needs the Call's `NoiseFloor` (byte-identical between repos). A long-closed
  gate is a pipeline reset; openWakeWord tolerates it (history seeds with ones).
- ⚠️ A cellular-under-Tailscale phone reads as cellular (`WakePolicy.notOnCellular`), so the
  ear RESTS away from home by default — the very state the phone is in as this is written.
- ⚠️ arm64-only: an x86 emulator will not install this APK any more. Waydroid on the Spark
  (arm64) would.
- ⚠️ Compose measure crashes and Android-only regex/ICU faults are invisible to JVM tests
  (2.6.2's two crashes). Nothing here renders in a horizontal scroller and there is no regex,
  but the LiteRT interpreter is a native call: `LocalWakeEngine.load` is `runCatching` and a
  null engine degrades to "on-device detector unavailable" + the streaming fallback.

## 4. Status

- Branch `keryx-2.7-wake-word` off `main` (`e1ae954`, the 2.6.2 tree). NOT merged, NOT tagged.
- 613 tests green (593 + 20 `WakeWordTest`); `assembleDebug` + `assembleRelease` OK;
  APK carries `lib/arm64-v8a/libtensorflowlite_jni.so` (4.3 MB) + `assets/wake/*.tflite` (2.6 MB).
- Repo-root `keryx-2.7.0-debug.apk` / `keryx-2.7.0-release.apk` (release ≈ 16.5 MB — the
  chat-deliverable size).
- ⚠️ **NOT device-walked** — phone off adb (tailnet only, wireless debugging off, Jonny away).
  Talaria's own wake leg was never device-verified either, so this is the first time any of it
  meets a phone. Walk list, direct door, phone plugged in and on Wi-Fi (or flip both policy
  switches off):
  1. Settings ▸ Voice: "Hey Hermes" card present; switch on → RECORD_AUDIO prompt → status
     "listening for "hey hermes" on this phone…"; ongoing notification "Listening for…".
  2. Say it: chime, Call opens in a NEW session (`start_new_session` default). Speak; the agent
     answers. End the call → status back to listening within ~2 s.
  3. Screen off, say it: screen lights, Keryx over the keyguard, Call up. Notification "Heard…"
     clears.
  4. Unplug: status "resting — plug in to listen", notification "Ear resting". Replug: listening.
  5. "Stop listening" from the notification: switch off in Settings, service gone.
  6. Mode switch off (stream to gateway): status "arming — first use may take a minute…" then
     "listening … streams to the gateway only while the room has sound"; gateway log shows
     `wake.start` surface=gui; say it → `wake.detected` → Call.
  7. Kill the app, reopen: ear re-arms on visibility (opt-in remembered).
  8. Matrix door: no card, no service, nothing in logcat tagged `KeryxWake`.
- Release cut = Jonny's call. 2.7.0 is a minor (Phase F closes) — merge after the walk;
  2.6.2 cuts from `main` first, on its own walk.
