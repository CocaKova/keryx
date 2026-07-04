#!/usr/bin/env python3
"""Install the Keryx side-channel stream patch into a hermes-agent tree.

REINSTALL-FRAGILE: ``hermes update`` replaces the patched files — re-run this script afterwards.
Idempotent: running it twice is a no-op. Verifies each patched file still compiles.

Usage:
    python3 install.py [--hermes-root ~/.hermes/hermes-agent]

What it changes:
  1. Copies keryx_stream.py  → <root>/gateway/keryx_stream.py       (new module, the hub + SSE handler)
  2. gateway/stream_consumer.py — mirrors deltas/segment-breaks/stop to the hub, and routes the
     "suppress homeserver edits?" decision through keryx_stream.suppress_protocol_edits().
  3. gateway/platforms/api_server.py — registers GET /keryx/stream on the existing API server.

After installing, enable streaming in ~/.hermes/config.yaml (the fallback tier's throttle):
    streaming:
      enabled: true
      edit_interval: 1.2      # seconds between fallback m.replace commits
      buffer_threshold: 60    # or after ~60 chars/tokens
then:  systemctl --user restart hermes-gateway.service
"""

import argparse
import py_compile
import shutil
import sys
from pathlib import Path

MARKER = "keryx_stream"


def patch(path: Path, anchor: str, replacement: str, label: str) -> bool:
    src = path.read_text()
    if replacement.strip().splitlines()[0].strip() in src and MARKER in src:
        # A finer check per-patch happens below via full replacement presence.
        pass
    if replacement in src:
        print(f"  = {label}: already applied")
        return True
    if anchor not in src:
        print(f"  ! {label}: ANCHOR NOT FOUND in {path} — hermes-agent code drifted; patch by hand")
        return False
    path.write_text(src.replace(anchor, replacement, 1))
    print(f"  + {label}: applied")
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--hermes-root", default=str(Path.home() / ".hermes" / "hermes-agent"))
    args = ap.parse_args()
    root = Path(args.hermes_root).expanduser()
    gw = root / "gateway"
    if not gw.is_dir():
        print(f"error: {gw} not found — is --hermes-root correct?")
        return 1

    here = Path(__file__).resolve().parent
    ok = True

    # 1. The hub module.
    shutil.copy2(here / "keryx_stream.py", gw / "keryx_stream.py")
    print(f"  + module: copied keryx_stream.py → {gw / 'keryx_stream.py'}")

    sc = gw / "stream_consumer.py"

    # 2a. Mirror deltas + segment boundary signal (on_delta runs on the agent worker thread;
    #     publish_threadsafe hops onto each subscriber's loop).
    ok &= patch(
        sc,
        anchor=(
            "        if text:\n"
            "            self._queue.put(text)\n"
            "        elif text is None:\n"
            "            self.on_segment_break()\n"
        ),
        replacement=(
            "        if text:\n"
            "            try:\n"
            "                from gateway import keryx_stream as _keryx\n"
            "                _keryx.publish_delta(self.adapter, self.chat_id, text)\n"
            "            except Exception:\n"
            "                pass\n"
            "            self._queue.put(text)\n"
            "        elif text is None:\n"
            "            self.on_segment_break()\n"
        ),
        label="stream_consumer.on_delta delta mirror",
    )

    # 2b. Segment break mirror.
    ok &= patch(
        sc,
        anchor="        self._queue.put(_NEW_SEGMENT)\n",
        replacement=(
            "        try:\n"
            "            from gateway import keryx_stream as _keryx\n"
            "            _keryx.publish_segment(self.adapter, self.chat_id)\n"
            "        except Exception:\n"
            "            pass\n"
            "        self._queue.put(_NEW_SEGMENT)\n"
        ),
        label="stream_consumer.on_segment_break mirror",
    )

    # 2c. Stop signal (finish() is the stream's single completion signal).
    ok &= patch(
        sc,
        anchor="        self._queue.put(_DONE)\n",
        replacement=(
            "        try:\n"
            "            from gateway import keryx_stream as _keryx\n"
            "            _keryx.publish_stop(self.adapter, self.chat_id)\n"
            "        except Exception:\n"
            "            pass\n"
            "        self._queue.put(_DONE)\n"
        ),
        label="stream_consumer.finish stop mirror",
    )

    # 2d. Edit-suppression: subscriber attached → no homeserver edits; Matrix without a
    #     subscriber → throttled m.replace fallback (keryx_stream.FALLBACK_EDITS gates this).
    ok &= patch(
        sc,
        anchor="                if not self.cfg.buffer_only:\n"
               "                    should_edit = should_edit or (\n",
        replacement=(
            "                _keryx_buffer_only = self.cfg.buffer_only\n"
            "                try:\n"
            "                    from gateway import keryx_stream as _keryx\n"
            "                    _keryx_buffer_only = _keryx.suppress_protocol_edits(\n"
            "                        self.adapter, self.chat_id, self.cfg.buffer_only\n"
            "                    )\n"
            "                except Exception:\n"
            "                    pass\n"
            "                if not _keryx_buffer_only:\n"
            "                    should_edit = should_edit or (\n"
        ),
        label="stream_consumer edit-suppression tier switch",
    )

    # 3. SSE + capabilities routes on the API server (ride existing auth + port 8642).
    #    ONE combined block: registering /keryx/stream twice makes aiohttp raise a duplicate-route
    #    error inside the try, which silently killed BOTH routes (live-debugged 2026-07-02).
    api = gw / "platforms" / "api_server.py"
    keryx_routes = (
        "            try:\n"
        "                from gateway.keryx_stream import (\n"
        "                    make_stream_handler,\n"
        "                    make_capabilities_handler,\n"
        "                    make_commands_handler,\n"
        "                )\n"
        '                self._app.router.add_get("/keryx/stream", make_stream_handler(self._check_auth))\n'
        '                self._app.router.add_get("/keryx/capabilities", make_capabilities_handler(self._check_auth))\n'
        '                self._app.router.add_get("/keryx/commands", make_commands_handler(self._check_auth))\n'
        "            except Exception:\n"
        '                logger.debug("keryx routes unavailable", exc_info=True)\n'
    )
    # Older installs to upgrade in place (newest first). Registering the same route twice
    # raises inside the try and silently kills EVERY route in the block, so upgrades must
    # REPLACE the old block, never sit beside it.
    legacy_blocks = [
        (
            "            try:\n"
            "                from gateway.keryx_stream import make_stream_handler, make_capabilities_handler\n"
            '                self._app.router.add_get("/keryx/stream", make_stream_handler(self._check_auth))\n'
            '                self._app.router.add_get("/keryx/capabilities", make_capabilities_handler(self._check_auth))\n'
            "            except Exception:\n"
            '                logger.debug("keryx routes unavailable", exc_info=True)\n'
        ),
        (
            "            try:\n"
            "                from gateway.keryx_stream import make_stream_handler\n"
            '                self._app.router.add_get("/keryx/stream", make_stream_handler(self._check_auth))\n'
            "            except Exception:\n"
            '                logger.debug("keryx stream route unavailable", exc_info=True)\n'
        ),
    ]
    src = api.read_text()
    if keryx_routes in src:
        stale = [b for b in legacy_blocks if b in src]
        if stale:
            for b in stale:
                src = src.replace(b, "", 1)
            api.write_text(src)
            print("  + api_server routes: removed stale legacy block(s)")
        else:
            print("  = api_server routes: already applied")
    else:
        upgraded = False
        for b in legacy_blocks:
            if b in src:
                api.write_text(src.replace(b, keryx_routes, 1))
                print("  + api_server routes: upgraded legacy block to stream+capabilities+commands")
                upgraded = True
                break
        if not upgraded:
            ok &= patch(
                api,
                anchor='            self._app.router.add_get("/health", self._handle_health)\n',
                replacement='            self._app.router.add_get("/health", self._handle_health)\n' + keryx_routes,
                label="api_server keryx routes",
            )

    # 4. Local-brain thinking switch: map reasoning_config → chat_template_kwargs.enable_thinking
    #    for custom providers, right after custom-provider extra_body merging. Makes /reasoning
    #    none ⇄ <level> a real on/off for local vLLM brains (chat templates that force-open think).
    ai = root / "agent" / "agent_init.py"
    ok &= patch(
        ai,
        anchor="    _merge_custom_provider_extra_body(agent, _custom_providers)\n",
        replacement=(
            "    _merge_custom_provider_extra_body(agent, _custom_providers)\n"
            "    try:\n"
            "        from gateway import keryx_stream as _keryx\n"
            "        _keryx.apply_thinking_kwargs(agent)\n"
            "    except Exception:\n"
            "        pass\n"
        ),
        label="agent_init enable_thinking mapping",
    )

    # 5. Reasoning display for streamed turns: the stream consumer's final commit bypasses the
    #    normal send — the only path that prepends the 💭 reasoning block — so enabling
    #    streaming.enabled silently dropped reasoning display for every model. Fold the block
    #    into the streamed message with one final edit at suppression time.
    run = gw / "run.py"
    ok &= patch(
        run,
        anchor=(
            "            if not _is_empty_sentinel and not _transformed and (_streamed or _content_delivered):\n"
            "                logger.info(\n"
        ),
        replacement=(
            "            if not _is_empty_sentinel and not _transformed and (_streamed or _content_delivered):\n"
            "                try:\n"
            "                    from gateway import keryx_stream as _keryx_r\n"
            "                    await _keryx_r.prepend_reasoning_to_streamed(self, source, response, _sc)\n"
            "                except Exception:\n"
            "                    pass\n"
            "                logger.info(\n"
        ),
        label="run.py streamed-turn reasoning fold",
    )

    # 6. Live /steer: steer() only injects into the NEXT tool result, so a steer sent while the
    #    model was generating a text-only final answer sat pending until the whole answer finished,
    #    then ran as a queued follow-up turn ("responds, then keeps going" — 2026-07-03). Break the
    #    token stream at the steer point instead: the partial answer commits normally and the
    #    leftover steer is delivered as the immediate next turn (gateway already handles
    #    result["pending_steer"]). Tool-call generation is left alone — the classic injection path.
    #    The 120-char floor keeps a just-started answer streaming: breaking on the first token
    #    committed junk fragments ("The") as real messages (live-caught 2026-07-04); an answer
    #    that never reaches the floor finishes fast and the steer lands via the leftover path.
    cch = root / "agent" / "chat_completion_helpers.py"
    steer_break_v1 = (
        "            # keryx_stream: a pending /steer during a text-only completion ends the\n"
        "            # stream here — the partial answer commits and the steer becomes the\n"
        "            # immediate next turn instead of waiting out the full answer.\n"
        "            if (\n"
        "                getattr(agent, \"_pending_steer\", None)\n"
        "                and not tool_calls_acc\n"
        "                and content_parts\n"
        "            ):\n"
        "                finish_reason = \"stop\"\n"
        "                break\n"
        "\n"
    )
    steer_break_v2 = (
        "            # keryx_stream: a pending /steer during a text-only completion ends the\n"
        "            # stream here — the partial answer commits and the steer becomes the\n"
        "            # immediate next turn instead of waiting out the full answer. The size\n"
        "            # floor keeps a just-started answer streaming (a first-token break\n"
        "            # committed bare fragments like \"The\" as real messages); short answers\n"
        "            # finish on their own and the steer lands via the leftover path.\n"
        "            if (\n"
        "                getattr(agent, \"_pending_steer\", None)\n"
        "                and not tool_calls_acc\n"
        "                and sum(len(_p) for _p in content_parts) >= 120\n"
        "            ):\n"
        "                finish_reason = \"stop\"\n"
        "                break\n"
        "\n"
    )
    cch_src = cch.read_text()
    if steer_break_v2 in cch_src:
        print("  = chat_completion_helpers live-steer stream break: already applied")
    elif steer_break_v1 in cch_src:
        cch.write_text(cch_src.replace(steer_break_v1, steer_break_v2, 1))
        print("  + chat_completion_helpers live-steer stream break: upgraded v1 → v2 (120-char floor)")
    else:
        ok &= patch(
            cch,
            anchor=(
                "            if agent._interrupt_requested:\n"
                "                break\n"
                "\n"
                "            if not chunk.choices:"
            ),
            replacement=(
                "            if agent._interrupt_requested:\n"
                "                break\n"
                "\n"
                + steer_break_v2
                + "            if not chunk.choices:"
            ),
            label="chat_completion_helpers live-steer stream break",
        )

    # 7. Matching ack wording (the old text promised "after the next tool call", which is no
    #    longer the only landing point). Keep the ⏩ prefix — Keryx folds it as telemetry.
    ok &= patch(
        run,
        anchor='                        return f"⏩ Steer queued — arrives after the next tool call: \'{preview}\'"',
        replacement='                        return f"⏩ Steering — lands at the next tool call or pause: \'{preview}\'"',
        label="run.py steer ack wording",
    )

    # Sanity: everything still compiles.
    for f in (gw / "keryx_stream.py", sc, api, ai, run, cch):
        try:
            py_compile.compile(str(f), doraise=True)
        except py_compile.PyCompileError as e:
            print(f"  ! COMPILE FAILED for {f}: {e}")
            ok = False

    print("\nDone." if ok else "\nFinished WITH ERRORS — review before restarting the gateway.")
    if ok:
        print("Next: ensure config.yaml streaming block (enabled: true, edit_interval: 1.2,")
        print("buffer_threshold: 60), then: systemctl --user restart hermes-gateway.service")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
