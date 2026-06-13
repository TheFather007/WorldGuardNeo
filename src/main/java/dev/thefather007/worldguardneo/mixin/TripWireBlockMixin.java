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
 * Stops a tripwire inside a protected region from being tripped by non-members, mobs, or thrown
 * items — the same grief class as a pressure plate, which {@link PressurePlateBlockMixin} already
 * covers. Tripwires fire on entity collision (not a right-click), so the interact/use command
 * handlers never see them.
 *
 * <p>Vanilla calls {@link TripWireBlock#entityInside} whenever an entity is inside the wire; that
 * is what kicks off {@code checkPressed} and powers the connected hooks. We inject at HEAD and
 * cancel it when the triggering entity isn't an authorised member of the controlling region —
 * the wire then never updates its powered state for that entity.
 *
 * <p>Note: a tripwire's redstone OUTPUT is also independently governed by the {@code redstone}
 * flag (the neighbour-notify handler), but that is opt-in; this membership gate matches the
 * "strangers can't operate my claim's mechanisms" default that pressure plates already enforce.
 *
 * <p>{@code require = 0}: if a future remap changes {@code entityInside}, the injector simply
 * doesn't apply rather than crashing — protection silently turns off, never a hard failure.
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
