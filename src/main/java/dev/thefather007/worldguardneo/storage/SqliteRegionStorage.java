package dev.thefather007.worldguardneo.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.region.RegionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * SQLite-backed region storage with a PER-REGION row schema, so a single {@code /rg flag} edit
 * upserts one row instead of rewriting the whole world.
 *
 * <pre>
 *   CREATE TABLE wgn_regions (
 *     world TEXT, region_id TEXT, payload TEXT, updated_at INTEGER,
 *     PRIMARY KEY (world, region_id));
 * </pre>
 * Each {@code payload} is the JSON for one region (the per-world {@code __global__} row holds the
 * global-flags document). Region payloads are byte-compatible with one entry of the old whole-world
 * document, so data migrates losslessly.
 *
 * <p><b>Migration.</b> Servers from an older build have a {@code world_regions(world, payload)} blob
 * table. On first load of a world whose per-region rows are empty, the legacy blob is read, loaded,
 * and re-persisted per-region. The old table is left untouched as a backup.
 *
 * <p>A single long-lived connection is reused (SQLite connections are not thread-safe → all calls
 * come from the server thread). Falls back to {@link JsonRegionStorage} if {@code org.sqlite.JDBC}
 * isn't on the classpath.
 */
public final class SqliteRegionStorage implements RegionStorage, AutoCloseable {

    private static final Gson   GSON       = new GsonBuilder().disableHtmlEscaping().create();
    /** Per-world row id holding the global region's flags. */
    private static final String GLOBAL_KEY = "__global__";

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
            st.executeUpdate("CREATE TABLE IF NOT EXISTS wgn_regions ("
                    + "world TEXT NOT NULL, region_id TEXT NOT NULL, payload TEXT NOT NULL, "
                    + "updated_at INTEGER NOT NULL, PRIMARY KEY (world, region_id))");
        }
    }

    @Override
    public void load(String worldKey, RegionManager into) throws IOException {
        if (!driverPresent) { fallback.load(worldKey, into); return; }
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT region_id, payload FROM wgn_regions WHERE world = ?")) {
            ps.setString(1, worldKey);
            boolean any = false;
            Map<String, String> parents = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    any = true;
                    String rid = rs.getString(1), payload = rs.getString(2);
                    if (rid != null && rid.contains(".corrupt-")) continue; // skip quarantine rows
                    try {
                        JsonObject o = JsonParser.parseString(payload).getAsJsonObject();
                        if (GLOBAL_KEY.equals(rid)) RegionJsonCodec.applyGlobalJson(o, into);
                        else { var r = RegionJsonCodec.readRegion(rid, o, parents); if (r != null) into.add(r); }
                    } catch (JsonParseException | IllegalStateException ex) {
                        quarantineCorrupt(worldKey, rid, payload);
                        WorldGuardNeo.LOGGER.error("SQLite payload for region '{}/{}' is malformed — "
                                + "quarantined and skipped.", worldKey, rid, ex);
                    }
                }
            }
            RegionJsonCodec.linkParents(parents, into);
            if (!any) migrateLegacyWorld(worldKey, into);
        } catch (SQLException ex) {
            throw new IOException("SQLite load failed for " + worldKey, ex);
        }
    }

    /**
     * One-time per-world migration: if the legacy {@code world_regions} blob table holds this
     * world, load it whole and re-persist it per-region. The legacy table is left intact as a
     * backup. No-op if the legacy table doesn't exist (fresh install).
     */
    private void migrateLegacyWorld(String worldKey, RegionManager into) {
        String blob = null;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT payload FROM world_regions WHERE world = ?")) {
            ps.setString(1, worldKey);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) blob = rs.getString(1); }
        } catch (SQLException noLegacy) {
            return; // legacy table absent → nothing to migrate
        }
        if (blob == null) return;
        try {
            RegionJsonCodec.applyJson(JsonParser.parseString(blob).getAsJsonObject(), into);
            save(worldKey, into); // write per-region rows
            WorldGuardNeo.LOGGER.info("[WorldGuardNeo] Migrated world '{}' from legacy blob to per-region SQLite rows.", worldKey);
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error("[WorldGuardNeo] Legacy SQLite migration failed for '{}' — leaving manager empty.", worldKey, ex);
        }
    }

    @Override
    public void save(String worldKey, RegionManager from) throws IOException {
        if (!driverPresent) { fallback.save(worldKey, from); return; }
        // Full sync in one transaction: replace all of this world's rows with the current set.
        try {
            conn().setAutoCommit(false);
            try {
                try (PreparedStatement del = conn().prepareStatement(
                        "DELETE FROM wgn_regions WHERE world = ? AND region_id NOT LIKE '%.corrupt-%'")) {
                    del.setString(1, worldKey);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn().prepareStatement(
                        "INSERT INTO wgn_regions (world, region_id, payload, updated_at) VALUES (?, ?, ?, ?)")) {
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
            throw new IOException("SQLite save failed for " + worldKey, ex);
        }
    }

    @Override
    public void saveRegion(String worldKey, RegionManager from, String regionId) throws IOException {
        if (!driverPresent) { fallback.saveRegion(worldKey, from, regionId); return; }
        ProtectedRegion r = GLOBAL_KEY.equals(regionId) ? from.globalRegion() : from.get(regionId).orElse(null);
        if (r == null) { deleteRegion(worldKey, from, regionId); return; } // vanished → delete its row
        String payload = GSON.toJson(GLOBAL_KEY.equals(regionId)
                ? RegionJsonCodec.globalToJson(r) : RegionJsonCodec.regionToJson(r));
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO wgn_regions (world, region_id, payload, updated_at) VALUES (?, ?, ?, ?) "
              + "ON CONFLICT(world, region_id) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at")) {
            ps.setString(1, worldKey); ps.setString(2, regionId);
            ps.setString(3, payload); ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException("SQLite saveRegion failed for " + worldKey + "/" + regionId, ex);
        }
    }

    @Override
    public void deleteRegion(String worldKey, RegionManager from, String regionId) throws IOException {
        if (!driverPresent) { fallback.deleteRegion(worldKey, from, regionId); return; }
        try (PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM wgn_regions WHERE world = ? AND region_id = ?")) {
            ps.setString(1, worldKey); ps.setString(2, regionId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException("SQLite deleteRegion failed for " + worldKey + "/" + regionId, ex);
        }
    }

    /** Preserve a corrupt per-region payload under a timestamped quarantine row id. */
    private void quarantineCorrupt(String worldKey, String regionId, String payload) {
        String qkey = regionId + ".corrupt-" + System.currentTimeMillis();
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO wgn_regions (world, region_id, payload, updated_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, worldKey); ps.setString(2, qkey);
            ps.setString(3, payload); ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException qe) {
            WorldGuardNeo.LOGGER.error("Failed to quarantine corrupt SQLite payload for '{}/{}'", worldKey, regionId, qe);
        }
    }

    @Override
    public void prepareForBackup() {
        // Checkpoint the WAL into the main .sqlite file so a plain file-copy backup captures all
        // committed data. Without this, recent commits live only in the -wal sidecar (which the
        // copy omits), so the snapshot could be stale or internally inconsistent. TRUNCATE also
        // resets the -wal file. Best-effort: never propagate failures into the backup path.
        if (!driverPresent) return;
        try (Statement s = conn().createStatement()) {
            s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException ex) {
            WorldGuardNeo.LOGGER.debug("SQLite WAL checkpoint before backup failed", ex);
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
