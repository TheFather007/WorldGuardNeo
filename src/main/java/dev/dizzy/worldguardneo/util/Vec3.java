package dev.dizzy.worldguardneo.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/** Immutable integer 3D vector used for region bounds. Mutability would lead to bugs. */
public record Vec3 (int x, int y, int z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public static Vec3 of(BlockPos bp) { return new Vec3(bp.getX(), bp.getY(), bp.getZ()); }
    public static Vec3 of(Vec3i v)     { return new Vec3(v.getX(), v.getY(), v.getZ()); }

    public BlockPos toBlockPos() { return new BlockPos(x, y, z); }

    public Vec3 min(Vec3 o) { return new Vec3(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z)); }
    public Vec3 max(Vec3 o) { return new Vec3(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z)); }

    /**
     * Volume of the AABB defined by this vector and {@code o}, inclusive.
     * Uses {@code long} arithmetic so a 50M-volume region won't overflow.
     */
    public long volumeWith(Vec3 o) {
        long dx = Math.abs((long) o.x - x) + 1L;
        long dy = Math.abs((long) o.y - y) + 1L;
        long dz = Math.abs((long) o.z - z) + 1L;
        return dx * dy * dz;
    }

    @Override public String toString() { return "(" + x + "," + y + "," + z + ")"; }
}
