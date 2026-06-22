package dev.thefather007.worldguardneo.mixin;
import dev.thefather007.worldguardneo.mixinsupport.MixinFlagBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels ice-melt random ticks where {@code ice-melt} is DENY. Only normal {@link IceBlock} melts
 * (packed/blue ice are separate non-melting blocks). Cancelling at HEAD skips the melt entirely —
 * no observers, neighbor updates, or item drops.
 */
@Mixin(IceBlock.class)
public abstract class IceBlockMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void worldguardneo$gateIceMelt(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource rng,
            CallbackInfo ci) {
        if (!MixinFlagBridge.iceMelt(level, pos)) ci.cancel();
    }
}
