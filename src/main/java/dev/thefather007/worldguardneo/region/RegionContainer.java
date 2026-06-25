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

    // Write-behind worker: the server thread serializes a snapshot (in prepare*) and submits an I/O
    // closure here, so the blocking disk/DB write (notably a MySQL round-trip) never stalls the tick.
    // Single-threaded → writes stay strictly ordered. The shared JDBC connection is therefore touched
    // by only ONE thread at a time: the worker during steady state, and the server thread only after
    // drainWrites() (saveAll / backup / reload), so they never overlap.
    private final java.util.concurrent.ExecutorService writer =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "WGN-RegionWriter");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.atomic.AtomicInteger pendingWrites =
            new java.util.concurrent.atomic.AtomicInteger();
    private MinecraftServer server; // captured at load; used to re-dirty on the server thread after a failed write

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
        this.server = server;
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
        // Immediate synchronous full save (/rg save, server stop). Drain queued async writes FIRST so
        // a stale in-flight snapshot can't land on disk after this fresh full save, then write
        // synchronously on the server thread (the worker is now idle → no connection contention).
        drainWrites();
        for (RegionManager m : managers.values()) {
            try { storage.save(m.world(), m); }
            catch (Exception ex) { WorldGuardNeo.LOGGER.error("Failed to save regions for {}", m.world(), ex); }
        }
        dirty.clear();
        regionDirty.clear();
        regionDeleted.clear();
    }

    /** Number of write-behind I/O tasks queued or in flight (for diagnostics / {@code /rg debug}). */
    public int pendingWrites() { return pendingWrites.get(); }

    /**
     * Block until the write-behind worker has drained all queued tasks. Call before any server-thread
     * use of the storage connection (saveAll, backup checkpoint, reload) so the two threads never
     * touch the connection at once. Bounded wait so a stuck write can't hang shutdown forever.
     */
    public void drainWrites() {
        try {
            writer.submit(() -> {}).get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Region write queue did not drain within 15s ({} pending).",
                    pendingWrites.get());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Region write drain failed", ex);
        }
    }

    /** Stop the write-behind worker after a final drain. Call on server stop, before closing storage. */
    public void shutdownWriter() {
        drainWrites();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException ie) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Serialize the task's snapshot has already happened (on the caller/server thread); here we only
     *  queue the blocking I/O and, on failure, re-mark the work dirty back on the server thread. */
    private void submitWrite(String desc, RegionStorage.IoTask task, Runnable onFailure) {
        pendingWrites.incrementAndGet();
        writer.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                WorldGuardNeo.LOGGER.error("[WorldGuardNeo] Async region write failed: {}", desc, t);
                if (server != null) server.execute(onFailure); // re-dirty on the server thread for retry
            } finally {
                pendingWrites.decrementAndGet();
            }
        });
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

        // Each storage.prepare* call SERIALIZES the snapshot here on the server thread (safe to read
        // the live managers) and returns an I/O closure; submitWrite runs that closure on the worker.

        // Full-world saves first; they subsume any pending per-region work for the same world.
        var fullSnapshot = new java.util.ArrayList<>(dirty);
        dirty.clear();
        for (String key : fullSnapshot) {
            regionDirty.remove(key);
            regionDeleted.remove(key);
            RegionManager m = managers.get(key);
            if (m == null) continue;
            // Re-mark dirty on failure so a transient error (disk full, DB down) is retried next flush.
            submitWrite("save world " + key, storage.prepareSave(key, m), () -> dirty.add(key));
        }

        // Per-region incremental deletes, then upserts.
        var delSnapshot = new java.util.HashMap<>(regionDeleted);
        regionDeleted.clear();
        delSnapshot.forEach((world, ids) -> {
            RegionManager m = managers.get(world);
            if (m == null) return;
            for (String id : ids) {
                submitWrite("delete " + world + "/" + id, storage.prepareDeleteRegion(world, m, id),
                        () -> regionDeleted.computeIfAbsent(world, k -> new java.util.HashSet<>()).add(id));
            }
        });
        var dirtySnapshot = new java.util.HashMap<>(regionDirty);
        regionDirty.clear();
        dirtySnapshot.forEach((world, ids) -> {
            RegionManager m = managers.get(world);
            if (m == null) return;
            for (String id : ids) {
                submitWrite("save " + world + "/" + id, storage.prepareSaveRegion(world, m, id),
                        () -> regionDirty.computeIfAbsent(world, k -> new java.util.HashSet<>()).add(id));
            }
        });
    }

    /** Remove the cached Level→manager mapping (call when a world is unloaded). */
    public void evict(Level level) {
        byLevel.remove(level);
    }

    public RegionStorage storage() { return storage; }
}
