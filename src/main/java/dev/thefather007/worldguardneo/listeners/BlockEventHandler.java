package dev.thefather007.worldguardneo.listeners;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.flags.StateFlag;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.UUID;

/**
 * Block-side protections: break / place / interact / explosions / pistons.
 * All handlers exit immediately on the client side or when a bypass permission applies.
 */
public final class BlockEventHandler {

    private final WorldGuardNeo mod;
    public BlockEventHandler(WorldGuardNeo mod) { this.mod = mod; }

    /* BlockEvent.getLevel() returns LevelAccessor; only ServerLevel matters here. */
    private static ServerLevel asServerLevel(Object levelAccessor) {
        return (levelAccessor instanceof ServerLevel sl) ? sl : null;
    }

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent e) {
        ServerLevel lvl = asServerLevel(e.getLevel());
        if (lvl == null) return;
        if (!mod.isProtectionActive(lvl)) return;
        if (!(e.getPlayer() instanceof ServerPlayer p)) return;
        RegionManager mgr = mod.regions().get(lvl);
        BlockPos bp = e.getPos();
        int x = bp.getX(), y = bp.getY(), z = bp.getZ();
        UUID id = p.getUUID();
        // World-config blocked block IDs apply per-world regardless of region membership.
        if (isBlockedBlock(lvl, e.getState().getBlock())) {
            if (canBypass(p)) return;
            e.setCanceled(true);
            denyMessage(p, mgr, bp, "break-blocked", blockId(e.getState().getBlock()));
            return;
        }
        // Build-access = explicit flags first, else implicit membership protection.
        if (mgr.testBuildAccess(Flags.BLOCK_BREAK, x, y, z, id)
         && mgr.testBuildAccess(Flags.BUILD,       x, y, z, id)) return;
        if (canBypass(p)) return;
        var applicable = mgr.getApplicable(x, y, z);
        if (!applicable.isEmpty() && dev.thefather007.worldguardneo.api.events.RegionFlagDeniedEvent
                .isOverridden(applicable.get(0), Flags.BUILD, p, "block-break")) return;
        e.setCanceled(true);
        denyMessage(p, mgr, bp, "break", null);
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.EntityPlaceEvent e) {
        ServerLevel lvl = asServerLevel(e.getLevel());
        if (lvl == null) return;
        if (!mod.isProtectionActive(lvl)) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        RegionManager mgr = mod.regions().get(lvl);
        BlockPos bp = e.getPos();
        int x = bp.getX(), y = bp.getY(), z = bp.getZ();
        UUID id = p.getUUID();
        if (isBlockedBlock(lvl, e.getPlacedBlock().getBlock())) {
            if (canBypass(p)) return;
            e.setCanceled(true);
            syncInventory(p);
            denyMessage(p, mgr, bp, "place-blocked", blockId(e.getPlacedBlock().getBlock()));
            return;
        }
        if (mgr.testBuildAccess(Flags.BLOCK_PLACE, x, y, z, id)
         && mgr.testBuildAccess(Flags.BUILD,       x, y, z, id)) return;
        if (canBypass(p)) return;
        // Public API override hook (see RegionFlagDeniedEvent) — a listener may cancel to permit.
        var applicable = mgr.getApplicable(x, y, z);
        if (!applicable.isEmpty() && dev.thefather007.worldguardneo.api.events.RegionFlagDeniedEvent
                .isOverridden(applicable.get(0), Flags.BUILD, p, "block-place")) return;
        e.setCanceled(true);
        // Resync inventory: cancelling EntityPlaceEvent keeps the item server-side but the
        // client already removed it from hand; without this the block vanishes until relog.
        syncInventory(p);
        denyMessage(p, mgr, bp, "place", null);
    }

    /**
     * Farmland trampling routes through FarmlandTrampleEvent, not a break, so block-break
     * protection misses it. Gate PLAYER trampling by the same build-access rule as breaking;
     * mob/entity trampling is left to vanilla (the {@code mobGriefing} game rule).
     */
    @SubscribeEvent
    public void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent e) {
        ServerLevel lvl = asServerLevel(e.getLevel());
        if (lvl == null) return;
        if (!mod.isProtectionActive(lvl)) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        RegionManager mgr = mod.regions().get(lvl);
        BlockPos bp = e.getPos();
        int x = bp.getX(), y = bp.getY(), z = bp.getZ();
        UUID id = p.getUUID();
        if (mgr.testBuildAccess(Flags.BLOCK_BREAK, x, y, z, id)
         && mgr.testBuildAccess(Flags.BUILD,       x, y, z, id)) return;
        if (canBypass(p)) return;
        e.setCanceled(true);
    }

    /**
     * Re-broadcast the player's open menu to the client after a cancelled place/use, correcting
     * the client's optimistic prediction (item already consumed) back to real server state.
     */
    private static void syncInventory(ServerPlayer p) {
        try {
            p.containerMenu.sendAllDataToRemote();
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("inventory resync failed", t);
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.isCanceled()) return; // honor higher-priority cancellations (e.g. wand)
        if (e.getLevel().isClientSide()) return;
        if (!mod.isProtectionActive(e.getLevel())) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        // NeoForge fires RightClickBlock twice per click (MAIN_HAND then OFF_HAND); we check both
        // so an off-hand item (e.g. bonemeal) can't bypass the INTERACT check.
        ServerLevel sl = p.serverLevel();
        RegionManager mgr = mod.regions().get(sl);
        BlockPos bp = e.getPos();
        int x = bp.getX(), y = bp.getY(), z = bp.getZ();
        var heldItem = e.getItemStack().getItem();
        if (isBlockedItem(sl, heldItem)) {
            if (canBypass(p)) return;
            e.setCanceled(true);
            syncInventory(p);
            denyMessage(p, mgr, bp, "use-item-blocked", itemId(heldItem));
            return;
        }
        UUID id = p.getUUID();
        // Dedicated right-click toggles (all default ALLOW): an explicit deny blocks that specific
        // action even for members, independent of the generic interact/use gate below. Resolve and
        // test the toggle FIRST; only consult region.bypass when it denies (lazy-bypass pattern, so
        // a normal allowed right-click never hits LuckPerms).
        StateFlag ded = null;
        String dmsg = null;
        if (heldItem instanceof net.minecraft.world.item.MinecartItem) {
            ded = Flags.VEHICLE_PLACE; dmsg = "msg.vehicle.place-denied";
        } else if (heldItem instanceof net.minecraft.world.item.BucketItem) {
            boolean empty = heldItem == net.minecraft.world.item.Items.BUCKET;
            ded  = empty ? Flags.BUCKET_FILL : Flags.BUCKET_EMPTY;
            dmsg = empty ? "msg.bucket.fill-denied" : "msg.bucket.empty-denied";
        } else {
            BlockState st = sl.getBlockState(bp);
            if (st.getBlock() instanceof net.minecraft.world.level.block.LecternBlock
                    && st.getValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK)) {
                ded = Flags.LECTERN_TAKE; dmsg = "msg.lectern.take-denied";
            } else if (st.getBlock() instanceof net.minecraft.world.level.block.SignBlock) {
                ded = Flags.SIGN_EDIT; dmsg = "msg.sign.edit-denied";
            }
        }
        if (ded != null && !mgr.testState(ded, id, x, y, z) && !canBypass(p)) {
            e.setCanceled(true);
            syncInventory(p);
            p.displayClientMessage(Component.literal(mod.i18n().raw(dmsg)), true);
            return;
        }
        // INTERACT/USE use build-access semantics (members interact freely, strangers are
        // blocked unless a flag explicitly opens it). Containers additionally require chest-access.
        boolean allowed = mgr.testBuildAccess(Flags.INTERACT, x, y, z, id)
                       && mgr.testBuildAccess(Flags.USE,      x, y, z, id);
        boolean isContainer = false;
        if (allowed) {
            try {
                var be = sl.getBlockEntity(bp);
                if (be instanceof net.minecraft.world.Container) {
                    isContainer = true;
                    allowed = mgr.testBuildAccess(Flags.CHEST_ACCESS, x, y, z, id);
                }
            } catch (Throwable t) {
                // Fail safe: if container detection throws (e.g. chunk-unload race), treat it as a
                // protected container and deny rather than risk leaking chest access.
                WorldGuardNeo.LOGGER.debug("container detection failed at {} — denying to be safe", bp, t);
                isContainer = true;
                allowed = false;
            }
        }
        if (allowed) return;
        if (canBypass(p)) return;
        // Public API override hook for interact/container denials (see RegionFlagDeniedEvent).
        var applicable = mgr.getApplicable(x, y, z);
        if (!applicable.isEmpty() && dev.thefather007.worldguardneo.api.events.RegionFlagDeniedEvent
                .isOverridden(applicable.get(0), isContainer ? Flags.CHEST_ACCESS : Flags.INTERACT,
                        p, isContainer ? "container" : "interact")) return;
        e.setCanceled(true);
        syncInventory(p);
        // Pick the clearest message — or stay silent when one would be noise (e.g. right-clicking
        // a plain block with no vanilla right-click action: cancel silently, no confusing message).
        String action;
        if (isContainer) {
            action = "container";
        } else if (heldItem instanceof net.minecraft.world.item.BlockItem) {
            action = "place";
        } else {
            BlockState clicked = sl.getBlockState(bp);
            if (isInteractableBlock(clicked)) {
                denyMessage(p, mgr, bp, "interact", blockId(clicked.getBlock()));
            }
            // else: non-interactable block + no placement → suppress message (event still cancelled)
            return;
        }
        denyMessage(p, mgr, bp, action, blockId(sl.getBlockState(bp).getBlock()));
    }

    /**
     * Boats are placed via a right-click of the item (not a block click), bypassing
     * {@link #onRightClickBlock}. Gate them with {@code vehicle-place} at the player's position.
     * Minecarts are placed on rails and go through the block-click path above.
     */
    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getLevel().isClientSide()) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (!(e.getItemStack().getItem() instanceof net.minecraft.world.item.BoatItem)) return;
        if (!mod.isProtectionActive(p.serverLevel())) return;
        RegionManager mgr = mod.regions().get(p.serverLevel());
        // Resolve the flag first; only consult region.bypass if it denies (lazy-bypass pattern).
        if (!mgr.testState(Flags.VEHICLE_PLACE, p.getUUID(), p.getX(), p.getY(), p.getZ()) && !canBypass(p)) {
            e.setCanceled(true);
            syncInventory(p);
            p.displayClientMessage(Component.literal(mod.i18n().raw("msg.vehicle.place-denied")), true);
        }
    }

    /**
     * Heuristic for "does right-clicking this block actually DO something?", deciding whether a
     * denied right-click deserves a feedback message. A block is interactable if it has a
     * BlockEntity or matches a common interactive vanilla type. The list need not be exhaustive —
     * a false negative only means we skip a message for an action that was blocked anyway.
     */
    private static boolean isInteractableBlock(BlockState st) {
        var b = st.getBlock();
        if (b instanceof net.minecraft.world.level.block.EntityBlock) return true; // has a BlockEntity
        return b instanceof net.minecraft.world.level.block.DoorBlock
            || b instanceof net.minecraft.world.level.block.TrapDoorBlock
            || b instanceof net.minecraft.world.level.block.FenceGateBlock
            || b instanceof net.minecraft.world.level.block.ButtonBlock
            || b instanceof net.minecraft.world.level.block.LeverBlock
            || b instanceof net.minecraft.world.level.block.BedBlock
            || b instanceof net.minecraft.world.level.block.AnvilBlock
            || b instanceof net.minecraft.world.level.block.NoteBlock
            || b instanceof net.minecraft.world.level.block.CakeBlock
            || b instanceof net.minecraft.world.level.block.CauldronBlock
            || b instanceof net.minecraft.world.level.block.ComposterBlock
            || b instanceof net.minecraft.world.level.block.RepeaterBlock
            || b instanceof net.minecraft.world.level.block.ComparatorBlock
            || b instanceof net.minecraft.world.level.block.DaylightDetectorBlock
            || b instanceof net.minecraft.world.level.block.RedStoneOreBlock
            || b instanceof net.minecraft.world.level.block.FlowerPotBlock;
    }

    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate e) {
        Level lvl = e.getLevel();
        if (lvl.isClientSide()) return;
        // Honour the per-world protection kill-switch (useRegions=false) like every other handler.
        if (!mod.isProtectionActive(lvl)) return;
        RegionManager mgr = mod.regions().get(lvl);

        // World-wide: if any affected block sits in a region and admins set
        // disableExplosionsAroundRegions=true, neuter the entire explosion by clearing
        // both the affected-blocks and affected-entities lists. Cheaper than per-block
        // filtering when the explosion would have wrecked half a claim anyway.
        if (lvl instanceof ServerLevel slvl) {
            var ws = mod.config().worldOrGlobal(slvl);
            if (ws != null && ws.disableExplosionsAroundRegions) {
                boolean touchesRegion = false;
                for (var pos : e.getAffectedBlocks()) {
                    if (mgr.hasAnyAt(pos.getX(), pos.getY(), pos.getZ())) {
                        touchesRegion = true;
                        break;
                    }
                }
                if (touchesRegion) {
                    e.getAffectedBlocks().clear();
                    // Only shield players actually standing in a region; one in the wilderness
                    // next to the blast shouldn't be made immune by a nearby claim overlap.
                    e.getAffectedEntities().removeIf(ent -> ent instanceof ServerPlayer
                            && mgr.hasAnyAt(ent.getX(), ent.getY(), ent.getZ()));
                    return;
                }
            }
        }

        StateFlag srcFlag = selectExplosionFlag(e.getExplosion().getDirectSourceEntity());
        final StateFlag finalFlag = srcFlag;
        // If the affected lists are already empty (e.g. cleared by another handler at lower
        // priority), skip the iteration. Saves an iterator allocation per empty event.
        if (!e.getAffectedBlocks().isEmpty()) {
            e.getAffectedBlocks().removeIf(pos ->
                    !mgr.testState(finalFlag, null, pos.getX(), pos.getY(), pos.getZ()));
        }
        if (!e.getAffectedEntities().isEmpty()) {
            e.getAffectedEntities().removeIf(ent -> {
                // Players: gated by PLAYER_DAMAGE (lets PvP zones override).
                if (ent instanceof ServerPlayer) {
                    return !mgr.testState(Flags.PLAYER_DAMAGE, ent.getUUID(),
                            ent.getX(), ent.getY(), ent.getZ());
                }
                // Decorations (item frames, paintings, armor stands): gated by the same
                // explosion-source flag as blocks. Null actor — explosions have no owner for
                // group resolution; only the global/region flag value matters.
                if (ent instanceof net.minecraft.world.entity.decoration.HangingEntity
                        || ent instanceof net.minecraft.world.entity.decoration.ArmorStand) {
                    return !mgr.testState(finalFlag, null, ent.getX(), ent.getY(), ent.getZ());
                }
                return false; // Mobs, items, projectiles etc. — vanilla rules.
            });
        }
    }

    /**
     * Map an explosion's source entity to the flag that should gate it. Falls back to
     * OTHER_EXPLOSION for unknown sources (custom mod entities, bed explosions, end-crystal
     * detonations triggered without a tracked source, etc).
     */
    private static StateFlag selectExplosionFlag(net.minecraft.world.entity.Entity src) {
        if (src == null) return Flags.OTHER_EXPLOSION;
        var type = src.getType();
        if (type == net.minecraft.world.entity.EntityType.CREEPER)        return Flags.CREEPER_EXPLOSION;
        if (type == net.minecraft.world.entity.EntityType.GHAST
         || type == net.minecraft.world.entity.EntityType.SMALL_FIREBALL
         || type == net.minecraft.world.entity.EntityType.FIREBALL)       return Flags.GHAST_FIREBALL;
        if (type == net.minecraft.world.entity.EntityType.TNT
         || type == net.minecraft.world.entity.EntityType.TNT_MINECART)   return Flags.TNT;
        if (type == net.minecraft.world.entity.EntityType.WITHER
         || type == net.minecraft.world.entity.EntityType.WITHER_SKULL)   return Flags.OTHER_EXPLOSION;
        if (type == net.minecraft.world.entity.EntityType.END_CRYSTAL)    return Flags.OTHER_EXPLOSION;
        if (type == net.minecraft.world.entity.EntityType.ENDER_DRAGON)   return Flags.ENDERDRAGON;
        return Flags.OTHER_EXPLOSION;
    }

    /* helpers */
    /**
     * True only if the player may bypass region protection entirely. Governed by the single
     * permission {@code worldguardneo.region.bypass}, which must be granted explicitly (not
     * implied by op status — see OpResolver, where it sits above op level 4).
     */
    private boolean canBypass(ServerPlayer p) {
        return mod.perms().has(p, "worldguardneo.region.bypass");
    }
    /**
     * Show the standard (or region-custom) deny message in the action bar and record the attempt
     * to the violation log.
     *
     * @param action short verb for the log ("break", "place", "interact", "container", "use-item")
     * @param detail optional extra context (block/item id), may be null
     */
    private void denyMessage(ServerPlayer p, RegionManager mgr, BlockPos bp, String action, String detail) {
        // Resolve the applicable list once, shared by the custom deny-message lookup and the
        // violation-log region id.
        var applicable = mgr.getApplicable(bp.getX(), bp.getY(), bp.getZ());
        String msg;
        String custom = mgr.resolveValue(Flags.DENY_MESSAGE, applicable, p.getUUID());
        if (custom != null) {
            msg = custom;
        } else {
            String key = switch (action) {
                case "break", "break-blocked"   -> "msg.protection.deny-break";
                case "place", "place-blocked"   -> "msg.protection.deny-place";
                case "container"                -> "msg.protection.deny-container";
                case "interact"                 -> "msg.protection.deny-interact";
                case "use-item-blocked"         -> "msg.protection.deny-use-item";
                default                          -> "msg.protection.deny";
            };
            msg = mod.i18n().has(key) ? mod.i18n().raw(key) : mod.i18n().raw("msg.protection.deny");
        }
        p.displayClientMessage(Component.literal(msg), true);
        String regionId = applicable.isEmpty() ? null : applicable.get(0).id();
        mod.violations().record(p, action, detail,
                bp.getX(), bp.getY(), bp.getZ(),
                p.serverLevel().dimension().location().toString(), regionId);
    }

    /** Canonical "namespace:path" id for a block, or null if unregistered. */
    private static String blockId(net.minecraft.world.level.block.Block block) {
        if (block == null) return null;
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? null : key.toString();
    }

    /** Canonical "namespace:path" id for an item, or null if unregistered. */
    private static String itemId(net.minecraft.world.item.Item item) {
        if (item == null) return null;
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return key == null ? null : key.toString();
    }

    /**
     * True if the block is listed as "deny" in the world's blocked-blocks config. Matches the
     * canonical {@code namespace:path} form, or bare vanilla form for {@code minecraft:} blocks.
     */
    private boolean isBlockedBlock(ServerLevel lvl, net.minecraft.world.level.block.Block block) {
        if (block == null) return false;
        var ws = mod.config().worldOrGlobal(lvl);
        if (ws == null || ws.blockedBlocks == null || ws.blockedBlocks.isEmpty()) return false;
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        if (key == null) return false;
        String full = key.toString();
        String action = ws.blockedBlocks.get(full);
        if (action == null && full.startsWith("minecraft:")) {
            // Accept bare form ("oak_planks") for vanilla blocks — common admin shorthand.
            action = ws.blockedBlocks.get(full.substring("minecraft:".length()));
        }
        return "deny".equalsIgnoreCase(action);
    }

    /** Same as {@link #isBlockedBlock} but for held items / item-in-hand checks. */
    private boolean isBlockedItem(ServerLevel lvl, net.minecraft.world.item.Item item) {
        if (item == null) return false;
        var ws = mod.config().worldOrGlobal(lvl);
        if (ws == null || ws.blockedItems == null || ws.blockedItems.isEmpty()) return false;
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        if (key == null) return false;
        String full = key.toString();
        String action = ws.blockedItems.get(full);
        if (action == null && full.startsWith("minecraft:")) {
            action = ws.blockedItems.get(full.substring("minecraft:".length()));
        }
        return "deny".equalsIgnoreCase(action);
    }
}
