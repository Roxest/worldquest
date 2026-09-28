package com.pixelclub.worldquest.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pixelclub.worldquest.config.WqConfig;
import com.pixelclub.worldquest.config.WqConfig.QuestDef;
import com.pixelclub.worldquest.quest.QuestService;
import com.pixelclub.worldquest.state.WqState;
import com.pixelclub.worldquest.util.Text;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /worldquest} — players: info, donate, offer, items, top, confirm, cancel. {@code /worldquest admin …} is
 * gated by {@code .requires(hasPermission(2))}, which Arclight evaluates during the parse (safe; the off-parse
 * {@code hasPermission} leak does not apply to requires/executes).
 */
public final class WqCommands {

    private WqCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d, Supplier<QuestService> raw) {
        // A service that failed to start (logged at boot) answers every command with this instead of an NPE.
        Supplier<QuestService> svc = () -> {
            QuestService s = raw.get();
            if (s == null) throw new IllegalStateException("WorldQuest is not running — see the server log");
            return s;
        };
        SuggestionProvider<CommandSourceStack> quests = (ctx, b) -> SharedSuggestionProvider.suggest(
                svc.get().config().quests.stream().map(q -> q.id).toList(), b);

        d.register(Commands.literal("worldquest")
                .executes(ctx -> info(ctx, svc.get(), null))
                .then(Commands.literal("info")
                        .executes(ctx -> info(ctx, svc.get(), null))
                        .then(Commands.argument("quest", StringArgumentType.word()).suggests(quests)
                                .executes(ctx -> info(ctx, svc.get(), StringArgumentType.getString(ctx, "quest")))))
                .then(Commands.literal("donate")
                        .executes(ctx -> donateUsage(ctx, svc.get()))
                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(ctx -> donate(ctx, svc.get()))))
                .then(Commands.literal("offer")
                        .executes(ctx -> offer(ctx, svc.get(), false))
                        .then(Commands.literal("all").executes(ctx -> offer(ctx, svc.get(), true))))
                .then(Commands.literal("items").executes(ctx -> items(ctx, svc.get())))
                .then(Commands.literal("top").executes(ctx -> top(ctx, svc.get())))
                .then(Commands.literal("confirm")
                        .then(Commands.argument("nonce", StringArgumentType.word())
                                .executes(ctx -> {
                                    svc.get().confirm(ctx.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(ctx, "nonce"));
                                    return 1;
                                })))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("nonce", StringArgumentType.word())
                                .executes(ctx -> {
                                    svc.get().cancel(ctx.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(ctx, "nonce"));
                                    return 1;
                                })))
                .then(admin(svc, quests)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> admin(
            Supplier<QuestService> svc, SuggestionProvider<CommandSourceStack> quests) {
        return Commands.literal("admin").requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> lines(ctx, svc.get().status())))
                .then(Commands.literal("reload").executes(ctx -> reply(ctx, "reload: " + svc.get().reload())))
                .then(Commands.literal("invariants").executes(ctx -> reply(ctx, svc.get().checkInvariants())))
                .then(Commands.literal("place")
                        .then(Commands.argument("quest", StringArgumentType.word()).suggests(quests)
                                .executes(ctx -> {
                                    QuestDef q = quest(ctx, svc.get());
                                    if (q == null) return 0;
                                    CommandSourceStack s = ctx.getSource();
                                    return reply(ctx, svc.get().place(q, s.getLevel(), s.getPosition(), who(s)));
                                })
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(ctx -> {
                                            QuestDef q = quest(ctx, svc.get());
                                            if (q == null) return 0;
                                            Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
                                            return reply(ctx, svc.get().place(q, ctx.getSource().getLevel(), pos,
                                                    who(ctx.getSource())));
                                        })
                                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                .executes(ctx -> {
                                                    QuestDef q = quest(ctx, svc.get());
                                                    if (q == null) return 0;
                                                    ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
                                                    Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
                                                    return reply(ctx, svc.get().place(q, level, pos,
                                                            who(ctx.getSource())));
                                                })))))
                .then(Commands.literal("unplace")
                        .then(Commands.argument("quest", StringArgumentType.word()).suggests(quests)
                                .executes(ctx -> {
                                    QuestDef q = quest(ctx, svc.get());
                                    return q == null ? 0 : reply(ctx, svc.get().unplace(q, who(ctx.getSource())));
                                })))
                .then(Commands.literal("credit")
                        .then(Commands.argument("quest", StringArgumentType.word()).suggests(quests)
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> credit(ctx, svc.get())))))
                .then(Commands.literal("uncredit")
                        .then(Commands.argument("quest", StringArgumentType.word()).suggests(quests)
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> uncredit(ctx, svc.get())))))
                .then(Commands.literal("sync")
                        .then(Commands.argument("quest", StringArgumentType.word()).suggests(quests)
                                .executes(ctx -> {
                                    QuestDef q = quest(ctx, svc.get());
                                    if (q == null) return 0;
                                    CommandSourceStack s = ctx.getSource();
                                    svc.get().sync(q, line -> s.sendSuccess(() -> Component.literal(line), true));
                                    return reply(ctx, "sync requested");
                                })))
                .then(Commands.literal("import")
                        .then(Commands.argument("quest", StringArgumentType.word()).suggests(quests)
                                .then(Commands.argument("file", StringArgumentType.word())
                                        .executes(ctx -> {
                                            QuestDef q = quest(ctx, svc.get());
                                            if (q == null) return 0;
                                            String file = StringArgumentType.getString(ctx, "file");
                                            if (file.contains("/") || file.contains("\\") || file.contains("..")) {
                                                return reply(ctx, "file name only — it is read from config/pxcworldquest/");
                                            }
                                            return reply(ctx, svc.get().importConquerors(q,
                                                    Path.of("config", "pxcworldquest", file)));
                                        }))))
                .then(Commands.literal("open")
                        .then(Commands.argument("quest", StringArgumentType.word()).suggests(quests)
                                .executes(ctx -> {
                                    QuestDef q = quest(ctx, svc.get());
                                    return q == null ? 0 : reply(ctx, svc.get().open(q, who(ctx.getSource())));
                                })
                                .then(Commands.literal("force").executes(ctx -> {
                                    QuestDef q = quest(ctx, svc.get());
                                    return q == null ? 0 : reply(ctx, svc.get().open(q, "force:" + who(ctx.getSource())));
                                }))))
                .then(Commands.literal("phase")
                        .then(Commands.argument("quest", StringArgumentType.word()).suggests(quests)
                                .then(Commands.argument("phase", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                List.of("ACTIVE", "REACHED", "OPENED"), b))
                                        .executes(ctx -> {
                                            QuestDef q = quest(ctx, svc.get());
                                            return q == null ? 0 : reply(ctx, svc.get().setPhase(q,
                                                    StringArgumentType.getString(ctx, "phase"), who(ctx.getSource())));
                                        }))))
                .then(Commands.literal("addpoints")
                        .then(Commands.argument("quest", StringArgumentType.word()).suggests(quests)
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("points", LongArgumentType.longArg())
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(ctx -> addPoints(ctx, svc.get())))))));
    }

    // ------------------------------------------------------------------ player

    private static int info(CommandContext<CommandSourceStack> ctx, QuestService svc, String id) {
        QuestDef q = svc.quest(id);
        WqConfig.Messages m = svc.config().messages;
        CommandSourceStack s = ctx.getSource();
        if (q == null) {
            s.sendSystemMessage(Text.of(m.prefix + m.noQuest));
            return 0;
        }
        WqState.QuestState st = svc.state(q);
        s.sendSystemMessage(Text.of(Text.fill(m.infoHeader, Map.of("quest", q.name))));
        if (q.conquerors.enabled) {
            s.sendSystemMessage(Text.of(Text.fill(m.infoConquerors, Map.of("label", q.conquerors.label,
                    "count", Text.num(st.conquerors.size()), "target", Text.num(q.conquerors.target)))));
        }
        if (q.fund.enabled) {
            s.sendSystemMessage(Text.of(Text.fill(m.infoFund, Map.of("label", q.fund.label,
                    "points", Text.num(st.fundPoints), "target", Text.num(q.fund.targetPoints),
                    "money", Text.baht(st.fundMoney), "itemPoints", Text.num(st.fundItemPoints)))));
        }
        ServerPlayer p = s.getPlayer();
        if (p != null) {
            String key = p.getUUID().toString();
            long mine = svc.personalPoints(q, st, key);
            WqConfig.Tier t = svc.tierOf(q, mine);
            s.sendSystemMessage(Text.of(Text.fill(m.infoMine, Map.of("mine", Text.num(mine),
                    "tier", t == null ? "" : " &7(" + t.name + "&7)"))));
            if (st.conquerors.containsKey(key)) {
                s.sendSystemMessage(Text.of(m.infoMineConquered));
            } else {
                int floor = svc.knownFloor(p.getUUID());
                if (floor >= 0) {
                    s.sendSystemMessage(Text.of(Text.fill(m.infoMineFloor, Map.of("floor", floor))));
                }
            }
        }
        switch (st.phase) {
            case WqState.Phase.REACHED -> s.sendSystemMessage(Text.of(m.infoPhaseReached));
            case WqState.Phase.OPENED -> s.sendSystemMessage(Text.of(m.infoPhaseOpened));
            default -> s.sendSystemMessage(Text.of(m.infoHow));
        }
        return 1;
    }

    private static int donateUsage(CommandContext<CommandSourceStack> ctx, QuestService svc) {
        QuestDef q = svc.primary();
        WqConfig.Messages m = svc.config().messages;
        long min = q == null ? svc.config().moneyPerPoint : Math.max(svc.config().moneyPerPoint, q.fund.minMoney);
        ctx.getSource().sendSystemMessage(Text.of(m.prefix + Text.fill(m.donateUsage, Map.of("min", Text.num(min)))));
        return 1;
    }

    private static int donate(CommandContext<CommandSourceStack> ctx, QuestService svc) throws
            com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        QuestDef q = svc.primary();
        if (q == null) {
            p.sendSystemMessage(Text.of(svc.config().messages.prefix + svc.config().messages.noQuest));
            return 0;
        }
        svc.requestDonation(p, q, LongArgumentType.getLong(ctx, "amount"));
        return 1;
    }

    private static int offer(CommandContext<CommandSourceStack> ctx, QuestService svc, boolean all) throws
            com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        QuestDef q = svc.primary();
        if (q == null) {
            p.sendSystemMessage(Text.of(svc.config().messages.prefix + svc.config().messages.noQuest));
            return 0;
        }
        svc.requestOffer(p, q, all);
        return 1;
    }

    private static int items(CommandContext<CommandSourceStack> ctx, QuestService svc) {
        for (String line : svc.itemLines()) {
            ctx.getSource().sendSystemMessage(Text.of(line));
        }
        return 1;
    }

    private static int top(CommandContext<CommandSourceStack> ctx, QuestService svc) {
        QuestDef q = svc.primary();
        if (q == null) return 0;
        WqState.QuestState st = svc.state(q);
        ctx.getSource().sendSystemMessage(Text.of("&6" + q.fund.label + " &7— ผู้อุปถัมภ์สูงสุด"));
        List<WqState.PlayerRec> top = st.players.values().stream().filter(r -> r.fundPoints > 0)
                .sorted(Comparator.comparingLong((WqState.PlayerRec r) -> r.fundPoints).reversed()
                        .thenComparingLong(r -> r.firstAt))
                .limit(10).toList();
        int i = 1;
        for (WqState.PlayerRec r : top) {
            ctx.getSource().sendSystemMessage(Text.of("&7" + i++ + ". &f" + r.name + " &e" + Text.num(r.fundPoints)
                    + " &7แต้ม (฿" + Text.baht(r.money) + " · ไอเทม " + Text.num(r.itemPoints) + ")"));
        }
        if (top.isEmpty()) ctx.getSource().sendSystemMessage(Text.of("&7ยังไม่มีผู้บริจาค"));
        return 1;
    }

    // ------------------------------------------------------------------ admin

    private static int credit(CommandContext<CommandSourceStack> ctx, QuestService svc) {
        QuestDef q = quest(ctx, svc);
        if (q == null) return 0;
        String arg = StringArgumentType.getString(ctx, "player");
        Optional<GameProfile> gp = svc.resolve(arg);
        if (gp.isEmpty()) {
            return reply(ctx, "unknown player " + arg);
        }
        boolean added = svc.creditConqueror(q, gp.get().getId(), gp.get().getName(), 0L, "command", true);
        return reply(ctx, (added ? "credited " : "already credited ") + gp.get().getName() + " ("
                + svc.state(q).conquerors.size() + "/" + q.conquerors.target + ")");
    }

    private static int uncredit(CommandContext<CommandSourceStack> ctx, QuestService svc) {
        QuestDef q = quest(ctx, svc);
        if (q == null) return 0;
        String arg = StringArgumentType.getString(ctx, "player");
        Optional<GameProfile> gp = svc.resolve(arg);
        if (gp.isEmpty()) return reply(ctx, "unknown player " + arg);
        boolean removed = svc.uncredit(q, gp.get().getId(), who(ctx.getSource()));
        return reply(ctx, (removed ? "uncredited " : "was not credited: ") + gp.get().getName());
    }

    private static int addPoints(CommandContext<CommandSourceStack> ctx, QuestService svc) {
        QuestDef q = quest(ctx, svc);
        if (q == null) return 0;
        String arg = StringArgumentType.getString(ctx, "player");
        Optional<GameProfile> gp = svc.resolve(arg);
        if (gp.isEmpty()) return reply(ctx, "unknown player " + arg);
        return reply(ctx, svc.addPoints(q, gp.get().getId(), gp.get().getName(),
                LongArgumentType.getLong(ctx, "points"), StringArgumentType.getString(ctx, "reason"),
                who(ctx.getSource())));
    }

    private static QuestDef quest(CommandContext<CommandSourceStack> ctx, QuestService svc) {
        String id = StringArgumentType.getString(ctx, "quest");
        QuestDef q = svc.config().quest(id);
        if (q == null) {
            ctx.getSource().sendFailure(Component.literal("unknown quest " + id));
        }
        return q;
    }

    private static String who(CommandSourceStack s) {
        return s.getTextName();
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String line) {
        ctx.getSource().sendSuccess(() -> Component.literal("[WorldQuest] " + line), true);
        return 1;
    }

    private static int lines(CommandContext<CommandSourceStack> ctx, List<String> lines) {
        for (String l : lines) {
            ctx.getSource().sendSuccess(() -> Component.literal(l), false);
        }
        return 1;
    }
}
