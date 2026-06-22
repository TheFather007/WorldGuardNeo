package dev.thefather007.worldguardneo.mixin;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.flags.StateFlag;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces {@code dispenser-output} and blocks cross-border dispenser grief (a dispenser aimed
 * across a border can shoot lava/water/fire/potions/items into a neighbouring claim). Vanilla has
 * no cancellable dispense event, so we intercept {@link DispenserBlock#dispenseFrom} at HEAD.
 *
 * <p>Two independent checks, either of which cancels: (1) {@code dispenser-output} at the
 * dispenser's own cell; (2) cross-border containment — the target cell must not belong to a region
 * the dispenser's cell isn't part of (same boundary rule as the piston/fluid handlers). A
 * {@code hasAnyAt} probe short-circuits when neither cell is in a region. Fails open on error.
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    // require = 0: if a future remap changes dispenseFrom's descriptor, the injector silently
    // skips rather than crashing at load — protection just turns off for dispensers.
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true, require = 0)
    private void worldguardneo$gateDispense(ServerLevel level, BlockState state, BlockPos pos,
                                            CallbackInfo ci) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return;
        try {
            if (!mod.isProtectionActive(level)) return;
            RegionManager mgr = mod.regions().get(level);

            // Target cell = the block the dispenser fires into (its FACING direction).
            BlockPos target = pos;
            try {
                Direction facing = state.getValue(DispenserBlock.FACING);
                target = pos.relative(facing);
            } catch (Throwable ignored) {
                // FACING unreadable → fall back to the dispenser's own cell.
            }

            // Fast path: nothing region-related at either cell AND no global opinion → vanilla.
            // The global check keeps a world-wide "dispenser-output deny" effective in wilderness.
            boolean nearDispenser = mgr.hasAnyAt(pos.getX(), pos.getY(), pos.getZ());
            boolean nearTarget    = mgr.hasAnyAt(target.getX(), target.getY(), target.getZ());
            if (!nearDispenser && !nearTarget) {
                if (mgr.globalRegion().getFlag(Flags.DISPENSER_OUTPUT) == StateFlag.State.DENY) {
                    ci.cancel();
                }
                return;
            }

            // Check 1: dispenser-output at the dispenser's own cell. No nearDispenser guard — when
            // only the TARGET is in a region, testState falls back to the global value, which must
            // still be able to deny.
            if (!mgr.testState(Flags.DISPENSER_OUTPUT, null, pos.getX(), pos.getY(), pos.getZ())) {
                ci.cancel();
                return;
            }

            // Check 2: cross-border containment.
            var dispenserRegions = mgr.getApplicable(pos.getX(), pos.getY(), pos.getZ());
            for (ProtectedRegion r : mgr.getApplicable(target.getX(), target.getY(), target.getZ())) {
                if (!containsId(dispenserRegions, r.id())) {
                    // Foreign region in the line of fire: allow only on explicit ALLOW (owner opt-in).
                    if (resolveDispenserAllow(r)) continue;
                    ci.cancel();
                    return;
                }
            }
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("dispenser gate failed", t);
        }
    }

    private static boolean containsId(java.util.List<ProtectedRegion> list, String id) {
        for (ProtectedRegion r : list) if (r.id().equals(id)) return true;
        return false;
    }

    /**
     * Resolve a region's dispenser-output (walking parents) for a CROSS-BORDER dispense. Only an
     * EXPLICIT ALLOW opts in; unset means protected. (Treating unset as allowed was a boundary
     * bug that let outside dispensers fire across the border into unconfigured regions.)
     */
    private static boolean resolveDispenserAllow(ProtectedRegion r) {
        StateFlag.State s = null;
        ProtectedRegion cur = r;
        int hops = 0;
        while (cur != null && hops++ < 32) {
            s = cur.getFlag(Flags.DISPENSER_OUTPUT);
            if (s != null) break;
            cur = cur.parent();
        }
        return s == StateFlag.State.ALLOW; // only an explicit ALLOW permits cross-border dispensing
    }
}
