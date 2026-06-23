package dev.thefather007.worldguardneo.api;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flag;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.flags.StateFlag;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public, stable API for other mods to query WorldGuardNeo. Static facade, no instance.
 *
 * <p>Null-safe: if WGN isn't loaded yet, methods return safe defaults (true / empty).
 * Signatures are stable across minor versions. Call only from the server thread — the
 * region caches are not thread-safe.
 *
 * @see dev.thefather007.worldguardneo.api.events.RegionEnterEvent
 * @see dev.thefather007.worldguardneo.api.events.RegionLeaveEvent
 * @see dev.thefather007.worldguardneo.api.events.RegionFlagDeniedEvent
 * @see dev.thefather007.worldguardneo.api.events.RegionModifyEvent
 */
public final class WorldGuardNeoAPI {

    private WorldGuardNeoAPI() {}

    /** Whether WorldGuardNeo is loaded and ready to answer queries. */
    public static boolean isAvailable() {
        return WorldGuardNeo.get() != null;
    }

    /* ====================== Region lookup ====================== */

    /**
     * Highest-priority region at the position, or empty for wilderness. The global region is
     * never returned (it's the implicit resolution fallback).
     */
    public static Optional<ProtectedRegion> getRegionAt(ServerLevel level, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return Optional.empty();
        RegionManager mgr = mod.regions().get(level);
        List<ProtectedRegion> applicable = mgr.getApplicable(pos.getX(), pos.getY(), pos.getZ());
        return applicable.isEmpty() ? Optional.empty() : Optional.of(applicable.get(0));
    }

    /** All regions covering the position, ordered by descending priority. */
    public static List<ProtectedRegion> getRegionsAt(ServerLevel level, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return List.of();
        return mod.regions().get(level).getApplicable(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Int-coords overload of {@link #getRegionAt(ServerLevel, BlockPos)} (script-friendly). */
    public static Optional<ProtectedRegion> getRegionAt(ServerLevel level, int x, int y, int z) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return Optional.empty();
        List<ProtectedRegion> applicable = mod.regions().get(level).getApplicable(x, y, z);
        return applicable.isEmpty() ? Optional.empty() : Optional.of(applicable.get(0));
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
     * Canonical "can the player break here" check: build + block-break flags, owner/member
     * status, bypass and the per-world kill switch. Mirrors what BlockEvent.BreakEvent enforces.
     */
    public static boolean canBuild(ServerPlayer player, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null || !mod.isProtectionActive(player.serverLevel())) return true;
        if (mod.perms().has(player, "worldguardneo.region.bypass")) return true;
        RegionManager mgr = mod.regions().get(player.serverLevel());
        UUID uid = player.getUUID();
        // testBuildAccess (not testState): honours implicit membership protection on claims with
        // no explicit flag, matching what the event handlers enforce.
        return mgr.testBuildAccess(Flags.BLOCK_BREAK, pos.getX(), pos.getY(), pos.getZ(), uid)
            && mgr.testBuildAccess(Flags.BUILD,       pos.getX(), pos.getY(), pos.getZ(), uid);
    }

    /** Like {@link #canBuild} but checks {@code block-place} instead of {@code block-break}. */
    public static boolean canPlace(ServerPlayer player, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null || !mod.isProtectionActive(player.serverLevel())) return true;
        if (mod.perms().has(player, "worldguardneo.region.bypass")) return true;
        RegionManager mgr = mod.regions().get(player.serverLevel());
        UUID uid = player.getUUID();
        // Mirrors EntityPlaceEvent handling — see canBuild for why testBuildAccess.
        return mgr.testBuildAccess(Flags.BLOCK_PLACE, pos.getX(), pos.getY(), pos.getZ(), uid)
            && mgr.testBuildAccess(Flags.BUILD,       pos.getX(), pos.getY(), pos.getZ(), uid);
    }

    /** Whether the player can right-click here (buttons, levers, doors…; see {@link #canAccessChests}). */
    public static boolean canInteract(ServerPlayer player, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null || !mod.isProtectionActive(player.serverLevel())) return true;
        if (mod.perms().has(player, "worldguardneo.region.bypass")) return true;
        RegionManager mgr = mod.regions().get(player.serverLevel());
        UUID uid = player.getUUID();
        // Mirrors RightClickBlock handling (INTERACT + USE, build-access semantics).
        return mgr.testBuildAccess(Flags.INTERACT, pos.getX(), pos.getY(), pos.getZ(), uid)
            && mgr.testBuildAccess(Flags.USE,      pos.getX(), pos.getY(), pos.getZ(), uid);
    }

    /** Whether the player can open chests/containers at the position. */
    public static boolean canAccessChests(ServerPlayer player, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null || !mod.isProtectionActive(player.serverLevel())) return true;
        if (mod.perms().has(player, "worldguardneo.region.bypass")) return true;
        RegionManager mgr = mod.regions().get(player.serverLevel());
        // Build-access semantics, same as the container branch of RightClickBlock.
        return mgr.testBuildAccess(Flags.CHEST_ACCESS,
                pos.getX(), pos.getY(), pos.getZ(), player.getUUID());
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
     * Generic state-flag query (priority+parent+group rules); {@code playerUuid} may be null.
     * Falls back to the flag's {@code defaultAllow} when unset at the position.
     */
    public static boolean queryFlag(ServerLevel level, StateFlag flag,
                                     @Nullable UUID playerUuid, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return flag.defaultAllow();
        RegionManager mgr = mod.regions().get(level);
        return mgr.testState(flag, playerUuid, pos.getX(), pos.getY(), pos.getZ());
    }

    /** Generic value-flag query (int/string/set); empty if unset at the position. */
    @SuppressWarnings("unchecked")
    public static <T> Optional<T> queryValue(ServerLevel level, Flag<T> flag,
                                              @Nullable UUID playerUuid, BlockPos pos) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return Optional.empty();
        RegionManager mgr = mod.regions().get(level);
        Object v = mgr.resolveValue(flag, pos.getX(), pos.getY(), pos.getZ(), playerUuid);
        return Optional.ofNullable((T) v);
    }

    /**
     * State-flag query by NAME (e.g. {@code "pvp"}) for scripting. Unknown/non-state names resolve
     * to {@code true} so a typo never hard-blocks an action; {@code playerUuid} may be null.
     */
    public static boolean queryFlag(ServerLevel level, String flagName,
                                    @Nullable UUID playerUuid, int x, int y, int z) {
        Flag<?> f = Flags.get(flagName);
        if (!(f instanceof StateFlag sf)) return true;
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return sf.defaultAllow();
        return mod.regions().get(level).testState(sf, playerUuid, x, y, z);
    }

    /** Value-flag query by NAME for scripting; empty if unset or unknown. */
    public static Optional<Object> queryValue(ServerLevel level, String flagName, int x, int y, int z) {
        Flag<?> f = Flags.get(flagName);
        if (f == null) return Optional.empty();
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return Optional.empty();
        return Optional.ofNullable(mod.regions().get(level).resolveValue(f, x, y, z, null));
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

    /** Does the player hold the global {@code worldguardneo.region.bypass} permission? */
    public static boolean hasBypass(ServerPlayer player) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return false;
        return mod.perms().has(player, "worldguardneo.region.bypass");
    }

    /* ====================== Custom flag registration ====================== */

    /**
     * Register a custom flag during mod init (before the server starts). Names are global and must
     * match {@code [a-z][a-z0-9-]*}; prefix with your mod id to avoid collisions. Once registered the
     * flag is first-class (settable via {@code /rg flag}, persisted). Idempotent: re-registering the
     * same name+type returns the existing instance, so it's safe in a {@code static final} field.
     *
     * @throws IllegalStateException if a flag with the same name but a DIFFERENT type exists
     */
    @SuppressWarnings("unchecked")
    public static <F extends Flag<?>> F registerFlag(F flag) {
        Flag<?> existing = Flags.get(flag.name());
        if (existing != null) {
            if (existing.getClass() == flag.getClass()) return (F) existing;
            throw new IllegalStateException("Flag '" + flag.name()
                    + "' is already registered with a different type (" + existing.getClass().getSimpleName() + ")");
        }
        Flags.register(flag);
        return flag;
    }
}
