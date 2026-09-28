package com.pixelclub.worldquest.tower;

import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reflection seam to {@code com.pixelclub.tower.api.PixelTowerApi} (pixel-tower ≥ 1.6.20). Both mods live in the
 * same Knot classloader, so {@code Class.forName} is legitimate here (the cross-bridge rule is about Bukkit
 * plugins). Without the API the quest still works: conquerors arrive only through the milestone command.
 *
 * <p>The API is read-only by contract: it never creates a {@code tower_progress} row (the tower's own
 * {@code getBestFloor} inserts one for any uncached uuid — calling that for every spawn visitor would pollute the
 * tower's player counts).
 */
public final class TowerBridge {

    private static final String API = "com.pixelclub.tower.api.PixelTowerApi";

    private final Logger log;
    private Method conquerors;
    private Method cachedBestFloor;
    private Method bestFloor;
    private int apiVersion = -1;
    private String why = "not probed";

    public TowerBridge(Logger log) {
        this.log = log;
    }

    public void probe() {
        try {
            Class<?> c = Class.forName(API);
            conquerors = c.getMethod("conquerors");
            cachedBestFloor = c.getMethod("cachedBestFloor", UUID.class);
            bestFloor = c.getMethod("bestFloor", UUID.class);
            apiVersion = c.getField("VERSION").getInt(null);
            why = null;
            log.info("[WorldQuest] pixel-tower API v{} found — conqueror sync on", apiVersion);
        } catch (ClassNotFoundException e) {
            why = "pixel-tower API not present (needs pixel-tower >= 1.6.20)";
            log.warn("[WorldQuest] {} — conquerors arrive only by the milestone command", why);
        } catch (Throwable t) {
            why = "pixel-tower API unusable: " + t;
            log.error("[WorldQuest] {}", why);
            conquerors = cachedBestFloor = bestFloor = null;
        }
    }

    public boolean available() {
        return conquerors != null;
    }

    public String describe() {
        return available() ? "PixelTowerApi v" + apiVersion : why;
    }

    /** uuid → conquest time (epoch ms). Null when the API is absent. */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<UUID, Long>> conquerors() {
        if (conquerors == null) return null;
        try {
            return (CompletableFuture<Map<UUID, Long>>) conquerors.invoke(null);
        } catch (Throwable t) {
            log.error("[WorldQuest] PixelTowerApi.conquerors() threw: {}", String.valueOf(t));
            return CompletableFuture.failedFuture(t);
        }
    }

    /** Best floor from the tower's cache, -1 when unknown or the API is absent. Never touches the database. */
    public int cachedBestFloor(UUID uuid) {
        if (cachedBestFloor == null) return -1;
        try {
            return (Integer) cachedBestFloor.invoke(null, uuid);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Read-only lookup (no row is created); completes with 0 for a player who never entered. Null if absent. */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Integer> bestFloor(UUID uuid) {
        if (bestFloor == null) return null;
        try {
            return (CompletableFuture<Integer>) bestFloor.invoke(null, uuid);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }
}
