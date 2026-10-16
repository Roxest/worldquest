package com.pixelclub.worldquest.event;

import com.google.gson.JsonParseException;
import com.pixelclub.worldquest.config.WqConfig;
import com.pixelclub.worldquest.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/pxcworldquest/event.json} — the event quest (Halloween 2026: feed candy to the pumpkin monument).
 * Same contract as {@link WqConfig}: keys the file lacks take the defaults below and are written back; a file that
 * does not parse is left untouched and the previous values stay in force.
 */
public final class EventConfig {

    public boolean enabled = true;
    /** Ledger / state key; a new id starts a new event (the old state file stays where it is). */
    public String id = "halloween2026";
    /** Client bedrock model id (pixelclub-client >= 1.51.0 ships pixelclubclient:worldquest_halloween). */
    public String model = "pixelclubclient:worldquest_halloween";

    public Anchor anchor = new Anchor();
    /** Model scale on the client; hitbox and label height follow it. */
    public double scale = 1.0;
    /** Draw the monument (locked idle, no bar) before /worldquest event admin start. */
    public boolean showWhenIdle = true;

    /** Candies the whole server must feed before the deadline (placeholder until Kraus sets it). */
    public long target = 10_000L;
    /** PixelclubPack custom item id of the candy. */
    public String candyItemId = "halloween_candy";
    /** Vanilla item id also accepted as candy, for a rig without PixelclubPack ("" = none). */
    public String candyFallbackItem = "";
    public double defaultDurationHours = 168.0;

    /** When the bar fills: the deadline is replaced by this countdown (crowd gathers for the end). */
    public int countdownSeconds = 600;
    public List<Integer> countdownMarks = new ArrayList<>(List.of(600, 300, 180, 120, 60, 30, 10, 5, 4, 3, 2, 1));
    public List<Integer> deadlineWarnHours = new ArrayList<>(List.of(48, 24, 6, 1));
    public List<Integer> milestonePercents = new ArrayList<>(List.of(25, 50, 75, 90));
    /** Length of the client's locked_ends clip; the quest flips to unlocked right after it. */
    public double endsSeconds = 4.0;
    /** Seconds into locked_ends where the pumpkin slams into the rift (sky turns, title shows). */
    public double slamAtSeconds = 2.13;

    public Hitbox hitbox = new Hitbox();
    /** How far a player's right-click reaches the pumpkin (blocks from the eyes). */
    public double reach = 7.0;
    /** One click per player per this many ms (the client sends use-item and use-on-block for one click). */
    public long clickCooldownMs = 250L;
    /** Clients within this radius (same dimension) get the bounce animation and sound when someone feeds it. */
    public double pulseRadius = 96.0;

    public Bar bar = new Bar();
    public Sky sky = new Sky();
    public Label label = new Label();

    /** Console commands run once when the quest unlocks (the Halloween event itself). */
    public List<String> unlockCommands = new ArrayList<>();
    /** Console commands run once when the quest is lost. */
    public List<String> failCommands = new ArrayList<>();

    public Messages messages = new Messages();

    public static final class Anchor {
        public boolean placed = false;
        public String dimension = "minecraft:overworld";
        public double x, y, z;
        /** Minecraft yaw the model's front faces (0 = south). */
        public float yaw;
    }

    /** The pumpkin, in blocks at scale 1 around the anchor (pumpkin cube 3 blocks, floating 1.6-4.6 in locked_idle). */
    public static final class Hitbox {
        public double halfWidth = 1.8;
        public double minY = 1.2;
        public double maxY = 5.0;
    }

    public static final class Bar {
        public boolean enabled = true;
        /** Players within this horizontal radius of the monument (same dimension) see the boss bar. 0 = whole dimension. */
        public double radius = 0.0;
        /** Keep showing the "unlocked" bar this long after the opening. */
        public int unlockedMinutes = 30;
    }

    public static final class Sky {
        /** Client SkyFx sky (pixelclub-client assets/pixelclubclient/sky/<id>.json). */
        public String id = "halloween";
        /** How it comes in at the slam: shatter · noise_bottom · vortex · iris · dissolve shapes ("" = fade). */
        public String reveal = "shatter";
        public double revealSeconds = 1.5;
        /** 0..1 sky strength on the client once opened; 0 = off. */
        public double amount = 1.0;
        /** Blocks from the monument the green sky reaches; 0 = the whole dimension (the spawn world). */
        public double radius = 0.0;
    }

    public static final class Label {
        public boolean enabled = true;
        public double y = 5.6;
        public double scale = 1.0;
    }

    /** &-coded chat/bar text (never &l with Thai). {time} {donated} {target} {percent} {count} {mine} {name} {secs}. */
    public static final class Messages {
        public String labelTitle = "World Quest · Halloween";
        public String labelCount = "ลูกอม {donated} / {target} ({percent}%)";
        public String labelTime = "เหลือเวลา {time}";
        public String labelCountdown = "{time}";
        public String labelDone = "ผนึกถูกทำลายแล้ว";

        public String barIdle = "&6World Quest &7· &fยังไม่เริ่ม";
        public String barCollecting = "&6World Quest &7· &fลูกอม {donated}/{target} &7({percent}%) · เหลือ {time}";
        public String barCountdown = "&aมีบางอย่างกำลังเกิดขึ้นที่ World Quest &7· &f{time}";
        public String barUnlocked = "&aWorld Quest ปลดผนึกแล้ว!";

        public String started = "&6[World Quest] &fภารกิจโลกฮาโลวีนเริ่มแล้ว! นำ &eลูกอม &fไปป้อนฟักทองที่ spawn ให้ครบ &e{target} &fชิ้น ภายใน &e{time}";
        public String milestone = "&6[World Quest] &fลูกอมครบ &e{percent}% &fแล้ว! &7({donated}/{target})";
        public String deadlineWarn = "&6[World Quest] &fเหลือเวลาอีก &e{time} &fก่อน World Quest จะสาปสูญ! &7(ลูกอม {donated}/{target})";
        public String full = "&6[World Quest] &aProgress เต็มแล้ว! &fกำลังมีอะไรบางอย่างเกิดขึ้นที่ World Quest!!! &7(อีก {time})";
        public String countdownMark = "&6[World Quest] &fอีก &e{time} &fผนึกจะแตก... มารวมตัวกันที่ World Quest!";
        public String countdownTitle = "&a{secs}";
        public String countdownSubtitle = "&7World Quest";
        public String opening = "&6[World Quest] &aผนึกกำลังแตก!";
        public String slamTitle = "&aผนึกแตกแล้ว!";
        public String slamSubtitle = "&7Halloween เริ่มต้นขึ้น";
        public String unlocked = "&6[World Quest] &aผนึกถูกทำลายแล้ว! &fHalloween Event เริ่มต้นขึ้น! ขอบคุณทุกคนที่ร่วมป้อนลูกอม &7({donated} ชิ้น)";
        public String failed = "&6[World Quest] &cหมดเวลา... World Quest ได้สาปสูญไปแล้ว";

        public String fed = "&6+{count} ลูกอม &7· ของคุณ {mine} · ทั้งเซิร์ฟ {donated}/{target}";
        public String notStarted = "&7World Quest ยังไม่เริ่ม";
        public String closed = "&7World Quest ไม่รับลูกอมแล้ว";
        public String info = "&6World Quest &7· &fลูกอม &e{donated}&7/&e{target} &7({percent}%) · {time} · ของคุณ &e{mine}";
    }

    // ------------------------------------------------------------------ load / save

    public static EventConfig loadOrCreate(Path file, EventConfig previous) throws IOException {
        if (!Files.exists(file)) {
            EventConfig c = new EventConfig();
            save(file, c);
            return c;
        }
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        EventConfig c;
        try {
            c = WqConfig.GSON.fromJson(raw, EventConfig.class);
        } catch (JsonParseException e) {
            if (previous != null) throw new IOException("event.json does not parse, previous values kept: " + e.getMessage(), e);
            throw new IOException("event.json does not parse: " + e.getMessage(), e);
        }
        if (c == null) c = new EventConfig();
        String normalized = WqConfig.GSON.toJson(c);
        if (!normalized.equals(raw)) save(file, c); // new keys written back with their defaults
        return c;
    }

    public static void save(Path file, EventConfig c) throws IOException {
        AtomicFiles.writeString(file, WqConfig.GSON.toJson(c));
    }
}
