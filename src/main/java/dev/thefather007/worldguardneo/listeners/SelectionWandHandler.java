package dev.thefather007.worldguardneo.listeners;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.selection.SelectionStore;
import dev.thefather007.worldguardneo.selection.WandItem;
import dev.thefather007.worldguardneo.util.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Turns the built-in wand ({@link WandItem}) into a selection tool: left-click sets pos1 / adds a
 * polygon vertex, right-click sets pos2 / adds a vertex. Every wand interaction is cancelled so the
 * item can only select. Runs at {@link EventPriority#HIGHEST} so the cancel lands before
 * {@link BlockEventHandler}'s protection checks (which defer to us). Selecting requires
 * {@code worldguardneo.selection.use}; lacking it still cancels, it just records nothing.
 */
public final class SelectionWandHandler {

    private final WorldGuardNeo mod;
    public SelectionWandHandler(WorldGuardNeo mod) { this.mod = mod; }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
        if (e.getLevel().isClientSide()) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (!WandItem.isWand(e.getItemStack())) return;
        // LeftClickBlock fires multiple actions (START/STOP/ABORT/CLIENT_HOLD) per physical click;
        // act only on START so a single click records a single position.
        e.setCanceled(true);
        e.setUseBlock(TriState.FALSE);
        e.setUseItem(TriState.FALSE);
        if (e.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
        if (!mod.perms().has(p, "worldguardneo.selection.use")) return;
        handle(p, e.getPos(), true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getLevel().isClientSide()) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (!WandItem.isWand(e.getItemStack())) return;
        e.setCanceled(true);
        e.setUseBlock(TriState.FALSE);
        e.setUseItem(TriState.FALSE);
        if (!mod.perms().has(p, "worldguardneo.selection.use")) return;
        handle(p, e.getPos(), false);
    }

    /** Right-click in air: nothing to select, but still suppress the wand's normal use. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getLevel().isClientSide()) return;
        if (!WandItem.isWand(e.getItemStack())) return;
        e.setCanceled(true);
    }

    /* ----------------------------------------------------------------- internals */

    private void handle(ServerPlayer p, BlockPos bp, boolean leftClick) {
        SelectionStore store = mod.selections();
        Vec3 v = new Vec3(bp.getX(), bp.getY(), bp.getZ());
        SelectionStore.Selection sel = store.getOrCreate(p.getUUID());
        if (sel.mode == SelectionStore.Mode.POLYGON) {
            int n = store.addPolyPoint(p, v);
            msg(p, "msg.selection.point", "n", String.valueOf(n),
                    "pos", v.x() + "," + v.y() + "," + v.z());
        } else if (leftClick) {
            store.setPos1(p, v);
            msg(p, "msg.selection.pos1", "pos", v.x() + "," + v.y() + "," + v.z());
        } else {
            store.setPos2(p, v);
            msg(p, "msg.selection.pos2", "pos", v.x() + "," + v.y() + "," + v.z());
        }
        // Live dimensions in the action bar — refreshed on every click. Almost free: a few ints.
        showDimensions(p, sel);
    }

    /** Show the current selection's size (W×H×L + volume, or polygon point count + Y span). */
    private void showDimensions(ServerPlayer p, SelectionStore.Selection sel) {
        String bar;
        if (sel.mode == SelectionStore.Mode.CUBOID) {
            if (sel.pos1 == null || sel.pos2 == null) return; // need both corners
            int w = Math.abs(sel.pos2.x() - sel.pos1.x()) + 1;
            int h = Math.abs(sel.pos2.y() - sel.pos1.y()) + 1;
            int l = Math.abs(sel.pos2.z() - sel.pos1.z()) + 1;
            bar = mod.i18n().format("msg.selection.dims",
                    "w", w, "h", h, "l", l, "vol", (long) w * h * l);
        } else {
            if (sel.polyPoints.isEmpty()) return;
            bar = mod.i18n().format("msg.selection.poly-dims",
                    "n", sel.polyPoints.size(),
                    "miny", sel.polyMinY, "maxy", sel.polyMaxY);
        }
        p.displayClientMessage(Component.literal(bar), true); // true = action bar
    }

    private void msg(ServerPlayer p, String key, Object... args) {
        p.displayClientMessage(Component.literal(mod.i18n().format(key, args)), false);
    }
}
