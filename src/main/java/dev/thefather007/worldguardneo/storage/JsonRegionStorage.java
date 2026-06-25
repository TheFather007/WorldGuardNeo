package dev.thefather007.worldguardneo.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.region.RegionManager;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Stores each world's regions in a JSON file under {@code config/worldguardneo/regions/<safeKey>.json}. */
public final class JsonRegionStorage implements RegionStorage {

    // Compact (not pretty-printed) JSON: ~30-50% smaller/faster. Hand-edit via `jq .` if needed.
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path baseDir;

    public JsonRegionStorage(Path baseDir) {
        this.baseDir = baseDir.resolve("regions");
        try { Files.createDirectories(this.baseDir); }
        catch (IOException e) { WorldGuardNeo.LOGGER.error("Failed to create region dir", e); }
    }

    private Path fileFor(String key) {
        // Replace chars illegal/risky on common filesystems (broad set — modded dimensions vary).
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == ':' || c == '/' || c == '\\' || c == '*' || c == '?'
             || c == '"' || c == '<' || c == '>' || c == '|' || c <= ' ') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return baseDir.resolve(sb + ".json");
    }

    @Override
    public void load(String worldKey, RegionManager into) throws IOException {
        Path p = fileFor(worldKey);
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            RegionJsonCodec.applyJson(root, into);
        } catch (com.google.gson.JsonParseException | IllegalStateException ex) {
            // Quarantine the bad file by renaming it. Otherwise the empty manager would be saved
            // back over the recoverable file on the next dirty-flush — permanent data loss.
            Path quarantine = p.resolveSibling(p.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(p, quarantine, StandardCopyOption.REPLACE_EXISTING);
                WorldGuardNeo.LOGGER.error(
                        "Region file {} is malformed — moved to {} and left world '{}' with NO regions. "
                      + "Repair the quarantined file or restore from a backup, then /rg reload.",
                        p, quarantine, worldKey, ex);
            } catch (IOException moveFail) {
                // Couldn't quarantine — surface as IOException so the caller logs loudly instead of
                // silently emptying (and later overwriting) the world.
                WorldGuardNeo.LOGGER.error(
                        "Region file {} is malformed AND could not be quarantined for world '{}'. "
                      + "The original is still on disk; back it up before the next save.",
                        p, worldKey, moveFail);
                throw new IOException("Malformed region file could not be quarantined: " + p, ex);
            }
        }
    }

    @Override
    public void save(String worldKey, RegionManager from) throws IOException {
        // Synchronous path: serialize then write. The async path uses prepare* below, which splits
        // the (server-thread) serialize from the (worker-thread) write.
        writeDocument(worldKey, RegionJsonCodec.toJson(from));
    }

    // JSON has no per-region rows: every incremental edit re-serializes the whole world document.
    // Serialization happens NOW (server thread); only writeDocument runs on the worker.
    @Override public IoTask prepareSave(String worldKey, RegionManager from) {
        JsonObject root = RegionJsonCodec.toJson(from);
        return () -> writeDocument(worldKey, root);
    }
    @Override public IoTask prepareSaveRegion(String worldKey, RegionManager from, String regionId) {
        return prepareSave(worldKey, from);
    }
    @Override public IoTask prepareDeleteRegion(String worldKey, RegionManager from, String regionId) {
        return prepareSave(worldKey, from); // the document already reflects the removal
    }

    private void writeDocument(String worldKey, JsonObject root) throws IOException {
        Path target = fileFor(worldKey);
        Path tmp    = target.resolveSibling(target.getFileName() + ".tmp");
        boolean ok = false;
        // fsync (force) the data to disk BEFORE the atomic rename: without it, a power loss can
        // persist the rename while data blocks are still cached, yielding a truncated file on reboot.
        try (FileChannel ch = FileChannel.open(tmp,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             Writer w = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8))) {
            GSON.toJson(root, w);
            w.flush();
            ch.force(true);
            ok = true;
        } finally {
            if (!ok) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
