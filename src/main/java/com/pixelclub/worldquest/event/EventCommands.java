package com.pixelclub.worldquest.event;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.pixelclub.worldquest.event.EventState.Contributor;
import com.pixelclub.worldquest.event.EventState.Phase;
import com.pixelclub.worldquest.util.Text;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code /worldquest event} — players: progress, their own count, the top feeders. {@code /worldquest event admin …}
 * (permission level 2, evaluated in {@code requires}): place · start · target · add · countdown · unlock · fail ·
 * reset · scale · give · status · reload. Registered under the same {@code worldquest} literal as the endless quest
 * (Brigadier merges the two trees), so PRD's command whitelist entry for {@code worldquest} covers it.
 */
public final class EventCommands {

    private EventCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d, Supplier<EventQuest> raw) {
        Supplier<EventQuest> svc = () -> {
            EventQuest s = raw.get();
            if (s == null) throw new IllegalStateException("WorldQuest event is not running — see the server log");
            return s;
        };
        LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> status(ctx, svc.get())))
                .then(Commands.literal("place")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            // front toward the admin who places it
                            float yaw = Math.round((p.getYRot() + 180f) / 45f) * 45f;
                            Vec3 at = new Vec3(Math.floor(p.getX()) + 0.5, Math.floor(p.getY()), Math.floor(p.getZ()) + 0.5);
                            svc.get().place(p.level().dimension().location().toString(), at, yaw);
                            return ok(ctx, "วางที่ " + fmt(at) + " yaw " + yaw);
                        })
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .then(Commands.argument("yaw", FloatArgumentType.floatArg(-360f, 360f))
                                        .executes(ctx -> placeAt(ctx, svc.get(), ctx.getSource().getLevel()))
                                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                .executes(ctx -> placeAt(ctx, svc.get(), DimensionArgument.getDimension(ctx, "dimension")))))))
                .then(Commands.literal("start")
                        .executes(ctx -> start(ctx, svc.get(), svc.get().config().defaultDurationHours))
                        .then(Commands.argument("hours", DoubleArgumentType.doubleArg(0.01, 24 * 60))
                                .executes(ctx -> start(ctx, svc.get(), DoubleArgumentType.getDouble(ctx, "hours")))))
                .then(Commands.literal("target")
                        .then(Commands.argument("candies", LongArgumentType.longArg(1))
                                .executes(ctx -> {
                                    svc.get().setTarget(LongArgumentType.getLong(ctx, "candies"));
                                    return ok(ctx, "เป้า = " + Text.num(svc.get().config().target));
                                })))
                .then(Commands.literal("add")
                        .then(Commands.argument("candies", LongArgumentType.longArg(1))
                                .executes(ctx -> {
                                    EventQuest q = svc.get();
                                    if (!q.state().accepting()) return fail(ctx, "รับเพิ่มได้เฉพาะช่วง collecting/countdown (ตอนนี้ " + q.state().phase + ")");
                                    q.adminAdd(LongArgumentType.getLong(ctx, "candies"), ctx.getSource().getTextName());
                                    return ok(ctx, "รวม " + Text.num(q.state().donated));
                                })))
                .then(Commands.literal("countdown")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 24 * 3600))
                                .executes(ctx -> {
                                    EventQuest q = svc.get();
                                    if (q.state().phase != Phase.COLLECTING && q.state().phase != Phase.COUNTDOWN) {
                                        return fail(ctx, "countdown ได้จาก collecting/countdown เท่านั้น (ตอนนี้ " + q.state().phase + ")");
                                    }
                                    q.startCountdown(System.currentTimeMillis(), IntegerArgumentType.getInteger(ctx, "seconds"));
                                    return ok(ctx, "countdown เริ่ม");
                                })))
                .then(Commands.literal("unlock").executes(ctx -> {
                    EventQuest q = svc.get();
                    if (q.state().phase == Phase.ENDING || q.state().phase == Phase.UNLOCKED) return fail(ctx, "เปิดไปแล้ว");
                    q.beginEnding(System.currentTimeMillis());
                    return ok(ctx, "เล่น locked_ends แล้ว");
                }))
                .then(Commands.literal("fail").executes(ctx -> {
                    EventQuest q = svc.get();
                    if (!q.state().accepting()) return fail(ctx, "fail ได้จาก collecting/countdown เท่านั้น");
                    q.fail(System.currentTimeMillis());
                    return ok(ctx, "สาปสูญแล้ว");
                }))
                .then(Commands.literal("reset").executes(ctx -> {
                    svc.get().reset();
                    return ok(ctx, "reset เป็น IDLE (ledger เก็บประวัติไว้)");
                }))
                .then(Commands.literal("scale")
                        .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.1, 10))
                                .executes(ctx -> {
                                    svc.get().setScale(DoubleArgumentType.getDouble(ctx, "scale"));
                                    return ok(ctx, "scale = " + svc.get().config().scale);
                                })))
                .then(Commands.literal("give")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 6400))
                                        .executes(ctx -> {
                                            ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                                            int n = svc.get().give(p, IntegerArgumentType.getInteger(ctx, "count"));
                                            if (n == 0) return fail(ctx, "ไม่มีไอเทม '" + svc.get().config().candyItemId + "' ใน PixelclubPack");
                                            return ok(ctx, "ให้ลูกอม " + n + " ชิ้นแก่ " + p.getGameProfile().getName());
                                        }))))
                .then(Commands.literal("sync").executes(ctx -> {
                    String r = svc.get().forceSync();
                    ctx.getSource().sendSuccess(() -> Text.of("&7" + (r.isEmpty() ? "ไม่มีผู้เล่นออนไลน์" : r.trim())), false);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(ctx -> {
                    try {
                        svc.get().reloadConfig();
                        return ok(ctx, "โหลด event.json แล้ว");
                    } catch (Exception e) {
                        return fail(ctx, e.getMessage());
                    }
                }));

        d.register(Commands.literal("worldquest")
                .then(Commands.literal("event")
                        .executes(ctx -> info(ctx, svc.get()))
                        .then(Commands.literal("top").executes(ctx -> top(ctx, svc.get())))
                        .then(admin)));
    }

    private static int placeAt(CommandContext<CommandSourceStack> ctx, EventQuest q, ServerLevel level) {
        Vec3 at = Vec3Argument.getVec3(ctx, "pos");
        float yaw = FloatArgumentType.getFloat(ctx, "yaw");
        q.place(level.dimension().location().toString(), at, yaw);
        return ok(ctx, "วางที่ " + level.dimension().location() + " " + fmt(at) + " yaw " + yaw);
    }

    private static int start(CommandContext<CommandSourceStack> ctx, EventQuest q, double hours) {
        if (!q.config().anchor.placed) return fail(ctx, "ยังไม่ได้วาง: /worldquest event admin place");
        if (q.state().phase != Phase.IDLE) return fail(ctx, "เริ่มได้จาก IDLE เท่านั้น (ตอนนี้ " + q.state().phase + ", ใช้ reset ก่อน)");
        q.begin(hours);
        return ok(ctx, "เริ่มแล้ว " + hours + " ชม. เป้า " + Text.num(q.config().target));
    }

    private static int info(CommandContext<CommandSourceStack> ctx, EventQuest q) {
        long now = System.currentTimeMillis();
        long mine = 0;
        if (ctx.getSource().getPlayer() != null) {
            Contributor c = q.state().players.get(ctx.getSource().getPlayer().getUUID().toString());
            if (c != null) mine = c.count;
        }
        String line = q.fillFor(q.config().messages.info, now, Map.of("mine", Text.num(mine)));
        ctx.getSource().sendSuccess(() -> Text.of(line), false);
        return 1;
    }

    private static int top(CommandContext<CommandSourceStack> ctx, EventQuest q) {
        List<Map.Entry<String, Contributor>> t = q.top(10);
        StringBuilder sb = new StringBuilder("&6World Quest &7· ผู้ป้อนลูกอมสูงสุด");
        int i = 1;
        for (Map.Entry<String, Contributor> e : t) {
            sb.append("\n&e").append(i++).append(". &f").append(e.getValue().name).append(" &7").append(Text.num(e.getValue().count));
        }
        if (t.isEmpty()) sb.append("\n&7ยังไม่มีใครป้อน");
        String s = sb.toString();
        ctx.getSource().sendSuccess(() -> Text.of(s), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx, EventQuest q) {
        EventState s = q.state();
        EventConfig c = q.config();
        long now = System.currentTimeMillis();
        String msg = "&6[event " + c.id + "] &f" + s.phase + (c.enabled ? "" : " &c(disabled)")
                + "\n&7donated " + Text.num(s.donated) + "/" + Text.num(c.target) + " · players " + s.players.size()
                + " · admin " + Text.num(s.adminAdded) + " · invariant " + (s.invariantHolds() ? "&aok" : "&cBROKEN")
                + "\n&7anchor " + (c.anchor.placed ? c.anchor.dimension + " " + fmt(new Vec3(c.anchor.x, c.anchor.y, c.anchor.z)) + " yaw " + c.anchor.yaw : "&cnot placed")
                + " &7scale " + c.scale
                + "\n&7deadline " + (s.deadlineAt > 0 && s.phase == Phase.COLLECTING ? EventQuest.longClock(Math.max(0, s.deadlineAt - now)) : "-")
                + " · countdown " + (s.countdownEndsAt > 0 && s.phase == Phase.COUNTDOWN ? EventQuest.countdownClock((int) Math.max(0, (s.countdownEndsAt - now) / 1000)) : "-")
                + (q.brokenReason() != null ? "\n&cBROKEN: " + q.brokenReason() : "");
        ctx.getSource().sendSuccess(() -> Text.of(msg), false);
        return 1;
    }

    private static String fmt(Vec3 v) {
        return String.format("%.1f %.1f %.1f", v.x, v.y, v.z);
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Text.of("&a[WorldQuest] &f" + msg), true);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Text.of("&c[WorldQuest] " + msg));
        return 0;
    }
}
