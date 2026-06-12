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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * H2-backed region storage (embedded file database).
 *
 * <p>Schema (one row per world, payload is the JSON document from {@link RegionJsonCodec}, so it
 * is round-trip-compatible with the JSON/SQLite backends):
 * <pre>
 *   CREATE TABLE world_regions (
 *     world      VARCHAR(255) PRIMARY KEY,
 *     payload    CLOB         NOT NULL,
 *     updated_at BIGINT       NOT NULL
 *   );
 * </pre>
 *
 * <p>A single long-lived JDBC connection is reused. All calls come from the server thread,
 * consistent with the rest of the mod.
 *
 * <p>The H2 driver ({@code org.h2.Driver}) is NOT bundled with this mod. It is, however, shipped
 * by LuckPerms (which uses H2 as its default storage), so on most servers it is already present.
 * If the driver is missing, this backend transparently defers to {@link JsonRegionStorage}.
 */
public final class H2RegionStorage implements RegionStorage, AutoCloseable {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path          dbFile;   // base path; H2 appends .mv.db
    private final RegionStorage fallback;
    private boolean             driverPresent;
    private Connection          conn;

    public H2RegionStorage(Path baseDir) {
        this.dbFile   = baseDir.resolve("regions_h2");
        this.fallback = new JsonRegionStorage(baseDir);
        boolean present = false;
        try {
            Class.forName("org.h2.Driver");
            present = true;
        } catch (ClassNotFoundException ignored) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] H2 driver (org.h2.Driver) not on classpath; H2 storage will defer to JSON. "
                    + "Install an H2 jar or LuckPerms (which ships H2) to use it.");
        }
        this.driverPresent = present;
        try {
            Files.createDirectories(baseDir);
            if (present) initSchema();
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error("[WorldGuardNeo] H2 init failed — falling back to JSON storage", ex);
            this.driverPresent = false;
        }
    }

    private Connection conn() throws SQLException {
        if (conn == null || conn.isClosed()) {
            // Embedded file mode. No username/password for a local embedded DB.
            conn = DriverManager.getConnection("jdbc:h2:file:" + dbFile.toAbsolutePath());
        }
        return conn;
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS world_regions ("
                    + "world VARCHAR(255) PRIMARY KEY, payload CLOB NOT NULL, updated_at BIGINT NOT NULL)");
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
                                "H2 payload for world '{}' is malformed — leaving manager empty.",
                                worldKey, ex);
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IOException("H2 load failed for " + worldKey, ex);
        }
    }

    @Override
    public void save(String worldKey, RegionManager from) throws IOException {
        if (!driverPresent) { fallback.save(worldKey, from); return; }
        String payload = GSON.toJson(RegionJsonCodec.toJson(from));
        // H2 native upsert.
        try (PreparedStatement ps = conn().prepareStatement(
                "MERGE INTO world_regions (world, payload, updated_at) KEY(world) VALUES (?, ?, ?)")) {
            ps.setString(1, worldKey);
            ps.setString(2, payload);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException("H2 save failed for " + worldKey, ex);
        }
    }

    @Override
    public void close() {
        if (conn != null) {
            try { conn.close(); }
            catch (SQLException ex) {
                WorldGuardNeo.LOGGER.warn("Failed to close H2 connection", ex);
            }
        }
    }
}
