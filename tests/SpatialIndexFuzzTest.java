// SpatialIndexFuzzTest — DIFFERENTIAL: validates RegionManager.getApplicable / hasAnyAt (which use
// the chunk-bucketed SpatialIndex) against a brute-force linear scan over all regions, for many
// random region sets, dense query grids, AND add/remove churn. The index and the naive scan must
// return the identical set of regions at every point. Deterministic seed. Pure JVM.
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.region.PolygonalRegion.Point2;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.*;

public final class SpatialIndexFuzzTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; if (failed <= 40) System.out.println("FAIL: " + n); } }

    static final int CONFIGS = 100;   // doubled (was 50)
    static final int QUERIES = 600;
    static final int RANGE   = 240; // coords in [-120, 120)

    public static void main(String[] args) {
        Random rng = new Random(0x5A17);
        for (int c = 0; c < CONFIGS; c++) {
            RegionManager m = new RegionManager("w");
            List<ProtectedRegion> live = new ArrayList<>();
            int r = 5 + rng.nextInt(30);
            for (int i = 0; i < r; i++) {
                ProtectedRegion reg = randomRegion("c" + c + "_r" + i, rng);
                if (m.add(reg)) live.add(reg);
            }
            queryAndCompare("config" + c, m, live, rng);

            // Churn: remove ~half (by id), then re-compare against the brute-force scan.
            Collections.shuffle(live, rng);
            int removeN = live.size() / 2;
            for (int i = 0; i < removeN; i++) {
                ProtectedRegion victim = live.remove(live.size() - 1);
                m.remove(victim.id());
            }
            queryAndCompare("config" + c + "-postremove", m, live, rng);
        }
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static void queryAndCompare(String label, RegionManager m, List<ProtectedRegion> live, Random rng) {
        for (int q = 0; q < QUERIES; q++) {
            int x = rng.nextInt(RANGE) - RANGE / 2;
            int y = rng.nextInt(40);
            int z = rng.nextInt(RANGE) - RANGE / 2;
            Set<String> idx = idsOf(m.getApplicable(x, y, z));
            Set<String> brute = bruteForce(live, x, y, z);
            if (!idx.equals(brute)) {
                check(label + " getApplicable(" + x + "," + y + "," + z + ") idx=" + idx + " brute=" + brute, false);
            } else {
                passed++;
            }
            // hasAnyAt must agree with "brute set non-empty".
            check(label + " hasAnyAt(" + x + "," + y + "," + z + ")", m.hasAnyAt(x, y, z) == !brute.isEmpty());
        }
    }

    static Set<String> idsOf(List<ProtectedRegion> rs) {
        Set<String> s = new HashSet<>();
        for (ProtectedRegion r : rs) s.add(r.id());
        return s;
    }
    static Set<String> bruteForce(List<ProtectedRegion> live, double x, double y, double z) {
        Set<String> s = new HashSet<>();
        for (ProtectedRegion r : live) if (r.contains(x, y, z)) s.add(r.id());
        return s;
    }

    static ProtectedRegion randomRegion(String id, Random rng) {
        int roll = rng.nextInt(10);
        if (roll == 0) {
            // Occasionally an oversized region (routed to the index's oversized fallback).
            int x1 = -1_000_000 - rng.nextInt(1000), x2 = 1_000_000 + rng.nextInt(1000);
            return new CuboidRegion(id, new Vec3(x1, 0, -50), new Vec3(x2, 40, 50));
        }
        int x1 = rng.nextInt(RANGE) - RANGE / 2, w = 1 + rng.nextInt(40), x2 = x1 + w;
        int z1 = rng.nextInt(RANGE) - RANGE / 2, h = 1 + rng.nextInt(40), z2 = z1 + h;
        int y1 = rng.nextInt(20), y2 = y1 + 1 + rng.nextInt(35);
        if (roll < 7) {
            return new CuboidRegion(id, new Vec3(x1, y1, z1), new Vec3(x2, y2, z2));
        }
        // Rectangle expressed as a polygon (exercises the polygon path through the index too).
        return new PolygonalRegion(id, List.of(
                new Point2(x1, z1), new Point2(x2, z1), new Point2(x2, z2), new Point2(x1, z2)), y1, y2);
    }
}
