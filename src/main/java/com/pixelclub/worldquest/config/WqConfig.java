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
            new ItemDef("cobblemon_utility:woodencap", 30),
            new ItemDef("mega_showdown:ash_cap", 67),
            new ItemDef("cobblemon_utility:hpsilvercap", 250),
            new ItemDef("cobblemon_utility:atksilvercap", 250),
            new ItemDef("cobblemon_utility:defsilvercap", 250),
            new ItemDef("cobblemon_utility:spatksilvercap", 250),
            new ItemDef("cobblemon_utility:spdefsilvercap", 250),
            new ItemDef("cobblemon_utility:speedsilvercap", 250),
            new ItemDef("raid_den_keys:raid_den_key_t6", 15),
            new ItemDef("raid_den_keys:raid_den_key_t7", 10)));

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
            q.tiers.add(new Tier("t1", "ผู้ร่วมปลดผนึก", 100));
            q.tiers.add(new Tier("t2", "ผู้อุปถัมภ์", 1_000));
            q.tiers.add(new Tier("t3", "ผู้บุกเบิก", 5_000));
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
        public boolean enabled = true;
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
        public float titleScale = 1.6f;
        public float bodyScale = 1.0f;
        /** Height above the anchor of the title and the body, in blocks. */
        public double titleOffsetY = 2.45;
        public double bodyOffsetY = 0.0;
        /** ARGB background; 0x40000000 = vanilla nametag box. */
        public int background = 0x70000000;
        /** Actionbar hint for players this close (blocks); 0 = off. */
        public int personalHintRange = 8;
        public int personalHintSeconds = 20;
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
        /** Display name for chat; falls back to the item's own name. */
        public String displayName;

        public ItemDef() {
        }

        public ItemDef(String id, long points) {
            this.id = id;
            this.points = points;
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

        public String offerEmptyHand = "&fถือไอเทมที่จะถวายไว้ในมือหลัก แล้วใช้ &b/worldquest offer";
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

        public String tierReached = "&fคุณได้ระดับ &e{tier} &fในภารกิจโลก &7(แต้ม {points})";

        public String infoHeader = "&6ภารกิจโลก &7— &f{quest}";
        public String infoConquerors = "&7- &f{label} &e{count}&7/{target}";
        public String infoFund = "&7- &f{label} &e{points}&7/{target} แต้ม &7(เงิน ฿{money} · ไอเทม {itemPoints} แต้ม)";
        public String infoMine = "&7- &fแต้มของคุณ &e{mine}{tier}";
        public String infoMineConquered = "&7- &aคุณพิชิตชั้น 100 แล้ว";
        public String infoMineFloor = "&7- &fชั้นสูงสุดของคุณ &e{floor}&7/100 &7— ไปที่ &b/tower";
        public String infoHow = "&7บริจาค &b/worldquest donate <เงิน> &7· ถวายของในมือ &b/worldquest offer";
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
