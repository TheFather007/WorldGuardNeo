package dev.thefather007.worldguardneo.storage;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.config.WGConfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * MySQL/MariaDB-backed region storage (per-region row schema — see {@link AbstractJdbcRegionStorage}).
 *
 * <p>Migration: an older build's whole-world blob table (PK {@code world}, no {@code region_id}) is
 * renamed to {@code <table>_legacy} on startup, a fresh per-region {@code <table>} is created, and
 * each world is migrated lazily on first load. The legacy table is kept as a backup.
 *
 * <p>Settings come from the {@code [mysql]} config section. The driver is NOT bundled; if missing or
 * unreachable, defers to {@link JsonRegionStorage}. Connections are validated/reopened. Server-thread only.
 */
public final class MySqlRegionStorage extends AbstractJdbcRegionStorage {

    private final String     jdbcUrl;
    private final Properties connProps;
    private final String     table;
    private final int        validateTimeoutSeconds;
    private final Class<?>   driverClass;
    /** Non-null if a legacy whole-world table exists to lazily migrate from. */
    private String           legacyTable;

    public MySqlRegionStorage(Path baseDir, WGConfig.GlobalSection g) {
        super(new JsonRegionStorage(baseDir));
        this.table  = sanitizeIdentifier(g.mysqlTable, "world_regions");
        int timeout = g.mysqlConnectionTimeout > 0 ? g.mysqlConnectionTimeout : 10;
        this.validateTimeoutSeconds = Math.min(timeout, 30);

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

        this.driverClass = JdbcSupport.findDriverClass(
                "com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver", "org.mariadb.jdbc.Driver");

        boolean ok = false;
        if (driverClass != null) {
            try {
                initSchema();
                ensureSchemaVersion();
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

    @Override protected String backendName() { return "MySQL"; }
    @Override protected String table()       { return table; }
    @Override protected String legacyTable() { return legacyTable; }

    @Override protected String upsertSql() {
        return "INSERT INTO " + table + " (world, region_id, payload, updated_at) VALUES (?, ?, ?, ?) "
             + "ON DUPLICATE KEY UPDATE payload = VALUES(payload), updated_at = VALUES(updated_at)";
    }

    @Override
    protected Connection openConnection() throws SQLException {
        return JdbcSupport.connect(driverClass, jdbcUrl, connProps);
    }

    /** Round-trip validity probe so a dropped/idle-timed-out server connection is transparently reopened. */
    @Override
    protected boolean validate(Connection c) throws SQLException {
        return c.isValid(validateTimeoutSeconds);
    }

    private static String sanitizeIdentifier(String raw, String def) {
        if (raw == null) return def;
        String s = raw.trim();
        if (s.isEmpty() || !s.matches("[A-Za-z_][A-Za-z0-9_]*")) return def;
        return s;
    }

    private boolean exists(String type, String name) throws SQLException {
        String sql = type.equals("table")
                ? "SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=? LIMIT 1"
                : "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=? LIMIT 1";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, name);
            if (!type.equals("table")) ps.setString(2, "region_id");
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private void initSchema() throws SQLException {
        boolean tableExists = exists("table", table);
        boolean hasRegionId = tableExists && exists("column", table);
        if (tableExists && !hasRegionId) {
            // Old whole-world schema — preserve under <table>_legacy, then create per-region.
            try (Statement st = conn().createStatement()) {
                st.executeUpdate("RENAME TABLE " + table + " TO " + table + "_legacy");
            }
            WorldGuardNeo.LOGGER.info("[WorldGuardNeo] Renamed legacy MySQL table '{}' to '{}_legacy' for per-region migration.", table, table);
            createPerRegion();
        } else if (!tableExists) {
            createPerRegion();
        }
        if (exists("table", table + "_legacy")) legacyTable = table + "_legacy";
    }

    private void createPerRegion() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "world VARCHAR(255) NOT NULL, region_id VARCHAR(255) NOT NULL, payload LONGTEXT NOT NULL, "
                    + "updated_at BIGINT NOT NULL, PRIMARY KEY (world, region_id)) DEFAULT CHARSET=utf8mb4");
        }
    }
}
