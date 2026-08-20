"""Keryx side-channel stream hub — the server half of Keryx's dual-tier streaming.

Installed as ``gateway/keryx_stream.py`` inside the hermes-agent tree (see install.py; this is a
REINSTALL-FRAGILE patch — re-run install.py after ``hermes update``).

What it does
============
The Keryx Android client opens a transient SSE subscription (``GET /keryx/stream?platform=matrix&
chat_id=<room>`` on the API server, Bearer-authed with API_SERVER_KEY) right before sending a
command into a Matrix room. While that subscriber is attached:

  * every assistant-text delta the ``GatewayStreamConsumer`` receives is mirrored to the SSE
    channel (``event: delta``) for live token rendering in the app;
  * protocol edits to the homeserver are suppressed — the room receives only the single final
    committed message (no m.replace database bloat);
  * ``event: stop`` fires when the turn's stream finishes, telling the client to hold its overlay
    until the final Matrix event syncs in;
  * ``event: tool`` carries the tool + subagent lifecycle (start / end / subagent.*) as JSON, so
    the app can show what the agent is DOING mid-turn instead of a spinner (see
    ``_attach_tool_callbacks``).

When no subscriber is attached and ``FALLBACK_EDITS`` is True, Matrix falls back to
smart-throttled native m.replace edits driven by the normal streaming config
(``streaming.edit_interval`` / ``streaming.buffer_threshold`` — tune to 1.2s / 60 in config.yaml).
Set ``FALLBACK_EDITS = False`` to restore final-message-only behaviour when Keryx is offline.

Thread-safety: ``publish_threadsafe`` is called from the agent's sync worker thread; delivery hops
onto each subscriber's event loop via ``call_soon_threadsafe``. Queues are bounded — a stalled
subscriber drops its own events, never blocks the agent.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import shutil
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger("gateway.keryx_stream")

# Opt-in fallback tier: when set (KERYX_STREAM_FALLBACK_EDITS=1), a Matrix chat WITHOUT a live
# side-channel subscriber gets throttled protocol (m.replace) edit streaming instead of the
# buffer-only default. OFF by default: on clients that don't collapse m.replace fallbacks the
# edit stream renders as duplicate bubbles, and heavy edit-streaming is exactly the homeserver
# bloat this side-channel exists to avoid.
FALLBACK_EDITS = os.getenv("KERYX_STREAM_FALLBACK_EDITS", "").strip().lower() in {"1", "true", "yes", "on"}

# Per-subscriber event buffer. Generous relative to token rate x ping interval; overflow drops
# oldest-first semantics are approximated by dropping the incoming event for that subscriber.
_QUEUE_MAX = 2048


class _Subscription:
    __slots__ = ("queue", "loop")

    def __init__(self, queue: "asyncio.Queue[Tuple[str, Optional[str]]]", loop: asyncio.AbstractEventLoop):
        self.queue = queue
        self.loop = loop


class KeryxStreamHub:
    """In-process pub/sub keyed by (platform, chat_id)."""

    def __init__(self) -> None:
        self._subs: Dict[Tuple[str, str], List[_Subscription]] = {}
        self._lock = threading.Lock()

    @staticmethod
    def _key(platform: str, chat_id: str) -> Tuple[str, str]:
        return (str(platform).strip().lower(), str(chat_id).strip())

    def subscribe(self, platform: str, chat_id: str) -> _Subscription:
        sub = _Subscription(asyncio.Queue(maxsize=_QUEUE_MAX), asyncio.get_running_loop())
        key = self._key(platform, chat_id)
        with self._lock:
            self._subs.setdefault(key, []).append(sub)
        logger.info("keryx subscriber attached: %s", key)
        return sub

    def unsubscribe(self, platform: str, chat_id: str, sub: _Subscription) -> None:
        key = self._key(platform, chat_id)
        with self._lock:
            lst = self._subs.get(key)
            if lst and sub in lst:
                lst.remove(sub)
                if not lst:
                    del self._subs[key]
        logger.info("keryx subscriber detached: %s", key)

    def has_subscribers(self, platform: str, chat_id: str) -> bool:
        with self._lock:
            return bool(self._subs.get(self._key(platform, chat_id)))

    def publish_threadsafe(self, platform: str, chat_id: str, event: str, text: Optional[str]) -> None:
        """Mirror one stream event to every subscriber. Never raises, never blocks."""
        key = self._key(platform, chat_id)
        with self._lock:
            subs = list(self._subs.get(key, ()))
        for sub in subs:
            try:
                sub.loop.call_soon_threadsafe(self._offer, sub.queue, (event, text))
            except Exception:
                # Subscriber's loop is gone — it will be pruned when its handler exits.
                pass

    @staticmethod
    def _offer(queue: "asyncio.Queue[Tuple[str, Optional[str]]]", item: Tuple[str, Optional[str]]) -> None:
        try:
            queue.put_nowait(item)
        except asyncio.QueueFull:
            logger.debug("keryx subscriber queue full; dropping %s", item[0])


hub = KeryxStreamHub()


def drain_coalesced(
    queue: "asyncio.Queue[Tuple[str, Optional[str]]]",
    first: Tuple[str, Optional[str]],
) -> Tuple[List[Tuple[str, Optional[str]]], bool]:
    """Merge a burst of queued token deltas into as few frames as possible.

    Takes the item already pulled from [queue] ([first]) plus everything currently queued
    (non-blocking) and returns ``(frames, stop)``: an ordered list of ``(event, text)`` frames
    ready to write, and whether a ``stop`` was seen (the caller then closes the channel).

    Consecutive ``delta`` events are concatenated into a single ``delta`` frame; non-delta
    boundaries (``segment``/``stop``) flush the accumulator and pass through in order. This is
    byte-exact — delta concatenation is associative — and bounds the write rate to how fast the
    consumer drains, so a fast brain (DFlash turbo runs ~150 tok/s) can't back the per-subscriber
    queue up to _QUEUE_MAX and lose tokens to overflow. A dropped token would break the client's
    StreamHandoff (accumulated stream no longer byte-matches the committed message → duplicate
    bubble / stuck overlay), which is exactly what this coalescing prevents.
    """
    pending: List[Tuple[str, Optional[str]]] = [first]
    while True:
        try:
            pending.append(queue.get_nowait())
        except asyncio.QueueEmpty:
            break

    # Both token-ish event types coalesce (concatenation is associative for each); crossing from
    # one type to the other flushes, so ordering between reasoning and answer text is preserved.
    frames: List[Tuple[str, Optional[str]]] = []
    buf: List[str] = []
    buf_event: Optional[str] = None
    stop = False

    def _flush() -> None:
        nonlocal buf, buf_event
        if buf:
            frames.append((buf_event or "delta", "".join(buf)))
            buf = []
            buf_event = None

    for event, text in pending:
        if event in ("delta", "reasoning"):
            if buf_event not in (None, event):
                _flush()
            buf_event = event
            buf.append(text or "")
            continue
        _flush()
        frames.append((event, text))
        if event == "stop":
            stop = True
            break
    _flush()
    return frames, stop


def _platform_of(adapter: Any) -> str:
    """Stable lowercase platform key for an adapter ("matrix", "telegram", …)."""
    try:
        return str(adapter.platform.value).lower()
    except Exception:
        return str(getattr(adapter, "name", "")).lower()


def publish_delta(adapter: Any, chat_id: Any, text: str) -> None:
    """Called from GatewayStreamConsumer.on_delta (agent worker thread)."""
    hub.publish_threadsafe(_platform_of(adapter), str(chat_id), "delta", text)


def publish_segment(adapter: Any, chat_id: Any) -> None:
    hub.publish_threadsafe(_platform_of(adapter), str(chat_id), "segment", None)


# Live agent per in-flight turn, so publish_stop can read token usage at the finish line.
# Weakrefs on purpose: the stash must never keep a dead agent (and its context) alive, and a
# missing/collected entry just means "no usage frame this turn" — the app's ring stays put.
_TURN_AGENTS: Dict[Tuple[str, str], Any] = {}


def _publish_usage(platform: str, chat_id: str) -> None:
    """Emit one ``event: usage`` frame with the turn's context occupancy.

    ``last_prompt_tokens`` (the final API call's prompt size) IS the model's current context
    occupancy — unlike the session_* counters, which are cumulative across calls. Must be
    published BEFORE the stop frame: the side-channel subscription is transient and the reader
    hangs up at stop, so anything after it is never delivered.
    """
    try:
        ref = _TURN_AGENTS.pop((platform, chat_id), None)
        agent = ref() if ref is not None else None
        comp = getattr(agent, "context_compressor", None) if agent is not None else None
        used = int(getattr(comp, "last_prompt_tokens", 0) or 0)
        cmax = int(getattr(comp, "context_length", 0) or 0)
        if used <= 0 or cmax <= 0:
            return
        payload = json.dumps(
            {"used": used, "max": cmax, "model": str(getattr(agent, "model", "") or "")}
        )
        hub.publish_threadsafe(platform, chat_id, "usage", payload)
    except Exception:
        logger.debug("usage publish failed", exc_info=True)


def publish_stop(adapter: Any, chat_id: Any, final_text: Optional[str] = None) -> None:
    platform = _platform_of(adapter)
    _publish_usage(platform, str(chat_id))
    hub.publish_threadsafe(platform, str(chat_id), "stop", final_text)


# --- tool & subagent theater (Keryx 2.4) ---------------------------------------------------
#
# The agent core has fired ``tool_progress_callback`` all along; the Keryx side-channel simply
# never listened, which is why a Matrix turn showed a spinner and nothing else until the whole
# answer committed. One SSE event type (``tool``) carries the whole vocabulary as JSON, so the
# frame alphabet stays small and an older app ignores it (unknown events are skipped client-side).
#
#   {"phase":"start",    "name": "terminal", "preview": "ls -la"}
#   {"phase":"end",      "name": "terminal", "ok": true, "ms": 412}   ("result" on failure only)
#   {"phase":"diff",     "name": "patch", "added": 40, "removed": 3, "diff": "…", "truncated": false}
#   {"phase":"sub", "kind":"start|tool|complete|thinking|progress|spawn_requested",
#    "child":"…", "name":…, "preview":…, plus the identity block and, on completion, the rollup}
#
# Starts and ends are correlated by ORDER, not by id: the executor runs a turn's tool calls
# sequentially, and ``tool.completed`` doesn't carry the call id anyway. The name rides along so
# the client can prefer the newest open entry with a matching name.

_TOOL_PREVIEW_MAX = 240
# Failures only (see below), and clipped: the full result lands in the committed Matrix message
# a moment later, and an unbounded one would push megabytes through a phone's SSE socket.
_TOOL_RESULT_MAX = 400


def _clip(value: Any, limit: int) -> str:
    text = "" if value is None else str(value)
    text = text.replace("\r\n", "\n").strip()
    return text if len(text) <= limit else text[: limit - 1] + "…"


# Every ``subagent.*`` event carries the same identity block (goal, task index/count, model,
# child session) and adds what only it knows — the lifecycle ones add activity, the completion
# adds the rollup. Relayed by ``tools/delegate_tool.py``'s ``_identity_kwargs``. This is the
# same set Talaria's ``Delegation`` model consumes, so the two clients show a delegation the
# same way rather than each inventing its own half-view.
_SUB_STR_FIELDS = ("goal", "model", "status")
_SUB_INT_FIELDS = (
    "task_index", "task_count", "depth", "tool_count",
    "input_tokens", "output_tokens", "reasoning_tokens", "api_calls",
)


def _subagent_frame(event_type: str, name: Any, preview: Any, kw: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    kind = event_type.split(".", 1)[1]
    # The child's streamed assistant text, relayed per delta. A watch window can drink from
    # that; a phone on a transient SSE socket cannot, and Talaria drops it for the same reason
    # — the wing's activity line is fed by thinking / tool / progress instead.
    if kind == "text":
        return None
    frame: Dict[str, Any] = {
        "phase": "sub",
        "kind": kind,
        # subagent_id is optional on the wire (older emitters omit it); the task index is the
        # stable fallback within one dispatch.
        "child": str(kw.get("subagent_id") or "").strip() or f"task-{kw.get('task_index', 0)}",
        "name": str(name or ""),
        "preview": _clip(preview, _TOOL_PREVIEW_MAX),
    }
    session = kw.get("child_session_id")
    if session:
        # The child's own stored session — what "open this subagent" needs. A delegated child
        # is not a live gateway session and its relay is never persisted, so without this id
        # a landed wing is a dead end: you can see that it worked and never what it did.
        frame["session"] = str(session)
    for key in _SUB_STR_FIELDS:
        value = kw.get(key)
        if value:
            frame[key] = _clip(value, 200)
    for key in _SUB_INT_FIELDS:
        value = kw.get(key)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            frame[key] = int(value)
    duration = kw.get("duration_seconds")
    if isinstance(duration, (int, float)) and not isinstance(duration, bool):
        frame["duration_seconds"] = float(duration)
    summary = kw.get("summary")
    if summary:
        # Longer than a tool preview on purpose: for a background fan-out reporting back, the
        # summary IS the work — it is the only place the child's result exists on this screen.
        frame["summary"] = _clip(summary, 600)
    # Only the counts: the wing renders "3 written", never the paths, and a 40-path list per
    # completion is a lot of socket for a number.
    for key in ("files_read", "files_written"):
        value = kw.get(key)
        if isinstance(value, (list, tuple)):
            frame[key + "_n"] = len(value)
    return frame


# --- inline edit diffs -----------------------------------------------------------------------
#
# `tool.completed` carries the tool's RESULT, which for an edit tool is a success envelope, not
# a diff — the diff only exists by comparing the file against what it was before the call. The
# agent's own display layer already does exactly that (`capture_local_edit_snapshot` at start,
# `render_edit_diff_with_delta` at completion), so this borrows it rather than re-deriving it,
# and the app therefore shows the same diff the CLI would have printed.
#
# Snapshots are keyed by tool_call_id and popped on completion; the cap is a leak-stop for the
# case where a call starts and never completes (interrupt, crash mid-tool).
_EDIT_SNAPSHOTS: Dict[str, Any] = {}
_EDIT_SNAPSHOT_MAX = 32

# The panel is a glimpse, like every other payload here. Stats are counted from the WHOLE diff
# before clipping, so "+40 −3" stays true even when the panel below it is cut.
_DIFF_MAX = 1800


def _capture_edit_snapshot(tool_call_id: str, name: str, args: Any) -> None:
    try:
        from agent.display import capture_local_edit_snapshot

        snapshot = capture_local_edit_snapshot(name, args if isinstance(args, dict) else {})
        if snapshot is None:
            return
        if len(_EDIT_SNAPSHOTS) >= _EDIT_SNAPSHOT_MAX:
            _EDIT_SNAPSHOTS.clear()
        _EDIT_SNAPSHOTS[str(tool_call_id)] = snapshot
    except Exception:
        logger.debug("edit snapshot failed", exc_info=True)


def _edit_diff(tool_call_id: str, name: str, args: Any, result: Any) -> Optional[str]:
    snapshot = _EDIT_SNAPSHOTS.pop(str(tool_call_id), None)
    try:
        from agent.display import render_edit_diff_with_delta

        rendered: List[str] = []
        ok = render_edit_diff_with_delta(
            name,
            result if isinstance(result, str) else json.dumps(result, default=str),
            function_args=args if isinstance(args, dict) else None,
            snapshot=snapshot,
            print_fn=rendered.append,
        )
        return "\n".join(rendered) if ok and rendered else None
    except Exception:
        logger.debug("edit diff render failed", exc_info=True)
        return None


def _diff_counts(diff: str) -> Tuple[int, int]:
    """(+added, -removed), counted the way the app's panel classifies lines.

    ⚠️ The rendered lines are ANSI-coloured (``ESC[38;2;…m+line ESC[0m``), so a naive
    ``startswith("+")`` counts nothing at all. Strip first, then classify — and a ``+++``/``---``
    file header is not a changed line.
    """
    add = rem = 0
    for line in diff.splitlines():
        bare = _ANSI.sub("", line).lstrip()
        if bare.startswith("+++") or bare.startswith("---"):
            continue
        if bare.startswith("+"):
            add += 1
        elif bare.startswith("-"):
            rem += 1
    return add, rem


_ANSI = re.compile(r"\x1b\[[0-9;]*m")


def _attach_tool_callbacks(agent: Any, platform: str, chat_id: str) -> None:
    """Mirror tool + subagent lifecycle onto the side-channel, without stealing the hook.

    ``gateway/run.py`` assigns ``tool_progress_callback`` immediately before calling us (it may
    be a live-status/log-mode consumer, or None), so this CHAINS: the previous callback still
    runs, and its exceptions are its own. It also runs per turn on a *cached* agent, so the
    wrapper is tagged and unwrapped before re-wrapping — otherwise every turn would nest one
    more layer for the life of the process.
    """
    prev = getattr(agent, "tool_progress_callback", None)
    prev = getattr(prev, "_keryx_inner", prev)  # never wrap our own wrapper

    def _emit(payload: Dict[str, Any]) -> None:
        try:
            hub.publish_threadsafe(platform, chat_id, "tool", json.dumps(payload))
        except Exception:
            pass

    def _mirror(event_type: Any, name: Any = None, preview: Any = None, args: Any = None, **kw: Any) -> None:
        try:
            et = str(event_type or "")
            if et == "tool.started":
                _emit({"phase": "start", "name": str(name or "tool"),
                       "preview": _clip(preview, _TOOL_PREVIEW_MAX)})
            elif et == "tool.completed":
                ok = not bool(kw.get("is_error"))
                frame = {"phase": "end", "name": str(name or "tool"), "ok": ok,
                         "ms": int(float(kw.get("duration") or 0.0) * 1000)}
                # Only a FAILURE carries its result. A success's output is the answer's raw
                # material — it lands in the committed message a moment later, rendered
                # properly, and pushing a few hundred bytes of escaped JSON per call to a phone
                # to show nothing is waste. A failure is the one case where the mid-turn glimpse
                # is the whole point.
                if not ok:
                    frame["result"] = _clip(kw.get("result"), _TOOL_RESULT_MAX)
                _emit(frame)
            elif et.startswith("subagent."):
                frame = _subagent_frame(et, name, preview, kw)
                if frame is not None:
                    _emit(frame)
        except Exception:
            logger.debug("keryx tool mirror failed", exc_info=True)
        # The mirror is a passenger: whatever the gateway wired stays authoritative and runs
        # even if our half raised.
        if prev is not None:
            prev(event_type, name, preview, args, **kw)

    _mirror._keryx_inner = prev  # type: ignore[attr-defined]
    agent.tool_progress_callback = _mirror

    # The id-bearing pair, for edit diffs only. Matrix leaves both unset (run.py wires them for
    # Discord voice acks and Slack task cards), but chain anyway — this module has no business
    # deciding that the platform it happens to be running under doesn't need its own callback.
    prev_start = getattr(agent, "tool_start_callback", None)
    prev_start = getattr(prev_start, "_keryx_inner", prev_start)
    prev_complete = getattr(agent, "tool_complete_callback", None)
    prev_complete = getattr(prev_complete, "_keryx_inner", prev_complete)

    def _on_start(tool_call_id: Any, name: Any, args: Any) -> None:
        _capture_edit_snapshot(tool_call_id, str(name or ""), args)
        if prev_start is not None:
            prev_start(tool_call_id, name, args)

    def _on_complete(tool_call_id: Any, name: Any, args: Any, result: Any) -> None:
        try:
            diff = _edit_diff(tool_call_id, str(name or ""), args, result)
            if diff:
                added, removed = _diff_counts(diff)
                # Its own frame, not a field on "end": `tool_progress_callback("tool.completed")`
                # fires BEFORE this one, so the end frame is already on the wire by now. The app
                # attaches it to the matching closed row.
                _emit({
                    "phase": "diff",
                    "name": str(name or "tool"),
                    "added": added,
                    "removed": removed,
                    "diff": _clip(diff, _DIFF_MAX),
                    "truncated": len(diff) > _DIFF_MAX,
                })
        except Exception:
            logger.debug("keryx diff mirror failed", exc_info=True)
        if prev_complete is not None:
            prev_complete(tool_call_id, name, args, result)

    _on_start._keryx_inner = prev_start  # type: ignore[attr-defined]
    _on_complete._keryx_inner = prev_complete  # type: ignore[attr-defined]
    agent.tool_start_callback = _on_start
    agent.tool_complete_callback = _on_complete


def attach_reasoning_callback(agent: Any, source: Any) -> None:
    """Register this turn's live mirrors on the agent — reasoning, and via
    [_attach_tool_callbacks] the tool/subagent theater.

    (Name kept for the install.py/reapply hook in gateway/run.py, which calls it as the single
    per-turn attach point.)

    The agent core already has a ``reasoning_callback`` hook that fires with every structured
    reasoning delta (``delta.reasoning_content`` / inline think-block text) — the gateway just
    never registered one, which is why Keryx only ever saw reasoning folded into the final
    committed message. Wired from gateway/run.py right after ``stream_delta_callback`` (see
    install.py), so it's refreshed per-message exactly like the other per-turn callbacks on the
    cached agent. Publishes ``event: reasoning`` frames to any live subscriber; with nobody
    attached, publish_threadsafe is a dict lookup and a no-op. Fires on the agent's worker
    thread — same threading contract as publish_delta. Never raises.
    """
    try:
        platform = str(getattr(source.platform, "value", source.platform)).lower()
        chat_id = str(source.chat_id)

        def _mirror_reasoning(text: str) -> None:
            try:
                hub.publish_threadsafe(platform, chat_id, "reasoning", text)
            except Exception:
                pass

        agent.reasoning_callback = _mirror_reasoning
        _attach_tool_callbacks(agent, platform, chat_id)
        # Same per-turn refresh cadence as the callback itself: the finish-line usage frame
        # (see _publish_usage) reads this turn's agent, weakly held.
        import weakref

        _TURN_AGENTS[(platform, chat_id)] = weakref.ref(agent)
    except Exception:
        logger.debug("attach_reasoning_callback failed", exc_info=True)


def suppress_protocol_edits(adapter: Any, chat_id: Any, default_buffer_only: bool) -> bool:
    """Decide whether the stream consumer should skip interval/threshold homeserver edits.

    Live Keryx subscriber → True (the side-channel carries tokens; commit only the final).
    No subscriber on Matrix with FALLBACK_EDITS → False (throttled m.replace fallback tier).
    Anything else → whatever the gateway decided ([default_buffer_only]).
    """
    platform = _platform_of(adapter)
    if hub.has_subscribers(platform, str(chat_id)):
        return True
    if default_buffer_only and FALLBACK_EDITS and platform == "matrix":
        return False
    return default_buffer_only


def _is_mistral_native(model: str) -> bool:
    """True for models served via vLLM's ``--tokenizer-mode mistral``.

    Those tokenizers hard-reject any request carrying ``chat_template`` or
    ``chat_template_kwargs`` with HTTP 400 ("chat_template is not supported for
    Mistral tokenizers"), so the enable_thinking switch must never be sent to them.
    Same name heuristic as the graphiti memory plugin's thinking client.
    """
    normalized = (model or "").strip().lower()
    return normalized.startswith("mistral") or "/mistral" in normalized


def apply_thinking_kwargs(agent) -> None:
    """Map Hermes' reasoning_config onto the local brain's thinking dial.

    Called from agent_init after ``_merge_custom_provider_extra_body`` (see install.py).
    Local OpenAI-compatible brains (provider ``custom``/``custom:*``) don't understand the
    OpenRouter-style ``extra_body.reasoning`` dial — thinking is a chat-template switch. Some
    local chat templates additionally force-open the thought channel when enable_thinking is set
    (for tunes that never open it voluntarily), so with this mapping ``/reasoning none`` ⇄ any
    effort level becomes a real on/off for local-brain reasoning. Never raises.

    Mistral-native exception: vLLM's ``--tokenizer-mode mistral`` rejects requests that
    carry ``chat_template_kwargs`` outright (HTTP 400), and its thinking dial is the
    standard top-level ``reasoning_effort`` param (only ``none``/``high`` accepted) —
    so Mistral-named models get that mapping instead.
    """
    try:
        # Kill switch for setups whose "custom" endpoint rejects unknown request fields
        # ("custom" can point anywhere): KERYX_THINKING_KWARGS=off in ~/.hermes/.env.
        if os.getenv("KERYX_THINKING_KWARGS", "").strip().lower() in {"0", "off", "false", "no"}:
            return
        provider = str(getattr(agent, "provider", "") or "").strip().lower()
        if provider != "custom" and not provider.startswith("custom:"):
            return
        rc = getattr(agent, "reasoning_config", None)
        # Only act when reasoning is explicitly configured (agent.reasoning_effort in
        # config.yaml, or a /reasoning override). rc is None on stock installs — inject
        # nothing, change nothing.
        if not isinstance(rc, dict):
            return
        enabled = rc.get("enabled") is not False
        overrides = dict(getattr(agent, "request_overrides", {}) or {})
        if _is_mistral_native(str(getattr(agent, "model", "") or "")):
            extra = dict(overrides.get("extra_body") or {})
            extra.pop("chat_template_kwargs", None)  # scrub any stale injection
            if extra:
                overrides["extra_body"] = extra
            else:
                overrides.pop("extra_body", None)
            overrides["reasoning_effort"] = "high" if enabled else "none"
            agent.request_overrides = overrides
            return
        extra = dict(overrides.get("extra_body") or {})
        ctk = dict(extra.get("chat_template_kwargs") or {})
        ctk["enable_thinking"] = enabled
        extra["chat_template_kwargs"] = ctk
        overrides["extra_body"] = extra
        agent.request_overrides = overrides
    except Exception:
        logger.debug("apply_thinking_kwargs failed", exc_info=True)


def _reasoning_capabilities() -> Dict[str, Any]:
    """Describe the active brain's reasoning dial for the Keryx client.

    Local custom providers are a binary switch (enable_thinking via chat template) — the app
    should render Off/On. Cloud providers accept the full effort scale. Reads config.yaml
    fresh on every call so a /model or /reasoning --global change is reflected immediately.
    """
    model = ""
    provider = ""
    effort = "medium"
    show = True
    room_profiles: Dict[str, str] = {}
    try:
        import yaml
        from pathlib import Path

        cfg = yaml.safe_load((Path.home() / ".hermes" / "config.yaml").read_text()) or {}
        model_cfg = cfg.get("model") or {}
        provider = str(model_cfg.get("provider", "") or "").strip().lower()
        model = str(model_cfg.get("model") or model_cfg.get("name") or "").strip()
        base = str(model_cfg.get("base_url", "") or "").strip()
        if (provider == "custom" or provider.startswith("custom:")) and base:
            # Brain hot-swaps (Spire systemd templates) change what's served without touching
            # config.yaml — ask the live endpoint what it actually is.
            try:
                import urllib.request as _rq

                with _rq.urlopen(base.rstrip("/") + "/models", timeout=2) as resp:
                    data = json.loads(resp.read().decode())
                served = [m.get("id", "") for m in data.get("data", []) if isinstance(m, dict)]
                if served and served[0]:
                    model = served[0]
            except Exception:
                pass
        if not model:
            for entry in (cfg.get("providers") or {}).values():
                if isinstance(entry, dict) and str(entry.get("base_url", "")).strip() == base:
                    model = str(entry.get("model") or entry.get("name") or "").strip()
                    if model:
                        break
        agent_cfg = cfg.get("agent") or {}
        # The global effort lives under model: in current configs (agent: is the legacy spot,
        # and the subagents block's '' must never win) — model wins, then agent, then medium.
        effort = str(
            model_cfg.get("reasoning_effort")
            or agent_cfg.get("reasoning_effort")
            or "medium"
        ).strip().lower()
        display = ((cfg.get("display") or {}).get("platforms") or {}).get("matrix") or {}
        show = bool(display.get("show_reasoning", True))
        # Which agent profile answers in which Matrix room (the routing-only multiplex map).
        # Keryx shows this as a profile chip next to the room name.
        rp = ((cfg.get("platforms") or {}).get("matrix") or {}).get("room_profile_map") or {}
        if isinstance(rp, dict):
            room_profiles = {str(k): str(v) for k, v in rp.items() if k and v}
    except Exception:
        logger.debug("capabilities config read failed", exc_info=True)

    local = provider == "custom" or provider.startswith("custom:")
    if local and _is_mistral_native(model):
        # Mistral-native tokenizers accept only none/high on reasoning_effort — for them a
        # binary switch is the honest declaration.
        reasoning = {
            "mode": "binary",
            "levels": ["none", "high"],
            "labels": {"none": "Off", "high": "On"},
            "current": "none" if effort == "none" else "high",
        }
    elif local:
        # The local serving stack (patched qwen-family templates) validates effort levels —
        # operator-confirmed on-device 2026-08-19: the accepted set is low/medium/xhigh (plus
        # none for thinking-off). Do NOT collapse this to a binary switch: the levels are real
        # on this stack, and the earlier binary declaration was the bug, not the ladder.
        reasoning = {
            "mode": "effort",
            "levels": ["none", "low", "medium", "xhigh"],
            "labels": {"none": "Off"},
            "current": effort,
        }
    else:
        reasoning = {
            "mode": "effort",
            "levels": ["none", "minimal", "low", "medium", "high", "xhigh"],
            "labels": {},
            "current": effort,
        }
    return {
        "model": model,
        "provider": provider,
        "reasoning": reasoning,
        "show_reasoning": show,
        "room_profiles": room_profiles,
    }


_FENCE_RUN = re.compile(r"`{3,}")


def _neutralize_fences(text: str) -> str:
    """Make any ```-or-longer backtick run in reasoning inert as a Markdown fence.

    The reasoning is wrapped in a ``` code block below. If the reasoning ITSELF
    quotes fenced code — which a thinking brain that reasons about code does on
    nearly every coding turn — an inner ``` closes our fence early. The Keryx
    client's reasoning-extraction regex then truncates at that inner fence, leaking
    the real answer into a copy-paste code block AND breaking the stream/commit
    byte-match so the live overlay never hands off to the committed message
    (duplicate bubble: overlay = answer without reasoning, commit = mangled block).
    Weaving a zero-width space between the backticks keeps them visually intact in
    the reasoning canvas while breaking the 3-in-a-row run that forms a fence.
    """
    return _FENCE_RUN.sub(lambda m: "​".join(m.group(0)), text)


async def prepend_reasoning_to_streamed(gateway, source, response, sc) -> bool:
    """Fold the 💭 reasoning block into an already-streamed final message.

    With streaming delivery on, the stream consumer commits the final message itself and the
    gateway suppresses the normal send (``already_sent``) — but the normal send is the ONLY
    path that prepends the 💭 reasoning block, so enabling streaming silently killed reasoning
    display for every model at once (live-debugged against a local vLLM brain: it delivered 998
    chars of ``delta.reasoning``; the committed Matrix message had none). Called from the
    suppression branch (see install.py); edits the streamed message in place, mirroring the
    plugin-transform branch. Returns True when the edit was applied. Never raises.
    """
    try:
        reasoning = str(response.get("last_reasoning") or "").strip()
        final = str(response.get("final_response") or "")
        if not reasoning or not final.strip() or sc is None:
            return False
        message_id = getattr(sc, "message_id", None)
        adapter = getattr(sc, "adapter", None)
        if not message_id or adapter is None:
            return False

        from gateway.run import (
            _load_gateway_config,
            _platform_config_key,
            _resolve_gateway_display_bool,
        )

        cfg = _load_gateway_config()
        platform_key = _platform_config_key(source.platform)
        show = _resolve_gateway_display_bool(
            cfg,
            platform_key,
            "show_reasoning",
            default=bool(getattr(gateway, "_show_reasoning", False)),
            platform=source.platform,
        )
        if not show:
            return False

        # Same 15-line collapse + per-platform style as the normal-send prepend.
        lines = reasoning.splitlines()
        if len(lines) > 15:
            display = "\n".join(lines[:15]) + f"\n_... ({len(lines) - 15} more lines)_"
        else:
            display = reasoning
        try:
            from gateway.display_config import resolve_display_setting

            style = resolve_display_setting(cfg, platform_key, "reasoning_style", "code")
        except Exception:
            style = "code"
        if style == "subtext":
            quoted = "\n".join(f"-# {ln}" if ln else "-#" for ln in display.splitlines())
            body = f"-# 💭 Reasoning\n{quoted}\n\n{final}"
        elif style == "blockquote":
            quoted = "\n".join(f"> {ln}" if ln else ">" for ln in display.splitlines())
            body = f"> 💭 **Reasoning:**\n{quoted}\n\n{final}"
        else:
            body = f"💭 **Reasoning:**\n```\n{_neutralize_fences(display)}\n```\n\n{final}"

        await adapter.edit_message(
            chat_id=source.chat_id,
            message_id=message_id,
            content=body,
            finalize=True,
        )
        logger.info("keryx: folded %d chars of reasoning into streamed message", len(reasoning))
        return True
    except Exception:
        logger.debug("prepend_reasoning_to_streamed failed", exc_info=True)
        return False


def _gateway_commands() -> List[Dict[str, Any]]:
    """The slash commands actually available on THIS gateway, from hermes' own
    command registry (single source of truth) plus any plugin-registered
    commands — so a client's "/" autocomplete reflects the installed system
    instead of a hardcoded guess."""
    out: List[Dict[str, Any]] = []
    try:
        from hermes_cli.commands import COMMAND_REGISTRY

        for cmd in COMMAND_REGISTRY:
            if cmd.cli_only and not cmd.gateway_config_gate:
                continue
            if cmd.name == "start":  # platform start-ping ack, not a user command
                continue
            out.append({
                "cmd": f"/{cmd.name}",
                "description": cmd.description,
                "category": cmd.category,
                "args_hint": cmd.args_hint or "",
                "aliases": [f"/{a}" for a in cmd.aliases],
            })
    except Exception:
        logger.debug("keryx: command registry unavailable", exc_info=True)
    try:
        from hermes_cli.plugins import get_plugin_commands

        # name → {handler, description, plugin}
        for name, meta in (get_plugin_commands() or {}).items():
            slug = f"/{str(name).lstrip('/')}"
            if any(c["cmd"] == slug for c in out):
                continue
            desc = meta.get("description", "") if isinstance(meta, dict) else str(meta)
            out.append({
                "cmd": slug,
                "description": str(desc or "Plugin command"),
                "category": "Plugin",
                "args_hint": "",
                "aliases": [],
            })
    except Exception:
        logger.debug("keryx: plugin commands unavailable", exc_info=True)
    return out


def make_commands_handler(check_auth):
    """aiohttp handler for ``GET /keryx/commands`` (wired in api_server.py)."""
    from aiohttp import web

    async def handle_keryx_commands(request: "web.Request") -> "web.Response":
        auth_err = check_auth(request)
        if auth_err is not None:
            return auth_err
        cmds = await asyncio.to_thread(_gateway_commands)
        return web.json_response({"commands": cmds})

    return handle_keryx_commands


def make_capabilities_handler(check_auth):
    """aiohttp handler for ``GET /keryx/capabilities`` (wired in api_server.py)."""
    from aiohttp import web

    async def handle_keryx_capabilities(request: "web.Request") -> "web.Response":
        auth_err = check_auth(request)
        if auth_err is not None:
            return auth_err
        # Config read + live-model probe both block — keep them off the event loop.
        caps = await asyncio.to_thread(_reasoning_capabilities)
        return web.json_response(caps)

    return handle_keryx_capabilities


def make_stream_handler(check_auth):
    """Build the aiohttp handler for ``GET /keryx/stream`` (wired in api_server.py).

    [check_auth] is ApiServerAdapter._check_auth — same Bearer key as every other route.
    """
    from aiohttp import web

    async def handle_keryx_stream(request: "web.Request") -> "web.StreamResponse":
        auth_err = check_auth(request)
        if auth_err is not None:
            return auth_err
        platform = request.query.get("platform", "matrix")
        chat_id = request.query.get("chat_id", "").strip()
        if not chat_id:
            return web.json_response({"error": {"message": "chat_id is required"}}, status=400)

        resp = web.StreamResponse(
            status=200,
            headers={
                "Content-Type": "text/event-stream",
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "X-Accel-Buffering": "no",
            },
        )
        await resp.prepare(request)
        sub = hub.subscribe(platform, chat_id)
        try:
            while True:
                try:
                    first = await asyncio.wait_for(sub.queue.get(), timeout=20.0)
                except asyncio.TimeoutError:
                    # Keepalive: keeps NATs open and lets a dead client surface as a write error.
                    await resp.write(b"event: ping\ndata: {}\n\n")
                    continue

                # Coalesce whatever else is queued into as few frames as possible so a fast brain
                # can't overflow the bounded queue and drop tokens (see drain_coalesced).
                frames, stop = drain_coalesced(sub.queue, first)
                for event, text in frames:
                    payload = json.dumps({"text": text} if text is not None else {})
                    await resp.write(f"event: {event}\ndata: {payload}\n\n".encode("utf-8"))
                if stop:
                    break  # transient channel: one turn per subscription
        except (ConnectionResetError, asyncio.CancelledError):
            pass
        finally:
            hub.unsubscribe(platform, chat_id, sub)
        try:
            await resp.write_eof()
        except Exception:
            pass
        return resp

    return handle_keryx_stream


# ---------------------------------------------------------------------------
# Kanban board (Keryx 1.6 "Missions") — read/create/comment over the agent's
# task board. State TRANSITIONS (complete/block/claim) stay agent-side on
# purpose: the dispatcher owns those; the phone reads, creates, and comments.
#
# The pure helpers below take an open sqlite connection and return plain
# dicts, so they unit-test against a temp board without aiohttp or a gateway.
# All writes go through hermes_cli.kanban_db — the same code path the agent's
# kanban_* tools use (WAL, schema migrations, event log, validation).
# ---------------------------------------------------------------------------

# Comment/created_by identity for phone-originated writes. Fixed server-side
# (not caller-supplied) for the same reason kanban_comment derives its author
# from runtime identity: a forged author like "hermes-system" would read as a
# system directive in future worker context.
KANBAN_ACTOR = "keryx"

# Fields safe + useful for the app. Excludes claim locks, workspace paths,
# idempotency keys — dispatcher internals the phone has no business rendering.
_KANBAN_SUMMARY_FIELDS = (
    "id", "title", "assignee", "status", "priority", "created_by",
    "created_at", "started_at", "completed_at", "consecutive_failures",
)
_KANBAN_DETAIL_FIELDS = _KANBAN_SUMMARY_FIELDS + (
    "body", "result", "last_failure_error", "goal_mode", "max_runtime_seconds",
    "last_heartbeat_at", "workspace_kind", "project_id",
    # v0.20 per-task overrides — settable from the phone via /task/{id}/settings.
    "model_override", "provider_override", "reasoning_effort",
)


def _kanban_connect(board: Optional[str] = None):
    """Same lazy import + board resolution chain as tools/kanban_tools.py."""
    from hermes_cli import kanban_db as kb

    return kb, kb.connect(board=board)


def _task_dict(task: Any, fields: Tuple[str, ...]) -> Dict[str, Any]:
    d = {f: getattr(task, f, None) for f in fields}
    # 200-char excerpt is enough for a card; detail carries the full body.
    if "body" not in fields:
        body = getattr(task, "body", None) or ""
        d["body_excerpt"] = body[:200]
    return d


def kanban_board_snapshot(kb: Any, conn: Any) -> Dict[str, Any]:
    """Tasks grouped by raw status. Column layout is the client's decision —
    grouping by status here means a future status never breaks old apps."""
    tasks = kb.list_tasks(conn, include_archived=False, order_by="priority")
    by_status: Dict[str, list] = {}
    for t in tasks:
        by_status.setdefault(t.status, []).append(_task_dict(t, _KANBAN_SUMMARY_FIELDS))
    return {
        "board": kb.get_current_board(),
        "tasks": by_status,
        "counts": {s: len(v) for s, v in by_status.items()},
    }


def kanban_task_detail(kb: Any, conn: Any, task_id: str) -> Optional[Dict[str, Any]]:
    task = kb.get_task(conn, task_id)
    if task is None:
        return None
    return {
        "task": _task_dict(task, _KANBAN_DETAIL_FIELDS),
        "comments": [
            {"id": c.id, "author": c.author, "body": c.body, "created_at": c.created_at}
            for c in kb.list_comments(conn, task_id)
        ],
        "events": [
            {"id": e.id, "kind": e.kind, "payload": e.payload, "created_at": e.created_at}
            for e in kb.list_events(conn, task_id)[-50:]
        ],
    }


def kanban_create(kb: Any, conn: Any, payload: Dict[str, Any]) -> Dict[str, Any]:
    """Create a mission. Mirrors kanban_create tool semantics: assignee is
    required (the dispatcher only spawns assigned tasks); triage=True parks it
    spec-first instead of letting the dispatcher pick it up immediately."""
    title = str(payload.get("title") or "").strip()
    assignee = str(payload.get("assignee") or "").strip()
    if not title:
        raise ValueError("title is required")
    if not assignee:
        raise ValueError("assignee is required (which profile runs this mission)")
    task_id = kb.create_task(
        conn,
        title=title,
        body=payload.get("body"),
        assignee=assignee,
        priority=int(payload.get("priority") or 0),
        triage=bool(payload.get("triage", False)),
        goal_mode=bool(payload.get("goal_mode", False)),
        created_by=KANBAN_ACTOR,
    )
    task = kb.get_task(conn, task_id)
    return {"task_id": task_id, "status": task.status if task else None}


def kanban_comment(kb: Any, conn: Any, task_id: str, body: str) -> Dict[str, Any]:
    cid = kb.add_comment(conn, task_id, author=KANBAN_ACTOR, body=body)
    return {"task_id": task_id, "comment_id": cid}


def kanban_task_settings(kb: Any, conn: Any, task_id: str, payload: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Per-task model + thinking depth (the v0.20 kanban override fields), the
    phone's side of what the dashboard's PATCH does. Key-present semantics: a key
    in the body is applied (empty/null clears that override — kanban_db treats
    None as clear), an absent key is untouched. ``reasoning_effort: "none"`` is a
    real value (thinking OFF for this task), not a clear."""
    if kb.get_task(conn, task_id) is None:
        return None
    if "model" in payload:
        model = str(payload.get("model") or "").strip() or None
        provider = str(payload.get("provider") or "").strip() or None
        kb.set_model_override(conn, task_id, model=model, provider=provider)
    if "reasoning_effort" in payload:
        effort = str(payload.get("reasoning_effort") or "").strip() or None
        kb.set_reasoning_effort(conn, task_id, effort)
    task = kb.get_task(conn, task_id)
    return {
        "task_id": task_id,
        "model_override": getattr(task, "model_override", None),
        "provider_override": getattr(task, "provider_override", None),
        "reasoning_effort": getattr(task, "reasoning_effort", None),
    }


def kanban_events_since(conn: Any, since: int, limit: int = 200) -> Dict[str, Any]:
    """Incremental poll for the app's mission watcher. Cursor = task_events.id
    (AUTOINCREMENT); pass the returned cursor back as ?since= next time."""
    rows = conn.execute(
        "SELECT id, task_id, kind, payload, created_at FROM task_events "
        "WHERE id > ? ORDER BY id ASC LIMIT ?",
        (int(since), int(limit)),
    ).fetchall()
    events = []
    cursor = int(since)
    for r in rows:
        payload = r["payload"]
        if isinstance(payload, str):
            try:
                payload = json.loads(payload)
            except Exception:
                payload = {"raw": payload}
        events.append(
            {
                "id": r["id"], "task_id": r["task_id"], "kind": r["kind"],
                "payload": payload, "created_at": r["created_at"],
            }
        )
        cursor = int(r["id"])
    return {"events": events, "cursor": cursor}


# --- Notify subscriptions (Keryx 1.8 real-time mission alerts) -------------
# The gateway's kanban-notifier watcher tails task_events every ~5s and pushes
# terminal transitions (completed/blocked/...) as a NATIVE message to every
# (platform, chat_id, thread_id) row in kanban_notify_subs. These helpers just
# manage those rows — delivery is entirely the watcher's job, so a subscribed
# Matrix room gets a real push message and the app needs no new plumbing.
# The watcher deletes subs itself once a task is genuinely done/archived;
# clients must treat a vanished sub as "task ended", not an error.

# Columns the app renders. Pinned by name (schema-drift armor, 1.6 rule).
_SUB_FIELDS = ("task_id", "platform", "chat_id", "thread_id", "created_at")


def kanban_subs_list(kb: Any, conn: Any) -> Dict[str, Any]:
    return {
        "subs": [
            {f: row.get(f) for f in _SUB_FIELDS}
            for row in kb.list_notify_subs(conn)
        ]
    }


def kanban_subscribe(
    kb: Any, conn: Any, task_id: str, payload: Dict[str, Any]
) -> Optional[Dict[str, Any]]:
    """Subscribe a chat to a task's terminal events. None = unknown task."""
    chat_id = str(payload.get("chat_id") or "").strip()
    if not chat_id:
        raise ValueError("chat_id is required (which room receives the alert)")
    if kb.get_task(conn, task_id) is None:
        return None
    kb.add_notify_sub(
        conn,
        task_id=task_id,
        platform=str(payload.get("platform") or "matrix").strip(),
        chat_id=chat_id,
        thread_id=str(payload.get("thread_id") or "") or None,
        user_id=KANBAN_ACTOR,
        # None on purpose: an unowned sub is adopted by whichever notifier
        # profile runs the watcher, so alerts keep flowing after profile swaps.
        notifier_profile=None,
    )
    return {"task_id": task_id, "subscribed": True}


def kanban_unsubscribe(
    kb: Any, conn: Any, task_id: str, payload: Dict[str, Any]
) -> Dict[str, Any]:
    chat_id = str(payload.get("chat_id") or "").strip()
    if not chat_id:
        raise ValueError("chat_id is required")
    removed = kb.remove_notify_sub(
        conn,
        task_id=task_id,
        platform=str(payload.get("platform") or "matrix").strip(),
        chat_id=chat_id,
        thread_id=str(payload.get("thread_id") or "") or None,
    )
    return {"task_id": task_id, "subscribed": False, "removed": bool(removed)}


def _make_kanban_handler(check_auth, work):
    """Shared shell: auth → run [work] (sync sqlite) off the event loop →
    JSON. [work] gets (kb, conn, request-ish dict) and returns (status, body)."""
    from aiohttp import web

    async def handler(request: "web.Request") -> "web.Response":
        auth_err = check_auth(request)
        if auth_err is not None:
            return auth_err
        board = request.query.get("board") or None
        try:
            body = {}
            if request.method == "POST":
                try:
                    body = await request.json()
                except Exception:
                    return web.json_response(
                        {"error": {"message": "invalid JSON body"}}, status=400
                    )

            def _run():
                kb, conn = _kanban_connect(board=board)
                try:
                    return work(kb, conn, request, body)
                finally:
                    conn.close()

            status, payload = await asyncio.to_thread(_run)
            return web.json_response(payload, status=status)
        except ValueError as e:
            return web.json_response({"error": {"message": str(e)}}, status=400)
        except Exception:
            logger.exception("keryx kanban handler failed")
            return web.json_response(
                {"error": {"message": "kanban unavailable"}}, status=500
            )

    return handler


def _make_json_handler(check_auth, work):
    """Generic shell for non-kanban routes: auth → JSON body (POST/PUT) →
    run [work] (sync filesystem/sqlite) off the event loop → JSON response.
    [work] gets (request, body) and returns (status, payload)."""
    from aiohttp import web

    async def handler(request: "web.Request") -> "web.Response":
        auth_err = check_auth(request)
        if auth_err is not None:
            return auth_err
        try:
            body: Dict[str, Any] = {}
            if request.method in ("POST", "PUT"):
                try:
                    body = await request.json()
                except Exception:
                    return web.json_response(
                        {"error": {"message": "invalid JSON body"}}, status=400
                    )
                if not isinstance(body, dict):
                    return web.json_response(
                        {"error": {"message": "JSON object body required"}}, status=400
                    )
            status, payload = await asyncio.to_thread(work, request, body)
            return web.json_response(payload, status=status)
        except ValueError as e:
            return web.json_response({"error": {"message": str(e)}}, status=400)
        except Exception:
            logger.exception("keryx handler failed: %s", request.path)
            return web.json_response({"error": {"message": "unavailable"}}, status=500)

    return handler


# ---------------------------------------------------------------------------
# Skill Forge (Keryx 1.8) — read/write SKILL.md over the gateway's own skill
# machinery. Writes go through tools.skill_manager_tool._edit_skill /
# _create_skill so the phone gets the same frontmatter validation, atomic
# write, and security-scan-with-rollback the agent's skill_manage tool has.
# Skills found outside ~/.hermes/skills (skills.external_dirs) are read-only:
# _edit_skill would happily write there, so the refusal lives HERE.
# ---------------------------------------------------------------------------

# Skill names are directory basenames; anything path-shaped is hostile.
_SKILL_NAME_BAD = re.compile(r"[/\\]|\.\.")

# One-deep undo written next to SKILL.md before every edit; hidden from the
# app's file listing. rglob("SKILL.md") in the loader can't match it.
_SKILL_BAK = "SKILL.md.bak"


def _skill_manager():
    from tools import skill_manager_tool as sm

    return sm


def _is_under(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except (ValueError, OSError):
        return False


def _bust_skills_prompt_cache() -> None:
    """Drop the never-revalidating skills-prompt LRU so NEW sessions see the
    edit immediately. Running sessions keep their cached system prompt —
    that's per-session state, not ours to invalidate."""
    try:
        from agent.prompt_builder import clear_skills_system_prompt_cache

        clear_skills_system_prompt_cache()
    except Exception:
        logger.debug("skills prompt cache bust failed", exc_info=True)


_FRONTMATTER_NAME = re.compile(r"^name:\s*(.+?)\s*$", re.MULTILINE)


def _frontmatter_name(skill_md: Path) -> Optional[str]:
    try:
        head = skill_md.read_text(encoding="utf-8", errors="replace")[:2048]
    except OSError:
        return None
    if not head.startswith("---"):
        return None
    m = _FRONTMATTER_NAME.search(head.split("\n---", 1)[0])
    return m.group(1).strip().strip("\"'").lower() if m else None


def _find_skill_dir(name: str) -> Optional[Path]:
    """Directory-basename match first (canonical, what _edit_skill uses), then
    a frontmatter-name fallback: /v1/skills lists frontmatter display names,
    which may differ from the dir name (spaces, capitals). Callers get the
    canonical basename back via the response's "name" field."""
    sm = _skill_manager()
    found = sm._find_skill(name)
    if found:
        return found["path"]
    try:
        from agent.skill_utils import get_all_skills_dirs, is_excluded_skill_path
    except Exception:
        return None
    want = name.strip().lower()
    for skills_dir in get_all_skills_dirs():
        if not skills_dir.exists():
            continue
        for skill_md in skills_dir.rglob("SKILL.md"):
            if is_excluded_skill_path(skill_md):
                continue
            if _frontmatter_name(skill_md) == want:
                return skill_md.parent
    return None


def skill_read(name: str) -> Optional[Dict[str, Any]]:
    """Full SKILL.md + sidecar-file listing, or None when unknown."""
    sm = _skill_manager()
    skill_dir = _find_skill_dir(name)
    if skill_dir is None:
        return None
    try:
        content = (skill_dir / "SKILL.md").read_text(encoding="utf-8")
    except OSError:
        return None
    category = None
    if _is_under(skill_dir, sm.SKILLS_DIR):
        rel = skill_dir.resolve().relative_to(sm.SKILLS_DIR.resolve())
        if len(rel.parts) > 1:
            category = rel.parts[0]
        readonly = False
    else:
        readonly = True
    files = sorted(
        str(p.relative_to(skill_dir))
        for p in skill_dir.rglob("*")
        if p.is_file()
        and p.name not in ("SKILL.md", _SKILL_BAK)
        and not p.name.startswith(".")
    )
    return {
        # Canonical directory basename — PUT /keryx/skills/{name} wants THIS,
        # even when the caller looked the skill up by its display name.
        "name": skill_dir.name,
        "category": category,
        "content": content,
        "files": files,
        "readonly": readonly,
    }


def skill_write(name: str, content: str) -> Tuple[int, Dict[str, Any]]:
    sm = _skill_manager()
    found = sm._find_skill(name)
    if not found:
        return 404, {"error": {"message": f"unknown skill '{name}'"}}
    skill_dir: Path = found["path"]
    if not _is_under(skill_dir, sm.SKILLS_DIR):
        return 403, {
            "error": {"message": "skill lives in a read-only external directory"}
        }
    skill_md = skill_dir / "SKILL.md"
    try:
        if skill_md.exists():
            shutil.copy2(skill_md, skill_dir / _SKILL_BAK)
    except OSError:
        logger.warning("skill backup failed for %s", name, exc_info=True)
    result = sm._edit_skill(name, content)
    if not result.get("success"):
        # Validation / security-scan message verbatim — the app renders it.
        return 400, {"error": {"message": str(result.get("error") or "edit failed")}}
    _bust_skills_prompt_cache()
    return 200, {
        "ok": True,
        "message": result.get("message"),
        "note": "skill index refreshes for new sessions",
    }


def skill_create(payload: Dict[str, Any]) -> Tuple[int, Dict[str, Any]]:
    name = str(payload.get("name") or "").strip()
    content = payload.get("content")
    if not name:
        raise ValueError("name is required")
    if _SKILL_NAME_BAD.search(name):
        raise ValueError("invalid skill name")
    if not isinstance(content, str) or not content.strip():
        raise ValueError("content is required")
    category = str(payload.get("category") or "").strip() or None
    if category and _SKILL_NAME_BAD.search(category):
        raise ValueError("invalid category")
    sm = _skill_manager()
    result = sm._create_skill(name, content, category)
    if not result.get("success"):
        return 400, {"error": {"message": str(result.get("error") or "create failed")}}
    _bust_skills_prompt_cache()
    return 200, {"ok": True, "path": result.get("path"), "message": result.get("message")}


# ---------------------------------------------------------------------------
# Skill trash (Keryx 1.25) — deleting a skill from the phone, recoverably.
#
# The trash root deliberately lives OUTSIDE every skills root. Hiding it in a
# dot-directory under ~/.hermes/skills would lean on the loader's exclusion set
# (agent.skill_utils.EXCLUDED_SKILL_DIRS) happening to cover our name — it
# covers .git/.archive/.hub and friends, NOT an arbitrary .trash — so a
# "deleted" skill would quietly go on being loaded into the agent's system
# prompt. Sitting outside the scanned roots makes that structurally impossible
# rather than conventionally unlikely, and _assert_trash_isolated re-checks it
# on every delete because skills.external_dirs is operator-configured and could
# one day grow to contain us.
# ---------------------------------------------------------------------------

_TRASH_ID_BAD = re.compile(r"[/\\]|\.\.")


def _skill_trash_root() -> Path:
    """Sibling of the local skills dir, never inside it. Derived from the same
    SKILLS_DIR the read/write paths already treat as authoritative, so the
    trash follows a relocated skills root instead of drifting away from it."""
    return _skill_manager().SKILLS_DIR.expanduser().resolve().parent / "keryx-skill-trash"


def _assert_trash_isolated(root: Path) -> None:
    """Refuse to trash anything while the trash root sits inside a scanned
    skills root: the moved skill would still be discovered, so "deleted" would
    be a lie. A failed delete is recoverable; a phantom skill is not obvious."""
    try:
        from agent.skill_utils import get_all_skills_dirs
    except Exception:
        return
    for skills_dir in get_all_skills_dirs():
        if _is_under(root, skills_dir):
            raise RuntimeError(
                f"skill trash {root} sits inside skills root {skills_dir} — "
                "refusing to delete (a trashed skill would stay live)"
            )


def _trash_entry_meta(entry: Path) -> Optional[Dict[str, Any]]:
    try:
        meta = json.loads((entry / "entry.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(meta, dict):
        return None
    meta["id"] = entry.name
    # A restore lands back on the original path, so a skill recreated under the
    # same name blocks it — tell the app up front instead of failing the tap.
    origin = str(meta.get("origin") or "")
    meta["restorable"] = bool(origin) and not Path(origin).exists()
    return meta


def skill_delete(name: str) -> Tuple[int, Dict[str, Any]]:
    """Move a skill out of the scanned tree and into the trash. Recoverable via
    skill_restore right up until it is purged."""
    sm = _skill_manager()
    skill_dir = _find_skill_dir(name)
    if skill_dir is None:
        return 404, {"error": {"message": f"unknown skill '{name}'"}}
    # Same refusal as skill_write: external dirs are read-only to the phone.
    if not _is_under(skill_dir, sm.SKILLS_DIR):
        return 403, {
            "error": {"message": "skill lives in a read-only external directory"}
        }
    origin = skill_dir.resolve()
    skills_root = sm.SKILLS_DIR.resolve()
    if origin == skills_root:
        return 400, {"error": {"message": "refusing to delete the skills root"}}

    root = _skill_trash_root()
    _assert_trash_isolated(root)

    from datetime import datetime, timezone

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    entry = root / f"{origin.name}-{stamp}"
    suffix = 1
    while entry.exists():
        suffix += 1
        entry = root / f"{origin.name}-{stamp}-{suffix}"
    entry.mkdir(parents=True)

    rel = origin.relative_to(skills_root)
    try:
        shutil.move(str(skill_dir), str(entry / "skill"))
    except OSError as e:
        shutil.rmtree(entry, ignore_errors=True)
        return 500, {"error": {"message": f"could not move skill to trash: {e}"}}
    meta = {
        "name": origin.name,
        "category": rel.parts[0] if len(rel.parts) > 1 else None,
        "origin": str(origin),
        "deleted_at": stamp,
    }
    (entry / "entry.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    _bust_skills_prompt_cache()
    return 200, {
        "ok": True,
        "id": entry.name,
        "name": origin.name,
        "note": "moved to trash — restorable until purged; "
        "skill index refreshes for new sessions",
    }


def skill_trash_list() -> Tuple[int, Dict[str, Any]]:
    """Newest first — the thing you just deleted by mistake is at the top."""
    root = _skill_trash_root()
    if not root.is_dir():
        return 200, {"entries": []}
    entries = []
    for entry in sorted(root.iterdir(), key=lambda p: p.name, reverse=True):
        if not entry.is_dir():
            continue
        meta = _trash_entry_meta(entry)
        if meta is not None:
            entries.append(meta)
    return 200, {"entries": entries}


def skill_restore(entry_id: str) -> Tuple[int, Dict[str, Any]]:
    if not entry_id or _TRASH_ID_BAD.search(entry_id):
        return 400, {"error": {"message": "invalid trash id"}}
    entry = _skill_trash_root() / entry_id
    meta = _trash_entry_meta(entry) if entry.is_dir() else None
    if meta is None:
        return 404, {"error": {"message": f"unknown trash entry '{entry_id}'"}}
    origin = Path(str(meta.get("origin") or ""))
    if not str(origin) or not origin.is_absolute():
        return 400, {"error": {"message": "trash entry has no usable origin path"}}
    sm = _skill_manager()
    if not _is_under(origin, sm.SKILLS_DIR):
        return 403, {
            "error": {"message": "trash entry points outside the skills directory"}
        }
    if origin.exists():
        return 409, {
            "error": {
                "message": f"'{origin.name}' exists again — rename or delete it first"
            }
        }
    origin.parent.mkdir(parents=True, exist_ok=True)
    try:
        shutil.move(str(entry / "skill"), str(origin))
    except OSError as e:
        return 500, {"error": {"message": f"could not restore skill: {e}"}}
    shutil.rmtree(entry, ignore_errors=True)
    _bust_skills_prompt_cache()
    return 200, {
        "ok": True,
        "name": origin.name,
        "note": "restored — skill index refreshes for new sessions",
    }


def skill_purge(entry_id: str) -> Tuple[int, Dict[str, Any]]:
    if not entry_id or _TRASH_ID_BAD.search(entry_id):
        return 400, {"error": {"message": "invalid trash id"}}
    entry = _skill_trash_root() / entry_id
    if not entry.is_dir():
        return 404, {"error": {"message": f"unknown trash entry '{entry_id}'"}}
    shutil.rmtree(entry)
    return 200, {"ok": True, "id": entry_id, "note": "purged for good"}


# ---------------------------------------------------------------------------
# Session prune (Keryx 1.8) — thin wrapper over hermes_state's new bulk
# pruner, mirroring the dashboard's POST /api/sessions/prune body/response
# byte-for-byte where it matters (the dashboard server is disabled on this
# box, so this is the phone's only door). Only ENDED sessions are ever
# touched — that guarantee lives upstream in _prune_filter_where.
# ---------------------------------------------------------------------------

# Attribute filters that suppress the implicit 90-day default (same list as
# web_server.SessionPrune; "explicit" here = key present in the JSON body,
# the no-pydantic equivalent of model_fields_set).
_PRUNE_ATTR_FILTERS = (
    "source", "title_like", "end_reason", "cwd_prefix",
    "min_messages", "max_messages", "model_like", "provider",
    "user_id", "chat_id", "chat_type", "branch_like",
    "min_tokens", "max_tokens", "min_cost", "max_cost",
    "min_tool_calls", "max_tool_calls",
)

# Dry-run sample cap: `matched` carries the true count, the row sample stays
# phone-sized. Deliberate deviation from the dashboard (which returns all).
_PRUNE_SAMPLE_CAP = 50


def sessions_prune(
    body: Dict[str, Any], db: Any = None, sessions_dir: Optional[Path] = None
) -> Dict[str, Any]:
    """Run (or dry-run) a filtered session prune. [db] injectable for tests."""
    older = body.get("older_than_days", 90)
    if older is not None:
        older = float(older)
    has_window = (
        body.get("started_before") is not None
        or body.get("started_after") is not None
    )
    if older is not None and older < 1 and not has_window:
        raise ValueError("older_than_days must be >= 1")
    attr_set = any(body.get(f) is not None for f in _PRUNE_ATTR_FILTERS)
    effective_older = older
    if has_window or (attr_set and "older_than_days" not in body):
        effective_older = None
    filters = dict(
        older_than_days=effective_older,
        source=(body.get("source") or None),
        started_before=body.get("started_before"),
        started_after=body.get("started_after"),
        title_like=(body.get("title_like") or None),
        end_reason=(body.get("end_reason") or None),
        cwd_prefix=(body.get("cwd_prefix") or None),
        min_messages=body.get("min_messages"),
        max_messages=body.get("max_messages"),
        model_like=(body.get("model_like") or None),
        provider=(body.get("provider") or None),
        user_id=(body.get("user_id") or None),
        chat_id=(body.get("chat_id") or None),
        chat_type=(body.get("chat_type") or None),
        branch_like=(body.get("branch_like") or None),
        min_tokens=body.get("min_tokens"),
        max_tokens=body.get("max_tokens"),
        min_cost=body.get("min_cost"),
        max_cost=body.get("max_cost"),
        min_tool_calls=body.get("min_tool_calls"),
        max_tool_calls=body.get("max_tool_calls"),
        archived=None if body.get("include_archived") else False,
    )
    own_db = db is None
    if own_db:
        from hermes_state import SessionDB

        db = SessionDB()
    try:
        if body.get("dry_run"):
            rows = db.list_prune_candidates(**filters)
            return {
                "ok": True,
                "removed": 0,
                "matched": len(rows),
                # Rows are ordered oldest-first upstream.
                "oldest_started_at": rows[0]["started_at"] if rows else None,
                "newest_started_at": rows[-1]["started_at"] if rows else None,
                "sessions": [
                    {
                        "id": r["id"],
                        "source": r["source"],
                        "title": r.get("title"),
                        "model": r.get("model"),
                        "started_at": r["started_at"],
                        "message_count": r["message_count"],
                    }
                    for r in rows[:_PRUNE_SAMPLE_CAP]
                ],
            }
        if own_db and sessions_dir is None:
            from hermes_constants import get_hermes_home

            candidate = get_hermes_home() / "sessions"
            sessions_dir = candidate if candidate.exists() else None
        removed = db.prune_sessions(sessions_dir=sessions_dir, **filters)
        return {"ok": True, "removed": removed}
    finally:
        if own_db:
            db.close()


# ---------------------------------------------------------------------------
# Toolset toggles (Keryx 1.16) — platform-aware toolset view + enable/disable.
# The core `/v1/toolsets` reports the api_server platform's enablement, but
# the agent Keryx chats with runs on platform_toolsets.<platform> (matrix by
# default) — so the hub was showing state the agent doesn't actually have.
# These routes read AND write the requested platform's list via the same
# hermes helpers the desktop dashboard uses (`_get_platform_tools` /
# `_save_platform_tools`), so all surfaces stay in lockstep. Edits are live
# on the agent's next turn: the gateway re-resolves platform toolsets per
# turn through the mtime-keyed config cache — no restart.
#
# Operators can pin the surface with two env vars (comma-separated toolset
# names, read fresh per request so a .env change only needs the usual
# gateway restart):
#   KERYX_TOOLSETS_LOCKED     — cannot be DISABLED from the app
#   KERYX_TOOLSETS_FORBIDDEN  — cannot be ENABLED from the app
# Both surface as `locked: true` so the client greys the switch out instead
# of offering a toggle that an external config guard would silently revert.
# ---------------------------------------------------------------------------

_TOOLSETS_DEFAULT_PLATFORM = "matrix"
# config.yaml platform keys are simple identifiers; anything else is hostile.
_PLATFORM_KEY_OK = re.compile(r"^[a-z0-9_]{1,32}$")


def _toolsets_env_set(var: str) -> set:
    return {t.strip() for t in (os.environ.get(var) or "").split(",") if t.strip()}


def _toolsets_platform(raw: str) -> str:
    platform = (raw or "").strip().lower() or _TOOLSETS_DEFAULT_PLATFORM
    if not _PLATFORM_KEY_OK.match(platform):
        raise ValueError(f"invalid platform '{raw}'")
    return platform


def toolsets_snapshot(platform: str) -> dict:
    """Payload for `GET /keryx/toolsets` — same entry shape as `/v1/toolsets`
    plus `locked`, keyed to the requested platform's enablement."""
    from hermes_cli.config import load_config
    from hermes_cli.tools_config import (
        _get_effective_configurable_toolsets,
        _get_platform_tools,
        _toolset_has_keys,
    )
    from toolsets import resolve_toolset

    locked = _toolsets_env_set("KERYX_TOOLSETS_LOCKED")
    forbidden = _toolsets_env_set("KERYX_TOOLSETS_FORBIDDEN")
    config = load_config()
    enabled = set(
        _get_platform_tools(config, platform, include_default_mcp_servers=False)
    )
    data = []
    for name, label, desc in _get_effective_configurable_toolsets():
        try:
            tools = sorted(set(resolve_toolset(name)))
        except Exception:
            tools = []
        data.append({
            "name": name,
            "label": label,
            "description": desc,
            "enabled": name in enabled,
            "configured": _toolset_has_keys(name, config),
            "locked": name in locked or name in forbidden,
            "tools": tools,
        })
    return {"platform": platform, "canToggle": True, "data": data}


def toolset_set_enabled(name: str, enabled: bool, platform: str) -> Tuple[int, dict]:
    """`PUT /keryx/toolsets/{name}` — persist one toolset's enablement for a
    platform. Refuses locked/forbidden changes so the app never makes an edit
    that an operator guard (or hard rule) would revert behind the user's back."""
    from hermes_cli.config import load_config
    from hermes_cli.tools_config import (
        _get_effective_configurable_toolsets,
        _get_platform_tools,
        _save_platform_tools,
    )

    valid = {key for key, _, _ in _get_effective_configurable_toolsets()}
    if name not in valid:
        return 400, {"error": {"message": f"unknown toolset '{name}'"}}
    if not enabled and name in _toolsets_env_set("KERYX_TOOLSETS_LOCKED"):
        return 403, {"error": {"message": f"'{name}' is locked on and cannot be disabled here"}}
    if enabled and name in _toolsets_env_set("KERYX_TOOLSETS_FORBIDDEN"):
        return 403, {"error": {"message": f"'{name}' is locked off and cannot be enabled here"}}

    config = load_config()
    current = set(
        _get_platform_tools(config, platform, include_default_mcp_servers=False)
    )
    if enabled:
        current.add(name)
    else:
        current.discard(name)

    # _save_platform_tools drops the `no_mcp` sentinel by design (the desktop
    # picker treats saving as consent to re-enable MCP servers). A phone
    # toggle of one toolset is no such consent — losing the sentinel would
    # resurrect every default MCP server on this platform. Put it back.
    raw_before = config.get("platform_toolsets", {}).get(platform) or []
    had_no_mcp = "no_mcp" in raw_before
    _save_platform_tools(config, platform, current)
    if had_no_mcp and "no_mcp" not in config["platform_toolsets"][platform]:
        from hermes_cli.config import save_config

        config["platform_toolsets"][platform] = sorted(
            set(config["platform_toolsets"][platform]) | {"no_mcp"}
        )
        save_config(config)
    return 200, {"ok": True, "name": name, "enabled": enabled, "platform": platform}


# ---------------------------------------------------------------------------
# Gateway Controls (Keryx 1.21) — a curated, non-secret slice of config.yaml
# the phone may adjust, plus the reasoning dial's write side, a redacted log
# tail, and a config-driven brain picker. Everything persists through hermes'
# own config helpers; secrets/.env are structurally out of reach because the
# keys are whitelisted here, never taken from the request.
# ---------------------------------------------------------------------------

_CONFIG_KNOBS: Dict[str, Dict[str, Any]] = {
    # -- Behavior ------------------------------------------------------------
    "busy_input_mode": {
        "section": "display", "path": ["busy_input_mode"], "kind": "enum",
        "choices": ["queue", "steer", "interrupt"], "default": "interrupt",
        "applies": "gateway restart", "label": "Busy input", "group": "Behavior",
        "description": "What a new message does while the agent is mid-task: wait in line, steer the current run, or interrupt it.",
    },
    "max_turns": {
        "section": "agent", "path": ["max_turns"], "kind": "int", "min": 1, "max": 500, "default": 500,
        "applies": "next session", "label": "Max turns", "group": "Behavior",
        "description": "How many agent turns one task may take before it must wrap up.",
    },
    # -- Display -------------------------------------------------------------
    "show_reasoning": {
        "section": "display", "path": ["show_reasoning"], "kind": "bool", "default": True,
        "applies": "next turn", "label": "Reasoning blocks", "group": "Display",
        "description": "Show the brain's \U0001F4AD reasoning above each answer.",
    },
    "streaming": {
        "section": "display", "path": ["streaming"], "kind": "bool", "default": False,
        "applies": "next turn", "label": "Protocol streaming", "group": "Display",
        "description": "Stream answers as message edits when no live side-channel is connected.",
    },
    "runtime_footer": {
        "section": "display", "path": ["runtime_footer", "enabled"], "kind": "bool", "default": False,
        "applies": "next turn", "label": "Runtime footer", "group": "Display",
        "description": "The model · context% · latency · cwd line under each answer.",
    },
    "timestamps": {
        "section": "display", "path": ["timestamps"], "kind": "bool", "default": False,
        "applies": "next turn", "label": "Timestamps", "group": "Display",
        "description": "Stamp each message label with its time.",
    },
    "memory_notifications": {
        "section": "display", "path": ["memory_notifications"], "kind": "enum",
        "choices": ["off", "on", "verbose"], "default": "on",
        "applies": "next turn", "label": "Memory notices", "group": "Display",
        "description": "How loudly the agent announces memory updates: silent, a note, or the full preview.",
    },
    "tool_progress": {
        "section": "display", "path": ["tool_progress"], "kind": "enum",
        "choices": ["off", "new", "all", "verbose"], "default": "all",
        "applies": "next turn", "label": "Tool progress", "group": "Display",
        "description": "Which tool calls narrate while the agent works.",
    },
    "compact": {
        "section": "display", "path": ["compact"], "kind": "bool", "default": False,
        "applies": "next turn", "label": "Compact output", "group": "Display",
        "description": "Trim the agent's chrome to the essentials.",
    },
    # -- Missions (the kanban dispatcher re-reads config every tick, so these
    #    land without a restart) --------------------------------------------
    "missions_dispatch_interval": {
        "section": "kanban", "path": ["dispatch_interval_seconds"], "kind": "int",
        "min": 15, "max": 3600, "default": 60,
        "applies": "next dispatch tick", "label": "Dispatch every", "group": "Missions",
        "description": "Seconds between dispatcher ticks — how quickly ready missions get workers.",
    },
    "missions_failure_limit": {
        "section": "kanban", "path": ["failure_limit"], "kind": "int",
        "min": 1, "max": 10, "default": 2,
        "applies": "next dispatch tick", "label": "Failure limit", "group": "Missions",
        "description": "Consecutive failures before a mission is parked as blocked.",
    },
    "missions_auto_decompose": {
        "section": "kanban", "path": ["auto_decompose"], "kind": "bool", "default": True,
        "applies": "next dispatch tick", "label": "Auto-decompose", "group": "Missions",
        "description": "Let the dispatcher break big missions into subtasks on its own.",
    },
    "missions_decompose_per_tick": {
        "section": "kanban", "path": ["auto_decompose_per_tick"], "kind": "int",
        "min": 1, "max": 10, "default": 3,
        "applies": "next dispatch tick", "label": "Decompose per tick", "group": "Missions",
        "description": "How many missions may be decomposed in one dispatcher pass.",
    },
    "missions_stale_timeout": {
        "section": "kanban", "path": ["dispatch_stale_timeout_seconds"], "kind": "int",
        "min": 600, "max": 86400, "default": 14400,
        "applies": "next dispatch tick", "label": "Stale after", "group": "Missions",
        "description": "Seconds a silent running mission may sit before the dispatcher calls it stale.",
    },
    "missions_default_assignee": {
        "section": "kanban", "path": ["default_assignee"], "kind": "enum",
        "choices_dynamic": "profiles", "default": "",
        "applies": "next dispatch tick", "label": "Default assignee", "group": "Missions",
        "description": "Which agent profile picks up missions that don't name one (blank = the dispatcher decides).",
    },
    "missions_orchestrator": {
        "section": "kanban", "path": ["orchestrator_profile"], "kind": "enum",
        "choices_dynamic": "profiles", "default": "",
        "applies": "next dispatch tick", "label": "Orchestrator", "group": "Missions",
        "description": "Profile that runs decompose/triage passes (blank = default brain).",
    },
    # -- Compression ---------------------------------------------------------
    "compression_threshold": {
        "section": "compression", "path": ["threshold"], "kind": "float",
        "min": 0.3, "max": 0.9, "default": 0.5,
        "applies": "next turn", "label": "Compress at", "group": "Compression",
        "description": "Context fill fraction that triggers compression — lower compresses earlier.",
    },
    "compression_protect_last": {
        "section": "compression", "path": ["protect_last_n"], "kind": "int",
        "min": 10, "max": 200, "default": 20,
        "applies": "next turn", "label": "Protect last", "group": "Compression",
        "description": "Recent messages never summarized away.",
    },
    "compression_message_limit": {
        "section": "compression", "path": ["hygiene_hard_message_limit"], "kind": "int",
        "min": 100, "max": 20000, "default": 5000,
        "applies": "next turn", "label": "Message ceiling", "group": "Compression",
        "description": "Hard cap on kept messages before hygiene trims the transcript.",
    },
    # -- Agent (Keryx 1.25) --------------------------------------------------
    # Everything below this line is typed and range-checked. Settings whose
    # vocabulary is open-ended (web.search_backend, context.engine — both
    # resolve plugin names) deliberately get NO enum knob: a fixed choice list
    # would go stale the moment a plugin is installed. Those live in the raw
    # config editor, which validates the whole file instead of one field.
    "agent_gateway_timeout": {
        "section": "agent", "path": ["gateway_timeout"], "kind": "int",
        "min": 30, "max": 3600, "default": 1800,
        "applies": "gateway restart", "label": "Turn timeout", "group": "Agent",
        "description": "Seconds one gateway turn may run before it is cut off.",
    },
    "agent_api_max_retries": {
        "section": "agent", "path": ["api_max_retries"], "kind": "int",
        "min": 0, "max": 10, "default": 3,
        "applies": "next session", "label": "API retries", "group": "Agent",
        "description": "How many times a failed model call is retried before the turn errors.",
    },
    "agent_task_completion_guidance": {
        "section": "agent", "path": ["task_completion_guidance"], "kind": "bool", "default": True,
        "applies": "next session", "label": "Completion guidance", "group": "Agent",
        "description": "Nudge the agent to finish and summarize rather than trailing off.",
    },
    "agent_parallel_tool_guidance": {
        "section": "agent", "path": ["parallel_tool_call_guidance"], "kind": "bool", "default": True,
        "applies": "next session", "label": "Parallel tool guidance", "group": "Agent",
        "description": "Encourage batching independent tool calls into one step.",
    },
    "agent_environment_probe": {
        "section": "agent", "path": ["environment_probe"], "kind": "bool", "default": True,
        "applies": "next session", "label": "Environment probe", "group": "Agent",
        "description": "Let the agent inspect its shell environment at session start.",
    },
    # -- Tools ---------------------------------------------------------------
    "tool_output_max_bytes": {
        "section": "tool_output", "path": ["max_bytes"], "kind": "int",
        "min": 1000, "max": 500000, "default": 50000,
        "applies": "next turn", "label": "Output byte cap", "group": "Tools",
        "description": "Largest tool result kept before it is truncated.",
    },
    "tool_output_max_lines": {
        "section": "tool_output", "path": ["max_lines"], "kind": "int",
        "min": 50, "max": 20000, "default": 2000,
        "applies": "next turn", "label": "Output line cap", "group": "Tools",
        "description": "Most lines a single tool result may contribute.",
    },
    "tool_output_max_line_length": {
        "section": "tool_output", "path": ["max_line_length"], "kind": "int",
        "min": 200, "max": 20000, "default": 2000,
        "applies": "next turn", "label": "Line length cap", "group": "Tools",
        "description": "Longest single line kept intact in tool output.",
    },
    "guardrails_warnings": {
        "section": "tool_loop_guardrails", "path": ["warnings_enabled"], "kind": "bool", "default": True,
        "applies": "next turn", "label": "Loop warnings", "group": "Tools",
        "description": "Warn the agent when it repeats a failing tool call.",
    },
    "guardrails_hard_stop": {
        "section": "tool_loop_guardrails", "path": ["hard_stop_enabled"], "kind": "bool", "default": False,
        "applies": "next turn", "label": "Loop hard stop", "group": "Tools",
        "description": "Actually halt the run once a tool loop passes the hard-stop threshold.",
    },
    "guardrails_warn_after": {
        "section": "tool_loop_guardrails", "path": ["warn_after", "exact_failure"], "kind": "int",
        "min": 1, "max": 20, "default": 2,
        "applies": "next turn", "label": "Warn after", "group": "Tools",
        "description": "Identical failing calls before the first warning.",
    },
    "guardrails_stop_after": {
        "section": "tool_loop_guardrails", "path": ["hard_stop_after", "exact_failure"], "kind": "int",
        "min": 2, "max": 50, "default": 5,
        "applies": "next turn", "label": "Stop after", "group": "Tools",
        "description": "Identical failing calls before the run is halted.",
    },
    # -- Terminal ------------------------------------------------------------
    "terminal_timeout": {
        "section": "terminal", "path": ["timeout"], "kind": "int",
        "min": 10, "max": 3600, "default": 180,
        "applies": "next turn", "label": "Command timeout", "group": "Terminal",
        "description": "Seconds a shell command may run before it is killed.",
    },
    "terminal_persistent_shell": {
        "section": "terminal", "path": ["persistent_shell"], "kind": "bool", "default": True,
        "applies": "next session", "label": "Persistent shell", "group": "Terminal",
        "description": "Keep one shell alive across commands so cd and exports stick.",
    },
    "terminal_auto_source_bashrc": {
        "section": "terminal", "path": ["auto_source_bashrc"], "kind": "bool", "default": True,
        "applies": "next session", "label": "Source bashrc", "group": "Terminal",
        "description": "Load your shell profile before running commands.",
    },
    "terminal_lifetime": {
        "section": "terminal", "path": ["lifetime_seconds"], "kind": "int",
        "min": 30, "max": 7200, "default": 300,
        "applies": "next session", "label": "Shell lifetime", "group": "Terminal",
        "description": "Seconds an idle persistent shell is kept before being recycled.",
    },
    # -- Browser -------------------------------------------------------------
    "browser_inactivity_timeout": {
        "section": "browser", "path": ["inactivity_timeout"], "kind": "int",
        "min": 15, "max": 3600, "default": 120,
        "applies": "next turn", "label": "Idle timeout", "group": "Browser",
        "description": "Seconds an unused browser session stays open.",
    },
    "browser_command_timeout": {
        "section": "browser", "path": ["command_timeout"], "kind": "int",
        "min": 5, "max": 600, "default": 30,
        "applies": "next turn", "label": "Action timeout", "group": "Browser",
        "description": "Seconds a single browser action may take.",
    },
    "browser_allow_private_urls": {
        "section": "browser", "path": ["allow_private_urls"], "kind": "bool", "default": False,
        "applies": "next turn", "label": "Allow private URLs", "group": "Browser",
        "description": "Let the browser reach LAN and localhost addresses.",
    },
    "browser_record_sessions": {
        "section": "browser", "path": ["record_sessions"], "kind": "bool", "default": False,
        "applies": "next turn", "label": "Record sessions", "group": "Browser",
        "description": "Save a trace of each browsing session to disk.",
    },
    "browser_dialog_policy": {
        "section": "browser", "path": ["dialog_policy"], "kind": "enum",
        "choices": ["must_respond", "auto_dismiss", "auto_accept"], "default": "must_respond",
        "applies": "next turn", "label": "Dialog policy", "group": "Browser",
        "description": "What happens when a page throws an alert or confirm box.",
    },
    # -- Memory --------------------------------------------------------------
    "memory_enabled": {
        "section": "memory", "path": ["memory_enabled"], "kind": "bool", "default": True,
        "applies": "next session", "label": "Memory", "group": "Memory",
        "description": "Inject curated long-term memory into the system prompt.",
    },
    "memory_user_profile": {
        "section": "memory", "path": ["user_profile_enabled"], "kind": "bool", "default": True,
        "applies": "next session", "label": "User profile", "group": "Memory",
        "description": "Include the learned profile of you alongside memories.",
    },
    "memory_write_approval": {
        "section": "memory", "path": ["write_approval"], "kind": "bool", "default": False,
        "applies": "next turn", "label": "Approve writes", "group": "Memory",
        "description": "Ask before the agent adds, replaces, or removes a memory.",
    },
    "memory_char_limit": {
        "section": "memory", "path": ["memory_char_limit"], "kind": "int",
        "min": 500, "max": 50000, "default": 2200,
        "applies": "next session", "label": "Memory budget", "group": "Memory",
        "description": "Characters of memory allowed into the prompt.",
    },
    "memory_user_char_limit": {
        "section": "memory", "path": ["user_char_limit"], "kind": "int",
        "min": 250, "max": 25000, "default": 1375,
        "applies": "next session", "label": "Profile budget", "group": "Memory",
        "description": "Characters of user profile allowed into the prompt.",
    },
    # -- Skills --------------------------------------------------------------
    "skills_write_approval": {
        "section": "skills", "path": ["write_approval"], "kind": "bool", "default": False,
        "applies": "next turn", "label": "Approve skill writes", "group": "Skills",
        "description": "Ask before the agent creates or edits a skill itself.",
    },
    "skills_guard_agent_created": {
        "section": "skills", "path": ["guard_agent_created"], "kind": "bool", "default": False,
        "applies": "next session", "label": "Guard agent-made skills", "group": "Skills",
        "description": "Hold skills the agent wrote for review before they load.",
    },
    "skills_template_vars": {
        "section": "skills", "path": ["template_vars"], "kind": "bool", "default": True,
        "applies": "next session", "label": "Template vars", "group": "Skills",
        "description": "Expand {{variables}} inside SKILL.md when loading.",
    },
    "skills_inline_shell": {
        "section": "skills", "path": ["inline_shell"], "kind": "bool", "default": False,
        "applies": "next session", "label": "Inline shell", "group": "Skills",
        "description": "Let a skill run shell snippets while it loads. Off is safer.",
    },
    "skills_creation_nudge": {
        "section": "skills", "path": ["creation_nudge_interval"], "kind": "int",
        "min": 0, "max": 100, "default": 10,
        "applies": "next session", "label": "Creation nudge", "group": "Skills",
        "description": "Turns between reminders that a repeated task could become a skill (0 = never).",
    },
    "curator_enabled": {
        "section": "curator", "path": ["enabled"], "kind": "bool", "default": True,
        "applies": "gateway restart", "label": "Curator", "group": "Skills",
        "description": "Let the curator groom the skill library on a schedule.",
    },
    "curator_interval_hours": {
        "section": "curator", "path": ["interval_hours"], "kind": "int",
        "min": 1, "max": 8760, "default": 168,
        "applies": "gateway restart", "label": "Curate every", "group": "Skills",
        "description": "Hours between curator passes.",
    },
    "curator_stale_days": {
        "section": "curator", "path": ["stale_after_days"], "kind": "int",
        "min": 1, "max": 3650, "default": 30,
        "applies": "gateway restart", "label": "Stale after", "group": "Skills",
        "description": "Days unused before a skill is flagged stale.",
    },
    "curator_archive_days": {
        "section": "curator", "path": ["archive_after_days"], "kind": "int",
        "min": 1, "max": 3650, "default": 90,
        "applies": "gateway restart", "label": "Archive after", "group": "Skills",
        "description": "Days unused before a stale skill is archived out of the prompt.",
    },
    # -- Delegation ----------------------------------------------------------
    "delegation_orchestrator": {
        "section": "delegation", "path": ["orchestrator_enabled"], "kind": "bool", "default": True,
        "applies": "next session", "label": "Orchestrator", "group": "Delegation",
        "description": "Allow the agent to spawn and coordinate subagents.",
    },
    "delegation_max_children": {
        "section": "delegation", "path": ["max_concurrent_children"], "kind": "int",
        "min": 1, "max": 16, "default": 10,
        "applies": "next session", "label": "Concurrent subagents", "group": "Delegation",
        "description": "How many subagents may run at once.",
    },
    "delegation_max_depth": {
        "section": "delegation", "path": ["max_spawn_depth"], "kind": "int",
        "min": 1, "max": 5, "default": 1,
        "applies": "next session", "label": "Spawn depth", "group": "Delegation",
        "description": "How many levels deep subagents may spawn their own subagents.",
    },
    "delegation_max_iterations": {
        "section": "delegation", "path": ["max_iterations"], "kind": "int",
        "min": 5, "max": 500, "default": 250,
        "applies": "next session", "label": "Subagent turns", "group": "Delegation",
        "description": "Turn ceiling for one subagent.",
    },
    "delegation_child_timeout": {
        "section": "delegation", "path": ["child_timeout_seconds"], "kind": "int",
        "min": 0, "max": 7200, "default": 0,
        "applies": "next session", "label": "Subagent timeout", "group": "Delegation",
        "description": "Seconds a subagent may run before it is cut off.",
    },
    "delegation_auto_approve": {
        "section": "delegation", "path": ["subagent_auto_approve"], "kind": "bool", "default": False,
        "applies": "next session", "label": "Auto-approve subagents", "group": "Delegation",
        "description": "Skip approval prompts inside subagent runs.",
    },
    # -- Voice ---------------------------------------------------------------
    "stt_enabled": {
        "section": "stt", "path": ["enabled"], "kind": "bool", "default": True,
        "applies": "gateway restart", "label": "Speech to text", "group": "Voice",
        "description": "Transcribe voice notes sent to the agent.",
    },
    "voice_auto_tts": {
        "section": "voice", "path": ["auto_tts"], "kind": "bool", "default": False,
        "applies": "next turn", "label": "Auto speak", "group": "Voice",
        "description": "Read every answer aloud without being asked.",
    },
    "voice_max_recording": {
        "section": "voice", "path": ["max_recording_seconds"], "kind": "int",
        "min": 5, "max": 600, "default": 120,
        "applies": "next turn", "label": "Recording cap", "group": "Voice",
        "description": "Longest single voice capture.",
    },
    "voice_silence_duration": {
        "section": "voice", "path": ["silence_duration"], "kind": "float",
        "min": 0.5, "max": 30.0, "default": 3.0,
        "applies": "next turn", "label": "End on silence", "group": "Voice",
        "description": "Seconds of quiet that end a recording.",
    },
    # -- Safety --------------------------------------------------------------
    "privacy_redact_pii": {
        "section": "privacy", "path": ["redact_pii"], "kind": "bool", "default": False,
        "applies": "next turn", "label": "Redact PII", "group": "Safety",
        "description": "Strip personal identifiers from what leaves the box.",
    },
    "checkpoints_enabled": {
        "section": "checkpoints", "path": ["enabled"], "kind": "bool", "default": False,
        "applies": "next session", "label": "Checkpoints", "group": "Safety",
        "description": "Snapshot files before the agent edits them, so changes can be rolled back.",
    },
    "checkpoints_max_snapshots": {
        "section": "checkpoints", "path": ["max_snapshots"], "kind": "int",
        "min": 1, "max": 500, "default": 20,
        "applies": "next session", "label": "Keep snapshots", "group": "Safety",
        "description": "How many checkpoints are retained before the oldest is pruned.",
    },
    "checkpoints_retention_days": {
        "section": "checkpoints", "path": ["retention_days"], "kind": "int",
        "min": 1, "max": 365, "default": 7,
        "applies": "next session", "label": "Keep for", "group": "Safety",
        "description": "Days a checkpoint survives before auto-pruning.",
    },
    "human_delay_mode": {
        "section": "human_delay", "path": ["mode"], "kind": "enum",
        "choices": ["off", "natural"], "default": "off",
        "applies": "next turn", "label": "Human delay", "group": "Safety",
        "description": "Pace replies at human speed instead of answering instantly.",
    },
}


def _profile_choices() -> list:
    """Dynamic enum choices for profile-shaped knobs: the routing map's named
    profiles plus 'default' plus blank (= unset). Computed fresh per call so a
    routing-map edit shows up without a payload change."""
    profiles: list = []
    try:
        import yaml
        from pathlib import Path

        cfg = yaml.safe_load((Path.home() / ".hermes" / "config.yaml").read_text()) or {}
        rp = ((cfg.get("platforms") or {}).get("matrix") or {}).get("room_profile_map") or {}
        if isinstance(rp, dict):
            profiles = sorted({str(v) for v in rp.values() if v})
    except Exception:
        pass
    if "default" not in profiles:
        profiles.append("default")
    return [""] + profiles


def _knob_choices(spec: Dict[str, Any]) -> list:
    if spec.get("choices_dynamic") == "profiles":
        return _profile_choices()
    return spec.get("choices") or []


def _knob_value(cfg: dict, spec: Dict[str, Any]) -> Any:
    node: Any = cfg.get(spec["section"]) or {}
    for part in spec["path"][:-1]:
        node = node.get(part) if isinstance(node, dict) else None
        if node is None:
            return None
    return node.get(spec["path"][-1]) if isinstance(node, dict) else None


def config_knobs_snapshot() -> dict:
    """`GET /keryx/config` — the whitelisted knobs with live values + metadata."""
    from hermes_cli.config import load_config

    cfg = load_config()
    locked = _toolsets_env_set("KERYX_CONFIG_LOCKED")
    knobs = []
    for key, spec in _CONFIG_KNOBS.items():
        value = _knob_value(cfg, spec)
        if value is None:
            value = spec["default"]
        knobs.append({
            "key": key,
            "label": spec["label"],
            "description": spec["description"],
            "kind": spec["kind"],
            "group": spec.get("group") or "Gateway",
            "value": value,
            "choices": _knob_choices(spec),
            "min": spec.get("min"),
            "max": spec.get("max"),
            "applies": spec["applies"],
            "locked": key in locked,
        })
    return {"knobs": knobs}


def config_knob_set(key: Any, value: Any) -> Tuple[int, dict]:
    """`PUT /keryx/config` — validate + persist ONE whitelisted knob."""
    spec = _CONFIG_KNOBS.get(str(key or ""))
    if spec is None:
        return 400, {"error": {"message": f"unknown config key '{key}'"}}
    if str(key) in _toolsets_env_set("KERYX_CONFIG_LOCKED"):
        return 403, {"error": {"message": f"'{key}' is locked by the operator"}}
    kind = spec["kind"]
    if kind == "enum":
        choices = _knob_choices(spec)
        # Dynamic choices (profile names) keep their exact case; static tables
        # are all-lowercase vocabularies, so normalize what the phone sent.
        value = str(value or "").strip()
        if not spec.get("choices_dynamic"):
            value = value.lower()
        if value not in choices:
            shown = [c if c else "(blank)" for c in choices]
            return 400, {"error": {"message": f"'{key}' must be one of: {', '.join(shown)}"}}
    elif kind == "bool":
        if not isinstance(value, bool):
            return 400, {"error": {"message": f"'{key}' takes true/false"}}
    elif kind == "int":
        if not isinstance(value, int) or isinstance(value, bool):
            return 400, {"error": {"message": f"'{key}' takes an integer"}}
        if not (spec["min"] <= value <= spec["max"]):
            return 400, {"error": {"message": f"'{key}' must be {spec['min']}–{spec['max']}"}}
    elif kind == "float":
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return 400, {"error": {"message": f"'{key}' takes a number"}}
        value = float(value)
        if not (spec["min"] <= value <= spec["max"]):
            return 400, {"error": {"message": f"'{key}' must be {spec['min']}–{spec['max']}"}}
    else:  # pragma: no cover - spec table is static
        return 500, {"error": {"message": "bad knob spec"}}

    from hermes_cli.config import load_config, save_config

    cfg = load_config()
    node = cfg.setdefault(spec["section"], {})
    for part in spec["path"][:-1]:
        nxt = node.get(part)
        if not isinstance(nxt, dict):
            nxt = {}
            node[part] = nxt
        node = nxt
    node[spec["path"][-1]] = value
    save_config(cfg)
    return 200, {"ok": True, "key": key, "value": value, "applies": spec["applies"]}


# ---------------------------------------------------------------------------
# Raw config editor (Keryx 1.25) — the escape hatch under the curated knobs.
#
# The knob table can only ever cover settings someone wrote a spec for; this
# hands the whole config.yaml to the phone. Every write is guarded because a
# phone is a bad place to edit YAML: the text must parse, it must parse to a
# mapping, load_config() must accept it, and a backup is taken first so a bad
# save is always one restore away. The optional base_hash makes a save fail
# loudly rather than silently clobbering an edit made elsewhere in between.
# ---------------------------------------------------------------------------

# A truncated paste is the realistic phone failure: select-all, fumble, save a
# fragment. Losing most of the file's top-level sections is treated as an
# accident and refused unless the caller explicitly confirms.
_CONFIG_SECTION_LOSS_GUARD = 0.5
_CONFIG_MAX_BYTES = 2_000_000


def _config_path() -> Path:
    from hermes_constants import get_config_path

    return Path(get_config_path())


def _config_hash(text: str) -> str:
    import hashlib

    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def config_raw_get() -> Tuple[int, dict]:
    """`GET /keryx/config/raw` — the file as text, plus the hash a later PUT
    should echo back so a concurrent edit can be detected."""
    path = _config_path()
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as e:
        return 500, {"error": {"message": f"could not read {path}: {e}"}}
    return 200, {
        "content": text,
        "hash": _config_hash(text),
        "path": str(path),
        "bytes": len(text.encode("utf-8")),
    }


def config_raw_put(body: Dict[str, Any]) -> Tuple[int, dict]:
    """`PUT /keryx/config/raw` — validate, back up, write, verify, roll back."""
    import os

    import yaml

    content = body.get("content")
    if not isinstance(content, str) or not content.strip():
        return 400, {"error": {"message": "content is required"}}
    if len(content.encode("utf-8")) > _CONFIG_MAX_BYTES:
        return 400, {"error": {"message": "config is implausibly large — refusing"}}

    path = _config_path()
    try:
        current = path.read_text(encoding="utf-8")
    except OSError:
        current = ""

    base_hash = str(body.get("base_hash") or "")
    if base_hash and current and base_hash != _config_hash(current):
        return 409, {
            "error": {
                "message": "config.yaml changed on the server since you opened it — "
                "reload before saving"
            }
        }

    try:
        parsed = yaml.safe_load(content)
    except yaml.YAMLError as e:
        # PyYAML's message carries line/column — the app shows it verbatim.
        return 400, {"error": {"message": f"YAML error: {e}"}}
    if not isinstance(parsed, dict):
        return 400, {
            "error": {"message": "config must be a mapping of top-level sections"}
        }

    if not body.get("force"):
        try:
            before = yaml.safe_load(current) if current.strip() else None
        except yaml.YAMLError:
            before = None
        if isinstance(before, dict) and before:
            kept = set(parsed) & set(before)
            if len(kept) < len(before) * _CONFIG_SECTION_LOSS_GUARD:
                lost = sorted(set(before) - set(parsed))
                return 409, {
                    "error": {
                        "message": "this save drops most of the file "
                        f"({len(before) - len(kept)} of {len(before)} sections, "
                        f"including {', '.join(lost[:5])}). Send force to confirm.",
                        "needs_force": True,
                    }
                }

    from datetime import datetime, timezone

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    backup = path.with_name(f"{path.name}.bak.keryx-{stamp}")
    try:
        if current:
            backup.write_text(current, encoding="utf-8")
    except OSError as e:
        return 500, {"error": {"message": f"could not write backup: {e}"}}

    tmp = path.with_name(f".{path.name}.keryx-tmp")
    try:
        tmp.write_text(content, encoding="utf-8")
        os.replace(tmp, path)
    except OSError as e:
        tmp.unlink(missing_ok=True)
        return 500, {"error": {"message": f"could not write config: {e}"}}

    # Last gate: Hermes' own loader has to accept the file. It knows things
    # yaml.safe_load doesn't (schema coercion, required shapes), so this is
    # where a syntactically fine but semantically broken config is caught —
    # while the backup is still one os.replace away.
    try:
        from hermes_cli.config import load_config

        load_config()
    except Exception as e:
        try:
            if current:
                path.write_text(current, encoding="utf-8")
        except OSError:
            logger.exception("config rollback failed — backup at %s", backup)
            return 500, {
                "error": {
                    "message": f"config was rejected AND rollback failed. "
                    f"Restore by hand from {backup}. ({e})"
                }
            }
        return 400, {
            "error": {
                "message": f"Hermes rejected that config, so it was rolled back: {e}"
            }
        }

    return 200, {
        "ok": True,
        "hash": _config_hash(content),
        "backup": str(backup),
        "applies": "gateway restart for most sections",
    }


def reasoning_set(level: Any) -> Tuple[int, dict]:
    """`PUT /keryx/reasoning` — persist the reasoning dial (write side of the
    /keryx/capabilities read). Validates against what the ACTIVE brain accepts
    (binary local brains take none/high; cloud takes the full effort scale)."""
    caps = _reasoning_capabilities()
    levels = caps.get("reasoning", {}).get("levels") or []
    level = str(level or "").strip().lower()
    if level not in levels:
        return 400, {"error": {"message": f"level must be one of: {', '.join(levels)}"}}

    from hermes_cli.config import load_config, save_config

    cfg = load_config()
    cfg.setdefault("agent", {})["reasoning_effort"] = level
    save_config(cfg)
    return 200, {"ok": True, "level": level, "applies": "next session"}


def logs_tail(lines_q: str) -> Tuple[int, dict]:
    """`GET /keryx/logs?lines=` — redacted tail of the gateway's own log.

    journalctl first (systemd installs, --user then system), then plain log
    files under ~/.hermes. Everything goes through the agent's own secret
    redaction before it leaves the box; if redaction can't load, nothing does.
    """
    import subprocess

    try:
        lines = max(20, min(500, int(lines_q or 120)))
    except (TypeError, ValueError):
        lines = 120

    text = ""
    source = ""
    unit = str(os.getenv("KERYX_LOGS_UNIT", "") or "hermes-gateway.service")
    for scope_args in (["--user"], []):
        try:
            proc = subprocess.run(
                ["journalctl", *scope_args, "-u", unit, "-n", str(lines), "--no-pager", "-o", "short-iso"],
                capture_output=True, text=True, timeout=8,
            )
            if proc.returncode == 0 and proc.stdout.strip() and "-- No entries --" not in proc.stdout:
                text, source = proc.stdout, "journal"
                break
        except Exception:
            continue
    if not text:
        from pathlib import Path

        for candidate in (Path.home() / ".hermes" / "logs" / "gateway.log",
                          Path.home() / ".hermes" / "gateway.log"):
            try:
                if candidate.is_file():
                    text = "\n".join(candidate.read_text(errors="replace").splitlines()[-lines:])
                    source = "file"
                    break
            except Exception:
                continue
    if not text:
        return 501, {"error": {"message": "no log source available on this install"}}

    try:
        from agent.redact import redact_sensitive_text

        text = redact_sensitive_text(text)
    except Exception:
        # Fail CLOSED: unredacted logs never leave the gateway.
        return 500, {"error": {"message": "log redaction unavailable"}}
    return 200, {"source": source, "lines": lines, "text": text}


# One swap at a time; a second tap while vLLM is still booting only hurts.
_BRAIN_SWAP_LAST: Dict[str, float] = {"ts": 0.0}
_BRAIN_SWAP_COOLDOWN_S = 60.0


def _brain_entries() -> List[Dict[str, str]]:
    """Operator-configured brains (config.yaml `keryx.brains`, list of
    {name, command, description?}). The COMMAND never leaves the gateway —
    the phone only ever sees name + description."""
    from hermes_cli.config import load_config

    entries = []
    for raw in ((load_config().get("keryx") or {}).get("brains") or []):
        if isinstance(raw, dict) and str(raw.get("name") or "").strip() and str(raw.get("command") or "").strip():
            entries.append({
                "name": str(raw["name"]).strip(),
                "command": str(raw["command"]).strip(),
                "description": str(raw.get("description") or "").strip(),
            })
    return entries


def brains_snapshot() -> dict:
    """`GET /keryx/brains` — the picker list + what's actually serving now.
    Empty list = unconfigured; clients hide the panel."""
    caps = _reasoning_capabilities()
    return {
        "active": caps.get("model", ""),
        "brains": [
            {"name": e["name"], "description": e["description"]} for e in _brain_entries()
        ],
    }


def brain_select(name: Any) -> Tuple[int, dict]:
    """`POST /keryx/brain` — launch the operator's swap command for [name],
    detached (a swap that restarts this gateway must not kill itself). The
    answer is 202: watch `active` on /keryx/brains land on the new model."""
    import subprocess
    import time as _time

    name = str(name or "").strip()
    entry = next((e for e in _brain_entries() if e["name"] == name), None)
    if entry is None:
        return 404, {"error": {"message": f"unknown brain '{name}'"}}
    now = _time.time()
    if now - _BRAIN_SWAP_LAST["ts"] < _BRAIN_SWAP_COOLDOWN_S:
        return 409, {"error": {"message": "a brain swap was just started — give it a minute"}}
    _BRAIN_SWAP_LAST["ts"] = now

    from pathlib import Path

    log_dir = Path.home() / ".hermes" / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    log = open(log_dir / "keryx-brain-swap.log", "ab")
    log.write(f"\n--- {name} @ {_time.strftime('%Y-%m-%dT%H:%M:%S')} ---\n".encode())
    subprocess.Popen(
        ["bash", "-c", entry["command"]],
        stdout=log, stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    return 202, {"ok": True, "started": name}


# ---------------------------------------------------------------------------
# Pet (Keryx 1.10) — the petdex mascot for the drawer header. Mirrors the
# desktop/TUI `pet.info` payload built in tui_gateway/server.py, but reuses
# only the engine (`agent.pet`): the phone renders the spritesheet itself.
# Pets stay configured server-side (`display.pet.enabled` / `.slug`), so the
# phone shows exactly the pet the desktop and TUI show.
# ---------------------------------------------------------------------------


def _pet_sheet_revision(spritesheet: Path) -> str:
    """Stable revision id (`mtime_ns:size`) so clients can cache the sheet."""
    try:
        stat = spritesheet.stat()
        return f"{stat.st_mtime_ns}:{stat.st_size}"
    except Exception:  # noqa: BLE001 - cosmetic, never break the surface
        return "0:0"


def pet_info(meta_only: bool = False) -> dict:
    """Active-pet payload for `GET /keryx/pet`.

    `meta_only` returns just enabled/slug/revision — a cheap probe the client
    uses to skip re-downloading an unchanged ~2MB spritesheet payload.
    Fail-open: any engine/config hiccup reports `{"enabled": False}` rather
    than erroring — the pet is cosmetic.
    """
    try:
        from agent.pet import constants, store
        from hermes_cli.config import load_config

        try:
            cfg = load_config()
            display = cfg.get("display", {}) if isinstance(cfg.get("display"), dict) else {}
            pet_cfg = display.get("pet", {}) if isinstance(display.get("pet"), dict) else {}
        except Exception:  # noqa: BLE001
            pet_cfg = {}

        if not bool(pet_cfg.get("enabled")):
            return {"enabled": False}
        pet = store.resolve_active_pet(str(pet_cfg.get("slug", "") or ""))
        if pet is None or not pet.exists:
            return {"enabled": False}

        revision = _pet_sheet_revision(pet.spritesheet)
        out: Dict[str, Any] = {
            "enabled": True,
            "slug": pet.slug,
            "displayName": pet.display_name,
            "spritesheetRevision": revision,
        }
        if meta_only:
            return out

        import base64

        raw = pet.spritesheet.read_bytes()
        out.update({
            "mime": "image/png" if pet.spritesheet.suffix.lower() == ".png" else "image/webp",
            "spritesheetBase64": base64.standard_b64encode(raw).decode("ascii"),
            "frameW": constants.FRAME_W,
            "frameH": constants.FRAME_H,
            "framesPerState": constants.FRAMES_PER_STATE,
            "loopMs": constants.LOOP_MS,
            "stateRows": _pet_state_rows(pet.spritesheet),
            "framesByRow": _pet_row_frame_counts(pet.spritesheet),
        })
        return out
    except Exception:  # noqa: BLE001 - cosmetic, never break the surface
        logger.debug("keryx: pet info unavailable", exc_info=True)
        return {"enabled": False}


def _pet_state_rows(spritesheet: Path) -> List[str]:
    """Row taxonomy for the concrete sheet (legacy 8-row vs Codex 9-row)."""
    from agent.pet import constants

    try:
        from PIL import Image

        with Image.open(spritesheet) as image:
            row_count = max(1, image.height // constants.FRAME_H)
        return list(constants.state_rows_for_grid(row_count))
    except Exception:  # noqa: BLE001 - cosmetic, never break the surface
        return list(constants.STATE_ROWS)


def _pet_row_frame_counts(spritesheet: Path) -> Dict[str, int]:
    """Real (padding-trimmed) frame count per concrete row name.

    Ragged sheets pad short rows with transparent frames; animating into the
    padding reads as the pet blinking out. Fail-open to `{}` — the client
    falls back to its static `framesPerState`.
    """
    try:
        from PIL import Image

        from agent.pet import constants, render

        with Image.open(spritesheet) as opened:
            image = opened.convert("RGBA")
        cols = max(1, image.width // constants.FRAME_W)
        row_count = max(1, image.height // constants.FRAME_H)
        rows = constants.state_rows_for_grid(row_count)
        out: Dict[str, int] = {}
        for row_idx, name in enumerate(rows[:row_count]):
            top = row_idx * constants.FRAME_H
            count = 0
            for col in range(cols):
                left = col * constants.FRAME_W
                frame = image.crop((left, top, left + constants.FRAME_W, top + constants.FRAME_H))
                if render._frame_is_blank(frame):
                    break
                count += 1
            out[name] = count
        return out
    except Exception:  # noqa: BLE001 - cosmetic, never break the surface
        return {}


def pet_gallery(local_only: bool = False) -> dict:
    """Adoptable-pets list for the phone picker — mirrors tui_gateway `pet.gallery`.

    Merges the petdex catalog with local install state. `local_only` skips the
    remote manifest fetch (and warms it in the background) so the picker can
    render the user's own pets instantly, then follow up with the full catalog
    — the same two-phase load the desktop picker does. Fail-open: offline you
    still get whatever is installed.
    """
    try:
        from agent.pet import store

        try:
            from hermes_cli.config import load_config

            cfg = load_config()
            display = cfg.get("display", {}) if isinstance(cfg.get("display"), dict) else {}
            pet_cfg = display.get("pet", {}) if isinstance(display.get("pet"), dict) else {}
        except Exception:  # noqa: BLE001
            pet_cfg = {}

        installed = {p.slug: p for p in store.installed_pets()}

        pets: List[dict] = []
        seen: set = set()
        try:
            from agent.pet.manifest import fetch_manifest, prefetch

            if local_only:
                prefetch()
            for entry in [] if local_only else fetch_manifest():
                seen.add(entry.slug)
                pets.append({
                    "slug": entry.slug,
                    "displayName": entry.display_name,
                    "installed": entry.slug in installed,
                    "spritesheetUrl": entry.spritesheet_url,
                    # petdex's hand-picked set — the closest thing to a popularity
                    # signal, so the picker can surface these first.
                    "curated": "/curated/" in entry.spritesheet_url,
                    "generated": entry.slug in installed and installed[entry.slug].generated,
                })
        except Exception as exc:  # noqa: BLE001 - offline: installed-only below
            logger.debug("keryx: petdex manifest fetch failed: %s", exc)

        for slug, pet in installed.items():
            if slug not in seen:
                pets.append({
                    "slug": slug,
                    "displayName": pet.display_name,
                    "installed": True,
                    "spritesheetUrl": "",
                    "curated": False,
                    "generated": pet.generated,
                })

        return {
            "enabled": bool(pet_cfg.get("enabled")),
            "active": str(pet_cfg.get("slug", "") or ""),
            "pets": pets,
        }
    except Exception:  # noqa: BLE001 - cosmetic, never break the surface
        logger.debug("keryx: pet gallery unavailable", exc_info=True)
        return {"enabled": False, "active": "", "pets": []}


def pet_select(slug: str) -> Tuple[int, dict]:
    """Adopt *slug* from the phone picker: install from petdex if needed, then
    persist ``display.pet.slug`` + ``enabled`` — the exact `pet.select` path the
    desktop picker takes (`store.install_pet` + `hermes_cli.pets._set_active`)."""
    from agent.pet import store
    from agent.pet.manifest import ManifestError
    from hermes_cli.pets import _set_active

    try:
        pet = store.install_pet(slug)
    except (store.PetStoreError, ManifestError) as exc:
        return 502, {"error": {"message": f"could not adopt '{slug}': {exc}"}}
    _set_active(slug)
    return 200, {"ok": True, "slug": slug, "displayName": pet.display_name}


# ---------------------------------------------------------------------------
# Hermes update (Keryx 2.4.1) — how far behind this install is, and the button
# that runs the operator's update command.
#
# Two deliberate splits:
#
#  * READ is always LOCAL. `git fetch` against this repo takes ~70 s (thousands
#    of auto-generated branches upstream), so the panel must never block on it.
#    The count comes from the refs already on disk and carries the age of the
#    last fetch; the phone decides whether that is fresh enough.
#  * REFRESH is a detached background fetch (`POST /keryx/update/check`), and
#    the phone re-reads the GET when it finishes.
#
# The update COMMAND is operator-configured and never leaves the gateway — same
# contract as `keryx.brains`. Unset = no button; the count still shows.
# ---------------------------------------------------------------------------

# One fetch at a time, and one update at a time.
_UPDATE_FETCH: Dict[str, Any] = {"running": False, "error": "", "ts": 0.0}
# Last anchor-probe result, kept in memory: a preflight is only meaningful for the
# session that ran it, and a stale "ALL CLEAR" from last week is worse than none.
_UPDATE_PROBE: Dict[str, Any] = {
    "running": False, "ts": 0.0, "exit": None, "output": "",
}
_PROBE_TIMEOUT_S = 900
_PROBE_OUTPUT_MAX = 4000
_UPDATE_RUN: Dict[str, float] = {"ts": 0.0}
_UPDATE_RUN_COOLDOWN_S = 600.0


def _update_entry() -> Optional[Dict[str, str]]:
    """What the update button runs, in two tiers.

     1. config.yaml `keryx.update.command` — an operator's own wrapper. Any install
        carrying a local patch layer MUST set this: a bare `hermes update` would
        overwrite the patches with no rollback point.
     2. Otherwise Hermes' own recommended command for this install method — plain
        `hermes update` on a normal git checkout. A stock install therefore gets a
        working button with no configuration at all, which is the point: this ships
        to people who have never heard of anyone's private wrapper.

    `keryx.update.enabled: false` turns the button off entirely (the commits-behind
    count still shows — that is read-only and always safe).

    The COMMAND never leaves the gateway; the phone only ever sees [label].
    """
    from hermes_cli.config import load_config

    raw = (load_config().get("keryx") or {}).get("update")
    if not isinstance(raw, dict):
        raw = {}
    if raw.get("enabled") is False:
        return None

    branch = str(raw.get("branch") or "").strip() or "origin/main"
    command = str(raw.get("command") or "").strip()
    if command:
        return {
            "command": command,
            "label": str(raw.get("label") or "").strip() or command.split()[0],
            "branch": branch,
            "source": "configured",
        }

    # Tier 2. recommended_update_command() already resolves managed installs
    # (package manager, Docker, Nix) and returns GUIDANCE TEXT rather than a
    # runnable command for the ones git can't update — only offer the button
    # when what comes back is actually runnable.
    try:
        from hermes_cli.config import recommended_update_command

        default_cmd = str(recommended_update_command() or "").strip()
    except Exception:
        return None
    if not default_cmd or "\n" in default_cmd or not default_cmd.startswith("hermes "):
        return None
    return {
        "command": default_cmd,
        "label": default_cmd,
        "branch": branch,
        "source": "default",
    }


def _update_probe_entry() -> Optional[Dict[str, str]]:
    """The operator's ANCHOR SCRIPT: a read-only preflight run before committing to
    an update (config.yaml `keryx.update.probe`).

    The shape this exists for: an install carrying a patch layer needs to know
    whether its anchors still exist in the target ref BEFORE anything mutates —
    `silas-update --check` is one such script, a bare `hermes update --check` is
    another, and a stock install has none and simply sees no button.

    Read-only is the CONTRACT, not something the gateway can enforce: whatever is
    named here runs verbatim. Point it at a probe, never at the update itself.
    """
    from hermes_cli.config import load_config

    raw = (load_config().get("keryx") or {}).get("update")
    if not isinstance(raw, dict):
        return None
    probe = raw.get("probe")
    # Accept both `probe: "<command>"` and `probe: {command:, label:}`.
    if isinstance(probe, dict):
        command = str(probe.get("command") or "").strip()
        label = str(probe.get("label") or "").strip()
    else:
        command = str(probe or "").strip()
        label = str(raw.get("probe_label") or "").strip()
    if not command:
        return None
    return {"command": command, "label": label or "preflight"}


def _update_tree() -> Optional[Path]:
    try:
        from hermes_cli.main import PROJECT_ROOT

        return Path(PROJECT_ROOT)
    except Exception:
        return None


def _git(tree: Path, *args: str, timeout: int = 15) -> Tuple[int, str]:
    import subprocess

    try:
        proc = subprocess.run(
            ["git", *args], cwd=str(tree),
            capture_output=True, text=True, timeout=timeout,
            encoding="utf-8", errors="replace",
        )
        return proc.returncode, (proc.stdout or "").strip()
    except Exception as exc:  # git missing, timeout, unreadable tree
        return 1, str(exc)


def _update_compare_ref(tree: Path, branch: str) -> str:
    """Resolve the ref to count against, preferring a remote that exists.

    A fork checkout has both `origin` (upstream) and `fork`; a plain install
    has only `origin`. Counting against a ref git can't resolve yields a bogus
    0 ("up to date!") — the one wrong answer this panel must never give.
    """
    if _git(tree, "rev-parse", "--verify", "--quiet", branch)[0] == 0:
        return branch
    for candidate in ("origin/main", "upstream/main", "up/main"):
        if _git(tree, "rev-parse", "--verify", "--quiet", candidate)[0] == 0:
            return candidate
    return ""


def update_snapshot() -> dict:
    """`GET /keryx/update` — local-only, ~10 ms. Never fetches.

    [behind] is -1 whenever the number cannot be trusted (shallow clone, no
    resolvable remote ref) so the client can say "unknown" instead of "0".
    """
    import time as _time

    entry = _update_entry()
    base: Dict[str, Any] = {
        "supported": False,
        "reason": "",
        "behind": -1,
        "ahead": 0,
        "branch": "",
        "head": "",
        "head_branch": "",
        "version": "",
        "command_configured": entry is not None,
        "label": (entry or {}).get("label", ""),
        # "configured" = operator wrapper, "default" = Hermes' own `hermes update`.
        "command_source": (entry or {}).get("source", ""),
        "checked_at": "",
        "checking": bool(_UPDATE_FETCH["running"]),
        "check_error": str(_UPDATE_FETCH["error"] or ""),
        "running": False,
    }

    probe = _update_probe_entry()
    base["probe_configured"] = probe is not None
    base["probe_label"] = (probe or {}).get("label", "")
    base["probe_running"] = bool(_UPDATE_PROBE["running"])
    # exit is None until a probe has ever run — "not yet run" is distinct from "passed".
    base["probe_exit"] = _UPDATE_PROBE["exit"]
    base["probe_output"] = str(_UPDATE_PROBE["output"] or "")
    base["probe_at"] = ""
    if _UPDATE_PROBE["ts"]:
        import datetime as _pdt

        base["probe_at"] = _pdt.datetime.fromtimestamp(
            float(_UPDATE_PROBE["ts"]), _pdt.timezone.utc
        ).isoformat(timespec="seconds")
    try:
        from hermes_cli import __version__

        base["version"] = str(__version__)
    except Exception:
        pass

    now = _time.time()
    base["running"] = (now - _UPDATE_RUN["ts"]) < _UPDATE_RUN_COOLDOWN_S

    tree = _update_tree()
    if tree is None or not (tree / ".git").exists():
        base["reason"] = "this install is not a git checkout — update from the host"
        return base

    try:
        from hermes_cli.config import detect_install_method

        method = detect_install_method(tree)
        if method in {"docker", "nix", "nixos"}:
            base["reason"] = f"{method} installs update outside git"
            return base
    except Exception:
        pass

    base["supported"] = True
    base["head"] = _git(tree, "rev-parse", "--short", "HEAD")[1]
    base["head_branch"] = _git(tree, "rev-parse", "--abbrev-ref", "HEAD")[1]

    branch = _update_compare_ref(tree, (entry or {}).get("branch", "origin/main"))
    base["branch"] = branch
    if not branch:
        base["reason"] = "no remote branch to compare against"
        return base

    # A shallow clone (installer default) can't count honestly — the boundary
    # makes every ancestor look missing. Report presence, not a number.
    if _git(tree, "rev-parse", "--is-shallow-repository")[1] == "true":
        rc, out = _git(tree, "rev-list", "--count", f"HEAD..{branch}")
        base["reason"] = "shallow clone — exact count unavailable"
        base["behind"] = -1 if rc != 0 else (1 if out not in ("", "0") else 0)
        return base

    rc, out = _git(tree, "rev-list", "--left-right", "--count", f"HEAD...{branch}")
    if rc == 0:
        parts = out.split()
        if len(parts) == 2 and parts[0].isdigit() and parts[1].isdigit():
            base["ahead"], base["behind"] = int(parts[0]), int(parts[1])

    # Age of the count = age of the last fetch, not of this request.
    import datetime as _dt

    for name in ("FETCH_HEAD", "HEAD"):
        candidate = tree / ".git" / name
        try:
            if candidate.is_file():
                base["checked_at"] = _dt.datetime.fromtimestamp(
                    candidate.stat().st_mtime, _dt.timezone.utc
                ).isoformat(timespec="seconds")
                break
        except Exception:
            continue
    return base


def update_check() -> Tuple[int, dict]:
    """`POST /keryx/update/check` — refresh the refs in the background.

    202 and return immediately: the fetch takes over a minute on this repo and
    an aiohttp worker thread is not the place to spend it.
    """
    import threading
    import time as _time

    if _UPDATE_FETCH["running"]:
        return 202, {"ok": True, "checking": True}
    tree = _update_tree()
    if tree is None or not (tree / ".git").exists():
        return 501, {"error": {"message": "not a git checkout"}}

    branch = _update_compare_ref(tree, (_update_entry() or {}).get("branch", "origin/main"))
    remote, _, ref = branch.partition("/")
    if not remote or not ref:
        return 501, {"error": {"message": "no remote branch to compare against"}}

    def _fetch() -> None:
        _UPDATE_FETCH["running"] = True
        _UPDATE_FETCH["error"] = ""
        try:
            # Clear an abandoned lock first: one crashed fetch otherwise wedges
            # every later one with "File exists" and the count silently goes stale.
            try:
                from hermes_cli.gitlock import clear_stale_git_locks

                clear_stale_git_locks(tree)
            except Exception:
                pass
            shallow = _git(tree, "rev-parse", "--is-shallow-repository")[1] == "true"
            depth = ["--depth", "1"] if shallow else []
            # Scope the fetch to the one branch: a bare `git fetch` drags in
            # thousands of upstream auto-branches.
            rc, out = _git(tree, "fetch", "--quiet", *depth, remote, ref, timeout=240)
            if rc != 0:
                _UPDATE_FETCH["error"] = (out or "fetch failed")[:200]
        except Exception as exc:
            _UPDATE_FETCH["error"] = str(exc)[:200]
        finally:
            _UPDATE_FETCH["ts"] = _time.time()
            _UPDATE_FETCH["running"] = False

    threading.Thread(target=_fetch, name="keryx-update-fetch", daemon=True).start()
    return 202, {"ok": True, "checking": True}


def update_probe() -> Tuple[int, dict]:
    """`POST /keryx/update/probe` — run the operator's anchor script in the background.

    202 and return: an anchor probe fetches and diffs against the target, which is
    minutes of work, not milliseconds. Poll `probe_running` on GET /keryx/update and
    read `probe_exit` (0 = clear) plus the captured tail when it clears.
    """
    import subprocess
    import threading
    import time as _time

    entry = _update_probe_entry()
    if entry is None:
        return 501, {
            "error": {"message": "no anchor script configured (config.yaml keryx.update.probe)"}
        }
    if _UPDATE_PROBE["running"]:
        return 202, {"ok": True, "probe_running": True}

    def _run() -> None:
        _UPDATE_PROBE.update({"running": True, "exit": None, "output": ""})
        out, code = "", 1
        try:
            proc = subprocess.run(
                ["bash", "-lc", entry["command"]],
                cwd=str(Path.home()),
                capture_output=True, text=True, timeout=_PROBE_TIMEOUT_S,
                encoding="utf-8", errors="replace",
            )
            out = ((proc.stdout or "") + (proc.stderr or "")).strip()
            code = proc.returncode
        except subprocess.TimeoutExpired:
            out, code = f"probe timed out after {_PROBE_TIMEOUT_S}s", 124
        except Exception as exc:
            out, code = str(exc), 1
        # Same fail-closed rule the log tail uses: unredacted output never leaves
        # the gateway, and a probe prints whatever the operator's script prints.
        try:
            from agent.redact import redact_sensitive_text

            out = redact_sensitive_text(out)
        except Exception:
            out = "(probe output withheld — redaction unavailable)"
        if len(out) > _PROBE_OUTPUT_MAX:
            out = "…" + out[-_PROBE_OUTPUT_MAX:]
        _UPDATE_PROBE.update(
            {"running": False, "ts": _time.time(), "exit": code, "output": out}
        )

    threading.Thread(target=_run, name="keryx-update-probe", daemon=True).start()
    return 202, {"ok": True, "probe_running": True, "started": entry["label"]}


def update_start() -> Tuple[int, dict]:
    """`POST /keryx/update` — launch the operator's update command detached.

    Detached for the same reason a brain swap is: the command restarts (and
    reinstalls under) this very gateway, so a child in our process group would
    be killed halfway through its own update.
    """
    import subprocess
    import time as _time

    entry = _update_entry()
    if entry is None:
        return 501, {
            "error": {"message": "no update command configured (config.yaml keryx.update.command)"}
        }
    now = _time.time()
    if now - _UPDATE_RUN["ts"] < _UPDATE_RUN_COOLDOWN_S:
        return 409, {"error": {"message": "an update was just started — let it finish"}}
    _UPDATE_RUN["ts"] = now

    log_dir = Path.home() / ".hermes" / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    log = open(log_dir / "keryx-update.log", "ab")
    log.write(
        f"\n--- {entry['label']} @ {_time.strftime('%Y-%m-%dT%H:%M:%S')} ---\n".encode()
    )
    subprocess.Popen(
        ["bash", "-lc", entry["command"]],
        stdout=log, stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    return 202, {"ok": True, "started": entry["label"]}


def register_keryx_routes(router: Any, check_auth) -> None:
    """Single registrar for every /keryx/* route — api_server.py calls only
    this, so future routes ship in this module (copied wholesale by
    install.py) without touching the api_server patch again."""
    router.add_get("/keryx/stream", make_stream_handler(check_auth))
    router.add_get("/keryx/capabilities", make_capabilities_handler(check_auth))
    router.add_get("/keryx/commands", make_commands_handler(check_auth))

    def _pet(request, body):
        meta = str(request.query.get("meta", "")).lower() in ("1", "true")
        return 200, pet_info(meta_only=meta)

    def _pets(request, body):
        local_only = str(request.query.get("localOnly", "")).lower() in ("1", "true")
        return 200, pet_gallery(local_only)

    def _pet_select(request, body):
        slug = str(body.get("slug") or "").strip()
        if not slug:
            raise ValueError("slug is required")
        return pet_select(slug)

    def _pet_thumb(request, body):
        slug = str(request.query.get("slug", "")).strip()
        if not slug:
            raise ValueError("slug is required")
        from agent.pet import store

        # `url` lets not-yet-installed catalog pets get a preview; the store
        # only fetches it when it points at petdex, never an arbitrary host.
        data = store.thumbnail_png(slug, source_url=str(request.query.get("url", "")))
        if not data:
            return 200, {"ok": False, "slug": slug}
        import base64

        return 200, {"ok": True, "slug": slug, "thumbBase64": base64.standard_b64encode(data).decode("ascii")}

    router.add_get("/keryx/pet", _make_json_handler(check_auth, _pet))
    router.add_get("/keryx/pets", _make_json_handler(check_auth, _pets))
    router.add_post("/keryx/pet/select", _make_json_handler(check_auth, _pet_select))
    router.add_get("/keryx/pet/thumb", _make_json_handler(check_auth, _pet_thumb))

    def _board(kb, conn, request, body):
        return 200, kanban_board_snapshot(kb, conn)

    def _detail(kb, conn, request, body):
        detail = kanban_task_detail(kb, conn, request.match_info["task_id"])
        if detail is None:
            return 404, {"error": {"message": "unknown task"}}
        return 200, detail

    def _create(kb, conn, request, body):
        return 200, kanban_create(kb, conn, body)

    def _comment(kb, conn, request, body):
        text = str(body.get("body") or "").strip()
        if not text:
            raise ValueError("body is required")
        return 200, kanban_comment(kb, conn, request.match_info["task_id"], text)

    def _settings(kb, conn, request, body):
        out = kanban_task_settings(kb, conn, request.match_info["task_id"], body)
        if out is None:
            return 404, {"error": {"message": "unknown task"}}
        return 200, out

    def _events(kb, conn, request, body):
        since = int(request.query.get("since", 0) or 0)
        return 200, kanban_events_since(conn, since)

    def _subs(kb, conn, request, body):
        return 200, kanban_subs_list(kb, conn)

    def _subscribe(kb, conn, request, body):
        out = kanban_subscribe(kb, conn, request.match_info["task_id"], body)
        if out is None:
            return 404, {"error": {"message": "unknown task"}}
        return 200, out

    def _unsubscribe(kb, conn, request, body):
        return 200, kanban_unsubscribe(kb, conn, request.match_info["task_id"], body)

    router.add_get("/keryx/kanban/board", _make_kanban_handler(check_auth, _board))
    router.add_get("/keryx/kanban/task/{task_id}", _make_kanban_handler(check_auth, _detail))
    router.add_post("/keryx/kanban/task", _make_kanban_handler(check_auth, _create))
    router.add_post("/keryx/kanban/task/{task_id}/comment", _make_kanban_handler(check_auth, _comment))
    router.add_post("/keryx/kanban/task/{task_id}/settings", _make_kanban_handler(check_auth, _settings))
    router.add_get("/keryx/kanban/events", _make_kanban_handler(check_auth, _events))
    router.add_get("/keryx/kanban/subs", _make_kanban_handler(check_auth, _subs))
    router.add_post("/keryx/kanban/task/{task_id}/subscribe", _make_kanban_handler(check_auth, _subscribe))
    router.add_post("/keryx/kanban/task/{task_id}/unsubscribe", _make_kanban_handler(check_auth, _unsubscribe))

    def _skill_get(request, body):
        name = request.match_info["name"]
        if _SKILL_NAME_BAD.search(name):
            return 400, {"error": {"message": "invalid skill name"}}
        detail = skill_read(name)
        if detail is None:
            return 404, {"error": {"message": f"unknown skill '{name}'"}}
        return 200, detail

    def _skill_put(request, body):
        name = request.match_info["name"]
        if _SKILL_NAME_BAD.search(name):
            return 400, {"error": {"message": "invalid skill name"}}
        content = body.get("content")
        if not isinstance(content, str) or not content.strip():
            raise ValueError("content is required")
        return skill_write(name, content)

    def _skill_post(request, body):
        return skill_create(body)

    def _skill_delete(request, body):
        name = request.match_info["name"]
        if _SKILL_NAME_BAD.search(name):
            return 400, {"error": {"message": "invalid skill name"}}
        return skill_delete(name)

    def _skill_trash_get(request, body):
        return skill_trash_list()

    def _skill_restore(request, body):
        return skill_restore(request.match_info["entry_id"])

    def _skill_purge(request, body):
        return skill_purge(request.match_info["entry_id"])

    router.add_get("/keryx/skills/{name}", _make_json_handler(check_auth, _skill_get))
    router.add_put("/keryx/skills/{name}", _make_json_handler(check_auth, _skill_put))
    router.add_post("/keryx/skills", _make_json_handler(check_auth, _skill_post))
    router.add_delete("/keryx/skills/{name}", _make_json_handler(check_auth, _skill_delete))
    # Trash rides its own prefix rather than /keryx/skills/trash: that path only
    # resolves while it stays registered ahead of /keryx/skills/{name}, and a
    # later reorder would silently start treating "trash" as a skill name.
    router.add_get("/keryx/skill-trash", _make_json_handler(check_auth, _skill_trash_get))
    router.add_post(
        "/keryx/skill-trash/{entry_id}/restore",
        _make_json_handler(check_auth, _skill_restore),
    )
    router.add_delete(
        "/keryx/skill-trash/{entry_id}", _make_json_handler(check_auth, _skill_purge)
    )

    def _prune(request, body):
        return 200, sessions_prune(body)

    router.add_post("/keryx/sessions/prune", _make_json_handler(check_auth, _prune))

    def _toolsets_get(request, body):
        platform = _toolsets_platform(request.query.get("platform", ""))
        return 200, toolsets_snapshot(platform)

    def _toolset_put(request, body):
        enabled = body.get("enabled")
        if not isinstance(enabled, bool):
            raise ValueError("boolean 'enabled' is required")
        platform = _toolsets_platform(str(body.get("platform") or ""))
        return toolset_set_enabled(request.match_info["name"], enabled, platform)

    router.add_get("/keryx/toolsets", _make_json_handler(check_auth, _toolsets_get))
    router.add_put("/keryx/toolsets/{name}", _make_json_handler(check_auth, _toolset_put))

    # --- Gateway Controls (Keryx 1.21) ------------------------------------

    def _reasoning_put(request, body):
        return reasoning_set(body.get("level"))

    def _config_get(request, body):
        return 200, config_knobs_snapshot()

    def _config_put(request, body):
        return config_knob_set(body.get("key"), body.get("value"))

    def _logs_get(request, body):
        return logs_tail(request.query.get("lines", ""))

    def _brains_get(request, body):
        return 200, brains_snapshot()

    def _brain_post(request, body):
        return brain_select(body.get("name"))

    def _update_get(request, body):
        return 200, update_snapshot()

    def _update_check_post(request, body):
        return update_check()

    def _update_probe_post(request, body):
        return update_probe()

    def _update_post(request, body):
        return update_start()

    def _config_raw_get(request, body):
        return config_raw_get()

    def _config_raw_put(request, body):
        return config_raw_put(body)

    router.add_put("/keryx/reasoning", _make_json_handler(check_auth, _reasoning_put))
    router.add_get("/keryx/config", _make_json_handler(check_auth, _config_get))
    router.add_put("/keryx/config", _make_json_handler(check_auth, _config_put))
    router.add_get("/keryx/config/raw", _make_json_handler(check_auth, _config_raw_get))
    router.add_put("/keryx/config/raw", _make_json_handler(check_auth, _config_raw_put))
    router.add_get("/keryx/logs", _make_json_handler(check_auth, _logs_get))
    router.add_get("/keryx/brains", _make_json_handler(check_auth, _brains_get))
    router.add_post("/keryx/brain", _make_json_handler(check_auth, _brain_post))
    # Hermes update (2.4.1): GET is local-only, /check refreshes refs in the
    # background, POST launches the operator's command.
    router.add_get("/keryx/update", _make_json_handler(check_auth, _update_get))
    router.add_post("/keryx/update/check", _make_json_handler(check_auth, _update_check_post))
    router.add_post("/keryx/update/probe", _make_json_handler(check_auth, _update_probe_post))
    router.add_post("/keryx/update", _make_json_handler(check_auth, _update_post))
