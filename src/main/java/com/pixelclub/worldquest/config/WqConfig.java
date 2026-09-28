package com.pixelclub.worldquest.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.pixelclub.worldquest.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config/pxcworldquest/config.json}. Every field carries its default here, so a file written by an older
 * version loads with the new keys filled in (Gson keeps the constructor's values for keys the file lacks).
 *
 * <p>Balance numbers in here are Kraus's to set (targets, point values, tiers). The defaults are starting values
 * recorded in the vault note "World Quest", not measurements.
 */
public final class WqConfig {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public int configVersion = 1;

    /** How many ฿ one fund point is worth. Item point values are priced on the same scale. */
    public long moneyPerPoint = 10_000L;

    public List<QuestDef> quests = new ArrayList<>(List.of(QuestDef.endlessDefault()));

    /**
     * Items that can be offered into a fund: registry id → points per single item. Starting values (2026-09-28):
     * points = floor(PRD GTS 30-day median ÷ moneyPerPoint), SOLD listings 08-29→09-28; farmable items and items a
     * server shop sells below their point value are left out (see vault note "World Quest").
     */
    public List<ItemDef> items = new ArrayList<>(List.of(
            new ItemDef("cobblemon:leftovers", 20),
            new ItemDef("cobblemon:life_orb", 30),
            new ItemDef("cobblemon:choice_scarf", 30),
            new ItemDef("cobblemon:choice_specs", 25),
            new ItemDef("cobblemon:choice_band", 25),
            new ItemDef("cobblemon:ability_patch", 100),
            // woodencap dropped 2026-09-28: its ฿300k "median" was a 64-stack; per unit it sells for ฿6k (n=85) → 0 points
            new ItemDef("mega_showdown:ash_cap", 67),
            new ItemDef("cobblemon_utility:hpsilvercap", 250),
            new ItemDef("cobblemon_utility:atksilvercap", 250),
            new ItemDef("cobblemon_utility:defsilvercap", 250),
            new ItemDef("cobblemon_utility:spatksilvercap", 250),
            new ItemDef("cobblemon_utility:spdefsilvercap", 250),
            new ItemDef("cobblemon_utility:speedsilvercap", 250),
            new ItemDef("raid_den_keys:raid_den_key_t6", 15),
            new ItemDef("raid_den_keys:raid_den_key_t7", 10),
            ItemDef.heldItemShard()));

    public Messages messages = new Messages();

    // ------------------------------------------------------------------ quest

    public static final class QuestDef {
        public String id = "endless_unlock";
        public boolean enabled = true;
        /** Shown in chat and on the hologram title line. */
        public String name = "ปลดผนึก Endless Tower";
        public ConquerorGoal conquerors = new ConquerorGoal();
        public FundGoal fund = new FundGoal();
        /** "manual" = reaching every goal celebrates and waits for /worldquest admin open. "auto" opens at once. */
        public String openMode = "manual";
        /** Keep taking donations after the goals are met (the fund keeps burning; tiers keep counting). */
        public boolean acceptAfterReached = true;
        /** Personal points a conqueror gets for the tier ladder (never added to the fund bar) — lands them on tier 1. */
        public long conquestBonusPoints = 100;
        public List<Tier> tiers = new ArrayList<>();
        /** Conqueror counts that get a server-wide line when crossed. */
        public List<Integer> conquerorMilestones = new ArrayList<>(List.of(60, 70, 80, 90, 95));
        /** Fund percentages that get a server-wide line when crossed. */
        public List<Integer> fundMilestonesPercent = new ArrayList<>(List.of(25, 50, 75, 90));
        /** A single money donation at or above this is announced to everyone (0 = never). */
        public long announceDonationAtLeast = 1_000_000L;
        public List<String> onReachedCommands = new ArrayList<>();
        public List<String> onOpenedCommands = new ArrayList<>();
        public Hologram hologram = new Hologram();

        public static QuestDef endlessDefault() {
            QuestDef q = new QuestDef();
            // Every floor-100 conqueror lands here (conquestBonusPoints) and unlocks the "Path finder" title in /tags
            // (Kraus 2026-09-28). Donation tiers come back with the fund.
            Tier pathfinder = new Tier("pathfinder", "Path finder", 100);
            pathfinder.commands.add("lp user %player% permission set pixelclub.unlock.pathfinder true");
            q.tiers.add(pathfinder);
            return q;
        }
    }

    public static final class ConquerorGoal {
        public boolean enabled = true;
        public String label = "พิชิตหอคอยชั้น 100";
        public int target = 100;
        /** Pull the conqueror list from pixel-tower (PixelTowerApi) at boot, every syncMinutes, and on admin sync. */
        public boolean towerSync = true;
        public int syncMinutes = 10;
        /** A conqueror found by sync whose conquest is younger than this is announced like a live one. */
        public int announceSyncedWithinMinutes = 15;
    }

    public static final class FundGoal {
        /**
         * Off by default (Kraus 2026-09-28): with one reward for any amount, a big donor and a small one get the same
         * thing and people would complain. The item list and the money path stay in place for when it has a design.
         */
        public boolean enabled = false;
        public String label = "กองทุนปลดผนึก";
        public long targetPoints = 50_000L;
        public boolean acceptMoney = true;
        public long minMoney = 10_000L;
        public long maxMoneyPerDonation = 50_000_000L;
        public boolean acceptItems = true;
    }

    public static final class Tier {
        public String id;
        public String name;
        public long points;
        /** Console commands run once per player on reaching the tier; %player% %uuid% %tier% %points%. Queued if offline. */
        public List<String> commands = new ArrayList<>();

        public Tier() {
        }

        public Tier(String id, String name, long points) {
            this.id = id;
            this.name = name;
            this.points = points;
        }
    }

    public static final class Hologram {
        /** Players inside this horizontal radius (same world) see it. */
        public int viewRange = 48;
        public int recentConquerors = 3;
        public int topDonors = 3;
        public int barLength = 30;
        /** Line width in pixels before the client wraps (vanilla default 200). */
        public int lineWidth = 320;
        public float titleScale = 2.4f;
        public float bodyScale = 1.3f;
        /**
         * "stacked": the seal stands centred on the anchor and the texts float above it, so from any side the text
         * never cuts through the model (Kraus 2026-09-28: text beside the seal looked wrong when walking around it).
         * "manual": titleOffsetY / bodyOffsetY and monument.offsetX/Y/Z as written.
         */
        public String layout = "stacked";
        /** stacked: blocks between the top of the seal and the bottom of the body text. */
        public double stackGap = 0.4;
        /** stacked: blocks between the top of the body text and the bottom of the title. */
        public double titleGap = 0.2;
        /** Height of one text line at scale 1, blocks (a text display draws 10 px lines at 0.025 block per px). */
        public double lineHeight = 0.25;
        /** manual layout: height above the anchor of the title and the body, in blocks. */
        public double titleOffsetY = 2.45;
        public double bodyOffsetY = 0.0;
        /**
         * Right-click the seal holding an item = offer it (sneak = every stack of that item); a second right-click
         * within rightClickConfirmSeconds confirms. Only while the fund takes items, and only for logged-in players.
         */
        public boolean rightClickOffer = true;
        /** Farthest a right-click still reaches the seal, blocks from the eyes. */
        public double rightClickReach = 6.0;
        public int rightClickConfirmSeconds = 10;
        public Particles particles = new Particles();
        /** ARGB background; 0x40000000 = vanilla nametag box. */
        public int background = 0x70000000;
        /** Actionbar hint for players this close (blocks); 0 = off. */
        public int personalHintRange = 8;
        public int personalHintSeconds = 20;
        public Monument monument = new Monument();
    }

    /**
     * A 3D model from the server resource pack (PixelclubPack: paper + custom_model_data) shown as a packet-only
     * ItemDisplay at the hologram — the seal that breaks as the quest fills. Off until the models are in the pack.
     */
    public static final class Monument {
        public boolean enabled = false;
        public String item = "minecraft:paper";
        /** Highest stage whose atPercent ≤ progress wins; progress = average of the enabled goals, each capped at 100. */
        public List<Stage> stages = new ArrayList<>(List.of(new Stage(0, 12001), new Stage(25, 12002),
                new Stage(50, 12003), new Stage(75, 12004), new Stage(100, 12005)));
        /** Model once the quest is opened. */
        public int openedCustomModelData = 12006;
        /**
         * Uniform scale; an item model's 16 px = 1 block at scale 1, centred on the entity (model-space 8,8,8). The seal
         * models are ~43 px tall with their base 21.5 px below that centre: scale 4 → a ~10.8-block monument, ~6.9
         * blocks across (Kraus 2026-09-28: make it big).
         */
        public float scale = 4.0f;
        /**
         * The seal's measurements in model px, from the exported item models (pack/…/seal_stage*.json): base 21.5
         * below the model centre, top 23.9 above it at the tallest stage (opened; the others 21.7), half-width 13.84.
         * The stacked layout, the right-click and the particle ring are all computed from these.
         */
        public double modelBaseBelowCenterPx = 21.5;
        public double modelTopAboveCenterPx = 23.9;
        public double modelRadiusPx = 13.84;
        /** stacked layout: raise the seal's base above the anchor (where the admin stood), blocks. */
        public double baseLift = 0.0;
        /** manual layout only: model centre relative to the hologram anchor, in blocks. */
        public double offsetX = -3.2;
        public double offsetY = 2.96;
        public double offsetZ = 0.0;
        /**
         * Facing, degrees around Y. The 1.21.1 item-display renderer turns an item 180°, so with the seal on the model's
         * −Z face, 0 means the seal looks south (+Z).
         */
        public float yawDegrees = 0f;
        /** Slow turn, degrees per second; 0 = still. Interpolated client-side between once-a-second updates. */
        public float spinDegreesPerSecond = 3f;
    }

    /**
     * Particles around the seal, sent only to players within {@link #range} (the client draws nothing farther than
     * ~32 blocks anyway). Ids must be simple particle types (no options): reverse_portal, end_rod, portal, enchant….
     */
    public static final class Particles {
        public boolean enabled = true;
        /** The ring that rises around the seal. */
        public String ambient = "minecraft:reverse_portal";
        /** Joins the ring once the quest's average progress reaches accentAtPercent. */
        public String accent = "minecraft:end_rod";
        public int accentAtPercent = 50;
        /** Ambient packets per second per nearby player; each carries 2 particles. */
        public int perSecond = 8;
        public int range = 24;
        /** On every donation or offering: a burst on the seal for everyone near, and a sound for the giver. */
        public String burst = "minecraft:end_rod";
        public int burstCount = 40;
        public String contributionSound = "minecraft:block.beacon.power_select";
    }

    public static final class Stage {
        public int atPercent;
        public int customModelData;

        public Stage() {
        }

        public Stage(int atPercent, int customModelData) {
            this.atPercent = atPercent;
            this.customModelData = customModelData;
        }
    }

    public static final class ItemDef {
        public String id;
        public long points;
        /**
         * If set, the stack's CUSTOM_DATA must contain this key (custom items such as crate keys). Items without a
         * requirement must be plain: no custom name, no lore, no custom data except a fresh held-item-durability stamp.
         */
        public String requireCustomDataKey;
        /** Optional exact string value the key must hold (e.g. a crate key's type). */
        public String requireCustomDataValue;
        /**
         * If set, the stack's custom name must be exactly this string, § codes included. Only for an item whose own
         * mod identifies it by name and by nothing else — helditem-durability's Held Item Shard
         * ({@code ShardShopHandler.createShardStack}: prismarine_shard + {@code Component.literal("§b§lHeld Item Shard")},
         * no custom data). An anvil cannot type §, so an exact match refuses a renamed prismarine shard (the shard
         * shop's own {@code contains("Held Item Shard")} would take it).
         */
        public String requireCustomName;
        /** With requireCustomName: the item's own lore belongs to it and does not make it "special". */
        public boolean allowLore;
        /** Display name for chat; falls back to the item's own name. */
        public String displayName;

        public ItemDef() {
        }

        public ItemDef(String id, long points) {
            this.id = id;
            this.points = points;
        }

        /**
         * 1 point = ฿10,000. Starting value (2026-09-28): the shard has no GTS market (2 sales, both ฿1 transfers),
         * so it is priced through the shard shop — destiny knot 60 shards ↔ GTS ฿600k (n=202, 30 days) → ฿10k/shard.
         */
        public static ItemDef heldItemShard() {
            ItemDef d = new ItemDef("minecraft:prismarine_shard", 1);
            d.requireCustomName = "§b§lHeld Item Shard";
            d.allowLore = true;
            d.displayName = "Held Item Shard";
            return d;
        }
    }

    // ------------------------------------------------------------------ messages

    /** Player-facing text. &-codes; never &l on Thai (the doubled glyph smears the marks). */
    public static final class Messages {
        public String prefix = "&6[ภารกิจโลก] &r";
        public String conquerAnnounce = "&e{player} &fพิชิตหอคอยชั้น 100! &7ผู้พิชิต &e{count}&7/{target} คน";
        public String conquerSelfTitle = "&6ร่วมปลดผนึกแล้ว";
        public String conquerSelfSubtitle = "&fคุณคือผู้พิชิตคนที่ &e{count}&f/{target}";
        public String conquerorMilestone = "&fผู้พิชิตหอคอยครบ &e{count}&f/{target} คนแล้ว &7— อีก &e{left} &7คนจะ{quest}";
        public String fundMilestone = "&f{fund} ถึง &e{percent}% &fแล้ว &7({points}/{target} แต้ม)";
        public String donationAnnounce = "&e{player} &fบริจาค &a฿{amount} &fเข้า{fund}";
        public String reachedLine1 = "&6ภารกิจโลกสำเร็จ! &f{quest}";
        public String reachedLine2 = "&7ขอบคุณผู้ร่วมภารกิจทุกคน &f— รอพิธีเปิดจากทีมงาน";
        public String reachedTitle = "&6ภารกิจโลกสำเร็จ";
        public String reachedSubtitle = "&f{quest}";
        public String openedLine1 = "&6{quest} &fเปิดแล้ว!";
        public String openedLine2 = "&7ขอบคุณผู้ร่วมภารกิจ &e{contributors} &7คน";
        public String openedTitle = "&6เปิดแล้ว";
        public String openedSubtitle = "&f{quest}";

        public String donateUsage = "&fใช้ &b/worldquest donate <จำนวนเงิน> &7(ขั้นต่ำ ฿{min})";
        public String donateTooSmall = "&cขั้นต่ำ ฿{min} ต่อครั้ง";
        public String donateTooLarge = "&cสูงสุด ฿{max} ต่อครั้ง &7— บริจาคหลายครั้งได้";
        public String donateConfirm = "&fบริจาค &a฿{amount} &fเข้า{fund} &7= &e{points} &7แต้ม";
        public String donateDone = "&aบริจาค ฿{amount} สำเร็จ &7— แต้มของคุณ &e{mine} &7· กองทุน &e{fundPoints}&7/{fundTarget}";
        public String donateNoFunds = "&cเงินไม่พอ &7(มี ฿{balance})";
        public String donateFailed = "&cหักเงินไม่สำเร็จ ไม่มีการหักเงิน &7({reason})";
        public String donateUndecided = "&cมีปัญหาระหว่างหักเงิน &7— แจ้งทีมงานพร้อมรหัส &e{ref}";
        public String donateBusy = "&7กำลังทำรายการก่อนหน้าอยู่ รอสักครู่";
        public String donateNoEconomy = "&cตอนนี้ระบบเงินใช้ไม่ได้";

        public String offerEmptyHand = "&fถือไอเทมที่จะถวายไว้ในมือหลัก แล้วคลิกขวาที่ผนึก &7(หรือ &b/worldquest offer&7)";
        /** Actionbar after a right-click on the seal opened an offer prompt. */
        public String rightClickConfirmHint = "&eคลิกขวาที่ผนึกอีกครั้งเพื่อยืนยัน &7· ย่อตัว+คลิกขวา = ถวายทั้งหมดในกระเป๋า";
        public String offerNotAccepted = "&c{item} &cรับถวายไม่ได้ &7— ดูรายการ &b/worldquest items";
        public String offerWorn = "&c{item} &cสึกแล้ว &7(ถวายได้เฉพาะของที่ยังไม่เคยสึก)";
        public String offerSpecial = "&c{item} &cมีชื่อ/คำอธิบาย/ข้อมูลพิเศษ ถวายไม่ได้";
        public String offerConfirm = "&fถวาย &e{item} &fx{count} &7= &e{points} &7แต้ม &c(ไอเทมจะหายไป)";
        public String offerDone = "&aถวาย {item} x{count} สำเร็จ &7— แต้มของคุณ &e{mine} &7· กองทุน &e{fundPoints}&7/{fundTarget}";
        public String offerChanged = "&cไอเทมในมือเปลี่ยนไปแล้ว &7ยกเลิกรายการ";
        public String itemsHeader = "&6ไอเทมที่รับถวาย &7(แต้มต่อชิ้น)";
        public String itemsLine = "&7- &f{item} &7= &e{points}";

        public String confirmButton = "&a[ยืนยัน]";
        public String confirmHover = "&7คลิกเพื่อยืนยัน · หมดเวลาใน {seconds} วินาที";
        public String cancelButton = "&c[ยกเลิก]";
        public String confirmExpired = "&7รายการนี้หมดเวลาแล้ว ทำใหม่อีกครั้ง";
        public String cancelled = "&7ยกเลิกแล้ว";
        public String notAccepting = "&7ตอนนี้{fund}ไม่รับบริจาค";
        public String noQuest = "&7ตอนนี้ไม่มีภารกิจโลก";

        public String tierReached = "&fคุณได้รับฉายา &e{tier} &fจากภารกิจโลก &7— เลือกใส่ได้ที่ &b/tags";

        public String infoHeader = "&6ภารกิจโลก &7— &f{quest}";
        public String infoConquerors = "&7- &f{label} &e{count}&7/{target}";
        public String infoFund = "&7- &f{label} &e{points}&7/{target} แต้ม &7(เงิน ฿{money} · ไอเทม {itemPoints} แต้ม)";
        public String infoMine = "&7- &fแต้มของคุณ &e{mine}{tier}";
        public String infoMineConquered = "&7- &aคุณพิชิตชั้น 100 แล้ว";
        public String infoMineFloor = "&7- &fชั้นสูงสุดของคุณ &e{floor}&7/100 &7— ไปที่ &b/tower";
        public String infoHow = "&7บริจาค &b/worldquest donate <เงิน> &7· ถวายไอเทม: ถือในมือแล้วคลิกขวาที่ผนึก";
        public String infoPhaseReached = "&aครบเป้าแล้ว &7— รอพิธีเปิด";
        public String infoPhaseOpened = "&aเปิดแล้ว";

        public String holoTitle = "&6ภารกิจโลก";
        public String holoName = "&e{quest}";
        public String holoConquerors = "&f⚔ {label}  &e{count}&7/{target}";
        public String holoFund = "&f✦ {label}  &e{points}&7/{target} &7แต้ม";
        public String holoFundDetail = "&7เงิน ฿{money} · ไอเทม {itemPoints} แต้ม";
        public String holoRecent = "&7ผู้พิชิตล่าสุด: &f{names}";
        public String holoTop = "&7ผู้อุปถัมภ์สูงสุด: &f{names}";
        public String holoHint = "&7ร่วมภารกิจ &b/worldquest";
        /** The last hologram line while the fund takes items and right-click offering is on. */
        public String holoHintOffer = "&7คลิกขวาที่ผนึกเพื่อถวายไอเทม &7· &b/worldquest";
        public String holoReached = "&aครบทุกเป้าแล้ว! &7รอพิธีเปิดจากทีมงาน";
        public String holoOpened = "&a{quest} เปิดแล้ว!";
        public String hintNotConquered = "&fชั้นสูงสุดของคุณ &e{floor}&f/100 &7· พิชิตชั้น 100 เพื่อร่วมปลดผนึก";
        public String hintConquered = "&aคุณร่วมปลดผนึกแล้ว &7· แต้มของคุณ &e{mine}";
        public String hintUnknown = "&fพิชิตหอคอยชั้น 100 หรือบริจาคเพื่อร่วมปลดผนึก &7· &b/worldquest";
    }

    // ------------------------------------------------------------------ load

    public QuestDef quest(String id) {
        for (QuestDef q : quests) {
            if (q.id.equalsIgnoreCase(id)) {
                return q;
            }
        }
        return null;
    }

    public Map<String, ItemDef> itemIndex() {
        Map<String, ItemDef> out = new LinkedHashMap<>();
        for (ItemDef d : items) {
            if (d != null && d.id != null && d.points > 0) {
                out.putIfAbsent(d.id, d);
            }
        }
        return out;
    }

    /** Loads, or writes the defaults when the file is missing. Throws on a file that does not parse. */
    public static WqConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            WqConfig fresh = new WqConfig();
            AtomicFiles.writeString(file, GSON.toJson(fresh));
            return fresh;
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        try {
            WqConfig c = GSON.fromJson(json, WqConfig.class);
            if (c == null) {
                throw new IOException("config.json is empty");
            }
            if (c.quests == null) c.quests = new ArrayList<>();
            if (c.items == null) c.items = new ArrayList<>();
            if (c.messages == null) c.messages = new Messages();
            for (QuestDef q : c.quests) {
                if (q.conquerors == null) q.conquerors = new ConquerorGoal();
                if (q.fund == null) q.fund = new FundGoal();
                if (q.tiers == null) q.tiers = new ArrayList<>();
                if (q.hologram == null) q.hologram = new Hologram();
                if (q.hologram.monument == null) q.hologram.monument = new Monument();
                if (q.hologram.particles == null) q.hologram.particles = new Particles();
                if (q.hologram.layout == null) q.hologram.layout = "stacked";
                if (q.hologram.monument.stages == null) q.hologram.monument.stages = new ArrayList<>();
                q.hologram.monument.stages.sort((a, b) -> Integer.compare(a.atPercent, b.atPercent));
                if (q.conquerorMilestones == null) q.conquerorMilestones = new ArrayList<>();
                if (q.fundMilestonesPercent == null) q.fundMilestonesPercent = new ArrayList<>();
                if (q.onReachedCommands == null) q.onReachedCommands = new ArrayList<>();
                if (q.onOpenedCommands == null) q.onOpenedCommands = new ArrayList<>();
                q.tiers.sort((a, b) -> Long.compare(a.points, b.points));
            }
            if (c.moneyPerPoint <= 0) c.moneyPerPoint = 10_000L;
            return c;
        } catch (JsonParseException e) {
            throw new IOException("config.json does not parse: " + e.getMessage(), e);
        }
    }
}
