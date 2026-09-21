# Notifications and the shade

The notice system, the two push transports, and what each button does. The marker syntax is defined
in [concepts.md](../concepts.md); the hands are in [controls.md](controls.md).

## Two push transports, one at a time

Keryx needs Matrix push delivery to wake a sync at the right moment, and it can carry that two ways.

**A UnifiedPush distributor.** If a distributor app is installed, Keryx registers as its client and
is woken with pushed Matrix event ids. Nothing to configure; the distributor is preferred when
present.

**Built-in push.** With no distributor installed, Keryx becomes its own distributor. A foreground
service holds a WebSocket on your own ntfy server and feeds each pushed room into the same sync
worker. Set the ntfy server URL and topic in Settings (Push notifications). The mechanics: the pusher
is registered with the Matrix pushkey `<ntfy>/<topic>?up=1`, which is the exact endpoint shape a
distributor would have issued, so Synapse's ntfy gateway path lands on the private topic. After a
reboot or an app update a boot receiver restarts that subscription.

Payloads stay `event_id_only`: the notice carries which room moved, and the content comes from the
sync. No body rides the push channel.

The run notice is the second always-on piece. It is alive exactly while an agent's turn is in flight,
one silent status line rather than an alert per event, and it doubles as the keep-alive that lets the
turn's answer reach a backgrounded phone when it is said rather than at the next unlock.

The manifest also requests exemption from battery optimization, because an aggressive deep-sleep
policy suspends the Matrix sync mid-turn and that reads as a lost answer.

## The quiet shade

An incoming notice renders as a small, low-contrast block rather than a chat bubble, and it says what
changed since you last looked (a new turn, a completed run, an unread count). One notice per session
keeps the shade legible.

Answering from the lock screen works like this: an approval or a choice question gets up to three one-
tap buttons; a plain free-text question gets the inline reply field; a question with more than three
choices, or a multi-select one, is tap-through to the app on purpose, since a truncated answer set
reads as a wrong answer with the number of options missing.

Two hard rules the shade follows:

- Credentials never ride the shade. A sudo or secret answer is a password, and a lock-screen reply
  field echoes it into the most shoulder-surfable surface the OS has. Those notices are tap-only and
  the in-app card, with its masked field, owns the answer.
- A notice must die honestly. Gateway approvals fail closed at the gateway's `approvals.timeout`,
  stock 300 s; blocking requests carry their own expiry, stock 600 s. Every notice carries the
  lifetime after which the shade drops it, so a button never outlives the wait it answers. That's why
  a stale button does nothing at all.

When both an approval and a blocking question are somehow live for one session, the blocking one wins:
it is the harder barrier, since the whole dispatch is stopped rather than one tool.

## In-app cards

Inside the room, the same states render as structured cards, and their buttons are the wire values. An
approval's buttons come from the gateway's own `choices`, which are subsets of `once`, `session`,
`always`, `deny`. `always` is the one that earns a lock-screen shortcut; `session` is a power move
better taken from the in-app card, so it sometimes shares the third slot.

A turn that failed shows the failure card with the gateway's own message, so the layer that broke is
visible without opening the logs panel.

## What each notification type means

| Title | Trigger | Buttons |
|---|---|---|
| `Approval needed` | the agent is blocked on a guarded command | from the gateway's choices, up to three |
| `The agent asks` | a blocking clarify request | its own choices if ≤3 and single-select, else inline reply |
| `Password needed` | a sudo prompt on the host | tap-through |
| `Secret needed` | a one-time code or env-var credential | tap-through |
| run notice | a turn in flight | status line only |

## Read-marks and unread

A room's unread count is the delta since its last read-mark, written per gateway so the fleet's rows do
not cross-contaminate each other. The 15-minute mission alerts worker is the fallback for comments and
non-terminal mission events; a subscribed terminal event arrives as a real room message instead, with no
polling. See [spaces.md](spaces.md) for the Missions board.
