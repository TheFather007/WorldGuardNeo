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
import java.util.Properties;

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
    private final Class<?>      driverClass;
    private boolean             driverPresent;
    private Connection          conn;

    public H2RegionStorage(Path baseDir) {
        this.dbFile   = baseDir.resolve("regions_h2");
        this.fallback = new JsonRegionStorage(baseDir);
        // org.h2.Driver is the canonical name across H2 1.x/2.x. Resolved via the classloader-
        // robust helper — a plain Class.forName missed drivers loaded by a sibling mod's loader,
        // which is exactly why "H2 init failed → JSON" happened on servers that had H2 present.
        this.driverClass = JdbcSupport.findDriverClass("org.h2.Driver");
        if (driverClass == null) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] H2 driver (org.h2.Driver) not found on any classloader; "
                    + "H2 storage will defer to JSON. Add an H2 jar to the server to use it.");
        }
        boolean present = driverClass != null;
        try {
            Files.createDirectories(baseDir);
            if (present) initSchema();
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error("[WorldGuardNeo] H2 init failed — falling back to JSON storage", ex);
            present = false;
        }
        this.driverPresent = present;
        if (present) JdbcSupport.logDriver("H2", driverClass);
    }

    private Connection conn() throws SQLException {
        if (conn == null || conn.isClosed()) {
            // Embedded file mode, bypassing DriverManager (see JdbcSupport). DB_CLOSE_ON_EXIT=FALSE
            // keeps the store open until we close() it ourselves at server stop.
            String url = "jdbc:h2:file:" + dbFile.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
            conn = JdbcSupport.connect(driverClass, url, new Properties());
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
                        // Data-loss guard (see JsonRegionStorage): quarantine the corrupt payload so
                        // the next (empty) save can't overwrite and destroy recoverable data.
                        quarantineCorrupt(worldKey, json);
                        WorldGuardNeo.LOGGER.error(
                                "H2 payload for world '{}' is malformed — copied to a quarantine row "
                              + "and left the manager empty. Restore from the quarantine row or a backup.",
                                worldKey, ex);
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IOException("H2 load failed for " + worldKey, ex);
        }
    }

    /** Preserve a corrupt payload under a timestamped quarantine key before the manager is emptied. */
    private void quarantineCorrupt(String worldKey, String payload) {
        String qkey = worldKey + ".corrupt-" + System.currentTimeMillis();
        try (PreparedStatement ps = conn().prepareStatement(
                "MERGE INTO world_regions (world, payload, updated_at) KEY(world) VALUES (?, ?, ?)")) {
            ps.setString(1, qkey);
            ps.setString(2, payload);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException qe) {
            WorldGuardNeo.LOGGER.error("Failed to quarantine corrupt H2 payload for '{}'", worldKey, qe);
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
    public void prepareForBackup() {
        // Flush H2's MVStore to the main .mv.db file so a file-copy backup is consistent.
        if (!driverPresent) return;
        try (Statement s = conn().createStatement()) {
            s.execute("CHECKPOINT SYNC");
        } catch (SQLException ex) {
            WorldGuardNeo.LOGGER.debug("H2 checkpoint before backup failed", ex);
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
