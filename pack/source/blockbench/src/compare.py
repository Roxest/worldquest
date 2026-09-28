"""Render one stage of each design variant side by side (design exploration helper).

    WQ_VARIANT is read by geometry at import, so each variant runs in its own subprocess:
    python compare.py B F  [stage_index=0]
"""
import os
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PY = "F:/Pixelclub/tools/.venv/Scripts/python.exe"
WS = "F:/Pixelclub/blockbench-models"


def one(variant, stage_idx):
    os.environ["WQ_VARIANT"] = variant
    sys.path.insert(0, HERE)
    import build
    import geometry
    rel_master = f"worldquest/_variants/master_{variant}.bbmodel"
    os.makedirs(WS + "/worldquest/_variants", exist_ok=True)
    build.MASTER = rel_master
    build.master()
    cmd, stem, keep = build.STAGES[stage_idx]
    rel = f"worldquest/_variants/{stem}_{variant}.bbmodel"
    shutil.copyfile(WS + "/" + rel_master, WS + "/" + rel)
    M = geometry.build()
    drop = [g for g in M.groups if g not in keep]
    build.run([{"tool": "bbmodel_edit", "args": {"file": rel, "operations": [
        {"op": "remove_node", "target": g} for g in drop]}}], "cmp_" + variant)
    outs = build.render(rel, f"cmp_{stem}_{variant}", views=("front", "three-quarter"), size=512, lighting="flat")
    outs += build.render(rel, f"cmp_{stem}_{variant}_out", views=("three-quarter",), size=512, lighting="outdoor")
    print("\n".join(outs))


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--one":
        one(sys.argv[2], int(sys.argv[3]))
        return
    variants = [a for a in sys.argv[1:] if not a.isdigit()] or ["B", "F"]
    stage_idx = int(next((a for a in sys.argv[1:] if a.isdigit()), 0))
    rows = []
    for v in variants:
        p = subprocess.run([PY, __file__, "--one", v, str(stage_idx)], capture_output=True, text=True, errors="replace", env=dict(os.environ, PYTHONIOENCODING="utf-8"),
                           encoding="utf-8")
        if p.returncode:
            print(p.stdout[-3000:], p.stderr[-3000:])
            raise SystemExit(1)
        rows.append([l for l in p.stdout.strip().splitlines() if l.endswith(".png")][-3:])
    tile = 512
    sheet = Image.new("RGB", (tile * 3, tile * len(rows) + 30 * len(rows)), (24, 26, 32))
    d = ImageDraw.Draw(sheet)
    for r, (v, paths) in enumerate(zip(variants, rows)):
        y = r * (tile + 30)
        d.text((8, y + 8), f"variant {v}", fill=(230, 230, 230))
        for c, pth in enumerate(paths):
            sheet.paste(Image.open(pth).convert("RGB").resize((tile, tile)), (c * tile, y + 30))
    out = WS + f"/worldquest/renders/compare_{''.join(variants)}_s{stage_idx + 1}.png"
    sheet.save(out)
    print(out)


if __name__ == "__main__":
    main()
