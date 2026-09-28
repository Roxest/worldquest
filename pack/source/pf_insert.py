"""Insert the Pathfinder title into titles.yml and tag_menu.yml (copies in this folder)."""
import yaml

BS = chr(92)          # a literal backslash, never an escape
GLYPH = chr(0xC109)   # the character the font maps to the badge


def load(p):
    raw = open(p, encoding="utf-8", newline="").read()
    nl = "\r\n" if "\r\n" in raw else "\n"
    return raw.replace("\r\n", "\n"), nl


t, nl_t = load("pf_titles.yml")
anchor = '  char: "' + BS + 'uC103"\n'
assert t.count(anchor) == 1, t.count(anchor)
assert "pathfinder:" not in t
entry = ('pathfinder:\n'
         '  texture: "pixelclub:font/titles/pathfinder.png"\n'
         '  height: 13\n'
         '  ascent: 10\n'
         '  char: "' + BS + 'uC109"\n')
t = t.replace(anchor, anchor + entry, 1)
open("pf_titles.new.yml", "w", encoding="utf-8", newline="").write(t.replace("\n", nl_t))

m, nl_m = load("pf_tag_menu.yml")
items = (
    "  'pathfinder_unlocked':\n"
    "    slot: 13\n"
    "    priority: 1\n"
    "    material: NAME_TAG\n"
    "    view_requirement:\n"
    "      requirements:\n"
    "        has_perm:\n"
    "          type: has permission\n"
    "          permission: pixelclub.unlock.pathfinder\n"
    "    display_name: '&dTAG: &f%img_pathfinder%'\n"
    "    lore:\n"
    "      - '&7Status: &a&lᴜɴʟᴏᴄᴋᴇᴅ'\n"
    "      - ''\n"
    "      - '&eคลิกเพื่อใส่ Tag นี้!'\n"
    "    left_click_commands:\n"
    "      - '[console] lp user %player_name% meta set custom_tag \"\"'\n"
    "      - '[console] lp user %player_name% meta set custom_tag \"" + GLYPH + "\"'\n"
    "      - '[message] &aสวมใส่ " + GLYPH + " &aเรียบร้อย!'\n"
    "      - '[close]'\n"
    "\n"
    "  'pathfinder_locked':\n"
    "    slot: 13\n"
    "    priority: 2\n"
    "    material: NAME_TAG\n"
    "    display_name: '&dTAG: &f%img_pathfinder%'\n"
    "    lore:\n"
    "      - '&7Status: &c&lʟᴏᴄᴋᴇᴅ'\n"
    "      - ''\n"
    "      - '&7ได้รับจากการพิชิต &bหอคอยชั้น 100'\n"
    "      - '&7ผู้บุกเบิกที่ร่วมปลดผนึก &bEndless Tower'\n"
    "\n"
)
anchor2 = "  # --- RESET BUTTON ---"
assert m.count(anchor2) == 1
assert "pathfinder_unlocked" not in m
m = m.replace(anchor2, items + anchor2, 1)
open("pf_tag_menu.new.yml", "w", encoding="utf-8", newline="").write(m.replace("\n", nl_m))

for f in ("pf_titles.new.yml", "pf_tag_menu.new.yml"):
    yaml.safe_load(open(f, encoding="utf-8"))
    print(f, "parses OK")
d = yaml.safe_load(open("pf_titles.new.yml", encoding="utf-8"))["pathfinder"]
print(d["texture"], d["height"], d["ascent"], hex(ord(d["char"])))
items_loaded = yaml.safe_load(open("pf_tag_menu.new.yml", encoding="utf-8"))["items"]
print([k for k in items_loaded if "pathfinder" in k], hex(ord(items_loaded["pathfinder_unlocked"]["left_click_commands"][1].split('"')[1])))
