"""Skill trash tests — delete / restore / purge on real skill_manager machinery.

The load-bearing one is test_trash_root_is_outside_every_skills_root: the whole
design rests on a trashed skill no longer being discoverable, and the loader's
EXCLUDED_SKILL_DIRS does NOT cover an arbitrary .trash name, so "hide it in a
dot-dir" would have left deleted skills live in the agent's system prompt.
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


def _discoverable(root: Path) -> set:
    """What the loader would actually pick up — the real exclusion rules."""
    return {
        p.parent.name
        for p in root.rglob("SKILL.md")
        if not skill_utils.is_excluded_skill_path(p)
    }


def test_trash_root_is_outside_every_skills_root(roots):
    local, external = roots
    root = ks._skill_trash_root()
    for skills_dir in (local, external):
        assert not ks._is_under(root, skills_dir)
    # And the guard actively refuses if that ever stops being true.
    monkey = [local, external, root / "nested"]
    original = skill_utils.get_all_skills_dirs
    skill_utils.get_all_skills_dirs = lambda: monkey
    try:
        with pytest.raises(RuntimeError, match="refusing to delete"):
            ks._assert_trash_isolated(root / "nested" / "trash")
    finally:
        skill_utils.get_all_skills_dirs = original


def test_delete_removes_skill_from_discovery(roots):
    local, _ = roots
    _seed(local, "doomed-skill")
    _seed(local, "keeper-skill")
    assert _discoverable(local) == {"doomed-skill", "keeper-skill"}

    status, out = ks.skill_delete("doomed-skill")
    assert status == 200 and out["ok"] is True

    # The point of the whole feature: it is gone from what the agent loads.
    assert _discoverable(local) == {"keeper-skill"}
    assert not (local / "doomed-skill").exists()
    assert ks.skill_read("doomed-skill") is None


def test_delete_then_restore_round_trips(roots):
    local, _ = roots
    skill_dir = _seed(local, "oops-skill", category="ops")
    (skill_dir / "reference.md").write_text("sidecar", encoding="utf-8")

    status, out = ks.skill_delete("oops-skill")
    assert status == 200
    entry_id = out["id"]

    status, listing = ks.skill_trash_list()
    assert status == 200
    entry = next(e for e in listing["entries"] if e["id"] == entry_id)
    assert entry["name"] == "oops-skill"
    assert entry["category"] == "ops"
    assert entry["restorable"] is True

    status, out = ks.skill_restore(entry_id)
    assert status == 200 and out["ok"] is True
    assert (local / "ops" / "oops-skill" / "SKILL.md").exists()
    assert (local / "ops" / "oops-skill" / "reference.md").read_text() == "sidecar"
    assert _discoverable(local) == {"oops-skill"}
    # Restoring consumes the trash entry.
    assert ks.skill_trash_list()[1]["entries"] == []


def test_restore_refuses_when_the_name_came_back(roots):
    local, _ = roots
    _seed(local, "returning-skill")
    status, out = ks.skill_delete("returning-skill")
    entry_id = out["id"]

    _seed(local, "returning-skill")  # recreated under the same name
    entry = next(e for e in ks.skill_trash_list()[1]["entries"] if e["id"] == entry_id)
    assert entry["restorable"] is False

    status, out = ks.skill_restore(entry_id)
    assert status == 409
    assert "exists again" in out["error"]["message"]
    # The refusal leaves both copies intact.
    assert (local / "returning-skill" / "SKILL.md").exists()
    assert ks.skill_trash_list()[1]["entries"][0]["id"] == entry_id


def test_purge_is_final(roots):
    local, _ = roots
    _seed(local, "burn-skill")
    entry_id = ks.skill_delete("burn-skill")[1]["id"]

    status, out = ks.skill_purge(entry_id)
    assert status == 200 and out["ok"] is True
    assert ks.skill_trash_list()[1]["entries"] == []
    assert ks.skill_restore(entry_id)[0] == 404
    assert ks.skill_purge(entry_id)[0] == 404


def test_external_skills_cannot_be_deleted(roots):
    _, external = roots
    _seed(external, "vendored-skill")
    status, out = ks.skill_delete("vendored-skill")
    assert status == 403
    assert (external / "vendored-skill" / "SKILL.md").exists()


def test_unknown_and_hostile_identifiers(roots):
    assert ks.skill_delete("no-such-skill")[0] == 404
    for bad in ("../evil", "a/b", ""):
        assert ks.skill_restore(bad)[0] in (400, 404)
        assert ks.skill_purge(bad)[0] in (400, 404)


def test_repeated_deletes_of_the_same_name_keep_separate_entries(roots):
    local, _ = roots
    _seed(local, "recycled-skill")
    first = ks.skill_delete("recycled-skill")[1]["id"]
    _seed(local, "recycled-skill")
    second = ks.skill_delete("recycled-skill")[1]["id"]

    assert first != second
    assert {e["id"] for e in ks.skill_trash_list()[1]["entries"]} == {first, second}
