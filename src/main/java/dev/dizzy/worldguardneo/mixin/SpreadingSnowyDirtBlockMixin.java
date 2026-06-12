package dev.dizzy.worldguardneo.mixin;
import dev.dizzy.worldguardneo.mixinsupport.MixinFlagBridge;

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
 * Gates the random-tick spread logic shared by {@link GrassBlock} and {@link MyceliumBlock}
 * (and any other modded block that subclasses {@link SpreadingSnowyDirtBlock}, since both
 * vanilla grass and mycelium use the same parent for their spread implementation).
 *
 * <p>We dispatch on the concrete class:
 * <ul>
 *   <li>Grass-block tick → {@code GRASS_SPREAD} flag</li>
 *   <li>Mycelium-block tick → {@code MYCELIUM_SPREAD} flag</li>
 *   <li>Modded subclasses → treated as grass-spread by default; admins can swap to
 *       mycelium-spread per region if their block is mycelium-like.</li>
 * </ul>
 */
@Mixin(SpreadingSnowyDirtBlock.class)
public abstract class SpreadingSnowyDirtBlockMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void worldguardneo$gateSpread(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource rng,
            CallbackInfo ci) {
        // Concrete-class dispatch — Mycelium gets its own flag, everything else falls under
        // grass-spread. This keeps the public flag set semantically clean.
        Object self = this;
        if (self instanceof MyceliumBlock) {
            if (!MixinFlagBridge.myceliumSpread(level, pos)) ci.cancel();
        } else {
            // GrassBlock + any modded SpreadingSnowyDirtBlock subclass that isn't mycelium.
            if (!MixinFlagBridge.grassSpread(level, pos)) ci.cancel();
        }
    }
}
