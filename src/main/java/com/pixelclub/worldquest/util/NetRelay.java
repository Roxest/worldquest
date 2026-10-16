package com.pixelclub.worldquest.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * X4 relay: a server-wide line shown here also reaches the players on the other backends, through LPC's network relay
 * ({@code com.wikmor.lpcpro.net.Network}, channel {@code pxc:social}), by reflection so this mod keeps no compile
 * dependency on LPC (same pattern as pxc-discordsrv {@code LpcNet}).
 * <ul>
 *   <li>The local broadcast is not touched: callers broadcast exactly as before, then call {@link #toOtherBackends}.</li>
 *   <li>No LPC, an LPC without the relay API, or {@code network.yml enabled: false}: nothing is sent (one server).</li>
 *   <li>Receivers hand the line straight to their players (LPC {@code Network.deliver}); no mod there sees it as a
 *       broadcast, so no backend relays a received copy again.</li>
 *   <li>LPC sends through one online player of this backend: nobody online here = nothing sent (and nothing seen).</li>
 * </ul>
 */
public final class NetRelay {

    private static final Logger LOG = LoggerFactory.getLogger("NetRelay");

    private static volatile boolean resolved;
    private static Method enabled;
    private static Method json;
    private static Method relaySystemJson;
    private static Method relayPerm;
    private static volatile boolean warned;

    private NetRelay() {
    }

    /** The relay decision: only with LPC's relay API present and its network relay on. */
    static boolean shouldRelay(boolean apiPresent, boolean networkOn) {
        return apiPresent && networkOn;
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            if (!FabricLoader.getInstance().isModLoaded("lpcpro")) return;
            Class<?> n = Class.forName("com.wikmor.lpcpro.net.Network");
            Method e = n.getMethod("enabled");
            Method j = n.getMethod("json", Component.class);
            Method s = n.getMethod("relaySystemJson", String.class);
            Method p = n.getMethod("relayPerm", String.class, Component.class);
            json = j;
            relaySystemJson = s;
            relayPerm = p;
            enabled = e; // last: present only with every method resolved
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // LPC without the relay API: one server
        } catch (Throwable t) {
            LOG.warn("[NetRelay] LPC relay API unusable ({}): lines stay on this backend", t.toString());
        }
    }

    private static boolean on() {
        resolve();
        boolean networkOn = false;
        if (enabled != null) {
            try {
                networkOn = (boolean) enabled.invoke(null);
            } catch (Throwable t) {
                networkOn = false;
            }
        }
        return shouldRelay(enabled != null, networkOn);
    }

    /** Server thread, right after the local broadcast: the same line to every player on the other backends. */
    public static boolean toOtherBackends(Component line) {
        if (line == null || !on()) return false;
        try {
            return (boolean) relaySystemJson.invoke(null, (String) json.invoke(null, line));
        } catch (Throwable t) {
            warnOnce(t);
            return false;
        }
    }

    /** As {@link #toOtherBackends}, only to players there who hold {@code perm} (LPC {@code Perms.op}). */
    public static boolean toOtherBackends(String perm, Component line) {
        if (line == null || perm == null || !on()) return false;
        try {
            return (boolean) relayPerm.invoke(null, perm, line);
        } catch (Throwable t) {
            warnOnce(t);
            return false;
        }
    }

    private static void warnOnce(Throwable t) {
        if (warned) return;
        warned = true;
        LOG.warn("[NetRelay] relay to the other backends failed: {}", t.toString());
    }
}
