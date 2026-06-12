package dev.dizzy.worldguardneo.mixin;

import dev.dizzy.worldguardneo.WorldGuardNeo;
import dev.dizzy.worldguardneo.flags.Flags;
import dev.dizzy.worldguardneo.region.RegionManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.HangingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Protects {@link HangingEntity} (item frames, paintings) from "indirect" destruction in
 * regions where {@code build} or {@code block-break} is DENY.
 *
 * <p>Vanilla calls {@link HangingEntity#survives()} from the entity's tick loop. If it
 * returns false, the entity drops as an item and is discarded. That happens when:
 * <ul>
 *   <li>The supporting block behind the frame/painting is broken (by another player,
 *       a piston push, water flow, or any other indirect cause).</li>
 *   <li>A piston pushes the wall the frame is attached to.</li>
 *   <li>A bed/respawn-anchor explosion clears the supporting block while the explosion
 *       itself doesn't directly damage the entity.</li>
 *   <li>Worldgen replacement (e.g. trail-ruin features overwriting a wall).</li>
 * </ul>
 *
 * <p>None of these paths fire {@code AttackEntityEvent} or {@code ProjectileImpactEvent}
 * — they're block-update side effects that {@code HangingEntity} self-checks via {@code
 * survives()}. By forcing {@code survives()} to return {@code true} when the entity is
 * inside a protected region, we make the entity "stuck" — it stays in place even when
 * its wall is gone. The wall itself is protected by {@code BlockEvent.BreakEvent}; this
 * mixin covers the case where the wall is destroyed by something we can't intercept.
 *
 * <p>Trade-off: a player with bypass who legitimately wants to relocate a frame must
 * left-click it directly (which fires AttackEntityEvent and respects bypass).
 *
 * <p>Fast-path: short-circuits to vanilla when {@link WorldGuardNeo} isn't loaded yet or
 * the level isn't a {@link ServerLevel}. This mixin runs every entity tick, so the
 * allocation-free probe is critical.
 */
@Mixin(HangingEntity.class)
public abstract class HangingEntityMixin {

    @Inject(method = "survives", at = @At("HEAD"), cancellable = true)
    private void worldguardneo$preventIndirectDrop(CallbackInfoReturnable<Boolean> cir) {
        // Cast to HangingEntity to read position. The mixin target IS HangingEntity, so
        // this cast is safe at runtime.
        HangingEntity self = (HangingEntity) (Object) this;
        if (!(self.level() instanceof ServerLevel sl)) return;

        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return; // pre-init or shutdown

        try {
            RegionManager mgr = mod.regions().get(sl);
            // Allocation-free fast probe: if no spatial-index entry under us AND globalRegion
            // has no BUILD opinion, vanilla survives() decides. Only when a region IS nearby
            // do we run the full state-resolution.
            int x = (int) self.getX(), y = (int) self.getY(), z = (int) self.getZ();
            if (!mgr.hasAnyAt(x, y, z)
                    && mgr.globalRegion().getFlag(Flags.BUILD) == null
                    && mgr.globalRegion().getFlag(Flags.BLOCK_BREAK) == null) {
                return; // wilderness — let vanilla decide
            }
            // If EITHER build or block-break denies at this position, force survives() = true
            // so vanilla doesn't drop the entity. Owner-group resolution doesn't apply here
            // because there's no "actor" — this is an indirect destruction without attribution.
            if (!mgr.testState(Flags.BUILD,        null, x, y, z)
             || !mgr.testState(Flags.BLOCK_BREAK,  null, x, y, z)) {
                cir.setReturnValue(true);
            }
        } catch (Throwable t) {
            // Never let a mixin path crash the entity tick. Log at debug only — this runs
            // every entity tick for every hanging entity on the server.
            WorldGuardNeo.LOGGER.debug("HangingEntity survives() gate failed", t);
        }
    }
}
