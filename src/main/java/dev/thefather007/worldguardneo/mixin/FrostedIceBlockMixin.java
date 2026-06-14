package dev.thefather007.worldguardneo.mixin;
import dev.thefather007.worldguardneo.mixinsupport.MixinFlagBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FrostedIceBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Frost Walker-style ice degradation inside regions flagged
 * {@code frosted-ice-melt = DENY}. Frosted ice is the temporary ice that
 * Frost Walker boots create.
 */
@Mixin(FrostedIceBlock.class)
public abstract class FrostedIceBlockMixin {

    // Target "tick", NOT "randomTick": frosted ice melts/ages via its SCHEDULED tick (which calls
    // slightlyMelt), scheduled from onPlace/neighborChanged. FrostedIceBlock does not declare
    // randomTick (it would only inherit IceBlock's light-based melt, the wrong path), so injecting
    // into "randomTick" silently found no method and — with the global require=0 — did nothing,
    // leaving frosted-ice-melt=DENY unenforced. tick has the same descriptor.
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void worldguardneo$gateFrostedMelt(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource rng,
            CallbackInfo ci) {
        if (!MixinFlagBridge.frostedIceMelt(level, pos)) ci.cancel();
    }
}
