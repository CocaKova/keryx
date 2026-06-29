"""keryx Hermes hook — Skill Forge backend (DRAFT, UNTESTED).

Implements the `!keryx skill …` command API from README.md so the Keryx app can list/read/edit
saved skills. This is the ONLY Keryx feature needing a plugin; citations + the skill-distilled
signal are a system-prompt nudge (see system-prompt-snippet.md).

⚠️  Two things to verify on a live gateway before trusting this (see README "Open questions"):
    1. that a `!keryx …` Matrix message reaches a `command:*` hook and the return value is delivered
       back to the room, and
    2. the on-disk skill path (SKILL_ROOT below).

Hook contract (gateway/hooks.py): a handler module exposes `async def handle(event_type, context)`.
For decision/reply hooks the gateway collects non-None return values via `emit_collect`.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, Optional

# TODO(verify): confirm where skill_manage writes SKILL.md files.
SKILL_ROOT = Path(os.path.expanduser("~/.hermes/skills"))


def _skill_file(skill_id: str) -> Optional[Path]:
    # Skills are directories containing SKILL.md; match by directory name == id.
    safe = "".join(c for c in skill_id if c.isalnum() or c in "-_")
    if not safe:
        return None
    candidate = SKILL_ROOT / safe / "SKILL.md"
    return candidate


def _list_skills() -> str:
    if not SKILL_ROOT.exists():
        return "(no skills found)"
    rows = []
    for d in sorted(SKILL_ROOT.iterdir()):
        md = d / "SKILL.md"
        if md.is_file():
            name = d.name
            rows.append(f"{d.name} | {name}")
    return "\n".join(rows) if rows else "(no skills found)"


async def handle(event_type: str, context: Dict[str, Any]) -> Optional[str]:
    # Only react to command events that look like `!keryx …`.
    text = (context.get("message") or "").strip()
    if not text.startswith("!keryx"):
        return None
    parts = text.split(maxsplit=3)  # ["!keryx", "skill", "get", "<id-and/or-body>"]

    if len(parts) >= 2 and parts[1] == "ping":
        return "⟦keryx:v1⟧ caps=skills"

    if len(parts) >= 2 and parts[1] == "skill":
        action = parts[2] if len(parts) >= 3 else "list"
        if action == "list":
            return "```\n" + _list_skills() + "\n```"
        if action == "get" and len(parts) >= 4:
            f = _skill_file(parts[3].strip())
            if f and f.is_file():
                return f"```\n{f.read_text()}\n```"
            return f"(skill not found: {parts[3]})"
        if action == "set" and len(parts) >= 4:
            # Expect "<id>\n```\n<body>\n```" — split id from fenced body.
            rest = parts[3]
            sid, _, body = rest.partition("\n")
            body = body.strip().removeprefix("```").removesuffix("```").strip("\n")
            f = _skill_file(sid.strip())
            if not f:
                return "(invalid skill id)"
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(body)
            return "ok"
    return "(keryx: unknown command)"
