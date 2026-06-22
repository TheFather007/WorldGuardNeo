package dev.thefather007.worldguardneo.mixin;
import dev.thefather007.worldguardneo.mixinsupport.MixinFlagBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.MyceliumBlock;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gates the random-tick spread shared by {@link GrassBlock}, {@link MyceliumBlock}, and any
 * modded {@link SpreadingSnowyDirtBlock} subclass (all share the parent's spread impl). Dispatches
 * on concrete class: mycelium → {@code MYCELIUM_SPREAD}, everything else → {@code GRASS_SPREAD}.
 */
@Mixin(SpreadingSnowyDirtBlock.class)
public abstract class SpreadingSnowyDirtBlockMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void worldguardneo$gateSpread(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource rng,
            CallbackInfo ci) {
        Object self = this;
        if (self instanceof MyceliumBlock) {
            if (!MixinFlagBridge.myceliumSpread(level, pos)) ci.cancel();
        } else {
            if (!MixinFlagBridge.grassSpread(level, pos)) ci.cancel();
        }
    }
}
