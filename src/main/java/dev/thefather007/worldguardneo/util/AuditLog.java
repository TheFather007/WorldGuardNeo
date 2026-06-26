package dev.thefather007.worldguardneo.util;

import net.minecraft.commands.CommandSourceStack;

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
 * Asynchronous audit log for administrative region changes (create/redefine/delete/transfer,
 * flag/priority/parent edits, owner/member changes) to {@code logs/worldguardneo-audit.log}.
 * Separate from the violation log so the audit trail stays reviewable.
 *
 * <p>Same async single-writer / bounded-queue / best-effort design as {@link ViolationLog}: the
 * game thread hands over a pre-formatted line, so a slow disk never stalls the tick.
 */
public final class AuditLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(2048);
    private final Thread worker;
    private final Path file;
    private volatile boolean running = true;
    private volatile boolean warnedOnce = false;

    public AuditLog(Path logsDir) {
        this.file = logsDir.resolve("worldguardneo-audit.log");
        try { Files.createDirectories(logsDir); } catch (IOException ignored) { }
        this.worker = new Thread(this::drainLoop, "WGN-AuditLog");
        this.worker.setDaemon(true);
        this.worker.start();
        offer("=== WorldGuardNeo audit log started " + TS.format(LocalDateTime.now()) + " ===");
    }

    /**
     * Record an administrative change.
     *
     * @param src    the command source performing the change (player name or "CONSOLE")
     * @param action short action key, e.g. "claim", "remove", "flag", "transfer"
     * @param region the region id affected
     * @param detail extra context (flag=value, target player, etc.); may be null
     */
    public void record(CommandSourceStack src, String action, String region, String detail) {
        if (!running) return;
        String actor;
        var p = src.getPlayer();
        actor = p != null ? p.getGameProfile().getName() + " (" + p.getUUID() + ")" : "CONSOLE";
        String line = TS.format(LocalDateTime.now())
                + " [" + action + "] " + actor
                + " region=" + region
                + (detail != null ? " " + detail : "");
        queue.offer(line); // never blocks the game thread; drops if saturated
    }

    /**
     * Read the most recent up-to-{@code limit} audit lines for a region, oldest-first. Streams the
     * file with a fixed-size ring buffer so a large log stays memory-bounded. Best-effort: entries
     * still queued (not yet flushed, &lt;1s) won't appear, and an I/O error yields an empty list.
     */
    public java.util.List<String> recent(String region, int limit) {
        if (limit <= 0 || !Files.exists(file)) return java.util.List.of();
        String needle = "region=" + region;
        java.util.ArrayDeque<String> ring = new java.util.ArrayDeque<>(limit);
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(l -> {
                int idx = l.indexOf(needle);
                // Match "region=<id>" as a whole token: space (or start) on the left, space/EOL on the right.
                if (idx >= 0 && (idx == 0 || l.charAt(idx - 1) == ' ')) {
                    int end = idx + needle.length();
                    if (end == l.length() || l.charAt(end) == ' ') {
                        if (ring.size() == limit) ring.pollFirst();
                        ring.addLast(l);
                    }
                }
            });
        } catch (IOException e) {
            return java.util.List.of();
        }
        return new java.util.ArrayList<>(ring);
    }

    private void offer(String line) { if (running) queue.offer(line); }

    private void drainLoop() {
        try (Writer w = new OutputStreamWriter(
                Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                StandardCharsets.UTF_8)) {
            while (running || !queue.isEmpty()) {
                String line = queue.poll(1, TimeUnit.SECONDS);
                if (line == null) continue;
                w.write(line);
                w.write(System.lineSeparator());
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
                        "[WorldGuardNeo] Could not write audit log ({}); changes will not be recorded to file.",
                        file, e);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Flush and stop the writer thread on server shutdown. */
    public void close() {
        running = false;
        try {
            worker.join(3000);
            if (worker.isAlive()) { worker.interrupt(); worker.join(1000); }
        } catch (InterruptedException ie) {
            worker.interrupt();
            Thread.currentThread().interrupt();
        }
    }
}
