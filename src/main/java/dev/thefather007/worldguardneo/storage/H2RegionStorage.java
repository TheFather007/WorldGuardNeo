package dev.thefather007.worldguardneo.storage;

import dev.thefather007.worldguardneo.WorldGuardNeo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * H2-backed region storage (embedded file database, per-region row schema — see
 * {@link AbstractJdbcRegionStorage}).
 *
 * <p>The H2 driver ({@code org.h2.Driver}) is NOT bundled but ships with LuckPerms, so usually
 * present. If missing, defers to {@link JsonRegionStorage}.
 */
public final class H2RegionStorage extends AbstractJdbcRegionStorage {

    private final Path     dbFile;   // base path; H2 appends .mv.db
    private final Class<?> driverClass;

    public H2RegionStorage(Path baseDir) {
        super(new JsonRegionStorage(baseDir));
        this.dbFile      = baseDir.resolve("regions_h2");
        this.driverClass = JdbcSupport.findDriverClass("org.h2.Driver");
        if (driverClass == null) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] H2 driver (org.h2.Driver) not found on any classloader; "
                    + "H2 storage will defer to JSON. Add an H2 jar to the server to use it.");
        }
        boolean present = driverClass != null;
        try {
            Files.createDirectories(baseDir);
            if (present) { initSchema(); ensureSchemaVersion(); }
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error("[WorldGuardNeo] H2 init failed — falling back to JSON storage", ex);
            present = false;
        }
        this.usable = present;
        if (present) JdbcSupport.logDriver("H2", driverClass);
    }

    @Override protected String backendName() { return "H2"; }
    @Override protected String table()       { return "wgn_regions"; }
    @Override protected String legacyTable() { return "world_regions"; }
    // Flush H2's MVStore to the main .mv.db file so a file-copy backup is consistent.
    @Override protected String backupSql()   { return "CHECKPOINT SYNC"; }

    @Override protected String upsertSql() {
        return "MERGE INTO wgn_regions (world, region_id, payload, updated_at) KEY(world, region_id) VALUES (?, ?, ?, ?)";
    }

    @Override
    protected Connection openConnection() throws SQLException {
        String url = "jdbc:h2:file:" + dbFile.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
        return JdbcSupport.connect(driverClass, url, new Properties());
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS wgn_regions ("
                    + "world VARCHAR(255) NOT NULL, region_id VARCHAR(255) NOT NULL, payload CLOB NOT NULL, "
                    + "updated_at BIGINT NOT NULL, PRIMARY KEY (world, region_id))");
        }
    }
}
