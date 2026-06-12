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
 * Environment & misc protections that didn't fit in BlockEventHandler or PlayerEventHandler.
 *
 * Wired flags here:
 * <ul>
 *   <li>{@code fire-spread}      — via {@link BlockEvent.NeighborNotifyEvent} when source is fire</li>
 *   <li>{@code lava-flow}        — via {@link BlockEvent.FluidPlaceBlockEvent} when source is lava</li>
 *   <li>{@code water-flow}       — same, when source is water</li>
 *   <li>{@code lava-fire}        — via {@link BlockEvent.NeighborNotifyEvent} when source is lava</li>
 *   <li>{@code exp-drops}        — via {@link LivingExperienceDropEvent}</li>
 *   <li>{@code lightning}        — via {@code EntityJoinLevelEvent} filtered to {@code LightningBolt}</li>
 *   <li>{@code blocked-effects}  — via {@code MobEffectEvent.Applicable}</li>
 *   <li>{@code crop-growth}      — via {@code CropGrowEvent.Pre}</li>
 *   <li>{@code notify-enter/leave} — fan-out helper, called by {@link PlayerEventHandler}</li>
 * </ul>
 *
 * <p>Flags that depend on random-tick internals (ice-form, ice-melt, snow-fall, snow-melt,
 * frosted-ice-melt, grass-spread, mycelium-spread, vine-growth, leaf-decay) are intentionally
 * NOT wired. Random ticks bypass all public NeoForge events; intercepting them requires
 * either a mixin / coremod or per-block override registration via {@code RegisterEvent}.
 * They remain editable via {@code /rg flag} so a compatibility add-on can later enforce them
 * without changing the storage format. The same applies to {@code receive-chat} (no public
 * packet-receive hook) and {@code allowed-enchants} (would require unequip-on-equip semantics).
 */
public final class WorldEventHandler {

    private final WorldGuardNeo mod;
    public WorldEventHandler(WorldGuardNeo mod) { this.mod = mod; }

    private static ServerLevel asServerLevel(Object levelAccessor) {
        return (levelAccessor instanceof ServerLevel sl) ? sl : null;
    }

    /**
     * Helper for state-flag probes at a block position with no actor (null UUID — used for
     * world-driven events). Adds a {@code hasAnyAt} fast-path so wilderness positions resolve
     * without going through the full priority+group cascade, which is critical for hot paths
     * like crop growth, redstone notify, and random ticks.
     */
    private boolean test(RegionManager mgr, StateFlag flag, BlockPos bp) {
        int x = bp.getX(), y = bp.getY(), z = bp.getZ();
        // Allocation-free fast path: if no region AABB touches the point AND the global region
        // has no opinion on this flag, vanilla default applies. Returns true (allow) for the
        // common wilderness case without any spatial-resolution machinery.
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
        // FluidPlaceBlockEvent fires when a flowing fluid converts to a solid block:
        //   water + lava → cobblestone / stone / obsidian / basalt
        // We can't read the *source* fluid type from the event payload reliably, so we infer
        // from the resulting block: obsidian/basalt → lava was involved (LAVA_FLOW); anything
        // else (cobblestone/stone) → water-flow. Both flags can disable creation entirely.
        BlockState newState = e.getNewState();
        boolean lavaInvolved = newState.is(Blocks.OBSIDIAN)
                || newState.is(Blocks.BASALT)
                || newState.is(Blocks.STONE);
        StateFlag flag = lavaInvolved ? Flags.LAVA_FLOW : Flags.WATER_FLOW;
        if (!test(mgr, flag, e.getPos())) e.setCanceled(true);
    }

    /* -------- redstone propagation -------- */

    /**
     * Cancels redstone signal propagation in regions where {@code redstone = DENY}.
     *
     * <p>This piggybacks on {@link BlockEvent.NeighborNotifyEvent} which is the same event
     * used for fire/lava. We filter aggressively:
     * <ol>
     *   <li>{@code state.isSignalSource()} — 99% of block changes (placing dirt, breaking
     *     grass) exit here in 1 method call.</li>
     *   <li>{@code mgr.hasAnyAt(...)} — when there's no region at the source position the
     *     global flag default (allow) wins; no need to do the full {@code testState} walk.</li>
     * </ol>
     *
     * <p>Performance note: redstone clocks can fire this thousands of times/sec. The two-stage
     * filter keeps per-event cost to ~1 hash lookup unless a region with REDSTONE=deny is
     * actually involved.
     */
    /**
     * Stops pistons from moving blocks ACROSS a region boundary — both pushing blocks INTO a
     * region from outside (задача 4: griefing by shoving blocks in) and pulling blocks OUT of a
     * region with a sticky piston (задача 5: stealing/destroying claimed blocks).
     *
     * <p>Approach: gather every position the piston operation touches — the piston itself, all
     * blocks it will push (and their destinations one step along the push direction), and any
     * blocks it will destroy. We then compute the set of regions covering each touched position.
     * If those region-sets are not all identical — i.e. the operation straddles a protection
     * boundary — we cancel. This single rule covers push-in, pull-out, and partial overlaps
     * without needing to know who powered the piston (pistons aren't players, so there's no
     * actor to permission-check; the boundary itself is the protection).
     *
     * <p>Fully outside any region (wilderness on both sides) → allowed, normal redstone builds
     * are unaffected. Fully inside one region → allowed, owners' own machines work.
     */
    @SubscribeEvent
    public void onPistonPre(net.neoforged.neoforge.event.level.PistonEvent.Pre e) {
        ServerLevel lvl = asServerLevel(e.getLevel());
        if (lvl == null) return;
        if (!mod.isProtectionActive(lvl)) return;
        RegionManager mgr = mod.regions().get(lvl);
        net.minecraft.core.Direction dir = e.getDirection();
        BlockPos piston = e.getPos();

        // Regions the piston's own cell belongs to.
        var pistonRegions = mgr.getApplicable(piston.getX(), piston.getY(), piston.getZ());

        // Collect every cell this move physically affects.
        java.util.List<BlockPos> touched = new java.util.ArrayList<>();
        touched.add(e.getFaceOffsetPos());
        var helper = e.getStructureHelper();
        if (helper != null && helper.resolve()) {
            for (BlockPos p : helper.getToPush()) {
                touched.add(p);
                touched.add(p.relative(dir));
            }
            touched.addAll(helper.getToDestroy());
        } else {
            touched.add(e.getFaceOffsetPos().relative(dir));
        }

        // Find the first touched cell that lies in a region the piston isn't allowed to affect.
        // A region is "off-limits" when:
        //   1. the piston's cell is NOT part of it (it's a foreign/adjacent region), AND
        //   2. that region's PISTONS flag does not allow the move.
        // The piston's OWN region(s) are always fine — that's the owner's own machinery.
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

        // Cross-border move into a protected foreign region. Per the owner's preference, instead
        // of silently cancelling (which left the piston visually stuck extended), we BREAK the
        // offending piston and drop it as an item. Done next tick via the server executor so we
        // never mutate block state from inside the piston event itself (mutating here previously
        // caused a re-fire → infinite loop → watchdog crash). destroyBlock(...) does not fire
        // PistonEvent, and deferring guarantees we're outside the event dispatch entirely.
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

    /** Resolve a region's PISTONS flag (walking parents); default ALLOW when unset. */
    private static boolean resolvePistonsAllow(ProtectedRegion r) {
        StateFlag.State s = null;
        ProtectedRegion cur = r;
        int hops = 0;
        while (cur != null && hops++ < 32) {
            s = cur.getFlag(Flags.PISTONS);
            if (s != null) break;
            cur = cur.parent();
        }
        return s != StateFlag.State.DENY; // unset or ALLOW → allowed; only explicit DENY blocks
    }

    /**
     * Single subscriber for {@link BlockEvent.NeighborNotifyEvent} covering BOTH concerns that
     * piggyback on it (redstone gating + fire/lava/water propagation).
     *
     * <p>Performance: NeighborNotify is the hottest block event on a server — every block
     * update fires it. These used to be two separate {@code @SubscribeEvent} methods, which
     * doubled event-bus dispatch and re-ran the level/kill-switch/state filters twice per
     * event. One subscriber shares those filters and exits in a couple of comparisons for
     * the vast majority of traffic (state is neither a signal source nor fire/lava/water).
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
            // test() fast-paths the wilderness case (single hasAnyAt probe) while still
            // honouring a REDSTONE flag set on the GLOBAL region.
            if (!test(mgr, Flags.REDSTONE, e.getPos())) {
                e.setCanceled(true);
            }
            return; // signal sources are never fire/lava/water — branches are disjoint
        }

        // ---- fire / lava / water propagation branch ----
        boolean isFire = state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
        boolean isLava = state.is(Blocks.LAVA);
        boolean isWater = state.is(Blocks.WATER);
        // Fast pre-filter: only fire, lava, or water propagation is of interest here. Everything
        // else (the vast majority of neighbour-notify traffic) exits in one call.
        if (!isFire && !isLava && !isWater) return;

        RegionManager mgr = mod.regions().get(lvl);
        BlockPos src = e.getPos();

        // World-wide kill-switches FIRST — they are documented as world-wide, so they must apply
        // in wilderness too. (They used to sit below the nearRegion bail, which made
        // prevent-fire-spread silently ineffective away from claims.) worldOrGlobal is a cached
        // per-Level lookup, so this costs one IdentityHashMap probe per event.
        var ws = mod.config().worldOrGlobal(lvl);
        boolean killFire = ws != null && isFire && ws.preventFireSpread;
        boolean killLava = ws != null && isLava && ws.preventLavaFire;
        if (killFire || killLava) { e.setCanceled(true); return; }

        StateFlag flag = isFire ? Flags.FIRE_SPREAD : (isLava ? Flags.LAVA_FIRE : null);

        // Global-region flag (fire/lava only): applies everywhere, including wilderness, so it
        // must be honoured before the nearRegion bail below.
        if (flag != null) {
            StateFlag.State g = mgr.globalRegion().getFlag(flag);
            if (g == StateFlag.State.DENY) { e.setCanceled(true); return; }
        }

        // Performance: water/lava neighbour-notifies fire constantly (every ocean, river, flow).
        // Before doing ANY set allocation, cheaply check whether a region is even near the source.
        // hasAnyAt is a single spatial-index probe. If neither the source nor any notified side is
        // inside a region, there's no boundary to enforce and no per-region flag to check → bail.
        boolean nearRegion = mgr.hasAnyAt(src.getX(), src.getY(), src.getZ());
        if (!nearRegion) {
            for (net.minecraft.core.Direction d : e.getNotifiedSides()) {
                BlockPos t = src.relative(d);
                if (mgr.hasAnyAt(t.getX(), t.getY(), t.getZ())) { nearRegion = true; break; }
            }
        }
        if (!nearRegion) return; // wilderness fluid/fire far from any claim → vanilla, zero cost

        var srcRegions = mgr.getApplicable(src.getX(), src.getY(), src.getZ());

        // Single pass over notified sides doing BOTH checks:
        //  (1) cross-border containment — a fluid/fire block in zone X must not propagate into a
        //      neighbouring cell belonging to a region X isn't part of (stops lava/water/fire from
        //      region A or wilderness crossing into adjacent region B; symmetric, no owner check);
        //  (2) per-region fire-spread / lava-fire flag at the target (fire & lava only; water has
        //      no such flag and relies solely on the boundary rule).
        // We avoid allocating a Set per side: walk the target's applicable list directly.
        for (net.minecraft.core.Direction dir : e.getNotifiedSides()) {
            BlockPos target = src.relative(dir);
            var targetRegions = mgr.getApplicable(target.getX(), target.getY(), target.getZ());
            // (1) boundary: any region at the target the source isn't part of → block.
            for (int i = 0, n = targetRegions.size(); i < n; i++) {
                if (!containsRegion(srcRegions, targetRegions.get(i).id())) {
                    e.setCanceled(true);
                    return;
                }
            }
            // (2) per-region flag at target (fire/lava only).
            if (flag != null
                    && !mgr.testState(flag, null, target.getX(), target.getY(), target.getZ())) {
                e.setCanceled(true);
                return;
            }
        }
    }

    /* -------- experience drops -------- */

    @SubscribeEvent
    public void onExpDrop(LivingExperienceDropEvent e) {
        LivingEntity victim = e.getEntity();
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
     * Cancel lightning bolts that would land in a region forbidding them.
     * Catches both natural strikes and trident/channeling-induced ones; the in-game effect
     * is that the bolt simply never spawns. Falls back to the source position when the bolt
     * has no level yet (very rare race with mod-summoned lightning).
     */
    @SubscribeEvent
    public void onLightningSpawn(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (!(e.getEntity() instanceof net.minecraft.world.entity.LightningBolt bolt)) return;
        Level lvl = e.getLevel();
        if (lvl.isClientSide()) return;
        if (!mod.isProtectionActive(lvl)) return;
        // World-wide lightning kill-switch — cancels every bolt in this world without
        // even consulting the region index.
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
     * Suppress potion / status effects inside regions that list them in {@code blocked-effects}.
     * The set value matches by effect's registry path (e.g. "speed", "invisibility").
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
            String id   = rl.toString();        // e.g. "minecraft:speed"
            String path = rl.getPath();         // e.g. "speed"
            if (blocked.contains(id) || blocked.contains(path)) {
                // MobEffectEvent.Applicable uses its OWN nested Result enum (NOT TriState):
                //   APPLY = force apply, DEFAULT = vanilla canBeAffected() check,
                //   DO_NOT_APPLY = prevent the effect. We want DO_NOT_APPLY.
                e.setResult(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
            }
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("blocked-effects lookup failed", t);
        }
    }

    /* -------- crop / plant growth -------- */

    /**
     * Stops a sapling/feature in one zone from growing into a tree whose canopy would intrude on
     * an adjacent foreign region. Adjacency grief: plant a sapling right against the shared border
     * of region B; when it grows, its trunk/leaves overwrite blocks inside B. We can't know the
     * exact canopy cells in advance (the event only carries the sapling position), so we apply a
     * conservative rule: if any region overlapping a small horizontal radius around the sapling is
     * one the sapling's own cell does NOT belong to, cancel the growth. A tree fully inside its
     * own region (or fully in wilderness) grows normally.
     */
    @SubscribeEvent
    public void onTreeGrow(net.neoforged.neoforge.event.level.BlockGrowFeatureEvent e) {
        if (!(e.getLevel() instanceof ServerLevel lvl)) return;
        if (!mod.isProtectionActive(lvl)) return;
        BlockPos pos = e.getPos();
        if (pos == null) return;
        RegionManager mgr = mod.regions().get(lvl);
        var baseRegions = mgr.getApplicable(pos.getX(), pos.getY(), pos.getZ());
        // Typical large-tree canopy reaches a few blocks out horizontally. 3 is a safe margin that
        // covers oak/birch/spruce/jungle without being huge.
        final int R = 3;
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = pos.getX() + dx, ny = pos.getY(), nz = pos.getZ() + dz;
                // Avoid allocating a Set per cell: walk the applicable list directly and look for
                // any region the sapling's own cell isn't part of.
                for (ProtectedRegion r : mgr.getApplicable(nx, ny, nz)) {
                    if (!containsRegion(baseRegions, r.id())) {
                        // BlockGrowFeatureEvent is an ICancellableEvent — cancelling prevents the
                        // tree feature from generating.
                        e.setCanceled(true);
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
            // CropGrowEvent.Pre uses its OWN nested Result enum (NOT TriState):
            //   GROW = force growth, DEFAULT = vanilla checks, DO_NOT_GROW = prevent growth.
            e.setResult(net.neoforged.neoforge.event.level.block.CropGrowEvent.Pre.Result.DO_NOT_GROW);
        }
    }

    /* -------- notify-enter / notify-leave fan-out -------- */

    /**
     * Send a notification to every player holding {@code worldguardneo.notify}.
     * Called from {@link PlayerEventHandler} when {@code notify-enter}/{@code notify-leave}
     * is set on a region the player crosses.
     */
    public void broadcastNotification(ServerPlayer mover, String regionId, boolean entered) {
        var server = mover.getServer();
        if (server == null) return;
        String key = entered ? "msg.notify.enter" : "msg.notify.leave";
        Component msg = Component.literal(mod.i18n().format(key,
                "player", mover.getGameProfile().getName(),
                "region", regionId,
                "world",  mover.serverLevel().dimension().location().toString()));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (mod.perms().has(p, "worldguardneo.notify")) {
                p.displayClientMessage(msg, false);
            }
        }
    }
}
