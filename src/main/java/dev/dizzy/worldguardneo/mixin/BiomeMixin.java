package dev.dizzy.worldguardneo.mixin;
import dev.dizzy.worldguardneo.mixinsupport.MixinFlagBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Force {@link Biome#shouldFreeze} and {@link Biome#shouldSnow} to return false at
 * positions inside regions where the corresponding WGN flag is DENY.
 *
 * <p>Biome has two overloads of {@code shouldFreeze}: a 2-arg form and a 3-arg form
 * that takes an extra {@code mustBeAtEdge} boolean. Vanilla calls both depending on
 * the path (chunk precipitation tick vs. waterloggable-block checks), so we inject
 * into both via two separate {@code @Inject} targets sharing the same gate logic.
 *
 * <p>The {@code LevelReader} passed in is the {@code ServerLevel}; we narrow with an
 * {@code instanceof} check inside {@link MixinFlagBridge#iceForm}.
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
