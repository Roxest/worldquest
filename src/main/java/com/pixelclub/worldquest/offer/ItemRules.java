package com.pixelclub.worldquest.offer;

import com.pixelclub.worldquest.config.WqConfig.ItemDef;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Map;

/**
 * Which held stacks may be offered, and for how many points. Identity is the registry id plus, for custom items,
 * a required CUSTOM_DATA key — never the display name (team rule: match items by NBT, never material/name). The one
 * exception is an item whose own mod has no NBT mark and identifies it by name (Held Item Shard): then the exact
 * name string, § codes included, which an anvil cannot produce.
 *
 * <p>A plain entry takes only unmodified items: no custom name, no lore, no enchantments, no damage, and no custom
 * data other than a <i>fresh</i> held-item-durability stamp ({@code pixelclub_hid.break_chance == 0}). A worn held
 * item is refused outright rather than paid in full (recycle rule: a price that ignores the durability bar pays a
 * nearly broken item like a new one).
 */
public final class ItemRules {

    public static final String HID_ROOT = "pixelclub_hid";
    public static final String HID_BREAK = "break_chance";

    public enum Kind { OK, EMPTY, NOT_ACCEPTED, WORN, SPECIAL }

    public record Verdict(Kind kind, ItemDef def, String itemId) {
        public boolean ok() {
            return kind == Kind.OK;
        }
    }

    private ItemRules() {
    }

    public static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static Component nameOf(ItemStack stack, ItemDef def) {
        if (def != null && def.displayName != null && !def.displayName.isBlank()) {
            return Component.literal(def.displayName);
        }
        return stack.getHoverName().copy();
    }

    public static Verdict check(ItemStack stack, Map<String, ItemDef> index) {
        if (stack == null || stack.isEmpty()) {
            return new Verdict(Kind.EMPTY, null, null);
        }
        String id = idOf(stack);
        ItemDef def = index.get(id);
        if (def == null) {
            return new Verdict(Kind.NOT_ACCEPTED, null, id);
        }
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd == null ? null : cd.copyTag();

        if (def.requireCustomName != null && !def.requireCustomName.isEmpty()) {
            // Identity by exact name for the one item its own mod names and nothing else (see ItemDef).
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            if (name == null || !def.requireCustomName.equals(name.getString())) {
                return new Verdict(Kind.NOT_ACCEPTED, def, id);
            }
            if (tag != null && !tag.isEmpty()) {
                return new Verdict(Kind.SPECIAL, def, id);
            }
            ItemLore lore = stack.get(DataComponents.LORE);
            if (!def.allowLore && lore != null && !lore.lines().isEmpty()) {
                return new Verdict(Kind.SPECIAL, def, id);
            }
            Integer damage = stack.get(DataComponents.DAMAGE);
            if (damage != null && damage > 0) {
                return new Verdict(Kind.WORN, def, id);
            }
            ItemEnchantments ench = stack.get(DataComponents.ENCHANTMENTS);
            if (ench != null && !ench.isEmpty()) {
                return new Verdict(Kind.SPECIAL, def, id);
            }
            return new Verdict(Kind.OK, def, id);
        }

        if (def.requireCustomDataKey != null && !def.requireCustomDataKey.isBlank()) {
            if (tag == null || !tag.contains(def.requireCustomDataKey)) {
                return new Verdict(Kind.NOT_ACCEPTED, def, id);
            }
            if (def.requireCustomDataValue != null
                    && !def.requireCustomDataValue.equals(tag.getString(def.requireCustomDataKey))) {
                return new Verdict(Kind.NOT_ACCEPTED, def, id);
            }
            return new Verdict(Kind.OK, def, id);
        }

        Integer damage = stack.get(DataComponents.DAMAGE);
        if (damage != null && damage > 0) {
            return new Verdict(Kind.WORN, def, id);
        }
        boolean hid = false;
        if (tag != null && !tag.isEmpty()) {
            if (tag.size() == 1 && tag.contains(HID_ROOT)) {
                CompoundTag root = tag.getCompound(HID_ROOT);
                if (root.getFloat(HID_BREAK) > 0.0f) {
                    return new Verdict(Kind.WORN, def, id);
                }
                hid = true;
            } else {
                return new Verdict(Kind.SPECIAL, def, id);
            }
        }
        if (stack.get(DataComponents.CUSTOM_NAME) != null) {
            return new Verdict(Kind.SPECIAL, def, id);
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty() && !hid) {
            // The durability mod writes its own lore next to its stamp; any other lore marks a special item.
            return new Verdict(Kind.SPECIAL, def, id);
        }
        ItemEnchantments ench = stack.get(DataComponents.ENCHANTMENTS);
        if (ench != null && !ench.isEmpty()) {
            return new Verdict(Kind.SPECIAL, def, id);
        }
        return new Verdict(Kind.OK, def, id);
    }
}
