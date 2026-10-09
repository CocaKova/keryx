#!/usr/bin/env python3
"""Diff the Hermes names Keryx matches on against a live Hermes tree (Keryx 2.19).

Keryx declares every tool, gateway method, event and server request it depends on in
`Hermes-Chat/core/.../model/KnownWire.kt` (tests keep that table complete). This script reads
it and compares it with a Hermes checkout:

  * the tool registry, the legacy alias table and each tool's argument schema, read by
    importing Hermes with its own interpreter;
  * the gateway contract (`apps/shared/src/gateway-contract.openrpc.json`): methods,
    notifications and server requests;
  * the API server's `/v1/runs` SSE event names, by text search.

A finding is one of:
  DRIFT  something Keryx relies on is gone or renamed: the app will draw it wrong or call
         a method that no longer exists. The ship gate turns AMBER.
  NOTE   Hermes has something Keryx doesn't know yet (a new tool, a new notification). Not
         broken; worth a look.

Exit status: 0 = no drift, 1 = drift, 2 = could not run (no Hermes tree, unreadable files).

Usage:
  tools/hermes_drift.py [--hermes ~/.hermes/hermes-agent] [--json] [--quiet]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MODEL = REPO / "Hermes-Chat/core/src/commonMain/kotlin/chat/keryx/core/model"
KNOWN_WIRE = MODEL / "KnownWire.kt"
TOOL_WIRE = MODEL / "ToolWire.kt"
CONTRACT = "apps/shared/src/gateway-contract.openrpc.json"

# Runs inside Hermes' own interpreter; prints one JSON object.
PROBE = r"""
import json, os, sys
sys.path.insert(0, os.environ["KERYX_DRIFT_ROOT"])
sys._hermes_pin_default_home = True
try:
    import hermes_bootstrap  # puts the managed dependencies on sys.path
except Exception:
    pass
import model_tools
from tools.registry import registry
names = sorted(registry.get_all_tool_names())
schemas = {}
for n in names:
    try:
        s = registry.get_schema(n) or {}
        params = s.get("parameters") or s.get("function", {}).get("parameters") or {}
        schemas[n] = sorted((params.get("properties") or {}).keys())
    except Exception:
        pass
try:
    from tools.tool_labels import BRIDGE_TOOL_NAMES as bridge
except Exception:
    bridge = []
print(json.dumps({
    "tools": names,
    "aliases": dict(getattr(model_tools, "_LEGACY_TOOL_ALIASES", {})),
    "schemas": schemas,
    "bridge": sorted(bridge),
}))
"""


def kotlin_sets(text: str) -> dict[str, set[str]]:
    """`val NAME: Set<String> = setOf(...)` blocks, by name."""
    out = {}
    for m in re.finditer(r"val\s+([A-Z_]+)\s*:\s*Set<String>\s*=\s*setOf\((.*?)\)\n", text, re.S):
        out[m.group(1)] = set(re.findall(r'"([^"]+)"', m.group(2)))
    return out


def kotlin_pairs(text: str, name: str) -> dict[str, str]:
    """`"a" to "b"` pairs inside the `val NAME ... = mapOf(...)` block."""
    m = re.search(rf"val\s+{name}\b[^=]*=\s*mapOf\((.*?)\)\n", text, re.S)
    if not m:
        return {}
    return dict(re.findall(r'"([^"]+)"\s+to\s+"([^"]+)"', m.group(1)))


def hermes_python(root: Path) -> list[str] | None:
    """The interpreter Hermes runs under, asked of its own launcher when it has one."""
    launcher = root / ".hermes/bin/hermes"
    if launcher.exists():
        try:
            out = subprocess.run([str(launcher), "--print-runtime-command"], capture_output=True,
                                 text=True, timeout=60, check=True).stdout
            cmd = json.loads(out)
            return [cmd[0], "-I"]
        except Exception:
            pass
    for venv in ("venv", ".venv"):
        py = root / venv / "bin/python"
        if py.exists():
            return [str(py)]
    return None


def probe(root: Path) -> dict | None:
    py = hermes_python(root)
    if py is None:
        return None
    env = {k: v for k, v in os.environ.items() if k not in ("PYTHONPATH", "PYTHONHOME", "VIRTUAL_ENV")}
    env["KERYX_DRIFT_ROOT"] = str(root)
    try:
        r = subprocess.run([*py, "-c", PROBE], capture_output=True, text=True, timeout=180,
                           env=env, cwd=str(root))
    except Exception as e:  # noqa: BLE001 — any failure means "could not read the registry"
        print(f"hermes_drift: registry probe failed: {e}", file=sys.stderr)
        return None
    last = (r.stdout.strip().splitlines() or [""])[-1]
    try:
        return json.loads(last)
    except ValueError:
        tail = (r.stderr.strip().splitlines() or ["(no output)"])[-1]
        print(f"hermes_drift: registry probe failed: {tail}", file=sys.stderr)
        return None


def run(root: Path) -> tuple[list[dict], dict]:
    known_text = KNOWN_WIRE.read_text()
    known = kotlin_sets(known_text)
    primary = kotlin_pairs(known_text, "PRIMARY_ARGS")
    keryx_aliases = kotlin_pairs(TOOL_WIRE.read_text(), "LEGACY_ALIASES")
    contract = json.loads((root / CONTRACT).read_text())
    methods = {m["name"] for m in contract.get("methods", [])}
    notes = {n["name"] for n in contract.get("x-notifications", [])}
    requests = {n["name"] for n in contract.get("x-server-requests", [])}

    findings: list[dict] = []

    def add(kind: str, what: str, detail: str) -> None:
        findings.append({"kind": kind, "what": what, "detail": detail})

    for m in sorted(known["METHODS"] - methods):
        add("DRIFT", f"method {m}", "Keryx calls it; the gateway contract no longer has it")
    for e in sorted(known["EVENTS"] - notes):
        add("DRIFT", f"event {e}", "Keryx handles it; the gateway contract no longer lists it")
    for q in sorted(known["SERVER_REQUESTS"] - requests):
        add("DRIFT", f"request {q}", "Keryx answers it; the gateway contract no longer lists it")
    for q in sorted(requests - known["SERVER_REQUESTS"]):
        add("NOTE", f"request {q}", "Hermes may ask it; Keryx declines it (4404)")
    for e in sorted(known["LEGACY_EVENTS"] & notes):
        add("NOTE", f"event {e}", "listed as legacy in KnownWire, but the contract has it again")

    # Hermes' own action allowlist and the API server's run stream: text search, no schema.
    sources = "\n".join(p.read_text(errors="ignore") for d in ("tui_gateway", "gateway/platforms")
                        for p in (root / d).glob("*.py"))
    for a in sorted(known["CONTROL_ACTIONS"]):
        if f'"{a}"' not in sources and f"'{a}'" not in sources:
            add("DRIFT", f"control action {a}", "the goal strip sends it; no gateway source names it")
    for e in sorted(known["RUN_EVENTS"]):
        if f'"{e}"' not in sources and f"'{e}'" not in sources:
            add("DRIFT", f"run event {e}", "the stream door reads it; the API server never sends it")

    reg = probe(root)
    summary = {"hermes": str(root), "methods": len(methods), "notifications": len(notes),
               "registry": reg is not None}
    if reg is None:
        add("NOTE", "tool registry", "could not import Hermes to read it; tool names unchecked")
        return findings, summary

    tools = set(reg["tools"])
    meta = set(reg.get("bridge") or []) or known["META_TOOLS"]
    summary["tools"] = len(tools)
    for old, new in sorted(reg["aliases"].items()):
        if keryx_aliases.get(old) != new:
            add("DRIFT", f"rename {old} -> {new}",
                "Hermes aliases it; ToolWire.LEGACY_ALIASES doesn't (old sessions draw the old name)")
    for t in sorted(known["TOOLS"] - tools):
        add("DRIFT", f"tool {t}", "Keryx draws it by name; Hermes has no such tool"
            + (f" (now {reg['aliases'][t]})" if t in reg["aliases"] else ""))
    for t in sorted(known["META_TOOLS"] - meta):
        add("DRIFT", f"bridge tool {t}", "Keryx unwraps it; Hermes no longer names it a bridge")
    for p in sorted(known["TOOL_PREFIXES"]):
        if not any(t.startswith(p) for t in tools):
            add("DRIFT", f"tool family {p}*", "Keryx groups these; Hermes has none left")
    for t, arg in sorted(primary.items()):
        props = reg["schemas"].get(t)
        if props is not None and arg not in props:
            add("DRIFT", f"argument {t}.{arg}",
                f"the row preview reads it; the schema has {', '.join(props) or 'no arguments'}")
    for t in sorted(known["LEGACY_TOOLS"] & tools):
        add("NOTE", f"tool {t}", "listed as legacy in KnownWire, but Hermes registers it again")

    def drawn(t: str) -> bool:
        return t in known["TOOLS"] or any(t.startswith(p) for p in known["TOOL_PREFIXES"])

    generic = sorted(t for t in tools if not drawn(t))
    summary["generic_tools"] = generic
    if generic:
        add("NOTE", f"{len(generic)} tools draw generically",
            "no verb in ToolGrammar: " + ", ".join(generic[:12]) + (" …" if len(generic) > 12 else ""))
    unhandled = sorted(notes - known["EVENTS"] - known["LEGACY_EVENTS"])
    summary["unhandled_notifications"] = unhandled
    return findings, summary


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--hermes", default=os.environ.get("HERMES_AGENT_DIR", str(Path.home() / ".hermes/hermes-agent")))
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--quiet", action="store_true", help="print DRIFT lines only")
    a = ap.parse_args()
    root = Path(a.hermes).expanduser()
    if not (root / CONTRACT).exists():
        print(f"hermes_drift: no Hermes tree at {root} (missing {CONTRACT})", file=sys.stderr)
        return 2
    try:
        findings, summary = run(root)
    except (OSError, KeyError, ValueError) as e:
        print(f"hermes_drift: {e}", file=sys.stderr)
        return 2
    drift = [f for f in findings if f["kind"] == "DRIFT"]
    if a.json:
        print(json.dumps({"drift": len(drift), "findings": findings, "summary": summary}, indent=2))
    else:
        for f in findings:
            if a.quiet and f["kind"] != "DRIFT":
                continue
            print(f"  {f['kind']:<5}  {f['what']}: {f['detail']}")
        if not a.quiet:
            print(f"  ({summary.get('tools', '?')} tools, {summary['methods']} methods, "
                  f"{summary['notifications']} notifications checked; "
                  f"{len(summary.get('unhandled_notifications', []))} notifications Keryx ignores)")
        print("hermes_drift: " + ("CLEAN" if not drift else f"{len(drift)} DRIFT"))
    return 1 if drift else 0


if __name__ == "__main__":
    sys.exit(main())
