// SpatialIndexTest — chunk-bucketed index mechanics: add/remove symmetry (no stale entries),
// point and area queries, oversized routing, chunk-border spanning, negative chunks, rebuild/clear.
// Pure JVM.
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.List;
import java.util.Set;

public final class SpatialIndexTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static boolean candHas(SpatialIndex idx, double x, double z, String id) {
        for (ProtectedRegion r : idx.candidates(x, z)) if (r.id().equals(id)) return true;
        return false;
    }
    static boolean areaHas(Set<ProtectedRegion> s, String id) {
        for (ProtectedRegion r : s) if (r.id().equals(id)) return true;
        return false;
    }

    public static void main(String[] args) {
        SpatialIndex idx = new SpatialIndex();

        CuboidRegion a = new CuboidRegion("a", new Vec3(0, 0, 0), new Vec3(15, 10, 15)); // one chunk
        idx.add(a);
        check("point in a found", candHas(idx, 8, 8, "a"));
        check("point outside a not found", !candHas(idx, 100, 100, "a"));
        check("bucketCount 1", idx.bucketCount() == 1);
        check("oversizedCount 0", idx.oversizedCount() == 0);

        // Region spanning multiple chunks (and crossing the 0 boundary into negative chunks).
        CuboidRegion span = new CuboidRegion("span", new Vec3(-20, 0, -20), new Vec3(40, 10, 40));
        idx.add(span);
        check("span found at negative chunk", candHas(idx, -18, -18, "span"));
        check("span found at positive chunk", candHas(idx, 38, 38, "span"));
        check("span found at origin", candHas(idx, 0, 0, "span"));
        check("a + span both at overlap", candHas(idx, 8, 8, "a") && candHas(idx, 8, 8, "span"));

        // Area query.
        Set<ProtectedRegion> area = idx.candidatesInArea(-20, -20, 40, 40);
        check("area query has a", areaHas(area, "a"));
        check("area query has span", areaHas(area, "span"));
        Set<ProtectedRegion> far = idx.candidatesInArea(1000, 1000, 1010, 1010);
        check("area query far is empty", far.isEmpty());

        // Remove leaves NO stale entries (the key correctness property).
        idx.remove(a);
        check("removed a not found", !candHas(idx, 8, 8, "a"));
        check("span still found after removing a", candHas(idx, 8, 8, "span"));
        idx.remove(span);
        check("removed span not found", !candHas(idx, 8, 8, "span"));
        check("bucketCount 0 after removing all", idx.bucketCount() == 0);
        check("totalRefs 0 after removing all", idx.totalRefs() == 0);

        // Oversized routing: a world-spanning region by huge span.
        SpatialIndex idx2 = new SpatialIndex();
        CuboidRegion huge = new CuboidRegion("huge", new Vec3(-30_000_000, 0, -30_000_000),
                new Vec3(30_000_000, 255, 30_000_000));
        idx2.add(huge);
        check("huge → oversized", idx2.oversizedCount() == 1 && idx2.bucketCount() == 0);
        check("huge found anywhere", candHas(idx2, 12345, -9999, "huge"));
        idx2.remove(huge);
        check("huge removed from oversized", idx2.oversizedCount() == 0 && !candHas(idx2, 0, 0, "huge"));

        // Extreme-coordinate region (audit fix) routes to oversized, still found.
        SpatialIndex idx3 = new SpatialIndex();
        CuboidRegion edge = new CuboidRegion("edge", new Vec3(Integer.MIN_VALUE, 0, 0), new Vec3(10, 10, 10));
        idx3.add(edge);
        check("extreme-coord → oversized", idx3.oversizedCount() == 1);
        check("extreme-coord found", candHas(idx3, 5, 5, "edge"));
        idx3.remove(edge);
        check("extreme-coord removed", idx3.oversizedCount() == 0);

        // GlobalRegion is never indexed.
        SpatialIndex idx4 = new SpatialIndex();
        idx4.add(new GlobalRegion());
        check("global not indexed", idx4.bucketCount() == 0 && idx4.oversizedCount() == 0);

        // rebuild + clear.
        SpatialIndex idx5 = new SpatialIndex();
        idx5.rebuild(List.of(a, span, huge));
        check("rebuild buckets a+span", candHas(idx5, 8, 8, "a") && candHas(idx5, 38, 38, "span"));
        check("rebuild oversized huge", candHas(idx5, 999999, 0, "huge"));
        idx5.clear();
        check("clear empties index", idx5.bucketCount() == 0 && idx5.oversizedCount() == 0);

        // Two regions in distinct chunks don't collide (distinct keys, incl. negative).
        SpatialIndex idx6 = new SpatialIndex();
        CuboidRegion c1 = new CuboidRegion("c1", new Vec3(0, 0, 0), new Vec3(5, 5, 5));       // chunk (0,0)
        CuboidRegion c2 = new CuboidRegion("c2", new Vec3(-5, 0, -5), new Vec3(-1, 5, -1));   // chunk (-1,-1)
        idx6.add(c1); idx6.add(c2);
        check("c1 only in its chunk", candHas(idx6, 2, 2, "c1") && !candHas(idx6, 2, 2, "c2"));
        check("c2 only in its chunk", candHas(idx6, -3, -3, "c2") && !candHas(idx6, -3, -3, "c1"));

        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }
}
