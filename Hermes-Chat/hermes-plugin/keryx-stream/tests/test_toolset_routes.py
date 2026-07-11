"""Toolset toggle wrapper tests — real hermes tools_config helpers with the
config load/save seams monkeypatched (no aiohttp, no gateway, never touches
the live ~/.hermes/config.yaml).

Covered: locked/forbidden env refusals, unknown-toolset 400, the platform-key
gate, and the no_mcp-sentinel preservation that _save_platform_tools would
otherwise strip (its picker-consent rule must not apply to a phone toggle).
They need a hermes-agent install for hermes_cli; without one they skip.
"""

import importlib.util
import sys
from pathlib import Path

import pytest

HERMES_ROOT = Path.home() / ".hermes" / "hermes-agent"
if str(HERMES_ROOT) not in sys.path:
    sys.path.insert(0, str(HERMES_ROOT))

pytest.importorskip("hermes_cli.tools_config")

_SPEC = importlib.util.spec_from_file_location(
    "keryx_stream", Path(__file__).resolve().parent.parent / "keryx_stream.py"
)
ks = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ks)


@pytest.fixture()
def config_seam(monkeypatch):
    """Route every config read/write through an in-memory dict."""
    import hermes_cli.config as hc
    import hermes_cli.tools_config as tc

    state = {
        "config": {
            "platform_toolsets": {
                "matrix": ["file", "no_mcp", "terminal", "todo", "web"],
            }
        },
        "saved": 0,
    }

    def fake_load():
        return state["config"]

    def fake_save(cfg):
        state["config"] = cfg
        state["saved"] += 1

    monkeypatch.setattr(hc, "load_config", fake_load)
    monkeypatch.setattr(hc, "save_config", fake_save)
    # tools_config bound save_config at import time — patch its copy too.
    monkeypatch.setattr(tc, "save_config", fake_save)
    monkeypatch.delenv("KERYX_TOOLSETS_LOCKED", raising=False)
    monkeypatch.delenv("KERYX_TOOLSETS_FORBIDDEN", raising=False)
    return state


def _matrix_list(state):
    return state["config"]["platform_toolsets"]["matrix"]


def test_unknown_toolset_is_400(config_seam):
    status, payload = ks.toolset_set_enabled("definitely-not-real", True, "matrix")
    assert status == 400
    assert config_seam["saved"] == 0


def test_locked_toolset_cannot_be_disabled(config_seam, monkeypatch):
    monkeypatch.setenv("KERYX_TOOLSETS_LOCKED", "terminal, no_mcp")
    status, payload = ks.toolset_set_enabled("terminal", False, "matrix")
    assert status == 403
    assert "locked" in payload["error"]["message"]
    assert config_seam["saved"] == 0
    # ...but enabling a locked toolset is fine (it's already the pinned state).
    status, _ = ks.toolset_set_enabled("terminal", True, "matrix")
    assert status == 200


def test_forbidden_toolset_cannot_be_enabled(config_seam, monkeypatch):
    monkeypatch.setenv("KERYX_TOOLSETS_FORBIDDEN", "browser,computer_use")
    status, payload = ks.toolset_set_enabled("browser", True, "matrix")
    assert status == 403
    assert config_seam["saved"] == 0


def test_disable_preserves_no_mcp_sentinel(config_seam):
    status, payload = ks.toolset_set_enabled("web", False, "matrix")
    assert status == 200 and payload["enabled"] is False
    after = _matrix_list(config_seam)
    assert "web" not in after
    assert "no_mcp" in after, "picker-consent rule must not strip the sentinel"
    assert "terminal" in after


def test_enable_adds_toolset_and_keeps_sentinel(config_seam):
    status, payload = ks.toolset_set_enabled("tts", True, "matrix")
    assert status == 200 and payload["enabled"] is True
    after = _matrix_list(config_seam)
    assert "tts" in after and "no_mcp" in after


def test_platform_key_gate():
    assert ks._toolsets_platform("") == "matrix"
    assert ks._toolsets_platform(" Telegram ") == "telegram"
    with pytest.raises(ValueError):
        ks._toolsets_platform("../evil")
    with pytest.raises(ValueError):
        ks._toolsets_platform("a b")


def test_snapshot_reports_locked_and_platform(config_seam, monkeypatch):
    monkeypatch.setenv("KERYX_TOOLSETS_LOCKED", "terminal")
    monkeypatch.setenv("KERYX_TOOLSETS_FORBIDDEN", "browser")
    snap = ks.toolsets_snapshot("matrix")
    assert snap["platform"] == "matrix" and snap["canToggle"] is True
    by_name = {t["name"]: t for t in snap["data"]}
    assert by_name["terminal"]["locked"] is True
    assert by_name["terminal"]["enabled"] is True
    if "browser" in by_name:
        assert by_name["browser"]["locked"] is True
    assert by_name["web"]["enabled"] is True
    fixture = {"file", "no_mcp", "terminal", "todo", "web"}
    not_enabled = [t for t in snap["data"] if t["name"] not in fixture]
    assert not_enabled and all(t["enabled"] is False for t in not_enabled)
