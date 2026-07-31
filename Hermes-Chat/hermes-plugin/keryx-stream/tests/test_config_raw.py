"""Raw config editor tests — the guard rails, not the happy path.

Editing config.yaml from a phone is the most destructive thing Keryx can do, so
what matters is that every rejection leaves the file exactly as it was and that
a config Hermes refuses gets rolled back rather than left in place.
"""

import importlib.util
import sys
from pathlib import Path

import pytest

HERMES_ROOT = Path.home() / ".hermes" / "hermes-agent"
if str(HERMES_ROOT) not in sys.path:
    sys.path.insert(0, str(HERMES_ROOT))

pytest.importorskip("yaml")
hermes_config = pytest.importorskip("hermes_cli.config")

_SPEC = importlib.util.spec_from_file_location(
    "keryx_stream", Path(__file__).resolve().parent.parent / "keryx_stream.py"
)
ks = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ks)

ORIGINAL = """\
model:
  default: test-brain
agent:
  max_turns: 90
display:
  compact: false
memory:
  memory_enabled: true
"""


@pytest.fixture()
def cfg(tmp_path, monkeypatch):
    """A throwaway config.yaml with a loader that accepts anything by default."""
    path = tmp_path / "config.yaml"
    path.write_text(ORIGINAL, encoding="utf-8")
    monkeypatch.setattr(ks, "_config_path", lambda: path)
    monkeypatch.setattr(hermes_config, "load_config", lambda: {"ok": True})
    return path


def _backups(path: Path):
    return sorted(path.parent.glob(f"{path.name}.bak.keryx-*"))


def test_get_round_trips_content_and_hash(cfg):
    status, out = ks.config_raw_get()
    assert status == 200
    assert out["content"] == ORIGINAL
    assert out["path"] == str(cfg)
    assert out["hash"] == ks._config_hash(ORIGINAL)


def test_successful_save_writes_and_backs_up(cfg):
    new = ORIGINAL.replace("max_turns: 90", "max_turns: 120")
    status, out = ks.config_raw_put({"content": new, "base_hash": ks._config_hash(ORIGINAL)})
    assert status == 200 and out["ok"] is True
    assert cfg.read_text(encoding="utf-8") == new
    backups = _backups(cfg)
    assert len(backups) == 1
    assert backups[0].read_text(encoding="utf-8") == ORIGINAL
    assert out["backup"] == str(backups[0])


def test_malformed_yaml_is_refused_and_file_untouched(cfg):
    status, out = ks.config_raw_put({"content": "agent:\n  max_turns: [unclosed\n"})
    assert status == 400
    assert "yaml" in out["error"]["message"].lower()
    assert cfg.read_text(encoding="utf-8") == ORIGINAL
    assert _backups(cfg) == []


def test_non_mapping_yaml_is_refused(cfg):
    for junk in ("- just\n- a\n- list\n", "42\n", "plain string\n"):
        status, out = ks.config_raw_put({"content": junk})
        assert status == 400, junk
        assert "mapping" in out["error"]["message"]
    assert cfg.read_text(encoding="utf-8") == ORIGINAL


def test_empty_content_is_refused(cfg):
    for junk in ("", "   \n", None, 42):
        assert ks.config_raw_put({"content": junk})[0] == 400
    assert cfg.read_text(encoding="utf-8") == ORIGINAL


def test_stale_base_hash_is_a_conflict(cfg):
    status, out = ks.config_raw_put(
        {"content": "agent:\n  max_turns: 5\n", "base_hash": "deadbeefdeadbeef"}
    )
    assert status == 409
    assert "changed on the server" in out["error"]["message"]
    assert cfg.read_text(encoding="utf-8") == ORIGINAL


def test_truncated_paste_needs_force(cfg):
    """The phone failure mode: a fragment saved over the whole file."""
    fragment = "model:\n  default: test-brain\n"
    status, out = ks.config_raw_put({"content": fragment})
    assert status == 409
    assert out["error"]["needs_force"] is True
    assert "drops most of the file" in out["error"]["message"]
    assert cfg.read_text(encoding="utf-8") == ORIGINAL

    status, out = ks.config_raw_put({"content": fragment, "force": True})
    assert status == 200
    assert cfg.read_text(encoding="utf-8") == fragment


def test_config_rejected_by_hermes_is_rolled_back(cfg, monkeypatch):
    def angry_loader():
        raise ValueError("unknown provider 'nonsense'")

    monkeypatch.setattr(hermes_config, "load_config", angry_loader)
    new = ORIGINAL.replace("test-brain", "nonsense")

    status, out = ks.config_raw_put({"content": new})
    assert status == 400
    assert "rolled back" in out["error"]["message"]
    assert "unknown provider" in out["error"]["message"]
    # The whole point: disk is back to what it was.
    assert cfg.read_text(encoding="utf-8") == ORIGINAL
    # And the backup still exists as a second line of defence.
    assert _backups(cfg)[0].read_text(encoding="utf-8") == ORIGINAL


def test_oversized_config_is_refused(cfg):
    status, out = ks.config_raw_put({"content": "a: 1\n" + "#pad\n" * 500_000})
    assert status == 400
    assert "implausibly large" in out["error"]["message"]
    assert cfg.read_text(encoding="utf-8") == ORIGINAL


def test_no_temp_file_is_left_behind(cfg):
    ks.config_raw_put({"content": "model:\n  default: x\nagent:\n  max_turns: 1\n"
                                  "display:\n  compact: true\nmemory:\n  memory_enabled: false\n"})
    leftovers = [p.name for p in cfg.parent.iterdir() if "keryx-tmp" in p.name]
    assert leftovers == []
