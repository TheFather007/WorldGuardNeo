package dev.thefather007.worldguardneo.listeners;

import com.mojang.brigadier.ParseResults;
import dev.thefather007.worldguardneo.WorldGuardNeo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;

import java.util.Locale;

/**
 * Customises WorldEdit's {@code //wand}. WorldEdit hands out a plain wooden axe; we intercept the
 * command and instead give a wooden axe with a custom name and a hidden marker tag. WorldEdit still
 * recognises it as a wand (it identifies wands purely by item type), so selection keeps working.
 *
 * <p>If the player already holds our marked wand, {@code //wand} is a no-op with a message instead
 * of stacking another axe.
 *
 * <p>We only take over {@code //wand} for players who have {@code worldguardneo.selection.use}
 * (op 0 by default, i.e. everyone unless an admin restricts it). For anyone else we don't touch the
 * command and WorldEdit handles it as usual.
 */
public final class WandCommandHandler {

    /** Hidden marker in the wand's custom data, so we recognise our own wand reliably. */
    public static final String WAND_MARKER = "wgn_wand";

    private final WorldGuardNeo mod;

    public WandCommandHandler(WorldGuardNeo mod) { this.mod = mod; }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        ParseResults<CommandSourceStack> parse = event.getParseResults();
        // Brigadier hands us the parsed input without the leading slash, so "//wand" arrives as
        // "/wand". Strip any leading slashes and check the first token.
        String raw = parse.getReader().getString().trim();
        String cmd = raw;
        while (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.isEmpty()) return;
        String first = cmd.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!first.equals("wand")) return;

        ServerPlayer player = parse.getContext().getSource().getPlayer();
        if (player == null) return; // console / command block — leave to WorldEdit

        // Only override for players allowed to select; otherwise let WorldEdit decide.
        if (!mod.perms().has(player, "worldguardneo.selection.use")) return;

        event.setCanceled(true);

        if (hasOurWand(player)) {
            player.displayClientMessage(Component.literal(mod.i18n().format("msg.wand.already")), false);
            return;
        }
        ItemStack wand = createWand();
        if (!player.getInventory().add(wand)) player.drop(wand, false);
        player.displayClientMessage(Component.literal(mod.i18n().format("msg.wand.given")), false);
    }

    private ItemStack createWand() {
        ItemStack axe = new ItemStack(Items.WOODEN_AXE);
        axe.set(DataComponents.CUSTOM_NAME, Component.literal(mod.i18n().format("item.wand.name")));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(WAND_MARKER, true);
        axe.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return axe;
    }

    private boolean hasOurWand(ServerPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isOurWand(inv.getItem(i))) return true;
        }
        return false;
    }

    /** True only for a wooden axe carrying our marker tag (a renamed vanilla axe won't match). */
    public static boolean isOurWand(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.WOODEN_AXE)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(WAND_MARKER);
    }
}
