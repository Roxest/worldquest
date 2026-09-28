package com.pixelclub.worldquest.holo;

import com.mojang.math.Transformation;
import com.pixelclub.worldquest.mixin.DisplayInvoker;
import com.pixelclub.worldquest.mixin.TextDisplayInvoker;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The quest hologram as <b>packet-only</b> TextDisplays (airdrop-fabric's FakeVisuals pattern): the entities are
 * never added to a level, so nothing is written to a chunk, a crash leaves nothing behind, and there is nothing to
 * duplicate on reload. Viewers are re-evaluated by distance on every refresh; a client that respawns or changes world
 * forgets them, so those players are dropped from the set and re-added on the next refresh.
 */
public final class HoloDisplay {

    private final ServerLevel level;
    private final Vec3 anchor;
    private final int range;
    private final List<Display.TextDisplay> parts = new ArrayList<>();
    private final List<String> lastKeys = new ArrayList<>();
    private final Set<UUID> viewers = new HashSet<>();

    /** One text display per part: offsets are heights above the anchor, scales are uniform. */
    public HoloDisplay(ServerLevel level, Vec3 anchor, int range, double[] offsetsY, float[] scales, int lineWidth,
                       int background) {
        this.level = level;
        this.anchor = anchor;
        this.range = Math.max(8, range);
        for (int i = 0; i < offsetsY.length; i++) {
            Display.TextDisplay d = new Display.TextDisplay(EntityType.TEXT_DISPLAY, level);
            d.setPos(anchor.x, anchor.y + offsetsY[i], anchor.z);
            DisplayInvoker di = (DisplayInvoker) d;
            di.pxcwq$setBillboardConstraints(Display.BillboardConstraints.CENTER);
            float s = scales[i];
            di.pxcwq$setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(s, s, s), new Quaternionf()));
            di.pxcwq$setBrightnessOverride(new Brightness(15, 15));
            di.pxcwq$setViewRange(2.0f);
            TextDisplayInvoker ti = (TextDisplayInvoker) d;
            ti.pxcwq$setLineWidth(lineWidth);
            ti.pxcwq$setBackgroundColor(background);
            ti.pxcwq$setFlags(Display.TextDisplay.FLAG_SHADOW);
            parts.add(d);
            lastKeys.add(null);
        }
        for (Display.TextDisplay d : parts) {
            d.getEntityData().packDirty(); // the initial values go out with the add packet, not as a later update
        }
    }

    public ServerLevel level() {
        return level;
    }

    public Vec3 anchor() {
        return anchor;
    }

    public int viewerCount() {
        return viewers.size();
    }

    /**
     * Updates the text of each part (sent to current viewers only when its key changed), then adds/removes viewers
     * by horizontal distance in the same level. {@code texts[i]}/{@code keys[i]} pair with the parts in order.
     */
    public void refresh(MinecraftServer server, Component[] texts, String[] keys) {
        for (int i = 0; i < parts.size() && i < texts.length; i++) {
            if (keys[i].equals(lastKeys.get(i))) {
                continue;
            }
            Display.TextDisplay d = parts.get(i);
            ((TextDisplayInvoker) d).pxcwq$setText(texts[i]);
            lastKeys.set(i, keys[i]);
            List<SynchedEntityData.DataValue<?>> dirty = d.getEntityData().packDirty();
            if (dirty != null && !viewers.isEmpty()) {
                ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(d.getId(), dirty);
                for (UUID id : viewers) {
                    ServerPlayer p = server.getPlayerList().getPlayer(id);
                    if (p != null) p.connection.send(packet);
                }
            }
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean should = p.serverLevel() == level && near(p);
            boolean sees = viewers.contains(p.getUUID());
            if (should && !sees) {
                show(p);
            } else if (!should && sees) {
                hide(p);
            }
        }
        viewers.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    public boolean near(ServerPlayer p) {
        double dx = p.getX() - anchor.x;
        double dz = p.getZ() - anchor.z;
        return dx * dx + dz * dz <= (double) range * range;
    }

    public double distanceSq(ServerPlayer p) {
        return p.position().distanceToSqr(anchor);
    }

    private void show(ServerPlayer p) {
        for (Display.TextDisplay d : parts) {
            p.connection.send(new ClientboundAddEntityPacket(d.getId(), d.getUUID(), d.getX(), d.getY(), d.getZ(),
                    0f, 0f, EntityType.TEXT_DISPLAY, 0, Vec3.ZERO, 0.0));
            List<SynchedEntityData.DataValue<?>> values = d.getEntityData().getNonDefaultValues();
            if (values != null) {
                p.connection.send(new ClientboundSetEntityDataPacket(d.getId(), values));
            }
        }
        viewers.add(p.getUUID());
    }

    private void hide(ServerPlayer p) {
        p.connection.send(new ClientboundRemoveEntitiesPacket(ids()));
        viewers.remove(p.getUUID());
    }

    private int[] ids() {
        int[] ids = new int[parts.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = parts.get(i).getId();
        return ids;
    }

    /** The client of this player dropped every entity (respawn, world change): re-add on the next refresh. */
    public void forget(UUID player) {
        viewers.remove(player);
    }

    public boolean seenBy(UUID player) {
        return viewers.contains(player);
    }

    /** Remove from every viewer (unplace, reload, stop). */
    public void hideAll(MinecraftServer server) {
        ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(ids());
        for (UUID id : viewers) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) p.connection.send(packet);
        }
        viewers.clear();
    }
}
