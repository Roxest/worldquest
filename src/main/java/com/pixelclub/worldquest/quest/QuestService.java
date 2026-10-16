package com.pixelclub.worldquest.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.pixelclub.worldquest.config.WqConfig;
import com.pixelclub.worldquest.config.WqConfig.ItemDef;
import com.pixelclub.worldquest.config.WqConfig.QuestDef;
import com.pixelclub.worldquest.econ.Econ;
import com.pixelclub.worldquest.econ.ImpactorEconomy;
import com.pixelclub.worldquest.econ.NoEconomy;
import com.pixelclub.worldquest.holo.HoloDisplay;
import com.pixelclub.worldquest.holo.HoloText;
import com.pixelclub.worldquest.offer.Confirmations;
import com.pixelclub.worldquest.offer.ItemRules;
import com.pixelclub.worldquest.state.Ledger;
import com.pixelclub.worldquest.state.StateStore;
import com.pixelclub.worldquest.state.WqState;
import com.pixelclub.worldquest.state.WqState.QuestState;
import com.pixelclub.worldquest.tower.TowerBridge;
import com.pixelclub.worldquest.util.Dispatch;
import com.pixelclub.worldquest.util.Text;
import com.pixelclub.worldquest.holo.Geometry;
import com.pixelclub.worldquest.util.AuthGate;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The quest engine. Everything that reads or writes quest state runs on the server thread; the only work that leaves
 * it is the Impactor withdraw (blocking, on {@code pxcworldquest-money}) and the tower's read-only lookups, whose
 * results come back through {@code server.execute}.
 *
 * <p>Invariants (checked at boot and every five minutes, logged as {@code [WorldQuest] INVARIANT}):
 * fund points = Σ player fund points; fund money = Σ player money; fund item points = Σ player item points;
 * state.json writes succeed. A mismatch is an ERROR line, never a silent correction.
 */
public final class QuestService {

    private static final long CONFIRM_TTL_MS = 30_000L;
    private static final long FLOOR_CACHE_MS = 60_000L;
    private static final long SEAL_CLICK_DEBOUNCE_MS = 350L;

    /** What the first right-click offered: the second one confirms it only for the same slot, item and count. */
    private record SealPrompt(String nonce, int slot, String itemId, int count, long at) {
    }

    private final MinecraftServer server;
    private final Logger log;
    private final Path configFile;
    private final StateStore store;
    private final Ledger ledger;
    private final TowerBridge tower;
    private final Confirmations confirms = new Confirmations();
    private final Map<String, HoloDisplay> holos = new HashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastHintAt = new HashMap<>();
    private final Map<UUID, int[]> floorCache = new HashMap<>(); // uuid -> {floor}
    private final Map<UUID, Long> floorCacheAt = new HashMap<>();
    private final Map<UUID, Long> lastConfirmAt = new HashMap<>();
    /** The last offer prompt a right-click on the seal opened, so the next right-click can confirm it. */
    private final Map<UUID, SealPrompt> sealPrompts = new HashMap<>();
    /** Arclight can hand one click to the use callbacks twice; one click is one click. */
    private final Map<UUID, Long> lastSealClickAt = new HashMap<>();
    /** Resolved particle types per quest (ambient, accent, burst); rebuilt with the holograms. */
    private final Map<String, ParticleOptions[]> questParticles = new HashMap<>();
    private final RandomSource rnd = RandomSource.create();
    private final ExecutorService moneyExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pxcworldquest-money");
        t.setDaemon(true);
        return t;
    });

    private WqConfig config = new WqConfig();
    private String configError;
    private Econ econ = new NoEconomy("not probed");
    private long ticks;
    private long nextSyncAt;
    private String lastInvariant = "not run";

    public QuestService(MinecraftServer server, Logger log, Path dir) {
        this.server = server;
        this.log = log;
        this.configFile = dir.resolve("config.json");
        this.store = new StateStore(dir.resolve("state.json"), log);
        this.ledger = new Ledger(dir.resolve("ledger"), log);
        this.tower = new TowerBridge(log);
    }

    // ================================================================== lifecycle

    public void start() {
        loadConfig();
        store.load();
        econ = ImpactorEconomy.probe(log);
        tower.probe();
        rebuildHolos();
        rewardSweep();
        checkInvariants();
        nextSyncAt = ticks + 20L * 20; // first tower sync 20 s after boot, once every mod is up
        log.info("[WorldQuest] started — quests={} economy={} tower={} state={}", config.quests.size(),
                econ.describe(), tower.describe(), store.broken() ? "BROKEN" : "ok");
    }

    public String reload() {
        loadConfig();
        rebuildHolos();
        rewardSweep();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            payOwed(p);
        }
        return configError == null ? "ok" : "config error: " + configError;
    }

    private void loadConfig() {
        try {
            config = WqConfig.load(configFile);
            configError = null;
        } catch (IOException e) {
            configError = e.getMessage();
            log.error("[WorldQuest] {} — keeping the previous config", configError);
        }
    }

    public void stop() {
        for (HoloDisplay h : holos.values()) {
            h.hideAll(server);
        }
        holos.clear();
        moneyExec.shutdown();
        try {
            if (!moneyExec.awaitTermination(10, TimeUnit.SECONDS)) {
                log.error("[WorldQuest] a money donation was still running at shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        store.save();
        store.close();
        ledger.close();
    }

    public void tick() {
        ticks++;
        if (ticks % 20 == 0) {
            refreshHolos();
            personalHints();
        }
        if (ticks % 5 == 0) {
            ambientParticles();
        }
        if (ticks % 200 == 0) {
            confirms.sweep();
        }
        if (ticks >= nextSyncAt) {
            QuestDef q = primary();
            int minutes = q == null ? 10 : Math.max(1, q.conquerors.syncMinutes);
            nextSyncAt = ticks + 20L * 60 * minutes;
            if (q != null && q.conquerors.enabled && q.conquerors.towerSync && tower.available()) {
                sync(q, null);
            }
        }
        if (ticks % (20L * 60 * 5) == 0) {
            checkInvariants();
        }
    }

    public void onJoin(ServerPlayer p) {
        for (HoloDisplay h : holos.values()) {
            h.forget(p.getUUID());
        }
        for (QuestDef q : config.quests) {
            QuestState s = store.state().quests.get(q.id);
            if (s != null && s.players.containsKey(p.getUUID().toString())) {
                s.players.get(p.getUUID().toString()).name = p.getGameProfile().getName();
            }
        }
        payOwed(p);
    }

    public void onRespawnOrWorldChange(ServerPlayer p) {
        for (HoloDisplay h : holos.values()) {
            h.forget(p.getUUID());
        }
    }

    // ================================================================== accessors

    public WqConfig config() {
        return config;
    }

    public QuestDef primary() {
        for (QuestDef q : config.quests) {
            if (q.enabled) return q;
        }
        return null;
    }

    public QuestDef quest(String id) {
        return id == null ? primary() : config.quest(id);
    }

    public QuestState state(QuestDef q) {
        return store.state().quest(q.id);
    }

    public boolean frozen() {
        return store.broken();
    }

    public TowerBridge tower() {
        return tower;
    }

    public Econ econ() {
        return econ;
    }

    private String msg(String template, Map<String, ?> values) {
        return Text.fill(template, values);
    }

    private void tell(ServerPlayer p, String legacy) {
        p.sendSystemMessage(Text.of(config.messages.prefix + legacy));
    }

    /** Every player on this backend, and (X4, LPC relay on) on the other backends: one network fund. */
    private void broadcast(String legacy) {
        Component line = Text.of(config.messages.prefix + legacy);
        server.getPlayerList().broadcastSystemMessage(line, false);
        com.pixelclub.worldquest.util.NetRelay.toOtherBackends(line);
    }

    // ================================================================== conquerors

    /**
     * Credits one conqueror. Idempotent: a uuid already credited only refreshes its name.
     * @return true when this call added a new conqueror
     */
    public boolean creditConqueror(QuestDef q, UUID uuid, String name, long at, String source, boolean announce) {
        if (frozen()) return false;
        QuestState s = state(q);
        String key = uuid.toString();
        WqState.Conqueror existing = s.conquerors.get(key);
        if (existing != null) {
            if (name != null && !name.isBlank()) existing.name = name;
            return false;
        }
        int before = s.conquerors.size();
        WqState.Conqueror c = new WqState.Conqueror();
        c.name = name;
        c.at = at > 0 ? at : System.currentTimeMillis();
        c.source = source;
        s.conquerors.put(key, c);
        s.player(key, name);
        int count = s.conquerors.size();
        ledger.record("CREDIT", ordered("quest", q.id, "uuid", key, "name", name, "source", source,
                "at", c.at, "count", count));
        if (announce) {
            Map<String, Object> v = Map.of("player", name == null ? "?" : name, "count", count,
                    "target", q.conquerors.target);
            broadcast(msg(config.messages.conquerAnnounce, v));
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                title(p, msg(config.messages.conquerSelfTitle, v), msg(config.messages.conquerSelfSubtitle, v));
                p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 1f, 1.2f);
            }
        }
        checkTiers(q, s, key, name, announce);
        conquerorMilestones(q, s, before, count, announce);
        checkCompletion(q, s);
        store.save();
        return true;
    }

    public boolean uncredit(QuestDef q, UUID uuid, String by) {
        if (frozen()) return false;
        QuestState s = state(q);
        WqState.Conqueror c = s.conquerors.remove(uuid.toString());
        if (c == null) return false;
        ledger.record("UNCREDIT", ordered("quest", q.id, "uuid", uuid.toString(), "name", c.name, "by", by));
        store.save();
        return true;
    }

    /** Pull the conqueror list from the tower; missing ones are credited (announced only if the conquest is fresh). */
    public void sync(QuestDef q, Consumer<String> reply) {
        var future = tower.conquerors();
        if (future == null) {
            if (reply != null) reply.accept("tower API unavailable: " + tower.describe());
            return;
        }
        future.whenComplete((map, err) -> server.execute(() -> {
            if (err != null || map == null) {
                String why = err == null ? "null result" : String.valueOf(err);
                log.error("[WorldQuest] tower sync failed: {}", why);
                if (reply != null) reply.accept("sync failed: " + why);
                return;
            }
            long now = System.currentTimeMillis();
            long freshMs = 60_000L * Math.max(0, q.conquerors.announceSyncedWithinMinutes);
            int added = 0;
            for (Map.Entry<UUID, Long> e : map.entrySet()) {
                UUID id = e.getKey();
                if (state(q).conquerors.containsKey(id.toString())) continue;
                long at = e.getValue() == null ? 0L : e.getValue();
                boolean fresh = at > 0 && now - at <= freshMs;
                if (creditConqueror(q, id, nameOf(id), at, "sync", fresh)) added++;
            }
            String line = "sync: tower reports " + map.size() + " conquerors, " + added + " newly credited, quest has "
                    + state(q).conquerors.size();
            if (added > 0 || reply != null) log.info("[WorldQuest] {}", line);
            if (reply != null) reply.accept(line);
        }));
    }

    /**
     * Seed file: a JSON object {@code {"<uuid>": {"name": "...", "at": <epoch ms>}}} or {@code {"<uuid>": "<name>"}}.
     * Credits silently. Returns a one-line report.
     */
    public String importConquerors(QuestDef q, Path file) {
        if (frozen()) return "state is frozen: " + store.brokenReason();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            int added = 0;
            int seen = 0;
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                UUID id;
                try {
                    id = UUID.fromString(e.getKey());
                } catch (IllegalArgumentException bad) {
                    continue;
                }
                seen++;
                String name = null;
                long at = 0;
                if (e.getValue().isJsonObject()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    if (o.has("name")) name = o.get("name").getAsString();
                    if (o.has("at")) at = o.get("at").getAsLong();
                } else if (e.getValue().isJsonPrimitive()) {
                    name = e.getValue().getAsString();
                }
                if (name == null) name = nameOf(id);
                if (creditConqueror(q, id, name, at, "import", false)) added++;
            }
            String line = "import " + file.getFileName() + ": " + seen + " uuids, " + added + " newly credited, quest has "
                    + state(q).conquerors.size();
            log.info("[WorldQuest] {}", line);
            return line;
        } catch (IOException | RuntimeException e) {
            return "import failed: " + e;
        }
    }

    private void conquerorMilestones(QuestDef q, QuestState s, int before, int after, boolean announce) {
        for (int m : q.conquerorMilestones) {
            String key = "c:" + m;
            if (before < m && after >= m && !s.firedMilestones.contains(key) && m < q.conquerors.target) {
                s.firedMilestones.add(key);
                if (announce) {
                    broadcast(msg(config.messages.conquerorMilestone, Map.of("count", after,
                            "target", q.conquerors.target, "left", q.conquerors.target - after, "quest", q.name)));
                }
            }
        }
    }

    // ================================================================== money

    public void requestDonation(ServerPlayer p, QuestDef q, long amount) {
        WqConfig.Messages m = config.messages;
        if (!acceptsFund(q, p, true)) return;
        long per = config.moneyPerPoint;
        long min = Math.max(per, q.fund.minMoney);
        if (amount < min) {
            tell(p, msg(m.donateTooSmall, Map.of("min", Text.num(min))));
            return;
        }
        if (amount > q.fund.maxMoneyPerDonation) {
            tell(p, msg(m.donateTooLarge, Map.of("max", Text.num(q.fund.maxMoneyPerDonation))));
            return;
        }
        long points = amount / per;
        long charge = points * per; // round down to whole points: nothing is burned without a point for it
        Confirmations.Pending pending = confirms.put(p.getUUID(), Confirmations.Kind.DONATE, q.id, charge, points,
                null, null, CONFIRM_TTL_MS);
        prompt(p, msg(m.donateConfirm, Map.of("amount", Text.num(charge), "fund", q.fund.label,
                "points", Text.num(points))), pending);
    }

    private boolean acceptsFund(QuestDef q, ServerPlayer p, boolean money) {
        WqConfig.Messages m = config.messages;
        if (frozen()) {
            tell(p, m.noQuest);
            return false;
        }
        QuestState s = state(q);
        boolean phaseOk = WqState.Phase.ACTIVE.equals(s.phase) || q.acceptAfterReached;
        boolean kindOk = q.fund.enabled && (money ? q.fund.acceptMoney : q.fund.acceptItems);
        if (!phaseOk || !kindOk) {
            tell(p, msg(m.notAccepting, Map.of("fund", q.fund.label)));
            return false;
        }
        if (money && !econ.available()) {
            tell(p, m.donateNoEconomy);
            return false;
        }
        if (inFlight.contains(p.getUUID())) {
            tell(p, m.donateBusy);
            return false;
        }
        return true;
    }

    private void prompt(ServerPlayer p, String line, Confirmations.Pending pending) {
        logPrompt(p, pending);
        WqConfig.Messages m = config.messages;
        MutableComponent c = Text.of(m.prefix + line + " ");
        c.append(Text.button(m.confirmButton, "/worldquest confirm " + pending.nonce,
                msg(m.confirmHover, Map.of("seconds", CONFIRM_TTL_MS / 1000))));
        c.append(Component.literal(" "));
        c.append(Text.button(m.cancelButton, "/worldquest cancel " + pending.nonce, null));
        p.sendSystemMessage(c);
    }

    /**
     * One line per prompt: what was offered and its nonce — so "I clicked and nothing happened" can be answered
     * from the log. The nonce is harmless in the log: it only works for its own player and for 30 s.
     */
    private void logPrompt(ServerPlayer p, Confirmations.Pending pending) {
        log.info("[WorldQuest] prompt {} player={} amount={} points={} item={} slots={} nonce={}", pending.kind,
                p.getGameProfile().getName(), pending.amount, pending.points, pending.itemId, pending.slots.size(),
                pending.nonce);
    }

    public void cancel(ServerPlayer p, String nonce) {
        if (confirms.take(p.getUUID(), nonce) != null) {
            tell(p, config.messages.cancelled);
        }
    }

    public void confirm(ServerPlayer p, String nonce) {
        long now = System.currentTimeMillis();
        Long last = lastConfirmAt.get(p.getUUID());
        if (last != null && now - last < 1_000L) {
            return; // a double click is one click
        }
        lastConfirmAt.put(p.getUUID(), now);
        Confirmations.Pending pending = confirms.take(p.getUUID(), nonce);
        if (pending == null) {
            tell(p, config.messages.confirmExpired);
            return;
        }
        QuestDef q = config.quest(pending.questId);
        if (q == null) {
            tell(p, config.messages.noQuest);
            return;
        }
        if (pending.kind == Confirmations.Kind.DONATE) {
            startWithdraw(p, q, pending.amount, pending.points);
        } else {
            finishOffer(p, q, pending);
        }
    }

    private void startWithdraw(ServerPlayer p, QuestDef q, long amount, long points) {
        if (!acceptsFund(q, p, true)) return;
        UUID uuid = p.getUUID();
        String name = p.getGameProfile().getName();
        String ref = Long.toString(System.currentTimeMillis(), 36) + "-" + uuid.toString().substring(0, 4);
        inFlight.add(uuid);
        ledger.record("DONATE_INTENT", ordered("quest", q.id, "ref", ref, "uuid", uuid.toString(), "name", name,
                "amount", amount, "points", points));
        moneyExec.execute(() -> {
            Econ.Move move;
            try {
                move = econ.withdraw(uuid, BigDecimal.valueOf(amount), "worldquest:" + q.id + ":" + ref);
            } catch (Throwable t) {
                move = Econ.Move.fail("withdraw threw " + t);
            }
            final Econ.Move result = move;
            server.execute(() -> {
                try {
                    finishDonation(uuid, name, q, amount, points, ref, result);
                } finally {
                    inFlight.remove(uuid);
                }
            });
        });
    }

    private void finishDonation(UUID uuid, String name, QuestDef q, long amount, long points, String ref,
                                Econ.Move move) {
        WqConfig.Messages m = config.messages;
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        Map<String, Object> row = ordered("quest", q.id, "ref", ref, "uuid", uuid.toString(), "name", name,
                "amount", amount, "points", points, "result", move.result(),
                "before", move.before() == null ? null : move.before().toPlainString(),
                "after", move.after() == null ? null : move.after().toPlainString(),
                "readBackOk", move.readBackOk(), "attempts", move.attempts(), "error", move.error());
        if (move.ok()) {
            ledger.record("DONATE_OK", row);
            QuestState s = state(q);
            addFund(q, s, uuid.toString(), name, points, amount, 0L);
            if (p != null) {
                tell(p, msg(m.donateDone, Map.of("amount", Text.num(amount), "mine",
                        Text.num(personalPoints(q, s, uuid.toString())), "fundPoints", Text.num(s.fundPoints),
                        "fundTarget", Text.num(q.fund.targetPoints))));
                p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.8f, 1.1f);
            }
            contributionFx(q, p);
            if (q.announceDonationAtLeast > 0 && amount >= q.announceDonationAtLeast) {
                broadcast(msg(m.donationAnnounce, Map.of("player", name, "amount", Text.baht(amount),
                        "fund", q.fund.label)));
            }
            store.save();
            return;
        }
        if (move.undecided()) {
            ledger.record("DONATE_UNDECIDED", row);
            log.error("[WorldQuest] donation {} by {} UNDECIDED — check Impactor balance by hand: {}", ref, name,
                    move.error());
            if (p != null) tell(p, msg(m.donateUndecided, Map.of("ref", ref)));
            return;
        }
        ledger.record("DONATE_REFUSED", row);
        if (p != null) {
            if ("NOT_ENOUGH_FUNDS".equals(move.result())) {
                String bal = move.before() == null ? "?" : Text.num(move.before().longValue());
                tell(p, msg(m.donateNoFunds, Map.of("balance", bal)));
            } else {
                tell(p, msg(m.donateFailed, Map.of("reason", String.valueOf(move.error()))));
            }
        }
    }

    // ================================================================== items

    /** Opens an offer prompt for the main-hand stack (all = every stack of it). Returns the prompt, or null when refused. */
    public Confirmations.Pending requestOffer(ServerPlayer p, QuestDef q, boolean all) {
        WqConfig.Messages m = config.messages;
        if (!acceptsFund(q, p, false)) return null;
        ItemStack held = p.getMainHandItem();
        Map<String, ItemDef> index = config.itemIndex();
        ItemRules.Verdict v = ItemRules.check(held, index);
        Component itemName = held.isEmpty() ? Component.empty() : ItemRules.nameOf(held, v.def());
        switch (v.kind()) {
            case EMPTY -> {
                tell(p, m.offerEmptyHand);
                return null;
            }
            case NOT_ACCEPTED -> {
                p.sendSystemMessage(withItem(m.prefix + m.offerNotAccepted, itemName));
                return null;
            }
            case WORN -> {
                p.sendSystemMessage(withItem(m.prefix + m.offerWorn, itemName));
                return null;
            }
            case SPECIAL -> {
                p.sendSystemMessage(withItem(m.prefix + m.offerSpecial, itemName));
                return null;
            }
            default -> {
            }
        }
        List<Confirmations.SlotSnap> slots = new ArrayList<>();
        int selected = p.getInventory().selected;
        long count;
        if (all) {
            count = 0;
            var items = p.getInventory().items;
            for (int i = 0; i < items.size(); i++) {
                ItemStack st = items.get(i);
                if (!st.isEmpty() && ItemStack.isSameItemSameComponents(st, held)) {
                    slots.add(new Confirmations.SlotSnap(i, st.copy()));
                    count += st.getCount();
                }
            }
        } else {
            slots.add(new Confirmations.SlotSnap(selected, held.copy()));
            count = held.getCount();
        }
        long points = count * v.def().points;
        Confirmations.Pending pending = confirms.put(p.getUUID(), Confirmations.Kind.OFFER, q.id, count, points,
                v.itemId(), slots, CONFIRM_TTL_MS);
        logPrompt(p, pending);
        String line = msg(m.offerConfirm, Map.of("count", Text.num(count), "points", Text.num(points)));
        MutableComponent c = withItem(m.prefix + line + " ", itemName);
        c.append(Text.button(m.confirmButton, "/worldquest confirm " + pending.nonce,
                msg(m.confirmHover, Map.of("seconds", CONFIRM_TTL_MS / 1000))));
        c.append(Component.literal(" "));
        c.append(Text.button(m.cancelButton, "/worldquest cancel " + pending.nonce, null));
        p.sendSystemMessage(c);
        return pending;
    }

    private void finishOffer(ServerPlayer p, QuestDef q, Confirmations.Pending pending) {
        WqConfig.Messages m = config.messages;
        if (!acceptsFund(q, p, false)) return;
        var items = p.getInventory().items;
        Map<String, ItemDef> index = config.itemIndex();
        ItemDef def = index.get(pending.itemId);
        if (def == null) {
            tell(p, m.offerChanged);
            return;
        }
        // Re-validate every slot NOW: same item, same count, and the stack as it is now still passes the item
        // rules (points depend only on id x count). Not full component equality: on dev a plain stack failed
        // ItemStack.matches against its own prompt-time copy with nothing visible changed (09-28), so something
        // touches components in between; the difference is logged so the mod doing it can be named.
        for (Confirmations.SlotSnap snap : pending.slots) {
            ItemStack now = snap.slot() >= 0 && snap.slot() < items.size() ? items.get(snap.slot()) : ItemStack.EMPTY;
            ItemStack then = snap.copy();
            ItemRules.Verdict v = ItemRules.check(now, index);
            boolean same = !now.isEmpty() && now.getItem() == then.getItem() && now.getCount() == then.getCount()
                    && v.ok() && pending.itemId.equals(v.itemId());
            if (!same) {
                log.info("[WorldQuest] offer refused at confirm: player={} slot={} then={}x{} now={}x{} verdict={}",
                        p.getGameProfile().getName(), snap.slot(), ItemRules.idOf(then), then.getCount(),
                        now.isEmpty() ? "empty" : ItemRules.idOf(now), now.getCount(), v.kind());
                tell(p, m.offerChanged);
                return;
            }
            if (!ItemStack.matches(now, then)) {
                log.info("[WorldQuest] offer slot {} of {} changed components between prompt and confirm: then={} now={}",
                        snap.slot(), p.getGameProfile().getName(), then.getComponentsPatch(), now.getComponentsPatch());
            }
        }
        long count = 0;
        for (Confirmations.SlotSnap snap : pending.slots) {
            count += items.get(snap.slot()).getCount();
            items.set(snap.slot(), ItemStack.EMPTY);
        }
        p.getInventory().setChanged();
        p.containerMenu.broadcastChanges();
        long points = count * def.points;
        QuestState s = state(q);
        s.itemCounts.merge(pending.itemId, count, Long::sum);
        ledger.record("OFFER_OK", ordered("quest", q.id, "uuid", p.getUUID().toString(),
                "name", p.getGameProfile().getName(), "item", pending.itemId, "count", count, "pointsEach", def.points,
                "points", points, "slots", pending.slots.size()));
        addFund(q, s, p.getUUID().toString(), p.getGameProfile().getName(), points, 0L, points);
        Component itemName = ItemRules.nameOf(pending.slots.get(0).copy(), def);
        p.sendSystemMessage(withItem(m.prefix + msg(m.offerDone, Map.of("count", Text.num(count),
                "mine", Text.num(personalPoints(q, s, p.getUUID().toString())),
                "fundPoints", Text.num(s.fundPoints), "fundTarget", Text.num(q.fund.targetPoints))), itemName));
        p.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.MASTER, 1f, 1f);
        contributionFx(q, p);
        store.save();
    }

    // ================================================================== the seal: right-click + effects

    /**
     * A right-click with something in hand (use-item on air, or use-on-block). True = it was aimed at a seal and is
     * handled here (the caller cancels the vanilla use); false = not ours, vanilla goes on.
     *
     * <p>Only the main hand, only while the fund takes items, only for a session AuthMe has logged in: this path
     * never passes AuthMe's command block, and it burns items. First click opens the usual prompt (chat buttons still
     * work); a second click on the seal within rightClickConfirmSeconds, same slot, same item, same count, confirms
     * it through {@link #confirm}, which re-checks everything. Sneaking offers every stack of the item.
     */
    public boolean onUse(ServerPlayer p, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || holos.isEmpty()) return false;
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1f);
        for (Map.Entry<String, HoloDisplay> e : holos.entrySet()) {
            HoloDisplay h = e.getValue();
            if (h.level() != p.serverLevel() || !h.hasSeal()) continue;
            QuestDef q = config.quest(e.getKey());
            if (q == null || !q.hologram.rightClickOffer || !q.fund.enabled || !q.fund.acceptItems) continue;
            double reach = Math.max(1.0, q.hologram.rightClickReach);
            double t = h.sealEntry(eye, look, reach);
            if (t < 0) continue;
            Vec3 at = eye.add(look.scale(t));
            BlockHitResult block = p.serverLevel().clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, p));
            if (block.getType() != HitResult.Type.MISS) continue; // a block stands in front of the seal
            if (!AuthGate.notImpostor(p, log)) return false;  // AuthMe denies the use itself
            long now = System.currentTimeMillis();
            Long last = lastSealClickAt.put(p.getUUID(), now);
            if (last != null && now - last < SEAL_CLICK_DEBOUNCE_MS) return true;
            sealClick(p, q, now);
            return true;
        }
        return false;
    }

    private void sealClick(ServerPlayer p, QuestDef q, long now) {
        ItemStack held = p.getMainHandItem();
        int slot = p.getInventory().selected;
        SealPrompt sp = sealPrompts.remove(p.getUUID());
        if (sp != null && now - sp.at() <= q.hologram.rightClickConfirmSeconds * 1000L && sp.slot() == slot
                && !held.isEmpty() && held.getCount() == sp.count() && ItemRules.idOf(held).equals(sp.itemId())) {
            confirm(p, sp.nonce());
            return;
        }
        Confirmations.Pending pending = requestOffer(p, q, p.isShiftKeyDown());
        if (pending != null) {
            sealPrompts.put(p.getUUID(), new SealPrompt(pending.nonce, slot, pending.itemId, held.getCount(), now));
            p.displayClientMessage(Text.of(config.messages.rightClickConfirmHint), true);
            p.playNotifySound(SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.MASTER, 0.7f, 1.2f);
        }
    }

    /** The giver hears it; everyone near sees the seal flare. */
    private void contributionFx(QuestDef q, ServerPlayer giver) {
        WqConfig.Particles pc = q.hologram.particles;
        HoloDisplay h = holos.get(q.id);
        ParticleOptions[] types = questParticles.get(q.id);
        if (pc != null && pc.enabled && h != null && types != null) {
            h.burst(types[2], pc.burstCount, Math.max(8, pc.range), rnd);
        }
        if (giver != null && pc != null && pc.contributionSound != null && !pc.contributionSound.isBlank()) {
            ResourceLocation id = ResourceLocation.tryParse(pc.contributionSound);
            var sound = id == null ? Optional.<SoundEvent>empty() : BuiltInRegistries.SOUND_EVENT.getOptional(id);
            sound.ifPresent(s -> giver.playNotifySound(s, SoundSource.MASTER, 0.9f, 1.0f));
        }
    }

    private void ambientParticles() {
        for (Map.Entry<String, HoloDisplay> e : holos.entrySet()) {
            QuestDef q = config.quest(e.getKey());
            ParticleOptions[] types = questParticles.get(e.getKey());
            if (q == null || types == null || types[0] == null) continue;
            WqConfig.Particles pc = q.hologram.particles;
            // perSecond packets, spread over the four steps a second; each step at least one
            int packets = Math.max(1, Math.round(pc.perSecond / 4f));
            boolean accent = types[1] != null && progressPercent(q, state(q)) >= pc.accentAtPercent;
            e.getValue().ambient(types[0], accent ? types[1] : null, packets, Math.max(8, pc.range), rnd);
        }
    }

    /** A simple (option-less) particle type by id, or null with one log line. */
    private ParticleOptions particle(String questId, String id) {
        if (id == null || id.isBlank()) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        var type = rl == null ? Optional.<net.minecraft.core.particles.ParticleType<?>>empty()
                : BuiltInRegistries.PARTICLE_TYPE.getOptional(rl);
        if (type.isPresent() && type.get() instanceof SimpleParticleType simple) return simple;
        log.error("[WorldQuest] particle {} of {} is not a simple particle type — skipped", id, questId);
        return null;
    }

    /** Replaces the first {item} with a (translatable) component so each client renders its own language. */
    private static MutableComponent withItem(String legacy, Component item) {
        int at = legacy.indexOf("{item}");
        if (at < 0) return Text.of(legacy);
        MutableComponent c = Text.of(legacy.substring(0, at));
        c.append(Component.empty().append(item).withStyle(net.minecraft.ChatFormatting.YELLOW));
        c.append(Text.of(carryColor(legacy.substring(0, at)) + legacy.substring(at + "{item}".length())));
        return c;
    }

    /** The last &-colour before a split point, so the text after an inserted component keeps its colour. */
    private static String carryColor(String before) {
        for (int i = before.length() - 2; i >= 0; i--) {
            char c = before.charAt(i);
            if ((c == '&' || c == '§') && "0123456789abcdefABCDEF".indexOf(before.charAt(i + 1)) >= 0) {
                return "&" + before.charAt(i + 1);
            }
        }
        return "";
    }

    public List<String> itemLines() {
        List<String> out = new ArrayList<>();
        out.add(config.messages.itemsHeader);
        for (ItemDef d : config.itemIndex().values()) {
            String name = d.displayName != null && !d.displayName.isBlank() ? d.displayName : d.id;
            out.add(msg(config.messages.itemsLine, Map.of("item", name, "points", Text.num(d.points))));
        }
        return out;
    }

    // ================================================================== fund, tiers, completion

    private void addFund(QuestDef q, QuestState s, String uuid, String name, long points, long money, long itemPoints) {
        long before = s.fundPoints;
        s.fundPoints += points;
        s.fundMoney += money;
        s.fundItemPoints += itemPoints;
        WqState.PlayerRec r = s.player(uuid, name);
        long now = System.currentTimeMillis();
        if (r.firstAt == 0) r.firstAt = now;
        r.lastAt = now;
        r.fundPoints += points;
        r.money += money;
        r.itemPoints += itemPoints;
        checkTiers(q, s, uuid, name, true);
        fundMilestones(q, s, before, s.fundPoints);
        checkCompletion(q, s);
    }

    public long personalPoints(QuestDef q, QuestState s, String uuid) {
        WqState.PlayerRec r = s.players.get(uuid);
        long pts = r == null ? 0 : r.fundPoints;
        if (s.conquerors.containsKey(uuid)) pts += q.conquestBonusPoints;
        return pts;
    }

    public WqConfig.Tier tierOf(QuestDef q, long points) {
        WqConfig.Tier best = null;
        for (WqConfig.Tier t : q.tiers) {
            if (points >= t.points) best = t;
        }
        return best;
    }

    /**
     * Announces newly reached tiers (once, tierGranted) and delivers each reached tier's reward commands once
     * (tiersRewarded) — run now for an online player, queued otherwise. The two are tracked apart so commands
     * added to a tier after players reached it are still delivered: {@link #rewardSweep()} re-runs this for
     * everyone at boot and reload.
     */
    private boolean checkTiers(QuestDef q, QuestState s, String uuid, String name, boolean tellPlayer) {
        long pts = personalPoints(q, s, uuid);
        WqState.PlayerRec r = s.player(uuid, name);
        if (r.tiersRewarded == null) r.tiersRewarded = new java.util.TreeSet<>();
        ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(uuid));
        boolean changed = false;
        for (int i = 0; i < q.tiers.size(); i++) {
            WqConfig.Tier t = q.tiers.get(i);
            if (pts < t.points) break;
            if (i > r.tierGranted) {
                r.tierGranted = i;
                changed = true;
                ledger.record("TIER", ordered("quest", q.id, "uuid", uuid, "name", r.name, "tier", t.id,
                        "points", pts));
                if (p != null && tellPlayer) {
                    tell(p, msg(config.messages.tierReached, Map.of("tier", t.name, "points", Text.num(pts))));
                }
            }
            if (t.commands != null && !t.commands.isEmpty() && r.tiersRewarded.add(t.id)) {
                changed = true;
                List<String> cmds = expand(t.commands, uuid, r.name, t, pts);
                ledger.record("TIER_REWARD", ordered("quest", q.id, "uuid", uuid, "name", r.name, "tier", t.id,
                        "commands", cmds.size(), "delivery", p != null ? "now" : "queued"));
                if (p != null) {
                    for (String c : cmds) Dispatch.console(server, log, c);
                } else {
                    s.owe(uuid, cmds);
                }
            }
        }
        return changed;
    }

    /** Backfill: every recorded player gets any tier reward they reached but never received (boot, reload). */
    private void rewardSweep() {
        if (frozen()) return;
        boolean changed = false;
        for (QuestDef q : config.quests) {
            QuestState s = store.state().quests.get(q.id);
            if (s == null) continue;
            for (String uuid : new ArrayList<>(s.players.keySet())) {
                changed |= checkTiers(q, s, uuid, null, false);
            }
        }
        if (changed) store.save();
    }

    private static List<String> expand(List<String> commands, String uuid, String name, WqConfig.Tier t, long pts) {
        List<String> out = new ArrayList<>();
        if (commands == null) return out;
        for (String c : commands) {
            out.add(c.replace("%player%", name == null ? uuid : name).replace("%uuid%", uuid)
                    .replace("%tier%", t.id).replace("%points%", Long.toString(pts)));
        }
        return out;
    }

    private void payOwed(ServerPlayer p) {
        String key = p.getUUID().toString();
        boolean changed = false;
        for (QuestDef q : config.quests) {
            QuestState s = store.state().quests.get(q.id);
            if (s == null) continue;
            List<String> owed = s.pendingCommands.remove(key);
            if (owed == null || owed.isEmpty()) continue;
            changed = true;
            String name = p.getGameProfile().getName();
            ledger.record("OWED_PAID", ordered("quest", q.id, "uuid", key, "name", name, "commands", owed.size()));
            for (String c : owed) {
                Dispatch.console(server, log, c.replace("%player%", name));
            }
        }
        if (changed) store.save();
    }

    private void fundMilestones(QuestDef q, QuestState s, long before, long after) {
        for (int pct : q.fundMilestonesPercent) {
            long mark = q.fund.targetPoints * pct / 100L;
            String key = "f:" + pct;
            if (before < mark && after >= mark && !s.firedMilestones.contains(key)) {
                s.firedMilestones.add(key);
                broadcast(msg(config.messages.fundMilestone, Map.of("fund", q.fund.label, "percent", pct,
                        "points", Text.num(after), "target", Text.num(q.fund.targetPoints))));
            }
        }
    }

    private boolean goalsMet(QuestDef q, QuestState s) {
        boolean c = !q.conquerors.enabled || s.conquerors.size() >= q.conquerors.target;
        boolean f = !q.fund.enabled || s.fundPoints >= q.fund.targetPoints;
        return c && f;
    }

    private void checkCompletion(QuestDef q, QuestState s) {
        if (!WqState.Phase.ACTIVE.equals(s.phase) || !goalsMet(q, s)) return;
        s.phase = WqState.Phase.REACHED;
        s.reachedAt = System.currentTimeMillis();
        ledger.record("REACHED", ordered("quest", q.id, "conquerors", s.conquerors.size(),
                "fundPoints", s.fundPoints, "fundMoney", s.fundMoney, "fundItemPoints", s.fundItemPoints,
                "players", s.players.size()));
        Map<String, Object> v = Map.of("quest", q.name);
        broadcast(msg(config.messages.reachedLine1, v));
        broadcast(msg(config.messages.reachedLine2, v));
        celebrate(q, s, msg(config.messages.reachedTitle, v), msg(config.messages.reachedSubtitle, v));
        for (String c : q.onReachedCommands) Dispatch.console(server, log, c);
        if ("auto".equalsIgnoreCase(q.openMode)) {
            open(q, "auto");
        }
    }

    /** Admin (or auto) opening. Allowed from REACHED; from ACTIVE only with force. */
    public String open(QuestDef q, String by) {
        QuestState s = state(q);
        if (WqState.Phase.OPENED.equals(s.phase)) return "already opened";
        boolean force = by != null && by.startsWith("force:");
        if (!WqState.Phase.REACHED.equals(s.phase) && !force) {
            return "not reached yet (" + s.conquerors.size() + "/" + q.conquerors.target + " conquerors, "
                    + s.fundPoints + "/" + q.fund.targetPoints + " points) — use 'open <quest> force' to override";
        }
        s.phase = WqState.Phase.OPENED;
        s.openedAt = System.currentTimeMillis();
        int contributors = s.players.size();
        ledger.record("OPENED", ordered("quest", q.id, "by", by, "contributors", contributors));
        Map<String, Object> v = Map.of("quest", q.name, "contributors", contributors);
        broadcast(msg(config.messages.openedLine1, v));
        broadcast(msg(config.messages.openedLine2, v));
        celebrate(q, s, msg(config.messages.openedTitle, v), msg(config.messages.openedSubtitle, v));
        for (String c : q.onOpenedCommands) Dispatch.console(server, log, c);
        store.save();
        return "opened";
    }

    private void celebrate(QuestDef q, QuestState s, String title, String subtitle) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            title(p, title, subtitle);
            p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1f, 1f);
        }
        HoloDisplay h = holos.get(q.id);
        if (h != null) {
            ServerLevel level = h.level();
            Vec3 a = h.anchor();
            if (level.isLoaded(BlockPos.containing(a))) {
                int[][] palettes = {{0xFFD54F, 0xFFA000}, {0x81D4FA, 0xFFFFFF}, {0xCE93D8, 0xFFD54F}};
                for (int i = 0; i < 6; i++) {
                    int[] colors = palettes[i % palettes.length];
                    ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
                    rocket.set(DataComponents.FIREWORKS, new Fireworks(1 + (i % 2), List.of(new FireworkExplosion(
                            i % 2 == 0 ? FireworkExplosion.Shape.LARGE_BALL : FireworkExplosion.Shape.STAR,
                            IntList.of(colors), IntList.of(0xFFFFFF), true, true))));
                    double dx = (i - 2.5) * 1.6;
                    FireworkRocketEntity e = new FireworkRocketEntity(level, a.x + dx, a.y + 1, a.z + (i % 3 - 1),
                            rocket);
                    level.addFreshEntity(e);
                }
            }
        }
    }

    private void title(ServerPlayer p, String title, String subtitle) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        p.connection.send(new ClientboundSetTitleTextPacket(Text.of(title)));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Text.of(subtitle)));
    }

    // ================================================================== admin

    public String addPoints(QuestDef q, UUID uuid, String name, long points, String reason, String by) {
        if (frozen()) return "state is frozen: " + store.brokenReason();
        QuestState s = state(q);
        ledger.record("ADMIN_POINTS", ordered("quest", q.id, "uuid", uuid.toString(), "name", name,
                "points", points, "reason", reason, "by", by));
        addFund(q, s, uuid.toString(), name, points, 0L, 0L);
        store.save();
        return "added " + points + " points to " + name + " (fund " + s.fundPoints + ")";
    }

    public String setPhase(QuestDef q, String phase, String by) {
        QuestState s = state(q);
        String p = phase.toUpperCase();
        if (!p.equals(WqState.Phase.ACTIVE) && !p.equals(WqState.Phase.REACHED) && !p.equals(WqState.Phase.OPENED)) {
            return "phase must be ACTIVE, REACHED or OPENED";
        }
        ledger.record("ADMIN_PHASE", ordered("quest", q.id, "from", s.phase, "to", p, "by", by));
        s.phase = p;
        store.save();
        return "phase " + p;
    }

    public String place(QuestDef q, ServerLevel level, Vec3 pos, String by) {
        if (frozen()) return "state is frozen: " + store.brokenReason();
        QuestState s = state(q);
        s.anchor = new WqState.Anchor(level.dimension().location().toString(), pos.x, pos.y, pos.z);
        ledger.record("PLACE", ordered("quest", q.id, "dimension", s.anchor.dimension, "x", pos.x, "y", pos.y,
                "z", pos.z, "by", by));
        store.save();
        rebuildHolos();
        return "hologram placed at " + s.anchor.dimension + " " + String.format("%.1f %.1f %.1f", pos.x, pos.y, pos.z);
    }

    public String unplace(QuestDef q, String by) {
        QuestState s = state(q);
        if (s.anchor == null) return "no hologram placed";
        ledger.record("UNPLACE", ordered("quest", q.id, "by", by));
        s.anchor = null;
        store.save();
        rebuildHolos();
        return "hologram removed";
    }

    public List<String> status() {
        List<String> out = new ArrayList<>();
        out.add("config: " + (configError == null ? "ok" : "ERROR " + configError) + " · state: "
                + (store.broken() ? "FROZEN " + store.brokenReason() : "ok")
                + (store.lastWriteError() == null ? "" : " · last write FAILED " + store.lastWriteError()));
        out.add("economy: " + econ.describe() + " · tower: " + tower.describe() + " · pending confirms: "
                + confirms.size() + " · in-flight donations: " + inFlight.size());
        out.add("invariants: " + lastInvariant);
        for (QuestDef q : config.quests) {
            QuestState s = state(q);
            HoloDisplay h = holos.get(q.id);
            int owed = s.pendingCommands.values().stream().mapToInt(List::size).sum();
            out.add(q.id + (q.enabled ? "" : " (disabled)") + " · phase " + s.phase + " · conquerors "
                    + s.conquerors.size() + "/" + q.conquerors.target + " · fund " + s.fundPoints + "/"
                    + q.fund.targetPoints + " pts (฿" + s.fundMoney + " + " + s.fundItemPoints + " item pts) · players "
                    + s.players.size() + " · owed commands " + owed);
            out.add("  hologram: " + (s.anchor == null ? "not placed"
                    : s.anchor.dimension + " " + String.format("%.1f %.1f %.1f", s.anchor.x, s.anchor.y, s.anchor.z)
                    + (h == null ? " (world not loaded!)" : " · viewers " + h.viewerCount())));
        }
        return out;
    }

    /** Recomputes the fund totals from the per-player rows and logs any difference (never corrects it). */
    public String checkInvariants() {
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, QuestState> e : store.state().quests.entrySet()) {
            QuestState s = e.getValue();
            long pts = 0, money = 0, items = 0;
            for (WqState.PlayerRec r : s.players.values()) {
                pts += r.fundPoints;
                money += r.money;
                items += r.itemPoints;
            }
            if (pts != s.fundPoints) bad.add(e.getKey() + " fundPoints " + s.fundPoints + " != Σplayers " + pts);
            if (money != s.fundMoney) bad.add(e.getKey() + " fundMoney " + s.fundMoney + " != Σplayers " + money);
            if (items != s.fundItemPoints) {
                bad.add(e.getKey() + " fundItemPoints " + s.fundItemPoints + " != Σplayers " + items);
            }
        }
        if (store.lastWriteError() != null) bad.add("state write failing: " + store.lastWriteError());
        if (bad.isEmpty()) {
            lastInvariant = "OK";
        } else {
            lastInvariant = "BROKEN " + String.join("; ", bad);
            log.error("[WorldQuest] INVARIANT {}", lastInvariant);
        }
        return lastInvariant;
    }

    // ================================================================== hologram + hints

    public void rebuildHolos() {
        for (HoloDisplay h : holos.values()) {
            h.hideAll(server);
        }
        holos.clear();
        questParticles.clear();
        sealPrompts.clear();
        for (QuestDef q : config.quests) {
            if (!q.enabled) continue;
            QuestState s = store.state().quests.get(q.id);
            if (s == null || s.anchor == null) continue;
            ServerLevel level = levelOf(s.anchor.dimension);
            if (level == null) {
                log.error("[WorldQuest] hologram of {} is in {} which is not loaded — not shown", q.id,
                        s.anchor.dimension);
                continue;
            }
            WqConfig.Hologram hc = q.hologram;
            WqConfig.Monument m = hc.monument;
            var item = java.util.Optional.<net.minecraft.world.item.Item>empty();
            if (m != null && m.enabled) {
                ResourceLocation id = ResourceLocation.tryParse(m.item);
                item = id == null ? item : BuiltInRegistries.ITEM.getOptional(id);
                if (item.isEmpty()) {
                    log.error("[WorldQuest] monument item {} of {} is not a registered item — monument off", m.item, q.id);
                }
            }
            boolean stacked = !"manual".equalsIgnoreCase(hc.layout);
            double titleY = hc.titleOffsetY;
            double bodyY = hc.bodyOffsetY;
            if (stacked) {
                // seal centred on the anchor, base on it; body just above the seal; title placed on the body at refresh
                double top = item.isPresent() ? m.baseLift + Geometry.px(m.modelBaseBelowCenterPx + m.modelTopAboveCenterPx,
                        m.scale) : 0.0;
                bodyY = item.isPresent() ? top + hc.stackGap : hc.bodyOffsetY;
                titleY = bodyY + Geometry.textHeight(7, hc.lineHeight, hc.bodyScale) + hc.titleGap;
            }
            Vec3 anchor = new Vec3(s.anchor.x, s.anchor.y, s.anchor.z);
            HoloDisplay h = new HoloDisplay(level, anchor, hc.viewRange, new double[]{titleY, bodyY},
                    new float[]{hc.titleScale, hc.bodyScale}, hc.lineWidth, hc.background);
            if (item.isPresent()) {
                if (stacked) {
                    double centre = m.baseLift + Geometry.px(m.modelBaseBelowCenterPx, m.scale);
                    h.enableMonument(item.get(), 0.0, centre, 0.0, m.scale, m.yawDegrees, m.spinDegreesPerSecond);
                    h.setSealVolume(anchor.x, anchor.z, Geometry.px(m.modelRadiusPx, m.scale), anchor.y + m.baseLift,
                            anchor.y + m.baseLift + Geometry.px(m.modelBaseBelowCenterPx + m.modelTopAboveCenterPx, m.scale));
                } else {
                    h.enableMonument(item.get(), m.offsetX, m.offsetY, m.offsetZ, m.scale, m.yawDegrees,
                            m.spinDegreesPerSecond);
                    double c = m.offsetY; // manual: the offsets are the model centre
                    h.setSealVolume(anchor.x + m.offsetX, anchor.z + m.offsetZ, Geometry.px(m.modelRadiusPx, m.scale),
                            anchor.y + c - Geometry.px(m.modelBaseBelowCenterPx, m.scale),
                            anchor.y + c + Geometry.px(m.modelTopAboveCenterPx, m.scale));
                }
            }
            WqConfig.Particles pc = hc.particles;
            if (pc != null && pc.enabled) {
                questParticles.put(q.id, new ParticleOptions[]{particle(q.id, pc.ambient), particle(q.id, pc.accent),
                        particle(q.id, pc.burst)});
            }
            holos.put(q.id, h);
        }
        refreshHolos();
    }

    /** stacked layout: the title sits on the body, whose height follows its line count. */
    private void placeTitle(QuestDef q, HoloDisplay h, String body) {
        WqConfig.Hologram hc = q.hologram;
        if ("manual".equalsIgnoreCase(hc.layout)) return;
        double bodyBase = h.partY(1);
        double y = bodyBase + Geometry.textHeight(Geometry.lineCount(body), hc.lineHeight, hc.bodyScale) + hc.titleGap;
        h.setPartY(server, 0, y);
    }

    /**
     * The seal model for the quest's state: OPENED has its own model; otherwise the stage for the average progress
     * of the enabled goals (each capped at 100), forced to 100 once REACHED so the fully broken seal and the
     * celebration arrive together.
     */
    private int monumentCmd(QuestDef q, QuestState s) {
        WqConfig.Monument m = q.hologram.monument;
        if (m == null || !m.enabled) return Integer.MIN_VALUE;
        if (WqState.Phase.OPENED.equals(s.phase)) return m.openedCustomModelData;
        int pct = progressPercent(q, s);
        int cmd = m.stages.isEmpty() ? m.openedCustomModelData : m.stages.get(0).customModelData;
        for (WqConfig.Stage st : m.stages) {
            if (pct >= st.atPercent) cmd = st.customModelData;
        }
        return cmd;
    }

    /** Average progress of the enabled goals, each capped at 100; 100 once REACHED or OPENED. */
    private int progressPercent(QuestDef q, QuestState s) {
        if (WqState.Phase.REACHED.equals(s.phase) || WqState.Phase.OPENED.equals(s.phase)) return 100;
        int n = 0;
        long sum = 0;
        if (q.conquerors.enabled) {
            sum += Math.min(100, Text.percent(s.conquerors.size(), q.conquerors.target));
            n++;
        }
        if (q.fund.enabled) {
            sum += Math.min(100, Text.percent(s.fundPoints, q.fund.targetPoints));
            n++;
        }
        return n == 0 ? 100 : (int) (sum / n);
    }

    private ServerLevel levelOf(String dimension) {
        ResourceLocation rl = ResourceLocation.tryParse(dimension);
        if (rl == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
    }

    private void refreshHolos() {
        for (Map.Entry<String, HoloDisplay> e : holos.entrySet()) {
            QuestDef q = config.quest(e.getKey());
            if (q == null) continue;
            QuestState s = state(q);
            String title = HoloText.title(config, q);
            String body = HoloText.body(config, q, s);
            placeTitle(q, e.getValue(), body);
            e.getValue().refresh(server, new Component[]{Text.of(title), Text.of(body)}, new String[]{title, body},
                    monumentCmd(q, s));
        }
    }

    private void personalHints() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, HoloDisplay> e : holos.entrySet()) {
            QuestDef q = config.quest(e.getKey());
            if (q == null || q.hologram.personalHintRange <= 0) continue;
            QuestState s = state(q);
            double r2 = (double) q.hologram.personalHintRange * q.hologram.personalHintRange;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.serverLevel() != e.getValue().level() || e.getValue().distanceSq(p) > r2) continue;
                Long last = lastHintAt.get(p.getUUID());
                if (last != null && now - last < q.hologram.personalHintSeconds * 1000L) continue;
                lastHintAt.put(p.getUUID(), now);
                p.displayClientMessage(Text.of(hintFor(q, s, p)), true);
            }
        }
    }

    private String hintFor(QuestDef q, QuestState s, ServerPlayer p) {
        WqConfig.Messages m = config.messages;
        String key = p.getUUID().toString();
        if (s.conquerors.containsKey(key)) {
            return msg(m.hintConquered, Map.of("mine", Text.num(personalPoints(q, s, key))));
        }
        int floor = knownFloor(p.getUUID());
        return floor >= 0 ? msg(m.hintNotConquered, Map.of("floor", floor)) : m.hintUnknown;
    }

    /** Best floor from the tower cache or our own 60 s cache; kicks off a read-only lookup when unknown. */
    public int knownFloor(UUID uuid) {
        int cached = tower.cachedBestFloor(uuid);
        if (cached >= 0) return cached;
        Long at = floorCacheAt.get(uuid);
        long now = System.currentTimeMillis();
        if (at != null && now - at < FLOOR_CACHE_MS) {
            return floorCache.get(uuid)[0];
        }
        var f = tower.bestFloor(uuid);
        if (f != null) {
            floorCacheAt.put(uuid, now); // one lookup per minute even if it fails
            floorCache.putIfAbsent(uuid, new int[]{-1});
            f.whenComplete((v, err) -> server.execute(() -> floorCache.put(uuid, new int[]{err == null && v != null
                    ? v : -1})));
        }
        int[] known = floorCache.get(uuid);
        return known == null ? -1 : known[0];
    }

    // ================================================================== helpers

    public String nameOf(UUID uuid) {
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        if (p != null) return p.getGameProfile().getName();
        var cache = server.getProfileCache();
        if (cache != null) {
            Optional<GameProfile> gp = cache.get(uuid);
            if (gp.isPresent()) return gp.get().getName();
        }
        return uuid.toString().substring(0, 8);
    }

    /** Resolves a typed player: online name, profile-cache name, or a literal uuid. */
    public Optional<GameProfile> resolve(String nameOrUuid) {
        ServerPlayer p = server.getPlayerList().getPlayerByName(nameOrUuid);
        if (p != null) return Optional.of(p.getGameProfile());
        try {
            UUID id = UUID.fromString(nameOrUuid);
            return Optional.of(new GameProfile(id, nameOf(id)));
        } catch (IllegalArgumentException ignored) {
            // not a uuid
        }
        var cache = server.getProfileCache();
        return cache == null ? Optional.empty() : cache.get(nameOrUuid);
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public Level overworld() {
        return server.overworld();
    }
}
