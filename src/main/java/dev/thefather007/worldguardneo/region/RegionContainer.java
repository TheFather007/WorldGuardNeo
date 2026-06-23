package dev.thefather007.worldguardneo.region;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.storage.RegionStorage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Maps world key → its RegionManager. Two indexes: {@link #managers} keyed by dimension-id string
 * (save/load, commands), and {@link #byLevel} keyed by Level instance for the hot lookup path —
 * Levels are stable, so identity hashing avoids allocating a "namespace:path" string per event.
 */
public final class RegionContainer {

    private final RegionStorage storage;
    private final Map<String, RegionManager> managers = new HashMap<>();
    private final IdentityHashMap<Level, RegionManager> byLevel = new IdentityHashMap<>();

    /**
     * Per-world "save needed" flag. {@link #save} only marks dirty; {@link #flushDirty} does the
     * disk write from the tick hook, coalescing rapid edits into a single I/O operation.
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
     * Count regions OWNED by a player across ALL dimensions. Claim limits must be global (the claim
     * path uses this, not single-world {@code RegionManager.countOwned}), else the quota resets per dimension.
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
        // Immediate synchronous full save (/rg save, server stop); drains all dirty/incremental sets.
        for (RegionManager m : managers.values()) {
            try { storage.save(m.world(), m); }
            catch (Exception ex) { WorldGuardNeo.LOGGER.error("Failed to save regions for {}", m.world(), ex); }
        }
        dirty.clear();
        regionDirty.clear();
        regionDeleted.clear();
    }

    /**
     * Per-region incremental dirty/deleted sets, keyed by world, so a single-region edit persists
     * only that region. Cascade-heavy ops use the full-world {@link #dirty} path, which subsumes
     * a world's per-region sets.
     */
    private final Map<String, java.util.Set<String>> regionDirty   = new HashMap<>();
    private final Map<String, java.util.Set<String>> regionDeleted = new HashMap<>();

    /** Mark a world dirty for the next {@link #flushDirty}. No I/O on the hot path. */
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
     * Mark a SINGLE region for incremental save (upsert one row) after a single-region edit, so the
     * flush touches only that region. Pass {@code "__global__"} after editing the global flags.
     */
    public void saveRegion(Level level, String regionId) {
        RegionManager m = byLevel.get(level);
        if (m == null) m = get(level);
        String w = m.world();
        regionDirty.computeIfAbsent(w, k -> new java.util.HashSet<>()).add(regionId);
        var del = regionDeleted.get(w);
        if (del != null) del.remove(regionId); // an upsert supersedes a pending delete
    }

    /** Mark a SINGLE region for incremental deletion (drop its row) after {@code /rg remove}. */
    public void deleteRegion(Level level, String regionId) {
        RegionManager m = byLevel.get(level);
        if (m == null) m = get(level);
        String w = m.world();
        regionDeleted.computeIfAbsent(w, k -> new java.util.HashSet<>()).add(regionId);
        var dty = regionDirty.get(w);
        if (dty != null) dty.remove(regionId); // a delete supersedes a pending upsert
    }

    /**
     * Writes pending dirty worlds to disk once enough ticks have elapsed. The 5s interval trades off
     * crash exposure (a few seconds of edits) against not hammering the disk during batch operations.
     */
    public void flushDirty(long currentTick) {
        if (dirty.isEmpty() && regionDirty.isEmpty() && regionDeleted.isEmpty()) return;
        if (currentTick - lastFlushTick < FLUSH_INTERVAL_TICKS) return;
        lastFlushTick = currentTick;

        // Full-world saves first; they subsume any pending per-region work for the same world.
        var fullSnapshot = new java.util.ArrayList<>(dirty);
        dirty.clear();
        for (String key : fullSnapshot) {
            regionDirty.remove(key);
            regionDeleted.remove(key);
            RegionManager m = managers.get(key);
            if (m == null) continue;
            try {
                storage.save(key, m);
            } catch (Exception ex) {
                WorldGuardNeo.LOGGER.error("Failed to save regions for {}", key, ex);
                // Re-mark dirty so a transient failure (disk full, locked file) is retried next flush
                // instead of silently dropping the world's unsaved edits.
                dirty.add(key);
            }
        }

        // Per-region incremental deletes, then upserts.
        var delSnapshot = new java.util.HashMap<>(regionDeleted);
        regionDeleted.clear();
        delSnapshot.forEach((world, ids) -> {
            RegionManager m = managers.get(world);
            if (m == null) return;
            for (String id : ids) {
                try { storage.deleteRegion(world, m, id); }
                catch (Exception ex) {
                    WorldGuardNeo.LOGGER.error("Failed to delete region {}/{}", world, id, ex);
                    regionDeleted.computeIfAbsent(world, k -> new java.util.HashSet<>()).add(id);
                }
            }
        });
        var dirtySnapshot = new java.util.HashMap<>(regionDirty);
        regionDirty.clear();
        dirtySnapshot.forEach((world, ids) -> {
            RegionManager m = managers.get(world);
            if (m == null) return;
            for (String id : ids) {
                try { storage.saveRegion(world, m, id); }
                catch (Exception ex) {
                    WorldGuardNeo.LOGGER.error("Failed to save region {}/{}", world, id, ex);
                    regionDirty.computeIfAbsent(world, k -> new java.util.HashSet<>()).add(id);
                }
            }
        });
    }

    /** Remove the cached Level→manager mapping (call when a world is unloaded). */
    public void evict(Level level) {
        byLevel.remove(level);
    }

    public RegionStorage storage() { return storage; }
}
