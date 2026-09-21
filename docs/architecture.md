# Architecture

The layers, both transports, the wire protocols, and one turn end to end. For the frame meanings see
[streaming.md](features/streaming.md); for the settings keys see [configuration.md](configuration.md).

## Layer map

```
       ┌─────────────────────────┐
       │  UI (Compose screens)   │  ChatScreen, drawer, sheets, instruments
       └───────────┬─────────────┘
                   │
       ┌───────────┴─────────────┐
       │  presentation/ delegates  │  one per place: Hub, Missions, Runs, Projects,
       │  + ChatViewModel          │  Bots, Shipyard, Archive, Models, Voice, Console
       └───────────┬─────────────┘
                   │
       ┌───────────┴─────────────┐
       │  :app                    │  transports (matrix/, direct/), data/ (remote+archive),
       │                        │  notify/, audio/, hands/, senses/, share/, theme/
       └───────────┬─────────────┘
                   │
       ┌───────────┴─────────────┐
       │  :core (KMP, jvm)       │  model/ + protocol/ — pure, common-stdlib only
       └────────────────────────┘
```

`:core` is the spine both doors stand on: message parsing, the theater reducer, the fleet rules, the
lexicon, the shade notice shapes, the tail-follow logic, the renderers' pure halves. It sees no Android
types, which the compiler enforces. `:app` adds the platform: two clients, one view model, the
notification plumbing, the local index.

## The two doors

| | Matrix door | Direct door |
|---|---|---|
| Client | Trixnity Matrix SDK | OkHttp WebSocket + OkHttp REST |
| Endpoint | the homeserver's `/_matrix` client API | `WS /api/ws` and the API server on :8642 |
| Row | room | session |
| Nouns | Quick Rooms, Rooms | Pinned, Sessions |
| Carries | rooms, council hues, MSC2530 media | projects, runs, bots, the hub panels |
| Auth | homeserver login, optional E2EE | token or ticket, Bearer on REST |

Both feed the same view model, so the chat floor renders one way. The door decides the nouns through
`DoorLexicon` and which settings rows exist.

## The direct door's wire

JSON-RPC 2.0 over `WS /api/ws`, the same dispatcher surface the desktop and the TUI use. Requests carry
`jsonrpc`, `id`, `method`, `params`; responses match by id, so a slow handler may answer out of order
and it does not matter. Everything else arrives as `method:"event"` frames with
`params.type` + `params.session_id` + `params.payload`, fanned out to the room that asked.

- `gateway.ready` flips the state to Ready and carries the server's skin (its branding payload).
- Liveness is at the WebSocket layer, not the protocol: a 20 s ping keeps NATs open and detects dead
  sockets. The gateway itself pings every 20 s on non-loopback binds.
- Reconnect uses exponential backoff from 1 s, capped at 30 s, and the cap *resets* after a socket
  that actually reached Ready. A network-state change kicks the wait early rather than letting the app
  sit on a stale timer.
- Credential codes are terminal: `401`/`403` (upgrade rejected before accept, what a gated gateway
  really sends) and `4401`/`4403` (accepted then closed). No retry heals those, so the loop stops and
  the UI re-onboards.
- Auth is one of two dialects. Token mode (`?token=`) is stable and serves ungated or loopback binds.
  Ticket mode (`?ticket=`) is minted fresh per attempt at `POST /api/auth/ws-ticket` for gated gateways,
  where the legacy token is refused by design; tickets burn on use and expire in 30 s. Browser sign-in
  follows RFC 8252: a PKCE pair, the system browser, a loopback listener on the phone catching
  `?code=`.
- No `Origin` header is sent on purpose, so the absent-Origin rule admits the upgrade; the `Host`
  header the client derives from the dialed URL must match the gateway's bound host.

Methods the app calls, by family:

| Family | Methods |
|---|---|
| Sessions | `session.active_list`, `session.list`, `session.resume`, `session.create`, `session.title`, `session.steer`, `session.undo`, `session.compress`, `session.context_breakdown`, `session.workspace.move` |
| Prompts | `prompt.submit`, `slash.exec`, `image.attach_bytes`, `file.attach`, `message.react`, `complete.path` |
| Models / config | `model.options`, `config.get`, `config.set`, `commands.catalog` |
| Projects | `projects.list`, `projects.tree`, `projects.project_sessions`, `projects.create`, `projects.delete`, `projects.archive` |
| Profiles | `profiles.list`, `profiles.create`, `profiles.configure`, `profiles.get_asset` |

`prompt.submit` acks fast (`status: streaming`); the turn itself arrives as events. A transcript page
is 120 messages, matching the desktop's tail page.

## The plugin's surface

The side-channel is a separate, transient SSE subscription on the same API server, so a turn's live
bytes do not compete with the room's sync. `GET /keryx/stream?platform=matrix&chat_id=<room>` opens it,
`event: stop` closes it. Frame alphabet and rules: [streaming.md](features/streaming.md).

The rest of `/keryx/*` is a curated REST surface for the panels: `capabilities`, `commands`, `config`,
`config/raw`, `reasoning`, `logs`, `brains`, `brain`, `toolsets`, `skills` plus `skill-trash`,
`sessions/prune`, `pet`, `pets`, `pet/select`, `pet/thumb`, and the `kanban` family. It speaks two
error dialects, `{"error":{"message":…}}` and a bare `{"error":"…"}`; both surface as one message
string, with structured extras (`needsForce`, `httpStatus`) riding on the exception so callers branch
on a flag rather than sniffing text. A 404 on a route hides its panel.

The Run Console speaks only the *stock* API-server surface, so it works with no plugin at all:
`POST /v1/runs`, `GET /v1/runs/{id}`, `GET /v1/runs/{id}/events` (SSE, unnamed events with an `event`
field inside the JSON), `.../approval`, `.../stop`, plus the session dialect
`POST /api/sessions/{id}/chat/stream` (named events, a different vocabulary). Both dialects normalize
into one stream shape, and the run registry is app-local because the gateway has no list-runs route.
One asymmetry to know: the gateway pops a run's event queue when its subscriber disconnects, so a
re-subscribe after a drop misses what came between and the recovery path is a status poll.

## One turn, end to end

**Direct door, plugin present.**

1. The view model opens the per-turn SSE subscription (or finds it already open).
2. `prompt.submit` with the text and the session id; the ack says `streaming`.
3. `reasoning` and `delta` frames render into a live tail row; `tool` frames fill the theater rail and
   the wings; `segment` marks a boundary; `status` drives the working banner, which is held open-ended
   across a compaction and re-armed when the compaction ends.
4. `usage` fills the context ring.
5. `stop` hands over: the app holds its overlay until the committed event lands, then swaps in one
   byte-exact pass. A dropped socket without a stop is a `StreamLost`, and the run may still be going,
   so the recovery is a status poll.

**Matrix door, plugin present.** Same shape, but the committed half is the room's single final
message and the `event` ids come from the Matrix sync. The side-channel is keyed by room id, so
`chat_id` is the room.

**No plugin.** One committed message per turn, rendered by the same parser, minus the durations and
verdicts the wire would have carried. The instruments that need plugin routes stay dark.

**A turn started elsewhere.** The phone's subscription is idle for it, so the tail-follow loop reads the
session's pulse instead: a grown count is a re-read, a shrank count is a new baseline, a locally-busy
turn is not foreign at all. That is the mechanism behind an open room catching up on a turn it did not
start.

## Local state

The app keeps its own small stores so a screen answers without a round trip: settings in one
`SharedPreferences` file with per-gateway key scoping, the Archive's `fts4` index in a database of its
own, the hub's snapshot cache keyed by gateway and by plain path, and the console's run registry riding
that snapshot store. Senses consent lives in a third file so clearing it does not disturb the account or
the theme. A crash log stays on-device only and is shareable from About.

## Where to look in the source

| Want | File |
|---|---|
| The RPC surface and its reconnect rules | `app/src/main/java/chat/keryx/app/transport/direct/GatewayRpc.kt` |
| The side-channel and its frames | `app/src/main/java/chat/keryx/app/data/remote/HermesStreamClient.kt` |
| The Matrix half | `app/src/main/java/chat/keryx/app/transport/matrix/` |
| Parsing and the theater reducer | `core/src/commonMain/kotlin/chat/keryx/core/protocol/`, `core/model/Theater.kt` |
| The nav spine and route names | `app/src/main/java/chat/keryx/app/presentation/ui/nav/KeryxNav.kt` |
| The settings catalog | `app/src/main/java/chat/keryx/app/presentation/ui/components/SettingsCatalog.kt` |
| The gateway half | `hermes-plugin/keryx-stream/keryx_stream.py`, its `README.md`, and `install.py` |
| The build gate | `tools/ship.sh`, `tools/README.md`, CI in `.github/workflows/ci.yml` |
