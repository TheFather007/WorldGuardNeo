package dev.thefather007.worldguardneo.region;

import dev.thefather007.worldguardneo.util.Vec3;

public final class CuboidRegion extends ProtectedRegion {

    private Vec3 min;
    private Vec3 max;

    public CuboidRegion(String id, Vec3 a, Vec3 b) {
        super(id);
        this.min = a.min(b);
        this.max = a.max(b);
    }

    /**
     * Mutates the cuboid's bounds. <b>WARNING</b>: if this region is registered in a
     * {@link RegionManager}, the spatial index is NOT automatically updated. Callers must
     * remove and re-add the region (or call {@code RegionManager.rebuildIndex()}) for
     * lookups to reflect the new bounds. Prefer constructing a new region and using
     * {@code /rg redefine}.
     */
    public void setCorners(Vec3 a, Vec3 b) {
        this.min = a.min(b);
        this.max = a.max(b);
    }

    @Override public boolean contains(double x, double y, double z) {
        return x >= min.x() && x <  max.x() + 1.0
            && y >= min.y() && y <  max.y() + 1.0
            && z >= min.z() && z <  max.z() + 1.0;
    }

    @Override public Vec3 minimumBound() { return min; }
    @Override public Vec3 maximumBound() { return max; }
    @Override public String type()       { return "cuboid"; }

    @Override public long volume() { return min.volumeWith(max); }
}
