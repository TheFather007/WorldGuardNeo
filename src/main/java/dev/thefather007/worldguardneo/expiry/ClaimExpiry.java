package dev.thefather007.worldguardneo.expiry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player last-seen times and auto-deletes player regions whose owners have ALL been offline
 * longer than {@code claim-expiry.days}. Admin/unowned regions (no owners) are never expired, and
 * a currently-online owner always counts as active. Disabled unless {@code claim-expiry.enabled}.
 *
 * <p>Last-seen times persist to {@code config/worldguardneo/activity.json}. A player whose time was
 * never recorded (predates tracking) is treated as active, so enabling expiry can't immediately
 * wipe long-standing regions before fresh activity data accumulates.
 */
public final class ClaimExpiry {

    private static final Gson GSON = new GsonBuilder().create();

    private final Path file;
    private final Map<UUID, Long> lastSeen = new ConcurrentHashMap<>();
    private long lastCheckTick = 0L;

    public ClaimExpiry(Path dataDir) {
        this.file = dataDir.resolve("activity.json");
        load();
    }

    /** Record a player as active right now (call on login and logout). */
    public void record(UUID id) {
        if (id != null) lastSeen.put(id, System.currentTimeMillis());
    }

    /** Last-seen epoch millis, or 0 if never recorded. */
    public long lastSeen(UUID id) {
        Long l = lastSeen.get(id);
        return l == null ? 0L : l;
    }

    /** Server-tick hook: run the expiry scan every {@code check-hours} (not at boot — start does that). */
    public void tick(WorldGuardNeo mod, long currentTick) {
        if (!mod.config().global().claimExpiryEnabled) return;
        long intervalTicks = (long) Math.max(1, mod.config().global().claimExpiryCheckHours) * 3600L * 20L;
        if (lastCheckTick == 0L) { lastCheckTick = currentTick; return; }
        if (currentTick - lastCheckTick < intervalTicks) return;
        lastCheckTick = currentTick;
        runCleanup(mod);
    }

    /**
     * Scan every world and delete player regions whose owners are all long-offline.
     * Returns the number removed. Safe to call manually (e.g. {@code /rg cleanup}).
     */
    public int runCleanup(WorldGuardNeo mod) {
        var g = mod.config().global();
        if (!g.claimExpiryEnabled) return 0;
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 0;
        long now = System.currentTimeMillis();
        long threshold = now - (long) Math.max(1, g.claimExpiryDays) * 86_400_000L;

        int removed = 0;
        for (Map.Entry<String, RegionManager> e : mod.regions().allManagers().entrySet()) {
            String worldKey = e.getKey();
            RegionManager mgr = e.getValue();
            ServerLevel level = levelFor(server, worldKey);

            List<ProtectedRegion> expired = new ArrayList<>();
            for (ProtectedRegion r : mgr.all()) {
                var owners = r.ownersView();
                if (owners.isEmpty()) continue;               // unowned / admin region → never expire
                boolean active = false;
                for (UUID o : owners) {
                    boolean online = server.getPlayerList().getPlayer(o) != null;
                    long seen = online ? now : lastSeen(o);
                    if (seen == 0L || seen >= threshold) { active = true; break; } // 0 = unknown → keep
                }
                if (!active) expired.add(r);
            }
            if (expired.isEmpty()) continue;
            for (ProtectedRegion r : expired) {
                if (level != null) {
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                            new dev.thefather007.worldguardneo.api.events.RegionModifyEvent(
                                    level, r,
                                    dev.thefather007.worldguardneo.api.events.RegionModifyEvent.ModifyType.DELETED, null));
                }
                mgr.remove(r.id());
                removed++;
                WorldGuardNeo.LOGGER.info("[WorldGuardNeo] Claim expiry removed region '{}' in {} (all owners offline > {}d).",
                        r.id(), worldKey, g.claimExpiryDays);
                if (level != null) {
                    var bm = dev.thefather007.worldguardneo.integrations.BluemapIntegration.get();
                    if (bm != null) bm.removeRegion(level, r.id());
                    var sq = dev.thefather007.worldguardneo.integrations.SquaremapIntegration.get();
                    if (sq != null) sq.removeRegion(level, r.id());
                }
            }
            mod.regions().save(worldKey); // full sync of this world (prunes removed rows, relinks children)
        }
        if (removed > 0) save();
        return removed;
    }

    private static ServerLevel levelFor(MinecraftServer server, String worldKey) {
        for (ServerLevel sl : server.getAllLevels()) {
            if (sl.dimension().location().toString().equals(worldKey)) return sl;
        }
        return null;
    }

    /* ---------------- persistence ---------------- */

    private void load() {
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
                try { lastSeen.put(UUID.fromString(e.getKey()), e.getValue().getAsLong()); }
                catch (Exception ignored) { /* skip bad entry */ }
            }
        } catch (IOException | RuntimeException ex) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Could not read activity.json — starting fresh.", ex);
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        for (Map.Entry<UUID, Long> e : lastSeen.entrySet()) root.addProperty(e.getKey().toString(), e.getValue());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { GSON.toJson(root, w); }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Could not write activity.json", ex);
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }
}
