package dev.thefather007.worldguardneo.integrations;

import dev.thefather007.worldguardneo.region.PolygonalRegion;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.List;

/**
 * Pure marker-footprint geometry shared by the BlueMap and Squaremap integrations: turning a
 * region's block bounds into the XZ corner coordinates a web-map marker needs.
 *
 * <p>Extracted from the reflection-heavy integration code (which can't be unit-tested without the
 * map mods present) so this math — notably the {@code +1} block-inclusive expansion, where an
 * off-by-one would silently mis-size every marker — is covered by {@code MarkerGeometryTest}.
 */
public final class MarkerGeometry {

    private MarkerGeometry() {}

    /**
     * The four XZ corners of a cuboid region's footprint, counter-clockwise from the min corner:
     * {@code (minX,minZ) → (maxX+1,minZ) → (maxX+1,maxZ+1) → (minX,maxZ+1)}. The {@code +1} makes the
     * footprint cover the FULL block extent — block column max really reaches world coordinate max+1.
     * BlueMap consumes all four corners (a ShapeMarker); Squaremap uses corner 0 and corner 2 as the
     * opposite corners of a rectangle.
     */
    public static double[][] cuboidCorners(Vec3 min, Vec3 max) {
        double x0 = min.x(), z0 = min.z();
        double x1 = max.x() + 1.0, z1 = max.z() + 1.0;
        return new double[][] { {x0, z0}, {x1, z0}, {x1, z1}, {x0, z1} };
    }

    /** The XZ coordinate of each polygon vertex, in order: {@code out[i] = {x, z}}. */
    public static double[][] polygonPoints(List<PolygonalRegion.Point2> pts) {
        double[][] out = new double[pts.size()][];
        for (int i = 0; i < pts.size(); i++) {
            PolygonalRegion.Point2 p = pts.get(i);
            out[i] = new double[] { p.x(), p.z() };
        }
        return out;
    }
}
