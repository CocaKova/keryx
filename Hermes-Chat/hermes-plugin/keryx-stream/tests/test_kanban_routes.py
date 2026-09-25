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


def test_subscribe_list_unsubscribe_roundtrip(board):
    tid = ks.kanban_create(kb, board, {"title": "alertable", "assignee": "milo"})["task_id"]
    out = ks.kanban_subscribe(kb, board, tid, {"platform": "matrix", "chat_id": "!room:x"})
    assert out == {"task_id": tid, "subscribed": True}
    # Idempotent: upstream is INSERT OR IGNORE on (task, platform, chat, thread).
    ks.kanban_subscribe(kb, board, tid, {"platform": "matrix", "chat_id": "!room:x"})
    subs = ks.kanban_subs_list(kb, board)["subs"]
    assert len(subs) == 1
    assert subs[0]["task_id"] == tid
    assert subs[0]["chat_id"] == "!room:x"
    assert set(subs[0]) == set(ks._SUB_FIELDS)  # pinned columns only

    gone = ks.kanban_unsubscribe(kb, board, tid, {"chat_id": "!room:x"})
    assert gone == {"task_id": tid, "subscribed": False, "removed": True}
    # Second unsubscribe reports removed=False instead of erroring.
    assert ks.kanban_unsubscribe(kb, board, tid, {"chat_id": "!room:x"})["removed"] is False
    assert ks.kanban_subs_list(kb, board)["subs"] == []


def test_subscribe_validates_task_and_chat(board):
    assert ks.kanban_subscribe(kb, board, "no-such-task", {"chat_id": "!r:x"}) is None
    tid = ks.kanban_create(kb, board, {"title": "t", "assignee": "milo"})["task_id"]
    with pytest.raises(ValueError):
        ks.kanban_subscribe(kb, board, tid, {})
    with pytest.raises(ValueError):
        ks.kanban_unsubscribe(kb, board, tid, {})


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


# --- 2.14: the card says what it needs, and the owner can answer it --------


def _block(board, tid, reason, kind):
    kb.block_task(board, tid, reason=reason, kind=kind)


def test_blocked_card_carries_reason_kind_and_needs_you(board):
    tid = ks.kanban_create(kb, board, {"title": "draft post", "assignee": "theo"})["task_id"]
    _block(board, tid, "needs your yes: publish the draft", "needs_input")
    snap = ks.kanban_board_snapshot(kb, board)
    card = snap["tasks"]["blocked"][0]
    assert card["block_kind"] == "needs_input"
    assert card["needs_you"] is True
    assert card["ask_excerpt"] == "needs your yes: publish the draft"
    assert snap["needs_you"] == 1

    detail = ks.kanban_task_detail(kb, board, tid)
    t = detail["task"]
    assert t["block_reason"] == "needs your yes: publish the draft"
    assert t["ask"] == t["block_reason"] and t["needs_you"] is True
    assert "block_recurrences" in t
    for key in ("runs", "diagnostics", "parents", "children", "attachments"):
        assert isinstance(detail[key], list)


def test_transient_block_is_not_the_owners_job(board):
    tid = ks.kanban_create(kb, board, {"title": "wait on run", "assignee": "default"})["task_id"]
    _block(board, tid, "run still going", "transient")
    card = ks.kanban_board_snapshot(kb, board)["tasks"]["blocked"][0]
    assert card["needs_you"] is False
    assert card["ask_excerpt"] == "run still going"


def test_detail_links_carry_titles_and_status(board):
    parent = ks.kanban_create(kb, board, {"title": "run trials", "assignee": "default"})["task_id"]
    child = ks.kanban_create(kb, board, {"title": "write post", "assignee": "theo"})["task_id"]
    kb.link_tasks(board, parent, child)
    d = ks.kanban_task_detail(kb, board, child)
    assert [(p["id"], p["title"]) for p in d["parents"]] == [(parent, "run trials")]
    assert ks.kanban_task_detail(kb, board, parent)["children"][0]["title"] == "write post"


def test_reply_comments_as_owner_and_unblocks(board):
    tid = ks.kanban_create(kb, board, {"title": "needs a yes", "assignee": "theo"})["task_id"]
    _block(board, tid, "needs your yes", "needs_input")
    out = ks.kanban_reply(kb, board, tid, {"body": "yes, ship it", "unblock": True})
    assert out["unblocked"] is True and out["status"] != "blocked"
    detail = ks.kanban_task_detail(kb, board, tid)
    assert detail["comments"][-1] == {**detail["comments"][-1], "author": ks.KANBAN_OWNER, "body": "yes, ship it"}
    assert detail["task"]["block_reason"] is None


def test_reply_without_unblock_leaves_it_blocked(board):
    tid = ks.kanban_create(kb, board, {"title": "q", "assignee": "theo"})["task_id"]
    _block(board, tid, "which port?", "needs_input")
    out = ks.kanban_reply(kb, board, tid, {"body": "thinking about it"})
    assert out["unblocked"] is False and out["status"] == "blocked"


def test_reply_validates(board):
    tid = ks.kanban_create(kb, board, {"title": "q", "assignee": "theo"})["task_id"]
    with pytest.raises(ValueError):
        ks.kanban_reply(kb, board, tid, {"body": "  "})
    assert ks.kanban_reply(kb, board, "no-such-task", {"body": "hi"}) is None


def test_crash_streak_outlived_by_a_later_run_is_stale():
    badge = ks._diag_badge([{"kind": "repeated_crashes", "severity": "error", "stale": True}])
    assert badge == {"count": 1, "severity": "error", "stale": True}
    badge = ks._diag_badge([
        {"kind": "repeated_crashes", "severity": "critical", "stale": True},
        {"kind": "stuck_in_blocked", "severity": "warning", "stale": False},
    ])
    assert badge["severity"] == "warning" and badge["stale"] is False
    assert ks._diag_badge([]) is None


# --- 2.14: review verdicts from the phone -----------------------------------


def _to_review(board, title="report page", digest=None):
    tid = ks.kanban_create(kb, board, {"title": title, "assignee": "default"})["task_id"]
    kb.request_review(board, tid, summary="x", force=True)
    if digest:
        kb.add_comment(board, tid, author="default", body=digest)
    assert kb.get_task(board, tid).status == "review"
    return tid


def test_review_card_needs_you_and_carries_the_digest(board):
    tid = _to_review(board, digest="REVIEW DIGEST: what changed / proof / needs: your yes to deploy")
    card = ks.kanban_board_snapshot(kb, board)["tasks"]["review"][0]
    assert card["needs_you"] is True
    assert card["ask_excerpt"].startswith("REVIEW DIGEST")
    assert ks.kanban_task_detail(kb, board, tid)["task"]["ask"].endswith("your yes to deploy")


def test_approve_completes_a_review_card(board):
    tid = _to_review(board)
    out = ks.kanban_approve(kb, board, tid, {"note": "looks right, ship it"})
    assert out["completed"] is True and out["status"] == "done"
    detail = ks.kanban_task_detail(kb, board, tid)
    assert detail["comments"][-1]["author"] == ks.KANBAN_OWNER
    assert detail["task"]["latest_summary"] == "looks right, ship it"


def test_approve_refuses_a_card_not_in_review(board):
    tid = ks.kanban_create(kb, board, {"title": "q", "assignee": "theo"})["task_id"]
    with pytest.raises(ValueError):
        ks.kanban_approve(kb, board, tid, {})
    assert ks.kanban_approve(kb, board, "no-such-task", {}) is None


def test_request_changes_reopens_a_parked_review(board):
    tid = _to_review(board)
    out = ks.kanban_request_changes(kb, board, tid, {"reason": "fix the engine label"})
    assert out["status"] in ("ready", "todo")
    body = ks.kanban_task_detail(kb, board, tid)["comments"][-1]["body"]
    assert body == "CHANGES REQUESTED: fix the engine label"


def test_request_changes_needs_a_reason_and_a_review(board):
    tid = _to_review(board)
    with pytest.raises(ValueError):
        ks.kanban_request_changes(kb, board, tid, {"reason": " "})
    other = ks.kanban_create(kb, board, {"title": "q", "assignee": "theo"})["task_id"]
    with pytest.raises(ValueError):
        ks.kanban_request_changes(kb, board, other, {"reason": "nope"})
