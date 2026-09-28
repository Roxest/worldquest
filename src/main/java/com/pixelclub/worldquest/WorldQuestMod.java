package com.pixelclub.worldquest;

import com.pixelclub.worldquest.command.WqCommands;
import com.pixelclub.worldquest.quest.QuestService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Pixelclub World Quest — a server-wide community goal on a spawn hologram. Quest 1 ("endless_unlock"): 100 tower
 * conquerors plus a donation fund of money and items (both destroyed on the way in) unlock Endless Tower; opening is
 * an admin action.
 *
 * <p>Threading: every listener here runs on the server thread except DISCONNECT, which Fabric fires on the netty
 * thread on this server — so it is deliberately not used (viewer sets are pruned by the tick instead).
 */
public final class WorldQuestMod implements ModInitializer {

    public static final String MOD_ID = "pxcworldquest";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile QuestService service;

    public static QuestService service() {
        return service;
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) ->
                WqCommands.register(dispatcher, WorldQuestMod::service));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                QuestService s = new QuestService(server, LOGGER, Path.of("config", MOD_ID));
                s.start();
                service = s;
            } catch (Throwable t) {
                LOGGER.error("[WorldQuest] failed to start — the mod is inactive", t);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            QuestService s = service;
            service = null;
            if (s != null) {
                try {
                    s.stop();
                } catch (Throwable t) {
                    LOGGER.error("[WorldQuest] stop failed", t);
                }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            QuestService s = service;
            if (s == null) return;
            try {
                s.tick();
            } catch (Throwable t) {
                LOGGER.error("[WorldQuest] tick failed", t);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            QuestService s = service;
            if (s == null) return;
            try {
                s.onJoin(handler.getPlayer());
            } catch (Throwable t) {
                LOGGER.error("[WorldQuest] join handling failed", t);
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            QuestService s = service;
            if (s != null) s.onRespawnOrWorldChange(newPlayer);
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            QuestService s = service;
            if (s != null) s.onRespawnOrWorldChange(player);
        });
        // Right-click on the seal (a packet-only display has no hitbox, so the client sends a plain use-item on air, or
        // a use-on-block when a block is behind it): the service ray-tests the seal and takes the click when it hits.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer sp && sealClick(sp, hand)) {
                return InteractionResultHolder.success(player.getItemInHand(hand));
            }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer sp && sealClick(sp, hand)) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    private static boolean sealClick(ServerPlayer player, InteractionHand hand) {
        QuestService s = service;
        if (s == null) return false;
        try {
            return s.onUse(player, hand);
        } catch (Throwable t) {
            LOGGER.error("[WorldQuest] seal right-click failed", t);
            return false;
        }
    }
}
