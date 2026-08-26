"""The lifecycle status mirror and the full tool result (Keryx 2.5.7).

Compaction used to be minutes of silence on a Matrix turn: the agent core announces it through
``_emit_status``, ``gateway/run.py`` swallows the announcement on chat platforms by design, and
the side-channel carried nothing. These pin the two things that make the mirror safe on a cached
agent — it survives run.py re-assigning ``status_callback`` AFTER attach, and re-attaching per
turn never nests — plus the result clip keeping the tail a ``transform_tool_result`` plugin
appends to.

Run: pytest -q   (from this directory; no gateway or network needed)
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import keryx_stream as ks  # noqa: E402


class _Agent:
    model = "m"

    def __init__(self):
        self.log = []
        self.status_callback = None

    def _emit_status(self, message):
        self.log.append(("status", message))

    def _emit_warning(self, message):
        self.log.append(("warning", message))

    def _compress_context(self, messages, system, **kw):
        self.log.append("compress")
        return messages, system


class _Source:
    platform = "matrix"
    chat_id = "!room:x"


def _capture(monkeypatch):
    sent = []
    monkeypatch.setattr(ks.hub, "publish_threadsafe", lambda p, c, e, t: sent.append((p, c, e, t)))
    return sent


def _frames(sent, event):
    return [json.loads(t) for (_, _, e, t) in sent if e == event]


def test_compaction_status_is_mirrored_and_ready_follows_compress(monkeypatch):
    sent = _capture(monkeypatch)
    a = _Agent()
    ks.attach_reasoning_callback(a, _Source())
    # run.py assigns status_callback AFTER attach — the mirror must not depend on it.
    a.status_callback = lambda kind, msg: None
    a._emit_status("📦 Pre-API compression: ~123,456 tokens near the context/output limit. Compacting before the next model call.")
    a._compress_context([], "sys")
    a._emit_warning("⚠ Compression aborted")
    frames = _frames(sent, "status")
    assert frames[0]["kind"] == "compacting"
    assert frames[0]["tokens"] == 123456
    assert frames[0]["text"].startswith("📦 Pre-API compression")
    assert frames[1] == {"kind": "ready"}
    assert frames[2]["kind"] == "warning"
    # The original emitters still ran — the mirror is a passenger.
    assert a.log == [("status", frames[0]["text"]), "compress", ("warning", "⚠ Compression aborted")]


def test_ready_fires_even_when_compression_raises(monkeypatch):
    sent = _capture(monkeypatch)
    a = _Agent()

    def boom(*_a, **_k):
        raise RuntimeError("summary model down")

    a._compress_context = boom
    ks.attach_reasoning_callback(a, _Source())
    try:
        a._compress_context([], "sys")
    except RuntimeError:
        pass
    assert _frames(sent, "status") == [{"kind": "ready"}]


def test_reattach_does_not_nest(monkeypatch):
    sent = _capture(monkeypatch)
    a = _Agent()
    for _ in range(5):
        ks.attach_reasoning_callback(a, _Source())
    a._emit_status("🗜️ Compacting context — summarizing earlier conversation so I can continue...")
    assert len(_frames(sent, "status")) == 1
    assert a.log == [("status", "🗜️ Compacting context — summarizing earlier conversation so I can continue...")]


def test_non_compaction_status_is_lifecycle():
    assert ks.classify_status("❌ Non-retryable error (HTTP 400): boom") == "lifecycle"
    assert ks.classify_status("💤 Resumed after 3600s idle — compacting ~120,000 tokens before continuing.") == "compacting"


def test_tool_end_carries_result_on_success_and_keeps_the_tail(monkeypatch):
    sent = _capture(monkeypatch)
    a = _Agent()
    a.tool_progress_callback = None
    ks.attach_reasoning_callback(a, _Source())
    verdict = "\n\n🔎 syntax-oracle — deterministic diagnosis from Python's tokenizer, not a guess:\n  line 3: unclosed '('"
    body = "x" * 5000 + verdict
    a.tool_progress_callback("tool.started", "write_file", "a.py", None)
    a.tool_progress_callback("tool.completed", "write_file", None, None, duration=0.05, is_error=False, result=body)
    end = [f for f in _frames(sent, "tool") if f["phase"] == "end"][0]
    assert end["ok"] is True
    assert end["result"].endswith("line 3: unclosed '('")
    assert end["result"].startswith("xxxx")
    assert "chars elided" in end["result"]
    assert end["result_len"] == len(body)
    assert len(end["result"]) <= ks._TOOL_RESULT_MAX + 40


def test_short_result_is_verbatim():
    assert ks._clip_middle("ok\r\n") == "ok"
    assert ks._clip_middle(None) == ""
