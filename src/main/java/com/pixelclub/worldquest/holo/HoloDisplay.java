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
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
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
    /** The seal model (PixelclubPack item model on an ItemDisplay); null when the monument is off. */
    private Display.ItemDisplay monument;
    private Item monumentItem;
    private int monumentCmd = Integer.MIN_VALUE;
    private float monumentScale = 1f;
    private float monumentYaw;
    private float spinPerSecond;
    private boolean delayToggle;

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

    /**
     * Adds the seal model. Item models are drawn centred on the entity (model-space 8,8,8 = entity position), so
     * the offset is to the model's centre. Brightness is pinned so the seal does not go dark at night.
     */
    public void enableMonument(Item item, double dx, double dy, double dz, float scale, float yawDegrees,
                               float spinDegreesPerSecond) {
        Display.ItemDisplay d = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
        d.setPos(anchor.x + dx, anchor.y + dy, anchor.z + dz);
        DisplayInvoker di = (DisplayInvoker) d;
        di.pxcwq$setBrightnessOverride(new Brightness(15, 15));
        di.pxcwq$setViewRange(2.0f);
        this.monument = d;
        this.monumentItem = item;
        this.monumentScale = scale;
        this.monumentYaw = yawDegrees;
        this.spinPerSecond = spinDegreesPerSecond;
        applyMonumentTransform(0);
        d.getEntityData().packDirty();
    }

    private void applyMonumentTransform(int interpolationTicks) {
        DisplayInvoker di = (DisplayInvoker) monument;
        float s = monumentScale;
        di.pxcwq$setTransformation(new Transformation(new Vector3f(),
                new Quaternionf().rotateY((float) Math.toRadians(monumentYaw)), new Vector3f(s, s, s), new Quaternionf()));
        if (interpolationTicks > 0) {
            di.pxcwq$setTransformationInterpolationDuration(interpolationTicks);
            // The client restarts an interpolation only when the start-delay value arrives changed, so it is
            // toggled 0/1 on every step — otherwise the second and later turns would snap instead of glide.
            delayToggle = !delayToggle;
            di.pxcwq$setTransformationInterpolationDelay(delayToggle ? 1 : 0);
        }
    }

    /** Shows the model for this custom_model_data (only sent when it changed). */
    private void setMonumentModel(MinecraftServer server, int cmd) {
        if (monument == null || cmd == monumentCmd) return;
        monumentCmd = cmd;
        ItemStack stack = new ItemStack(monumentItem);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(cmd));
        monument.getSlot(0).set(stack);
        sendDirty(server, monument);
    }

    private void spinStep(MinecraftServer server) {
        if (monument == null || spinPerSecond == 0f) return;
        monumentYaw = (monumentYaw + spinPerSecond) % 360f;
        applyMonumentTransform(20);
        sendDirty(server, monument);
    }

    private void sendDirty(MinecraftServer server, Display d) {
        List<SynchedEntityData.DataValue<?>> dirty = d.getEntityData().packDirty();
        if (dirty == null || viewers.isEmpty()) return;
        ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(d.getId(), dirty);
        for (UUID id : viewers) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) p.connection.send(packet);
        }
    }

    /** refresh() plus the seal: its model for the current stage, and one spin step (called once a second). */
    public void refresh(MinecraftServer server, Component[] texts, String[] keys, int monumentCmd) {
        setMonumentModel(server, monumentCmd);
        spinStep(server);
        refresh(server, texts, keys);
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
            addFor(p, d, EntityType.TEXT_DISPLAY);
        }
        if (monument != null) {
            addFor(p, monument, EntityType.ITEM_DISPLAY);
        }
        viewers.add(p.getUUID());
    }

    private static void addFor(ServerPlayer p, Display d, EntityType<?> type) {
        p.connection.send(new ClientboundAddEntityPacket(d.getId(), d.getUUID(), d.getX(), d.getY(), d.getZ(),
                0f, 0f, type, 0, Vec3.ZERO, 0.0));
        List<SynchedEntityData.DataValue<?>> values = d.getEntityData().getNonDefaultValues();
        if (values != null) {
            p.connection.send(new ClientboundSetEntityDataPacket(d.getId(), values));
        }
    }

    private void hide(ServerPlayer p) {
        p.connection.send(new ClientboundRemoveEntitiesPacket(ids()));
        viewers.remove(p.getUUID());
    }

    private int[] ids() {
        int n = parts.size() + (monument != null ? 1 : 0);
        int[] ids = new int[n];
        for (int i = 0; i < parts.size(); i++) ids[i] = parts.get(i).getId();
        if (monument != null) ids[n - 1] = monument.getId();
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
