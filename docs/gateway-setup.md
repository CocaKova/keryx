# Gateway setup

What the Hermes side needs so Keryx has something to talk to. App-only steps are in
[getting-started.md](getting-started.md); wire details are in [architecture.md](architecture.md).

## Chat alone

Chat works against a stock Hermes gateway with nothing installed. The app speaks JSON-RPC 2.0 to the
gateway's `WS /api/ws` and plain HTTP to its API server, on the API-server port (`8642` by default,
`api_server.port` in `config.yaml`). That is enough to send prompts, read transcripts, pick models, and
answer approvals.

What you do not have without the plugin is live token streaming and the hub panels (the reasoning dial,
Missions, the Shipyard, the config editor, skills, pets, the update feed). The app shows committed turns
instead and hides any panel whose route answers 404.

## Install keryx-stream

The supported server half is the standalone plugin repo:
[keryx-stream](https://github.com/CocaKova/keryx-stream). It is a normal Hermes plugin on the public
plugin surface, with no patches to the core tree.

```bash
git clone https://github.com/CocaKova/keryx-stream.git
cd keryx-stream && ./install.sh        # symlinks the package into ~/.hermes/plugins/
```

Then enable it and restart the gateway:

```bash
hermes plugins enable keryx-stream
hermes gateway restart
```

Two notes on those steps.

1. `hermes plugins enable` is what puts the plugin in `config.yaml`; the installer only places files.
   An installed-but-disabled plugin answers nothing.
2. The loader refuses a plugin whose imports no longer match the installed core. When that happens the
   plugin list shows it as incompatible; run `hermes plugins compat` and follow its note. A reinstall
   after a `hermes update` is the usual cure, since the update rewrote the tree under it.

`install.sh --copy` copies instead of symlinking, if you prefer an unpacked tree. From a clone,
`pip install -e .` also works: the package is discovered through its `hermes_agent.plugins` entry point.
It is not published on PyPI.

The live-stream half depends on the core's shipped stream observer hooks (`on_stream_start`,
`on_stream_delta`, `on_stream_end`, `on_interim_message`, plus `pre_tool_call` / `post_tool_call`). On a
core that predates them the plugin still loads and still serves toolsets, and live streaming stays
inactive with a one-line notice in the log. Chat keeps working, on committed turns.

`Hermes-Chat/hermes-plugin/keryx-stream/` in this repo is the older in-tree copy. It patches the core
tree in place and no longer applies cleanly to a current hermes-agent; use the standalone plugin.

## Configure it

Non-secret settings go in `~/.hermes/config.yaml`, in the plugin's own block:

```yaml
keryx_stream:
  enabled: true
  host: "0.0.0.0"
  port: 8646
  default_platform: matrix     # used when a turn's metadata omits one
  toolsets:
    locked: []                 # toolsets the app may not disable
    forbidden: []              # toolsets the app may not enable
```

The plugin runs its own small HTTP server on that port, bearer-authed, and does not touch the gateway's
api_server. The bearer token is a secret, so it comes from the environment: set `KERYX_STREAM_TOKEN`, or
reuse your existing `API_SERVER_KEY`. Every route except the health one requires the
`Authorization: Bearer <token>` header.

That is what you type into the app. Settings → **Hermes Link**:

| Field | Value |
|---|---|
| Gateway URL | the plugin's server, `http://<gateway-host>:8646` |
| API key | the same token the plugin is configured with |
| Live token streaming | on, once the plugin is installed |

The app also probes `GET /health` on the stored URL for its **Test link** button, which is the gateway's
own liveness route on the API server; the plugin's own is `/keryx/health`. If your Test link probe goes
red while streaming works, that is the two servers, not a bad key. The verdict says what it checked.

## Reaching the gateway from a phone

The usual shape is a server that binds loopback on its own host, and a phone that must arrive through a
fronted hostname.

- A loopback bind (`127.0.0.1`) answers locally and to nothing else. On a tailnet the usual pattern is
  Tailscale Serve in front of it: you get a hostname on your tailnet and Serve proxies to the local port,
  so the app dials `https://your-host.your-tailnet.ts.net`. A raw port on a LAN address
  (`192.168.x.x`) works on the same network when the host firewall accepts it, which is the usual reason
  a phone "cannot reach" a port that `curl` answers locally.
- On a gated gateway the WebSocket needs a ticket rather than the raw token: the app mints one per
  attempt at `POST /api/auth/ws-ticket`. Sign-in itself follows RFC 8252, the loopback browser flow, the
  same as the desktop app.

## Verify from a shell

```bash
KEY=$KERYX_STREAM_TOKEN   # or API_SERVER_KEY
curl -s -H "Authorization: Bearer $KEY" http://127.0.0.1:8646/keryx/health
curl -N -H "Authorization: Bearer $KEY" \
  "http://127.0.0.1:8646/keryx/stream?platform=matrix&chat_id=%21room%3Ahost"
```

The first is a liveness answer. The second opens the side-channel: `200` with `text/event-stream`, `ping`
frames every 20 seconds, and `delta` frames while that room's agent turn runs. Pings only, with no turn
in flight, is the normal between-turns state.

## Hub and forward mode

A turn driven from *outside* the gateway process (a `hermes chat` one-shot, a cron run, any CLI process)
fires the same hooks in its own process, where no subscriber is attached. The plugin handles this with
two run modes, chosen automatically at startup, no flag:

- **Hub mode**, this instance binds the SSE port and serves subscribers. That is the gateway, normally.
- **Forward mode**, the port is already held by another instance, so hook events are POSTed to that
  instance's `/keryx/publish` route. A CLI session's deltas and tool events then appear on the hub
  owner's side-channel like any other turn, keyed by session id; you subscribe with
  `GET /keryx/stream?platform=cli&chat_id=<session_id>`.

Point a forwarder at a specific hub owner (for example a remote gateway) with
`keryx_stream.forward_url` in `config.yaml`.

## Optional knobs

The plugin's own env block, read fresh per request:

| Variable | Effect |
|---|---|
| `KERYX_STREAM_TOKEN` | The plugin server's bearer token. `API_SERVER_KEY` is accepted in its place. |
| `KERYX_STREAM_FALLBACK_EDITS=1` | Without a live subscriber, Matrix gets throttled `m.replace` edit-streaming instead of one final message. Off otherwise; most clients render the edits as duplicate bubbles. |
| `KERYX_TOOLSETS_LOCKED` | Comma-separated toolset names the app cannot disable; they report `locked: true`. |
| `KERYX_TOOLSETS_FORBIDDEN` | Comma-separated names the app cannot enable. |
| `KERYX_CONFIG_LOCKED` | Config knob names the app cannot write; those rows render read-only. |

The paired `streaming:` block in `config.yaml` shapes the stream itself: `enabled: true`,
`transport: auto`, `edit_interval: 1.2`, `buffer_threshold: 60`.
