package dev.thefather007.worldguardneo.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.region.RegionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite-backed region storage for servers with very large region counts.
 *
 * Schema:
 *   CREATE TABLE world_regions (
 *     world TEXT PRIMARY KEY,
 *     payload TEXT NOT NULL,
 *     updated_at INTEGER NOT NULL
 *   );
 *
 * Payload is exactly the JSON document produced by {@link RegionJsonCodec}, so the table
 * is round-trip-compatible with the JSON file backend.
 *
 * <p>A single long-lived JDBC connection is reused across calls to avoid the per-save
 * cost of opening one. SQLite connections are not thread-safe so all calls must come
 * from the server thread (consistent with the rest of the mod).
 *
 * <p>Falls back to {@link JsonRegionStorage} transparently if {@code org.sqlite.JDBC}
 * is not on the classpath, since {@code sqlite-jdbc} is not bundled with the mod.
 */
public final class SqliteRegionStorage implements RegionStorage, AutoCloseable {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path          dbFile;
    private final RegionStorage fallback;
    private final Class<?>      driverClass;
    private boolean             driverPresent;
    /** Shared connection. Lazily opened on first use; closed at server stop via close(). */
    private Connection          conn;

    public SqliteRegionStorage(Path baseDir) {
        this.dbFile   = baseDir.resolve("regions.sqlite");
        this.fallback = new JsonRegionStorage(baseDir);
        this.driverClass = JdbcSupport.findDriverClass("org.sqlite.JDBC");
        if (driverClass == null) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] sqlite-jdbc not found on any classloader; SQLite storage will defer to JSON.");
        }
        boolean present = driverClass != null;
        try {
            Files.createDirectories(baseDir);
            if (present) initSchema();
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error("[WorldGuardNeo] SQLite init failed — falling back to JSON storage", ex);
            present = false; // demote to JSON; further calls go to the fallback
        }
        this.driverPresent = present;
        if (present) JdbcSupport.logDriver("SQLite", driverClass);
    }

    private Connection conn() throws SQLException {
        if (conn == null || conn.isClosed()) {
            // Bypass DriverManager (see JdbcSupport) for classloader robustness.
            conn = JdbcSupport.connect(driverClass, "jdbc:sqlite:" + dbFile.toAbsolutePath(),
                    new java.util.Properties());
            // Sensible defaults for a desktop-class server using SQLite.
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA synchronous = NORMAL");
            }
        }
        return conn;
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS world_regions ("
                    + "world TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at INTEGER NOT NULL)");
        }
    }

    @Override
    public void load(String worldKey, RegionManager into) throws IOException {
        if (!driverPresent) { fallback.load(worldKey, into); return; }
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT payload FROM world_regions WHERE world = ?")) {
            ps.setString(1, worldKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString(1);
                    try {
                        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                        RegionJsonCodec.applyJson(root, into);
                    } catch (JsonParseException | IllegalStateException ex) {
                        WorldGuardNeo.LOGGER.error(
                                "SQLite payload for world '{}' is malformed — leaving manager empty.",
                                worldKey, ex);
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IOException("SQLite load failed for " + worldKey, ex);
        }
    }

    @Override
    public void save(String worldKey, RegionManager from) throws IOException {
        if (!driverPresent) { fallback.save(worldKey, from); return; }
        String payload = GSON.toJson(RegionJsonCodec.toJson(from));
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO world_regions (world, payload, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(world) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at")) {
            ps.setString(1, worldKey);
            ps.setString(2, payload);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException("SQLite save failed for " + worldKey, ex);
        }
    }

    @Override
    public void close() {
        if (conn != null) {
            try { conn.close(); }
            catch (SQLException ex) {
                WorldGuardNeo.LOGGER.warn("Failed to close SQLite connection", ex);
            }
        }
    }
}
