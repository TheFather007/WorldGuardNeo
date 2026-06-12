package dev.dizzy.worldguardneo.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dizzy.worldguardneo.WorldGuardNeo;
import dev.dizzy.worldguardneo.region.RegionManager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
        try (Reader r = Files.newBufferedReader(p)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            RegionJsonCodec.applyJson(root, into);
        } catch (com.google.gson.JsonParseException | IllegalStateException ex) {
            WorldGuardNeo.LOGGER.error(
                    "Region file {} is malformed — leaving world '{}' with NO regions until repaired.",
                    p, worldKey, ex);
        }
    }

    @Override
    public void save(String worldKey, RegionManager from) throws IOException {
        JsonObject root = RegionJsonCodec.toJson(from);
        Path target = fileFor(worldKey);
        Path tmp    = target.resolveSibling(target.getFileName() + ".tmp");
        boolean ok = false;
        try (Writer w = Files.newBufferedWriter(tmp)) {
            GSON.toJson(root, w);
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
