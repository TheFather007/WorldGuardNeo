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
 * Turns the built-in wand ({@link WandItem}) into a selection tool, replacing WorldEdit's wand:
 * <ul>
 *   <li>left-click a block → set position 1 (cuboid) or add a polygon vertex;</li>
 *   <li>right-click a block → set position 2 (cuboid) or add a polygon vertex.</li>
 * </ul>
 * Every wand interaction is cancelled so the item can <em>only</em> select — it never breaks a
 * block, places, eats or attacks. Listeners run at {@link EventPriority#HIGHEST} so the cancel
 * lands before {@link BlockEventHandler}'s protection checks (which already defer to us — see the
 * "honor higher-priority cancellations (e.g. wand)" note there).
 *
 * <p>Selecting requires {@code worldguardneo.selection.use} (op 0 by default, i.e. everyone). A
 * player who lacks it still can't grief with the wand: the interaction is cancelled, it just
 * doesn't record a position.
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
            return;
        }
        if (leftClick) {
            store.setPos1(p, v);
            msg(p, "msg.selection.pos1", "pos", v.x() + "," + v.y() + "," + v.z());
        } else {
            store.setPos2(p, v);
            msg(p, "msg.selection.pos2", "pos", v.x() + "," + v.y() + "," + v.z());
        }
    }

    private void msg(ServerPlayer p, String key, Object... args) {
        p.displayClientMessage(Component.literal(mod.i18n().format(key, args)), false);
    }
}
