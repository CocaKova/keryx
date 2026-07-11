# Real background push (UnifiedPush) — Keryx 1.16

In-process sync notifications only work while Android lets the app live (battery exemption,
OEM allowing). Push closes that gap:

```
Synapse ──HTTP pusher (event_id_only)──▶ ntfy  /_matrix/push/v1/notify   (self-hosted)
   ntfy ──topic──▶ UnifiedPush distributor app on the phone (the ntfy Android app)
   distributor ──broadcast──▶ KeryxPushService (app wakes, even force-stopped*)
   PushSyncWorker ──▶ MatrixService.restore() → Trixnity sync (decrypts E2EE)
   ──▶ KeryxNotifications.notifyMessage (same per-room id as the in-app path — replaces, never stacks)
```

Only event/room **ids** ride the push (`event_id_only`): message content never touches ntfy,
and encrypted rooms work identically because the text always comes from our own sync.

## Server (once)

ntfy ships the Matrix push gateway built in — no extra component:

```bash
docker run -d --name ntfy --restart unless-stopped \
  -p 2586:80 -v ~/ntfy:/var/lib/ntfy \
  binwiederhier/ntfy serve \
  --base-url http://<host-tailscale-ip>:2586 \
  --cache-file /var/lib/ntfy/cache.db
```

`base-url` must be exactly the URL phones use — ntfy refuses to gateway pushes whose
endpoint host doesn't match it. A VPN-mesh (e.g. Tailscale) address works well: the phone's
distributor connects over the mesh and nothing is exposed publicly. Verify:
`curl http://127.0.0.1:2586/v1/health` and confirm the homeserver can reach the same URL from
wherever Synapse runs (e.g. `docker exec matrix-synapse curl http://<ip>:2586/v1/health`).

## Phone (once)

1. Install the **ntfy Android app** (the UnifiedPush distributor), Settings → default server →
   your ntfy URL. Grant it notifications + disable its battery optimization (it holds the one
   persistent connection so Keryx doesn't have to).
2. Keryx → Settings → Connection → **Push notifications** ON, push gateway URL = the same ntfy URL.
3. Confirm the pusher landed: `GET /_matrix/client/v3/pushers` (as the user) shows app_id
   `chat.keryx.app.android` with `format: event_id_only`.

No distributor installed → the switch refuses with a toast and everything stays on the
in-process tier. Toggling off (or signing out) removes the pusher and the distributor
registration.

## Verify end-to-end

```bash
adb shell am force-stop chat.keryx.app     # kill it properly
# send a message from another account → notification within seconds; tap opens the room
```

*force-stopped apps still receive UnifiedPush broadcasts because the distributor delivers via
an explicit intent to an app that has been "started" by the user enabling push; on some OEMs a
reboot before first delivery helps.

## Notes

- The mission-alert worker is untouched: different channel + id space.
- Notification dedup: push path and in-app watcher share `roomId.hashCode()` and both skip
  the room currently open in the foreground.
- ntfy runs open (no auth) scoped to Tailscale/LAN; add `--auth-default-access deny-all` +
  access tokens if it's ever exposed wider.
