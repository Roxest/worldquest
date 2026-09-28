"""Master geometry for the Endless Tower seal monument (Java block/item model, MC 1.21.1 rules).

Design coordinates: x/z centred on 8, front = -Z (north face; Blockbench 'front' view; at ItemDisplay yaw 0
the ItemDisplayRenderer's 180-degree Y turn makes it look south). Every cube is shifted by DY on output so the
whole monument is vertically centred on the item centre (8, 8, 8).

Rules kept here: one rotation axis per cube, angles in {-45, -22.5, 0, 22.5, 45}, all from/to within -16..32.
"""
import math

from atlas import R

import os

TEX = "seal_tower"
VARIANT = os.environ.get("WQ_VARIANT", "B")   # B: seal in front, chains behind | F: chains cross in front
DY = -4.5          # global vertical shift applied on output
YC = 3.0           # seal centre y (design coords)
if VARIANT == "B":
    SEAL_Z = -4.6      # seal plate z (front face of the seal)
    Z_A = Z_B = -3.0   # both chain diagonals meet behind the seal
else:
    SEAL_Z = -2.6
    Z_A = -4.0         # chain diagonal A ("\" seen from the front) centre plane
    Z_B = -3.2         # chain diagonal B ("/") centre plane, behind A
HUB_Z = (-5.3, -4.3)
LINK_W = 4.0       # flat link height (ring 4x4, hole 2x2)
SPIRE_BASE = 19.0
SPIRE_HALF = 4.0
TILT = 22.5
SPIRE_L = SPIRE_HALF / math.tan(math.radians(TILT)) / math.cos(math.radians(TILT))   # slope length 10.45
SPIRE_APEX = SPIRE_BASE + SPIRE_HALF / math.tan(math.radians(TILT))                  # 28.66
NEEDLE_TOP = SPIRE_APEX + 1.6
CRYSTAL_Y = NEEDLE_TOP + 2.45
LINK_LEN = 4.0
LINK_PITCH = 3.0

FACES = ("north", "east", "south", "west", "up", "down")


def fsize(fr, to, d):
    dx, dy, dz = (to[i] - fr[i] for i in range(3))
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}[d]


def reg(name, fw=None, fh=None, off=(0, 0)):
    """UV rect inside a region, sized to the face (1 texel per unit)."""
    u, v, w, h = R[name]
    fw = w if fw is None else fw
    fh = h if fh is None else fh
    ou = max(0, min(off[0], w - fw))
    ov = max(0, min(off[1], h - fh))
    return [u + ou, v + ov, u + ou + fw, v + ov + fh]


def planar(name, d, fr, to):
    """World-planar mapping (continuous across neighbouring cubes). Stone for sides, 'top' for up/down."""
    x0, y0, z0 = fr
    x1, y1, z1 = to
    u, v, w, h = R[name]
    if d == "north":
        a0, a1, b0, b1 = 21 - x1, 21 - x0, 33 - y1, 33 - y0
    elif d == "south":
        a0, a1, b0, b1 = x0 + 5, x1 + 5, 33 - y1, 33 - y0
    elif d == "east":
        a0, a1, b0, b1 = 21 - z1, 21 - z0, 33 - y1, 33 - y0
    elif d == "west":
        a0, a1, b0, b1 = z0 + 6, z1 + 6, 33 - y1, 33 - y0
    else:  # up / down use the flagstone region
        a0, a1, b0, b1 = x0 + 5, x1 + 5, z0 + 6, z1 + 6
    assert 0 <= a0 <= a1 <= w and 0 <= b0 <= b1 <= h, (name, d, fr, to, (a0, a1, b0, b1))
    return [u + a0, v + b0, u + a1, v + b1]


class Model:
    def __init__(self):
        self.cubes = []
        self.groups = []

    def group(self, name):
        if name not in self.groups:
            self.groups.append(name)
        return name

    def cube(self, name, group, fr, to, faces, rot=None, origin=None):
        """faces: dict dir -> uv list (missing dirs are disabled). rot: (axis, angle)."""
        self.group(group)
        fr = [float(fr[0]), float(fr[1]), float(fr[2])]
        to = [float(to[0]), float(to[1]), float(to[2])]
        for i in range(3):
            assert fr[i] <= to[i], (name, fr, to)
        fcs = {}
        for d in FACES:
            uv = faces.get(d)
            fcs[d] = {"texture": None} if uv is None else {"uv": [round(x, 4) for x in uv], "texture": TEX}
        c = {"name": name, "group": group, "from": fr, "to": to, "faces": fcs}
        if rot:
            axis, ang = rot
            assert ang in (-45, -22.5, 0, 22.5, 45), (name, ang)
            r = [0.0, 0.0, 0.0]
            r["xyz".index(axis)] = float(ang)
            c["rotation"] = r
            c["origin"] = [float(o) for o in (origin or [(fr[i] + to[i]) / 2 for i in range(3)])]
        self.cubes.append(c)
        return c

    def box(self, name, group, fr, to, side=None, top=None, bottom=None, skip=(), off=(0, 0), rot=None,
            origin=None):
        """side/top/bottom: 'planar' | region name. Faces in skip are disabled."""
        faces = {}
        for d in FACES:
            if d in skip:
                continue
            spec = top if d == "up" else bottom if d == "down" else side
            if spec is None:
                continue
            if spec == "planar":
                faces[d] = planar("stone" if d not in ("up", "down") else "top", d, fr, to)
            else:
                fw, fh = fsize(fr, to, d)
                faces[d] = reg(spec, fw, fh, off)
        return self.cube(name, group, fr, to, faces, rot=rot, origin=origin)

    def decal(self, name, group, plane, rect, uv, facing):
        """Zero-thickness decal. plane: fixed coordinate; rect: ((a0,b0),(a1,b1)) in the other two axes
        (x,y for north/south; z,y for east/west); facing: the visible side."""
        (a0, b0), (a1, b1) = rect
        if facing in ("north", "south"):
            fr, to = (a0, b0, plane), (a1, b1, plane)
        else:
            fr, to = (plane, b0, a0), (plane, b1, a1)
        return self.cube(name, group, fr, to, {facing: uv})


def link_faces(kind, axis):
    """Faces for a chain link. kind flat|edge; axis h (long along x) | v (long along y)."""
    if kind == "flat":       # 4 x 4 x 1, ring faces the front
        return {"north": reg("ring"), "south": reg("ring"), "up": reg("bar_h", 4, 1), "down": reg("bar_h", 4, 1),
                "east": reg("bar_v", 1, 4), "west": reg("bar_v", 1, 4)}
    if axis == "h":          # 4 x 1 x 2.5, seen edge-on from the front
        return {"north": reg("bar_h", 4, 1), "south": reg("bar_h", 4, 1), "up": reg("ring_side", 4, 2.5),
                "down": reg("ring_side", 4, 2.5), "east": reg("iron", 2.5, 1), "west": reg("iron", 2.5, 1)}
    return {"north": reg("bar_v", 1, 4), "south": reg("bar_v", 1, 4), "east": reg("ring_side_v", 2.5, 4),
            "west": reg("ring_side_v", 2.5, 4), "up": reg("iron", 1, 2.5), "down": reg("iron", 1, 2.5)}


def build():
    M = Model()

    # ================= plinth (base has a front apron that carries the lower chain anchors) =================
    g = "plinth"
    M.box("plinth_base", g, (-4, -9, -6), (20, -7, 20), side="base_side", top="planar", bottom="planar")
    M.box("plinth_step", g, (-2.5, -7, -2.5), (18.5, -5, 18.5), side="step_side", top="planar", skip=("down",),
          off=(7, 0))
    M.box("stair", g, (4.5, -7, -3.5), (11.5, -6, -2.5), side="stair_side", top="planar", skip=("down", "south"))

    # ================= tower =================
    g = "spire"
    M.box("gf_back", g, (0, -5, 2), (16, 10, 16), side="planar", skip=("north", "up", "down"))
    M.box("gf_front_w", g, (0, -5, 0), (4, 10, 2), side="planar", skip=("south", "up", "down"))
    M.box("gf_front_e", g, (12, -5, 0), (16, 10, 2), side="planar", skip=("south", "up", "down"))
    M.box("gf_front_top", g, (4, 5, 0), (12, 10, 2), side="planar", bottom="planar",
          skip=("south", "up", "east", "west"))
    # door frame: gold jambs + pointed pediment (hidden behind the seal until it shatters)
    M.box("jamb_w", g, (3.25, -5, -0.5), (4.25, 5, 0.5), side="gold", top="gold", skip=("down", "south"))
    M.box("jamb_e", g, (11.75, -5, -0.5), (12.75, 5, 0.5), side="gold", top="gold", skip=("down", "south"))
    beam = math.hypot(4, 4)
    M.box("arch_w", g, (3.5, 4.5, -0.5), (4 + beam + 0.5, 5.5, 0.5), side="gold", top="gold", bottom="gold",
          skip=("south",), rot=("z", 45), origin=(4, 5, 0))
    M.box("arch_e", g, (12 - beam - 0.5, 4.5, -0.5), (12.5, 5.5, 0.5), side="gold", top="gold", bottom="gold",
          skip=("south",), rot=("z", -45), origin=(12, 5, 0))

    # corner buttresses: tall, gold-banded caps, slate pinnacles, gold finials
    for tag, bx, bz in (("nw", 0, 0), ("ne", 16, 0), ("sw", 0, 16), ("se", 16, 16)):
        M.box(f"buttress_{tag}", g, (bx - 2, -5, bz - 2), (bx + 2, 13.5, bz + 2), side="planar", top="planar",
              skip=("down",))
        M.box(f"cap_{tag}", g, (bx - 1.5, 13.5, bz - 1.5), (bx + 1.5, 14.5, bz + 1.5), side="cornice", top="top",
              skip=("down",))
        M.box(f"pinnacle_{tag}", g, (bx - 1, 14.5, bz - 1), (bx + 1, 18, bz + 1), side="slate", top="slate",
              skip=("down",))
        M.box(f"tip_{tag}", g, (bx - 0.5, 18, bz - 0.5), (bx + 0.5, 20, bz + 0.5), side="gold", top="gold",
              skip=("down",))

    M.box("cornice_1", g, (-0.5, 10, -0.5), (16.5, 11, 16.5), side="cornice", top="planar", bottom="gold")
    M.box("floor_2", g, (2, 11, 2), (14, 18, 14), side="planar", skip=("up", "down"))
    M.box("cornice_2", g, (1.5, 18, 1.5), (14.5, 19, 14.5), side="cornice", top="planar", bottom="gold")
    for tag, px, pz in (("nw", 2.5, 2.5), ("ne", 13.5, 2.5), ("sw", 2.5, 13.5), ("se", 13.5, 13.5)):
        M.box(f"spike_{tag}", g, (px - 0.75, 19, pz - 0.75), (px + 0.75, 21.5, pz + 0.75), side="slate", top="gold",
              skip=("down",))
        M.box(f"spike_tip_{tag}", g, (px - 0.3, 21.5, pz - 0.3), (px + 0.3, 22.5, pz + 0.3), side="gold", top="gold",
              skip=("down",))

    wv = reg("win")
    wrect = ((6, 11.5), (10, 17.5))
    M.decal("win_n", g, 1.9, wrect, wv, "north")
    M.decal("win_s", g, 14.1, wrect, wv, "south")
    M.decal("win_e", g, 14.1, wrect, wv, "east")
    M.decal("win_w", g, 1.9, wrect, wv, "west")
    sv = reg("win_slit")
    M.decal("slit_e", g, 16.1, ((7, 1), (9, 6)), sv, "east")
    M.decal("slit_w", g, -0.1, ((7, 1), (9, 6)), sv, "west")
    M.decal("rune_back", g, 16.1, ((5, 0), (11, 6)), reg("rune_plate"), "south")

    # spire: stepped core (backing) + four tilted triangle planes = a true square pyramid
    M.box("spire_core_1", g, (5, SPIRE_BASE, 5), (11, SPIRE_BASE + 3, 11), side="slate", top="slate",
          skip=("down",))
    M.box("spire_core_2", g, (6, SPIRE_BASE + 3, 6), (10, SPIRE_BASE + 6, 10), side="slate", top="slate",
          skip=("down",))
    M.box("spire_core_3", g, (7, SPIRE_BASE + 6, 7), (9, SPIRE_BASE + 8.5, 9), side="slate", top="slate",
          skip=("down",))
    tri = [R["spire"][0], R["spire"][1], R["spire"][0] + 8, R["spire"][1] + SPIRE_L]
    lo, hi = 8 - SPIRE_HALF, 8 + SPIRE_HALF
    top = SPIRE_BASE + SPIRE_L
    M.cube("spire_n", g, (lo, SPIRE_BASE, lo), (hi, top, lo), {"north": tri}, rot=("x", TILT),
           origin=(8, SPIRE_BASE, lo))
    M.cube("spire_s", g, (lo, SPIRE_BASE, hi), (hi, top, hi), {"south": tri}, rot=("x", -TILT),
           origin=(8, SPIRE_BASE, hi))
    M.cube("spire_w", g, (lo, SPIRE_BASE, lo), (lo, top, hi), {"west": tri}, rot=("z", -TILT),
           origin=(lo, SPIRE_BASE, 8))
    M.cube("spire_e", g, (hi, SPIRE_BASE, lo), (hi, top, hi), {"east": tri}, rot=("z", TILT),
           origin=(hi, SPIRE_BASE, 8))
    M.box("finial", g, (7.5, SPIRE_APEX - 0.6, 7.5), (8.5, SPIRE_APEX + 0.4, 8.5), side="gold", top="gold",
          skip=("down",))
    M.box("needle", g, (7.75, SPIRE_APEX + 0.4, 7.75), (8.25, NEEDLE_TOP, 8.25), side="gold", top="gold",
          skip=("down",))

    # ================= door states =================
    M.decal("door", "door_closed", 1.95, ((4, -5), (12, 5)), reg("door"), "north")
    M.decal("portal_plane", "portal", 1.95, ((4, -5), (12, 5)), reg("portal"), "north")

    # ================= seal =================
    body = [(12, 4), (10, 8), (8, 10), (4, 12)]      # pixel circle d=12 from four stepped cubes
    for i, (w, h) in enumerate(body):
        M.box(f"seal_body_{i}", "seal_body", (8 - w / 2, YC - h / 2, SEAL_Z + 0.1), (8 + w / 2, YC + h / 2, 0),
              side="gold", top="gold", bottom="gold", skip=("north", "south"))
    plate_rect = ((2, YC - 6), (14, YC + 6))
    hub_fr, hub_to = (6, YC - 2, HUB_Z[0]), (10, YC + 2, HUB_Z[1])
    for mode in ("dim", "glow", "bright"):
        grp = "seal_" + mode
        M.decal(f"seal_plate_{mode}", grp, SEAL_Z, plate_rect, reg("seal_" + mode), "north")
        gem = {"dim": "crystal_dim", "glow": "crystal_glow", "bright": "crystal_glow"}[mode]
        ga = 1.6 if mode != "bright" else 1.8
        M.box(f"seal_gem_{mode}", grp, (8 - ga, YC - ga, SEAL_Z - 1.4), (8 + ga, YC + ga, SEAL_Z + 0.3),
              side=gem, top=gem, bottom=gem, skip=("south",), rot=("z", 45), origin=(8, YC, SEAL_Z))
        if VARIANT == "F":
            M.cube(f"hub_{mode}", grp, hub_fr, hub_to,
                   {"north": reg("hub_" + mode), "east": reg("gold", 1, 4), "west": reg("gold", 1, 4),
                    "up": reg("gold", 4, 1), "down": reg("gold", 4, 1)})
    M.decal("seal_halo", "seal_bright", -2.2, ((-2, YC - 10), (18, YC + 10)), reg("halo"), "north")

    # ================= chains =================
    # four arms from the hub to the anchors; viewer-left = +X (camera looks at the -Z face)
    UP_D, LO_D = 9.0, 9.5            # anchor offsets (x and y) from the seal centre
    arms = {
        "chain_1": dict(sgn=+1, ang=45, dist=UP_D * math.sqrt(2), z=Z_A, anchor=(8 + UP_D, YC + UP_D)),   # upper, viewer-left
        "chain_2": dict(sgn=-1, ang=45, dist=LO_D * math.sqrt(2), z=Z_A, anchor=(8 - LO_D, YC - LO_D)),   # lower, viewer-right
        "chain_3": dict(sgn=-1, ang=-45, dist=UP_D * math.sqrt(2), z=Z_B, anchor=(8 - UP_D, YC + UP_D)),  # upper, viewer-right
        "chain_4": dict(sgn=+1, ang=-45, dist=LO_D * math.sqrt(2), z=Z_B, anchor=(8 + LO_D, YC - LO_D)),  # lower, viewer-left
    }

    def arm_link(name, group, a, s0, s1, kind):
        sgn, ang, zc = a["sgn"], a["ang"], a["z"]
        x0, x1 = (8 + s0, 8 + s1) if sgn > 0 else (8 - s1, 8 - s0)
        if kind == "flat":
            fr, to = (x0, YC - LINK_W / 2, zc - 0.5), (x1, YC + LINK_W / 2, zc + 0.5)
        else:
            fr, to = (x0, YC - 0.5, zc - 1.25), (x1, YC + 0.5, zc + 1.25)
        M.cube(name, group, fr, to, link_faces(kind, "h"), rot=("z", ang), origin=(8, YC, zc))

    def arm_segments(a):
        """Links from the anchor inwards at a fixed pitch; variant B stops once hidden behind the seal."""
        end = a["dist"] + 0.4
        segs = []
        i = 0
        while True:
            s1 = end - i * LINK_PITCH
            s0 = s1 - LINK_LEN
            if VARIANT == "B" and s1 < 6.5:
                break
            if VARIANT == "F" and s1 < 2.0:
                break
            segs.append((max(s0, 0.3), s1))
            i += 1
        segs.reverse()
        n = len(segs)
        # the link on the anchor is always a flat ring; alternate inwards
        return [(s0, s1, "flat" if (n - 1 - j) % 2 == 0 else "edge") for j, (s0, s1) in enumerate(segs)]

    for arm, a in arms.items():
        for i, (s0, s1, k) in enumerate(arm_segments(a)):
            arm_link(f"{arm}_link{i}", arm, a, s0, s1, k)

    # anchors (always present): eye bolts on the buttress fronts, gold staples on the plinth apron
    for arm in ("chain_1", "chain_3"):
        ax, ay = arms[arm]["anchor"]
        M.box(f"anchor_plate_{arm[-1]}", "anchors", (ax - 1.25, ay - 1.25, -2.5), (ax + 1.25, ay + 1.25, -2),
              side="gold", top="gold", bottom="gold", skip=("south",))
        M.box(f"anchor_eye_{arm[-1]}", "anchors", (ax - 0.75, ay - 0.75, min(Z_A, Z_B) - 1.5),
              (ax + 0.75, ay + 0.75, -2.5),
              side="iron", top="iron", bottom="iron", skip=("south",))
    for arm in ("chain_2", "chain_4"):
        ax, ay = arms[arm]["anchor"]
        zc = arms[arm]["z"]
        M.box(f"anchor_block_{arm[-1]}", "anchors", (ax - 1.5, -7, zc - 1.5), (ax + 1.5, -6, zc + 1.5),
              side="gold", top="gold", skip=("down",))
        M.box(f"anchor_eye_{arm[-1]}", "anchors", (ax - 0.5, -6, zc - 0.5), (ax + 0.5, -5.25, zc + 0.5),
              side="iron", top="iron", skip=("down",))

    # broken remnants
    for arm in ("chain_1", "chain_3"):         # upper: two links hang from the eye bolt, the last one swings
        ax, ay = arms[arm]["anchor"]
        g = "chain_broken_" + arm[-1]
        zc = min(Z_A, Z_B)
        M.cube(f"{g}_a", g, (ax - 2, ay - 4.5, zc - 0.5), (ax + 2, ay - 0.5, zc + 0.5), link_faces("flat", "v"))
        M.cube(f"{g}_b", g, (ax - 0.5, ay - 7.5, zc - 1.25), (ax + 0.5, ay - 3.5, zc + 1.25), link_faces("edge", "v"))
        M.cube(f"{g}_c", g, (ax - 2, ay - 10.5, zc - 0.5), (ax + 2, ay - 6.5, zc + 0.5), link_faces("flat", "v"),
               rot=("z", -22.5 if ax > 8 else 22.5), origin=(ax, ay - 6.5, zc))
    for arm in ("chain_2", "chain_4"):         # lower: stub on the staple, slack on the apron, stub under the hub
        a = arms[arm]
        ax, ay = a["anchor"]
        g = "chain_broken_" + arm[-1]
        s0, s1, k = arm_segments(a)[-1]
        arm_link(f"{g}_a", g, a, s0, s1, k)
        lx = ax + (-2.4 if ax > 8 else 2.4)          # slack link on the apron, toward the stair
        lz = a["z"] - 0.3
        M.cube(f"{g}_b", g, (lx - 2, -7, lz - 2), (lx + 2, -6.5, lz + 2),
               {"up": reg("ring"), "north": reg("bar_h", 4, 0.5), "south": reg("bar_h", 4, 0.5),
                "east": reg("iron", 4, 0.5), "west": reg("iron", 4, 0.5)},
               rot=("y", 22.5 if ax > 8 else -22.5), origin=(lx, -6.75, lz))
        if VARIANT == "F":
            hx = 8 + a["sgn"] * 2.4
            M.cube(f"{g}_c", g, (hx - 2, YC - 6.2, a["z"] - 0.5), (hx + 2, YC - 2.2, a["z"] + 0.5),
                   link_faces("flat", "v"), rot=("z", 22.5 if a["sgn"] > 0 else -22.5), origin=(hx, YC - 2.2, a["z"]))

    # ================= cracks =================
    M.decal("crack_1a", "cracks_1", -2.1, ((-2, -4), (2, 8)), reg("crack_v"), "north")        # viewer-right buttress
    M.decal("crack_1b", "seal_cracks_1", SEAL_Z - 0.1, ((9, YC + 1), (14, YC + 6)), reg("crack_seal_a"), "north")
    M.decal("crack_1c", "cracks_1", 1.9, ((2, 11), (6, 18)), reg("crack_w"), "north")          # 2nd floor, left
    M.decal("crack_2a", "cracks_2", -2.1, ((14, -2), (18, 10)), reg("crack_v"), "north")       # viewer-left buttress
    M.decal("crack_2b", "cracks_2", 1.9, ((10, 11), (14, 18)), reg("crack_w"), "north")        # 2nd floor, right
    M.decal("crack_2e", "seal_cracks_2", SEAL_Z - 0.1, ((2, YC - 6), (7, YC - 1)), reg("crack_seal_b"), "north")
    M.decal("crack_2c", "cracks_2", 16.1, ((3, -4), (11, 10)), reg("crack_big"), "east")
    M.decal("crack_2d", "cracks_2", -0.1, ((5, -4), (13, 10)), reg("crack_big"), "west")

    # ================= crystal =================
    for gname in ("crystal_dim", "crystal_glow"):
        s = 1.0 if gname == "crystal_dim" else 1.2
        a = 1.5 * s
        M.box(f"{gname}_a", gname, (8 - a, CRYSTAL_Y - a, 7), (8 + a, CRYSTAL_Y + a, 9), side=gname, top=gname,
              bottom=gname, rot=("z", 45), origin=(8, CRYSTAL_Y, 8))
        M.box(f"{gname}_b", gname, (7, CRYSTAL_Y - a, 8 - a), (9, CRYSTAL_Y + a, 8 + a), side=gname, top=gname,
              bottom=gname, rot=("x", 45), origin=(8, CRYSTAL_Y, 8))
    hx = reg("halo_x")
    hh = min(5.0, 31.9 - (CRYSTAL_Y + DY))
    hr = ((8 - hh, CRYSTAL_Y - hh), (8 + hh, CRYSTAL_Y + hh))
    M.decal("crystal_halo_n", "crystal_glow", 8, hr, hx, "north")
    M.decal("crystal_halo_s", "crystal_glow", 8.02, hr, hx, "south")
    M.decal("crystal_halo_w", "crystal_glow", 8, hr, hx, "west")
    M.decal("crystal_halo_e", "crystal_glow", 8.02, hr, hx, "east")

    # ================= shattered seal (opened) =================
    g = "seal_shattered"
    shards = [
        ("shard_floor_1", (3.0, -5, -2.4), (6.0, -4.2, -0.9), ("y", 22.5)),
        ("shard_floor_2", (10.5, -5, -2.3), (13.0, -4.3, -1.0), ("y", -45)),
        ("shard_floor_3", (0.5, -7, -5.1), (3.0, -6.3, -3.6), ("y", 45)),
        ("shard_floor_4", (13.5, -7, -5.2), (15.5, -6.4, -3.8), ("y", -22.5)),
        ("shard_fly_1", (0.6, 2.0, -4.2), (2.6, 4.0, -3.7), ("z", 45)),
        ("shard_fly_2", (13.4, 4.5, -4.6), (14.9, 6.0, -4.1), ("z", 22.5)),
        ("shard_fly_3", (7.0, 10.2, -4.4), (9.0, 12.2, -3.9), ("z", -22.5)),
        ("shard_fly_4", (12.6, -1.5, -4.8), (13.8, -0.3, -4.3), ("z", 45)),
    ]
    for name, fr, to, rot in shards:
        faces = {}
        for d in FACES:
            fw, fh = fsize(fr, to, d)
            faces[d] = reg("shard", min(fw, 4), min(fh, 4)) if d in ("north", "south", "up") else reg("gold", fw, fh)
        M.cube(name, g, fr, to, faces, rot=rot)

    names = [c["name"] for c in M.cubes]
    assert len(names) == len(set(names)), "duplicate element names"
    clash = set(names) & set(M.groups)
    assert not clash, f"element/group name clash: {clash}"
    return M


if __name__ == "__main__":
    M = build()
    print(len(M.cubes), "cubes;", "groups:", M.groups)
