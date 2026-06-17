// GeometryTest — exhaustive region geometry: CuboidRegion / PolygonalRegion / GlobalRegion
// contains(), bounds, volume, and Vec3 math. Pure JVM (no Minecraft).
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.List;

public final class GeometryTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    public static void main(String[] args) {
        vec3();
        cuboid();
        polygon();
        global();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static void vec3() {
        Vec3 a = new Vec3(3, -4, 5), b = new Vec3(-1, 8, 2);
        check("vec3 min", a.min(b).equals(new Vec3(-1, -4, 2)));
        check("vec3 max", a.max(b).equals(new Vec3(3, 8, 5)));
        check("vec3 equals/hashCode", a.equals(new Vec3(3, -4, 5)) && a.hashCode() == new Vec3(3, -4, 5).hashCode());
        check("vec3 not-equals", !a.equals(b));
        // volume inclusive: |dx|+1 * |dy|+1 * |dz|+1
        check("vec3 volume 1x1x1", new Vec3(0, 0, 0).volumeWith(new Vec3(0, 0, 0)) == 1L);
        check("vec3 volume 2x3x4", new Vec3(0, 0, 0).volumeWith(new Vec3(1, 2, 3)) == 24L);
        check("vec3 volume neg coords", new Vec3(-5, 0, 0).volumeWith(new Vec3(5, 0, 0)) == 11L);
        // long arithmetic — no int overflow for a large region
        long big = new Vec3(-100000, 0, -100000).volumeWith(new Vec3(100000, 255, 100000));
        check("vec3 volume long (no overflow)", big == 200001L * 256L * 200001L);
        check("vec3 ZERO", Vec3.ZERO.equals(new Vec3(0, 0, 0)));
    }

    static void cuboid() {
        // Corners given in any order are normalized to min/max.
        CuboidRegion c = new CuboidRegion("c", new Vec3(20, 70, 20), new Vec3(-10, 5, -10));
        check("cuboid min normalized", c.minimumBound().equals(new Vec3(-10, 5, -10)));
        check("cuboid max normalized", c.maximumBound().equals(new Vec3(20, 70, 20)));
        // Block-inclusive: [min, max+1) on each axis.
        check("cuboid center inside", c.contains(0, 30, 0));
        check("cuboid min corner inside", c.contains(-10, 5, -10));
        check("cuboid max block inside (integer)", c.contains(20, 70, 20));
        check("cuboid max block inside (continuous)", c.contains(20.9, 70.9, 20.9));
        check("cuboid just past max-X out", !c.contains(21, 30, 0));
        check("cuboid just below min-Y out", !c.contains(0, 4.9, 0));
        check("cuboid below min-X out", !c.contains(-10.5, 30, 0)); // block -11
        check("cuboid above max-Y out", !c.contains(0, 71, 0));
        // Single-block cuboid.
        CuboidRegion one = new CuboidRegion("one", new Vec3(5, 5, 5), new Vec3(5, 5, 5));
        check("cuboid 1-block contains its block", one.contains(5, 5, 5) && one.contains(5.5, 5.5, 5.5));
        check("cuboid 1-block excludes neighbour", !one.contains(6, 5, 5) && !one.contains(5, 5, 6));
        check("cuboid 1-block volume", one.volume() == 1L);
        check("cuboid volume", c.volume() == 31L * 66L * 31L);
        check("cuboid type", c.type().equals("cuboid"));
        // setCorners updates bounds.
        one.setCorners(new Vec3(0, 0, 0), new Vec3(2, 2, 2));
        check("cuboid setCorners", one.contains(2, 2, 2) && !one.contains(3, 2, 2));
    }

    static void polygon() {
        // 20x20 square (0,0)-(20,20).
        PolygonalRegion sq = new PolygonalRegion("sq", List.of(
                new PolygonalRegion.Point2(0, 0), new PolygonalRegion.Point2(20, 0),
                new PolygonalRegion.Point2(20, 20), new PolygonalRegion.Point2(0, 20)), 0, 30);
        check("poly center", sq.contains(10, 15, 10));
        check("poly minY inclusive", sq.contains(10, 0, 10));
        check("poly maxY block inclusive", sq.contains(10, 30, 10));
        check("poly below minY out", !sq.contains(10, -1, 10));
        check("poly above maxY out", !sq.contains(10, 31, 10));
        check("poly outside -X", !sq.contains(-1, 10, 10));
        check("poly outside +X far", !sq.contains(30, 10, 10));
        // Floor consistency: any point within a block gives the same verdict as the block.
        for (int[] p : new int[][]{{0,0},{19,19},{10,10},{20,20},{-1,-1},{21,21}}) {
            boolean a = sq.contains(p[0], 10, p[1]);
            boolean b = sq.contains(p[0] + 0.9, 10, p[1] + 0.9);
            check("poly floor-consistent (" + p[0] + "," + p[1] + ")", a == b);
        }
        // Concave L-shape: corner cut out of the top-right quadrant.
        PolygonalRegion l = new PolygonalRegion("l", List.of(
                new PolygonalRegion.Point2(0, 0), new PolygonalRegion.Point2(20, 0),
                new PolygonalRegion.Point2(20, 10), new PolygonalRegion.Point2(10, 10),
                new PolygonalRegion.Point2(10, 20), new PolygonalRegion.Point2(0, 20)), 0, 10);
        check("L-shape inside lower-right arm", l.contains(15, 5, 5));
        check("L-shape inside left arm", l.contains(5, 5, 15));
        check("L-shape in the cut-out notch is OUT", !l.contains(15, 5, 15));
        check("L-shape inside elbow", l.contains(5, 5, 5));
        // Triangle.
        PolygonalRegion tri = new PolygonalRegion("t", List.of(
                new PolygonalRegion.Point2(0, 0), new PolygonalRegion.Point2(20, 0),
                new PolygonalRegion.Point2(0, 20)), 0, 5);
        check("triangle inside near origin", tri.contains(2, 2, 2));
        check("triangle outside far corner", !tri.contains(18, 2, 18));
        check("triangle type", tri.type().equals("polygonal"));
        // Degenerate polygons rejected.
        boolean threw = false;
        try { new PolygonalRegion("z", List.of(new PolygonalRegion.Point2(0, 0),
                new PolygonalRegion.Point2(10, 0), new PolygonalRegion.Point2(20, 0)), 0, 5); }
        catch (IllegalArgumentException e) { threw = true; }
        check("collinear (zero-area) polygon rejected", threw);
        boolean threw2 = false;
        try { new PolygonalRegion("z2", List.of(new PolygonalRegion.Point2(0, 0),
                new PolygonalRegion.Point2(1, 1)), 0, 5); }
        catch (IllegalArgumentException e) { threw2 = true; }
        check("<3 points rejected", threw2);
        // minY/maxY normalized regardless of order.
        PolygonalRegion inv = new PolygonalRegion("inv", sq.points(), 50, 10);
        check("poly Y normalized", inv.minY() == 10 && inv.maxY() == 50);
    }

    static void global() {
        GlobalRegion g = new GlobalRegion();
        check("global contains anywhere", g.contains(0, 0, 0)
                && g.contains(1e9, 1e9, 1e9) && g.contains(-1e9, -1e9, -1e9));
        check("global volume sentinel", g.volume() == Long.MAX_VALUE);
        check("global id", g.id().equals(GlobalRegion.ID));
        check("global type", g.type().equals("global"));
    }
}
