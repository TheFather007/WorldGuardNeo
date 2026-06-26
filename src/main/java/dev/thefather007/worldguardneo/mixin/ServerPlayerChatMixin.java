package dev.thefather007.worldguardneo.mixin;

import dev.thefather007.worldguardneo.mixinsupport.MixinFlagBridge;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces the receiver side of {@code receive-chat}: a player in a region where it's DENY doesn't
 * receive others' chat. Complement of {@code send-chat}; there's no per-recipient NeoForge event,
 * so we cancel vanilla's per-recipient {@link ServerPlayer#sendChatMessage} at HEAD (system
 * messages use a different method and still get through).
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerChatMixin {

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void worldguardneo$gateReceiveChat(OutgoingChatMessage message, boolean filtered,
                                               ChatType.Bound boundType, CallbackInfo ci) {
        if (!MixinFlagBridge.receiveChat((ServerPlayer) (Object) this)) ci.cancel();
    }
}
