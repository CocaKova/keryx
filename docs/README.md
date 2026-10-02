# Keryx docs

Keryx is a native Android chat client for [Hermes](https://github.com/NousResearch/hermes-agent)
agents. It speaks to a self-hosted Hermes gateway either through a Matrix homeserver or through the
gateway's own API, renders agent turns as structured cards instead of walls of text, and streams
tokens live when the keryx-stream plugin is installed. The app is one APK, no distributor service
required, and it works against any stock Hermes gateway with nothing extra installed (live
streaming then just falls back to committed turns).

This folder is the user documentation. The version it describes is 2.16.0; what changed since
2.12 is in the [changelog](CHANGELOG.md), and some screenshots predate it (listed at the end).

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
| [Tap-In](features/tap-in.md) | The turn in flight, full screen: headline, mind, crew, rail, instruments; steer it, and steer or stop one helper from its own mind |
| [Spaces](features/spaces.md) | Archive, Missions, Projects, Runs, Bots, the Gateway hub, the Shipyard, the Artifact viewer |
| [Home screen](features/home-screen.md) | The widget and the Quick Settings tile |
| [Configuration](configuration.md) | Every settings row, the Hermes Link fields, the gateway env knobs |
| [Troubleshooting](troubleshooting.md) | Real failures seen in this app, each with cause and fix |
| [Building](building.md) | Modules, JDK/SDK versions, the ship gate and its three verdicts, signing, CI |
| [Architecture](architecture.md) | The layer map, both transports, the wire protocols, one turn end to end |
| [Changelog](CHANGELOG.md) | Per-version history, newest first |

The build-plan notes in `Hermes-Chat/docs/` are internal design history. They are not required
reading and sometimes differ from what shipped; the pages here are checked against the source.

## Stale screenshots

Every image in `img/` was taken before 2.16, so all of them show the old type sizes. These also
show something that has since changed, and should be retaken first:

| Image | What changed |
|---|---|
| `turn-anatomy.jpg` | tok/s readout (now "≈ N tok/s" or chars/s), exit-code chips, model-switch divider |
| `chat-folded.jpg` | exit-code chips in folded runs, model-switch divider |
| `context-ring.jpg` | the sheet's run numbers; the ring's gilded edge and compaction drain |
| `steer.jpg` | holding Steer now offers Queue / Ask aside / Redirect, and the hint reads "hold for more" |
| `reasoning-dial.jpg` | one spelling per level ("X-High") |
| `drawer.jpg` | running rows breathe; Export in the row menu |
| `gateway-panel.jpg`, `controls-panel.jpg` | the Hub's Update and Memory spokes |
| `missions-new.jpg` | priority and goal mode on create; its copy was already out of date |
| `model-picker.jpg`, `bots.jpg`, `shipyard.jpg`, `account-transport.jpg`, `gateway-test-link.jpg` | type scale only |

Missing entirely: Tap-In, the Missions board and a card sheet, Runs and the job editor, the
widget, the artifact viewer, the goal strip, an aside, find in chat.
