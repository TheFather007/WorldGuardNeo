package dev.thefather007.worldguardneo.mixinsupport;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.flags.StateFlag;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Static helpers called from {@code @Inject} mixins on vanilla blocks.
 *
 * <p>Lives OUTSIDE the {@code ...worldguardneo.mixin} package on purpose: Sponge Mixin treats every
 * class in a declared mixin package as a mixin and throws {@code IllegalClassLoadError} if one is
 * referenced directly at runtime, so shared helper logic must sit in a normal package.
 *
 * <p>Every helper fails open (returns true/allow) on pre-boot, non-{@link ServerLevel}, or
 * out-of-region. Critical for performance: random ticks fire ~60x/sec per loaded chunk, so the
 * fast path allocates zero objects and does at most one map lookup.
 */
public final class MixinFlagBridge {

    private MixinFlagBridge() {}

    /** True if the given state flag permits the action at {@code pos}. Static so HotSpot inlines it. */
    public static boolean check(Level level, BlockPos pos, StateFlag flag) {
        // Client-side copies / worldgen accessors must not block — return permissive.
        if (!(level instanceof ServerLevel sl)) return true;

        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return true; // pre-init or shutdown

        try {
            // Per-world useRegions kill-switch — without it the random-tick flags stayed enforced
            // in worlds where the admin disabled regions. Cheap cached IdentityHashMap probe.
            if (!mod.isProtectionActive(sl)) return true;
            RegionManager mgr = mod.regions().get(sl);
            // Fast path (~99% of random ticks, wilderness): no region AABB here → resolve directly
            // against globalRegion, skipping the allocation that getApplicable() would do.
            if (!mgr.hasAnyAt(pos.getX(), pos.getY(), pos.getZ())) {
                return flag.test(mgr.globalRegion().getFlag(flag));
            }
            return mgr.testState(flag, null, pos.getX(), pos.getY(), pos.getZ());
        } catch (Throwable t) {
            // Never crash the chunk tick; debug-only since these handlers run extremely often.
            WorldGuardNeo.LOGGER.debug("Mixin flag check failed for {}", flag.name(), t);
            return true;
        }
    }

    /* ---- Per-flag conveniences. Inlined call sites reduce mixin verbosity. ---- */

    /**
     * Whether the given player may RECEIVE chat at their current position — false when standing in
     * a region whose {@code receive-chat} flag denies it. Resolved with the player as the actor so
     * group filters work. Fail-open (allow) on any error / pre-init / non-server level.
     */
    public static boolean receiveChat(net.minecraft.server.level.ServerPlayer player) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return true;
        try {
            if (!(player.level() instanceof ServerLevel sl)) return true;
            if (!mod.isProtectionActive(sl)) return true;
            RegionManager mgr = mod.regions().get(sl);
            return mgr.testState(Flags.RECEIVE_CHAT, player.getUUID(),
                    player.getX(), player.getY(), player.getZ());
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("receiveChat check failed", t);
            return true;
        }
    }

    public static boolean iceForm(Level lvl, BlockPos p)        { return check(lvl, p, Flags.ICE_FORM); }
    public static boolean iceMelt(Level lvl, BlockPos p)        { return check(lvl, p, Flags.ICE_MELT); }
    public static boolean frostedIceMelt(Level lvl, BlockPos p) { return check(lvl, p, Flags.FROSTED_ICE_MELT); }
    public static boolean snowFall(Level lvl, BlockPos p)       { return check(lvl, p, Flags.SNOW_FALL); }
    public static boolean snowMelt(Level lvl, BlockPos p)       { return check(lvl, p, Flags.SNOW_MELT); }
    public static boolean grassSpread(Level lvl, BlockPos p)    { return check(lvl, p, Flags.GRASS_SPREAD); }
    public static boolean myceliumSpread(Level lvl, BlockPos p) { return check(lvl, p, Flags.MYCELIUM_SPREAD); }
    public static boolean vineGrowth(Level lvl, BlockPos p)     { return check(lvl, p, Flags.VINE_GROWTH); }
    public static boolean leafDecay(Level lvl, BlockPos p)      { return check(lvl, p, Flags.LEAF_DECAY); }
}
