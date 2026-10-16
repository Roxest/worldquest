package com.pixelclub.worldquest.event;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * What the event quest borrows from pixelclubpack, by reflection (pxc-gacha's {@code bridge/Pack}: the pack owns
 * {@code pixelclub:ui} and the custom items, and its only artifact is the remapped production jar). Every call
 * degrades to "not available".
 */
final class PackBridge {
    private final Logger log;
    private boolean resolved;
    private Object ui, items;
    private Method hasFeature, sendRaw, canReceive, blueprint, resolveStrict;

    PackBridge(Logger log) {
        this.log = log;
    }

    private synchronized void resolve() {
        if (resolved) return;
        try {
            if (!FabricLoader.getInstance().isModLoaded("pixelclubpack")) {
                resolved = true;
                log.warn("[WorldQuest] pixelclubpack not loaded: no monument on clients, no candy item");
                return;
            }
            Class<?> pc = Class.forName("com.pixelclub.pack.PixelclubPack");
            Object p = pc.getMethod("getInstance").invoke(null);
            if (p == null) return; // not started yet; ask again later
            Object u = pc.getMethod("getUiManager").invoke(p);
            Object i = pc.getMethod("getCustomItemRegistry").invoke(p);
            if (u == null || i == null) return;
            Class<?> net = Class.forName("com.pixelclub.pack.ui.UINetworkManager");
            hasFeature = u.getClass().getMethod("hasFeature", ServerPlayer.class, String.class);
            sendRaw = net.getMethod("sendRaw", ServerPlayer.class, String.class);
            canReceive = net.getMethod("canReceive", ServerPlayer.class);
            blueprint = i.getClass().getMethod("getItemBlueprint", String.class);
            resolveStrict = i.getClass().getMethod("resolveStrictItemId", ItemStack.class);
            ui = u;
            items = i;
            resolved = true;
            log.info("[WorldQuest] event quest riding pixelclubpack (ui, items)");
        } catch (Throwable t) {
            resolved = true;
            log.warn("[WorldQuest] pixelclubpack bridge unavailable: {}", t.toString());
        }
    }

    boolean available() {
        resolve();
        return ui != null;
    }

    /** True when this player's client announced {@code feature} in its ui_hello. */
    boolean has(ServerPlayer p, String feature) {
        if (p == null || !available()) return false;
        try {
            return (Boolean) canReceive.invoke(null, p) && (Boolean) hasFeature.invoke(ui, p, feature);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Why {@link #has} answers what it does, for the admin sync report. */
    String why(ServerPlayer p, String feature) {
        if (!available()) return "pixelclubpack bridge unavailable";
        try {
            boolean ch = (Boolean) canReceive.invoke(null, p);
            boolean f = (Boolean) hasFeature.invoke(ui, p, feature);
            return "channel=" + ch + " feature=" + f;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    void send(ServerPlayer p, JsonObject payload) {
        if (p == null || !available()) return;
        try {
            sendRaw.invoke(null, p, payload.toString());
        } catch (Throwable t) {
            log.warn("[WorldQuest] send to {} failed: {}", p.getGameProfile().getName(), t.toString());
        }
    }

    /** A fresh pixelclub item, or null when the id is unknown / the pack is missing. */
    ItemStack item(String id) {
        if (!available()) return null;
        try {
            Object st = blueprint.invoke(items, id);
            return st instanceof ItemStack s ? s.copy() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The pixelclub item id of a stack (strict: a renamed/forged copy does not resolve), or null. */
    String idOf(ItemStack stack) {
        if (!available() || stack.isEmpty()) return null;
        try {
            return (String) resolveStrict.invoke(items, stack);
        } catch (Throwable t) {
            return null;
        }
    }
}
