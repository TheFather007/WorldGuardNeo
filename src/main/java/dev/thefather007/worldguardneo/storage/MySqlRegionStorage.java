package dev.thefather007.worldguardneo.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.config.WGConfig;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.region.RegionManager;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * MySQL/MariaDB-backed region storage with a PER-REGION row schema:
 * <pre>
 *   CREATE TABLE &lt;table&gt; (
 *     world VARCHAR(255), region_id VARCHAR(255), payload LONGTEXT, updated_at BIGINT,
 *     PRIMARY KEY (world, region_id)) DEFAULT CHARSET=utf8mb4;
 * </pre>
 * A single {@code /rg flag} edit upserts one row instead of rewriting the whole world; the per-world
 * {@code __global__} row holds the global-flags document.
 *
 * <p>Migration: an older build's whole-world blob table (PK {@code world}, no {@code region_id}) is
 * renamed to {@code <table>_legacy} on startup, a fresh per-region {@code <table>} is created, and
 * each world is migrated lazily on first load. The legacy table is kept as a backup.
 *
 * <p>Settings come from the {@code [mysql]} config section. The driver is NOT bundled; if missing or
 * unreachable, defers to {@link JsonRegionStorage}. Connections are validated/reopened. Server-thread only.
 */
public final class MySqlRegionStorage implements RegionStorage, AutoCloseable {

    private static final Gson   GSON       = new GsonBuilder().disableHtmlEscaping().create();
    private static final String GLOBAL_KEY = "__global__";

    private final String        jdbcUrl;
    private final Properties    connProps;
    private final String        table;
    private final int           validateTimeoutSeconds;
    private final RegionStorage fallback;
    private final Class<?>      driverClass;
    private boolean             usable;
    /** Non-null if a legacy whole-world table exists to lazily migrate from. */
    private String              legacyTable;
    private Connection          conn;

    public MySqlRegionStorage(Path baseDir, WGConfig.GlobalSection g) {
        this.fallback = new JsonRegionStorage(baseDir);
        this.table    = sanitizeIdentifier(g.mysqlTable, "world_regions");
        int timeout   = g.mysqlConnectionTimeout > 0 ? g.mysqlConnectionTimeout : 10;
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

    private static String sanitizeIdentifier(String raw, String def) {
        if (raw == null) return def;
        String s = raw.trim();
        if (s.isEmpty() || !s.matches("[A-Za-z_][A-Za-z0-9_]*")) return def;
        return s;
    }

    private Connection conn() throws SQLException {
        if (conn == null || conn.isClosed() || !conn.isValid(validateTimeoutSeconds)) {
            if (conn != null) { try { conn.close(); } catch (SQLException ignored) {} }
            conn = JdbcSupport.connect(driverClass, jdbcUrl, connProps);
        }
        return conn;
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

    @Override
    public void load(String worldKey, RegionManager into) throws IOException {
        if (!usable) { fallback.load(worldKey, into); return; }
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT region_id, payload FROM " + table + " WHERE world = ?")) {
            ps.setString(1, worldKey);
            boolean any = false;
            Map<String, String> parents = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    any = true;
                    String rid = rs.getString(1), payload = rs.getString(2);
                    if (rid != null && rid.contains(".corrupt-")) continue;
                    try {
                        JsonObject o = JsonParser.parseString(payload).getAsJsonObject();
                        if (GLOBAL_KEY.equals(rid)) RegionJsonCodec.applyGlobalJson(o, into);
                        else { var r = RegionJsonCodec.readRegion(rid, o, parents); if (r != null) into.add(r); }
                    } catch (JsonParseException | IllegalStateException ex) {
                        quarantineCorrupt(worldKey, rid, payload);
                        WorldGuardNeo.LOGGER.error("MySQL payload for region '{}/{}' is malformed — "
                                + "quarantined and skipped.", worldKey, rid, ex);
                    }
                }
            }
            RegionJsonCodec.linkParents(parents, into);
            if (!any && legacyTable != null) migrateLegacyWorld(worldKey, into);
        } catch (SQLException ex) {
            throw new IOException("MySQL load failed for " + worldKey, ex);
        }
    }

    /** One-time per-world migration from {@code <table>_legacy} (kept as a backup). */
    private void migrateLegacyWorld(String worldKey, RegionManager into) {
        String blob = null;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT payload FROM " + legacyTable + " WHERE world = ?")) {
            ps.setString(1, worldKey);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) blob = rs.getString(1); }
        } catch (SQLException noLegacy) {
            return;
        }
        if (blob == null) return;
        try {
            RegionJsonCodec.applyJson(JsonParser.parseString(blob).getAsJsonObject(), into);
            save(worldKey, into);
            WorldGuardNeo.LOGGER.info("[WorldGuardNeo] Migrated world '{}' from legacy blob to per-region MySQL rows.", worldKey);
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error("[WorldGuardNeo] Legacy MySQL migration failed for '{}' — leaving manager empty.", worldKey, ex);
        }
    }

    @Override
    public void save(String worldKey, RegionManager from) throws IOException {
        if (!usable) { fallback.save(worldKey, from); return; }
        try {
            conn().setAutoCommit(false);
            try {
                try (PreparedStatement del = conn().prepareStatement(
                        "DELETE FROM " + table + " WHERE world = ? AND region_id NOT LIKE '%.corrupt-%'")) {
                    del.setString(1, worldKey);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn().prepareStatement(
                        "INSERT INTO " + table + " (world, region_id, payload, updated_at) VALUES (?, ?, ?, ?)")) {
                    long now = System.currentTimeMillis();
                    for (ProtectedRegion r : from.all()) {
                        ins.setString(1, worldKey); ins.setString(2, r.id());
                        ins.setString(3, GSON.toJson(RegionJsonCodec.regionToJson(r))); ins.setLong(4, now);
                        ins.addBatch();
                    }
                    ins.setString(1, worldKey); ins.setString(2, GLOBAL_KEY);
                    ins.setString(3, GSON.toJson(RegionJsonCodec.globalToJson(from.globalRegion()))); ins.setLong(4, now);
                    ins.addBatch();
                    ins.executeBatch();
                }
                conn().commit();
            } catch (SQLException ex) {
                conn().rollback();
                throw ex;
            } finally {
                conn().setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IOException("MySQL save failed for " + worldKey, ex);
        }
    }

    @Override
    public void saveRegion(String worldKey, RegionManager from, String regionId) throws IOException {
        if (!usable) { fallback.saveRegion(worldKey, from, regionId); return; }
        ProtectedRegion r = GLOBAL_KEY.equals(regionId) ? from.globalRegion() : from.get(regionId).orElse(null);
        if (r == null) { deleteRegion(worldKey, from, regionId); return; }
        String payload = GSON.toJson(GLOBAL_KEY.equals(regionId)
                ? RegionJsonCodec.globalToJson(r) : RegionJsonCodec.regionToJson(r));
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO " + table + " (world, region_id, payload, updated_at) VALUES (?, ?, ?, ?) "
              + "ON DUPLICATE KEY UPDATE payload = VALUES(payload), updated_at = VALUES(updated_at)")) {
            ps.setString(1, worldKey); ps.setString(2, regionId);
            ps.setString(3, payload); ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException("MySQL saveRegion failed for " + worldKey + "/" + regionId, ex);
        }
    }

    @Override
    public void deleteRegion(String worldKey, RegionManager from, String regionId) throws IOException {
        if (!usable) { fallback.deleteRegion(worldKey, from, regionId); return; }
        try (PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM " + table + " WHERE world = ? AND region_id = ?")) {
            ps.setString(1, worldKey); ps.setString(2, regionId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException("MySQL deleteRegion failed for " + worldKey + "/" + regionId, ex);
        }
    }

    /** Preserve a corrupt per-region payload under a timestamped quarantine row id. */
    private void quarantineCorrupt(String worldKey, String regionId, String payload) {
        String qkey = regionId + ".corrupt-" + System.currentTimeMillis();
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO " + table + " (world, region_id, payload, updated_at) VALUES (?, ?, ?, ?) "
              + "ON DUPLICATE KEY UPDATE payload = VALUES(payload), updated_at = VALUES(updated_at)")) {
            ps.setString(1, worldKey); ps.setString(2, qkey);
            ps.setString(3, payload); ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException qe) {
            WorldGuardNeo.LOGGER.error("Failed to quarantine corrupt MySQL payload for '{}/{}'", worldKey, regionId, qe);
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
