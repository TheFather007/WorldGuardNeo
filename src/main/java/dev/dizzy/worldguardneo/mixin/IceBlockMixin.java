package dev.dizzy.worldguardneo.mixin;
import dev.dizzy.worldguardneo.mixinsupport.MixinFlagBridge;

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
 * Cancels ice-melt random ticks inside regions where {@code ice-melt} is DENY.
 *
 * {@code IceBlock} handles regular (frosty) ice melting into water via its
 * {@link IceBlock#randomTick} override. Packed ice and blue ice are separate blocks that do
 * not melt at all, so they aren't affected here — only normal ice is gated.
 * We inject at HEAD with {@code cancellable = true} so the vanilla melt logic
 * is skipped entirely — no observers, no neighbor updates, no item drops.
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
