package dev.thefather007.worldguardneo.region;

import dev.thefather007.worldguardneo.util.Vec3;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Chunk-bucketed spatial index for region lookup. Each region is registered in every 16×16 (XZ)
 * chunk-column its AABB overlaps; a point lookup is one chunk-key probe + linear filter over the
 * (tiny) bucket. Y is not bucketed (extents are usually large; a Y axis would just bloat memory).
 *
 * <p>Regions whose footprint exceeds {@link #MAX_BUCKETS_PER_REGION} buckets (e.g. world-spanning
 * admin regions) go in {@link #oversized} and are consulted via fallback scan, keeping memory bounded.
 *
 * <p>Uses {@link Long2ObjectOpenHashMap} to avoid Long autoboxing — lookups fire thousands of times
 * per tick. Read-only after build; mutators must run on the server thread.
 */
public final class SpatialIndex {

    /** Per-region cap on chunk-column buckets to keep memory bounded. 1M ≈ 16M-block span. */
    private static final long MAX_BUCKETS_PER_REGION = 1_000_000L;

    /** Key = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL). Primitive map → no Long boxing. */
    private final Long2ObjectOpenHashMap<ArrayList<ProtectedRegion>> buckets = new Long2ObjectOpenHashMap<>();

    /** Regions too large to bucket. IdentityHashMap as a poor-man's identity set (region instances). */
    private final IdentityHashMap<ProtectedRegion, Boolean> oversized = new IdentityHashMap<>();

    /**
     * Immutable snapshot of {@link #oversized}'s keys, rebuilt only on mutation. Lets
     * {@link #candidates} return it with zero allocation for the common "point is in a world-spanning
     * region but no bucketed region" lookup.
     */
    private List<ProtectedRegion> oversizedSnapshot = List.of();

    private void refreshOversizedSnapshot() {
        oversizedSnapshot = oversized.isEmpty() ? List.of() : List.copyOf(oversized.keySet());
    }

    public void clear() {
        buckets.clear();
        oversized.clear();
        oversizedSnapshot = List.of();
    }

    public void add(ProtectedRegion r) {
        // GlobalRegion uses Integer.MIN/MAX bounds → never index it (the manager treats it specially).
        if (r instanceof GlobalRegion) return;
        Vec3 mn = r.minimumBound(), mx = r.maximumBound();
        // A real (non-global) region whose bounds reach an extreme int coordinate must NOT be
        // silently dropped (that would make it invisible to lookups — its flags would never
        // apply). Treat it as oversized so it is still consulted via the fallback scan.
        if (mn.x() == Integer.MIN_VALUE || mx.x() == Integer.MAX_VALUE
         || mn.z() == Integer.MIN_VALUE || mx.z() == Integer.MAX_VALUE) {
            oversized.put(r, Boolean.TRUE);
            refreshOversizedSnapshot();
            return;
        }
        int cx0 = mn.x() >> 4, cx1 = mx.x() >> 4;
        int cz0 = mn.z() >> 4, cz1 = mx.z() >> 4;
        long span = (long)(cx1 - cx0 + 1) * (long)(cz1 - cz0 + 1);
        if (span <= 0 || span > MAX_BUCKETS_PER_REGION) {
            oversized.put(r, Boolean.TRUE);
            refreshOversizedSnapshot();
            return;
        }
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                long k = key(cx, cz);
                // get+put avoids computeIfAbsent overload ambiguity (primitive vs Map variant).
                ArrayList<ProtectedRegion> list = buckets.get(k);
                if (list == null) {
                    list = new ArrayList<>(2);
                    buckets.put(k, list);
                }
                list.add(r);
            }
        }
    }

    public void remove(ProtectedRegion r) {
        if (r instanceof GlobalRegion) return;
        if (oversized.remove(r) != null) { refreshOversizedSnapshot(); return; }
        Vec3 mn = r.minimumBound(), mx = r.maximumBound();
        // Extreme-coordinate regions live in oversized (handled above); guard the bucket loop
        // against extreme bounds so we never iterate an astronomically large chunk range.
        if (mn.x() == Integer.MIN_VALUE || mx.x() == Integer.MAX_VALUE
         || mn.z() == Integer.MIN_VALUE || mx.z() == Integer.MAX_VALUE) return;
        int cx0 = mn.x() >> 4, cx1 = mx.x() >> 4;
        int cz0 = mn.z() >> 4, cz1 = mx.z() >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                long k = key(cx, cz);
                ArrayList<ProtectedRegion> list = buckets.get(k);
                if (list != null) {
                    list.remove(r);
                    if (list.isEmpty()) buckets.remove(k);
                }
            }
        }
    }

    public void rebuild(Collection<ProtectedRegion> all) {
        clear();
        for (ProtectedRegion r : all) add(r);
    }

    /**
     * Returns regions whose AABB *may* contain (x,z). Caller still needs to test
     * {@link ProtectedRegion#contains} for non-cuboid shapes and Y bounds.
     *
     * If any oversized regions exist they are appended (still cheap — the list is short).
     */
    public List<ProtectedRegion> candidates(double x, double z) {
        long k = key(floor(x) >> 4, floor(z) >> 4);
        List<ProtectedRegion> bucket = buckets.get(k);
        if (oversized.isEmpty()) return bucket == null ? List.of() : bucket;
        // No bucketed region here but oversized ones exist (the common case on a server with a
        // world-spanning region): return the cached snapshot directly — zero allocation.
        if (bucket == null) return oversizedSnapshot;
        // Both present (rarer): combine. Oversized is usually 1-2 entries.
        ArrayList<ProtectedRegion> out = new ArrayList<>(bucket.size() + oversizedSnapshot.size());
        out.addAll(bucket);
        out.addAll(oversizedSnapshot);
        return out;
    }

    /** Bulk lookup: returns the set of regions overlapping a (minX..maxX, minZ..maxZ) AABB. */
    public Set<ProtectedRegion> candidatesInArea(int minX, int minZ, int maxX, int maxZ) {
        Set<ProtectedRegion> out = new HashSet<>();
        int cx0 = minX >> 4, cx1 = maxX >> 4;
        int cz0 = minZ >> 4, cz1 = maxZ >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                List<ProtectedRegion> list = buckets.get(key(cx, cz));
                if (list != null) out.addAll(list);
            }
        }
        out.addAll(oversized.keySet());
        return out;
    }

    public int bucketCount()    { return buckets.size(); }
    public int oversizedCount() { return oversized.size(); }
    public int totalRefs() {
        int n = oversized.size();
        for (List<ProtectedRegion> l : buckets.values()) n += l.size();
        return n;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffff_ffffL);
    }

    private static int floor(double d) {
        int i = (int) d;
        return d < i ? i - 1 : i;
    }
}
