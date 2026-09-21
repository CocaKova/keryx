# Troubleshooting

Symptom first, then cause, then fix. Every entry here is a real failure this project has hit, and the
fix was checked against the source that shipped it. Start with [getting-started.md](getting-started.md)
if you have not seen a turn render at all.

### The phone cannot reach the gateway port, but `curl` on the host can

**Cause:** the host firewall does not forward to the bound port, or the app is dialing a loopback
address that means nothing on mobile data. A `127.0.0.1` bind answers locally and to nothing else.
**Fix:** bind the gateway on its LAN address or on `0.0.0.0`, open the API-server port in the firewall,
and put that host-and-port in the Gateway URL field. On a tailnet, the usual shape is Tailscale Serve
in front of a loopback bind, so the app dials the Serve hostname and Serve proxies to the local port.
**How to confirm:** `curl -s -H "Authorization: Bearer <key>" http://<host>:8642/health` from a machine
on the same network, then from the phone via **Test link** in Settings → Hermex Link. A version line in
both means the path is good.

### The WebSocket is accepted, then dies with a 403

**Cause:** two possible, and the wire looks the same from the client. Code `401`/`403` is an upgrade
rejected before accept; `4401`/`4403` is the server accepting and then closing. Both come from the
credential or the Host boundary.
**Fix:** check the API key exactly (no trailing newline from the paste), then the URL form. A gated
gateway on hermes ≥ 0.20.5 rejects the legacy token by design and wants a ticket, which the app mints
itself at `POST /api/auth/ws-ticket`; the sign-in is RFC 8252 loopback, the same flow the desktop uses.
The `Host` header the HTTP client derives from the dialed URL must match the gateway's bound host.
**How to confirm:** a 401/403 from **Test link** is the credential; a 404 on a `/keryx/*` route is a
missing plugin, not a bad key.

### The app connects but no text streams in

**Cause:** no live tier-1 subscriber, so the room only ever carries the single committed message. The
usual reasons, in order of likelihood: the keryx-stream plugin is not installed on that gateway, the
plugin is installed but not enabled, the *Live token streaming* toggle is off, the turn was started by
something other than this app.
**Fix:** install and enable the plugin ([gateway-setup.md](gateway-setup.md)), then flip the toggle on
and probe. A one-shot `hermes chat` turn has no side-channel by design and will read as committed-only
even on a fully installed gateway; that is not a fault.
**How to confirm:** the top-bar link-health dot. It breathes while tokens flow, dims when idle, and goes
red when the gateway is unreachable.

### After a `hermes update`, streaming stopped but chat still works

**Cause:** the update rewrote the core tree under the plugin, so the inserted hooks are gone. Chat on
the plain surface is fine; only the `/keryx/*` half went quiet.
**Fix:** re-run the plugin's installer, then `hermes gateway restart`. The plugin is a normal Hermes
plugin, so `hermes plugins enable keryx-stream` again if the list lost it.
**How to confirm:** the panels that vanished come back, and a long answer starts arriving token by token.

### A plugin does not load at all

**Cause:** the loader refuses a plugin whose imports no longer match the installed core, and a plugin
that was never enabled is not in `config.yaml`.
**Fix:** `hermes plugins enable keryx-stream`, and for the import case run `hermes plugins compat` and
follow its note. Then restart the gateway.
**How to confirm:** the plugin appears in the enabled list, and `GET /keryx/commands` answers.

### A second client stole the live stream

**Cause:** only one WebSocket client may own a session's event transport. Another one that resumed or
submitted on the same session took it from the phone, so the phone's own subscription is now idle.
**Fix:** reopen the app. A fresh process re-attaches and the next turn streams again.
**How to confirm:** the ring and the flight plan refresh again with the tokens.

### `**https://…**` printed instead of a tappable link

**Cause:** a URL wrapped in bold was not linkified by older renderers, so it read as literal text with
asterisks. Fixed in 2.11.4, where structured bold and strike went back to the renderer.
**Fix:** on a current build, nothing to do. From the agent's side, send a bare URL on its own line, which
reads well in every version.

### A half-streamed code block ate the rest of the message

**Cause:** the turn ended inside an open fence. Pre-2.11 renderers left the block unterminated and the
numbering of what followed shifted.
**Fix:** current builds append a closing fence when a message ends inside an open one, and strip a
trailing half-written marker. If you are on an older APK, update.

### The app is gone from the drawer's recent list after a while

**Cause:** a phone's process is killed behind your back all day, and the reconnect backoff reached its
cap. The backoff exists for a gateway that is down; it is wrong for a route that just came back.
**Fix:** reopen the app. A fresh process starts at 1 s again, and a network-rejoined or plane-mode-off
signal also kicks the socket immediately rather than waiting out the timer. The first thing the resumed
process reads is the last route you were on.

### A long-running turn looks stuck with no output

**Cause:** the context is being compacted, which is minutes of silence with nothing else on the wire.
**Fix:** nothing. The status line reads the compaction notice while it runs, and `ready` clears it when
the compression call returns, on a success or a raise alike. The context ring shows where the window sits.

### `INSTALL_FAILED_CONFLICTING_PROVIDER` from an `adb install`

**Cause:** a debug variant carries a `.debug` application-id suffix and its own FileProvider authority, so
the two variants coexist by design, but a third install path with the same authority from another build
tree can collide.
**Fix:** install the one artifact you want, and remove the other variant by its own number rather than
wiping everything. The two never overwrite each other, which is why the suffix exists.
