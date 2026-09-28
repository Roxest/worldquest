"""Build the Endless Tower seal monument through the headless Blockbench MCP (bbmcp.py).

    python build.py master            # paint atlas + (re)create worldquest/master.bbmodel
    python build.py render [file]     # front + three-quarter renders of a .bbmodel (default master)
    python build.py stages            # stage_1..6 .bbmodel copies (groups removed) + renders + validate
    python build.py export            # Java item models + pack tree + contact sheet
    python build.py all
"""
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import atlas  # noqa: E402
import geometry  # noqa: E402

WS = "F:/Pixelclub/blockbench-models"
WQ = WS + "/worldquest"
REL = "worldquest"
PY = "F:/Pixelclub/tools/.venv/Scripts/python.exe"
BBMCP = "F:/Pixelclub/tools/bbmcp.py"
SCRATCH = os.environ.get("WQ_SCRATCH", os.path.join(__import__("tempfile").gettempdir(), "wq_bbmcp_calls"))
MASTER = REL + "/master.bbmodel"
TEX_NAME = "seal_tower"
NS_TEX = "pixelclub:item/worldquest/" + TEX_NAME

STAGES = [
    # (cmd, file stem, groups kept)
    (12001, "seal_stage1_sealed",
     ["plinth", "spire", "anchors", "door_closed", "seal_body", "seal_dim", "chain_1", "chain_2", "chain_3", "chain_4",
      "crystal_dim"]),
    (12002, "seal_stage2_25",
     ["plinth", "spire", "anchors", "door_closed", "seal_body", "seal_dim", "chain_broken_1", "chain_2", "chain_3",
      "chain_4", "cracks_1", "seal_cracks_1", "crystal_dim"]),
    (12003, "seal_stage3_50",
     ["plinth", "spire", "anchors", "door_closed", "seal_body", "seal_dim", "chain_broken_1", "chain_broken_2",
      "chain_3", "chain_4", "cracks_1", "seal_cracks_1", "crystal_dim"]),
    (12004, "seal_stage4_75",
     ["plinth", "spire", "anchors", "door_closed", "seal_body", "seal_glow", "chain_broken_1", "chain_broken_2",
      "chain_broken_3", "chain_4", "cracks_1", "cracks_2", "seal_cracks_1", "seal_cracks_2", "crystal_dim"]),
    (12005, "seal_stage5_100",
     ["plinth", "spire", "anchors", "door_closed", "seal_body", "seal_bright", "chain_broken_1", "chain_broken_2",
      "chain_broken_3", "chain_broken_4", "cracks_1", "cracks_2", "seal_cracks_1", "seal_cracks_2",
      "crystal_dim"]),
    (12006, "seal_stage6_opened",
     ["plinth", "spire", "anchors", "portal", "seal_shattered", "chain_broken_1", "chain_broken_2", "chain_broken_3",
      "chain_broken_4", "cracks_1", "cracks_2", "crystal_glow"]),
]


def run(calls, tag, max_chars=3000, check=True):
    os.makedirs(SCRATCH, exist_ok=True)
    path = os.path.join(SCRATCH, tag + ".json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(calls, fh, indent=1)
    env = dict(os.environ, MSYS_NO_PATHCONV="1", PYTHONIOENCODING="utf-8")
    p = subprocess.run([PY, BBMCP, path, "--max", str(max_chars)], capture_output=True, text=True, encoding="utf-8",
                       env=env)
    out = p.stdout + p.stderr
    if check and p.returncode != 0:
        print(out[-6000:])
        raise SystemExit(f"bbmcp failed: {tag}")
    return out


def shift(v, dy):
    return [v[0], v[1] + dy, v[2]]


def master():
    atlas.build()
    M = geometry.build()
    ops = []
    for g in M.groups:
        ops.append({"op": "add_group", "name": g, "origin": [8, 8, 8]})
    for c in M.cubes:
        op = {"op": "add_cube", "name": c["name"], "parent": c["group"],
              "from": shift(c["from"], geometry.DY), "to": shift(c["to"], geometry.DY), "faces": c["faces"]}
        if "rotation" in c:
            op["rotation"] = c["rotation"]
            op["origin"] = shift(c["origin"], geometry.DY)
        ops.append(op)
    calls = [
        {"tool": "bbmodel_create", "args": {"file": MASTER, "format": "java_block", "name": "endless_tower_seal",
                                            "resolution": {"width": 128, "height": 128}, "overwrite": True}},
        {"tool": "bbmodel_add_texture", "args": {"file": MASTER, "image": REL + "/tex/seal_tower.png",
                                                 "name": TEX_NAME}},
    ]
    for i in range(0, len(ops), 80):
        calls.append({"tool": "bbmodel_edit", "args": {"file": MASTER, "operations": ops[i:i + 80]}})
    out = run(calls, "master", max_chars=400)
    print(out[-1500:])
    print(f"master: {len(M.cubes)} cubes in {len(M.groups)} groups")


def render(file=MASTER, stem=None, views=("front", "three-quarter"), size=640, lighting="flat"):
    stem = stem or os.path.splitext(os.path.basename(file))[0]
    calls = []
    outs = []
    for v in views:
        out = f"{REL}/renders/{stem}_{v}.png"
        outs.append(WS + "/" + out)
        calls.append({"tool": "bbmodel_render", "args": {"file": file, "view": v, "width": size, "height": size,
                                                         "output": out, "overwrite": True, "lighting": lighting}})
    run(calls, "render_" + stem, max_chars=300)
    return outs


def stage_files():
    return [(cmd, stem, groups, f"{REL}/stages/{stem}.bbmodel") for cmd, stem, groups in STAGES]


def stages(do_render=True):
    M = geometry.build()
    os.makedirs(WQ + "/stages", exist_ok=True)
    calls = []
    for cmd, stem, keep, rel in stage_files():
        shutil.copyfile(WS + "/" + MASTER, WS + "/" + rel)
        drop = [g for g in M.groups if g not in keep]
        missing = [g for g in keep if g not in M.groups]
        assert not missing, (stem, missing)
        calls.append({"tool": "bbmodel_edit", "args": {"file": rel, "operations": [
            {"op": "remove_node", "target": g} for g in drop]}})
        calls.append({"tool": "bbmodel_validate", "args": {"file": rel, "block_limits": "java_block",
                                                           "gates": ["block_limits"]}})
    out = run(calls, "stages", max_chars=1200, check=False)
    print(out[-5000:])
    if do_render:
        for cmd, stem, keep, rel in stage_files():
            render(rel, stem)


def export():
    pack = WQ + "/pack"
    if os.path.isdir(pack):
        shutil.rmtree(pack)
    os.makedirs(pack + "/items")
    os.makedirs(pack + "/models/worldquest")
    os.makedirs(pack + "/textures/item/worldquest")
    shutil.copyfile(WQ + "/tex/seal_tower.png", pack + "/textures/item/worldquest/seal_tower.png")
    calls = []
    for cmd, stem, keep, rel in stage_files():
        calls.append({"tool": "bbmodel_export_java_block", "args": {
            "file": rel, "output": f"{REL}/_export/{stem}.json", "version": "1.9.0", "export_groups": False,
            "export_pivots": False, "overwrite": True}})
    run(calls, "export", max_chars=300)
    counts = {}
    lo = [99.0] * 3
    hi = [-99.0] * 3
    for cmd, stem, keep, rel in stage_files():
        with open(f"{WQ}/_export/{stem}.json", encoding="utf-8") as fh:
            model = json.load(fh)
        model = postprocess(model, stem)
        counts[stem] = len(model["elements"])
        a, b = extents(model)
        lo = [min(lo[i], a[i]) for i in range(3)]
        hi = [max(hi[i], b[i]) for i in range(3)]
        with open(f"{pack}/models/worldquest/{stem}.json", "w", encoding="utf-8") as fh:
            json.dump(model, fh, separators=(",", ":"))
    write_items_yml(pack, lo, hi)
    shutil.rmtree(f"{WQ}/_export", ignore_errors=True)
    print(json.dumps(counts, indent=1))
    print("extents", [round(x, 2) for x in lo], [round(x, 2) for x in hi])
    return counts


def extents(model):
    """True bounding box of the rendered geometry (rotated element corners included)."""
    import math
    lo = [99.0] * 3
    hi = [-99.0] * 3
    for e in model["elements"]:
        fr, to = e["from"], e["to"]
        rot = e.get("rotation")
        for cx in (fr[0], to[0]):
            for cy in (fr[1], to[1]):
                for cz in (fr[2], to[2]):
                    p = [cx, cy, cz]
                    if rot:
                        o = rot["origin"]
                        a = math.radians(rot["angle"])
                        i, j = {"x": (1, 2), "y": (2, 0), "z": (0, 1)}[rot["axis"]]
                        di, dj = p[i] - o[i], p[j] - o[j]
                        p[i] = o[i] + di * math.cos(a) - dj * math.sin(a)
                        p[j] = o[j] + di * math.sin(a) + dj * math.cos(a)
                    lo = [min(lo[k], p[k]) for k in range(3)]
                    hi = [max(hi[k], p[k]) for k in range(3)]
    return lo, hi


ALLOWED = {-45.0, -22.5, 0.0, 22.5, 45.0}


def postprocess(model, stem):
    """Point textures at pixelclub:item/worldquest/..., drop editor-only fields, and assert the 1.21.1 rules."""
    keys = list(model.get("textures", {}).keys())
    assert keys, stem
    tex = {"particle": NS_TEX}
    remap = {}
    for k in keys:
        remap["#" + k] = "#t"
    tex["t"] = NS_TEX
    out = {"credit": "Pixelclub - Endless Tower seal (" + stem + ")", "textures": tex, "elements": []}
    for e in model["elements"]:
        ne = {"from": e["from"], "to": e["to"]}
        for i in range(3):
            assert -16 <= e["from"][i] <= 32 and -16 <= e["to"][i] <= 32, (stem, e.get("name"), e["from"], e["to"])
        rot = e.get("rotation")
        if rot and float(rot.get("angle", 0)) != 0.0:
            assert float(rot["angle"]) in ALLOWED, (stem, e.get("name"), rot)
            assert rot["axis"] in ("x", "y", "z"), rot
            ne["rotation"] = {"angle": rot["angle"], "axis": rot["axis"], "origin": rot["origin"]}
        faces = {}
        for d, f in e["faces"].items():
            if f.get("texture") is None:
                continue
            nf = {"uv": [round(x, 4) for x in f["uv"]], "texture": remap.get(f["texture"], "#t")}
            if f.get("rotation"):
                nf["rotation"] = f["rotation"]
            faces[d] = nf
        if not faces:
            continue
        ne["faces"] = faces
        out["elements"].append(ne)
    assert len(out["elements"]) <= 250, (stem, len(out["elements"]))
    # a plain 'fixed'/'gui' display so an op holding the item sees something sensible; ItemDisplay uses NONE
    out["display"] = {
        "gui": {"rotation": [20, 200, 0], "translation": [0, 0, 0], "scale": [0.34, 0.34, 0.34]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.2, 0.2, 0.2]},
        "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.34, 0.34, 0.34]},
        "thirdperson_righthand": {"rotation": [0, 180, 0], "translation": [0, 2, 1], "scale": [0.15, 0.15, 0.15]},
        "firstperson_righthand": {"rotation": [0, 200, 0], "translation": [0, 2, 0], "scale": [0.15, 0.15, 0.15]},
        "head": {"rotation": [0, 180, 0], "translation": [0, 12, 0], "scale": [0.3, 0.3, 0.3]},
    }
    return out


def write_items_yml(pack, lo, hi):
    lines = [
        "# World quest 'Unseal the Endless Tower' -- the seal monument next to the spawn hologram (pxc-worldquest).",
        "#",
        "# Not items anyone is given: they exist so PackBuilder reserves the custom_model_data and ships the models;",
        "# pxc-worldquest hangs the stage model on a packet-only item_display (hologram.monument in its config).",
        "# Stage = highest atPercent <= progress (0/25/50/75/100), 12006 once the quest is opened.",
        "# One texture for all six: textures/item/worldquest/seal_tower.png (pixelclub:item/worldquest/seal_tower).",
        "# Source + generator: F:/Pixelclub/blockbench-models/worldquest (master.bbmodel, src/build.py).",
        "#",
        "# Model front (the seal) is the model's -Z face. ItemDisplayRenderer turns items 180 deg about Y, so at",
        f"# monument.yawDegrees = 0 the seal looks SOUTH. Geometry spans y {lo[1]:.1f}..{hi[1]:.1f} "
        f"({hi[1] - lo[1]:.0f} units) around the item centre (8,8,8):",
        f"# at scale 3 the base sits {(8 - lo[1]) * 3 / 16:.2f} blocks below the entity, the crystal "
        f"{(hi[1] - 8) * 3 / 16:.2f} above; footprint {hi[0] - lo[0]:.0f} x {hi[2] - lo[2]:.0f} units.",
        "",
    ]
    names = {12001: "Sealed", 12002: "25%", 12003: "50%", 12004: "75%", 12005: "100%", 12006: "Opened"}
    for cmd, stem, keep in STAGES:
        key = "worldquest_" + stem
        lines += [
            f"{key}:",
            f"  display_name: \"&5Endless Tower Seal &7({names[cmd]})\"",
            "  material: PAPER",
            f"  model: pixelclub:worldquest/{stem}",
            f"  custom_model_data: {cmd}",
            "",
        ]
    with open(pack + "/items/worldquest.yml", "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines))


def sheet(views=("front", "three-quarter"), tile=420, out=None):
    """One image: the six stages as columns, one row per view (renders must exist)."""
    from PIL import Image, ImageDraw, ImageFont
    labels = ["12001  sealed", "12002  25%", "12003  50%", "12004  75%", "12005  100%", "12006  opened"]
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 26)
    except OSError:
        font = ImageFont.load_default()
    head = 44
    img = Image.new("RGB", (tile * len(STAGES), head + tile * len(views)), (22, 24, 30))
    d = ImageDraw.Draw(img)
    for c, (cmd, stem, keep) in enumerate(STAGES):
        d.text((c * tile + 14, 8), labels[c], fill=(235, 235, 235), font=font)
        for r, v in enumerate(views):
            im = Image.open(f"{WQ}/renders/{stem}_{v}.png").convert("RGB").resize((tile, tile), Image.LANCZOS)
            img.paste(im, (c * tile, head + r * tile))
    out = out or f"{WQ}/renders/contact_sheet_all_stages.png"
    img.save(out)
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "all"
    if cmd == "master":
        master()
    elif cmd == "render":
        f = sys.argv[2] if len(sys.argv) > 2 else MASTER
        print(render(f))
    elif cmd == "stages":
        stages()
        print(sheet())
    elif cmd == "sheet":
        print(sheet())
    elif cmd == "export":
        export()
    elif cmd == "all":
        master()
        stages()
        print(sheet())
        export()
