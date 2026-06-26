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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared JDBC region-storage logic for the SQLite, H2 and MySQL backends, which use an identical
 * PER-REGION row schema:
 * <pre>
 *   (world, region_id, payload, updated_at), PRIMARY KEY (world, region_id)
 * </pre>
 * Each {@code payload} is the JSON for one region; the per-world {@code __global__} row holds the
 * global-flags document. A single {@code /rg flag} edit upserts one row instead of rewriting the
 * whole world. Malformed payloads are quarantined under a timestamped {@code <id>.corrupt-<ts>} row
 * rather than aborting the load.
 *
 * <p>Subclasses provide only the dialect specifics: the connection, table name, upsert SQL, the
 * legacy migration source and (optionally) a backup checkpoint. Everything below — load, full save,
 * incremental save/delete, legacy migration, quarantine and connection lifecycle — is shared.
 *
 * <p>One long-lived connection is reused (server-thread only). When the backend is unusable (driver
 * absent or init failed) every operation defers to a {@link JsonRegionStorage} fallback.
 */
abstract class AbstractJdbcRegionStorage implements RegionStorage, AutoCloseable {

    protected static final Gson   GSON       = new GsonBuilder().disableHtmlEscaping().create();
    /** Per-world row id holding the global region's flags. */
    protected static final String GLOBAL_KEY = "__global__";

    /** Current on-disk schema version. Bump and add a migration branch in {@link #ensureSchemaVersion}
     *  whenever the persisted shape changes, so old databases upgrade cleanly instead of by heuristic. */
    public static final int SCHEMA_VERSION = 1;

    protected final RegionStorage fallback;
    /** Set by the subclass constructor once the driver is present and the schema is initialised. */
    protected boolean             usable;
    /** Schema version read/written at init; -1 until {@link #ensureSchemaVersion} runs (or fallback). */
    protected int                 schemaVersion = -1;
    /** Shared connection. Lazily opened on first use; closed at server stop via {@link #close()}. */
    protected Connection          conn;

    protected AbstractJdbcRegionStorage(RegionStorage fallback) {
        this.fallback = fallback;
    }

    /* ---------------- schema versioning ---------------- */

    /** {@code <table>_meta} key/value table holding the schema version (and room for future markers). */
    private String metaTable() { return table() + "_meta"; }

    /**
     * Ensure the meta table exists and carries a schema version. On a fresh DB it stamps
     * {@link #SCHEMA_VERSION}; on an existing one it reads (and, in future, migrates) it. Call from the
     * subclass constructor right after {@code initSchema()}. Best-effort: a failure here just leaves
     * {@link #schemaVersion} at -1 and is logged — it never blocks region loading.
     */
    protected final void ensureSchemaVersion() {
        try {
            try (Statement st = conn().createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + metaTable()
                        + " (mkey VARCHAR(64) NOT NULL, mvalue VARCHAR(255) NOT NULL, PRIMARY KEY (mkey))");
            }
            Integer found = null;
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT mvalue FROM " + metaTable() + " WHERE mkey = 'schema_version'");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { try { found = Integer.parseInt(rs.getString(1)); } catch (NumberFormatException ignored) {} }
            }
            if (found == null) {
                try (PreparedStatement ps = conn().prepareStatement(
                        "INSERT INTO " + metaTable() + " (mkey, mvalue) VALUES ('schema_version', ?)")) {
                    ps.setString(1, String.valueOf(SCHEMA_VERSION));
                    ps.executeUpdate();
                }
                schemaVersion = SCHEMA_VERSION;
            } else {
                schemaVersion = found;
                // Future: if (found < SCHEMA_VERSION) { run ordered migrations; bump the stored value. }
                if (found > SCHEMA_VERSION) {
                    WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] {} storage schema is v{} but this build expects v{} "
                            + "— it was written by a newer version; proceeding read-as-is.",
                            backendName(), found, SCHEMA_VERSION);
                }
            }
        } catch (SQLException ex) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] {} schema-version check failed (continuing).", backendName(), ex);
        }
    }

    /** The schema version recorded on disk, or -1 if unknown/unusable (for diagnostics). */
    public int schemaVersion() { return schemaVersion; }

    /* ---------------- dialect hooks ---------------- */

    /** Human-readable backend name for log/error messages (e.g. "SQLite"). */
    protected abstract String backendName();

    /** The per-region table name. */
    protected abstract String table();

    /** Open a fresh connection (URL + driver + any post-connect setup such as PRAGMAs). */
    protected abstract Connection openConnection() throws SQLException;

    /** Dialect upsert into {@link #table()} binding (world, region_id, payload, updated_at) in order. */
    protected abstract String upsertSql();

    /** Name of a legacy whole-world blob table to migrate from on first load, or {@code null} if none. */
    protected abstract String legacyTable();

    /** SQL run before a file-copy backup to flush the journal, or {@code null} for a no-op (e.g. MySQL). */
    protected String backupSql() { return null; }

    /** Whether an existing connection is still healthy; default trusts {@code !isClosed()} (checked in
     *  {@link #conn()}). MySQL overrides to add a round-trip {@code isValid} probe. */
    protected boolean validate(Connection c) throws SQLException { return true; }

    /* ---------------- connection lifecycle ---------------- */

    protected final Connection conn() throws SQLException {
        if (conn == null || conn.isClosed() || !validate(conn)) {
            if (conn != null) { try { conn.close(); } catch (SQLException ignored) {} }
            conn = openConnection();
        }
        return conn;
    }

    /* ---------------- shared RegionStorage implementation ---------------- */

    @Override
    public void load(String worldKey, RegionManager into) throws IOException {
        if (!usable) { fallback.load(worldKey, into); return; }
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT region_id, payload FROM " + table() + " WHERE world = ?")) {
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
                        WorldGuardNeo.LOGGER.error("{} payload for region '{}/{}' is malformed — "
                                + "quarantined and skipped.", backendName(), worldKey, rid, ex);
                    }
                }
            }
            RegionJsonCodec.linkParents(parents, into);
            if (!any && legacyTable() != null) migrateLegacyWorld(worldKey, into);
        } catch (SQLException ex) {
            throw new IOException(backendName() + " load failed for " + worldKey, ex);
        }
    }

    /** One-time per-world migration from the legacy whole-world blob table (kept as a backup). */
    private void migrateLegacyWorld(String worldKey, RegionManager into) {
        String blob = null;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT payload FROM " + legacyTable() + " WHERE world = ?")) {
            ps.setString(1, worldKey);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) blob = rs.getString(1); }
        } catch (SQLException noLegacy) {
            return; // legacy table absent → nothing to migrate
        }
        if (blob == null) return;
        try {
            RegionJsonCodec.applyJson(JsonParser.parseString(blob).getAsJsonObject(), into);
            save(worldKey, into); // write per-region rows
            WorldGuardNeo.LOGGER.info("[WorldGuardNeo] Migrated world '{}' from legacy blob to per-region {} rows.",
                    worldKey, backendName());
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error("[WorldGuardNeo] Legacy {} migration failed for '{}' — leaving manager empty.",
                    backendName(), worldKey, ex);
        }
    }

    // The sync methods delegate to the two-phase prepare* (serialize now, run the I/O immediately).
    @Override public void save(String worldKey, RegionManager from) throws IOException {
        prepareSave(worldKey, from).run();
    }
    @Override public void saveRegion(String worldKey, RegionManager from, String regionId) throws IOException {
        prepareSaveRegion(worldKey, from, regionId).run();
    }
    @Override public void deleteRegion(String worldKey, RegionManager from, String regionId) throws IOException {
        prepareDeleteRegion(worldKey, from, regionId).run();
    }

    @Override
    public IoTask prepareSave(String worldKey, RegionManager from) {
        if (!usable) return fallback.prepareSave(worldKey, from);
        // Serialize every region NOW (server thread, where the manager is safely readable).
        List<String[]> rows = new ArrayList<>();
        for (ProtectedRegion r : from.all()) {
            rows.add(new String[]{ r.id(), GSON.toJson(RegionJsonCodec.regionToJson(r)) });
        }
        rows.add(new String[]{ GLOBAL_KEY, GSON.toJson(RegionJsonCodec.globalToJson(from.globalRegion())) });
        return () -> writeFullReplace(worldKey, rows);
    }

    @Override
    public IoTask prepareSaveRegion(String worldKey, RegionManager from, String regionId) {
        if (!usable) return fallback.prepareSaveRegion(worldKey, from, regionId);
        ProtectedRegion r = GLOBAL_KEY.equals(regionId) ? from.globalRegion() : from.get(regionId).orElse(null);
        if (r == null) return prepareDeleteRegion(worldKey, from, regionId); // vanished → delete its row
        String payload = GSON.toJson(GLOBAL_KEY.equals(regionId)
                ? RegionJsonCodec.globalToJson(r) : RegionJsonCodec.regionToJson(r));
        return () -> writeUpsert(worldKey, regionId, payload);
    }

    @Override
    public IoTask prepareDeleteRegion(String worldKey, RegionManager from, String regionId) {
        if (!usable) return fallback.prepareDeleteRegion(worldKey, from, regionId);
        return () -> writeDelete(worldKey, regionId);
    }

    /* ---------------- blocking I/O (run on the write-behind worker) ---------------- */

    private void writeFullReplace(String worldKey, List<String[]> rows) throws IOException {
        // Full sync in one transaction: replace all of this world's (non-quarantine) rows.
        try {
            // Resolve the connection ONCE and reuse it for the whole transaction — re-calling conn()
            // could reopen a different handle mid-txn (e.g. MySQL validity reconnect).
            Connection c = conn();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM " + table() + " WHERE world = ? AND region_id NOT LIKE '%.corrupt-%'")) {
                    del.setString(1, worldKey);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO " + table() + " (world, region_id, payload, updated_at) VALUES (?, ?, ?, ?)")) {
                    long now = System.currentTimeMillis();
                    for (String[] row : rows) {
                        ins.setString(1, worldKey); ins.setString(2, row[0]);
                        ins.setString(3, row[1]); ins.setLong(4, now);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                c.commit();
            } catch (SQLException ex) {
                try { c.rollback(); } catch (SQLException re) { ex.addSuppressed(re); }
                throw ex;
            } finally {
                // Never let restoring autoCommit throw out of finally and mask the real failure.
                try { c.setAutoCommit(true); }
                catch (SQLException re) {
                    WorldGuardNeo.LOGGER.debug("{} setAutoCommit(true) after save txn failed", backendName(), re);
                }
            }
        } catch (SQLException ex) {
            throw new IOException(backendName() + " save failed for " + worldKey, ex);
        }
    }

    private void writeUpsert(String worldKey, String regionId, String payload) throws IOException {
        try (PreparedStatement ps = conn().prepareStatement(upsertSql())) {
            ps.setString(1, worldKey); ps.setString(2, regionId);
            ps.setString(3, payload); ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException(backendName() + " saveRegion failed for " + worldKey + "/" + regionId, ex);
        }
    }

    private void writeDelete(String worldKey, String regionId) throws IOException {
        try (PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM " + table() + " WHERE world = ? AND region_id = ?")) {
            ps.setString(1, worldKey); ps.setString(2, regionId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException(backendName() + " deleteRegion failed for " + worldKey + "/" + regionId, ex);
        }
    }

    /** Preserve a corrupt per-region payload under a timestamped quarantine row id (unique, so the
     *  upsert never actually conflicts). */
    protected void quarantineCorrupt(String worldKey, String regionId, String payload) {
        String qkey = regionId + ".corrupt-" + System.currentTimeMillis();
        try (PreparedStatement ps = conn().prepareStatement(upsertSql())) {
            ps.setString(1, worldKey); ps.setString(2, qkey);
            ps.setString(3, payload); ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException qe) {
            WorldGuardNeo.LOGGER.error("Failed to quarantine corrupt {} payload for '{}/{}'",
                    backendName(), worldKey, regionId, qe);
        }
    }

    @Override
    public void prepareForBackup() {
        if (!usable) return;
        String sql = backupSql();
        if (sql == null) return;
        try (Statement s = conn().createStatement()) {
            s.execute(sql);
        } catch (SQLException ex) {
            WorldGuardNeo.LOGGER.debug("{} checkpoint before backup failed", backendName(), ex);
        }
    }

    @Override
    public void close() {
        if (conn != null) {
            try { conn.close(); }
            catch (SQLException ex) {
                WorldGuardNeo.LOGGER.warn("Failed to close {} connection", backendName(), ex);
            }
        }
    }
}
