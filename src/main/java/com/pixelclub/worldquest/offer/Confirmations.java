package com.pixelclub.worldquest.offer;

import net.minecraft.world.item.ItemStack;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pending "are you sure" actions, one per player at a time. The clickable [ยืนยัน] runs
 * {@code /worldquest confirm <nonce>}; a nonce is random, bound to its player, and expires. Server thread only.
 */
public final class Confirmations {

    public enum Kind { DONATE, OFFER }

    /** For an offering: the inventory slots and exact copies of what was in them when the prompt was shown. */
    public record SlotSnap(int slot, ItemStack copy) {
    }

    public static final class Pending {
        public final String nonce;
        public final UUID player;
        public final Kind kind;
        public final String questId;
        public final long amount;
        public final long points;
        public final String itemId;
        public final List<SlotSnap> slots;
        public final long expiresAt;

        Pending(String nonce, UUID player, Kind kind, String questId, long amount, long points, String itemId,
                List<SlotSnap> slots, long expiresAt) {
            this.nonce = nonce;
            this.player = player;
            this.kind = kind;
            this.questId = questId;
            this.amount = amount;
            this.points = points;
            this.itemId = itemId;
            this.slots = slots;
            this.expiresAt = expiresAt;
        }
    }

    private static final SecureRandom RNG = new SecureRandom();
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final Map<String, Pending> byNonce = new HashMap<>();
    private final Map<UUID, String> byPlayer = new HashMap<>();

    public Pending put(UUID player, Kind kind, String questId, long amount, long points, String itemId,
                       List<SlotSnap> slots, long ttlMillis) {
        String old = byPlayer.remove(player);
        if (old != null) byNonce.remove(old);
        String nonce = newNonce();
        Pending p = new Pending(nonce, player, kind, questId, amount, points, itemId,
                slots == null ? List.of() : List.copyOf(slots), System.currentTimeMillis() + ttlMillis);
        byNonce.put(nonce, p);
        byPlayer.put(player, nonce);
        return p;
    }

    /** Removes and returns the pending action if it belongs to this player and has not expired; else null. */
    public Pending take(UUID player, String nonce) {
        Pending p = byNonce.get(nonce);
        if (p == null || !p.player.equals(player)) {
            return null;
        }
        byNonce.remove(nonce);
        byPlayer.remove(player);
        return System.currentTimeMillis() > p.expiresAt ? null : p;
    }

    public void sweep() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Pending>> it = byNonce.entrySet().iterator();
        while (it.hasNext()) {
            Pending p = it.next().getValue();
            if (now > p.expiresAt) {
                it.remove();
                byPlayer.remove(p.player, p.nonce);
            }
        }
    }

    public int size() {
        return byNonce.size();
    }

    private static String newNonce() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
