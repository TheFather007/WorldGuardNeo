package dev.dizzy.worldguardneo.region;

import dev.dizzy.worldguardneo.WorldGuardNeo;
import dev.dizzy.worldguardneo.storage.RegionStorage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Maps world key (e.g. {@code "minecraft:overworld"}) → its RegionManager.
 *
 * Two indexes are kept:
 *   - {@link #managers} keyed by stringified dimension id, for save/load and command paths.
 *   - {@link #byLevel} keyed by the Level instance, for the hot lookup path. Levels are
 *     stable objects for the lifetime of the server, so identity hashing is correct and
 *     avoids allocating a fresh {@code "namespace:path"} string per event.
 */
public final class RegionContainer {

    private final RegionStorage storage;
    private final Map<String, RegionManager> managers = new HashMap<>();
    private final IdentityHashMap<Level, RegionManager> byLevel = new IdentityHashMap<>();

    /**
     * Per-world "save needed" flag. The save() method just marks the world dirty;
     * {@link #flushDirty} actually performs the disk write. Called from the server-tick
     * hook every few seconds, this coalesces dozens of rapid edits (e.g. an admin running
     * a batch of /rg flag commands) into a single I/O operation.
     */
    private final java.util.Set<String> dirty = new java.util.HashSet<>();
    /** Tick of last flush, in overworld ticks. */
    private long lastFlushTick;
    /** Minimum tick gap between flushes. 100 ticks = 5 seconds. */
    private static final long FLUSH_INTERVAL_TICKS = 100L;

    public RegionContainer(RegionStorage storage) { this.storage = storage; }

    public int size() { return managers.size(); }

    /** Read-only view of all world→manager mappings. Used by startup diagnostics. */
    public java.util.Map<String, RegionManager> allManagers() {
        return java.util.Collections.unmodifiableMap(managers);
    }

    /**
     * Total number of regions OWNED by a player across ALL dimensions. Region limits must be
     * global, not per-world — otherwise a player could claim the full quota in the Overworld,
     * then again in the Nether, the End, and every modded dimension, multiplying their real
     * limit by the dimension count. {@code RegionManager.countOwned} only sees one world, so
     * the claim path must use THIS method for the limit check.
     */
    public int countOwnedGlobal(java.util.UUID player) {
        int n = 0;
        for (RegionManager m : managers.values()) n += m.countOwned(player);
        return n;
    }

    public RegionManager get(String worldKey) {
        return managers.computeIfAbsent(worldKey, RegionManager::new);
    }

    /** Hot path: 1 IdentityHashMap.get() — no string allocation. */
    public RegionManager get(Level level) {
        RegionManager m = byLevel.get(level);
        if (m != null) return m;
        ResourceLocation rl = level.dimension().location();
        m = get(rl.toString());
        byLevel.put(level, m);
        return m;
    }

    public void loadAllForServer(MinecraftServer server) {
        for (ServerLevel sl : server.getAllLevels()) {
            String key = sl.dimension().location().toString();
            RegionManager m = new RegionManager(key);
            try {
                storage.load(key, m);
            } catch (Exception ex) {
                WorldGuardNeo.LOGGER.error("Failed to load regions for {} — starting empty", key, ex);
                // m is left empty; continue so the world is still usable.
            }
            managers.put(key, m);
            byLevel.put(sl, m);
        }
    }

    public void saveAll() {
        // Immediate, synchronous full save — used by /rg save and on server stop.
        // Drains the dirty set since nothing more can be pending.
        for (RegionManager m : managers.values()) {
            try { storage.save(m.world(), m); }
            catch (Exception ex) { WorldGuardNeo.LOGGER.error("Failed to save regions for {}", m.world(), ex); }
        }
        dirty.clear();
    }

    /**
     * Mark a world as dirty so the next {@link #flushDirty} writes it.
     * Returns immediately — no I/O on the hot path. This is the API every
     * /rg command should call after mutating a region.
     */
    public void save(String worldKey) {
        if (managers.containsKey(worldKey)) dirty.add(worldKey);
    }

    /** Convenience: mark the given level's world dirty. */
    public void save(Level level) {
        RegionManager m = byLevel.get(level);
        if (m == null) m = get(level);
        dirty.add(m.world());
    }

    /**
     * Called from the server tick. Writes pending dirty worlds to disk if enough ticks
     * have elapsed since the last flush. The 5-second interval is a good trade-off:
     * frequent enough that crashes only lose a few seconds of edits; rare enough that
     * batch operations don't hammer the disk.
     */
    public void flushDirty(long currentTick) {
        if (dirty.isEmpty()) return;
        if (currentTick - lastFlushTick < FLUSH_INTERVAL_TICKS) return;
        lastFlushTick = currentTick;
        // Copy to avoid concurrent-modification issues if a save() races during the write.
        var snapshot = new java.util.ArrayList<>(dirty);
        dirty.clear();
        for (String key : snapshot) {
            RegionManager m = managers.get(key);
            if (m == null) continue;
            try {
                storage.save(key, m);
            } catch (Exception ex) {
                WorldGuardNeo.LOGGER.error("Failed to save regions for {}", key, ex);
                // CRITICAL: re-mark dirty so a transient failure (disk full, locked file)
                // gets retried on the next flush instead of silently dropping the world's
                // unsaved edits until someone happens to modify it again. Without this, a
                // single failed write could lose region data on the next crash.
                dirty.add(key);
            }
        }
    }

    /** Remove the cached Level→manager mapping (call when a world is unloaded). */
    public void evict(Level level) {
        byLevel.remove(level);
    }

    public RegionStorage storage() { return storage; }
}
