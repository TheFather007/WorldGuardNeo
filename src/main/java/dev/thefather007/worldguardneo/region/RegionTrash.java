package dev.thefather007.worldguardneo.region;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory "recycle bin" for deleted regions, enabling {@code /rg undo}. Each world keeps a small
 * bounded LIFO of the most recently removed {@link ProtectedRegion} instances; {@code /rg remove}
 * pushes the region here before unlinking it, and {@code /rg undo} pops and re-adds the newest one.
 *
 * <p>Session-scoped (not persisted): a server restart clears the bin. Bounded per world so it can't
 * grow without limit. The stored object retains the region's geometry, flags, owners and members;
 * parent links are not restored if the parent was itself changed after deletion (rare; documented).
 *
 * <p>Accessed only from the server thread (command handlers), so no synchronisation is needed.
 */
public final class RegionTrash {

    /** Most recent deletions kept per world. Older entries fall off the tail. */
    private static final int MAX_PER_WORLD = 20;

    private final Map<ResourceKey<Level>, Deque<ProtectedRegion>> bins = new HashMap<>();

    /** Remember a region that was just removed from {@code world}. */
    public void push(ResourceKey<Level> world, ProtectedRegion region) {
        Deque<ProtectedRegion> bin = bins.computeIfAbsent(world, k -> new ArrayDeque<>());
        bin.addFirst(region);
        while (bin.size() > MAX_PER_WORLD) bin.removeLast();
    }

    /** Pop the most recently deleted region for {@code world}, or null if the bin is empty. */
    public ProtectedRegion pop(ResourceKey<Level> world) {
        Deque<ProtectedRegion> bin = bins.get(world);
        return (bin == null || bin.isEmpty()) ? null : bin.removeFirst();
    }
}
