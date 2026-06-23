package dev.thefather007.worldguardneo.util;

import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous logger for protection violations (denied break/place/interact in a region), written
 * to {@code logs/worldguardneo-violations.log}. Kept in its own file so routine griefing attempts
 * don't bury important console messages.
 *
 * <p>Async single-writer: a daemon thread drains a bounded queue and appends lines; game-thread
 * callers only offer a pre-formatted string, so a slow disk can't stall the tick. The queue is
 * bounded and drops on overflow (the action is still blocked in-game; only the log line is skipped).
 * Best-effort: any I/O failure is swallowed after a single warning.
 */
public final class ViolationLog {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(4096);
    private final Thread worker;
    private final Path file;
    private volatile boolean running = true;
    private volatile boolean warnedOnce = false;

    public ViolationLog(Path logsDir) {
        this.file = logsDir.resolve("worldguardneo-violations.log");
        try {
            Files.createDirectories(logsDir);
        } catch (IOException ignored) {
            // Likely already exists; real failures surface on first write.
        }
        // Rotate the previous session's log out of the way (gzipped, dated) so this session writes
        // a fresh file, mirroring how Minecraft archives latest.log.
        rotateExisting(logsDir);
        this.worker = new Thread(this::drainLoop, "WGN-ViolationLog");
        this.worker.setDaemon(true);
        this.worker.start();
        // Startup marker so the file exists immediately and the session start is visible.
        record0("=== WorldGuardNeo violation log started "
                + TS.format(LocalDateTime.now()) + " ===");
    }

    /**
     * Gzip a prior run's violations log to a dated archive ({@code -YYYY-MM-DD-N.log.gz}) so the new
     * session starts clean. Best-effort: on failure we fall back to appending, never losing data.
     */
    private void rotateExisting(Path logsDir) {
        try {
            if (!Files.exists(file)) return;
            // Date the archive by the old file's last-modified (when that session ran).
            String date;
            try {
                var ft = Files.getLastModifiedTime(file).toInstant()
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                date = ft.toString();
            } catch (Exception ex) {
                date = java.time.LocalDate.now().toString();
            }
            for (int n = 1; n < 1000; n++) {
                Path archive = logsDir.resolve("worldguardneo-violations-" + date + "-" + n + ".log.gz");
                if (!Files.exists(archive)) {
                    try (var in = Files.newInputStream(file);
                         var gz = new java.util.zip.GZIPOutputStream(Files.newOutputStream(archive))) {
                        in.transferTo(gz);
                    }
                    Files.delete(file);
                    return;
                }
            }
            // 999 archives in one day — delete-and-restart rather than grow forever.
            Files.delete(file);
        } catch (IOException ex) {
            // Couldn't rotate (locked/permissions) — the writer appends to the existing file instead.
            dev.thefather007.worldguardneo.WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Could not rotate violation log; appending instead.", ex);
        }
    }

    /** Internal: enqueue a raw pre-formatted line. */
    private void record0(String line) {
        if (running) queue.offer(line);
    }

    /**
     * Record a violation. Called from the game thread; formats the line and hands it off, returns immediately.
     *
     * @param player the offending player
     * @param action short action key, e.g. "break", "place", "interact", "container"
     * @param detail extra context, e.g. block id or region id
     * @param x block X
     * @param y block Y
     * @param z block Z
     * @param world dimension id
     * @param region region id the action was denied in (may be null for world-config denials)
     */
    public void record(ServerPlayer player, String action, String detail,
                       int x, int y, int z, String world, String region) {
        if (!running) return;
        String line = TS.format(LocalDateTime.now())
                + " [" + action + "] "
                + player.getGameProfile().getName()
                + " (" + player.getUUID() + ")"
                + " @ " + world + " " + x + "," + y + "," + z
                + (region != null ? " region=" + region : "")
                + (detail != null ? " " + detail : "");
        queue.offer(line); // never blocks the game thread; drops if saturated
    }

    private void drainLoop() {
        try (Writer w = new OutputStreamWriter(
                Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                StandardCharsets.UTF_8)) {
            while (running || !queue.isEmpty()) {
                String line = queue.poll(1, TimeUnit.SECONDS);
                if (line == null) continue;
                w.write(line);
                w.write(System.lineSeparator());
                // Drain backlog before flushing so bursts cost one flush, not N.
                String more;
                while ((more = queue.poll()) != null) {
                    w.write(more);
                    w.write(System.lineSeparator());
                }
                w.flush();
            }
        } catch (IOException e) {
            if (!warnedOnce) {
                warnedOnce = true;
                dev.thefather007.worldguardneo.WorldGuardNeo.LOGGER.warn(
                        "[WorldGuardNeo] Could not write violation log ({}); violations will not be recorded to file.",
                        file, e);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Flush and stop the writer thread on server shutdown. */
    public void close() {
        running = false;
        // Don't interrupt immediately: an interrupt aborts the drain loop's queue.poll() and drops
        // queued lines. The loop exits on its own (running=false + empty queue, 1s poll timeout), so
        // a plain join flushes the backlog. Interrupt only as a last resort if the writer is stuck.
        try {
            worker.join(3000);
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(1000);
            }
        } catch (InterruptedException ie) {
            worker.interrupt();
            Thread.currentThread().interrupt();
        }
    }
}
