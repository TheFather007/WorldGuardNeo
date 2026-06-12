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
 * <p>Why a mixin and not the {@code EntityInteractSpecific} event: armor-stand equipping runs
 * through {@code ArmorStand#interactAt}, which vanilla calls from the server packet handler.
 * The Forge/NeoForge {@code EntityInteractSpecific} event fires too late and a server-side
 * cancel there does NOT reliably stop the swap (MinecraftForge issue #9040) — the armor moves
 * anyway. Intercepting {@code interactAt} directly at HEAD is the only place that reliably
 * prevents the equip before vanilla mutates the stand's inventory.
 *
 * <p>Gate: a player may manipulate the stand only if {@code testBuildAccess} allows BUILD at the
 * stand's position — i.e. they own/are a member of the region, or a flag explicitly opens it.
 * Returning {@link InteractionResult#PASS} tells vanilla the interaction did nothing, so no
 * armor is taken or placed. Players with {@code region.bypass} are let through untouched.
 *
 * <p>Left-click destruction of the stand is handled separately by {@code AttackEntityEvent}
 * (already gated). This mixin only covers the right-click equip/unequip path.
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
            // Explicit bypass node lets admins/owners-by-permission manage stands anywhere.
            if (mod.perms().has(sp, "worldguardneo.region.bypass")) return;

            RegionManager mgr = mod.regions().get(sl);
            int x = (int) Math.floor(self.getX());
            int y = (int) Math.floor(self.getY());
            int z = (int) Math.floor(self.getZ());
            UUID id = sp.getUUID();
            // Build-access semantics: owners/members pass; strangers are denied unless a flag
            // explicitly re-allows build/interact at this spot.
            if (!mgr.testBuildAccess(Flags.BUILD, x, y, z, id)
                    || !mgr.testBuildAccess(Flags.INTERACT, x, y, z, id)) {
                sp.displayClientMessage(
                        Component.literal(mod.i18n().raw("msg.interact.decoration-denied")), true);
                // Record to the violation log so admins see armor-stand tampering attempts.
                mod.violations().record(sp, "armor-stand", "swap-denied",
                        x, y, z, sl.dimension().location().toString(), topRegionId(mgr, x, y, z));
                cir.setReturnValue(InteractionResult.PASS); // no swap happens
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
