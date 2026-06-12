package dev.dizzy.worldguardneo.util;

import com.mojang.authlib.GameProfile;
import dev.dizzy.worldguardneo.WorldGuardNeo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves player UUIDs from arbitrary user input.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Raw UUID syntax — {@code UUID.fromString}, accepted as-is.</li>
 *   <li>Online player with matching name (case-insensitive). Constant-time.</li>
 *   <li>Server's {@link GameProfileCache} (the {@code usercache.json} on disk). Synchronous lookup.</li>
 * </ol>
 *
 * <p>We deliberately do NOT call the Mojang session-server here. Hitting an external API
 * synchronously from a command thread would freeze the server for unknown players. Admins
 * who need to add an unseen player should first connect them, or paste their UUID directly.
 *
 * <p>The reverse direction ({@link #nameOf}) returns a best-effort display name, falling
 * back to the stringified UUID when the profile is unknown.
 */
public final class UuidResolver {

    private UuidResolver() {}

    /**
     * Resolve a player identifier ("Notch", "069a79f4-44e9-4726-a5be-fca90e38aaf5") to a UUID.
     */
    public static Optional<UUID> resolve(MinecraftServer server, String input) {
        if (input == null || input.isEmpty()) return Optional.empty();

        // 1. Raw UUID input. Accept dashed (36-char canonical) or undashed (32 hex chars).
        // The strict length check avoids slow exception paths for short inputs like "Bob".
        int len = input.length();
        boolean looksDashed   = len == 36 && input.charAt(8) == '-';
        boolean looksUndashed = len == 32 && input.indexOf('-') < 0;
        if (looksDashed || looksUndashed) {
            try { return Optional.of(UUID.fromString(canonicalizeUuid(input))); }
            catch (IllegalArgumentException ignored) { /* not a UUID, fall through */ }
        }

        // 2. Online player.
        ServerPlayer online = server.getPlayerList().getPlayerByName(input);
        if (online != null) return Optional.of(online.getUUID());

        // 3. Profile cache (synchronous, but reads from in-memory map or single JSON file).
        try {
            GameProfileCache cache = server.getProfileCache();
            if (cache != null) {
                return cache.get(input).map(GameProfile::getId);
            }
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("GameProfileCache lookup failed for '{}'", input, t);
        }
        return Optional.empty();
    }

    /** Best-effort name for display. Returns the UUID string when no name is known. */
    public static String nameOf(MinecraftServer server, UUID uuid) {
        if (uuid == null) return "(none)";
        // Online?
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) return online.getGameProfile().getName();
        // Cached?
        try {
            GameProfileCache cache = server.getProfileCache();
            if (cache != null) {
                Optional<GameProfile> hit = cache.get(uuid);
                if (hit.isPresent() && hit.get().getName() != null) return hit.get().getName();
            }
        } catch (Throwable ignored) {}
        return uuid.toString();
    }

    /**
     * Accept both dashed (8-4-4-4-12) and undashed (32 hex) UUIDs.
     * Returns the canonical dashed form for {@link UUID#fromString}.
     */
    private static String canonicalizeUuid(String s) {
        if (s.indexOf('-') >= 0) return s;
        if (s.length() != 32) return s;
        return s.substring(0, 8) + '-'
             + s.substring(8, 12) + '-'
             + s.substring(12, 16) + '-'
             + s.substring(16, 20) + '-'
             + s.substring(20);
    }
}
