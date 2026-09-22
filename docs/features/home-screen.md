# Home screen

Two ways to reach the agent without opening the app (2.13).

## The widget

A card for the home screen: the link (`live` / `reconnecting` / `offline` as a dot and a word), the
session you were in last with a two-line preview of its newest reply, and while a turn runs there,
`working · 0:42` or `2 helpers flying`. A mono footer with the clock. Tap it and the app opens on
that session; while the turn runs, it opens already tapped in.

It reads what the app already holds — the same transport, the same session peek the shade uses —
and never opens a second socket. It repaints on a 15-minute schedule, on every change of the link,
and on every run notice, floored to one repaint a few seconds so a turn's burst of notices costs
one draw and the turn's end is never the one dropped. Colours follow the app's theme and accent in
both day and night.

## The tile

A Quick Settings tile, `Note to <agent>` (the default profile's name where one is set). Tap it and a
one-line composer floats over whatever you were doing: type, send, done. The note goes to the
session you were in last, or the first pinned one; a row under the field changes the target. It
travels the same path a shared text does. The tile lights when the link is up and dims when it is
not, so a note into the void is never a surprise. Locked phone: it asks for the unlock first, as
the share sheet does.
