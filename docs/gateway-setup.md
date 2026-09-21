# Gateway setup

What the Hermes side needs so Keryx has something to talk to. For the app-only steps see
[getting-started.md](getting-started.md); the wire details are in [architecture.md](architecture.md).

## Chat alone

Chat works against a stock Hermes gateway with nothing installed. The app speaks JSON-RPC 2.0 to the
gateway's WebSocket endpoint `WS /api/ws`, and plain HTTP to its API server, on the gateway's API
port (8642 by default, see `api_server.port` in `config.yaml`). That is enough to send prompts, read
transcripts, pick models, and answer approvals.

What you do not have without the plugin is live token streaming and the hub panels: the reasoning
dial, Missions, the Shipyard, the config editor, skills, pets, the update feed, plus several
`/keryx/*` routes the panels call. The app shows committed turns instead, and hides any panel whose
route answers 404.

## Install keryx-stream

The server half lives in its own repo:
[keryx-stream](https://github.com/CocaKova/keryx-stream). It is a normal Hermes plugin, no core
patches.

```bash
git clone https://github.com/CocaKova/keryx-stream.git
cd keryx-stream && ./install.sh
hermes plugins enable keryx-stream
hermes gateway restart
```

Two notes on those steps.

1. `hermes plugins enable` is what puts the plugin in `config.yaml`; the install script only drops
   files. An installed-but-disabled plugin answers nothing.
2. Hermes refuses to load a plugin whose imports no longer match the installed core. When that
   happens the plugin list shows it as incompatible; run `hermes plugins compat` and follow its
   note. A reinstall after `hermes update` is the usual cure, because the update rewrote the tree
   under the plugin.

The plugin registers its routes on the API server itself, so one URL covers it all. It answers
`/keryx/*` directly and relays the rest to the Hermes API server.

## What to type into the app

Settings → **Hermes Link**:

| Field | Value |
|---|---|
| Gateway URL | `http://<gateway-host>:8642` |
| API key | the gateway's `API_SERVER_KEY` |
| Live token streaming | on, once the plugin is installed |

The key is the same string the API server is configured with (`api_server.key` in `config.yaml`, or
`API_SERVER_KEY` in the gateway's environment). It rides as `Authorization: Bearer <key>`.

Then **Test link**. That button calls `GET /health` and prints the gateway's version line back.

There is a second certificate switch on the same page, **Allow self-signed certificates**, for a
gateway behind a self-signed TLS chain. It is off by default.

## Reaching the gateway from a phone

The usual shape is a gateway that binds loopback on the machine that hosts it, and a phone that
must arrive through a fronted hostname.

- A loopback bind (`127.0.0.1`) is fine when something forwards to it. Tailscale Serve does that:
  it gives you a hostname on your tailnet and proxies to the local port, so the app dials
  `https://your-host.your-tailnet.ts.net` and the Serve rule lands on 8642. Note that Serve speaks
  443 for the fronted name; if you keep the plain HTTP port, use the host-and-port form instead and
  match the hostname the gateway itself expects.
- A raw port on a LAN address (`192.168.x.x`) works on the same network. The host firewall has to
  accept it, which is the usual reason a phone "cannot reach" a port that `curl` answers locally.
- On a gated gateway, the WebSocket needs a ticket rather than the raw token: the app mints one per
  attempt via `POST /api/auth/ws-ticket`. Sign-in itself follows RFC 8252, the loopback browser flow,
  the same as the desktop app.

## Verify from a shell

With the plugin up, both halves answer:

```bash
KEY=$(grep ^API_SERVER_KEY= ~/.hermes/.env | cut -d= -f2)
curl -s -H "Authorization: Bearer $KEY" http://127.0.0.1:8642/health
curl -N -H "Authorization: Bearer $KEY" \
  "http://127.0.0.1:8642/keryx/stream?platform=matrix&chat_id=%21room%3Ahost"
```

The first prints a JSON health object. The second opens the side-channel: a `200` with
`text/event-stream`, `ping` frames every 20 seconds, and `delta` frames while that room's agent
turn runs. An empty stream with pings only means the plugin is loaded but no turn is in flight for
that room, which is normal between turns.

## Optional knobs

These read from the gateway's environment, and the plugin re-reads them per request.

| Variable | Effect |
|---|---|
| `KERYX_STREAM_FALLBACK_EDITS=1` | Without a live subscriber, Matrix gets throttled `m.replace` edit-streaming instead of one final message. Off by default; most clients render the edits as duplicate bubbles. |
| `KERYX_TOOLSETS_LOCKED` | Comma-separated toolset names the app cannot disable. |
| `KERYX_TOOLSETS_FORBIDDEN` | Comma-separated names the app cannot enable. |
| `KERYX_CONFIG_LOCKED` | Names config knobs the app cannot write. |

The paired `streaming:` block in `config.yaml` is what shapes the stream itself:
`enabled: true`, `transport: auto`, `edit_interval: 1.2`, `buffer_threshold: 60`.
