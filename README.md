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
- **Actionable notifications** — Reply inline from the lock screen; when the agent is blocking
  on a decision it can attach one-tap option buttons (`⟦keryx:ask|Approve|Deny⟧` — a structural
  marker, not keyword sniffing) that also render as reply chips in chat.
- **Self-contained push** — No distributor app required: point Keryx at any ntfy server and it
  holds its own WebSocket subscription (a UnifiedPush distributor, if installed, is
  auto-detected and preferred). Payloads stay `event_id_only` — content never rides the push.
- **Share-sheet target** — Send any text, link, image, video, or file from any app straight
  into a room with an optional note; attachments + note land as one MSC2530-captioned turn.
- **The Archive** — Full-text search over the room's entire history, powered by a local FTS
  index the app builds itself (E2EE rooms can't be searched server-side — the phone is the
  only place the plaintext exists). Jump to any date, keep messages in a Saved list from the
  long-press menu, browse every photo and file in a gallery; tapping anything opens a live
  context window around that moment.
- **The dream look (2.0)** — A living dark: ambient glows drifting at minutes per pass, streams
  of magic sand pouring off the reply while it's still being written, spring-physics navigation
  where the back gesture scrubs the page transition under your finger, and one attention budget
  governing it all — one focal effect at a time, everything else a whisper. Battery Saver
  stills every ornament.
- **Assistant doorway (2.0)** — Set Keryx as the device assist app and the long-press gesture
  summons your agent from any screen, composer ready.
- **The room is the truth** — The conversation lives in Matrix and on your phone, not in a
  session. History survives a gateway restart, a reinstall, or the gateway being down entirely;
  the same room reads the same from the phone, the desktop, or the Archive. A process can end.
  The room does not.
- **The Council (2.3)** — Several agents, one room, each its own light. Every agent account gets
  a stable hue and the herald's sigil, carried by its bubble rim, its name and its spinner, so a
  room full of agents reads as several lives rather than one voice with different words. One
  agent relaying another renders as an attributed notice, never as the courier speaking, and a
  turn nobody asked for is marked as an *arrival* instead of an answer.
- **The tool theater (2.4)** — While the agent works you see what it is *doing*, not a spinner:
  each tool as it starts, how long it took, what failed and why, and calls fired in one turn
  grouped on a rail. Subagents get their own wings — goal, model, tool count, duration, token
  cost, and the summary each one came back with. A delegated child is not a session you can open
  and its relay is never stored, so this is the only window onto it. Deliberately quiet: the
  committed reply renders the same calls properly a moment later.
- **Local-first diagnostics** — Crash log kept on-device only, shareable from Settings.

## Installing

Grab the latest APK from [Releases](https://github.com/CocaKova/keryx/releases) and sideload
it. Every release ships a signed APK.

## Building

```bash
cd Hermes-Chat
./gradlew :app:assembleRelease   # JDK 17 + Android SDK 36 (Kotlin 2.1.21)
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
for the changelog. The streaming plugin was proposed upstream as
[NousResearch/hermes-agent#57091](https://github.com/NousResearch/hermes-agent/pull/57091), which
was closed without merging, so this repo is its home: install it on top of any hermes-agent tree
as shown above.
