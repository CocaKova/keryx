"""Kanban helper tests — a real temp board via hermes_cli.kanban_db.

These exercise the pure (kb, conn)-taking helpers behind the /keryx/kanban/*
routes: board snapshot grouping, create semantics (assignee required, triage
parks spec-first), comment authorship, and the events cursor. They need a
hermes-agent install for hermes_cli; without one they skip rather than fail
(the coalescing tests stay standalone).
"""

import importlib.util
import sys
from pathlib import Path

import pytest

HERMES_ROOT = Path.home() / ".hermes" / "hermes-agent"
if str(HERMES_ROOT) not in sys.path:
    sys.path.insert(0, str(HERMES_ROOT))

kb = pytest.importorskip("hermes_cli.kanban_db")

_SPEC = importlib.util.spec_from_file_location(
    "keryx_stream", Path(__file__).resolve().parent.parent / "keryx_stream.py"
)
ks = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ks)


@pytest.fixture()
def board(tmp_path, monkeypatch):
    """A throwaway board db, isolated from ~/.hermes/kanban.db."""
    db_path = tmp_path / "kanban.db"
    monkeypatch.setenv("HERMES_KANBAN_DB", str(db_path))
    conn = kb.connect()
    yield conn
    conn.close()


def test_create_requires_title_and_assignee(board):
    with pytest.raises(ValueError):
        ks.kanban_create(kb, board, {"assignee": "milo"})
    with pytest.raises(ValueError):
        ks.kanban_create(kb, board, {"title": "orphan mission"})


def test_create_then_board_snapshot_groups_by_status(board):
    created = ks.kanban_create(kb, board, {"title": "ship 1.6", "assignee": "milo"})
    assert created["task_id"]
    # No parents -> dispatchable immediately.
    assert created["status"] == "ready"
    parked = ks.kanban_create(
        kb, board, {"title": "spec first", "assignee": "theo", "triage": True}
    )
    assert parked["status"] == "triage"

    snap = ks.kanban_board_snapshot(kb, board)
    assert [t["title"] for t in snap["tasks"]["ready"]] == ["ship 1.6"]
    assert [t["title"] for t in snap["tasks"]["triage"]] == ["spec first"]
    assert snap["counts"] == {"ready": 1, "triage": 1}
    # Card fields stay summary-shaped: excerpt, no dispatcher internals.
    card = snap["tasks"]["ready"][0]
    assert card["created_by"] == ks.KANBAN_ACTOR
    assert "body_excerpt" in card and "claim_lock" not in card


def test_detail_carries_comments_and_unknown_task_is_none(board):
    tid = ks.kanban_create(kb, board, {"title": "t", "assignee": "milo"})["task_id"]
    ks.kanban_comment(kb, board, tid, "from the phone")
    detail = ks.kanban_task_detail(kb, board, tid)
    assert detail["task"]["id"] == tid
    assert [c["body"] for c in detail["comments"]] == ["from the phone"]
    assert [c["author"] for c in detail["comments"]] == [ks.KANBAN_ACTOR]
    # Creation + comment both land in the event log shown on the card.
    assert {e["kind"] for e in detail["events"]} >= {"commented"}
    assert ks.kanban_task_detail(kb, board, "no-such-task") is None


def test_events_cursor_is_incremental(board):
    tid = ks.kanban_create(kb, board, {"title": "watched", "assignee": "milo"})["task_id"]
    first = ks.kanban_events_since(board, 0)
    assert first["events"] and first["cursor"] > 0
    # Nothing new -> empty page, cursor unchanged.
    again = ks.kanban_events_since(board, first["cursor"])
    assert again == {"events": [], "cursor": first["cursor"]}
    # New activity -> only the delta comes back.
    ks.kanban_comment(kb, board, tid, "ping")
    delta = ks.kanban_events_since(board, first["cursor"])
    assert [e["kind"] for e in delta["events"]] == ["commented"]
    assert delta["cursor"] > first["cursor"]
