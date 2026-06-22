package dev.thefather007.worldguardneo.mixin;

import net.minecraft.server.level.ServerPlayerGameMode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppresses the vanilla "Mismatch in destroy block pos" console spam, which fires every time a
 * break is denied (the client keeps sending destroy packets for a block the server won't break).
 * Purely cosmetic vanilla quirk (MC-166432 / MC-167828, 1.15–1.21) that floods busy servers.
 *
 * <p>Redirects ONLY the {@code LOGGER.warn(...)} call inside {@code handleBlockBreakAction}; all
 * other behaviour is untouched and our own feedback/violation log is unaffected. If a future
 * mapping renames the method the redirect just won't apply (optional, {@code require = 0}).
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    // Signature must match the exact Logger.warn overload used (String + two Object args).
    @Redirect(
        method = "handleBlockBreakAction",
        at = @At(
            value = "INVOKE",
            target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"
        ),
        require = 0   // optional: if mappings change, just skip rather than crash
    )
    private void worldguardneo$silenceDestroyMismatch(Logger logger, String msg, Object a, Object b) {
        if (msg != null && msg.startsWith("Mismatch in destroy block pos")) {
            return; // suppressed
        }
        // Defensive: if this overload is ever reused for a different message, preserve it.
        logger.warn(msg, a, b);
    }
}
