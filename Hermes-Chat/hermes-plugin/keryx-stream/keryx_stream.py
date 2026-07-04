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
import threading
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

    frames: List[Tuple[str, Optional[str]]] = []
    buf: List[str] = []
    stop = False
    for event, text in pending:
        if event == "delta":
            buf.append(text or "")
            continue
        if buf:
            frames.append(("delta", "".join(buf)))
            buf = []
        frames.append((event, text))
        if event == "stop":
            stop = True
            break
    if buf:
        frames.append(("delta", "".join(buf)))
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


def apply_thinking_kwargs(agent) -> None:
    """Map Hermes' reasoning_config onto vLLM's ``chat_template_kwargs.enable_thinking``.

    Called from agent_init after ``_merge_custom_provider_extra_body`` (see install.py).
    Local OpenAI-compatible brains (provider ``custom``/``custom:*``) don't understand the
    OpenRouter-style ``extra_body.reasoning`` dial — thinking is a chat-template switch. Some
    local chat templates additionally force-open the thought channel when enable_thinking is set
    (for tunes that never open it voluntarily), so with this mapping ``/reasoning none`` ⇄ any
    effort level becomes a real on/off for local-brain reasoning. Never raises.
    """
    try:
        # Kill switch for setups whose "custom" endpoint rejects unknown request fields
        # (vLLM, llama.cpp and Ollama's /v1 all accept or ignore chat_template_kwargs, but
        # "custom" can point anywhere): KERYX_THINKING_KWARGS=off in ~/.hermes/.env.
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
