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
import java.util.Properties;

/**
 * H2-backed region storage (embedded file database) with a PER-REGION row schema:
 * <pre>
 *   CREATE TABLE wgn_regions (
 *     world VARCHAR(255), region_id VARCHAR(255), payload CLOB, updated_at BIGINT,
 *     PRIMARY KEY (world, region_id));
 * </pre>
 * A single {@code /rg flag} edit upserts one row instead of rewriting the whole world. The
 * per-world {@code __global__} row holds the global-flags document. Region payloads match one entry
 * of the old whole-world document, so the legacy {@code world_regions} blob table is migrated
 * losslessly on first load (and kept as a backup).
 *
 * <p>The H2 driver ({@code org.h2.Driver}) is NOT bundled but is shipped by LuckPerms, so it is
 * usually present. If missing, this backend transparently defers to {@link JsonRegionStorage}.
 */
public final class H2RegionStorage implements RegionStorage, AutoCloseable {

    private static final Gson   GSON       = new GsonBuilder().disableHtmlEscaping().create();
    private static final String GLOBAL_KEY = "__global__";

    private final Path          dbFile;   // base path; H2 appends .mv.db
    private final RegionStorage fallback;
    private final Class<?>      driverClass;
    private boolean             driverPresent;
    private Connection          conn;

    public H2RegionStorage(Path baseDir) {
        this.dbFile   = baseDir.resolve("regions_h2");
        this.fallback = new JsonRegionStorage(baseDir);
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
            String url = "jdbc:h2:file:" + dbFile.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
            conn = JdbcSupport.connect(driverClass, url, new Properties());
        }
        return conn;
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS wgn_regions ("
                    + "world VARCHAR(255) NOT NULL, region_id VARCHAR(255) NOT NULL, payload CLOB NOT NULL, "
                    + "updated_at BIGINT NOT NULL, PRIMARY KEY (world, region_id))");
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
                    if (rid != null && rid.contains(".corrupt-")) continue;
                    try {
                        JsonObject o = JsonParser.parseString(payload).getAsJsonObject();
                        if (GLOBAL_KEY.equals(rid)) RegionJsonCodec.applyGlobalJson(o, into);
                        else { var r = RegionJsonCodec.readRegion(rid, o, parents); if (r != null) into.add(r); }
                    } catch (JsonParseException | IllegalStateException ex) {
                        quarantineCorrupt(worldKey, rid, payload);
                        WorldGuardNeo.LOGGER.error("H2 payload for region '{}/{}' is malformed — "
                                + "quarantined and skipped.", worldKey, rid, ex);
                    }
                }
            }
            RegionJsonCodec.linkParents(parents, into);
            if (!any) migrateLegacyWorld(worldKey, into);
        } catch (SQLException ex) {
            throw new IOException("H2 load failed for " + worldKey, ex);
        }
    }

    /** One-time per-world migration from the legacy whole-world blob table (kept as a backup). */
    private void migrateLegacyWorld(String worldKey, RegionManager into) {
        String blob = null;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT payload FROM world_regions WHERE world = ?")) {
            ps.setString(1, worldKey);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) blob = rs.getString(1); }
        } catch (SQLException noLegacy) {
            return;
        }
        if (blob == null) return;
        try {
            RegionJsonCodec.applyJson(JsonParser.parseString(blob).getAsJsonObject(), into);
            save(worldKey, into);
            WorldGuardNeo.LOGGER.info("[WorldGuardNeo] Migrated world '{}' from legacy blob to per-region H2 rows.", worldKey);
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error("[WorldGuardNeo] Legacy H2 migration failed for '{}' — leaving manager empty.", worldKey, ex);
        }
    }

    @Override
    public void save(String worldKey, RegionManager from) throws IOException {
        if (!driverPresent) { fallback.save(worldKey, from); return; }
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
            throw new IOException("H2 save failed for " + worldKey, ex);
        }
    }

    @Override
    public void saveRegion(String worldKey, RegionManager from, String regionId) throws IOException {
        if (!driverPresent) { fallback.saveRegion(worldKey, from, regionId); return; }
        ProtectedRegion r = GLOBAL_KEY.equals(regionId) ? from.globalRegion() : from.get(regionId).orElse(null);
        if (r == null) { deleteRegion(worldKey, from, regionId); return; }
        String payload = GSON.toJson(GLOBAL_KEY.equals(regionId)
                ? RegionJsonCodec.globalToJson(r) : RegionJsonCodec.regionToJson(r));
        try (PreparedStatement ps = conn().prepareStatement(
                "MERGE INTO wgn_regions (world, region_id, payload, updated_at) KEY(world, region_id) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, worldKey); ps.setString(2, regionId);
            ps.setString(3, payload); ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException("H2 saveRegion failed for " + worldKey + "/" + regionId, ex);
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
            throw new IOException("H2 deleteRegion failed for " + worldKey + "/" + regionId, ex);
        }
    }

    /** Preserve a corrupt per-region payload under a timestamped quarantine row id. */
    private void quarantineCorrupt(String worldKey, String regionId, String payload) {
        String qkey = regionId + ".corrupt-" + System.currentTimeMillis();
        try (PreparedStatement ps = conn().prepareStatement(
                "MERGE INTO wgn_regions (world, region_id, payload, updated_at) KEY(world, region_id) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, worldKey); ps.setString(2, qkey);
            ps.setString(3, payload); ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException qe) {
            WorldGuardNeo.LOGGER.error("Failed to quarantine corrupt H2 payload for '{}/{}'", worldKey, regionId, qe);
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
