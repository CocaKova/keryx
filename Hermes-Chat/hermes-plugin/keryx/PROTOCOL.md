# Keryx ⟦…⟧ Marker Protocol v1

How the Keryx Android client surfaces Hermes' richer features (citations, skill distillation,
Skill Forge). All markers use the bracket pair `⟦ ⟧` (U+27E6 / U+27E7) — characters that never
appear in normal prose — and are emitted **inside the plaintext message body**. The client strips
every marker before display, so a Hermes that doesn't emit them is unaffected (**graceful
degradation** — absent markers ⇒ zero change).

This document is the contract. Two of the three features need only a **system-prompt nudge** (config,
no code). Only Skill Forge *editing* needs the bundled plugin.

---

## Architectural findings (why it's built this way)

Verified against the live hermes-agent source:

- **There is no outgoing-message-transform hook.** `gateway/hooks.py` fires `agent:end` *after* the
  message is sent and only passes the response truncated to 500 chars. The only rewrite hook is for
  slash-**commands** (`command:*` via `emit_collect`). ⇒ A plugin **cannot** inline-annotate the
  agent's answer. It can only (a) reply to a command, or (b) post a follow-up message.
- **Citations therefore come from the model, not a plugin.** The brain already does RAG/memory
  recall; nudging it to *write* citation markers in its answer is the only clean inline path. The
  client renders them.
- **Skills have no distillation event.** `agent/learn_prompt.py`: skills are saved by the model
  calling the **`skill_manage`** tool (no separate engine). So the "distilled" signal is also a
  model-emitted marker; *editing* a saved skill needs filesystem access → the plugin command API.

---

## Markers

### 1. Citations (Phase 3) — model-emitted, no plugin
In the answer text, reference a source inline:
```
The gateway hot-reloads config ⟦c1⟧, and reasoning is stripped before send ⟦c2⟧.
```
Then, anywhere in the message (the client pulls them out), define each source:
```
⟦cite 1 | memory | SILAS gateway hot-reloads config.yaml | episode:2026-06-28⟧
⟦cite 2 | file | run_agent.py:1388 | strip_think_blocks⟧
```
Fields: `n | kind | label | detail`. `kind` drives the icon (`memory`/`graphiti`/`recall` → 🧠,
`file`/`doc` → 📄, `web`/`url`/`search` → 🌐, `session`/`chat` → 💬, else 🔗).
Client render: inline `⟦cN⟧` → glowing `⁽ⁿ⁾` superscript; a "Sources" chip bar below the message;
tap a chip to reveal kind/label/detail.

### 2. Skill Distilled (Phase 5 signal) — model-emitted, no plugin
After saving a skill via `skill_manage`, emit once:
```
⟦keryx:skill | login-retry-flow | Login retry flow | Captures the email→password→2FA retry loop⟧
```
Fields: `id | name | summary`. Client render: a glowing "✦ Skill Distilled · <name>" pill; tap to
peek the summary.

### 3. Capability beacon (optional)
```
⟦keryx:v1⟧
```
Lets the client know a Keryx-aware Hermes/plugin is present (enables Forge affordances). Stripped
from display.

---

## Enabling (your choice — nothing here is auto-applied)

**Citations + Skill signal (no plugin):** add the snippet in `system-prompt-snippet.md` to SILAS's
personality block in `~/.hermes/config.yaml` (`personalities.silas`). Same category as the Phase 1
`show_reasoning` toggle — one config edit, hot-reloaded.

**Skill Forge editing (needs the plugin):** see `handler.py` + the install steps in `README.md`.
This is the only piece that needs a `~/.hermes/` install + gateway restart.
