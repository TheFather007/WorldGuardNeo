package dev.thefather007.worldguardneo.storage;

import dev.thefather007.worldguardneo.WorldGuardNeo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * SQLite-backed region storage (per-region row schema — see {@link AbstractJdbcRegionStorage}).
 *
 * <p>A single long-lived connection is reused (SQLite connections aren't thread-safe → server-thread
 * only). Falls back to {@link JsonRegionStorage} if {@code org.sqlite.JDBC} is absent.
 */
public final class SqliteRegionStorage extends AbstractJdbcRegionStorage {

    private final Path     dbFile;
    private final Class<?> driverClass;

    public SqliteRegionStorage(Path baseDir) {
        super(new JsonRegionStorage(baseDir));
        this.dbFile      = baseDir.resolve("regions.sqlite");
        this.driverClass = JdbcSupport.findDriverClass("org.sqlite.JDBC");
        if (driverClass == null) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] sqlite-jdbc not found on any classloader; SQLite storage will defer to JSON.");
        }
        boolean present = driverClass != null;
        try {
            Files.createDirectories(baseDir);
            if (present) { initSchema(); ensureSchemaVersion(); }
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error("[WorldGuardNeo] SQLite init failed — falling back to JSON storage", ex);
            present = false; // demote to JSON; further calls go to the fallback
        }
        this.usable = present;
        if (present) JdbcSupport.logDriver("SQLite", driverClass);
    }

    @Override protected String backendName() { return "SQLite"; }
    @Override protected String table()       { return "wgn_regions"; }
    @Override protected String legacyTable() { return "world_regions"; }
    // Checkpoint the WAL into the main .sqlite file so a file-copy backup captures all committed data.
    @Override protected String backupSql()   { return "PRAGMA wal_checkpoint(TRUNCATE)"; }

    @Override protected String upsertSql() {
        return "INSERT INTO wgn_regions (world, region_id, payload, updated_at) VALUES (?, ?, ?, ?) "
             + "ON CONFLICT(world, region_id) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at";
    }

    @Override
    protected Connection openConnection() throws SQLException {
        // Bypass DriverManager (see JdbcSupport) for classloader robustness.
        Connection c = JdbcSupport.connect(driverClass, "jdbc:sqlite:" + dbFile.toAbsolutePath(), new Properties());
        // Sensible defaults for a desktop-class server using SQLite.
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous = NORMAL");
        }
        return c;
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS wgn_regions ("
                    + "world TEXT NOT NULL, region_id TEXT NOT NULL, payload TEXT NOT NULL, "
                    + "updated_at INTEGER NOT NULL, PRIMARY KEY (world, region_id))");
        }
    }
}
