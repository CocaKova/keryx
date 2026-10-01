#!/usr/bin/env python3
"""Regenerate the README header art (hero-dark.svg, hero-light.svg). Stdlib only.

The diagram is the app's two doors, as the source has them: Matrix (Trixnity, rooms) or
direct to the gateway (JSON-RPC over WS /api/ws + REST), with the keryx-stream plugin's
SSE side-channel carrying live tokens on either door.
"""
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))

THEMES = {
    "dark": dict(bg="#0d1117", panel="#161b22", line="#30363d", text="#e6edf3", dim="#8b949e",
                 accent="#f0883e"),
    "light": dict(bg="#ffffff", panel="#f6f8fa", line="#d0d7de", text="#1f2328", dim="#656d76",
                  accent="#bc4c00"),
}
FONT = "ui-sans-serif, -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif"
MONO = "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace"
ALT = ("Keryx: an Android app that reaches a Hermes gateway through a Matrix homeserver or directly, "
       "with live tokens over the keryx-stream plugin's SSE side-channel")


def box(x, y, w, h, label, sub, c, stroke, mono_sub=False):
    cy = y + h / 2
    sub_font = MONO if mono_sub else FONT
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{c["panel"]}" stroke="{stroke}" stroke-width="1.5"/>'
            f'<text x="{x + w / 2}" y="{cy - 4}" text-anchor="middle" font-family="{FONT}" font-size="17" font-weight="600" fill="{c["text"]}">{label}</text>'
            f'<text x="{x + w / 2}" y="{cy + 17}" text-anchor="middle" font-family="{sub_font}" font-size="12.5" fill="{c["dim"]}">{sub}</text>')


def arrow(x1, y1, x2, y2, color, dashed=False):
    """A line from (x1, y1) to (x2, y2) with a head at the second point, in any direction."""
    a = math.atan2(y2 - y1, x2 - x1)
    bx, by = x2 - 7 * math.cos(a), y2 - 7 * math.sin(a)
    px, py = -math.sin(a) * 5, math.cos(a) * 5
    hx, hy = x2 - 8 * math.cos(a), y2 - 8 * math.sin(a)
    dash = ' stroke-dasharray="5 4"' if dashed else ""
    return (f'<line x1="{x1}" y1="{y1}" x2="{bx:.1f}" y2="{by:.1f}" stroke="{color}" stroke-width="1.8"{dash}/>'
            f'<path d="M{hx + px:.1f},{hy + py:.1f} L{x2},{y2} L{hx - px:.1f},{hy - py:.1f} Z" fill="{color}"/>')


def label(x, y, text, c, color=None, anchor="middle", mono=True):
    return (f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-family="{MONO if mono else FONT}" font-size="12.5" '
            f'fill="{color or c["dim"]}">{text}</text>')


def hero(c):
    W, H = 1200, 340
    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" role="img" aria-label="{ALT}">',
         f'<rect width="{W}" height="{H}" rx="16" fill="{c["bg"]}" stroke="{c["line"]}"/>',
         f'<text x="60" y="78" font-family="{MONO}" font-size="38" font-weight="700" fill="{c["text"]}">keryx</text>',
         f'<text x="60" y="112" font-family="{FONT}" font-size="18" fill="{c["dim"]}">'
         'An Android client for Hermes agents. Over Matrix or straight to the gateway, with tokens streamed live.</text>']
    # columns: app | middle (Matrix above, keryx-stream below) | gateway
    ax, aw = 60, 220          # Keryx app
    mx, mw = 480, 240         # middle column
    gx, gw = 920, 220         # Hermes gateway
    top, bot, h = 150, 248, 62
    s.append(box(ax, top, aw, bot + h - top, "Keryx", "Android · Compose", c, c["accent"]))
    s.append(box(mx, top, mw, h, "Matrix homeserver", "rooms · sync · history", c, c["line"]))
    s.append(box(mx, bot, mw, h, "keryx-stream", "plugin · :8646", c, c["accent"]))
    s.append(box(gx, top, gw, bot + h - top, "Hermes gateway", "the agent", c, c["line"]))
    # Matrix door: app <-> homeserver <-> gateway
    s.append(arrow(ax + aw, top + 22, mx, top + 22, c["dim"]))
    s.append(arrow(mx, top + 42, ax + aw, top + 42, c["dim"]))
    s.append(label((ax + aw + mx) / 2, top + 14, "Matrix door", c, mono=False))
    s.append(arrow(mx + mw, top + 22, gx, top + 22, c["dim"]))
    s.append(arrow(gx, top + 42, mx + mw, top + 42, c["dim"]))
    # direct door: straight across, between the middle boxes
    mid = (top + h + bot) / 2
    s.append(arrow(ax + aw, mid, gx, mid, c["dim"], dashed=True))
    s.append(f'<rect x="{(ax + aw + gx) / 2 - 150}" y="{mid - 11}" width="300" height="22" fill="{c["bg"]}"/>')
    s.append(label((ax + aw + gx) / 2, mid + 4, "direct door: WS /api/ws + REST", c))
    # live tokens: gateway hooks -> keryx-stream -> app
    s.append(arrow(gx, bot + 31, mx + mw, bot + 31, c["accent"]))
    s.append(label((mx + mw + gx) / 2, bot + 52, "plugin hooks", c, color=c["accent"]))
    s.append(arrow(mx, bot + 31, ax + aw, bot + 31, c["accent"]))
    s.append(label((ax + aw + mx) / 2, bot + 52, "SSE: live tokens", c, color=c["accent"]))
    s.append("</svg>")
    return "".join(s)


for theme, colors in THEMES.items():
    with open(os.path.join(HERE, f"hero-{theme}.svg"), "w", encoding="utf-8") as f:
        f.write(hero(colors))
print("ok")
