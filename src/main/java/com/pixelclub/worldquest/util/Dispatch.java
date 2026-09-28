package com.pixelclub.worldquest.util;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * Console commands for rewards and unlock hooks. Bukkit's console first when Arclight is there (plugin commands such
 * as {@code lp} only exist on that side; Fabric commands fall through to the vanilla tree), vanilla dispatch
 * otherwise — the same order pixel-tower's RewardExecutor uses. A {@code true} here is the <i>executed</i> rung only:
 * Bukkit's dispatch reports true for almost anything.
 */
public final class Dispatch {

    private Dispatch() {
    }

    public static boolean console(MinecraftServer server, Logger log, String cmd) {
        String line = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
            Object sender = bukkit.getMethod("getConsoleSender").invoke(null);
            Object out = bukkit.getMethod("dispatchCommand", Class.forName("org.bukkit.command.CommandSender"),
                    String.class).invoke(null, sender, line);
            log.info("[WorldQuest] dispatched (bukkit) '{}' -> {}", line, out);
            return !(out instanceof Boolean b) || b;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            try {
                int r = server.getCommands().getDispatcher().execute(line, server.createCommandSourceStack());
                log.info("[WorldQuest] dispatched (vanilla) '{}' -> {}", line, r);
                return r != 0;
            } catch (Exception ex) {
                log.error("[WorldQuest] command '{}' rejected: {}", line, ex.getMessage());
                return false;
            }
        } catch (Throwable t) {
            log.error("[WorldQuest] command '{}' failed: {}", line, String.valueOf(t));
            return false;
        }
    }
}
