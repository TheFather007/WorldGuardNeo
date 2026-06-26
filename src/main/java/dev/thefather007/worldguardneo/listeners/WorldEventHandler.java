package dev.thefather007.worldguardneo.listeners;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.flags.StateFlag;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Environment & misc protections (fluid flow, fire/lava spread, redstone, pistons, lightning,
 * exp drops, blocked-effects, crop/tree growth, notify fan-out).
 *
 * <p>Random-tick-driven flags (ice/snow form/melt, grass/mycelium spread, vine growth, leaf
 * decay) are intentionally NOT wired: random ticks bypass all public NeoForge events and would
 * need a mixin/coremod. They stay editable via {@code /rg flag} so an add-on can enforce them
 * later without changing storage. Same for {@code receive-chat} and {@code allowed-enchants}.
 */
public final class WorldEventHandler {

    private final WorldGuardNeo mod;
    public WorldEventHandler(WorldGuardNeo mod) { this.mod = mod; }

    private static ServerLevel asServerLevel(Object levelAccessor) {
        return (levelAccessor instanceof ServerLevel sl) ? sl : null;
    }

    /**
     * State-flag probe at a block position with no actor (world-driven events). Has a
     * {@code hasAnyAt} fast-path for wilderness, critical for hot paths (crop growth, redstone).
     */
    private boolean test(RegionManager mgr, StateFlag flag, BlockPos bp) {
        int x = bp.getX(), y = bp.getY(), z = bp.getZ();
        // Allocation-free fast path: no region at the point and global has no opinion → flag
        // default, skipping spatial resolution.
        if (!mgr.hasAnyAt(x, y, z) && mgr.globalRegion().getFlag(flag) == null) {
            return flag.defaultAllow();
        }
        return mgr.testState(flag, null, x, y, z);
    }

    /* -------- fluid placement (lava-flow, water-flow) -------- */

    @SubscribeEvent
    public void onFluidPlace(BlockEvent.FluidPlaceBlockEvent e) {
        ServerLevel lvl = asServerLevel(e.getLevel());
        if (lvl == null) return;
        if (!mod.isProtectionActive(lvl)) return;
        RegionManager mgr = mod.regions().get(lvl);
        // Fluid solidification (cobble/stone/obsidian/basalt) always involves lava, so known
        // products gate under LAVA_FLOW — else "lava-flow deny" fails to stop cobble generators.
        // WATER_FLOW is the modded fallback.
        BlockState newState = e.getNewState();
        boolean lavaInvolved = newState.is(Blocks.OBSIDIAN)
                || newState.is(Blocks.BASALT)
                || newState.is(Blocks.STONE)
                || newState.is(Blocks.COBBLESTONE);
        StateFlag flag = lavaInvolved ? Flags.LAVA_FLOW : Flags.WATER_FLOW;
        if (!test(mgr, flag, e.getPos())) e.setCanceled(true);
    }

    /* -------- redstone propagation -------- */

    /**
     * Stops pistons from moving blocks ACROSS a region boundary (pushing in from outside or
     * pulling out with a sticky piston). Gather every cell the operation touches; if any lies in
     * a foreign region the piston isn't part of and that doesn't opt in, cancel. Pistons have no
     * actor to permission-check, so the boundary itself is the protection.
     */
    @SubscribeEvent
    public void onPistonPre(net.neoforged.neoforge.event.level.PistonEvent.Pre e) {
        ServerLevel lvl = asServerLevel(e.getLevel());
        if (lvl == null) return;
        if (!mod.isProtectionActive(lvl)) return;
        RegionManager mgr = mod.regions().get(lvl);
        // Fast path: no regions in this world → a piston can't push across a region border. Bail
        // before allocating the touched-cells list or probing the index (redstone farms fire this a lot).
        if (mgr.size() == 0) return;
        net.minecraft.core.Direction dir = e.getDirection();
        BlockPos piston = e.getPos();
        // getDirection() is the piston's FACING; retraction (sticky pull) moves blocks the
        // opposite way, so its destinations use the inverted direction.
        boolean extending = e.getPistonMoveType()
                == net.neoforged.neoforge.event.level.PistonEvent.PistonMoveType.EXTEND;
        net.minecraft.core.Direction moveDir = extending ? dir : dir.getOpposite();

        var pistonRegions = mgr.getApplicable(piston.getX(), piston.getY(), piston.getZ());

        // Every cell the move affects: each moved block's origin (catches sticky-piston theft
        // FROM a region) AND its destination (catches pushes INTO one), plus destroyed blocks.
        java.util.List<BlockPos> touched = new java.util.ArrayList<>();
        touched.add(e.getFaceOffsetPos());
        var helper = e.getStructureHelper();
        if (helper != null && helper.resolve()) {
            for (BlockPos p : helper.getToPush()) {
                touched.add(p);
                touched.add(p.relative(moveDir));
            }
            touched.addAll(helper.getToDestroy());
        } else {
            touched.add(e.getFaceOffsetPos().relative(moveDir));
        }

        // First touched cell in a foreign region (not the piston's own) whose PISTONS flag
        // doesn't allow the move. The piston's own region(s) are always fine.
        ProtectedRegion violated = null;
        for (BlockPos p : touched) {
            var hereRegions = mgr.getApplicable(p.getX(), p.getY(), p.getZ());
            for (ProtectedRegion r : hereRegions) {
                if (containsRegion(pistonRegions, r.id())) continue; // piston's own zone → fine
                // Foreign region. Allow only if its PISTONS flag is explicitly ALLOW.
                if (!resolvePistonsAllow(r)) {
                    violated = r;
                    break;
                }
            }
            if (violated != null) break;
        }
        if (violated == null) return; // move stays within the piston's own zone(s) → permit

        // Cross-border move into a protected foreign region: break and drop the offending piston
        // (a silent cancel leaves it visually stuck extended). Deferred to next tick via the server
        // executor — mutating block state inside the event re-fires it → infinite loop → crash.
        e.setCanceled(true);
        final BlockPos pistonPos = piston.immutable();
        lvl.getServer().execute(() -> {
            try {
                if (!lvl.isLoaded(pistonPos)) return;
                var st = lvl.getBlockState(pistonPos);
                if (st.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock) {
                    // true = drop the block as an item, matching the previous (preferred) behaviour.
                    lvl.destroyBlock(pistonPos, true);
                }
            } catch (Throwable ignored) {
                // Best-effort; the cancel already protected the region.
            }
        });
    }

    /** True if the region id is among the supplied applicable list. */
    private static boolean containsRegion(java.util.List<ProtectedRegion> list, String id) {
        for (ProtectedRegion r : list) if (r.id().equals(id)) return true;
        return false;
    }

    /**
     * Resolve a region's PISTONS flag (walking parents) for a cross-border move. Only an EXPLICIT
     * ALLOW opts the region in; unset means protected (a "unset → allow" default would gut the
     * boundary rule, since pistons is unset on every fresh claim).
     */
    private static boolean resolvePistonsAllow(ProtectedRegion r) {
        StateFlag.State s = null;
        ProtectedRegion cur = r;
        int hops = 0;
        while (cur != null && hops++ < 32) {
            s = cur.getFlag(Flags.PISTONS);
            if (s != null) break;
            cur = cur.parent();
        }
        return s == StateFlag.State.ALLOW; // only an explicit ALLOW permits cross-border moves
    }

    /**
     * Single subscriber for {@link BlockEvent.NeighborNotifyEvent} covering both redstone gating
     * and fire/lava/water propagation. NeighborNotify is the hottest block event, so sharing the
     * filters here lets the vast majority of traffic exit in a couple of comparisons.
     */
    @SubscribeEvent
    public void onNeighborNotify(BlockEvent.NeighborNotifyEvent e) {
        ServerLevel lvl = asServerLevel(e.getLevel());
        if (lvl == null) return;
        if (!mod.isProtectionActive(lvl)) return;
        BlockState state = e.getState();

        // ---- redstone branch ----
        if (state.isSignalSource()) {
            RegionManager mgr = mod.regions().get(lvl);
            if (!test(mgr, Flags.REDSTONE, e.getPos())) {
                e.setCanceled(true);
            }
            return; // signal sources are never fire/lava/water — branches are disjoint
        }

        // ---- fire / lava / water propagation branch ----
        boolean isFire = state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
        boolean isLava = state.is(Blocks.LAVA);
        boolean isWater = state.is(Blocks.WATER);
        // Fast pre-filter: everything that isn't fire/lava/water (the vast majority) exits here.
        if (!isFire && !isLava && !isWater) return;

        RegionManager mgr = mod.regions().get(lvl);
        BlockPos src = e.getPos();

        // World-wide kill-switches FIRST — they must apply in wilderness too (below the nearRegion
        // bail they'd be ineffective away from claims).
        var ws = mod.config().worldOrGlobal(lvl);
        boolean killFire = ws != null && isFire && ws.preventFireSpread;
        boolean killLava = ws != null && isLava && ws.preventLavaFire;
        if (killFire || killLava) { e.setCanceled(true); return; }

        StateFlag flag = isFire ? Flags.FIRE_SPREAD : (isLava ? Flags.LAVA_FIRE : null);

        // Global-region flag (fire/lava only): applies everywhere, so honour it before nearRegion.
        if (flag != null) {
            StateFlag.State g = mgr.globalRegion().getFlag(flag);
            if (g == StateFlag.State.DENY) { e.setCanceled(true); return; }
        }

        // Water/lava neighbour-notifies fire constantly; cheaply probe whether a region is near the
        // source or any notified side before doing real work.
        boolean nearRegion = mgr.hasAnyAt(src.getX(), src.getY(), src.getZ());
        if (!nearRegion) {
            for (net.minecraft.core.Direction d : e.getNotifiedSides()) {
                BlockPos t = src.relative(d);
                if (mgr.hasAnyAt(t.getX(), t.getY(), t.getZ())) { nearRegion = true; break; }
            }
        }
        if (!nearRegion) return; // wilderness fluid/fire far from any claim → vanilla, zero cost

        // Single pass over notified sides doing both checks:
        //  (1) cross-border containment — fluid/fire must not propagate into a cell belonging to a
        //      region the source isn't part of (symmetric, allocation-free crossesBoundary probe).
        //  (2) per-region fire-spread/lava-fire flag at the target (fire & lava only; water has none).
        int sx = src.getX(), sy = src.getY(), sz = src.getZ();
        for (net.minecraft.core.Direction dir : e.getNotifiedSides()) {
            BlockPos target = src.relative(dir);
            int tx = target.getX(), ty = target.getY(), tz = target.getZ();
            if (mgr.crossesBoundary(sx, sy, sz, tx, ty, tz)) {
                e.setCanceled(true);
                return;
            }
            if (flag != null && !mgr.testState(flag, null, tx, ty, tz)) {
                e.setCanceled(true);
                return;
            }
        }
    }

    /* -------- experience drops -------- */

    @SubscribeEvent
    public void onExpDrop(LivingExperienceDropEvent e) {
        LivingEntity victim = e.getEntity();
        // EXP_DROPS governs MOB experience only; player death XP is owned by the KEEP_XP handler.
        // Handling players here too would let `exp-drops deny` destroy player XP and fight keep-xp.
        if (victim instanceof ServerPlayer) return;
        Level lvl = victim.level();
        if (lvl.isClientSide()) return;
        if (!mod.isProtectionActive(lvl)) return;
        RegionManager mgr = mod.regions().get(lvl);
        double x = victim.getX(), y = victim.getY(), z = victim.getZ();
        if (!mgr.testState(Flags.EXP_DROPS, null, x, y, z)) {
            e.setDroppedExperience(0);
        }
    }

    /* -------- lightning strikes -------- */

    /**
     * Cancel lightning bolts that would land in a region forbidding them (natural or
     * trident/channeling-induced); the bolt simply never spawns.
     */
    @SubscribeEvent
    public void onLightningSpawn(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (!(e.getEntity() instanceof net.minecraft.world.entity.LightningBolt bolt)) return;
        Level lvl = e.getLevel();
        if (lvl.isClientSide()) return;
        if (!mod.isProtectionActive(lvl)) return;
        // World-wide lightning kill-switch — cancels every bolt without consulting regions.
        if (lvl instanceof ServerLevel sl) {
            var ws = mod.config().worldOrGlobal(sl);
            if (ws != null && ws.preventLightningFire) {
                e.setCanceled(true);
                return;
            }
        }
        RegionManager mgr = mod.regions().get(lvl);
        if (!mgr.testState(Flags.LIGHTNING, null, bolt.getX(), bolt.getY(), bolt.getZ())) {
            e.setCanceled(true);
        }
    }

    /* -------- mob effects (blocked-effects) -------- */

    /**
     * Suppress potion/status effects listed in a region's {@code blocked-effects}. Matches by
     * effect registry id or path (e.g. "minecraft:speed" or "speed").
     */
    @SubscribeEvent
    public void onEffectApplied(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable e) {
        LivingEntity victim = e.getEntity();
        Level lvl = victim.level();
        if (lvl.isClientSide()) return;
        if (!(victim instanceof ServerPlayer)) return;
        if (!mod.isProtectionActive(lvl)) return;
        RegionManager mgr = mod.regions().get(lvl);
        var blocked = mgr.resolveValue(Flags.BLOCKED_EFFECTS,
                victim.getX(), victim.getY(), victim.getZ(), victim.getUUID());
        if (blocked == null || blocked.isEmpty()) return;
        try {
            var holder = e.getEffectInstance().getEffect();
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getKey(holder.value());
            if (rl == null) return;
            String id   = rl.toString();
            String path = rl.getPath();
            if (blocked.contains(id) || blocked.contains(path)) {
                // Applicable uses its own Result enum (NOT TriState); DO_NOT_APPLY prevents it.
                e.setResult(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
            }
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("blocked-effects lookup failed", t);
        }
    }

    /* -------- crop / plant growth -------- */

    /**
     * Stops a sapling near a region border from growing a canopy into a foreign region (adjacency
     * grief). The event only carries the sapling position, so apply a conservative rule: if any
     * region within a small horizontal radius isn't the sapling's own, cancel growth.
     */
    @SubscribeEvent
    public void onTreeGrow(net.neoforged.neoforge.event.level.BlockGrowFeatureEvent e) {
        if (!(e.getLevel() instanceof ServerLevel lvl)) return;
        if (!mod.isProtectionActive(lvl)) return;
        BlockPos pos = e.getPos();
        if (pos == null) return;
        RegionManager mgr = mod.regions().get(lvl);
        // Fast path: a world with no regions can't have an adjacency-grief case → skip the scan.
        if (mgr.size() == 0) return;
        var baseRegions = mgr.getApplicable(pos.getX(), pos.getY(), pos.getZ());
        // Margin covering oak/birch/spruce/jungle canopy without being huge.
        final int R = 3;
        // One area query for the whole footprint instead of 48 per-cell lookups. Any region whose AABB
        // overlaps the footprint is a candidate; the precise per-cell contains() below runs only for
        // FOREIGN candidates (usually none), so the outcome is identical to the old cell-by-cell scan.
        var candidates = mgr.overlapping(
                new dev.thefather007.worldguardneo.util.Vec3(pos.getX() - R, pos.getY(), pos.getZ() - R),
                new dev.thefather007.worldguardneo.util.Vec3(pos.getX() + R, pos.getY(), pos.getZ() + R));
        for (ProtectedRegion r : candidates) {
            if (containsRegion(baseRegions, r.id())) continue; // the sapling's own region → fine
            for (int dx = -R; dx <= R; dx++) {
                for (int dz = -R; dz <= R; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (r.contains(pos.getX() + dx, pos.getY(), pos.getZ() + dz)) {
                        e.setCanceled(true); // a foreign region's canopy would grow into this spot
                        return;
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onCropGrow(net.neoforged.neoforge.event.level.block.CropGrowEvent.Pre e) {
        ServerLevel lvl = asServerLevel(e.getLevel());
        if (lvl == null) return;
        if (!mod.isProtectionActive(lvl)) return;
        RegionManager mgr = mod.regions().get(lvl);
        if (!test(mgr, Flags.CROP_GROWTH, e.getPos())) {
            // CropGrowEvent.Pre uses its own Result enum (NOT TriState); DO_NOT_GROW prevents it.
            e.setResult(net.neoforged.neoforge.event.level.block.CropGrowEvent.Pre.Result.DO_NOT_GROW);
        }
    }

    /* -------- notify-enter / notify-leave fan-out -------- */

    /**
     * Send a notification to every player holding {@code worldguardneo.notify}. Called from
     * {@link PlayerEventHandler} when {@code notify-enter}/{@code notify-leave} is crossed.
     */
    public void broadcastNotification(ServerPlayer mover, String regionId, boolean entered) {
        var server = mover.getServer();
        if (server == null) return;
        String key = entered ? "msg.notify.enter" : "msg.notify.leave";
        // Lazily build the message on the first permission holder — if nobody online holds the
        // node, we never format the string.
        Component msg = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (mod.perms().has(p, "worldguardneo.notify")) {
                if (msg == null) {
                    msg = Component.literal(mod.i18n().format(key,
                            "player", mover.getGameProfile().getName(),
                            "region", regionId,
                            "world",  mover.serverLevel().dimension().location().toString()));
                }
                p.displayClientMessage(msg, false);
            }
        }
    }
}
