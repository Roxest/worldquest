"""Pixel-art atlas for the Endless Tower seal monument (128x128, 1 texel = 1 model unit).

Every region is painted procedurally so the atlas can be regenerated after tweaks:
    python atlas.py  ->  ../tex/seal_tower.png  (+ a 4x preview)
"""
import math
import os
import random

from PIL import Image

SIZE = 128
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "tex", "seal_tower.png")

# name -> (u, v, w, h) in texels
R = {
    "stone": (0, 0, 64, 48),
    "top": (64, 0, 32, 32),
    "spire": (100, 4, 8, 11),        # pyramid-plane triangle, padded by transparent texels (no mip bleed)
    "slate": (112, 0, 16, 16),
    "gold": (64, 32, 16, 16),
    "iron": (80, 32, 16, 16),
    "dark": (96, 32, 16, 16),
    "halo_x": (112, 16, 16, 16),     # crystal glow (crossed planes)
    "base_side": (0, 48, 64, 2),     # plinth base sides: gold lip + dark stone
    "step_side": (0, 50, 64, 2),     # plinth step sides: lit edge + rune band
    "cornice": (0, 52, 64, 2),       # gold bead band (two identical rows: 1-unit bands, no mip mixing)
    "stair_side": (0, 54, 16, 2),
    "seal_dim": (64, 48, 12, 12),
    "seal_glow": (76, 48, 12, 12),
    "seal_bright": (88, 48, 12, 12),
    "hub_dim": (100, 48, 4, 4),
    "hub_glow": (104, 48, 4, 4),
    "hub_bright": (108, 48, 4, 4),
    "ring": (0, 56, 4, 4),
    "ring_side": (4, 56, 4, 3),
    "ring_side_v": (8, 56, 3, 4),
    "bar_h": (11, 56, 5, 1),
    "bar_v": (16, 56, 1, 5),
    "crystal_dim": (32, 56, 8, 8),
    "crystal_glow": (40, 56, 8, 8),
    "win": (48, 56, 4, 6),           # lancet window, glowing
    "win_slit": (52, 56, 2, 5),
    "rune_plate": (54, 56, 6, 6),    # small glowing rune tablet (back face)
    "shard": (60, 56, 4, 4),         # broken seal shard face
    "door": (0, 64, 8, 10),
    "portal": (8, 64, 8, 10),
    "halo": (64, 64, 20, 20),        # stepped glow ring cast on the wall behind the bright seal
    "crack_v": (16, 64, 4, 12),      # buttress fronts
    "crack_seal_a": (20, 64, 5, 5),  # on the seal plate, gem -> rim (upper viewer-left)
    "crack_seal_b": (25, 64, 5, 5),  # on the seal plate, gem -> rim (lower viewer-right)
    "crack_w": (20, 69, 4, 7),       # second floor, beside the window
    "crack_big": (24, 69, 8, 14),    # ground-floor side walls
}

# palette -------------------------------------------------------------------
MORTAR = (44, 37, 60)
STONE = [(96, 85, 120), (88, 78, 112), (104, 93, 130), (92, 82, 117)]
GOLD_HL = (255, 232, 136)
GOLD = (238, 186, 68)
GOLD_DK = (190, 130, 42)
GOLD_SH = (124, 78, 28)
IRON_HL = (146, 154, 176)
IRON = (92, 98, 118)
IRON_DK = (56, 60, 76)
IRON_SH = (34, 36, 48)
VOID = (18, 12, 28)
RUNE_DIM = (112, 78, 170)
RUNE_DIM_DK = (70, 46, 112)
CYAN = (86, 232, 255)
CYAN_HL = (204, 255, 255)
VIOLET = (168, 96, 255)
VIOLET_DK = (98, 44, 188)
WHITE = (246, 255, 255)
SLATE = (74, 62, 112)
SLATE_HL = (104, 90, 150)
SLATE_DK = (46, 38, 76)


def clamp(c):
    return tuple(max(0, min(255, int(x))) for x in c)


def shade(c, d):
    return clamp((c[0] + d, c[1] + d, c[2] + d))


class Atlas:
    def __init__(self):
        self.im = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        self.px = self.im.load()

    def put(self, region, x, y, c, a=255):
        u, v, w, h = R[region]
        if 0 <= x < w and 0 <= y < h:
            self.px[u + x, v + y] = (*clamp(c), a)

    def fill(self, region, c, a=255):
        u, v, w, h = R[region]
        for y in range(h):
            for x in range(w):
                self.px[u + x, v + y] = (*clamp(c), a)

    def draw(self, region, rows, cmap):
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch in cmap:
                    c = cmap[ch]
                    if len(c) == 4:
                        self.put(region, x, y, c[:3], c[3])
                    else:
                        self.put(region, x, y, c)


def paint_stone(A, rng):
    """Ashlar blocks: rows 4 px (3 stone + 1 mortar), blocks 5-8 px, staggered, bevel-lit."""
    u, v, w, h = R["stone"]
    A.fill("stone", MORTAR)
    y = 0
    row = 0
    while y < h:
        x = -rng.randint(0, 5) if row % 2 else -rng.randint(2, 7)
        while x < w:
            bw = rng.choice([5, 6, 6, 7, 8])
            tone = rng.choice(STONE)
            for yy in range(3):
                for xx in range(bw - 1):
                    c = tone
                    if yy == 0:
                        c = shade(tone, 13)
                    elif yy == 2:
                        c = shade(tone, -8)
                    if xx == 0 and yy > 0:
                        c = shade(c, 5)
                    if xx == bw - 2 and yy > 0:
                        c = shade(c, -6)
                    if rng.random() < 0.10:
                        c = shade(c, rng.choice([-10, -7, 8]))
                    A.put("stone", x + xx, y + yy, c)
            x += bw
        y += 4
        row += 1


def paint_top(A, rng):
    """Flagstone slabs 8x8 with mortar grid."""
    A.fill("top", MORTAR)
    u, v, w, h = R["top"]
    for sy in range(0, h, 8):
        for sx in range(0, w, 8):
            off = 4 if (sy // 8) % 2 else 0
            tone = rng.choice(STONE)
            for yy in range(7):
                for xx in range(7):
                    c = shade(tone, 6)
                    if yy == 0 or xx == 0:
                        c = shade(tone, 14)
                    if yy == 6 or xx == 6:
                        c = shade(tone, -4)
                    if rng.random() < 0.08:
                        c = shade(c, rng.choice([-9, 9]))
                    A.put("top", (sx + off + xx) % w, sy + yy, c)


def paint_triangle(A, region, tw, th, rng, slope=None):
    """Roof triangle (apex at top centre) with slate shingles; slightly over-wide so neighbours overlap.
    slope: the plane's real slope length in texels (rows past it are only partly shown)."""
    cx = tw / 2.0
    slope = slope or th
    for y in range(th):
        half = (tw / 2.0) * min(1.0, (y + 1) / slope) + 0.6
        for x in range(tw):
            d = abs(x + 0.5 - cx)
            if d <= half:
                band = y % 3
                c = SLATE
                if band == 2:
                    c = SLATE_DK
                elif band == 0:
                    c = SLATE_HL if (x + y // 3) % 2 == 0 else SLATE
                if rng.random() < 0.06:
                    c = shade(c, 8)
                if y >= th - 1:
                    c = GOLD_DK                        # gold eave line at the base
                A.put(region, x, y, c)


def paint_slate(A, rng):
    for y in range(16):
        for x in range(16):
            c = SLATE_DK if y % 3 == 2 else (SLATE_HL if (x + y // 3) % 3 == 0 else SLATE)
            A.put("slate", x, y, c)


def paint_gold(A, rng):
    for y in range(16):
        for x in range(16):
            c = GOLD
            if y % 4 == 0:
                c = GOLD_HL
            elif y % 4 == 3:
                c = GOLD_DK
            if (x + y) % 7 == 0:
                c = shade(c, 12)
            A.put("gold", x, y, c)


def paint_iron(A, rng):
    for y in range(16):
        for x in range(16):
            r = rng.random()
            c = IRON_DK if r < 0.18 else IRON_HL if r < 0.26 else IRON
            A.put("iron", x, y, c)


def paint_dark(A, rng):
    for y in range(16):
        for x in range(16):
            A.put("dark", x, y, VOID if rng.random() > 0.08 else RUNE_DIM_DK)


def paint_strips(A, rng):
    for x in range(64):
        A.put("base_side", x, 0, GOLD_HL if x % 4 == 0 else GOLD)
        A.put("base_side", x, 1, shade(STONE[1], -12 if x % 6 == 5 else -4))
    glyphs = ["x.x", ".x.", "xx.", ".xx", "x..", "..x"]
    for x in range(64):
        A.put("step_side", x, 0, shade(STONE[2], 16 if x % 5 else 6))
        col = x % 4
        if col == 3:
            c = shade(STONE[1], -10)
        else:
            g = glyphs[(x // 4) % len(glyphs)]
            c = VIOLET if g[col] == "x" else shade(STONE[1], -2)
        A.put("step_side", x, 1, c)
    for x in range(64):
        for y in range(2):
            A.put("cornice", x, y, [GOLD_HL, GOLD, GOLD_DK, GOLD][x % 4])
    for x in range(16):
        for y in range(2):
            A.put("stair_side", x, y, shade(STONE[2], 10 if x % 4 else 0))


def circle_mask(d):
    r = d / 2.0
    m = set()
    for y in range(d):
        for x in range(d):
            dx = x + 0.5 - r
            dy = y + 0.5 - r
            if dx * dx + dy * dy <= (r - 0.15) ** 2 + 0.5:
                m.add((x, y))
    return m


def paint_seal(A, region, mode):
    """12x12 magic-circle seal: bevelled gold rim, dark field, rune ring with N/E/S/W nodes.
    The centre is covered by the keyhole hub. mode: dim | glow | bright."""
    d = 12
    m = circle_mask(d)
    pal = {
        "dim": dict(field=(34, 22, 54), field2=(44, 29, 68), ring=(138, 98, 206), node=(178, 138, 240)),
        "glow": dict(field=(38, 22, 80), field2=(52, 30, 104), ring=CYAN, node=CYAN_HL),
        "bright": dict(field=(150, 98, 250), field2=(150, 98, 250), ring=WHITE, node=WHITE),
    }[mode]
    for (x, y) in m:
        edge = any((x + dx, y + dy) not in m for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        cx, cy = x + 0.5 - 6, y + 0.5 - 6
        dist = math.hypot(cx, cy)
        if edge:
            lit = -cx - cy
            c = GOLD_HL if lit > 4 else (GOLD if lit > -4 else GOLD_DK)
        elif dist > 4.75:
            c = GOLD_SH if mode != "bright" else GOLD_DK
        else:
            c = pal["field2"] if (x * 3 + y) % 5 == 0 and mode != "bright" else pal["field"]
            if abs(dist - 3.55) < 0.6:
                c = pal["ring"]
            if (abs(cx) < 0.6 or abs(cy) < 0.6) and 3.0 < dist < 4.7:
                c = pal["node"]
            if mode == "bright":
                c = WHITE if dist < 3.1 else (CYAN_HL if dist < 3.9 else VIOLET)
                if (abs(cx) < 0.6 or abs(cy) < 0.6) and 3.0 < dist < 4.7:
                    c = WHITE
            if 1.6 < dist < 2.5:
                c = GOLD_DK                              # socket ring around the centre gem
            elif dist <= 1.6:
                c = pal["field"]
            if (abs(abs(cx) - abs(cy)) < 0.6) and 2.6 < dist < 4.3 and mode != "bright":
                c = pal["field2"] if mode == "dim" else pal["ring"]   # diagonal ticks
        A.put(region, x, y, c)


def paint_hub(A, region, mode):
    """5x5 round gold boss with a keyhole; the chains meet under it."""
    key = {"dim": VOID, "glow": CYAN, "bright": WHITE}[mode]
    key2 = {"dim": RUNE_DIM_DK, "glow": CYAN_HL, "bright": CYAN_HL}[mode]
    rows = [".hG.", "hkKG", "GKkd", ".dd."]
    A.draw(region, rows, {"h": GOLD_HL, "G": GOLD, "d": GOLD_DK, "k": key, "K": key2})


def paint_links(A):
    cmap = {"h": IRON_HL, "m": IRON, "d": IRON_DK, "s": IRON_SH}
    A.draw("ring", [".hh.", "h..m", "m..d", ".dd."], cmap)       # face-on link: round ring, 2x2 hole
    A.draw("ring_side", [".hh.", "m..d", ".dd."], cmap)           # edge link seen from above (4 x 2.5)
    A.draw("ring_side_v", [".m.", "h.d", "h.d", ".d."], cmap)     # edge link seen from the side (2.5 x 4)
    A.draw("bar_h", ["dmhmd"], cmap)
    A.draw("bar_v", ["h", "m", "m", "d", "d"], cmap)


def paint_crystal(A, region, glow):
    for y in range(8):
        for x in range(8):
            if glow:
                c = CYAN
                if x < 3 and y < 4:
                    c = CYAN_HL
                if x + y < 3:
                    c = WHITE
                if x >= 5 or y >= 6:
                    c = (116, 150, 255)
                if x == 7 or y == 7:
                    c = VIOLET
            else:
                c = (84, 46, 136)
                if x < 3 and y < 4:
                    c = (124, 80, 186)
                if x + y < 2:
                    c = (168, 126, 226)
                if x >= 5 or y >= 6:
                    c = (58, 30, 98)
                if x == 7 or y == 7:
                    c = (40, 20, 70)
            A.put(region, x, y, c)


def paint_door(A):
    for y in range(10):
        for x in range(8):
            c = (50, 38, 58) if x % 2 == 0 else (44, 33, 51)
            if x in (0, 7):
                c = (34, 25, 40)
            if y in (2, 7):
                c = IRON_DK if x not in (1, 6) else IRON_HL
            A.put("door", x, y, c)
    for (x, y, c) in [(3, 4, RUNE_DIM), (4, 4, RUNE_DIM), (3, 5, RUNE_DIM_DK), (4, 5, RUNE_DIM_DK)]:
        A.put("door", x, y, c)


def paint_portal(A):
    for y in range(10):
        for x in range(8):
            cx, cy = x + 0.5 - 4, (y + 0.5 - 5.5) * 0.8
            dist = math.hypot(cx, cy)
            ang = math.atan2(cy, cx)
            swirl = math.sin(ang * 2 + dist * 1.6)
            if dist < 1.2:
                c = WHITE
            elif dist < 2.2:
                c = CYAN_HL if swirl > -0.2 else CYAN
            elif dist < 3.4:
                c = CYAN if swirl > 0 else VIOLET
            else:
                c = VIOLET if swirl > 0.3 else VIOLET_DK
            A.put("portal", x, y, c)


def paint_windows(A):
    A.draw("win", [".dd.", "dwhd", "dwcd", "dccd", "dcvd", "gGGg"],
           {"d": GOLD_SH, "w": WHITE, "h": CYAN_HL, "c": CYAN, "v": (70, 170, 255), "g": GOLD_DK, "G": GOLD})
    A.draw("win_slit", ["dd", "hd", "cd", "cv", "gg"],
           {"d": GOLD_SH, "h": CYAN_HL, "c": CYAN, "v": (70, 170, 255), "g": GOLD_DK})
    A.draw("rune_plate", ["gGGGGg", "G.cc.G", "Gc..cG", "Gc..cG", "G.cc.G", "gGGGGg"],
           {"g": GOLD_DK, "G": GOLD, "c": CYAN, ".": (34, 22, 56)})


def paint_halo(A):
    """Stepped (not blurred) glow ring: 20x20, alpha in hard steps, eight rays."""
    for y in range(20):
        for x in range(20):
            dist = math.hypot(x + 0.5 - 10, y + 0.5 - 10)
            ang = math.atan2(y + 0.5 - 10, x + 0.5 - 10)
            ray = abs(math.sin(ang * 4)) < 0.2
            if 6.1 < dist <= 7.0:
                A.put("halo", x, y, CYAN_HL, 255)
            elif 7.0 < dist <= 7.7:
                A.put("halo", x, y, CYAN, 150)
            elif 7.7 < dist <= 9.9 and ray:
                A.put("halo", x, y, CYAN_HL if dist < 8.8 else CYAN, 230 if dist < 8.8 else 140)
    for y in range(16):
        for x in range(16):
            dist = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            ang = math.atan2(y + 0.5 - 8, x + 0.5 - 8)
            ray = abs(math.sin(ang * 2)) > 0.93 or abs(math.cos(ang * 2)) > 0.93
            if dist < 2.5:
                continue
            if ray and dist < 7.8:
                A.put("halo_x", x, y, CYAN_HL if dist < 5 else CYAN, 210 if dist < 5 else 130)
            elif dist < 4.2:
                A.put("halo_x", x, y, CYAN, 90)


def paint_cracks(A):
    core, hot, shadow = (200, 134, 255), (248, 228, 255), (26, 17, 38)

    def fissure(region, pts, hot=(), shade_px=()):
        for (x, y) in pts:
            A.put(region, x, y, core)
        for (x, y) in hot:
            A.put(region, x, y, hot_c)
        for (x, y) in shade_px:
            A.put(region, x, y, shadow)

    hot_c = hot
    fissure("crack_v", [(2, 0), (2, 1), (2, 2), (1, 3), (1, 4), (1, 5), (1, 6), (2, 7), (2, 8), (2, 9), (3, 10),
                        (3, 11), (0, 4), (0, 5)], hot=[(1, 4), (2, 8)])
    fissure("crack_w", [(1, 0), (1, 1), (1, 2), (2, 3), (2, 4), (2, 5), (3, 6)], hot=[(2, 4)])
    fissure("crack_big", [(3, 0), (3, 1), (4, 2), (4, 3), (4, 4), (3, 5), (3, 6), (4, 7), (5, 8), (5, 9), (4, 10),
                          (4, 11), (3, 12), (3, 13), (5, 4), (6, 5), (7, 5), (2, 9), (1, 10), (1, 11)],
            hot=[(4, 3), (4, 7), (4, 11)], shade_px=[(5, 2), (2, 6)])
    fissure("crack_seal_a", [(4, 4), (3, 3), (3, 2), (2, 1), (1, 1), (0, 0)], hot=[(3, 3)])
    fissure("crack_seal_b", [(0, 0), (1, 1), (1, 2), (2, 3), (3, 3), (4, 4)], hot=[(1, 1)])


def paint_shard(A):
    A.draw("shard", ["GGGd", "Gcfd", "Gffd", "dddd"],
           {"G": GOLD, "d": GOLD_DK, "c": CYAN, "f": (40, 24, 84)})


def build(out=OUT):
    rng = random.Random(1207)
    A = Atlas()
    paint_stone(A, rng)
    paint_top(A, rng)
    paint_triangle(A, "spire", 8, 11, rng, slope=10.453)
    paint_slate(A, rng)
    paint_gold(A, rng)
    paint_iron(A, rng)
    paint_dark(A, rng)
    paint_strips(A, rng)
    for mode in ("dim", "glow", "bright"):
        paint_seal(A, "seal_" + mode, mode)
        paint_hub(A, "hub_" + mode, mode)
    paint_links(A)
    paint_crystal(A, "crystal_dim", False)
    paint_crystal(A, "crystal_glow", True)
    paint_door(A)
    paint_portal(A)
    paint_windows(A)
    paint_halo(A)
    paint_cracks(A)
    paint_shard(A)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    A.im.save(out)
    A.im.resize((SIZE * 4, SIZE * 4), Image.NEAREST).save(out.replace(".png", "_x4.png"))
    return out


if __name__ == "__main__":
    print(build())
