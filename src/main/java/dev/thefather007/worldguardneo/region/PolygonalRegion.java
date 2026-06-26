package dev.thefather007.worldguardneo.region;

import dev.thefather007.worldguardneo.util.Vec3;

import java.util.List;

/**
 * A 2D polygon extruded vertically between two Y values (XZ polygon × Y range).
 *
 * Immutable after construction. Bounds and volume are computed once and cached because
 * they are read frequently (spatial-index registration, overlap checks, /rg info).
 */
public final class PolygonalRegion extends ProtectedRegion {

    public record Point2(int x, int z) {}

    private final List<Point2> points;
    private final int minY;
    private final int maxY;

    // Cached AABB and volume, computed in the constructor.
    private final Vec3 minBound;
    private final Vec3 maxBound;
    private final long volume;

    public PolygonalRegion(String id, List<Point2> points, int minY, int maxY) {
        super(id);
        if (points.size() < 3) throw new IllegalArgumentException("Polygon needs >=3 points");
        this.points = List.copyOf(points); // immutable
        this.minY   = Math.min(minY, maxY);
        this.maxY   = Math.max(minY, maxY);

        // AABB
        int mnx = Integer.MAX_VALUE, mnz = Integer.MAX_VALUE;
        int mxx = Integer.MIN_VALUE, mxz = Integer.MIN_VALUE;
        for (Point2 p : this.points) {
            int x = p.x(), z = p.z();
            if (x < mnx) mnx = x; if (x > mxx) mxx = x;
            if (z < mnz) mnz = z; if (z > mxz) mxz = z;
        }
        this.minBound = new Vec3(mnx, this.minY, mnz);
        this.maxBound = new Vec3(mxx, this.maxY, mxz);

        // Shoelace × height.
        long sum = 0;
        int n = this.points.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            sum += (long) this.points.get(j).x() * this.points.get(i).z()
                 - (long) this.points.get(i).x() * this.points.get(j).z();
        }
        long area = Math.abs(sum) / 2L;
        if (area == 0L)
            throw new IllegalArgumentException("Polygon has zero area: " + id);
        this.volume = area * ((long)(this.maxY - this.minY) + 1L);
    }

    /** Returns the polygon's points. The returned list is immutable (from {@link List#copyOf}). */
    public List<Point2> points() { return points; }
    public int minY() { return minY; }
    public int maxY() { return maxY; }

    @Override public boolean contains(double x, double y, double z) {
        // Block-inclusive Y like CuboidRegion: [minY, maxY+1) so y=maxY+1 is the block above (excluded).
        if (y < minY || y >= maxY + 1.0) return false;
        // Quick AABB reject before the ray-cast. Same exclusive-upper rule.
        if (x < minBound.x() || x >= maxBound.x() + 1.0
         || z < minBound.z() || z >= maxBound.z() + 1.0) return false;
        // Ray-cast in XZ, but test the BLOCK the position sits in (floor), not the raw coordinate:
        // vertices are block coords, so a continuous position on the max +X/+Z block row (e.g. x=10.5
        // vs vertex 10) would otherwise read OUTSIDE and leave that row unprotected. Flooring matches
        // CuboidRegion's [min, max+1) and WorldEdit's block-based contains. Math.floor needed for negatives.
        double bx = Math.floor(x), bz = Math.floor(z);
        boolean inside = false;
        int n = points.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            int xi = points.get(i).x(), zi = points.get(i).z();
            int xj = points.get(j).x(), zj = points.get(j).z();
            boolean intersect = ((zi > bz) != (zj > bz))
                    && (bx < (double)(xj - xi) * (bz - zi) / (double)(zj - zi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    @Override public Vec3   minimumBound() { return minBound; }
    @Override public Vec3   maximumBound() { return maxBound; }
    @Override public String type()         { return "polygonal"; }
    @Override public long   volume()       { return volume; }

    @Override public ProtectedRegion withId(String newId) {
        return new PolygonalRegion(newId, points, minY, maxY);
    }
}
