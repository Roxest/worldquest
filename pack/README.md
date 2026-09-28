# World quest pack assets

## "Path finder" title (tier-1 reward; unlocks in /tags)
- `PixelclubPack/textures/font/titles/pathfinder.png` → server `config/PixelclubPack/textures/font/titles/pathfinder.png`
  (172x38, from kie `nano-banana-pro` candidate A — prompt in `source/pathfinder_kie_plan.json`, raw in `source/`).
- `config/PixelclubPack/glyphs/titles.yml`: add key `pathfinder` → texture `pixelclub:font/titles/pathfinder.png`,
  height 13, ascent 10, char `\uC109` (C101–C108 were taken on dev 2026-09-28 — re-check on the target server).
- `config/deluxemenus/gui_menus/tag_menu.yml`: add `pathfinder_unlocked` (perm `pixelclub.unlock.pathfinder`) and
  `pathfinder_locked` at slot 13, before `# --- RESET BUTTON ---`.
  `source/pf_insert.py` makes both edits on copies of the server files (run it next to `pf_titles.yml` / `pf_tag_menu.yml`).
- Then: `pixelclub reload` → wait ~10 s → `pixelclub pack update` → `fdm reload`.
- Grant: the world quest tier `pathfinder` runs `lp user %player% permission set pixelclub.unlock.pathfinder true`.

## Seal monument (CMD 12001–12006, PAPER)
- `PixelclubPack/items/worldquest.yml` + `models/worldquest/seal_stage*.json` (6) + `textures/item/worldquest/seal_tower.png`
  → copy into `config/PixelclubPack/` → `pixelclub reload` → wait → `pixelclub pack update`; check that
  `assets/minecraft/models/item/paper.json` in `generated/pack.zip` has overrides 12001–12006 (dev 2026-09-28: 6/6).
- Stages: 12001 sealed · 12002 25% · 12003 50% · 12004 75% · 12005 reached · 12006 opened. Seal on the model's −Z face
  (item display turns it 180° → yaw 0 faces south). 45 px tall, base 21.5 px below the item centre.
- Source: `source/blockbench/master.bbmodel` (24 named groups), `stages/*.bbmodel`, `src/build.py all` rebuilds every
  stage and export (headless Blockbench MCP, `F:/Pixelclub/tools/bbmcp.py`). Preview: `source/contact_sheet_all_stages.png`.
