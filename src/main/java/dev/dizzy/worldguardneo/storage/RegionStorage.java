package dev.dizzy.worldguardneo.storage;

import dev.dizzy.worldguardneo.region.RegionManager;

import java.io.IOException;

/** Persistence backend for region managers. */
public interface RegionStorage extends AutoCloseable {
    void load(String worldKey, RegionManager into) throws IOException;
    void save(String worldKey, RegionManager from) throws IOException;
    /** Optional: release any resources (DB connections, file handles). Default is a no-op. */
    @Override default void close() {}
}
