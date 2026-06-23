package dev.thefather007.worldguardneo.region;

import dev.thefather007.worldguardneo.util.Vec3;

/**
 * A virtual region with no geometry. Its flags apply to every block in the world
 * but it is overridden by any "real" region a player happens to be standing in.
 */
public final class GlobalRegion extends ProtectedRegion {

    public static final String ID = "__global__";

    private static final Vec3 MIN = new Vec3(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
    private static final Vec3 MAX = new Vec3(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    public GlobalRegion() { super(ID); }

    @Override public boolean contains(double x, double y, double z) { return true; }
    @Override public Vec3 minimumBound() { return MIN; }
    @Override public Vec3 maximumBound() { return MAX; }
    @Override public String type()       { return "global"; }
    @Override public long   volume()     { return Long.MAX_VALUE; }

    /** The global region is a singleton fallback and is never renamed or copied. */
    @Override public ProtectedRegion withId(String newId) {
        throw new UnsupportedOperationException("the global region cannot be renamed");
    }
}
