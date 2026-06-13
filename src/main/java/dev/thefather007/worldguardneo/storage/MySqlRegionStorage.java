package dev.thefather007.worldguardneo.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.config.WGConfig;
import dev.thefather007.worldguardneo.region.RegionManager;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * MySQL/MariaDB-backed region storage for servers that want regions in a shared external database
 * (e.g. a network of servers, or centralised backups).
 *
 * <p>Schema (payload is the JSON document from {@link RegionJsonCodec}, round-trip-compatible
 * with the other backends; {@code <table>} is configurable):
 * <pre>
 *   CREATE TABLE &lt;table&gt; (
 *     world      VARCHAR(255) PRIMARY KEY,
 *     payload    LONGTEXT     NOT NULL,
 *     updated_at BIGINT       NOT NULL
 *   );
 * </pre>
 *
 * <p>All connection settings come from the {@code [mysql]} section of {@code config.toml}
 * (host, port, database, user, password, ssl, table, connection-timeout, pool size, extra
 * JDBC properties). The Connector/J (or MariaDB) driver is NOT bundled — drop the jar into the
 * server. If the driver is missing or the connection can't be established, this backend defers
 * to {@link JsonRegionStorage} so the server still runs.
 *
 * <p>Connections are validated before each operation and reopened if dropped, so an idle network
 * connection self-heals. All calls come from the server thread.
 */
public final class MySqlRegionStorage implements RegionStorage, AutoCloseable {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String        jdbcUrl;
    private final Properties    connProps;
    private final String        table;
    private final int           validateTimeoutSeconds;
    private final RegionStorage fallback;
    private final Class<?>      driverClass;
    private boolean             usable;
    private Connection          conn;

    public MySqlRegionStorage(Path baseDir, WGConfig.GlobalSection g) {
        this.fallback = new JsonRegionStorage(baseDir);
        this.table    = sanitizeIdentifier(g.mysqlTable, "world_regions");
        int timeout   = g.mysqlConnectionTimeout > 0 ? g.mysqlConnectionTimeout : 10;
        this.validateTimeoutSeconds = Math.min(timeout, 30);

        // Build the JDBC URL. Base params are sane defaults; admin extra-params can override or add.
        StringBuilder url = new StringBuilder("jdbc:mysql://")
                .append(g.mysqlHost).append(':').append(g.mysqlPort).append('/').append(g.mysqlDatabase)
                .append("?useSSL=").append(g.mysqlUseSsl)
                .append("&characterEncoding=UTF-8&useUnicode=true&autoReconnect=true")
                .append("&connectTimeout=").append(timeout * 1000)
                .append("&socketTimeout=").append(Math.max(timeout, 30) * 1000);
        if (g.mysqlProperties != null) {
            for (String kv : g.mysqlProperties) {
                if (kv != null && kv.indexOf('=') > 0) url.append('&').append(kv.trim());
            }
        }
        this.jdbcUrl = url.toString();

        this.connProps = new Properties();
        connProps.setProperty("user", g.mysqlUser == null ? "" : g.mysqlUser);
        connProps.setProperty("password", g.mysqlPassword == null ? "" : g.mysqlPassword);

        // Connector/J 8 = com.mysql.cj.jdbc.Driver; legacy 5.x = com.mysql.jdbc.Driver;
        // MariaDB's own driver also speaks the mysql:// URL and is a common substitute.
        this.driverClass = JdbcSupport.findDriverClass(
                "com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver", "org.mariadb.jdbc.Driver");

        boolean ok = false;
        if (driverClass != null) {
            try {
                initSchema();
                ok = true;
                JdbcSupport.logDriver("MySQL", driverClass);
                WorldGuardNeo.LOGGER.info("[WorldGuardNeo] MySQL region storage connected to {}:{}/{} (table '{}').",
                        g.mysqlHost, g.mysqlPort, g.mysqlDatabase, table);
            } catch (Exception ex) {
                WorldGuardNeo.LOGGER.error("[WorldGuardNeo] MySQL connection/init failed — falling back to JSON storage. "
                        + "Check the [mysql] settings in config.toml.", ex);
            }
        } else {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] No MySQL/MariaDB driver found on any classloader; MySQL storage "
                    + "will defer to JSON. Drop mysql-connector-j-*.jar (or the MariaDB driver) into the server.");
        }
        this.usable = ok;
    }

    /**
     * Keep a SQL identifier to a safe character set so a misconfigured table name can't become
     * an injection vector (the name is interpolated, not bindable). Falls back to {@code def}.
     */
    private static String sanitizeIdentifier(String raw, String def) {
        if (raw == null) return def;
        String s = raw.trim();
        if (s.isEmpty() || !s.matches("[A-Za-z_][A-Za-z0-9_]*")) return def;
        return s;
    }

    private Connection conn() throws SQLException {
        // Validate the connection; MySQL drops idle ones. Reopen via the driver directly.
        if (conn == null || conn.isClosed() || !conn.isValid(validateTimeoutSeconds)) {
            if (conn != null) { try { conn.close(); } catch (SQLException ignored) {} }
            conn = JdbcSupport.connect(driverClass, jdbcUrl, connProps);
        }
        return conn;
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "world VARCHAR(255) PRIMARY KEY, payload LONGTEXT NOT NULL, updated_at BIGINT NOT NULL)");
        }
    }

    @Override
    public void load(String worldKey, RegionManager into) throws IOException {
        if (!usable) { fallback.load(worldKey, into); return; }
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT payload FROM " + table + " WHERE world = ?")) {
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
                "INSERT INTO " + table + " (world, payload, updated_at) VALUES (?, ?, ?) "
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
