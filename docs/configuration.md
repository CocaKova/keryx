# Configuration

Every settings row the app has, the Hermes Link fields, and the gateway-side environment knobs. Names
and section labels are copied from `SettingsCatalog.kt` and the settings screen; the row's own on-screen
title is what you will see.

## How the two doors differ

Some rows exist on one door only, and then they are not offered on the other, so a search never opens
an empty page. The table's "Door" column uses this key:

- **both** — on Matrix and on the direct door
- **direct** — direct door only
- **matrix** — Matrix door only

Sections are the groupings the settings screen shows: Account, Gateway / Connection, Hermes Link,
Agent, Voice, Appearance, Companion, Privacy & Security, Sessions, About.

## Settings rows

| Row (on-screen title) | Section | Door | What it does |
|---|---|---|---|
| Account | Account | both | The signed-in identity for this door, plus sign out. |
| Transport | Account | both | Switches the door: Matrix or direct gateway. |
| Gateways | Gateway | direct | The fleet: named gateway rows, their URLs, and which is Primary. |
| At startup, return to the last-used gateway | Gateway | direct | Where a cold start lands. On (the default) opens the last-used gateway; off opens Primary. A relaunch the app did to itself always lands on the chosen row whatever this says. |
| Allow self-signed certificates | Gateway / Connection | both | Accepts a self-signed TLS chain on the gateway or homeserver URL. Off by default. |
| Homeserver URL | Connection | matrix | The Matrix homeserver base URL. |
| Hermes Agent Matrix IDs | Connection | matrix | Which Matrix user ids are heralds, so the council can give each its own hue. |
| Push notifications | Connection | matrix | The ntfy server and topic for built-in push when no UnifiedPush distributor is installed. |
| Re-authenticate | Connection | matrix | Replays the Matrix login against the stored homeserver. |
| Hermes Link | Gateway / Hermes Link | both | The toggle for live token streaming over the SSE side-channel. Off means committed turns only. |
| Gateway URL | Gateway / Hermes Link | both | The plugin's base URL, `http://<gateway-host>:8642`, with the API key beside it and a **Test link** probe. |
| Show telemetry | Agent | both | Whether runtime footers and cron check-ins render as low-contrast telemetry rows. |
| Mission alerts | Agent | both | The 15-minute kanban alerts worker, for comments and non-terminal task events. |
| Reopen last chat on launch | Agent | both | Opens the room you were in rather than the drawer. |
| New sessions use the last model picked | Agent | direct | On: a new chat inherits the last chosen model. Off: it opens on the gateway's configured default brain. |
| Voice dictation | Voice | both | Mic input through the gateway's STT. |
| Voice replies | Voice | both | Spoken replies through the built-in TTS engine. |
| Theme | Appearance | both | Dark, light, or follow the system. |
| Bubble style | Appearance | both | The bubble treatment: gilded, solid, gradient, glass. |
| Text size | Appearance | both | Font scale. |
| Accent colours | Appearance | both | The accent set, including a second accent and the gradient stops. |
| Haptic feedback | Appearance | both | The two-weight haptic grammar on or off. |
| Loading animation | Appearance | both | Which spinner: caduceus, braille, dots, wave. Battery Saver stills all of them. |
| Companion | Companion | both | The petdex mascot the gateway serves, adopted from its picker. |
| Biometric app lock | Privacy & Security | both | Fingerprint, face or PIN to open the app. |
| End-to-end encryption | Privacy & Security | matrix | E2EE handling for the homeserver's rooms. |
| Senses | Privacy & Security | both | Opt-in device telemetry the agent may read: battery, time, place. Each is its own switch and all start off. |
| Archived sessions | Sessions | direct | The drawer's hidden rows, restorable. |
| Prune sessions | Sessions | direct | Bulk-delete ended sessions, with a dry-run preview first. |
| Crash log | About | both | The on-device crash log, shareable. |
| Version | About | both | `Keryx v<versionName> (<versionCode>)`, plus the gateway's own version when the hub knows it. |

## How the fleet stores things

Each gateway row keeps its own half of the state, keyed by that row's id, so switching gateways does
not cross-contaminate. Under the active id live the seven direct-door credential keys
(`direct_api_key`, `direct_auth_mode`, `direct_access_token`, `direct_refresh_token`,
`direct_token_expires_at`, `direct_logged_in`) and the per-gateway ledger rows: `last_room_id`,
`temporary_session_ids`, the cron baseline and seen ids, `bot_seen_at`, `pinned_bots`,
`mission_events_cursor`, and Hermes Link's `gateway_url` / `gateway_api_key`. The hub snapshot cache is
keyed per gateway too, since `/api/status` is the same path on every one of them.

Fleet rows themselves follow the desktop's rules: names are unique case-insensitively and at most 64
characters, rows are deduplicated on the normalised URL (trimmed, trailing slashes stripped, scheme and
host lowercased), one row is always Primary, and only one row is active. The active row cannot be
removed; switch first.

The Hermes Link URL has a small defaulting rule worth knowing: with no stored URL the app builds
`http://<host>:8642` from the host part of the door's own address, since 8642 is the gateway's API-server
port by default on both doors.

## Senses

All three are opt-in, all off by default, and their consent lives in its own preferences file so
clearing it does not disturb your account or theme. The throttle is a 30-minute minimum interval between
reports; battery is reported in 20-percent steps; place is the last-known coarse fix only (±1 km), never
a live location request, with a short geocode budget.

## Gateway environment knobs

Read fresh per request on the keryx-stream side, so a change lands on the next call without a restart.

| Variable | Effect |
|---|---|
| `KERYX_STREAM_FALLBACK_EDITS` | `1`/`true`/`yes`/`on` turns the no-subscriber fallback into throttled `m.replace` edits instead of one final message. Off otherwise. |
| `KERYX_TOOLSETS_LOCKED` | Comma-separated toolset names that cannot be disabled from the app. They report `locked: true`. |
| `KERYX_TOOLSETS_FORBIDDEN` | Comma-separated names that cannot be enabled from the app. |
| `KERYX_CONFIG_LOCKED` | Config knob names the app cannot write; those rows render read-only. |

## The gateway's own config block

The stream shape comes from `config.yaml`, not from the app:

```yaml
streaming:
  enabled: true
  transport: auto
  edit_interval: 1.2
  buffer_threshold: 60
```

Pets are configured in the same file (`display.pet.enabled`, `display.pet.slug`), the same keys the
desktop and TUI read, so every surface shows the same mascot. Everything pet-related fails open: no pet
configured, or any engine hiccup, answers `{"enabled": false}` and the phone draws nothing.

## Build-time properties

`Hermes-Chat/local.properties` is not committed and is read by the app module:

```
keryx.keystore=/absolute/path/to/release.keystore
keryx.keystore.password=…
keryx.key.alias=…
keryx.key.password=…
```

Absent those four, a release build signs with the debug keystore, on purpose. See
[building.md](building.md).
