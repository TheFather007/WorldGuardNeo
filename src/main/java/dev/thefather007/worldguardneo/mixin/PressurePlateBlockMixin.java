package dev.thefather007.worldguardneo.mixin;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Stops a wooden/stone pressure plate inside a protected region from firing for non-members.
 *
 * <p>Targets the CONCRETE {@link PressurePlateBlock} (covers wooden and stone plates), not the
 * abstract {@code BasePressurePlateBlock} — the signal method is abstract there, so a mixin on
 * the base class has no method body to inject into and crashes at load (insnNode null). The
 * concrete subclass provides the real {@code getSignalStrength(Level, BlockPos)} body.
 *
 * <p>Covers two grief vectors that click/interact handlers can't catch (plates fire on entity
 * collision, not a right-click):
 * <ul>
 *   <li><b>Стоя на плите</b> (задача 1): a stranger steps on a plate in a claim and powers it.</li>
 *   <li><b>Брошенный предмет</b> (задача 2): a wooden plate detects all entities, so a thrown
 *       item lands on it and triggers it.</li>
 * </ul>
 *
 * <p>Rule: a plate inside a region outputs signal only if at least one MEMBER of the controlling
 * region is standing on it. Strangers, mobs, and items never authorise it. Wilderness plates are
 * untouched (fast {@code hasAnyAt} probe returns immediately).
 */
@Mixin(PressurePlateBlock.class)
public abstract class PressurePlateBlockMixin {

    @Inject(method = "getSignalStrength", at = @At("HEAD"), cancellable = true)
    private void worldguardneo$gatePlate(Level level, BlockPos pos,
                                         CallbackInfoReturnable<Integer> cir) {
        if (!(level instanceof ServerLevel sl)) return;
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return;

        try {
            if (!mod.isProtectionActive(sl)) return;
            RegionManager mgr = mod.regions().get(sl);
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            // Fast path: no region here AND the global region has no USE opinion → vanilla,
            // no allocation. The global check keeps a world-wide "use deny" effective on
            // wilderness plates (testBuildAccess below falls back to the global value).
            if (!mgr.hasAnyAt(x, y, z)
                    && mgr.globalRegion().getFlag(Flags.USE) == null) return;

            // Inside a region: allow only if a member of the controlling region stands on it.
            AABB box = TOUCH_AABB.move(pos);
            List<Entity> on = level.getEntities((Entity) null, box);
            for (Entity e : on) {
                if (e instanceof Player pl
                        && mgr.testBuildAccess(Flags.USE, x, y, z, pl.getUUID())) {
                    return; // authorised presser present → let vanilla compute normally
                }
            }
            // No authorised presser → force zero so the plate stays unpressed.
            cir.setReturnValue(0);
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("pressure-plate gate failed", t);
        }
    }

    /**
     * Vanilla's pressure-plate touch box: full footprint inset 1/8 on horizontal edges, 1/4 tall.
     * Matches what the plate itself scans so our membership check sees the same entities.
     */
    private static final AABB TOUCH_AABB =
            new AABB(0.0625, 0.0, 0.0625, 0.9375, 0.25, 0.9375);
}
