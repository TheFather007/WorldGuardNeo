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

    /**
     * Flush journal state into the primary data file(s) so a file-copy backup is consistent.
     * Embedded SQL engines checkpoint the WAL; others no-op. Called on the server thread just
     * before BackupManager copies the files. Best-effort: never throws.
     */
    default void prepareForBackup() {}

    /** Optional: release any resources (DB connections, file handles). Default is a no-op. */
    @Override default void close() {}
}
