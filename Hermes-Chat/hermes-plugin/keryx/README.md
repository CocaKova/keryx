# keryx Hermes plugin (Skill Forge backend) — DRAFT

Staged artifact, **not installed**. The only Keryx feature that genuinely needs a Hermes-side plugin
is **Skill Forge editing** (reading/writing a saved skill's `SKILL.md`), because the app has no
filesystem access and no hook can rewrite outgoing messages. Citations and the Skill-Distilled signal
do **not** need this — they're a system-prompt nudge (see `system-prompt-snippet.md`).

Status: `handler.py` is a **reference draft, UNTESTED**. Two things must be verified on a live gateway
before relying on it (I couldn't, running unattended):

1. **Command routing.** Does a Matrix message like `!keryx skill get <id>` reach a `command:*` hook,
   and can the handler's return value (via `emit_collect`) be delivered back to the room as a reply?
   `gateway/hooks.py` shows `command:*` + `emit_collect` rewrite/decision support, but `!`-prefixed
   custom commands (vs `/`-commands) may not route here. **Verify first.**
2. **Skill storage path.** Confirm where `skill_manage` writes `SKILL.md` files (under `~/.hermes/`),
   so get/set target the right files.

## Intended command API (app ⇄ plugin, over Matrix)
- `!keryx ping` → reply `⟦keryx:v1⟧ caps=skills` (capability probe; app gates the Forge on this)
- `!keryx skill list` → reply with `id | name` lines
- `!keryx skill get <id>` → reply with the skill body in a fenced block
- `!keryx skill set <id>` + a fenced body → overwrite the skill, reply `ok`

## Install (when you're back and have verified the above)
1. `cp -r hermes-plugin/keryx ~/.hermes/plugins/keryx`  (or `~/.hermes/hooks/keryx`)
2. Ensure `plugins.enabled: true` in `~/.hermes/config.yaml`
3. Restart the gateway
4. In Keryx, the Skill Forge edit affordance appears once `!keryx ping` succeeds.

## Open design choice for you
Citations are currently **model-emitted** (prompt nudge). If you'd rather have them be deterministic
(plugin-computed from actual recall sources), that needs a **core** change to expose recall provenance
to a hook — bigger than a plugin. Recommend starting with the prompt nudge.
