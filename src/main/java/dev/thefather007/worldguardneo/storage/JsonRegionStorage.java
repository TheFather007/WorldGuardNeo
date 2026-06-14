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

    // Compact (NOT pretty-printed) JSON is roughly 30-50% smaller and faster to serialise.
    // The regions file is for the mod's own use; admins normally edit via /rg commands,
    // not by hand. If you must hand-edit, run the file through `jq .` for readable form.
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path baseDir;

    public JsonRegionStorage(Path baseDir) {
        this.baseDir = baseDir.resolve("regions");
        try { Files.createDirectories(this.baseDir); }
        catch (IOException e) { WorldGuardNeo.LOGGER.error("Failed to create region dir", e); }
    }

    private Path fileFor(String key) {
        // Replace all characters that are illegal or risky on common filesystems.
        // The set is intentionally broad — modded dimensions can use almost any chars.
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
            // A corrupt/truncated file would otherwise leave the world with an EMPTY manager,
            // and the next dirty-flush save would overwrite the (recoverable) file with empty
            // data — permanent data loss. Quarantine the bad file by renaming it so a later
            // save writes a fresh file instead of destroying the original. The quarantined copy
            // can be hand-repaired or restored from backup.
            Path quarantine = p.resolveSibling(p.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(p, quarantine, StandardCopyOption.REPLACE_EXISTING);
                WorldGuardNeo.LOGGER.error(
                        "Region file {} is malformed — moved to {} and left world '{}' with NO regions. "
                      + "Repair the quarantined file or restore from a backup, then /rg reload.",
                        p, quarantine, worldKey, ex);
            } catch (IOException moveFail) {
                // Could not move it out of the way — surface as IOException so the caller logs the
                // failure loudly rather than silently emptying (and later overwriting) the world.
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
        JsonObject root = RegionJsonCodec.toJson(from);
        Path target = fileFor(worldKey);
        Path tmp    = target.resolveSibling(target.getFileName() + ".tmp");
        boolean ok = false;
        // Write through a FileChannel so we can fsync (force) the data to stable storage BEFORE
        // the atomic rename. Without the fsync, a power loss can persist the rename while the
        // file's data blocks are still in the OS cache, yielding a truncated/empty file on reboot
        // (which then trips the corrupt-file path above). Charset is pinned to UTF-8 explicitly.
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
