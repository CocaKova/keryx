# Keryx ⚡

A dream-styled Android client for [Hermes](https://github.com/NousResearch/hermes-agent) agents, over
Matrix or straight against the gateway. It turns a shared agent room into a real interface: live token
streaming, collapsible reasoning, tool-call cards, quiet telemetry, rendered in a deliberate visual
language instead of a wall of raw model output. Nothing in it is tied to one deployment; any Matrix
homeserver and any hermes-agent gateway will do.

<p align="center">
  <img src="docs/img/turn-anatomy.jpg" alt="A finished turn: prompt, reasoning, a tool card with its output, the reply" width="260">
  <img src="docs/img/drawer.jpg" alt="The drawer: sessions, filters, and the spaces" width="260">
  <img src="docs/img/gateway-panel.jpg" alt="The Gateway panel: brain, state, and the rooms of the machine" width="260">
  <br><sub>A turn with its reasoning and tool output open · the drawer · the Gateway panel</sub>
</p>

Documentation lives in [`docs/`](docs/README.md). Start at
[getting-started.md](docs/getting-started.md); that page takes you from APK to a live turn.

## The pieces

| Directory | What it is |
|---|---|
| `Hermes-Chat/` | The Android app: `:app` (Compose + Trixnity Matrix SDK) and `:core` (pure KMP) |
| `Hermes-Chat/hermes-plugin/keryx-stream/` | The gateway half, in-tree. The standalone [keryx-stream](https://github.com/CocaKova/keryx-stream) plugin is the one to install |
| `tools/` | `ship.sh`, the build gate. Its contract is in [`tools/README.md`](tools/README.md) |
| `Hermes-Chat/docs/` | Internal build plans and pass notes; user docs are in [`docs/`](docs/README.md) |

## In short

- **Dual-tier live streaming.** A transient SSE side-channel renders tokens live; the room gets one
  final committed message, so no `m.replace` bloat. No side-channel, and it falls back to the committed
  turn, transparently.
- **A parser built for agent output.** Folded reasoning, grouped tool runs with verdicts, structured
  JSON as cards, footers and cron check-ins as low-contrast telemetry. GFM tables, scrollable code with
  copy, healed fences, Mermaid.
- **Two doors, one chrome.** Matrix (rooms, the council's per-agent hues) or the direct gateway door
  (sessions, projects, runs, bots, the hub panels). One `DoorLexicon` per door says the noun.
- **Answerable notices.** Reply inline from the lock screen; one-tap option buttons and phone-action
  tiles ride as structured markers, and the tap is the consent. No distributor app needed: it holds its
  own ntfy WebSocket, a UnifiedPush distributor is preferred when present.
- **Places over tabs.** Archive (a local FTS index, so it works with the gateway down), Missions,
  Projects, Shipyard, Runs, Bots, the Gateway hub, the fleet of many gateways on one phone.

## Quick reference

```bash
# install (the APK from the Releases page)
adb install -r keryx-<version>.apk

# build (no emulator on arm64 Linux; the on-device canary needs a phone on adb)
cd Hermes-Chat && ../tools/ship.sh --release
```

Gateway side, in four lines: clone [keryx-stream](https://github.com/CocaKova/keryx-stream), run its
`install.sh`, `hermes plugins enable keryx-stream`, `hermes gateway restart`. Then in
Settings → **Hermes Link** set the Gateway URL to the plugin's server (`http://<gateway-host>:8646`),
paste your `API_SERVER_KEY`, and hit **Test link**. The whole walkthrough is in
[gateway-setup.md](docs/gateway-setup.md).

## Status

Actively developed and released; see [Releases](https://github.com/CocaKova/keryx/releases) and the
[Changelog](docs/CHANGELOG.md). The gateway half was first proposed upstream as
[NousResearch/hermes-agent#57091](https://github.com/NousResearch/hermes-agent/pull/57091); Hermes keeps
third-party integrations out of core, so it lives as the standalone plugin, built on the stream observer
hooks Hermes ships.
