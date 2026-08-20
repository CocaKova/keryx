#!/usr/bin/env python3
"""
Keryx 2.5 — launcher icon generator.

2.5.2: the mark is now THE ECLIPSE — a void sigil knocked out of a bone field — replacing the
kerykeion. Jonny's call. The adaptive layers map onto it exactly: the background IS the bone
field (flat, full-bleed, so the launcher's mask decides the silhouette), and the foreground is
the void sigil alone. Masked to a circle or a squircle, that composes back into the mark as
drawn. Geometry comes from tools/icon_source/eclipse-sigil.svg, the same vector master as the
standalone asset, so the icon and the mark can never drift.

Single source for the adaptive icon layers AND the legacy API 24/25 rasters, so
the two can never drift.  Run:  python3 Hermes-Chat/tools/launcher_icon.py

WHY THIS REPLACED THE BEZIER GENERATOR
--------------------------------------
Until 2.5 the launcher art came from tools/kerykeion_icon.py, which plotted the
kerykeion as constant-width, round-capped monoline strokes.  That geometry was
exact and symmetrical and it looked like clip art — Jonny, on device: "those
look drawn by little kids".  The failure was structural, not a matter of
tuning: a real mark needs tapered bodies, a shaped serpent head and wings with
internal structure, which is filled-form work rather than traced lines.

So the art comes from a vector master (tools/icon_source/eclipse-sigil.svg), and
this script does everything downstream of it deterministically.
kerykeion_icon.py survives, but ONLY as the source of the 24dp notification
glyph — see its header.  Nothing writes the launcher art but this file.

WHAT IT DOES
------------
  1. rasterise the sigil's subpaths to a void-on-transparent mark
  2. crop to the mark, scale it against THE DISC (see MARK_ON_DISC below)
  3. emit the foreground PNGs, the monochrome silhouette, and the legacy
     rounded-square / circular rasters
"""

import os

import cv2
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
SOURCE = os.path.join(HERE, "icon_source", "eclipse-sigil.svg")

VOID = (0x0B, 0x0A, 0x12)
BONE = (0xED, 0xE6, 0xD6)
BONE_HEX = "#EDE6D6"

# ⚠️ THE MARK IS SIZED AGAINST THE DISC, NOT THE CANVAS.
#
# The eclipse is a void sigil knocked out of a bone DISC, and the bone margin
# around the sigil is part of the mark — without it you get a dark blob, not an
# eclipse. In the approved master the sigil's longest side is 633.4 of the 1024
# disc, and the standalone raster measures the same, so that ratio is the thing
# to preserve everywhere the mark appears.
MARK_ON_DISC = 0.619

# In an adaptive icon the launcher's mask IS the disc, and that mask falls on the
# central 72dp of the 108dp foreground canvas — so the canvas is 1.5x wider than
# the disc and the ratio has to be converted before use.
#
# ⚠️ 2.5.2 shipped `SAFE = 0.62` applied straight to the 108dp canvas, i.e. the
# disc ratio spent against the wrong reference. That is 1.5x too large: the mark
# overran the 66dp safe circle, the mask ate the point off the blade, and the
# bone margin vanished. Do not "simplify" these two constants back into one —
# they are different denominators, not a redundant pair.
VISIBLE = 72.0 / 108.0
SAFE = MARK_ON_DISC * VISIBLE      # 0.413 of the 108dp adaptive canvas

# The legacy raster gets masked to fill its whole tile, so there the tile IS the
# disc and the ratio applies unconverted.
LEGACY_SAFE = MARK_ON_DISC

# (density, legacy px, adaptive foreground px) — the legacy 108dp canvas maps
# onto a 72dp visible window, hence the two different sizes.
DENSITIES = [("mdpi", 48, 108), ("hdpi", 72, 162), ("xhdpi", 96, 216),
             ("xxhdpi", 144, 324), ("xxxhdpi", 192, 432)]

SS = 4  # supersample for the mask edges, downscaled with LANCZOS


def sigil_paths():
    """The eclipse sigil's subpaths, in the SVG's own 1024 viewBox coordinates."""
    import re
    d = re.search(r'<path[^>]*d="([^"]+)"', open(SOURCE).read()).group(1)
    subs, cur = [], []
    for tok in re.finditer(r"([MLZ])([^MLZ]*)", d):
        cmd, args = tok.group(1), tok.group(2).strip()
        if cmd in "ML":
            n = [float(x) for x in re.findall(r"-?\d+\.?\d*", args)]
            if cmd == "M" and cur:
                subs.append(np.array(cur)); cur = []
            cur.append(n[:2])
        elif cur:
            subs.append(np.array(cur)); cur = []
    if cur:
        subs.append(np.array(cur))
    return subs


def keyed_mark(px=1024):
    """The void sigil on transparency, cropped to its own bounds — the adaptive FOREGROUND."""
    subs = sigil_paths()
    sc = px / 1024.0
    m = np.zeros((px, px), np.uint8)
    cv2.fillPoly(m, [(sp * sc).astype(np.int32) for sp in subs], 255)
    out = np.zeros((px, px, 4), np.uint8)
    out[m > 0] = (*VOID, 255)
    img = Image.fromarray(out, "RGBA")
    return img.crop(img.getchannel("A").point(lambda v: 255 if v > 24 else 0).getbbox())


def fitted(mark, canvas_px, safe=SAFE):
    """[mark] scaled to occupy `safe` of a `canvas_px` square, centred, on transparency."""
    target = canvas_px * safe
    s = min(target / mark.width, target / mark.height)
    m = mark.resize((max(1, round(mark.width * s)), max(1, round(mark.height * s))), Image.LANCZOS)
    out = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    out.paste(m, ((canvas_px - m.width) // 2, (canvas_px - m.height) // 2), m)
    return out


def silhouette(rgba):
    """White-on-transparent silhouette — what the themed-icon layer is tinted from."""
    a = rgba.getchannel("A")
    out = Image.new("RGBA", rgba.size, (255, 255, 255, 0))
    out.putalpha(a)
    return out


def legacy(mark, size, shape):
    """Pre-adaptive icon: the mark on the bone field, masked to a rounded square or a circle."""
    S = size * SS
    base = Image.new("RGBA", (S, S), BONE + (255,))
    # No 72/108 conversion here: this tile is the disc itself. See MARK_ON_DISC.
    fg = fitted(mark, S, safe=LEGACY_SAFE)
    base.paste(fg, (0, 0), fg)
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    if shape == "round":
        d.ellipse([0, 0, S - 1, S - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.235), fill=255)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(base, (0, 0), m)
    return out.resize((size, size), Image.LANCZOS)


def write_background_xml():
    """The bone field. Owned here as of 2.5.2 — kerykeion_icon.py used to write a void square,
    and the two generators writing one file is how an icon silently reverts."""
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- GENERATED by Hermes-Chat/tools/launcher_icon.py — do not hand-edit.\n'
           '     The bone field the eclipse is knocked out of. Keep @color/ic_launcher_bg in sync. -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="108dp" android:height="108dp"\n'
           '    android:viewportWidth="108" android:viewportHeight="108">\n'
           f'    <path android:fillColor="{BONE_HEX}" android:pathData="M0,0h108v108h-108z" />\n'
           '</vector>\n')
    p = os.path.join(RES, "drawable/ic_launcher_background.xml")
    with open(p, "w") as fh:
        fh.write(xml)
    print("wrote drawable/ic_launcher_background.xml (bone)")
    c = os.path.join(RES, "values/colors.xml")
    with open(c, "w") as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                 '    <!-- GENERATED by tools/launcher_icon.py. The bone field behind the eclipse;\n'
                 '         must match @drawable/ic_launcher_background or the mask reveals a seam. -->\n'
                 f'    <color name="ic_launcher_bg">{BONE_HEX}</color>\n</resources>\n')
    print("wrote values/colors.xml (bone)")


def write_adaptive_xml():
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- GENERATED by Hermes-Chat/tools/launcher_icon.py — do not hand-edit. -->\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@drawable/ic_launcher_background" />\n'
           '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
           '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n'
           '</adaptive-icon>\n')
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        p = os.path.join(RES, "mipmap-anydpi-v26", name)
        with open(p, "w") as fh:
            fh.write(xml)
        print("wrote", os.path.relpath(p, RES))


def main():
    mark = keyed_mark()
    print(f"mark keyed: {mark.size[0]}x{mark.size[1]}")
    for dens, legacy_px, fg_px in DENSITIES:
        d = os.path.join(RES, f"mipmap-{dens}")
        os.makedirs(d, exist_ok=True)
        fg = fitted(mark, fg_px)
        fg.save(os.path.join(d, "ic_launcher_foreground.png"))
        silhouette(fg).save(os.path.join(d, "ic_launcher_monochrome.png"))
        legacy(mark, legacy_px, "square").save(os.path.join(d, "ic_launcher.png"))
        legacy(mark, legacy_px, "round").save(os.path.join(d, "ic_launcher_round.png"))
        print(f"wrote mipmap-{dens}: {fg_px}px foreground + monochrome, {legacy_px}px legacy pair")
    write_adaptive_xml()
    write_background_xml()

    # The vector foreground/monochrome are gone; leaving them would let a stale
    # @drawable/… reference resurrect the old mark.
    for stale in ("drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml"):
        p = os.path.join(RES, stale)
        if os.path.exists(p):
            os.remove(p)
            print("removed stale", stale)


if __name__ == "__main__":
    main()
