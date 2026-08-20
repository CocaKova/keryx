#!/usr/bin/env python3
"""
Keryx 2.3 — kerykeion icon geometry.

⚠️ 2.5: THIS NO LONGER OWNS THE LAUNCHER ICON. It owns the 24dp notification
glyph (ic_stat_keryx), the flat void background, and colors.xml — nothing else.

The launcher art was monoline here: constant-width, round-capped strokes, exact
and symmetrical and reading as clip art ("those look drawn by little kids"). A
mark needs tapered bodies, a shaped serpent head and structured wings, which is
filled-form work this generator cannot express. The launcher icon is now built
by tools/launcher_icon.py from tools/icon_source/keryx-mark.png.

write_foreground / write_monochrome / write_adaptive / write_rasters are left
intact but are NOT called from __main__ — running them would quietly restore
the old mark over the new one.

The notification glyph stays here on purpose: at status-bar size it is an
alpha-only silhouette whose job is legibility, not brand fidelity, and it is
already authored natively in the 24-unit viewport with a single serpent
crossing because the two-crossing braid collapses into a blob that small.

Originally: single source of geometry for BOTH the adaptive VectorDrawables and
the legacy API 24/25 rasters, so the vector and the PNGs can never drift.

Design space is the 108x108 adaptive canvas (centre 54,54).  Everything is
authored in "design units", then uniformly scaled about the centre by SCALE so
the whole sigil sits inside the 66dp safe circle (radius 33; we stay under ~28).

The kerykeion:
    orb      small filled disc floating above the staff
    wings    one unbroken swept outline per side, mirrored
    staff    single vertical hairline
    serpents two curves, mirror images of each other about x=54, crossing the
             staff (and each other) exactly twice — at (54,57) and (54,70.8)

Curves are Catmull-Rom splines through hand-placed waypoints, converted to
cubic Beziers.  The XML emits those Beziers verbatim; the raster samples the
same Beziers as a dense polyline.  Serpent B / the left wing are produced by
mirroring x -> 108-x, so symmetry is exact by construction.

Run:  python3 Hermes-Chat/tools/kerykeion_icon.py
"""

import math
import os

from PIL import Image, ImageDraw

# Resolved from this file so the generator works from any checkout and any cwd.
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")

# ---------------------------------------------------------------- palette ---
VOID = "#0B0A12"          # matte near-black background
GOLD = "#F0B429"          # gilt, top of gradient
EMBER = "#E55A00"         # ember, bottom of gradient
GOLD_LIGHT = "#FFD98A"    # wings / orb, top
GOLD_LIGHT_2 = "#F0B429"  # wings / orb, bottom

GRAD_Y0, GRAD_Y1 = 24.0, 84.0   # gradient span, canvas units

# ------------------------------------------------------------- geometry -----
SCALE = 0.94   # uniform shrink about (54,54) so max radius ~27.7 < 30

# stroke widths, in FINAL canvas units (not scaled again)
W_STAFF = 2.70
W_SERPENT = 2.15
W_WING = 1.85
R_ORB = 2.60

CX = CY = 54.0

# staff: straight hairline
STAFF = [(54.0, 31.0), (54.0, 83.5)]

# orb: centre + radius, design units (radius scaled with SCALE separately)
ORB_C = (54.0, 27.0)

# right wing — ONE unbroken stroke: up the leading edge from the shoulder, out
# to a swept tip, then back along a concave trailing edge.  A single outlined
# shape reads as a wing at 48px where parallel feather hairlines would smear
# into a blur; the open gap at the shoulder is where it meets the staff.
WING_R = [
    [(55.8, 37.6),   # shoulder, top
     (59.4, 34.0),
     (65.0, 31.7),
     (70.6, 31.8),
     (73.0, 33.6),   # swept tip
     (68.4, 35.2),
     (63.2, 37.4),
     (57.8, 39.6),
     (55.8, 40.4)],  # shoulder, underside
]

# serpent A — head rears up beside the staff under the wings, then the body
# loops left / right / left, crossing the staff (and serpent B) at y=55, y=70.
SERPENT_A = [
    (50.4, 44.6),   # head, rearing up beside the staff under the wings
    (48.6, 47.8),   # neck
    (44.6, 50.6),   # swings out left
    (46.6, 54.4),
    (54.0, 57.0),   # crossing 1 (on the staff, and on serpent B)
    (62.2, 59.6),
    (65.8, 64.2),   # belly of the right loop
    (61.6, 68.4),
    (54.0, 70.8),   # crossing 2
    (46.8, 73.2),
    (44.0, 77.2),   # bottom loop, left
    (49.5, 81.6),   # tail tip
]

# --- 24dp notification small icon -------------------------------------------
# Authored natively in the 24-unit viewport rather than scaled down from the
# launcher art: at status-bar size the two-crossing braid collapses into a
# blob, so this variant keeps ONE crossing (two lobes per serpent) and much
# heavier strokes.  Content stays inside the 2dp keyline (x,y in 2..22).
STAT_STAFF = [(12.0, 4.4), (12.0, 21.2)]
STAT_ORB = ((12.0, 2.9), 1.30)
STAT_WING_R = [[(12.6, 6.4), (15.6, 4.8), (18.8, 4.4), (20.0, 5.7)]]
STAT_SERPENT_A = [
    (10.0, 9.3),    # head
    (6.9, 12.4),    # left lobe
    (12.0, 15.2),   # the single crossing
    (17.1, 17.9),   # right lobe
    (13.8, 20.8),   # tail
]
STAT_W_STAFF, STAT_W_SERPENT, STAT_W_WING = 1.80, 1.50, 1.40


# ------------------------------------------------------------- helpers ------
def mirror(pts):
    """Mirror a point list about x=54 — the icon's axis of symmetry."""
    return [(108.0 - x, y) for (x, y) in pts]


def scale_pt(p, s=SCALE, cx=CX, cy=CY):
    return (cx + (p[0] - cx) * s, cy + (p[1] - cy) * s)


def scale_pts(pts, s=SCALE):
    return [scale_pt(p, s) for p in pts]


def catmull_beziers(pts):
    """Catmull-Rom through `pts` -> list of cubic segments (p0, c1, c2, p1)."""
    if len(pts) == 2:
        p0, p1 = pts
        c1 = (p0[0] + (p1[0] - p0[0]) / 3.0, p0[1] + (p1[1] - p0[1]) / 3.0)
        c2 = (p0[0] + 2 * (p1[0] - p0[0]) / 3.0, p0[1] + 2 * (p1[1] - p0[1]) / 3.0)
        return [(p0, c1, c2, p1)]
    P = [pts[0]] + list(pts) + [pts[-1]]
    segs = []
    for i in range(1, len(P) - 2):
        p0, p1, p2, p3 = P[i - 1], P[i], P[i + 1], P[i + 2]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6.0, p1[1] + (p2[1] - p0[1]) / 6.0)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6.0, p2[1] - (p3[1] - p1[1]) / 6.0)
        segs.append((p1, c1, c2, p2))
    return segs


def fmt(v):
    return f"{round(v, 2):g}"


def path_data(pts):
    """Cubic-Bezier SVG path string for a waypoint list (already scaled)."""
    segs = catmull_beziers(pts)
    (p0, _, _, _) = segs[0]
    d = f"M{fmt(p0[0])},{fmt(p0[1])}"
    for (_, c1, c2, p1) in segs:
        d += (f"C{fmt(c1[0])},{fmt(c1[1])} {fmt(c2[0])},{fmt(c2[1])} "
              f"{fmt(p1[0])},{fmt(p1[1])}")
    return d


def circle_path(c, r):
    """Filled disc as two arcs (VectorDrawable has no <circle>)."""
    x, y = c
    return (f"M{fmt(x - r)},{fmt(y)}"
            f"a{fmt(r)},{fmt(r)} 0 1,0 {fmt(2 * r)},0"
            f"a{fmt(r)},{fmt(r)} 0 1,0 {fmt(-2 * r)},0z")


def sample(pts, px_per_unit, to_px, spacing):
    """
    Polyline (device px) sampled from the same Beziers the vector emits.

    `spacing` matters: PIL quantises draw coordinates to integers, so points
    packed closer than ~1px produce degenerate segments and a frayed edge.
    We sample at a fraction of the stroke width instead and let the discs
    drawn at each point form the stroke.
    """
    segs = catmull_beziers(pts)
    out = []
    for (p0, c1, c2, p1) in segs:
        approx = (math.dist(p0, c1) + math.dist(c1, c2) + math.dist(c2, p1)) * px_per_unit
        n = max(8, int(approx / spacing))
        for i in range(n + 1):
            t = i / n
            u = 1 - t
            x = (u ** 3 * p0[0] + 3 * u * u * t * c1[0]
                 + 3 * u * t * t * c2[0] + t ** 3 * p1[0])
            y = (u ** 3 * p0[1] + 3 * u * u * t * c1[1]
                 + 3 * u * t * t * c2[1] + t ** 3 * p1[1])
            pt = to_px((x, y))
            if not out or math.dist(out[-1], pt) > spacing * 0.5:
                out.append(pt)
    return out


# ---------------------------------------------------------- scaled art ------
def art():
    """Final (scaled) geometry for the 108-canvas, as stroke/fill records."""
    strokes_main = [
        ("staff", scale_pts(STAFF), W_STAFF),
        ("serpentA", scale_pts(SERPENT_A), W_SERPENT),
        ("serpentB", scale_pts(mirror(SERPENT_A)), W_SERPENT),
    ]
    strokes_light = []
    for i, f in enumerate(WING_R):
        strokes_light.append((f"wingR{i}", scale_pts(f), W_WING))
        strokes_light.append((f"wingL{i}", scale_pts(mirror(f)), W_WING))
    orb = (scale_pt(ORB_C), R_ORB)
    return strokes_main, strokes_light, orb


def art_small():
    """Notification-icon geometry, already in the 24-unit viewport."""
    def mir24(pts):
        return [(24.0 - x, y) for (x, y) in pts]

    strokes = [
        (STAT_STAFF, STAT_W_STAFF),
        (STAT_SERPENT_A, STAT_W_SERPENT),
        (mir24(STAT_SERPENT_A), STAT_W_SERPENT),
    ]
    for f in STAT_WING_R:
        strokes.append((f, STAT_W_WING))
        strokes.append((mir24(f), STAT_W_WING))
    return strokes, STAT_ORB


# ------------------------------------------------------------ XML output ----
VEC_HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Keryx kerykeion — the herald\'s staff.  GENERATED by\n'
            '     Hermes-Chat/tools/kerykeion_icon.py, which emits this vector\n'
            '     AND the legacy\n'
            '     API 24/25 rasters from one set of control points.  Edit the\n'
            '     generator and re-run it; editing this file by hand makes the\n'
            '     mipmap PNGs drift out of sync with the adaptive icon. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp"\n'
            '    android:height="108dp"\n'
            '    android:viewportWidth="108"\n'
            '    android:viewportHeight="108">\n')


def grad(attr, c0, c1):
    return (f'        <aapt:attr name="android:{attr}">\n'
            f'            <gradient\n'
            f'                android:type="linear"\n'
            f'                android:startX="54" android:startY="{fmt(GRAD_Y0)}"\n'
            f'                android:endX="54" android:endY="{fmt(GRAD_Y1)}">\n'
            f'                <item android:color="#FF{c0[1:]}" android:offset="0" />\n'
            f'                <item android:color="#FF{c1[1:]}" android:offset="1" />\n'
            f'            </gradient>\n'
            f'        </aapt:attr>\n')


def write_foreground():
    strokes_main, strokes_light, (oc, orb_r) = art()
    out = [VEC_HEAD]
    for name, pts, w in strokes_main:
        out.append(f'    <!-- {name} -->\n'
                   f'    <path\n'
                   f'        android:pathData="{path_data(pts)}"\n'
                   f'        android:fillColor="#00000000"\n'
                   f'        android:strokeWidth="{fmt(w)}"\n'
                   f'        android:strokeLineCap="round"\n'
                   f'        android:strokeLineJoin="round">\n'
                   + grad("strokeColor", GOLD, EMBER) +
                   f'    </path>\n')
    for name, pts, w in strokes_light:
        out.append(f'    <!-- {name} -->\n'
                   f'    <path\n'
                   f'        android:pathData="{path_data(pts)}"\n'
                   f'        android:fillColor="#00000000"\n'
                   f'        android:strokeWidth="{fmt(w)}"\n'
                   f'        android:strokeLineCap="round"\n'
                   f'        android:strokeLineJoin="round">\n'
                   + grad("strokeColor", GOLD_LIGHT, GOLD_LIGHT_2) +
                   f'    </path>\n')
    out.append(f'    <!-- orb -->\n'
               f'    <path android:pathData="{circle_path(oc, orb_r)}">\n'
               + grad("fillColor", GOLD_LIGHT, GOLD_LIGHT_2) +
               f'    </path>\n')
    out.append('</vector>\n')
    write(os.path.join(RES, "drawable/ic_launcher_foreground.xml"), "".join(out))


def write_monochrome():
    strokes_main, strokes_light, (oc, orb_r) = art()
    head = VEC_HEAD.replace('    xmlns:aapt="http://schemas.android.com/aapt"\n', '')
    head = head.replace("Keryx kerykeion.", "Keryx kerykeion, themed-icon layer (Android 13+).")
    out = [head]
    for name, pts, w in strokes_main + strokes_light:
        out.append(f'    <!-- {name} -->\n'
                   f'    <path\n'
                   f'        android:pathData="{path_data(pts)}"\n'
                   f'        android:fillColor="#00000000"\n'
                   f'        android:strokeColor="#FFFFFFFF"\n'
                   f'        android:strokeWidth="{fmt(w)}"\n'
                   f'        android:strokeLineCap="round"\n'
                   f'        android:strokeLineJoin="round" />\n')
    out.append(f'    <!-- orb -->\n'
               f'    <path\n'
               f'        android:pathData="{circle_path(oc, orb_r)}"\n'
               f'        android:fillColor="#FFFFFFFF" />\n')
    out.append('</vector>\n')
    write(os.path.join(RES, "drawable/ic_launcher_monochrome.xml"), "".join(out))


def write_background():
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- Matte void. Keep in sync with @color/ic_launcher_bg. -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="108dp"\n'
           '    android:height="108dp"\n'
           '    android:viewportWidth="108"\n'
           '    android:viewportHeight="108">\n'
           f'    <path\n'
           f'        android:fillColor="{VOID}"\n'
           f'        android:pathData="M0,0h108v108h-108z" />\n'
           '</vector>\n')
    write(os.path.join(RES, "drawable/ic_launcher_background.xml"), xml)


def write_adaptive():
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@drawable/ic_launcher_background" />\n'
           '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
           '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
           '</adaptive-icon>\n')
    write(os.path.join(RES, "mipmap-anydpi-v26/ic_launcher.xml"), xml)
    write(os.path.join(RES, "mipmap-anydpi-v26/ic_launcher_round.xml"), xml)


def write_stat():
    strokes, (oc, orb_r) = art_small()
    out = ['<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- Notification small icon: flat white kerykeion silhouette.\n'
           '     Status-bar icons are alpha-only — no gradient, no colour. -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="24dp"\n'
           '    android:height="24dp"\n'
           '    android:viewportWidth="24"\n'
           '    android:viewportHeight="24"\n'
           '    android:tint="#FFFFFFFF">\n']
    for pts, w in strokes:
        out.append(f'    <path\n'
                   f'        android:pathData="{path_data(pts)}"\n'
                   f'        android:fillColor="#00000000"\n'
                   f'        android:strokeColor="#FFFFFFFF"\n'
                   f'        android:strokeWidth="{fmt(w)}"\n'
                   f'        android:strokeLineCap="round"\n'
                   f'        android:strokeLineJoin="round" />\n')
    out.append(f'    <path\n'
               f'        android:pathData="{circle_path(oc, orb_r)}"\n'
               f'        android:fillColor="#FFFFFFFF" />\n')
    out.append('</vector>\n')
    write(os.path.join(RES, "drawable/ic_stat_keryx.xml"), "".join(out))


def write_colors():
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<resources>\n'
           '    <!-- Matte void behind the gilded kerykeion. Must match\n'
           '         @drawable/ic_launcher_background so the launcher mask\n'
           '         never reveals a seam at the corners. -->\n'
           f'    <color name="ic_launcher_bg">{VOID}</color>\n'
           '</resources>\n')
    write(os.path.join(RES, "values/colors.xml"), xml)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(text)
    print("wrote", path)


# --------------------------------------------------------- raster output ----
SS = 4  # supersample factor; downscaled with LANCZOS


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def gradient_image(size, c0, c1, y0_px, y1_px):
    """Vertical linear gradient as an RGB image."""
    img = Image.new("RGB", (size, size))
    a, b = hexrgb(c0), hexrgb(c1)
    px = img.load()
    span = max(1e-6, y1_px - y0_px)
    for y in range(size):
        t = min(1.0, max(0.0, (y - y0_px) / span))
        col = tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))
        for x in range(size):
            px[x, y] = col
    return img


def stroke_mask(size, px_per_unit, to_px, strokes, disc=None):
    """
    8-bit coverage mask for round-capped, round-joined strokes.

    A round stroke is exactly the union of discs of radius w/2 centred on the
    path, so that is what we draw — overlapping discs spaced at r/2 deviate
    from the true offset curve by ~3% of r, which vanishes in the LANCZOS
    downscale.  Drawing discs (rather than ImageDraw.line) also sidesteps
    PIL's degenerate-thick-segment fraying.
    """
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    for pts, w in strokes:
        r = max(0.5, w * px_per_unit / 2.0)
        poly = sample(pts, px_per_unit, to_px, spacing=max(1.0, r / 2.0))
        d.line(poly, fill=255, width=max(1, int(round(2 * r))), joint=None)
        for (x, y) in poly:
            d.ellipse([x - r, y - r, x + r, y + r], fill=255)
    if disc is not None:
        c, rad = disc
        cx, cy = to_px(c)
        r = rad * px_per_unit
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
    return m


def render_foreground(size):
    """Transparent-background foreground: the full 108 canvas fills `size`."""
    S = size * SS
    ppu = S / 108.0

    def to_px(p):
        return (p[0] * ppu, p[1] * ppu)

    strokes_main, strokes_light, orb = art()
    m_main = stroke_mask(S, ppu, to_px, [(p, w) for _, p, w in strokes_main])
    m_light = stroke_mask(S, ppu, to_px, [(p, w) for _, p, w in strokes_light], disc=orb)

    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    g_main = gradient_image(S, GOLD, EMBER, GRAD_Y0 * ppu, GRAD_Y1 * ppu)
    g_light = gradient_image(S, GOLD_LIGHT, GOLD_LIGHT_2, GRAD_Y0 * ppu, GRAD_Y1 * ppu)
    out.paste(g_main, (0, 0), m_main)
    out.paste(g_light, (0, 0), m_light)
    return out.resize((size, size), Image.LANCZOS)


def render_legacy(size, shape):
    """
    Legacy API 24/25 icon.  The adaptive 108dp canvas maps onto a 72dp visible
    window, so px_per_unit = size/72 and we centre-crop.  `shape` is
    'square' (rounded square) or 'round'.
    """
    S = size * SS
    ppu = S / 72.0
    off = S / 2.0 - 54.0 * ppu   # canvas (54,54) -> image centre

    def to_px(p):
        return (p[0] * ppu + off, p[1] * ppu + off)

    strokes_main, strokes_light, orb = art()
    m_main = stroke_mask(S, ppu, to_px, [(p, w) for _, p, w in strokes_main])
    m_light = stroke_mask(S, ppu, to_px, [(p, w) for _, p, w in strokes_light], disc=orb)

    base = Image.new("RGB", (S, S), hexrgb(VOID))
    base.paste(gradient_image(S, GOLD, EMBER, GRAD_Y0 * ppu + off, GRAD_Y1 * ppu + off),
               (0, 0), m_main)
    base.paste(gradient_image(S, GOLD_LIGHT, GOLD_LIGHT_2, GRAD_Y0 * ppu + off, GRAD_Y1 * ppu + off),
               (0, 0), m_light)

    mask = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(mask)
    if shape == "round":
        d.ellipse([0, 0, S - 1, S - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255)

    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


DENSITIES = [("mdpi", 48, 108), ("hdpi", 72, 162), ("xhdpi", 96, 216),
             ("xxhdpi", 144, 324), ("xxxhdpi", 192, 432)]


def write_rasters():
    for dens, legacy_px, fg_px in DENSITIES:
        d = os.path.join(RES, f"mipmap-{dens}")
        os.makedirs(d, exist_ok=True)
        render_legacy(legacy_px, "square").save(os.path.join(d, "ic_launcher.png"))
        render_legacy(legacy_px, "round").save(os.path.join(d, "ic_launcher_round.png"))
        render_foreground(fg_px).save(os.path.join(d, "ic_launcher_foreground.png"))
        print(f"wrote mipmap-{dens}: {legacy_px}px legacy, {fg_px}px foreground")


def sanity():
    """Assert every drawn point stays inside the 66dp safe circle (r=33)."""
    worst = 0.0
    strokes_main, strokes_light, (oc, orb_r) = art()
    for _, pts, w in strokes_main + strokes_light:
        for (p0, c1, c2, p1) in catmull_beziers(pts):
            for p in (p0, c1, c2, p1):
                worst = max(worst, math.dist(p, (CX, CY)) + w / 2.0)
    worst = max(worst, math.dist(oc, (CX, CY)) + orb_r)
    print(f"max art radius (incl. stroke half-width): {worst:.2f} / safe 33")
    assert worst < 31.0, "art escapes the safe circle"


if __name__ == "__main__":
    # 2.5: the LAUNCHER art no longer comes from here — see the module docstring and
    # tools/launcher_icon.py. write_foreground / write_monochrome / write_adaptive /
    # write_rasters are deliberately NOT called: they still work, and running them would
    # silently overwrite the new mark with the old monoline one. They are kept only so the
    # 24dp notification geometry below still has its helpers.
    sanity()
    write_background()   # flat void — shared by both icons, unchanged
    write_stat()         # the 24dp notification glyph, which is still this file's job
    write_colors()
