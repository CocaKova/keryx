# Keryx docs

Keryx is a native Android chat client for [Hermes](https://github.com/NousResearch/hermes-agent)
agents. It speaks to a self-hosted Hermes gateway either through a Matrix homeserver or through the
gateway's own API, renders agent turns as structured cards instead of walls of text, and streams
tokens live when the keryx-stream plugin is installed. The app is one APK, no distributor service
required, and it works against any stock Hermes gateway with nothing extra installed (live
streaming then just falls back to committed turns).

This folder is the user documentation. The version it describes is 2.11.8.

## Pages

| Page | What it covers |
|---|---|
| [Getting started](getting-started.md) | Install the APK, make the first connection, tell whether it worked |
| [Gateway setup](gateway-setup.md) | The Hermes side: the API server, the key, the keryx-stream plugin, reaching a loopback bind from a phone |
| [Concepts](concepts.md) | Doors, rooms, sessions, profiles, the two streaming tiers, foreign-follow |
| [Chat and rendering](features/chat-and-rendering.md) | The transcript: bubbles, reasoning disclosure, tool cards, tables, code blocks, telemetry rows |
| [Streaming](features/streaming.md) | The dual-tier live-stream architecture and what each tier does on the wire |
| [Controls](features/controls.md) | Command palette, steer, model picker, reasoning dial, share target, assistant doorway |
| [Notifications and hands](features/notifications-and-hands.md) | The quiet shade, one-tap answers, the `⟦keryx:…⟧` markers, built-in ntfy push |
| [Spaces](features/spaces.md) | Archive, Missions, Projects, Runs, Bots, the Gateway hub, the Shipyard |
| [Configuration](configuration.md) | Every settings row, the Hermes Link fields, the gateway env knobs |
| [Troubleshooting](troubleshooting.md) | Real failures seen in this app, each with cause and fix |
| [Building](building.md) | Modules, JDK/SDK versions, the ship gate and its three verdicts, signing, CI |
| [Architecture](architecture.md) | The layer map, both transports, the wire protocols, one turn end to end |
| [Changelog](CHANGELOG.md) | Per-version history, newest first |

The build-plan notes in `Hermes-Chat/docs/` are internal design history. They are not required
reading and sometimes differ from what shipped; the pages here are checked against the source.
