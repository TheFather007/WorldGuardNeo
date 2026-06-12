package dev.dizzy.worldguardneo.api;

import dev.dizzy.worldguardneo.WorldGuardNeo;
import dev.dizzy.worldguardneo.flags.Flag;
import dev.dizzy.worldguardneo.flags.Flags;
import dev.dizzy.worldguardneo.flags.StateFlag;
import dev.dizzy.worldguardneo.region.ProtectedRegion;
import dev.dizzy.worldguardneo.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public, stable API for other mods to query and interact with WorldGuardNeo.
 *
 * <p>This is a static facade — no instance to construct. Methods are null-safe; if
 * WorldGuardNeo isn't fully loaded yet (e.g. mod init order issue), they return safe
 * defaults (true / empty / Optional.empty).
 *
 * <p>Method stability guarantee: methods declared here will not have their signatures
 * changed between minor versions. New methods may be added. Existing methods may have
 * their behavior refined, with breaking changes only in major version bumps.
 *
 * <p>Thread safety: ALL methods must be called from the server thread. The underlying
 * region storage uses identity-mapped caches that aren't thread-safe.
 *
 * <h2>Common use cases</h2>
 * <pre>{@code
 * // Check if a player can build at a position:
 * if (!WorldGuardNeoAPI.canBuild(player, pos)) {
 *     return; // skip the action
 * }
 *
 * // Find what region a player is standing in:
 * Optional<ProtectedRegion> r = WorldGuardNeoAPI.getRegionAt(level, player.blockPosition());
 *
 * // List all regions owned by a player:
 * List<ProtectedRegion> owned = WorldGuardNeoAPI.getOwnedRegions(level, player.getUUID());
 * }</pre>
 *
 * @see dev.dizzy.worldguardneo.api.events.RegionEnterEvent
 * @see dev.dizzy.worldguardneo.api.events.RegionLeaveEvent
 * @see dev.dizzy.worldguardneo.api.events.RegionFlagDeniedEvent
 * @see dev.dizzy.worldguardneo.api.events.RegionModifyEvent
 */
public final class WorldGuardNeoAPI {

    private WorldGuardNeoAPI() {}

    /**
     * Returns whether WorldGuardNeo is loaded and ready to answer queries. Always check
     * this before relying on API output if your mod loads earlier than WGN.
     */
    public static boolean isAvailable() {
        return WorldGuardNeo.get() != null;
    }

    /* ====================== Region lookup ====================== */

    /**
     * Get the highest-priority region at the given position. Returns {@code Optional.empty()}
     * if the position is in wilderness (no user-defined region covers it). The global region
     * is never returned here — it's the implicit fallback in resolution, accessed via
     * {@code regions().get(level).globalRegion()} if needed.
     */
    public static Optional<ProtectedRegion> getRegionAt(ServerLevel level, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return Optional.empty();
        RegionManager mgr = mod.regions().get(level);
        List<ProtectedRegion> applicable = mgr.getApplicable(pos.getX(), pos.getY(), pos.getZ());
        return applicable.isEmpty() ? Optional.empty() : Optional.of(applicable.get(0));
    }

    /**
     * Get ALL regions covering the given position, ordered by descending priority.
     * Useful when you need to walk through every layer (e.g. for compound queries).
     */
    public static List<ProtectedRegion> getRegionsAt(ServerLevel level, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return List.of();
        return mod.regions().get(level).getApplicable(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Get a region by id. */
    public static Optional<ProtectedRegion> getRegion(ServerLevel level, String id) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return Optional.empty();
        return mod.regions().get(level).get(id);
    }

    /** Get all regions in a world. */
    public static Collection<ProtectedRegion> getRegions(ServerLevel level) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return List.of();
        return mod.regions().get(level).all();
    }

    /** Get all regions owned by a player in a world. */
    public static List<ProtectedRegion> getOwnedRegions(ServerLevel level, UUID playerUuid) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return List.of();
        return mod.regions().get(level).getOwnedBy(playerUuid);
    }

    /* ====================== Permission queries ====================== */

    /**
     * Whether the player can build (break or place) at the given position.
     *
     * <p>This is the canonical "can the player modify the world here" check. It takes
     * into account: build flag, block-break flag, owner/member status, bypass permissions,
     * and the world-level useRegions kill switch.
     *
     * <p>Equivalent to what {@code BlockEvent.BreakEvent} listeners check internally —
     * checks BOTH the general {@code build} flag and the specific {@code block-break} flag.
     * Both must permit the action.
     */
    public static boolean canBuild(ServerPlayer player, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null || !mod.isProtectionActive(player.serverLevel())) return true;
        if (mod.perms().has(player, "worldguardneo.region.bypass")) return true;
        RegionManager mgr = mod.regions().get(player.serverLevel());
        // Get applicable once and test both flags against it — avoids two spatial lookups.
        List<ProtectedRegion> applicable = mgr.getApplicable(pos.getX(), pos.getY(), pos.getZ());
        UUID uid = player.getUUID();
        return mgr.testState(Flags.BLOCK_BREAK, applicable, uid)
            && mgr.testState(Flags.BUILD,       applicable, uid);
    }

    /**
     * Whether the player can place a block at the position. Like {@link #canBuild} but
     * checks the specific {@code block-place} flag instead of {@code block-break}.
     */
    public static boolean canPlace(ServerPlayer player, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null || !mod.isProtectionActive(player.serverLevel())) return true;
        if (mod.perms().has(player, "worldguardneo.region.bypass")) return true;
        RegionManager mgr = mod.regions().get(player.serverLevel());
        List<ProtectedRegion> applicable = mgr.getApplicable(pos.getX(), pos.getY(), pos.getZ());
        UUID uid = player.getUUID();
        return mgr.testState(Flags.BLOCK_PLACE, applicable, uid)
            && mgr.testState(Flags.BUILD,       applicable, uid);
    }

    /**
     * Whether the player can interact (right-click) at the position. Covers buttons,
     * levers, doors, signs, chests (also see {@link #canAccessChests}).
     */
    public static boolean canInteract(ServerPlayer player, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null || !mod.isProtectionActive(player.serverLevel())) return true;
        if (mod.perms().has(player, "worldguardneo.region.bypass")) return true;
        RegionManager mgr = mod.regions().get(player.serverLevel());
        return mgr.testState(Flags.INTERACT, player.getUUID(), pos.getX(), pos.getY(), pos.getZ());
    }

    /** Whether the player can open chests/containers at the position. */
    public static boolean canAccessChests(ServerPlayer player, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null || !mod.isProtectionActive(player.serverLevel())) return true;
        if (mod.perms().has(player, "worldguardneo.region.bypass")) return true;
        RegionManager mgr = mod.regions().get(player.serverLevel());
        return mgr.testState(Flags.CHEST_ACCESS, player.getUUID(), pos.getX(), pos.getY(), pos.getZ());
    }

    /** Whether PvP is allowed at the position for this attacker. */
    public static boolean canPvP(ServerPlayer attacker, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null || !mod.isProtectionActive(attacker.serverLevel())) return true;
        if (mod.perms().has(attacker, "worldguardneo.region.bypass")) return true;
        RegionManager mgr = mod.regions().get(attacker.serverLevel());
        return mgr.testState(Flags.PVP, attacker.getUUID(), pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Generic state-flag query — for custom or non-listed flags. Returns the resolved
     * boolean according to priority+parent+owner-group rules. {@code playerUuid} may be
     * null for non-player contexts.
     *
     * <p>If the flag has no setting on any region at the position, the flag's
     * {@code defaultAllow} value is returned.
     */
    public static boolean queryFlag(ServerLevel level, StateFlag flag,
                                     @Nullable UUID playerUuid, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return flag.defaultAllow();
        RegionManager mgr = mod.regions().get(level);
        return mgr.testState(flag, playerUuid, pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Generic value-flag query for any flag type (int, string, set). Returns Optional.empty()
     * if no region at the position has the flag set.
     */
    @SuppressWarnings("unchecked")
    public static <T> Optional<T> queryValue(ServerLevel level, Flag<T> flag,
                                              @Nullable UUID playerUuid, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return Optional.empty();
        RegionManager mgr = mod.regions().get(level);
        // resolveValue takes coords first, actor last
        Object v = mgr.resolveValue(flag, pos.getX(), pos.getY(), pos.getZ(), playerUuid);
        return Optional.ofNullable((T) v);
    }

    /* ====================== Ownership checks ====================== */

    /** Is this UUID an owner of the region? */
    public static boolean isOwner(ProtectedRegion region, UUID playerUuid) {
        return region.isOwner(playerUuid);
    }

    /** Is this UUID an owner OR member of the region? */
    public static boolean isMember(ProtectedRegion region, UUID playerUuid) {
        return region.isMember(playerUuid);
    }

    /**
     * Does the player hold the global {@code worldguardneo.region.bypass} permission?
     * Useful for mods that want to skip their own protections when the player is
     * a WGN admin.
     */
    public static boolean hasBypass(ServerPlayer player) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return false;
        return mod.perms().has(player, "worldguardneo.region.bypass");
    }

    /* ====================== Custom flag registration ====================== */

    /**
     * Register a custom flag from your mod. Should be called during mod initialization,
     * BEFORE the server starts. Flag identifiers are global — pick something namespaced
     * (e.g. {@code "mymod.feature"}) to avoid collisions.
     *
     * <p>Returns the registered flag instance — keep a reference to it for later
     * {@code queryFlag()} / {@code queryValue()} calls.
     *
     * @throws IllegalStateException if a flag with the same name is already registered
     */
    public static <F extends Flag<?>> F registerFlag(F flag) {
        Flags.register(flag);
        return flag;
    }
}
