"""Session-prune wrapper tests — a real temp SessionDB via hermes_state.

These exercise sessions_prune behind POST /keryx/sessions/prune with an
injected db (no aiohttp, no gateway): the bare-prune 90-day default, the
attribute-filter suppression rule mirrored from the dashboard endpoint,
dry-run vs wet behavior, and the ended-sessions-only upstream guarantee.
They need a hermes-agent install for hermes_state; without one they skip.
"""

import importlib.util
import sys
import time
from pathlib import Path

import pytest

HERMES_ROOT = Path.home() / ".hermes" / "hermes-agent"
if str(HERMES_ROOT) not in sys.path:
    sys.path.insert(0, str(HERMES_ROOT))

hs = pytest.importorskip("hermes_state")

_SPEC = importlib.util.spec_from_file_location(
    "keryx_stream", Path(__file__).resolve().parent.parent / "keryx_stream.py"
)
ks = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ks)


@pytest.fixture()
def db(tmp_path):
    d = hs.SessionDB(db_path=tmp_path / "state.db")
    yield d
    d.close()


def _seed(db, sid: str, days_old: float, ended: bool = True) -> None:
    db.create_session(sid, "gateway")
    if ended:
        db.end_session(sid, "test")
    db._conn.execute(
        "UPDATE sessions SET started_at = ? WHERE id = ?",
        (time.time() - days_old * 86400, sid),
    )
    db._conn.commit()


def test_bare_prune_defaults_to_90_days_dry_run(db):
    _seed(db, "ancient", days_old=200)
    _seed(db, "recent", days_old=10)
    _seed(db, "ancient-active", days_old=200, ended=False)

    out = ks.sessions_prune({"dry_run": True}, db=db)
    assert out["ok"] is True and out["removed"] == 0
    assert out["matched"] == 1
    assert [s["id"] for s in out["sessions"]] == ["ancient"]
    assert out["oldest_started_at"] == out["newest_started_at"]


def test_wet_prune_deletes_only_matches(db):
    _seed(db, "ancient", days_old=200)
    _seed(db, "recent", days_old=10)
    _seed(db, "ancient-active", days_old=200, ended=False)

    out = ks.sessions_prune({"older_than_days": 90}, db=db)
    assert out == {"ok": True, "removed": 1}
    left = {r[0] for r in db._conn.execute("SELECT id FROM sessions").fetchall()}
    assert left == {"recent", "ancient-active"}


def test_attribute_filter_suppresses_age_default(db):
    _seed(db, "ancient", days_old=200)
    _seed(db, "recent", days_old=10)

    # Attribute filter, no explicit age -> matches all ages (upstream rule).
    all_ages = ks.sessions_prune({"dry_run": True, "max_messages": 100}, db=db)
    assert all_ages["matched"] == 2
    # Explicit age alongside the attribute filter -> age applies again.
    aged = ks.sessions_prune(
        {"dry_run": True, "max_messages": 100, "older_than_days": 90}, db=db
    )
    assert aged["matched"] == 1


def test_tiny_age_without_window_is_rejected(db):
    with pytest.raises(ValueError):
        ks.sessions_prune({"older_than_days": 0}, db=db)
    # ...but an explicit started_before window makes small ages legal.
    out = ks.sessions_prune(
        {"older_than_days": 0.5, "started_before": time.time(), "dry_run": True},
        db=db,
    )
    assert out["ok"] is True
