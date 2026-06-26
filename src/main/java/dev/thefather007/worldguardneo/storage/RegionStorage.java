package dev.thefather007.worldguardneo.storage;

import dev.thefather007.worldguardneo.region.RegionManager;

import java.io.IOException;

/** Persistence backend for region managers. */
public interface RegionStorage extends AutoCloseable {
    void load(String worldKey, RegionManager into) throws IOException;

    /** Full sync of a world: persist every current region (+ global) and prune removed ones. */
    void save(String worldKey, RegionManager from) throws IOException;

    /**
     * Incrementally persist a single region (by id; {@code "__global__"} for the global-flags row),
     * reading its current state from {@code from}. Used for the common single-region edit so a
     * {@code /rg flag} on one region doesn't rewrite the whole world. The default falls back to a
     * full {@link #save} — correct for backends without per-region rows (e.g. the JSON file).
     */
    default void saveRegion(String worldKey, RegionManager from, String regionId) throws IOException {
        save(worldKey, from);
    }

    /**
     * Incrementally delete a single region's persisted row. {@code from} reflects the post-removal
     * state (the region is already gone from it). Default falls back to a full {@link #save}.
     */
    default void deleteRegion(String worldKey, RegionManager from, String regionId) throws IOException {
        save(worldKey, from);
    }

    /** A unit of blocking storage I/O, prepared on the server thread and run on the write-behind worker. */
    @FunctionalInterface
    interface IoTask { void run() throws IOException; }

    /**
     * Two-phase save: serialize the live region NOW (must be called on the server thread, where the
     * RegionManager is safely readable) and return a closure that performs ONLY the blocking I/O,
     * to be run on the write-behind worker. This is what makes writes async-safe — no backend touches
     * the live manager off-thread. The default keeps the old synchronous behaviour (serialize+write
     * together) for any backend that doesn't override it.
     */
    default IoTask prepareSave(String worldKey, RegionManager from) {
        return () -> save(worldKey, from);
    }
    default IoTask prepareSaveRegion(String worldKey, RegionManager from, String regionId) {
        return () -> saveRegion(worldKey, from, regionId);
    }
    default IoTask prepareDeleteRegion(String worldKey, RegionManager from, String regionId) {
        return () -> deleteRegion(worldKey, from, regionId);
    }

    /**
     * Flush journal state into the primary data file(s) so a file-copy backup is consistent.
     * Embedded SQL engines checkpoint the WAL; others no-op. Called on the server thread just
     * before BackupManager copies the files. Best-effort: never throws.
     */
    default void prepareForBackup() {}

    /** On-disk schema version for diagnostics, or -1 if not applicable (e.g. the JSON backend). */
    default int schemaVersion() { return -1; }

    /** Optional: release any resources (DB connections, file handles). Default is a no-op. */
    @Override default void close() {}
}
