package dev.thefather007.worldguardneo.storage;

import dev.thefather007.worldguardneo.region.RegionManager;

import java.io.IOException;

/** Persistence backend for region managers. */
public interface RegionStorage extends AutoCloseable {
    void load(String worldKey, RegionManager into) throws IOException;
    void save(String worldKey, RegionManager from) throws IOException;

    /**
     * Flush in-memory/journal state to the primary data file(s) so an external file-copy backup
     * captures a consistent, up-to-date snapshot. For embedded SQL engines this checkpoints the
     * write-ahead log into the main DB file; for the others it is a no-op. Called by
     * {@link dev.thefather007.worldguardneo.backup.BackupManager} on the server thread immediately
     * before it copies the data files. Best-effort: never throws.
     */
    default void prepareForBackup() {}

    /** Optional: release any resources (DB connections, file handles). Default is a no-op. */
    @Override default void close() {}
}
