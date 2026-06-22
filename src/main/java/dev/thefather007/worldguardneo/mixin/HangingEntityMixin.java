package dev.thefather007.worldguardneo.mixin;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.HangingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Protects {@link HangingEntity} (item frames, paintings) from INDIRECT destruction where
 * {@code build} or {@code block-break} is DENY. Vanilla drops the entity when {@link
 * HangingEntity#survives()} returns false — i.e. its supporting wall is broken by a piston, water
 * flow, explosion, worldgen, etc. None of these fire {@code AttackEntityEvent}, so we force
 * {@code survives()} to return true inside a protected region, keeping the entity stuck in place.
 *
 * <p>Trade-off: a bypass player must left-click the frame directly to relocate it (that path fires
 * AttackEntityEvent and respects bypass). Runs every entity tick, so the fast path is allocation-free.
 */
@Mixin(HangingEntity.class)
public abstract class HangingEntityMixin {

    @Inject(method = "survives", at = @At("HEAD"), cancellable = true)
    private void worldguardneo$preventIndirectDrop(CallbackInfoReturnable<Boolean> cir) {
        HangingEntity self = (HangingEntity) (Object) this;
        if (!(self.level() instanceof ServerLevel sl)) return;

        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return; // pre-init or shutdown

        try {
            if (!mod.isProtectionActive(sl)) return;
            RegionManager mgr = mod.regions().get(sl);
            // floor, not (int) cast: truncation toward zero would map x=-3.7 to block -3 (one off),
            // missing frames near a region border in negative coords.
            int x = (int) Math.floor(self.getX());
            int y = (int) Math.floor(self.getY());
            int z = (int) Math.floor(self.getZ());
            if (!mgr.hasAnyAt(x, y, z)
                    && mgr.globalRegion().getFlag(Flags.BUILD) == null
                    && mgr.globalRegion().getFlag(Flags.BLOCK_BREAK) == null) {
                return; // wilderness — let vanilla decide
            }
            // EITHER build or block-break denying → force survives() = true. No actor here (indirect
            // destruction without attribution), so owner-group resolution doesn't apply.
            if (!mgr.testState(Flags.BUILD,        null, x, y, z)
             || !mgr.testState(Flags.BLOCK_BREAK,  null, x, y, z)) {
                cir.setReturnValue(true);
            }
        } catch (Throwable t) {
            // Never crash the entity tick; debug-only since this runs per hanging entity per tick.
            WorldGuardNeo.LOGGER.debug("HangingEntity survives() gate failed", t);
        }
    }
}
