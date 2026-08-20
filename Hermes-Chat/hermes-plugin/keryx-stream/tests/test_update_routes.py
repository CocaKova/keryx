"""Hermes-update endpoint tests.

Three things here are load-bearing and all three are silent when wrong:

  * the COMMAND must never reach the client — only its label,
  * an untrustworthy count must surface as -1 ("unknown"), never 0 ("up to date"),
  * the config tiers must resolve in the right order, because tier 2 (`hermes update`)
    is correct on a stock install and destructive on a patched one.
"""

import importlib.util
import sys
from pathlib import Path

import pytest

HERMES_ROOT = Path.home() / ".hermes" / "hermes-agent"
if str(HERMES_ROOT) not in sys.path:
    sys.path.insert(0, str(HERMES_ROOT))

hermes_config = pytest.importorskip("hermes_cli.config")

_SPEC = importlib.util.spec_from_file_location(
    "keryx_stream", Path(__file__).resolve().parent.parent / "keryx_stream.py"
)
ks = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ks)


@pytest.fixture
def cfg(monkeypatch):
    """Swap config.yaml for a dict the test controls."""
    def _set(keryx_block):
        monkeypatch.setattr(
            hermes_config, "load_config",
            lambda *a, **k: ({"keryx": keryx_block} if keryx_block is not None else {}),
        )
    return _set


# --- tier resolution -------------------------------------------------------

def test_configured_wrapper_wins(cfg):
    cfg({"update": {"command": "my-update --yes", "label": "my-update"}})
    entry = ks._update_entry()
    assert entry["command"] == "my-update --yes"
    assert entry["label"] == "my-update"
    assert entry["source"] == "configured"


def test_label_defaults_to_first_word(cfg):
    cfg({"update": {"command": "my-update --yes --force"}})
    assert ks._update_entry()["label"] == "my-update"


def test_stock_install_falls_back_to_hermes_update(cfg):
    """No config at all still yields a working button — this ships to people who
    have never configured anything."""
    cfg(None)
    entry = ks._update_entry()
    assert entry is not None
    assert entry["command"].startswith("hermes ")
    assert entry["source"] == "default"


def test_enabled_false_hides_the_button(cfg):
    cfg({"update": {"command": "my-update", "enabled": False}})
    assert ks._update_entry() is None


def test_guidance_text_is_not_offered_as_a_command(cfg, monkeypatch):
    """Docker/Nix installs get multi-line GUIDANCE from recommended_update_command,
    which must never be handed to bash as if it were runnable."""
    cfg(None)
    monkeypatch.setattr(
        hermes_config, "recommended_update_command",
        lambda: "Hermes is installed via Nix.\nRun: nix profile upgrade hermes",
    )
    assert ks._update_entry() is None


# --- probe (anchor script) -------------------------------------------------

def test_probe_string_form(cfg):
    cfg({"update": {"probe": "my-update --check", "probe_label": "anchor gate"}})
    probe = ks._update_probe_entry()
    assert probe == {"command": "my-update --check", "label": "anchor gate"}


def test_probe_dict_form(cfg):
    cfg({"update": {"probe": {"command": "probe.sh", "label": "preflight"}}})
    assert ks._update_probe_entry()["command"] == "probe.sh"


def test_probe_label_defaults(cfg):
    cfg({"update": {"probe": "probe.sh"}})
    assert ks._update_probe_entry()["label"] == "preflight"


def test_no_probe_configured(cfg):
    cfg({"update": {"command": "my-update"}})
    assert ks._update_probe_entry() is None


def test_probe_route_refuses_when_unconfigured(cfg):
    cfg({"update": {"command": "my-update"}})
    status, payload = ks.update_probe()
    assert status == 501
    assert "anchor script" in payload["error"]["message"]


# --- the snapshot contract -------------------------------------------------

def test_snapshot_never_leaks_the_command(cfg):
    cfg({"update": {"command": "my-update --token hunter2", "probe": "probe.sh"}})
    snap = ks.update_snapshot()
    blob = repr(snap)
    assert "hunter2" not in blob
    assert "probe.sh" not in blob
    assert snap["command_configured"] is True
    assert snap["probe_configured"] is True


def test_snapshot_has_every_field_the_client_parses(cfg):
    cfg(None)
    snap = ks.update_snapshot()
    for key in (
        "supported", "reason", "behind", "ahead", "branch", "head", "head_branch",
        "version", "command_configured", "label", "command_source", "checked_at",
        "checking", "check_error", "running", "probe_configured", "probe_label",
        "probe_running", "probe_exit", "probe_output", "probe_at",
    ):
        assert key in snap, f"missing {key}"


def test_probe_exit_starts_as_none_not_zero(cfg):
    """"Not run yet" and "passed" must not look alike: an update gated on a
    preflight nobody ran is an ungated update."""
    cfg({"update": {"probe": "probe.sh"}})
    ks._UPDATE_PROBE.update({"running": False, "ts": 0.0, "exit": None, "output": ""})
    assert ks.update_snapshot()["probe_exit"] is None


def test_unresolvable_branch_reports_unknown_not_up_to_date(cfg, monkeypatch):
    cfg({"update": {"branch": "nope/nothing"}})
    monkeypatch.setattr(ks, "_update_compare_ref", lambda tree, branch: "")
    snap = ks.update_snapshot()
    assert snap["behind"] == -1
    assert snap["behind"] != 0


def test_compare_ref_falls_back_when_configured_branch_is_missing(cfg, monkeypatch):
    """A fork has origin+fork+up; a plain install has only origin. Counting against
    a ref git can't resolve is what produces a bogus 0."""
    cfg(None)
    seen = []

    def fake_git(tree, *args, **kw):
        seen.append(args)
        if args[:2] == ("rev-parse", "--verify"):
            return (0, "") if args[-1] == "origin/main" else (1, "")
        return (0, "")

    monkeypatch.setattr(ks, "_git", fake_git)
    assert ks._update_compare_ref(Path("/tmp"), "does/not-exist") == "origin/main"
