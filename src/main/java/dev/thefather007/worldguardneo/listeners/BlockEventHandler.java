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

    /* -------- BlockEvent.getLevel() returns LevelAccessor; only ServerLevel matters here. -------- */
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
        if (!applicable.isEmpty()) {
            var deniedEvent = new dev.thefather007.worldguardneo.api.events.RegionFlagDeniedEvent(
                    applicable.get(0), Flags.BUILD, p, "block-break");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(deniedEvent);
            if (deniedEvent.isCanceled()) return;
        }
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
        e.setCanceled(true);
        // Re-sync inventory: cancelling EntityPlaceEvent keeps the item server-side but the
        // client already removed it from hand; without this the block vanishes until relog.
        syncInventory(p);
        denyMessage(p, mgr, bp, "place", null);
    }

    /**
     * Farmland trampling: jumping on crops to destroy the farmland underneath (turning it back
     * to dirt and killing the plant) is a classic claim-grief that block-break protection misses,
     * because vanilla routes it through {@link BlockEvent.FarmlandTrampleEvent}, not a break. We
     * gate PLAYER trampling by the same build-access membership rule as breaking: a non-member
     * (no explicit allow) can't trample a claimed farm. Mob/entity trampling is left to vanilla
     * (the {@code mobGriefing} game rule already governs that).
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
     * Force the player's currently-open menu (always at least the inventory menu) to re-broadcast
     * its contents to the client. Used after cancelling a place/use so the client's optimistic
     * prediction (item already consumed) is corrected back to the real server state.
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
        // NeoForge fires RightClickBlock twice per actual click (MAIN_HAND then OFF_HAND) so
        // both held items can react. We check both — off-hand can hold bonemeal while
        // main hand holds a tool, and the bonemeal's right-click effect would otherwise
        // bypass our INTERACT check.
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
                // Fail SAFE: if we can't determine whether this is a container (e.g. a chunk-unload
                // race throwing in getBlockEntity), treat it as a protected container and deny the
                // interaction rather than silently letting a potential chest access through.
                WorldGuardNeo.LOGGER.debug("container detection failed at {} — denying to be safe", bp, t);
                isContainer = true;
                allowed = false;
            }
        }
        if (allowed) return;
        if (canBypass(p)) return;
        e.setCanceled(true);
        syncInventory(p);
        // Pick the clearest message — or stay silent when a message would be noise.
        // - Placing a block (BlockItem in hand): "can't build here".
        // - Opening a container: "can't open containers".
        // - Interacting with something that actually HAS a right-click action (door, button,
        //   lever, bed, etc.): "can't interact here".
        // - Right-clicking a plain block that has NO right-click behaviour (dirt, stone,
        //   cobblestone…) with a non-block item or empty hand: the click does nothing in vanilla
        //   anyway, so showing "can't interact here" is just confusing noise — we cancel silently
        //   (no message, but still no interaction).
        String action;
        if (isContainer) {
            action = "container";
        } else if (heldItem instanceof net.minecraft.world.item.BlockItem) {
            action = "place";
        } else {
            // Resolve the clicked block's state once and reuse it for both the interactable
            // check and the violation detail (was previously fetched twice).
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
     * Heuristic for "does right-clicking this block actually DO something?" Used to decide
     * whether a denied right-click deserves a feedback message. Plain building blocks (dirt,
     * stone, ore, planks…) have no right-click action, so denying them silently avoids the
     * confusing "you can't interact here" when the player wasn't really interacting.
     *
     * <p>We treat a block as interactable if it's a BlockEntity (chests, furnaces, signs, etc.)
     * or matches one of the common interactive vanilla block types (doors, trapdoors, gates,
     * buttons, levers, beds, anvils, workbenches, note blocks, jukeboxes, lecterns, bells,
     * cauldrons, composters, …). This list doesn't need to be exhaustive — a false "not
     * interactable" only means we skip a message for an action that was blocked anyway.
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
        // Without this, explosions were still being filtered by region flags in a world where an
        // admin had disabled WorldGuardNeo.
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
                    // Only shield players who are themselves standing in a region — a player out
                    // in the wilderness next to the blast shouldn't be made immune just because
                    // some of the affected blocks happened to overlap a nearby claim.
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
                // explosion-source flag as blocks. Otherwise a creeper would destroy
                // someone's painting collection even in a creeper-explosion-DENY region.
                // Use null actor — explosions have no "owner" for group resolution; only
                // the global / region-level flag value matters.
                if (ent instanceof net.minecraft.world.entity.decoration.HangingEntity
                        || ent instanceof net.minecraft.world.entity.decoration.ArmorStand) {
                    // Pass raw doubles — testState takes doubles; the old (int) casts truncated
                    // toward zero, mis-binning decorations at negative coordinates by one block.
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
     * True only if the player may bypass region protection entirely. Governed by a SINGLE
     * permission: {@code worldguardneo.region.bypass}, which must be granted explicitly (it is
     * not implied by op status — see OpResolver, where it sits above op level 4).
     *
     * <p>Note: there is deliberately no "global build permission" backdoor here. An earlier
     * version also honoured a configurable {@code buildPermNode}, but that node defaulted to an
     * op-level-0 permission granted to everyone, so it silently let every player bypass all
     * protection. That concept has been removed entirely; region bypass is this one node only.
     */
    private boolean canBypass(ServerPlayer p) {
        return mod.perms().has(p, "worldguardneo.region.bypass");
    }
    /**
     * Show the player the standard (or region-custom) "you can't do that here" message in the
     * ACTION BAR, and record the attempt to the dedicated violation log. This replaces the old
     * behaviour where denied actions produced console noise / vanilla desync warnings.
     *
     * @param action short verb for the log ("break", "place", "interact", "container", "use-item")
     * @param detail optional extra context (block/item id), may be null
     */
    private void denyMessage(ServerPlayer p, RegionManager mgr, BlockPos bp, String action, String detail) {
        String msg;
        String custom = mgr.resolveValue(Flags.DENY_MESSAGE, bp.getX(), bp.getY(), bp.getZ(), p.getUUID());
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
        // Record to the dedicated violation log (async, off the game thread).
        String regionId = null;
        var applicable = mgr.getApplicable(bp.getX(), bp.getY(), bp.getZ());
        if (!applicable.isEmpty()) regionId = applicable.get(0).id();
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
     * Returns true if the given block is listed as "deny" in the world's blocked-blocks config.
     * Accepts ANY modded ResourceLocation — comparison is case-sensitive against the
     * canonical {@code namespace:path} form (e.g. {@code create:cardboard_block}).
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
