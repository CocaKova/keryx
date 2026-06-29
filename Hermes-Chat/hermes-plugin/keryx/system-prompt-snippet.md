# SILAS system-prompt snippet — Keryx markers

Paste into `personalities.silas` (the personality/system block) in `~/.hermes/config.yaml`, then let
the gateway hot-reload. This makes SILAS emit Keryx citation + skill markers that the app renders.
Remove it to turn the features off. Safe for any client: non-Keryx clients just see the bracket text.

```
## Keryx client markers (Matrix)
When you state a fact that came from memory recall, a file you read, a web result, or a past
session, cite it for the Keryx app using these exact markers (plaintext, in your normal answer):
  • Inline, right after the claim: ⟦c1⟧ ⟦c2⟧ …
  • Define each source once, anywhere in the message:
      ⟦cite 1 | <kind> | <short label> | <detail>⟧
    where <kind> is one of: memory, file, web, session.  Example:
      ⟦cite 1 | memory | SILAS gateway hot-reloads config | episode:2026-06-28⟧
Only cite real sources; never invent one. No sources → emit no cite markers.

After you save a reusable skill with skill_manage, announce it once so the app can show it:
  ⟦keryx:skill | <skill-id> | <Skill Name> | <one-line summary of what it captures>⟧
```

Notes:
- The app strips these markers from display and renders glowing superscripts + a Sources bar +
  a "Skill Distilled" pill. Other Matrix clients show the literal bracket text (harmless).
- This is the same "one optional config nudge" pattern as `display.show_reasoning`.
