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
 * Enforces the {@code dispenser-output} flag and blocks cross-border dispenser grief.
 *
 * <p>A dispenser/dropper is a classic adjacency-grief tool: place one just outside (or in your
 * own region) aimed across the border and it can shoot lava/water buckets, fire charges, splash
 * potions, or eject items into a neighbouring claim. Vanilla offers no cancellable event for
 * dispensing, so we intercept {@link DispenserBlock#dispenseFrom} at HEAD.
 *
 * <p>Two independent checks, either of which cancels the dispense:
 * <ol>
 *   <li><b>{@code dispenser-output} flag</b> at the dispenser's own cell — lets an owner turn a
 *       region's dispensers off entirely (resolved with parents; default ALLOW).</li>
 *   <li><b>Cross-border containment</b> — the cell the dispenser fires INTO (its facing
 *       direction) must not belong to a region the dispenser's own cell isn't part of. This is
 *       the same boundary rule the piston/fluid handlers use, so a dispenser outside a claim
 *       can't fire into it, and one in region A can't fire across into adjacent region B.</li>
 * </ol>
 *
 * <p>Performance: a single {@code hasAnyAt} probe short-circuits the whole check when neither the
 * dispenser nor its target cell is inside any region (the overwhelmingly common case), so normal
 * world dispensers pay almost nothing. Fails open (allows) on any unexpected error so a dispenser
 * can never crash the tick.
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    // require = 0: this is a defensive safety net. dispenseFrom's exact descriptor is stable in
    // 1.21.1 Mojmap (ServerLevel, BlockState, BlockPos), but if a future remap changes it, the
    // injector will simply not apply rather than crash the server at load. The protection just
    // silently turns off for dispensers in that case — never a hard failure.
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true, require = 0)
    private void worldguardneo$gateDispense(ServerLevel level, BlockState state, BlockPos pos,
                                            CallbackInfo ci) {
        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return;
        try {
            if (!mod.isProtectionActive(level)) return;
            RegionManager mgr = mod.regions().get(level);

            // Target cell = the block directly in the dispenser's facing direction (where items/
            // fluids/projectiles are emitted). DispenserBlock exposes the FACING property.
            BlockPos target = pos;
            try {
                Direction facing = state.getValue(DispenserBlock.FACING);
                target = pos.relative(facing);
            } catch (Throwable ignored) {
                // If FACING can't be read for some reason, fall back to the dispenser's own cell.
            }

            // Fast path: nothing region-related at either the dispenser or its target AND the
            // global region has no opinion → vanilla. (The global check keeps a world-wide
            // "dispenser-output deny" effective in wilderness, matching MixinFlagBridge.)
            boolean nearDispenser = mgr.hasAnyAt(pos.getX(), pos.getY(), pos.getZ());
            boolean nearTarget    = mgr.hasAnyAt(target.getX(), target.getY(), target.getZ());
            if (!nearDispenser && !nearTarget) {
                if (mgr.globalRegion().getFlag(Flags.DISPENSER_OUTPUT) == StateFlag.State.DENY) {
                    ci.cancel();
                }
                return;
            }

            // Check 1: dispenser-output flag at the dispenser's own position. No nearDispenser
            // guard: when only the TARGET is in a region, testState falls back to the global
            // value, which must still be able to deny.
            if (!mgr.testState(Flags.DISPENSER_OUTPUT, null, pos.getX(), pos.getY(), pos.getZ())) {
                ci.cancel();
                return;
            }

            // Check 2: cross-border containment. If the target cell belongs to a region the
            // dispenser's cell is not part of, the dispense crosses a protection boundary.
            var dispenserRegions = mgr.getApplicable(pos.getX(), pos.getY(), pos.getZ());
            for (ProtectedRegion r : mgr.getApplicable(target.getX(), target.getY(), target.getZ())) {
                if (!containsId(dispenserRegions, r.id())) {
                    // Foreign region in the line of fire. Allow only if that region's
                    // dispenser-output is explicitly ALLOW (an owner opting in); otherwise block.
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

    /** Resolve a region's dispenser-output (walking parents); default ALLOW when unset. */
    private static boolean resolveDispenserAllow(ProtectedRegion r) {
        StateFlag.State s = null;
        ProtectedRegion cur = r;
        int hops = 0;
        while (cur != null && hops++ < 32) {
            s = cur.getFlag(Flags.DISPENSER_OUTPUT);
            if (s != null) break;
            cur = cur.parent();
        }
        return s != StateFlag.State.DENY; // unset/ALLOW → allowed; only explicit DENY blocks
    }
}
