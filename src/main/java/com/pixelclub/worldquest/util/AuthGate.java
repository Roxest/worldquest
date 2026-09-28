package com.pixelclub.worldquest.util;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether a connected player has passed AuthMe's {@code /login} — the same lookup and fail-closed rule as PxcAC's
 * {@code server/AuthGate} and pxc-essentials' {@code perm/AuthGate}.
 *
 * <p>The server is offline-mode: until AuthMe's login a session is only a claim on a name. AuthMe blocks a typed
 * command (so the chat [ยืนยัน] button is covered), but a right-click on the seal reaches us through a Fabric callback
 * it never sees — without this, anyone connecting under a player's name could burn that player's items before
 * logging in.
 *
 * <p>{@link #loggedIn}: TRUE logged in · FALSE not logged in, or AuthMe is installed but could not be asked (fail
 * closed) · null no Bukkit/AuthMe here at all (pure Fabric, test rig): allow. Reflection only — AuthMe is a Bukkit
 * plugin on another classloader; handles are looked up once and cached.
 */
public final class AuthGate {

    private static volatile Object api;
    private static volatile Method isAuthenticated;
    private static volatile boolean absent;
    private static volatile Method bukkitEntity;
    private static volatile Class<?> bukkitEntityOwner;
    private static final AtomicBoolean failureLogged = new AtomicBoolean();

    private AuthGate() {
    }

    /** True unless AuthMe says this session has not logged in (or cannot be asked). */
    public static boolean notImpostor(ServerPlayer player, Logger log) {
        return loggedIn(player, log) != Boolean.FALSE;
    }

    public static Boolean loggedIn(ServerPlayer player, Logger log) {
        if (absent) {
            return null;
        }
        try {
            Object authMe = api();
            if (authMe == null) {
                return absent ? null : Boolean.FALSE;
            }
            Class<?> type = player.getClass();
            Method m = bukkitEntity;
            if (m == null || bukkitEntityOwner != type) {
                m = type.getMethod("getBukkitEntity");
                bukkitEntityOwner = type;
                bukkitEntity = m;
            }
            Object answer = isAuthenticated.invoke(authMe, m.invoke(player));
            return answer instanceof Boolean b && b;
        } catch (Throwable t) {
            if (failureLogged.compareAndSet(false, true)) {
                log.error("[WorldQuest] AuthMe is installed but could not be asked who is logged in — right-clicks on "
                        + "the seal are refused until it can", t);
            }
            return Boolean.FALSE;
        }
    }

    private static Object api() throws ReflectiveOperationException {
        Object cached = api;
        if (cached != null) {
            return cached;
        }
        Class<?> bukkit;
        try {
            bukkit = Class.forName("org.bukkit.Bukkit", true, AuthGate.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            absent = true;
            return null;
        }
        Object pluginManager = bukkit.getMethod("getPluginManager").invoke(null);
        if (pluginManager == null) {
            return null; // too early in boot; ask again next time
        }
        Object plugin = pluginManager.getClass().getMethod("getPlugin", String.class).invoke(pluginManager, "AuthMe");
        if (plugin == null) {
            absent = true;
            return null;
        }
        Class<?> apiType = Class.forName("fr.xephi.authme.api.v3.AuthMeApi", true, plugin.getClass().getClassLoader());
        Class<?> playerType = Class.forName("org.bukkit.entity.Player", true, bukkit.getClassLoader());
        Object instance = apiType.getMethod("getInstance").invoke(null);
        isAuthenticated = apiType.getMethod("isAuthenticated", playerType);
        api = instance; // written last: a reader that sees it also sees isAuthenticated
        return instance;
    }
}
