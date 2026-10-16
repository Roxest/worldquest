package com.pixelclub.worldquest.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.pixelclub.worldquest.config.WqConfig;
import com.pixelclub.worldquest.event.EventState.Contributor;
import com.pixelclub.worldquest.event.EventState.Phase;
import com.pixelclub.worldquest.state.Ledger;
import com.pixelclub.worldquest.util.AtomicFiles;
import com.pixelclub.worldquest.util.Dispatch;
import com.pixelclub.worldquest.util.Text;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The event quest (Halloween 2026): the whole server feeds candy to a pumpkin monument at spawn before a deadline.
 * Full bar → the deadline is replaced by a short countdown so people gather → the monument plays its ending
 * (client clip locked_ends), the sky of its world turns green, the unlock commands run → unlocked for good.
 * Deadline first → the quest is lost: announced, and the monument is gone.
 *
 * <p>The monument itself is client-side (pixelclub-client {@code WorldQuestClient}, feature {@code worldquest});
 * this class owns every number, the phase, and the clicks. A click is a right-click with candy in hand whose look
 * ray hits the pumpkin's box: the server tests that itself, so a player on an older client can still feed it and
 * follows along on the boss bar. All of it runs on the server thread; only state writes go to an IO thread.</p>
 */
public final class EventQuest {

    public static final String FEATURE = "worldquest";

    private final MinecraftServer server;
    private final Logger log;
    private final Path dir;
    private final Path configFile;
    private final Path stateFile;
    private final Ledger ledger;
    private final PackBridge pack;

    private EventConfig config;
    private EventState state;
    /** State that could not be read is never overwritten; the quest refuses candy until an admin looks. */
    private String brokenReason;
    private boolean dirty;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pxcworldquest-event-io");
        t.setDaemon(true);
        return t;
    });

    private final ServerBossEvent bar = new ServerBossEvent(Component.literal("World Quest"),
            BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
    private final Set<UUID> synced = new HashSet<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private long tick;
    private long lastSyncedDonated = -1;
    private long lastSyncAt;

    public EventQuest(MinecraftServer server, Logger log, Path dir, Ledger ledger) {
        this.server = server;
        this.log = log;
        this.dir = dir;
        this.configFile = dir.resolve("event.json");
        this.stateFile = dir.resolve("event-state.json");
        this.ledger = ledger;
        this.pack = new PackBridge(log);
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() throws IOException {
        config = EventConfig.loadOrCreate(configFile, null);
        loadState();
        bar.setVisible(false);
        log.info("[WorldQuest] event '{}' {} phase={} donated={}/{} placed={}", config.id,
                config.enabled ? "enabled" : "disabled", state.phase, state.donated, config.target, config.anchor.placed);
        if (!state.invariantHolds()) {
            log.error("[WorldQuest] INVARIANT BROKEN event donated={} players={} admin={}", state.donated,
                    state.sumPlayers(), state.adminAdded);
        }
    }

    private void loadState() throws IOException {
        brokenReason = null;
        if (!Files.exists(stateFile)) {
            state = new EventState();
            state.id = config.id;
            return;
        }
        try {
            EventState s = WqConfig.GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), EventState.class);
            if (s == null) throw new JsonParseException("empty");
            if (s.players == null) s.players = new LinkedHashMap<>();
            if (s.marksDone == null) s.marksDone = new ArrayList<>();
            if (s.warnsDone == null) s.warnsDone = new ArrayList<>();
            if (s.milestonesDone == null) s.milestonesDone = new ArrayList<>();
            if (s.phase == null) s.phase = Phase.IDLE;
            if (!config.id.equals(s.id)) {
                // a new event id in the config: keep the old file for the record, start clean
                Path keep = dir.resolve("event-state-" + (s.id == null || s.id.isEmpty() ? "old" : s.id) + ".json");
                Files.move(stateFile, keep, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.warn("[WorldQuest] event id changed {} -> {}: old state kept as {}", s.id, config.id, keep.getFileName());
                s = new EventState();
                s.id = config.id;
            }
            state = s;
        } catch (JsonParseException e) {
            state = new EventState();
            state.id = config.id;
            brokenReason = "event-state.json does not parse: " + e.getMessage();
            log.error("[WorldQuest] {} — event frozen, file left untouched", brokenReason);
        }
    }

    public void stop() {
        bar.removeAllPlayers();
        flush(true);
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void reloadConfig() throws IOException {
        config = EventConfig.loadOrCreate(configFile, config);
        resyncAll();
    }

    public EventConfig config() {
        return config;
    }

    public EventState state() {
        return state;
    }

    public String brokenReason() {
        return brokenReason;
    }

    private void markDirty() {
        dirty = true;
    }

    /** Writes the state if it changed; async from the tick, sync at shutdown. */
    private void flush(boolean sync) {
        if (!dirty || brokenReason != null) return;
        dirty = false;
        String json = WqConfig.GSON.toJson(state);
        Runnable w = () -> {
            try {
                AtomicFiles.writeString(stateFile, json);
            } catch (IOException e) {
                log.error("[WorldQuest] event state write failed: {}", e.toString());
            }
        };
        if (sync) w.run();
        else io.execute(w);
    }

    // ------------------------------------------------------------------ tick

    public void tick() {
        tick++;
        if (brokenReason != null || !config.enabled) {
            if (tick % 20 == 0) bar.removeAllPlayers();
            return;
        }
        long now = System.currentTimeMillis();
        switch (state.phase) {
            case COLLECTING -> tickCollecting(now);
            case COUNTDOWN -> tickCountdown(now);
            case ENDING -> tickEnding(now);
            default -> {
            }
        }
        if (tick % 20 == 0) {
            updateBar(now);
            syncNewClients();
            if (state.donated != lastSyncedDonated && now - lastSyncAt >= 2000) resyncAll();
            else if (now - lastSyncAt >= 30_000) resyncAll(); // keeps the client timers honest
            flush(false);
        }
    }

    private void tickCollecting(long now) {
        if (state.deadlineAt > 0 && now >= state.deadlineAt) {
            fail(now);
            return;
        }
        if (tick % 20 != 0 || state.deadlineAt <= 0) return;
        long left = state.deadlineAt - now;
        int due = -1;
        for (int h : config.deadlineWarnHours) {
            if (left <= h * 3_600_000L && !state.warnsDone.contains(h)) {
                state.warnsDone.add(h);
                due = due < 0 ? h : Math.min(due, h); // after a restart only the nearest one is said
                markDirty();
            }
        }
        if (due > 0 && left > 0) broadcast(fill(config.messages.deadlineWarn, now));
    }

    private void tickCountdown(long now) {
        long left = state.countdownEndsAt - now;
        if (left <= 0) {
            beginEnding(now);
            return;
        }
        int secs = (int) Math.ceil(left / 1000.0);
        int due = -1;
        for (int m : config.countdownMarks) {
            if (secs <= m && !state.marksDone.contains(m)) {
                state.marksDone.add(m);
                due = due < 0 ? m : Math.min(due, m);
                markDirty();
            }
        }
        if (due < 0) return;
        if (due <= 10) {
            for (ServerPlayer p : playersInWorld()) {
                title(p, Text.fill(config.messages.countdownTitle, Map.of("secs", secs)), config.messages.countdownSubtitle, 0, 22, 4);
                p.playNotifySound(SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.MASTER, 1f, secs <= 3 ? 1.6f : 1.2f);
            }
        } else {
            broadcast(Text.fill(config.messages.countdownMark, Map.of("time", countdownClock(secs))));
        }
    }

    private void tickEnding(long now) {
        long el = now - state.endsStartedAt;
        if (!state.slamShown && el >= (long) (config.slamAtSeconds * 1000)) {
            state.slamShown = true;
            markDirty();
            for (ServerPlayer p : playersInWorld()) {
                title(p, config.messages.slamTitle, config.messages.slamSubtitle, 5, 60, 20);
            }
        }
        if (el >= (long) (config.endsSeconds * 1000) + 250) unlock(now);
    }

    // ------------------------------------------------------------------ transitions

    public void begin(double hours) {
        long now = System.currentTimeMillis();
        state.phase = Phase.COLLECTING;
        state.startedAt = now;
        state.deadlineAt = now + (long) (hours * 3_600_000L);
        state.countdownEndsAt = state.endsStartedAt = state.unlockedAt = state.failedAt = 0;
        state.warnsDone.clear();
        state.marksDone.clear();
        state.slamShown = false;
        // a warning whose window has already begun is not announced at once
        for (int h : config.deadlineWarnHours) if (state.deadlineAt - now <= h * 3_600_000L) state.warnsDone.add(h);
        markDirty();
        ledger.record("EVENT_START", fields("hours", hours, "deadline", state.deadlineAt, "target", config.target));
        broadcast(fill(config.messages.started, now));
        checkFull(now);
        resyncAll();
    }

    private void checkFull(long now) {
        if (state.phase != Phase.COLLECTING || state.donated < config.target) return;
        startCountdown(now, config.countdownSeconds);
    }

    public void startCountdown(long now, int seconds) {
        boolean wasFull = state.phase == Phase.COUNTDOWN;
        state.phase = Phase.COUNTDOWN;
        state.countdownEndsAt = now + seconds * 1000L;
        state.marksDone.clear();
        // marks above the countdown's own length are not said; the "full" line already gives the time
        for (int m : config.countdownMarks) if (m >= seconds) state.marksDone.add(m);
        markDirty();
        ledger.record("EVENT_FULL", fields("donated", state.donated, "countdown", seconds));
        resyncAll();
        if (wasFull) return; // an admin shortening the countdown: the "full" line was already said
        broadcast(Text.fill(config.messages.full, Map.of("time", countdownClock(seconds))));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.playNotifySound(SoundEvents.WITHER_AMBIENT, SoundSource.MASTER, 0.6f, 0.8f);
        }
    }

    public void beginEnding(long now) {
        state.phase = Phase.ENDING;
        state.endsStartedAt = now;
        state.slamShown = false;
        markDirty();
        ledger.record("EVENT_ENDING", fields("donated", state.donated));
        broadcast(config.messages.opening);
        resyncAll();
    }

    private void unlock(long now) {
        state.phase = Phase.UNLOCKED;
        state.unlockedAt = now;
        markDirty();
        ledger.record("EVENT_UNLOCKED", fields("donated", state.donated, "players", state.players.size()));
        broadcast(fill(config.messages.unlocked, now));
        if (!state.unlockCommandsRun) {
            state.unlockCommandsRun = true;
            flush(true); // the commands run once: say so on disk before they do
            for (String c : config.unlockCommands) Dispatch.console(server, log, c);
        }
        resyncAll();
    }

    public void fail(long now) {
        state.phase = Phase.FAILED;
        state.failedAt = now;
        markDirty();
        ledger.record("EVENT_FAILED", fields("donated", state.donated, "target", config.target));
        broadcast(config.messages.failed);
        bar.removeAllPlayers();
        if (!state.failCommandsRun) {
            state.failCommandsRun = true;
            flush(true);
            for (String c : config.failCommands) Dispatch.console(server, log, c);
        }
        resyncAll();
    }

    /** Back to IDLE with every count cleared (admin). The ledger keeps the history. */
    public void reset() {
        ledger.record("EVENT_RESET", fields("phase", state.phase.name(), "donated", state.donated));
        EventState s = new EventState();
        s.id = config.id;
        state = s;
        brokenReason = null;
        markDirty();
        bar.removeAllPlayers();
        resyncAll();
    }

    public void adminAdd(long n, String by) {
        state.donated += n;
        state.adminAdded += n;
        markDirty();
        ledger.record("EVENT_ADMIN_ADD", fields("n", n, "by", by, "total", state.donated));
        milestones(System.currentTimeMillis());
        checkFull(System.currentTimeMillis());
    }

    public void setTarget(long target) {
        config.target = Math.max(1, target);
        saveConfig();
        resyncAll();
    }

    public void place(String dimension, Vec3 at, float yaw) {
        config.anchor.placed = true;
        config.anchor.dimension = dimension;
        config.anchor.x = at.x;
        config.anchor.y = at.y;
        config.anchor.z = at.z;
        config.anchor.yaw = yaw;
        saveConfig();
        ledger.record("EVENT_PLACE", fields("dim", dimension, "x", at.x, "y", at.y, "z", at.z, "yaw", yaw));
        bar.removeAllPlayers();
        resyncAll();
    }

    public void setScale(double scale) {
        config.scale = scale;
        saveConfig();
        resyncAll();
    }

    private void saveConfig() {
        try {
            EventConfig.save(configFile, config);
        } catch (IOException e) {
            log.error("[WorldQuest] event.json write failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ candy

    /** True when this right-click was a feed (or a refused feed) aimed at the monument; the click is then used up. */
    public boolean onUse(ServerPlayer p, InteractionHand hand) {
        if (!config.enabled || !config.anchor.placed || !visible()) return false;
        ItemStack stack = p.getItemInHand(hand);
        if (stack.isEmpty() || !isCandy(stack)) return false;
        if (!p.level().dimension().location().toString().equals(config.anchor.dimension)) return false;
        if (!aimsAtPumpkin(p)) return false;
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUUID());
        if (last != null && now - last < config.clickCooldownMs) return true;
        lastClick.put(p.getUUID(), now);
        if (brokenReason != null) {
            p.sendSystemMessage(Text.of("&cWorld Quest ปิดรับชั่วคราว (แจ้งแอดมิน)"));
            return true;
        }
        if (!state.accepting()) {
            actionBar(p, state.phase == Phase.IDLE ? config.messages.notStarted : config.messages.closed);
            return true;
        }
        int count = stack.getCount();
        String what = pack.idOf(stack) != null ? pack.idOf(stack) : BuiltInRegistries.ITEM.getKey(stack.getItem()) + "";
        stack.shrink(count);
        Contributor c = state.players.computeIfAbsent(p.getUUID().toString(), k -> new Contributor(p.getGameProfile().getName()));
        c.name = p.getGameProfile().getName();
        c.count += count;
        c.last = now;
        state.donated += count;
        markDirty();
        ledger.record("EVENT_CANDY", fields("uuid", p.getUUID().toString(), "name", c.name, "count", count,
                "item", what, "mine", c.count, "total", state.donated));
        actionBar(p, Text.fill(config.messages.fed, vars(now, Map.of("count", count, "mine", Text.num(c.count)))));
        p.playNotifySound(SoundEvents.GENERIC_EAT, SoundSource.MASTER, 0.8f, 1.3f);
        pulse();
        milestones(now);
        checkFull(now);
        return true;
    }

    private boolean isCandy(ItemStack stack) {
        String id = pack.idOf(stack);
        if (id != null && id.equals(config.candyItemId)) return true;
        if (config.candyFallbackItem == null || config.candyFallbackItem.isEmpty()) return false;
        Item fb = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(config.candyFallbackItem));
        return stack.is(fb) && id == null;
    }

    private boolean aimsAtPumpkin(ServerPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 to = eye.add(p.getLookAngle().scale(config.reach));
        return pumpkinBox().clip(eye, to).isPresent() || pumpkinBox().contains(eye);
    }

    AABB pumpkinBox() {
        EventConfig.Anchor a = config.anchor;
        double s = config.scale, hw = config.hitbox.halfWidth * s;
        return new AABB(a.x - hw, a.y + config.hitbox.minY * s, a.z - hw, a.x + hw, a.y + config.hitbox.maxY * s, a.z + hw);
    }

    private void milestones(long now) {
        if (config.target <= 0) return;
        int pct = Text.percent(state.donated, config.target);
        int due = -1;
        for (int m : config.milestonePercents) {
            if (pct >= m && !state.milestonesDone.contains(m)) {
                state.milestonesDone.add(m);
                due = Math.max(due, m);
                markDirty();
            }
        }
        if (due > 0 && pct < 100) broadcast(Text.fill(config.messages.milestone, vars(now, Map.of("percent", due))));
    }

    /** Hands out candy (admin, DEV testing). Returns how many were given. */
    public int give(ServerPlayer p, int n) {
        ItemStack proto = pack.item(config.candyItemId);
        if (proto == null && config.candyFallbackItem != null && !config.candyFallbackItem.isEmpty()) {
            proto = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(config.candyFallbackItem)));
        }
        if (proto == null || proto.isEmpty()) return 0;
        int left = n;
        while (left > 0) {
            ItemStack st = proto.copy();
            int k = Math.min(left, st.getMaxStackSize());
            st.setCount(k);
            if (!p.getInventory().add(st)) p.drop(st, false);
            left -= k;
        }
        return n;
    }

    // ------------------------------------------------------------------ clients

    private boolean visible() {
        return switch (state.phase) {
            case IDLE -> config.showWhenIdle;
            case FAILED -> false;
            default -> true;
        };
    }

    private String clientPhase() {
        if (!visible()) return "hidden";
        return switch (state.phase) {
            case IDLE -> "idle";
            case COLLECTING -> "collecting";
            case COUNTDOWN -> "countdown";
            case ENDING -> "ending";
            case UNLOCKED -> "unlocked";
            case FAILED -> "failed";
        };
    }

    private JsonObject syncPayload() {
        long now = System.currentTimeMillis();
        JsonObject p = new JsonObject();
        p.addProperty("action", "wq_sync");
        JsonArray sites = new JsonArray();
        if (config.enabled && config.anchor.placed && brokenReason == null) {
            EventConfig.Anchor a = config.anchor;
            JsonObject o = new JsonObject();
            o.addProperty("id", config.id);
            o.addProperty("model", config.model);
            o.addProperty("dimension", a.dimension);
            o.addProperty("x", a.x);
            o.addProperty("y", a.y);
            o.addProperty("z", a.z);
            o.addProperty("yaw", a.yaw);
            o.addProperty("scale", config.scale);
            o.addProperty("phase", clientPhase());
            o.addProperty("donated", state.donated);
            o.addProperty("target", config.target);
            o.addProperty("progress", config.target <= 0 ? 1.0 : Math.min(1.0, state.donated / (double) config.target));
            double remaining = switch (state.phase) {
                case COLLECTING -> state.deadlineAt > 0 ? Math.max(0, state.deadlineAt - now) / 1000.0 : -1;
                case COUNTDOWN -> Math.max(0, state.countdownEndsAt - now) / 1000.0;
                default -> -1;
            };
            o.addProperty("remaining", remaining);
            if (state.phase == Phase.ENDING) o.addProperty("ends_elapsed", Math.max(0, now - state.endsStartedAt) / 1000.0);
            o.addProperty("sky", config.sky.amount);
            o.addProperty("sky_radius", config.sky.radius);
            o.addProperty("sky_at", config.slamAtSeconds);
            o.addProperty("sky_id", config.sky.id);
            o.addProperty("sky_reveal", config.sky.reveal);
            o.addProperty("sky_reveal_secs", config.sky.revealSeconds);
            o.addProperty("label", config.label.enabled);
            o.addProperty("label_y", config.label.y);
            o.addProperty("label_scale", config.label.scale);
            EventConfig.Messages m = config.messages;
            o.addProperty("title", m.labelTitle);
            o.addProperty("count_text", m.labelCount);
            o.addProperty("time_text", m.labelTime);
            o.addProperty("countdown_text", m.labelCountdown);
            o.addProperty("done_text", m.labelDone);
            sites.add(o);
        }
        p.add("sites", sites);
        return p;
    }

    private void syncNewClients() {
        synced.removeIf(u -> server.getPlayerList().getPlayer(u) == null);
        JsonObject payload = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (synced.contains(p.getUUID()) || !pack.has(p, FEATURE)) continue;
            if (payload == null) payload = syncPayload();
            pack.send(p, payload);
            synced.add(p.getUUID());
            log.info("[WorldQuest] event sync -> {}", p.getGameProfile().getName());
        }
    }

    public void resyncAll() {
        lastSyncAt = System.currentTimeMillis();
        lastSyncedDonated = state.donated;
        JsonObject payload = syncPayload();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (synced.contains(p.getUUID())) pack.send(p, payload);
        }
    }

    /** Admin: forget who was synced, send to everyone who can take it now, and say who could not and why. */
    public String forceSync() {
        synced.clear();
        StringBuilder sb = new StringBuilder();
        JsonObject payload = syncPayload();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean ok = pack.has(p, FEATURE);
            sb.append(p.getGameProfile().getName()).append(ok ? " ✔ " : " ✘ ").append(pack.why(p, FEATURE)).append('\n');
            if (ok) {
                pack.send(p, payload);
                synced.add(p.getUUID());
            }
        }
        log.info("[WorldQuest] event force sync ({} sites): {}", payload.getAsJsonArray("sites").size(), sb.toString().replace('\n', ';'));
        return sb.toString();
    }

    public void onJoin(ServerPlayer p) {
        synced.remove(p.getUUID()); // the hello arrives after JOIN; syncNewClients picks the player up then
    }

    private void pulse() {
        EventConfig.Anchor a = config.anchor;
        JsonObject p = new JsonObject();
        p.addProperty("action", "wq_pulse");
        p.addProperty("id", config.id);
        double r2 = config.pulseRadius * config.pulseRadius;
        for (ServerPlayer pl : playersInWorld()) {
            if (synced.contains(pl.getUUID()) && pl.distanceToSqr(a.x, a.y, a.z) <= r2) pack.send(pl, p);
        }
    }

    // ------------------------------------------------------------------ bar + text

    private void updateBar(long now) {
        if (!config.bar.enabled || !config.anchor.placed || !visible()
                || (state.phase == Phase.UNLOCKED && now - state.unlockedAt > config.bar.unlockedMinutes * 60_000L)) {
            bar.removeAllPlayers();
            return;
        }
        EventConfig.Messages m = config.messages;
        switch (state.phase) {
            case IDLE -> {
                bar.setName(Text.of(m.barIdle));
                bar.setColor(BossEvent.BossBarColor.PURPLE);
                bar.setProgress(0f);
            }
            case COLLECTING -> {
                bar.setName(Text.of(fill(m.barCollecting, now)));
                bar.setColor(BossEvent.BossBarColor.YELLOW);
                bar.setProgress(progress());
            }
            case COUNTDOWN -> {
                long left = Math.max(0, state.countdownEndsAt - now);
                bar.setName(Text.of(Text.fill(m.barCountdown, Map.of("time", countdownClock((int) Math.ceil(left / 1000.0))))));
                bar.setColor(BossEvent.BossBarColor.GREEN);
                bar.setProgress(Math.max(0f, Math.min(1f, left / (config.countdownSeconds * 1000f))));
            }
            default -> {
                bar.setName(Text.of(m.barUnlocked));
                bar.setColor(BossEvent.BossBarColor.GREEN);
                bar.setProgress(1f);
            }
        }
        bar.setVisible(true);
        Set<ServerPlayer> want = new HashSet<>(playersNearBar());
        for (ServerPlayer p : new ArrayList<>(bar.getPlayers())) if (!want.contains(p)) bar.removePlayer(p);
        for (ServerPlayer p : want) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
    }

    private List<ServerPlayer> playersInWorld() {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().dimension().location().toString().equals(config.anchor.dimension)) out.add(p);
        }
        return out;
    }

    private List<ServerPlayer> playersNearBar() {
        List<ServerPlayer> out = playersInWorld();
        if (config.bar.radius <= 0) return out;
        double r2 = config.bar.radius * config.bar.radius;
        out.removeIf(p -> {
            double dx = p.getX() - config.anchor.x, dz = p.getZ() - config.anchor.z;
            return dx * dx + dz * dz > r2;
        });
        return out;
    }

    private float progress() {
        return config.target <= 0 ? 1f : (float) Math.min(1.0, state.donated / (double) config.target);
    }

    private Map<String, Object> vars(long now, Map<String, ?> extra) {
        Map<String, Object> v = new HashMap<>();
        v.put("donated", Text.num(state.donated));
        v.put("target", Text.num(config.target));
        v.put("percent", Text.percent(state.donated, config.target));
        long left = switch (state.phase) {
            case COLLECTING -> state.deadlineAt - now;
            case COUNTDOWN -> state.countdownEndsAt - now;
            case IDLE -> (long) (config.defaultDurationHours * 3_600_000L);
            default -> 0;
        };
        v.put("time", longClock(Math.max(0, left)));
        v.putAll(extra);
        return v;
    }

    String fill(String template, long now) {
        return Text.fill(template, vars(now, Map.of()));
    }

    String fillFor(String template, long now, Map<String, ?> extra) {
        return Text.fill(template, vars(now, extra));
    }

    /** "6 วัน 23 ชม." / "5 ชม. 12 นาที" / "12 นาที". */
    static String longClock(long ms) {
        long m = ms / 60_000L, d = m / 1440, h = (m % 1440) / 60, mm = m % 60;
        if (d > 0) return d + " วัน " + h + " ชม.";
        if (h > 0) return h + " ชม. " + mm + " นาที";
        return Math.max(1, mm) + " นาที";
    }

    /** "10:00" / "0:30". */
    static String countdownClock(int secs) {
        return (secs / 60) + ":" + String.format("%02d", secs % 60);
    }

    private void broadcast(String legacy) {
        server.getPlayerList().broadcastSystemMessage(Text.of(legacy), false);
    }

    private static void actionBar(ServerPlayer p, String legacy) {
        p.connection.send(new ClientboundSetActionBarTextPacket(Text.of(legacy)));
    }

    private static void title(ServerPlayer p, String title, String subtitle, int in, int stay, int out) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Text.of(subtitle)));
        p.connection.send(new ClientboundSetTitleTextPacket(Text.of(title)));
    }

    private static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Top contributors, most candy first. */
    public List<Map.Entry<String, Contributor>> top(int n) {
        List<Map.Entry<String, Contributor>> l = new ArrayList<>(state.players.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue().count, a.getValue().count));
        return l.subList(0, Math.min(n, l.size()));
    }
}
