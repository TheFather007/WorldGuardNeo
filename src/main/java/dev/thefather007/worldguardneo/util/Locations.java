package dev.thefather007.worldguardneo.util;

import net.minecraft.world.level.Level;

/** Small geometry helpers for region-driven player movement (spawn / teleport flags). */
public final class Locations {

    private Locations() {}

    /**
     * Clamp a Y coordinate into the level's placeable range {@code [minBuildHeight, maxBuildHeight-1]}.
     *
     * <p>Defensive: the {@code spawn} / {@code teleport} flags hold admin-supplied free text, so a typo
     * or stale value (e.g. a Y from another world with a different height, or the void) could otherwise
     * fling a player out of the world and into a death/fall loop. X/Z are left untouched.
     */
    public static double clampY(Level level, double y) {
        double min = level.getMinBuildHeight();
        double max = level.getMaxBuildHeight() - 1; // last placeable block
        return Math.max(min, Math.min(max, y));
    }
}
