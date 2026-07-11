# Keryx ⚡

**A dream-styled Android client for [Hermes](https://github.com/NousResearch/hermes-agent) agents over Matrix.**

Keryx (Greek: *κῆρυξ*, "herald") turns any Matrix room shared with a Hermes agent into a
first-class agent interface: live token streaming, collapsible reasoning, tool-call cards,
quiet telemetry — all rendered in a deliberately dreamlike visual language instead of a wall
of raw model output.

> Works with any Matrix homeserver and any hermes-agent gateway. Nothing in the app is tied
> to a specific deployment.

## The pieces

| Directory | What it is |
|---|---|
| `Hermes-Chat/` | The Android app (Jetpack Compose + Trixnity Matrix SDK) |
| `Hermes-Chat/hermes-plugin/keryx-stream/` | The gateway-side streaming plugin (dual-tier side-channel), also submitted upstream to hermes-agent |

## Highlights

- **Dual-tier live streaming** — A transient SSE side-channel from the Hermes gateway renders
  tokens live. The Matrix room receives exactly one final committed message (no `m.replace`
  homeserver bloat). If no side-channel? Falls back to plain Matrix sync transparently.
- **A parsing engine for agent output** — `$$` blocks fold into collapsible reasoning
  canvases. Tool calls group into expandable run cards with success/failure verdicts.
  Structured JSON becomes "Action Output" cards. Runtime footers and cron check-ins render as
  low-contrast telemetry, never as chat.
- **Markdown that holds up** — GFM tables as real grids. Horizontally-scrollable code blocks
  with copy buttons. Unclosed fences healed mid-stream.
- **Hermes-native controls** — Reasoning-effort menu (persists via `/reasoning --global`),
  slash-command palette with recents, steer shortcut, link-health dot in the top bar.
- **Local-first diagnostics** — Crash log kept on-device only, shareable from Settings.

## Installing

Grab the latest APK from [Releases](https://github.com/CocaKova/keryx/releases) and sideload
it. Every release ships a signed APK.

## Building

```bash
cd Hermes-Chat
./gradlew :app:assembleRelease   # JDK 17 + Android SDK 36
```

Release builds sign with the debug keystore unless `local.properties` provides
`keryx.keystore`, `keryx.keystore.password`, `keryx.key.alias`, `keryx.key.password`.

## Gateway setup (for streaming)

Install the plugin into your hermes-agent tree and restart the gateway:

```bash
python3 Hermes-Chat/hermes-plugin/keryx-stream/install.py
```

Then in Keryx → Settings → **Hermes Link**, set the gateway URL
(`http://<gateway-host>:8642`), paste your `API_SERVER_KEY`, and hit **Test link**.
Full details in [`Hermes-Chat/hermes-plugin/keryx-stream/README.md`](Hermes-Chat/hermes-plugin/keryx-stream/README.md).

## Status

Actively developed and released — see [Releases](https://github.com/CocaKova/keryx/releases)
for the changelog. The streaming plugin is submitted upstream as
[NousResearch/hermes-agent#57091](https://github.com/NousResearch/hermes-agent/pull/57091);
until it lands there, install it from this repo as shown above.
