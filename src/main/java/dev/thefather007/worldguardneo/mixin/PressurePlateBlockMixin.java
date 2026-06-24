package dev.thefather007.worldguardneo.mixin;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.flags.StateFlag;
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
 * Stops a pressure plate inside a region from firing for non-members. Plates fire on entity
 * collision (not a right-click), so interact handlers can't catch a stranger stepping on one or a
 * thrown item landing on it. Rule: outputs signal only if a controlling-region MEMBER is on it.
 *
 * <p>Targets the CONCRETE {@link PressurePlateBlock}, not abstract {@code BasePressurePlateBlock} —
 * the signal method is abstract there, so a mixin on the base class has no body to inject and
 * crashes at load (insnNode null).
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
            // Fast path: no region here AND global USE isn't an explicit DENY → vanilla, no entity
            // scan. Only a world-wide "use deny" needs enforcing on wilderness plates; a global
            // ALLOW (or unset) means the plate just fires normally, so skip the allocation/scan.
            if (!mgr.hasAnyAt(x, y, z)
                    && mgr.globalRegion().getFlag(Flags.USE) != StateFlag.State.DENY) return;

            // Allow only if a member of the controlling region is standing on it.
            AABB box = TOUCH_AABB.move(pos);
            List<Entity> on = level.getEntities((Entity) null, box);
            for (Entity e : on) {
                if (e instanceof Player pl
                        && mgr.testBuildAccess(Flags.USE, x, y, z, pl.getUUID())) {
                    return; // authorised presser → vanilla computes normally
                }
            }
            cir.setReturnValue(0); // no authorised presser → plate stays unpressed
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("pressure-plate gate failed", t);
        }
    }

    /** Vanilla's pressure-plate touch box, so our membership scan sees the same entities. */
    private static final AABB TOUCH_AABB =
            new AABB(0.0625, 0.0, 0.0625, 0.9375, 0.25, 0.9375);
}
