"""Regression tests for the Keryx side-channel delta coalescing.

Guards the fix for the "fast brain outruns the stream" bug: a brain generating faster than the
client drains (e.g. DFlash turbo at ~150 tok/s) used to fill the bounded per-subscriber queue and
drop the overflow, which broke the client's byte-exact StreamHandoff (duplicate bubble / stuck
overlay). `drain_coalesced` merges a burst of queued deltas into as few frames as possible,
bounding the write rate to the drain rate. These tests pin the two properties that make that safe:
the merge is byte-exact, and event ordering across segment/stop boundaries is preserved.

Run: pytest -q   (from this directory; no gateway or network needed)
"""

import asyncio
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from keryx_stream import drain_coalesced  # noqa: E402


def _queue(items):
    q = asyncio.Queue()
    for it in items:
        q.put_nowait(it)
    return q


def _first(q):
    """Mimic the handler: it has already pulled one item before calling drain_coalesced."""
    return q.get_nowait()


def test_consecutive_deltas_merge_byte_exact():
    # A burst of token deltas must collapse to a single frame whose text is the exact
    # concatenation — no bytes added, dropped, or reordered.
    tokens = ["Hel", "lo,", " wor", "ld", "!"]
    q = _queue([("delta", t) for t in tokens])
    frames, stop = drain_coalesced(q, _first(q))
    assert frames == [("delta", "Hello, world!")]
    assert stop is False


def test_stop_flushes_pending_delta_and_terminates():
    # The final delta before stop must be emitted, and stop must be reported so the caller closes.
    q = _queue([("delta", "answer"), ("stop", "answer")])
    frames, stop = drain_coalesced(q, _first(q))
    assert frames == [("delta", "answer"), ("stop", "answer")]
    assert stop is True


def test_segment_boundary_splits_runs_in_order():
    # A segment break (text -> tool -> text) must NOT be merged away: the two text runs stay
    # separate frames on either side of the segment, preserving wire order.
    q = _queue([
        ("delta", "before"),
        ("delta", " tool"),
        ("segment", None),
        ("delta", "after"),
    ])
    frames, stop = drain_coalesced(q, _first(q))
    assert frames == [("delta", "before tool"), ("segment", None), ("delta", "after")]
    assert stop is False


def test_large_burst_survives_intact():
    # The real failure mode: hundreds of deltas arrive faster than they drain. Every token must
    # still be present, in order, once, after coalescing (this is what queue-overflow used to break).
    tokens = [f"t{i}" for i in range(1000)]
    q = _queue([("delta", t) for t in tokens] + [("stop", "".join(tokens))])
    frames, stop = drain_coalesced(q, _first(q))
    assert stop is True
    delta_text = "".join(t for ev, t in frames if ev == "delta")
    assert delta_text == "".join(tokens)
    # And the committed final carried by stop matches the streamed text exactly (handoff invariant).
    stop_text = next(t for ev, t in frames if ev == "stop")
    assert stop_text == delta_text


def test_reasoning_runs_coalesce_but_never_merge_into_answer_text():
    # Live reasoning rides the same channel as answer deltas. Consecutive reasoning frames
    # coalesce like deltas do, but a reasoning→delta (or delta→reasoning) crossover must flush,
    # never concatenate across types — reasoning bytes leaking into the answer would break the
    # client's StreamHandoff byte-match against the committed message.
    q = _queue([
        ("reasoning", "let me "),
        ("reasoning", "think"),
        ("delta", "The answer"),
        ("delta", " is 4."),
        ("reasoning", "hm"),
        ("stop", "The answer is 4."),
    ])
    frames, stop = drain_coalesced(q, _first(q))
    assert frames == [
        ("reasoning", "let me think"),
        ("delta", "The answer is 4."),
        ("reasoning", "hm"),
        ("stop", "The answer is 4."),
    ]
    assert stop is True


def test_none_delta_text_treated_as_empty():
    # A stray None-text delta must not blow up the join or corrupt the stream.
    q = _queue([("delta", "a"), ("delta", None), ("delta", "b")])
    frames, stop = drain_coalesced(q, _first(q))
    assert frames == [("delta", "ab")]
    assert stop is False


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
