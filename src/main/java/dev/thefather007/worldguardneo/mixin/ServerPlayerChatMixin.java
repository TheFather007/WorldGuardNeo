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
 * Enforces the {@code receive-chat} flag: a player standing in a region where {@code receive-chat}
 * is DENY does not receive other players' chat messages. This is the RECEIVER side — the
 * complement of {@code send-chat} (which gates the sender via {@code ServerChatEvent}). There is no
 * per-recipient NeoForge event, so we intercept the per-player delivery method directly.
 *
 * <p>{@link ServerPlayer#sendChatMessage} is vanilla's per-recipient chat delivery (system messages
 * use a different method, so those still get through). Cancelling at HEAD simply suppresses the
 * message for this receiver. {@code require = 0} (mixin-config default): a future remap just skips
 * the injection rather than crashing — protection turns off, never a hard failure.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerChatMixin {

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void worldguardneo$gateReceiveChat(OutgoingChatMessage message, boolean filtered,
                                               ChatType.Bound boundType, CallbackInfo ci) {
        if (!MixinFlagBridge.receiveChat((ServerPlayer) (Object) this)) ci.cancel();
    }
}
