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
 * <p>This class lives OUTSIDE the {@code dev.thefather007.worldguardneo.mixin} package on purpose.
 * Sponge Mixin treats every class inside a declared mixin package (see worldguardneo.mixins.json)
 * as a mixin and forbids referencing it directly at runtime — doing so throws
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced
 * directly}. Helper logic shared by the mixins must therefore sit in a normal package; the
 * mixins import it like any other class.
 *
 * Every helper short-circuits to {@code true} (allow) if:
 * <ul>
 *   <li>{@link WorldGuardNeo#get()} returns null (mod not yet booted),</li>
 *   <li>the level isn't a {@link ServerLevel} (client side, or worldgen contexts
 *       where there's no live region manager),</li>
 *   <li>or the position is outside any region.</li>
 * </ul>
 * This is critical for performance: random ticks fire ~60×/sec per loaded chunk,
 * so the fast path must allocate zero objects and do at most one map lookup.
 */
public final class MixinFlagBridge {

    private MixinFlagBridge() {}

    /**
     * Returns true if the given state flag permits the action at {@code pos}.
     * Use a static reference so HotSpot can inline it across mixin call sites.
     */
    public static boolean check(Level level, BlockPos pos, StateFlag flag) {
        // Random ticks happen on the server logical thread. Anything else (client-side
        // copies, worldgen with a level accessor) must not block — return permissive.
        if (!(level instanceof ServerLevel sl)) return true;

        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return true; // pre-init or shutdown

        try {
            // Per-world useRegions kill-switch — the listeners and the other mixins all honour
            // it; without this check the nine random-tick flags kept being enforced in worlds
            // where the admin disabled regions entirely. Cheap: a cached IdentityHashMap probe.
            if (!mod.isProtectionActive(sl)) return true;
            RegionManager mgr = mod.regions().get(sl);
            // Allocation-free fast path: if there's no region AABB at this position, only the
            // GLOBAL region can override the flag. Resolve directly against globalRegion
            // without going through getApplicable() (which would allocate). This is the case
            // for ~99% of random ticks on a typical server (most chunks are in wilderness).
            if (!mgr.hasAnyAt(pos.getX(), pos.getY(), pos.getZ())) {
                return flag.test(mgr.globalRegion().getFlag(flag));
            }
            return mgr.testState(flag, null, pos.getX(), pos.getY(), pos.getZ());
        } catch (Throwable t) {
            // Never let a mixin path crash the chunk tick. Log at debug only because
            // these handlers run extremely often.
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
