// MarkerGeometryTest — the pure marker-footprint math shared by the BlueMap and Squaremap
// integrations (MarkerGeometry). The reflection/lifecycle of those integrations can't be exercised
// without the map mods on the classpath, but this is where the actual coordinate bugs would hide:
// the +1 block-inclusive expansion and polygon-point mapping. Pure JVM.
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.integrations.MarkerGeometry;
import dev.thefather007.worldguardneo.region.PolygonalRegion;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.List;

public final class MarkerGeometryTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }
    static void eq(String n, double a, double b) { check(n + " (" + a + " vs " + b + ")", a == b); }

    public static void main(String[] args) {
        cuboidInclusive();
        cuboidNegative();
        cuboidUnitBlock();
        cuboidOppositeCornersForRectangle();
        polygon();
        polygonOrderPreserved();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    // Block region (0,0)..(9,9) covers world coords [0,10) on each axis → corners 0..10.
    static void cuboidInclusive() {
        double[][] c = MarkerGeometry.cuboidCorners(new Vec3(0, 60, 0), new Vec3(9, 80, 9));
        check("4 corners", c.length == 4);
        eq("c0.x", c[0][0], 0.0);   eq("c0.z", c[0][1], 0.0);     // min
        eq("c1.x", c[1][0], 10.0);  eq("c1.z", c[1][1], 0.0);     // maxX+1, minZ
        eq("c2.x", c[2][0], 10.0);  eq("c2.z", c[2][1], 10.0);    // maxX+1, maxZ+1
        eq("c3.x", c[3][0], 0.0);   eq("c3.z", c[3][1], 10.0);    // minX, maxZ+1
    }

    // Negative coords must keep the +1 on the MAX side (block -1 reaches world coord 0).
    static void cuboidNegative() {
        double[][] c = MarkerGeometry.cuboidCorners(new Vec3(-5, 0, -5), new Vec3(-1, 0, -1));
        eq("neg min.x", c[0][0], -5.0); eq("neg min.z", c[0][1], -5.0);
        eq("neg max+1.x", c[2][0], 0.0); eq("neg max+1.z", c[2][1], 0.0);
    }

    // A single block (5,5)..(5,5) is a 1x1 footprint: (5,5) to (6,6).
    static void cuboidUnitBlock() {
        double[][] c = MarkerGeometry.cuboidCorners(new Vec3(5, 0, 5), new Vec3(5, 0, 5));
        eq("unit min.x", c[0][0], 5.0); eq("unit min.z", c[0][1], 5.0);
        eq("unit max+1.x", c[2][0], 6.0); eq("unit max+1.z", c[2][1], 6.0);
        // Footprint area (Squaremap rectangle) must be exactly one block (1x1).
        double w = c[2][0] - c[0][0], h = c[2][1] - c[0][1];
        check("unit area = 1", w * h == 1.0);
    }

    // Squaremap builds a rectangle from corner 0 (min) and corner 2 (max+1); they must be the
    // opposite corners of the AABB, and the diagonal must be strictly positive both axes.
    static void cuboidOppositeCornersForRectangle() {
        double[][] c = MarkerGeometry.cuboidCorners(new Vec3(10, 0, 20), new Vec3(30, 0, 50));
        check("rect corner0 < corner2 on x", c[0][0] < c[2][0]);
        check("rect corner0 < corner2 on z", c[0][1] < c[2][1]);
        eq("rect width = span+1", c[2][0] - c[0][0], 21.0);   // 30-10+1
        eq("rect depth = span+1", c[2][1] - c[0][1], 31.0);   // 50-20+1
    }

    static void polygon() {
        var pts = List.of(new PolygonalRegion.Point2(0, 0),
                          new PolygonalRegion.Point2(10, 0),
                          new PolygonalRegion.Point2(5, 8));
        double[][] p = MarkerGeometry.polygonPoints(pts);
        check("3 points", p.length == 3);
        // Polygon points are NOT +1 expanded — vertices map straight through (x,z).
        eq("p0.x", p[0][0], 0.0);  eq("p0.z", p[0][1], 0.0);
        eq("p1.x", p[1][0], 10.0); eq("p1.z", p[1][1], 0.0);
        eq("p2.x", p[2][0], 5.0);  eq("p2.z", p[2][1], 8.0);
    }

    // Vertex order must be preserved (a reorder would deform the rendered polygon).
    static void polygonOrderPreserved() {
        var pts = List.of(new PolygonalRegion.Point2(-3, 7),
                          new PolygonalRegion.Point2(99, -2),
                          new PolygonalRegion.Point2(40, 40),
                          new PolygonalRegion.Point2(0, 100));
        double[][] p = MarkerGeometry.polygonPoints(pts);
        check("count matches", p.length == pts.size());
        boolean ok = true;
        for (int i = 0; i < pts.size(); i++) {
            if (p[i][0] != pts.get(i).x() || p[i][1] != pts.get(i).z()) ok = false;
        }
        check("order + values preserved", ok);
    }
}
