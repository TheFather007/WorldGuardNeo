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
 * Dedicated, asynchronous logger for protection violations (a player trying to break, place,
 * or interact in a region where they lack permission).
 *
 * <p>Why a separate log: previously every denied action produced noise on the server console
 * (either our own logging or vanilla's "Mismatch in destroy block pos" warnings). On a busy
 * server that buries genuinely important messages. Violations are routine gameplay events, not
 * errors, so they belong in their own file — {@code logs/worldguardneo-violations.log} — where
 * admins can audit griefing attempts without scrolling past them constantly.
 *
 * <p>Design notes:
 * <ul>
 *   <li><b>Async single-writer.</b> A daemon thread drains a bounded queue and appends lines.
 *       Game-thread callers never touch disk — they just offer a pre-formatted string, so a
 *       slow disk can't stall the server tick.</li>
 *   <li><b>Bounded queue, drop-on-full.</b> If something floods violations faster than we can
 *       write (e.g. an auto-clicker in a denied region), we drop extras rather than grow the
 *       queue unboundedly. The action is still blocked in-game; only the log line is skipped.</li>
 *   <li><b>Best-effort.</b> Any I/O failure is swallowed after a single warning — logging must
 *       never crash or degrade the server.</li>
 * </ul>
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
            // Directory likely exists; createDirectories is a no-op then. Real failures surface
            // on first write and are handled there.
        }
        // Rotate the previous session's log out of the way, mirroring how Minecraft archives
        // latest.log into dated files. The current session always writes a fresh
        // worldguardneo-violations.log; the prior one is renamed to
        // worldguardneo-violations-<date>-<n>.log so history is preserved but not appended to.
        rotateExisting(logsDir);
        this.worker = new Thread(this::drainLoop, "WGN-ViolationLog");
        this.worker.setDaemon(true);
        this.worker.start();
        // Emit a startup marker so the file exists immediately (confirms the logger is live
        // even before the first violation) and admins can see when the session began.
        record0("=== WorldGuardNeo violation log started "
                + TS.format(LocalDateTime.now()) + " ===");
    }

    /**
     * If a violations log from a previous run exists, rename it to a dated archive so the new
     * session starts clean. Picks a non-colliding suffix ({@code -YYYY-MM-DD-N.log}). Best
     * effort: if rotation fails for any reason we fall back to appending to the existing file
     * (the old behaviour), which is safe — we never lose data.
     */
    private void rotateExisting(Path logsDir) {
        try {
            if (!Files.exists(file)) return;
            // Use the file's last-modified date for the archive name (when the old session ran).
            String date;
            try {
                var ft = Files.getLastModifiedTime(file).toInstant()
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                date = ft.toString(); // YYYY-MM-DD
            } catch (Exception ex) {
                date = java.time.LocalDate.now().toString();
            }
            for (int n = 1; n < 1000; n++) {
                Path archive = logsDir.resolve("worldguardneo-violations-" + date + "-" + n + ".log.gz");
                if (!Files.exists(archive)) {
                    // Gzip the old log into the archive (matches Minecraft's *.log.gz convention),
                    // then remove the plaintext original so the new session starts fresh.
                    try (var in = Files.newInputStream(file);
                         var gz = new java.util.zip.GZIPOutputStream(Files.newOutputStream(archive))) {
                        in.transferTo(gz);
                    }
                    Files.delete(file);
                    return;
                }
            }
            // 999 archives for one day is absurd — just delete-and-restart rather than grow forever.
        } catch (IOException ex) {
            // Couldn't rotate (file locked, permissions). The writer will append to the existing
            // file instead — no data lost, just not split per-session.
            dev.thefather007.worldguardneo.WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Could not rotate violation log; appending instead.", ex);
        }
    }

    /** Internal: enqueue a raw pre-formatted line. */
    private void record0(String line) {
        if (running) queue.offer(line);
    }

    /**
     * Record a violation. Called from the game thread; returns immediately. The line is
     * formatted here (cheap) and handed to the writer thread.
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
        // offer() — never block the game thread. Drop if the queue is saturated.
        queue.offer(line);
    }

    private void drainLoop() {
        // Open in append mode so restarts keep history. Buffered writer flushed each batch.
        try (Writer w = new OutputStreamWriter(
                Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                StandardCharsets.UTF_8)) {
            while (running || !queue.isEmpty()) {
                String line = queue.poll(1, TimeUnit.SECONDS);
                if (line == null) continue;
                w.write(line);
                w.write(System.lineSeparator());
                // Drain any backlog before flushing, so bursts cost one flush, not N.
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
        // Do NOT interrupt immediately: an interrupt lands inside queue.poll() and aborts the
        // drain loop via its InterruptedException handler, dropping every line still queued.
        // The loop exits on its own once running=false and the queue is empty (poll has a 1s
        // timeout), so a plain join lets the backlog flush. Interrupt only as a last resort
        // if the writer is genuinely stuck (e.g. disk hang).
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
