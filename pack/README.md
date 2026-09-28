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

## Seal monument
Models from the Blockbench headless worker go in `PixelclubPack/{items,models/worldquest,textures/item/worldquest}` (CMD 12001–12006).
