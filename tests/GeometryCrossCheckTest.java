// GeometryCrossCheckTest — DIFFERENTIAL point-in-polygon test. PolygonalRegion.contains uses a
// crossing-number ray cast; this validates it against an INDEPENDENT winding-number implementation
// over dense grids for many random axis-aligned polygons (rectangles + concave L-shapes).
//
// Vertices use EVEN coordinates and we sample at ODD block coordinates, so no sample point ever
// lies exactly on an edge — the two algorithms then provably agree for any correct implementation,
// with no boundary ambiguity. Also checks floor-consistency (block vs continuous coords). Pure JVM.
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.region.PolygonalRegion;
import dev.thefather007.worldguardneo.region.PolygonalRegion.Point2;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class GeometryCrossCheckTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; if (failed <= 40) System.out.println("FAIL: " + n); } }

    public static void main(String[] args) {
        Random rng = new Random(0xBEEF);
        int shapes = 0;
        for (int i = 0; i < 120; i++) {
            List<Point2> poly = (i % 2 == 0) ? randomRect(rng) : randomL(rng);
            crossCheck("shape" + i, poly);
            shapes++;
        }
        // A couple of fixed concave shapes (plus / U) for good measure.
        crossCheck("plus", plus());
        crossCheck("u-shape", uShape());
        System.out.println("(" + (shapes + 2) + " polygons cross-checked)");
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    /** Independent winding-number PIP — completely different code path from the crossing-number impl. */
    static boolean winding(List<Point2> p, int px, int pz) {
        int wn = 0, n = p.size();
        for (int i = 0; i < n; i++) {
            Point2 a = p.get(i), b = p.get((i + 1) % n);
            if (a.z() <= pz) {
                if (b.z() > pz && isLeft(a, b, px, pz) > 0) wn++;
            } else {
                if (b.z() <= pz && isLeft(a, b, px, pz) < 0) wn--;
            }
        }
        return wn != 0;
    }
    static long isLeft(Point2 a, Point2 b, int px, int pz) {
        return (long) (b.x() - a.x()) * (pz - a.z()) - (long) (px - a.x()) * (b.z() - a.z());
    }

    static void crossCheck(String label, List<Point2> poly) {
        PolygonalRegion r = new PolygonalRegion("p", poly, 0, 10);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Point2 pt : poly) {
            minX = Math.min(minX, pt.x()); maxX = Math.max(maxX, pt.x());
            minZ = Math.min(minZ, pt.z()); maxZ = Math.max(maxZ, pt.z());
        }
        // Sample ODD block coords across the AABB (+margin). Vertices are even → no point on an edge.
        for (int x = minX - 3; x <= maxX + 3; x++) {
            if ((x & 1) == 0) continue;
            for (int z = minZ - 3; z <= maxZ + 3; z++) {
                if ((z & 1) == 0) continue;
                boolean impl = r.contains(x, 5, z);
                boolean ref  = winding(poly, x, z);
                check(label + " PIP(" + x + "," + z + ")", impl == ref);
                // Floor-consistency: any point within the block agrees with the block.
                check(label + " floor(" + x + "," + z + ")", impl == r.contains(x + 0.7, 5, z + 0.3));
            }
        }
    }

    /* ---- shape generators (EVEN coordinates) ---- */

    static int even(Random rng, int lo, int spanHalf) { return lo + 2 * rng.nextInt(spanHalf); }

    static List<Point2> randomRect(Random rng) {
        int x1 = even(rng, -20, 20), w = 2 * (1 + rng.nextInt(15)), x2 = x1 + w;
        int z1 = even(rng, -20, 20), h = 2 * (1 + rng.nextInt(15)), z2 = z1 + h;
        return List.of(new Point2(x1, z1), new Point2(x2, z1), new Point2(x2, z2), new Point2(x1, z2));
    }

    static List<Point2> randomL(Random rng) {
        int x1 = even(rng, -20, 10), w = 2 * (2 + rng.nextInt(12)), x2 = x1 + w;
        int z1 = even(rng, -20, 10), h = 2 * (2 + rng.nextInt(12)), z2 = z1 + h;
        int cx = x1 + 2 * (1 + rng.nextInt((x2 - x1) / 2 - 1)); // strictly inside, even
        int cz = z1 + 2 * (1 + rng.nextInt((z2 - z1) / 2 - 1));
        // Outline of (left column [x1,cx]×[z1,z2]) ∪ (bottom row [x1,x2]×[z1,cz]).
        List<Point2> p = new ArrayList<>();
        p.add(new Point2(x1, z1)); p.add(new Point2(x2, z1)); p.add(new Point2(x2, cz));
        p.add(new Point2(cx, cz)); p.add(new Point2(cx, z2)); p.add(new Point2(x1, z2));
        return p;
    }

    static List<Point2> plus() {
        // A plus/cross centred at origin, arms width 10.
        return List.of(
                new Point2(10, 0), new Point2(20, 0), new Point2(20, 10), new Point2(30, 10),
                new Point2(30, 20), new Point2(20, 20), new Point2(20, 30), new Point2(10, 30),
                new Point2(10, 20), new Point2(0, 20), new Point2(0, 10), new Point2(10, 10));
    }

    static List<Point2> uShape() {
        // A U: outer 30x20 with a notch cut from the top-middle.
        return List.of(
                new Point2(0, 0), new Point2(30, 0), new Point2(30, 20), new Point2(20, 20),
                new Point2(20, 10), new Point2(10, 10), new Point2(10, 20), new Point2(0, 20));
    }
}
