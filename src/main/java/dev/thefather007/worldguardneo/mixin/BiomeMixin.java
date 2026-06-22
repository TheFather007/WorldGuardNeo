package dev.thefather007.worldguardneo.mixin;
import dev.thefather007.worldguardneo.mixinsupport.MixinFlagBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces {@link Biome#shouldFreeze} and {@link Biome#shouldSnow} to return false where the
 * corresponding WGN flag is DENY. {@code shouldFreeze} has two overloads (2-arg and 3-arg
 * {@code mustBeAtEdge}) that vanilla calls from different paths, so both are injected.
 */
@Mixin(Biome.class)
public abstract class BiomeMixin {

    @Inject(
        method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void worldguardneo$gateIceFormShort(
            LevelReader reader, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (reader instanceof net.minecraft.server.level.ServerLevel sl
                && !MixinFlagBridge.iceForm(sl, pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
        method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void worldguardneo$gateIceFormLong(
            LevelReader reader, BlockPos pos, boolean mustBeAtEdge,
            CallbackInfoReturnable<Boolean> cir) {
        if (reader instanceof net.minecraft.server.level.ServerLevel sl
                && !MixinFlagBridge.iceForm(sl, pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
        method = "shouldSnow(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void worldguardneo$gateSnowFall(
            LevelReader reader, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (reader instanceof net.minecraft.server.level.ServerLevel sl
                && !MixinFlagBridge.snowFall(sl, pos)) {
            cir.setReturnValue(false);
        }
    }
}
