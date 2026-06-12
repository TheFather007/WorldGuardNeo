package dev.dizzy.worldguardneo.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.dizzy.worldguardneo.WorldGuardNeo;
import dev.dizzy.worldguardneo.region.RegionManager;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * MySQL-backed region storage for servers that want regions in a shared external database
 * (e.g. a network of servers, or centralised backups).
 *
 * <p>Schema (payload is the JSON document from {@link RegionJsonCodec}, round-trip-compatible
 * with the other backends):
 * <pre>
 *   CREATE TABLE world_regions (
 *     world      VARCHAR(255) PRIMARY KEY,
 *     payload    LONGTEXT     NOT NULL,
 *     updated_at BIGINT       NOT NULL
 *   );
 * </pre>
 *
 * <p>Connection details (host, port, database, user, password) come from the {@code [mysql]}
 * section of {@code config.toml}. The MySQL Connector/J driver is NOT bundled with this mod —
 * the admin must drop {@code mysql-connector-j-*.jar} into the server's libraries/mods. If the
 * driver is missing or the connection can't be established, this backend transparently defers to
 * {@link JsonRegionStorage} so the server still runs (regions just stay local).
 *
 * <p>A single connection is reused; it is validated (and reopened) before each operation so a
 * dropped network connection self-heals. All calls come from the server thread.
 */
public final class MySqlRegionStorage implements RegionStorage, AutoCloseable {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String        jdbcUrl;
    private final String        user;
    private final String        password;
    private final RegionStorage fallback;
    private boolean             usable;
    private Connection          conn;

    public MySqlRegionStorage(Path baseDir, String host, int port, String database,
                              String user, String password, boolean useSsl) {
        this.fallback = new JsonRegionStorage(baseDir);
        this.user     = user;
        this.password = password;
        this.jdbcUrl  = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSsl
                + "&autoReconnect=true&characterEncoding=UTF-8&useUnicode=true";

        boolean ok = false;
        // MySQL Connector/J 8 uses com.mysql.cj.jdbc.Driver; older 5.x used com.mysql.jdbc.Driver.
        if (driverAvailable("com.mysql.cj.jdbc.Driver") || driverAvailable("com.mysql.jdbc.Driver")) {
            try {
                initSchema();
                ok = true;
                WorldGuardNeo.LOGGER.info("[WorldGuardNeo] MySQL region storage connected to {}:{}/{}",
                        host, port, database);
            } catch (Exception ex) {
                WorldGuardNeo.LOGGER.error("[WorldGuardNeo] MySQL connection/init failed — falling back to JSON storage. "
                        + "Check the [mysql] settings in config.toml.", ex);
            }
        } else {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] MySQL Connector/J driver not on classpath; MySQL storage will defer to JSON. "
                    + "Drop mysql-connector-j-*.jar into the server to use it.");
        }
        this.usable = ok;
    }

    private static boolean driverAvailable(String cls) {
        try { Class.forName(cls); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    private Connection conn() throws SQLException {
        // Validate the pooled connection; MySQL drops idle ones. isValid(2s) → reopen if needed.
        if (conn == null || conn.isClosed() || !conn.isValid(2)) {
            if (conn != null) { try { conn.close(); } catch (SQLException ignored) {} }
            conn = DriverManager.getConnection(jdbcUrl, user, password);
        }
        return conn;
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS world_regions ("
                    + "world VARCHAR(255) PRIMARY KEY, payload LONGTEXT NOT NULL, updated_at BIGINT NOT NULL)");
        }
    }

    @Override
    public void load(String worldKey, RegionManager into) throws IOException {
        if (!usable) { fallback.load(worldKey, into); return; }
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
                                "MySQL payload for world '{}' is malformed — leaving manager empty.",
                                worldKey, ex);
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IOException("MySQL load failed for " + worldKey, ex);
        }
    }

    @Override
    public void save(String worldKey, RegionManager from) throws IOException {
        if (!usable) { fallback.save(worldKey, from); return; }
        String payload = GSON.toJson(RegionJsonCodec.toJson(from));
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO world_regions (world, payload, updated_at) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE payload = VALUES(payload), updated_at = VALUES(updated_at)")) {
            ps.setString(1, worldKey);
            ps.setString(2, payload);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException("MySQL save failed for " + worldKey, ex);
        }
    }

    @Override
    public void close() {
        if (conn != null) {
            try { conn.close(); }
            catch (SQLException ex) {
                WorldGuardNeo.LOGGER.warn("Failed to close MySQL connection", ex);
            }
        }
    }
}
