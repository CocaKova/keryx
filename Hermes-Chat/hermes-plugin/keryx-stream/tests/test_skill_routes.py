"""Skill Forge helper tests — real skill_manager_tool machinery on temp dirs.

These exercise skill_read / skill_write / skill_create behind the
/keryx/skills/* routes: flat + category-nested lookup, the external-dir
readonly refusal, the .bak one-deep undo, and that writes ride the gateway's
own frontmatter validation. They need a hermes-agent install for
tools.skill_manager_tool; without one they skip rather than fail.
"""

import importlib.util
import sys
from pathlib import Path

import pytest

HERMES_ROOT = Path.home() / ".hermes" / "hermes-agent"
if str(HERMES_ROOT) not in sys.path:
    sys.path.insert(0, str(HERMES_ROOT))

sm = pytest.importorskip("tools.skill_manager_tool")
skill_utils = pytest.importorskip("agent.skill_utils")

_SPEC = importlib.util.spec_from_file_location(
    "keryx_stream", Path(__file__).resolve().parent.parent / "keryx_stream.py"
)
ks = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ks)


def _skill_md(name: str, body: str = "Do the thing.") -> str:
    return f"---\nname: {name}\ndescription: test skill\n---\n\n{body}\n"


@pytest.fixture()
def roots(tmp_path, monkeypatch):
    """A throwaway local skills root + an external (read-only) root."""
    local = tmp_path / "skills"
    external = tmp_path / "external"
    local.mkdir()
    external.mkdir()
    monkeypatch.setattr(sm, "SKILLS_DIR", local)
    monkeypatch.setattr(skill_utils, "get_all_skills_dirs", lambda: [local, external])
    return local, external


def _seed(root: Path, name: str, category: str = None) -> Path:
    skill_dir = (root / category / name) if category else (root / name)
    skill_dir.mkdir(parents=True)
    (skill_dir / "SKILL.md").write_text(_skill_md(name), encoding="utf-8")
    return skill_dir


def test_read_flat_and_nested_layouts(roots):
    local, _ = roots
    _seed(local, "flat-skill")
    nested_dir = _seed(local, "nested-skill", category="ops")
    (nested_dir / "reference.md").write_text("extra", encoding="utf-8")

    flat = ks.skill_read("flat-skill")
    assert flat["category"] is None
    assert flat["readonly"] is False
    assert "Do the thing." in flat["content"]

    nested = ks.skill_read("nested-skill")
    assert nested["category"] == "ops"
    assert nested["files"] == ["reference.md"]

    assert ks.skill_read("no-such-skill") is None


def test_read_resolves_frontmatter_display_names(roots):
    """/v1/skills lists frontmatter names ("Financial Dashboard Implementation")
    that can differ from the dir basename — GET must resolve both and hand back
    the canonical basename for the PUT that follows."""
    local, _ = roots
    skill_dir = local / "fancy-skill"
    skill_dir.mkdir()
    (skill_dir / "SKILL.md").write_text(
        "---\nname: Fancy Skill Display Name\ndescription: test\n---\n\nBody.\n",
        encoding="utf-8",
    )
    got = ks.skill_read("Fancy Skill Display Name")
    assert got is not None
    assert got["name"] == "fancy-skill"  # canonical, PUT-able
    assert ks.skill_read("fancy-skill")["name"] == "fancy-skill"


def test_write_updates_disk_and_keeps_bak(roots):
    local, _ = roots
    skill_dir = _seed(local, "scratch-skill")
    original = (skill_dir / "SKILL.md").read_text(encoding="utf-8")

    status, out = ks.skill_write("scratch-skill", _skill_md("scratch-skill", "v2 body"))
    assert status == 200 and out["ok"] is True
    assert "v2 body" in (skill_dir / "SKILL.md").read_text(encoding="utf-8")
    assert (skill_dir / "SKILL.md.bak").read_text(encoding="utf-8") == original
    # The undo file stays out of the app's sidecar listing.
    assert ks.skill_read("scratch-skill")["files"] == []


def test_write_rejects_bad_frontmatter_and_unknown_skill(roots):
    local, _ = roots
    _seed(local, "strict-skill")
    status, out = ks.skill_write("strict-skill", "no frontmatter at all")
    assert status == 400
    assert "frontmatter" in out["error"]["message"].lower()
    # Failed validation never touches the file.
    assert "Do the thing." in ks.skill_read("strict-skill")["content"]

    status, out = ks.skill_write("ghost-skill", _skill_md("ghost-skill"))
    assert status == 404


def test_external_dir_is_readonly(roots):
    _, external = roots
    _seed(external, "vendored-skill")
    detail = ks.skill_read("vendored-skill")
    assert detail["readonly"] is True
    status, out = ks.skill_write("vendored-skill", _skill_md("vendored-skill", "nope"))
    assert status == 403


def test_create_and_hostile_names(roots):
    local, _ = roots
    status, out = ks.skill_create(
        {"name": "born-on-phone", "content": _skill_md("born-on-phone")}
    )
    assert status == 200 and out["ok"] is True
    assert (local / "born-on-phone" / "SKILL.md").exists()

    with pytest.raises(ValueError):
        ks.skill_create({"name": "../evil", "content": _skill_md("evil")})
    with pytest.raises(ValueError):
        ks.skill_create({"name": "a/b", "content": _skill_md("ab")})
    with pytest.raises(ValueError):
        ks.skill_create({"name": "no-content"})
