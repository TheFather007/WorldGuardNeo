package dev.thefather007.worldguardneo.mixin;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a tripwire inside a region from being tripped by non-members, mobs, or thrown items — same
 * grief class as a pressure plate ({@link PressurePlateBlockMixin}). Fires on entity collision, so
 * interact handlers never see it. We cancel {@link TripWireBlock#entityInside} at HEAD unless the
 * triggering entity is an authorised member, so the wire never powers its hooks for that entity.
 *
 * <p>{@code require = 0}: a future remap of {@code entityInside} skips the injector rather than
 * crashing — protection silently turns off.
 */
@Mixin(TripWireBlock.class)
public abstract class TripWireBlockMixin {

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true, require = 0)
    private void worldguardneo$gateTripwire(BlockState state, Level level, BlockPos pos,
                                            Entity entity, CallbackInfo ci) {
        if (!(level instanceof ServerLevel sl)) return;
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return;
        try {
            if (!mod.isProtectionActive(sl)) return;
            RegionManager mgr = mod.regions().get(sl);
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            // Fast path: no region here AND no global USE opinion → vanilla, no allocation.
            if (!mgr.hasAnyAt(x, y, z) && mgr.globalRegion().getFlag(Flags.USE) == null) return;
            // An authorised member (USE build-access) trips it normally.
            if (entity instanceof Player pl && mgr.testBuildAccess(Flags.USE, x, y, z, pl.getUUID())) return;
            // Stranger, mob, item, or projectile → don't let it trip the wire.
            ci.cancel();
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("tripwire gate failed", t);
        }
    }
}
