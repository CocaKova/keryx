"""Config knob table tests — every knob is checked against Hermes' OWN defaults.

The knob table is hand-written metadata pointing into config.yaml, so the failure
mode is a silent typo: a knob whose path doesn't exist writes a brand-new key
that nothing reads, and the app shows a control that does nothing. Validating
each spec against hermes_cli.config.DEFAULT_CONFIG turns that into a test
failure instead of a dead switch on the phone.
"""

import importlib.util
import sys
from pathlib import Path

import pytest

HERMES_ROOT = Path.home() / ".hermes" / "hermes-agent"
if str(HERMES_ROOT) not in sys.path:
    sys.path.insert(0, str(HERMES_ROOT))

hermes_config = pytest.importorskip("hermes_cli.config")
DEFAULTS = hermes_config.DEFAULT_CONFIG

_SPEC = importlib.util.spec_from_file_location(
    "keryx_stream", Path(__file__).resolve().parent.parent / "keryx_stream.py"
)
ks = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ks)

KNOBS = ks._CONFIG_KNOBS

# Knobs that legitimately do NOT appear in DEFAULT_CONFIG. Each one is listed
# with the read site that proves the key is real and supplies its fallback, so
# this stays an audited exception list rather than a hole in the check. A knob
# added here without a verified read site is a dead switch on the phone —
# memory.flush_min_turns was exactly that (present in config.yaml, read by
# nothing) and was dropped instead of being excused.
_NOT_IN_DEFAULTS = {
    # dynamic profile enums — value space is the routing map, not the defaults
    "missions_default_assignee",
    "missions_orchestrator",
    # display.get("tool_progress", "all")
    "tool_progress",
    # _cleanup_inactive_envs(lifetime_seconds: int = 300)
    "terminal_lifetime",
    # skills.get("creation_nudge_interval", 10)
    "skills_creation_nudge",
}


def _resolve(spec):
    """Walk (section, path) through DEFAULT_CONFIG; KeyError if it isn't real."""
    node = DEFAULTS[spec["section"]]
    for part in spec["path"]:
        node = node[part]
    return node


@pytest.mark.parametrize("key", sorted(KNOBS))
def test_every_knob_points_at_a_real_config_path(key):
    spec = KNOBS[key]
    if key in _NOT_IN_DEFAULTS:
        pytest.skip("knob targets a Keryx-managed section")
    try:
        _resolve(spec)
    except (KeyError, TypeError):
        pytest.fail(
            f"knob '{key}' points at {spec['section']}.{'.'.join(spec['path'])} "
            "which does not exist in Hermes' DEFAULT_CONFIG"
        )


@pytest.mark.parametrize("key", sorted(KNOBS))
def test_declared_kind_matches_the_real_default_type(key):
    spec = KNOBS[key]
    if key in _NOT_IN_DEFAULTS:
        pytest.skip("knob targets a Keryx-managed section")
    actual = _resolve(spec)
    kind = spec["kind"]
    if kind == "bool":
        assert isinstance(actual, bool), f"{key}: declared bool, default is {actual!r}"
    elif kind == "int":
        assert isinstance(actual, int) and not isinstance(actual, bool), (
            f"{key}: declared int, default is {actual!r}"
        )
    elif kind == "float":
        assert isinstance(actual, (int, float)) and not isinstance(actual, bool), (
            f"{key}: declared float, default is {actual!r}"
        )
    elif kind == "enum":
        assert isinstance(actual, str), f"{key}: declared enum, default is {actual!r}"


@pytest.mark.parametrize("key", sorted(KNOBS))
def test_declared_default_matches_hermes_default(key):
    """Our "default" is what the app shows when the key is absent from
    config.yaml — if it disagrees with Hermes, the phone lies about the state."""
    spec = KNOBS[key]
    if key in _NOT_IN_DEFAULTS:
        pytest.skip("knob targets a Keryx-managed section")
    actual = _resolve(spec)
    declared = spec["default"]
    if spec["kind"] == "float":
        assert float(declared) == pytest.approx(float(actual)), key
    else:
        assert declared == actual, (
            f"{key}: table says {declared!r}, Hermes default is {actual!r}"
        )


@pytest.mark.parametrize("key", sorted(KNOBS))
def test_spec_shape_is_complete(key):
    spec = KNOBS[key]
    for field in ("section", "path", "kind", "label", "group", "description", "applies"):
        assert spec.get(field), f"{key} is missing '{field}'"
    assert isinstance(spec["path"], list) and spec["path"], key
    if spec["kind"] in ("int", "float"):
        assert spec["min"] < spec["max"], key
        assert spec["min"] <= spec["default"] <= spec["max"], (
            f"{key}: default {spec['default']} outside {spec['min']}–{spec['max']}"
        )
    if spec["kind"] == "enum" and not spec.get("choices_dynamic"):
        assert spec["default"] in spec["choices"], key
        assert spec["choices"] == [c.lower() for c in spec["choices"]], (
            f"{key}: static enum choices must be lowercase — config_knob_set "
            "lowercases what the phone sends before matching"
        )


def test_enum_choices_are_accepted_by_the_setter(monkeypatch):
    """Guards the round trip: every static enum choice must survive
    config_knob_set's own validation, not just look right in the table."""
    saved = {}
    monkeypatch.setattr(
        ks, "_toolsets_env_set", lambda *_a, **_kw: set(), raising=False
    )
    fake = {"hermes_cli.config": None}

    import hermes_cli.config as hc

    monkeypatch.setattr(hc, "load_config", lambda: {})
    monkeypatch.setattr(hc, "save_config", lambda cfg: saved.update(cfg))

    for key, spec in KNOBS.items():
        if spec["kind"] != "enum" or spec.get("choices_dynamic"):
            continue
        for choice in spec["choices"]:
            status, out = ks.config_knob_set(key, choice)
            assert status == 200, f"{key}={choice!r} rejected: {out}"
            assert out["value"] == choice
    assert fake  # keeps the fixture honest about having run


def test_unknown_key_and_out_of_range_are_refused(monkeypatch):
    import hermes_cli.config as hc

    monkeypatch.setattr(hc, "load_config", lambda: {})
    monkeypatch.setattr(hc, "save_config", lambda cfg: None)
    monkeypatch.setattr(
        ks, "_toolsets_env_set", lambda *_a, **_kw: set(), raising=False
    )

    assert ks.config_knob_set("no_such_knob", 1)[0] == 400
    assert ks.config_knob_set("memory_char_limit", 10)[0] == 400  # below min
    assert ks.config_knob_set("memory_char_limit", 10**9)[0] == 400  # above max
    assert ks.config_knob_set("memory_enabled", "yes")[0] == 400  # not a bool
    assert ks.config_knob_set("browser_dialog_policy", "explode")[0] == 400


def test_knob_count_and_grouping():
    """1.25 widened the surface; groups are what the app renders as sections."""
    assert len(KNOBS) >= 55
    groups = {spec["group"] for spec in KNOBS.values()}
    for expected in (
        "Behavior", "Display", "Missions", "Compression",
        "Agent", "Tools", "Terminal", "Browser",
        "Memory", "Skills", "Delegation", "Voice", "Safety",
    ):
        assert expected in groups, f"missing group {expected}"
