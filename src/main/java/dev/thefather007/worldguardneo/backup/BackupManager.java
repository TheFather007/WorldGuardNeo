package dev.thefather007.worldguardneo.backup;

import dev.thefather007.worldguardneo.WorldGuardNeo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * Async, rotating backups of the region files.
 *
 * <p>Backups are full copies of {@code worldguardneo/regions/}, written into
 * {@code worldguardneo/backups/<timestamp>/} as gzipped region files. Rotation
 * keeps only the newest N directories; older ones are removed.
 *
 * <p>All disk I/O runs on a single-threaded executor — never on the server tick
 * thread. The server tick only flips a "is it time?" check via {@link #tick}.
 * Concurrent backup attempts are guarded by an {@code in-flight} atomic flag;
 * if a previous backup is still running when the next one is due, the new one
 * is dropped (logged at info-level) rather than corrupting the snapshot.
 *
 * <p>The executor is a daemon thread so it doesn't prevent JVM shutdown.
 */
public final class BackupManager implements AutoCloseable {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Path dataDir;
    private final Path regionsDir;
    private final Path backupsDir;

    private final ExecutorService executor;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    /** Last server tick at which a backup was started. Used by the scheduler. */
    private final AtomicLong lastRunTick = new AtomicLong(0L);

    /**
     * @param dataDir the mod's data root (config/worldguardneo). Regions are read from
     *                {@code dataDir/regions} and backups written to {@code dataDir/backups}.
     */
    public BackupManager(Path dataDir) {
        this.dataDir    = dataDir;
        this.regionsDir = dataDir.resolve("regions");
        this.backupsDir = dataDir.resolve("backups");
        // Single-thread daemon executor. Serial execution avoids concurrent
        // backup attempts colliding on the filesystem.
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            private int count = 0;
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "WorldGuardNeo-backup-" + (++count));
                t.setDaemon(true);
                // Below normal so the backup never starves the server tick on shared cores.
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });
    }

    /**
     * Server-tick scheduler. Submits an async backup task if the configured interval
     * has elapsed since the last run. Cheap — the common case (interval not yet up,
     * or already running) is a single AtomicLong read.
     *
     * @param currentTick current server tick
     * @param intervalMinutes minutes between backups; if {@code <= 0}, scheduling is disabled
     */
    public void tick(long currentTick, int intervalMinutes) {
        if (intervalMinutes <= 0) return;
        long intervalTicks = (long) intervalMinutes * 60L * 20L;  // 20 ticks/sec
        long last = lastRunTick.get();
        if (last != 0L && currentTick - last < intervalTicks) return;
        // First backup runs ~intervalTicks after server start, not immediately at boot,
        // to avoid racing with world load. If lastRunTick is 0, seed it with current.
        if (last == 0L) {
            lastRunTick.set(currentTick);
            return;
        }
        lastRunTick.set(currentTick);
        runAsync(/* manualLabel = */ null);
    }

    /**
     * Trigger a backup immediately. Returns whether one was scheduled (false if another
     * is already running). The result is available asynchronously via the logger.
     *
     * @param label optional human-readable suffix appended to the backup directory name
     */
    public boolean runAsync(String label) {
        if (!inFlight.compareAndSet(false, true)) {
            WorldGuardNeo.LOGGER.info("[WorldGuardNeo] Backup already in progress; skipping new request.");
            return false;
        }
        // Checkpoint embedded DBs NOW, on the calling (server) thread, before the async file copy.
        // runAsync is only ever invoked from the server tick or the /rg backup command, both on the
        // server thread — the same thread that owns the single DB connection — so this is safe.
        // It flushes the SQLite WAL / H2 MVStore into the main file so the copy is consistent.
        try {
            var mod = WorldGuardNeo.get();
            if (mod != null && mod.regions() != null) mod.regions().storage().prepareForBackup();
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("[WorldGuardNeo] prepareForBackup failed", t);
        }
        executor.submit(() -> {
            try {
                doBackup(label);
            } catch (Throwable t) {
                // Catch Throwable so a single bad backup doesn't take down the executor.
                WorldGuardNeo.LOGGER.error("[WorldGuardNeo] Backup failed", t);
            } finally {
                inFlight.set(false);
            }
        });
        return true;
    }

    /** Returns the list of existing backup directory names, newest first. */
    public List<String> listBackups() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(backupsDir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupsDir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) out.add(p.getFileName().toString());
            }
        } catch (IOException e) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Failed to list backups", e);
        }
        // Reverse-sort by name. Since names are timestamps, lexicographic order matches time.
        out.sort(Comparator.reverseOrder());
        return out;
    }

    /* ---------------------- internals ---------------------- */

    private void doBackup(String label) throws IOException {
        // Region data may exist as JSON files in regions/ OR as an embedded database file
        // (regions.sqlite / regions_h2.mv.db) at the data root, depending on storage-format.
        boolean haveJsonDir = Files.isDirectory(regionsDir);
        boolean haveDbFiles = !collectDbFiles().isEmpty();
        if (!haveJsonDir && !haveDbFiles) {
            // Nothing to back up yet — server hasn't created any region data.
            return;
        }
        String stamp = LocalDateTime.now().format(STAMP);
        String baseName = (label == null || label.isEmpty()) ? stamp : stamp + "_" + sanitize(label);
        // Handle the (rare) case of two backups starting in the same second — append _N suffix
        // until we find an unused directory. Bounded loop to prevent infinite spinning on
        // weird FS errors.
        Path dest = backupsDir.resolve(baseName);
        for (int attempt = 2; Files.exists(dest) && attempt < 100; attempt++) {
            dest = backupsDir.resolve(baseName + "_" + attempt);
        }
        Files.createDirectories(dest);
        String dirName = dest.getFileName().toString();

        boolean compress = true;
        int retain = 10;
        try {
            var mod = WorldGuardNeo.get();
            if (mod != null) {
                compress = mod.config().global().backupCompress;
                retain   = Math.max(1, mod.config().global().backupRetainCount);
            }
        } catch (Throwable ignored) {}

        int copied = 0;
        if (haveJsonDir) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionsDir, "*.{json,sqlite}")) {
                for (Path src : stream) {
                    if (!Files.isRegularFile(src)) continue;
                    copyOne(src, dest, compress);
                    copied++;
                }
            }
        }
        // Embedded DB backends keep their data file at the DATA ROOT, not in regions/ —
        // SqliteRegionStorage writes <root>/regions.sqlite, H2RegionStorage <root>/regions_h2.mv.db.
        // Without this pass a sqlite/h2 server's backups silently contained no region data at all.
        // Best-effort: the file is copied while the DB connection is open, which is fine for a
        // disaster-recovery snapshot (both engines keep the main file consistent between commits,
        // and all writes happen on the server thread).
        for (Path src : collectDbFiles()) {
            copyOne(src, dest, compress);
            copied++;
        }

        WorldGuardNeo.LOGGER.info("[WorldGuardNeo] Backup '{}' written ({} files, compress={})",
                dirName, copied, compress);

        rotate(retain);
    }

    /** Embedded-database files living at the data root (sqlite / h2), present ones only. */
    private List<Path> collectDbFiles() {
        List<Path> out = new ArrayList<>(2);
        for (String name : new String[]{"regions.sqlite", "regions_h2.mv.db"}) {
            Path p = dataDir.resolve(name);
            if (Files.isRegularFile(p)) out.add(p);
        }
        return out;
    }

    /** Copy (optionally gzipping) one source file into the backup directory. */
    private static void copyOne(Path src, Path destDir, boolean compress) throws IOException {
        Path target = destDir.resolve(src.getFileName().toString() + (compress ? ".gz" : ""));
        if (compress) {
            // Gzip — typical compression ratio for JSON regions is 6-12x.
            try (OutputStream raw = Files.newOutputStream(target);
                 GZIPOutputStream gz = new GZIPOutputStream(raw)) {
                Files.copy(src, gz);
            }
        } else {
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Delete the oldest backup directories until at most {@code retain} remain.
     * Failures are logged but don't propagate — a stuck file shouldn't abort the rotation.
     */
    private void rotate(int retain) {
        List<String> all = listBackups(); // newest-first
        if (all.size() <= retain) return;
        for (int i = retain; i < all.size(); i++) {
            Path victim = backupsDir.resolve(all.get(i));
            try {
                deleteRecursive(victim);
                WorldGuardNeo.LOGGER.info("[WorldGuardNeo] Rotated out old backup '{}'", all.get(i));
            } catch (IOException e) {
                WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Failed to delete old backup '{}'", all.get(i), e);
            }
        }
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
                for (Path child : stream) deleteRecursive(child);
            }
        }
        Files.deleteIfExists(p);
    }

    /** Filesystem-safe label. Keep [a-zA-Z0-9_-], drop everything else. */
    private static String sanitize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-') {
                out.append(ch);
            }
        }
        return out.toString();
    }

    @Override
    public void close() {
        // Graceful shutdown — wait briefly for any in-flight backup to finish, then force.
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
