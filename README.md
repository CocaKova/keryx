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

- **Dual-tier live streaming** — a transient SSE side-channel from the Hermes gateway renders
  tokens live; the Matrix room receives exactly one final committed message (no `m.replace`
  homeserver bloat). No side-channel? Falls back to plain Matrix sync transparently.
- **A parsing engine for agent output** — `<think>` blocks fold into collapsible reasoning
  canvases; tool calls group into expandable run cards with success/failure verdicts;
  structured JSON becomes "Action Output" cards; runtime footers and cron check-ins render as
  low-contrast telemetry, never as chat.
- **Markdown that holds up** — GFM tables as real grids, horizontally-scrollable code blocks
  with copy buttons, unclosed fences healed mid-stream.
- **Hermes-native controls** — reasoning-effort menu (persists via `/reasoning --global`),
  slash-command palette with recents, steer shortcut, link-health dot in the top bar.
- **Local-first diagnostics** — crash log kept on-device only, shareable from Settings.

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
Full details in [`hermes-plugin/keryx-stream/README.md`](Hermes-Chat/hermes-plugin/keryx-stream/README.md).

## Status

Pre-release. The streaming plugin is submitted upstream as
[NousResearch/hermes-agent#57091](https://github.com/NousResearch/hermes-agent/pull/57091).
