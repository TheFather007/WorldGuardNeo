package dev.dizzy.worldguardneo.mixin;
import dev.dizzy.worldguardneo.mixinsupport.MixinFlagBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels leaf decay (random tick from {@link LeavesBlock#randomTick}) inside
 * regions flagged {@code leaf-decay = DENY}. This is the most commonly requested
 * flag for natural-park or roof-garden regions, where players don't want leaves
 * vanishing when distant from their supporting logs.
 *
 * Performance note: leaves are extremely common, but {@code randomTick} only fires
 * for blocks whose distance-from-log property is high enough to be a decay candidate.
 * The early-exit in {@link MixinFlagBridge#check} (empty spatial bucket → permissive)
 * keeps the overhead well below 0.1% server-tick CPU on a typical chunk-loaded grid.
 */
@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void worldguardneo$gateLeafDecay(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource rng,
            CallbackInfo ci) {
        if (!MixinFlagBridge.leafDecay(level, pos)) ci.cancel();
    }
}
