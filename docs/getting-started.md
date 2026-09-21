# Getting started

This page takes you from no APK to a live agent conversation, and tells you how to tell whether it
worked. It assumes you already have a Hermes gateway running somewhere. The plugin steps are in
[gateway-setup.md](gateway-setup.md).

## What you need

- An Android phone, Android 8.0 or newer (minSdk is 26).
- A running Hermes gateway. The default API-server port is `8642`.
- That gateway's `API_SERVER_KEY`, the bearer token the API server accepts.

## 1. Install the APK

Grab the newest release from [Releases](https://github.com/CocaKova/keryx/releases) and sideload
it. Every release ships a signed APK. With the phone connected over adb:

```bash
adb install -r keryx-2.11.7-vc98-release.apk
```

A release build signs with the debug certificate unless `local.properties` names a keystore, so a
release APK installs over a debug one without an uninstall. Keep that in mind when a build comes
from the command line instead of a release: a *different* keystore produces a signature mismatch.
The fix is still one install of the matching file, not an uninstall, because uninstalling wipes the
app's stored gateways and settings. See [troubleshooting.md](troubleshooting.md).

## 2. Pick your door

Keryx reaches a Hermes agent through one of two transports. The name in
Settings → Account → **Transport** is what the app uses.

**Matrix** — the app joins your homeserver with the Matrix SDK and reads rooms. Every row in the
list is a room. This is the door for a gateway that publishes into Matrix rooms, and it is where
the multi-agent **council** look appears, because a room can hold several heralds.

**Direct** — the app talks straight to the gateway's own JSON-RPC over a WebSocket plus the REST
surface. Rows are gateway sessions rather than rooms, and the gateway's native projects, the runs
list and the bot roster come along with them.

The noun changes per door and it never mixes: rooms on Matrix, sessions on the direct door. Both
doors render the same turn structure. See [concepts.md](concepts.md).

## 3. Fill in the Hermes Link

Settings → **Hermes Link** holds the gateway half of the connection. Three fields, and they are the
whole configuration for the plugin path:

| Field | Value |
|---|---|
| Gateway URL | the URL that answers `/keryx/*` for this deployment (`http://<gateway-host>:8646` for a standalone keryx-stream install, `http://<gateway-host>:8642` when the routes ride the gateway's API server) |
| API key | the gateway's `API_SERVER_KEY` |
| Live token streaming | on, when the keryx-stream plugin is installed |

Then tap **Test link**. The probe is `GET /health`, so a good answer is short and factual, in the
form `ok · <platform> · <version>`. Anything else and the toast carries the gateway's own error
wording or an HTTP code. A `401`/`403` means the credential was refused; `404` on a `/keryx/*`
route means the plugin is not installed there.

If your gateway binds to loopback only and your phone is on mobile data, the phone cannot reach
`127.0.0.1` and a raw port does not survive a Tailscale Serve either. Both cases are walked through
in [gateway-setup.md](gateway-setup.md).

## 4. Open a room and send something

The drawer lists rooms (or sessions), with a pinned deck on top. Tap a row and the transcript opens
on the chat floor; the composer sits at the bottom with the instruments strip above it.

A turn you can expect to see:

1. Your message as a right-side bubble.
2. A reasoning disclosure that folds and unfolds, when the brain emits one.
3. Tool rows as small cards, grouped on one rail when a turn fired several calls.
4. The committed reply as one left-side bubble.

With the plugin installed and streaming on, the text of step 4 arrives token by token. Without it,
you get the same bubble one moment later, once Matrix syncs it. Nothing else changes. That is the
fallback tier, described in [features/streaming.md](features/streaming.md).

## 5. Tell whether it worked

Four checks, in order of how little they ask of you.

1. **Health.** Settings → **Hermes Link** → **Test link** prints the gateway version. That proves the URL
   and key are right and the API server answers.
2. **A committed turn.** Send `ping`. One bubble back, with no duplicate above it, means the
   homeserver is carrying one final message per turn, which is the point of the design.
3. **Live streaming.** Send a prompt that produces a long answer. If characters arrive while the
   spinner is still up, tier 1 is live. If the whole answer appears at once, the app fell back. It is
   not an error, and the same room behaves differently across turns, since the subscription is
   per-turn.
4. **Instruments.** The context ring near the top bar should show a filled arc and, on tap, a figure
   like `84k / 128k`. That comes from the plugin's `usage` frame, so no figure means no plugin, not a
   broken model.

The link-health dot in the top bar is the fast summary of all of this: it breathes while tokens flow,
dims when idle, and goes red when the gateway is unreachable.
