package dev.thefather007.worldguardneo.mixin;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.region.RegionManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Blocks swapping armor on/off an {@link ArmorStand} inside a region the player can't build in.
 *
 * <p>Mixin rather than the {@code EntityInteractSpecific} event: that event fires too late and a
 * server-side cancel doesn't reliably stop the swap (MinecraftForge #9040). Intercepting
 * {@code interactAt} at HEAD is the only place that prevents the equip before vanilla mutates the
 * stand's inventory. Left-click destruction is handled separately by {@code AttackEntityEvent}.
 */
@Mixin(ArmorStand.class)
public abstract class ArmorStandMixin {

    @Inject(method = "interactAt", at = @At("HEAD"), cancellable = true)
    private void worldguardneo$gateArmorSwap(Player player, Vec3 hitPos, InteractionHand hand,
                                             CallbackInfoReturnable<InteractionResult> cir) {
        ArmorStand self = (ArmorStand) (Object) this;
        if (!(self.level() instanceof ServerLevel sl)) return; // client predicts; server decides
        if (!(player instanceof ServerPlayer sp)) return;

        WorldGuardNeo mod = WorldGuardNeo.get();
        if (mod == null) return; // pre-init / shutdown — let vanilla run

        try {
            if (!mod.isProtectionActive(sl)) return;
            if (mod.perms().has(sp, "worldguardneo.region.bypass")) return;

            RegionManager mgr = mod.regions().get(sl);
            int x = (int) Math.floor(self.getX());
            int y = (int) Math.floor(self.getY());
            int z = (int) Math.floor(self.getZ());
            UUID id = sp.getUUID();
            // Owners/members pass; strangers denied unless a flag explicitly re-allows here.
            if (!mgr.testBuildAccess(Flags.BUILD, x, y, z, id)
                    || !mgr.testBuildAccess(Flags.INTERACT, x, y, z, id)) {
                sp.displayClientMessage(
                        Component.literal(mod.i18n().raw("msg.interact.decoration-denied")), true);
                mod.violations().record(sp, "armor-stand", "swap-denied",
                        x, y, z, sl.dimension().location().toString(), topRegionId(mgr, x, y, z));
                cir.setReturnValue(InteractionResult.PASS);
            }
        } catch (Throwable t) {
            // Never crash the interaction path; fail open to vanilla on unexpected errors.
            WorldGuardNeo.LOGGER.debug("ArmorStand interact gate failed", t);
        }
    }

    /** Top applicable region id at a position, or null for wilderness — for the violation log. */
    private static String topRegionId(RegionManager mgr, int x, int y, int z) {
        var applicable = mgr.getApplicable(x, y, z);
        return applicable.isEmpty() ? null : applicable.get(0).id();
    }
}
