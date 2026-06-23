package dev.thefather007.worldguardneo.selection;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * The built-in selection wand handed out by {@code /rg wand}. Item type is configurable
 * ({@code selection.wand-item}); every wand carries a hidden {@code wgn_wand} marker so it's
 * recognised regardless of type/renaming and the config item can change without orphaning
 * existing wands. Obtainable once ({@link #hasWand}); its normal use is cancelled so it only
 * picks selection corners.
 */
public final class WandItem {

    private WandItem() {}

    /** Hidden marker in the wand's custom data. */
    public static final String WAND_MARKER = "wgn_wand";

    /**
     * Build a wand stack from the configured item id, or {@link ItemStack#EMPTY} if the configured
     * id is invalid (caller should warn the player via {@code msg.wand.bad-item}).
     */
    public static ItemStack create(WorldGuardNeo mod) {
        Item item = resolveConfiguredItem(mod);
        if (item == null) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(mod.i18n().format("item.wand.name")));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(WAND_MARKER, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** Resolve the configured wand item, or null if {@code selection.wand-item} names no item. */
    public static Item resolveConfiguredItem(WorldGuardNeo mod) {
        String id = mod.config().global().wandItem;
        if (id == null || id.isBlank()) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id.trim());
        if (rl == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        return (item == null || item == Items.AIR) ? null : item;
    }

    /**
     * True only for a stack carrying our hidden marker tag. Uses {@link CustomData#contains} so the
     * common non-wand case allocates nothing — this runs for every block interaction.
     */
    public static boolean isWand(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(WAND_MARKER);
    }

    /** True if the player already holds a WGN wand anywhere in their inventory. */
    public static boolean hasWand(ServerPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isWand(inv.getItem(i))) return true;
        }
        return false;
    }
}
