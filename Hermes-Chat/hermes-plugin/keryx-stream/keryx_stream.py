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
    until the final Matrix event syncs in.

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


def publish_stop(adapter: Any, chat_id: Any, final_text: Optional[str] = None) -> None:
    hub.publish_threadsafe(_platform_of(adapter), str(chat_id), "stop", final_text)


def attach_reasoning_callback(agent: Any, source: Any) -> None:
    """Register a live-reasoning mirror on the agent for this turn.

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
        effort = str(agent_cfg.get("reasoning_effort", "medium") or "medium").strip().lower()
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
    if local:
        reasoning = {
            "mode": "binary",
            "levels": ["none", "high"],
            "labels": {"none": "Off", "high": "On"},
            "current": "none" if effort == "none" else "high",
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


def register_keryx_routes(router: Any, check_auth) -> None:
    """Single registrar for every /keryx/* route — api_server.py calls only
    this, so future routes ship in this module (copied wholesale by
    install.py) without touching the api_server patch again."""
    router.add_get("/keryx/stream", make_stream_handler(check_auth))
    router.add_get("/keryx/capabilities", make_capabilities_handler(check_auth))
    router.add_get("/keryx/commands", make_commands_handler(check_auth))

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

    router.add_get("/keryx/skills/{name}", _make_json_handler(check_auth, _skill_get))
    router.add_put("/keryx/skills/{name}", _make_json_handler(check_auth, _skill_put))
    router.add_post("/keryx/skills", _make_json_handler(check_auth, _skill_post))

    def _prune(request, body):
        return 200, sessions_prune(body)

    router.add_post("/keryx/sessions/prune", _make_json_handler(check_auth, _prune))
