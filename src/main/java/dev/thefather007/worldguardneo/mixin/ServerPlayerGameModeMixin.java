package dev.thefather007.worldguardneo.mixin;

import net.minecraft.server.level.ServerPlayerGameMode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppresses the vanilla "Mismatch in destroy block pos" console spam.
 *
 * <p>This warning is logged by {@link ServerPlayerGameMode} whenever a block-destroy action
 * is interrupted before completion — which is EXACTLY what happens every time WorldGuardNeo
 * (or vanilla spawn-protection, for that matter) denies a break: the client keeps sending
 * destroy packets for a block the server refuses to break, and their positions no longer line
 * up. It's a long-standing vanilla quirk (Mojang MC-166432 / MC-167828, present 1.15–1.21) and
 * is purely cosmetic — the protection itself works correctly. On a busy server with players
 * probing claim borders it floods the console and buries real messages.
 *
 * <p>We surgically no-op ONLY the {@code LOGGER.warn(...)} call inside
 * {@code handleBlockBreakAction} via {@link Redirect}, leaving every other behaviour of the
 * method untouched. Denied-break feedback (action bar) and the violation log are emitted by
 * our own {@code BlockEventHandler}, so suppressing this vanilla line loses no information.
 *
 * <p>If a future mapping renames the method, the redirect simply won't apply and the warning
 * returns — it can never break the game, because the injection is optional ({@code require = 0}
 * is set in the mixin JSON's injector defaults).
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    /**
     * Intercept the {@code LOGGER.warn("Mismatch in destroy block pos: {} {}", ...)} call and
     * drop it. The redirect signature must match the exact {@code Logger.warn} overload used
     * (String format + two Object args). Doing nothing here removes the line from the console.
     */
    @Redirect(
        method = "handleBlockBreakAction",
        at = @At(
            value = "INVOKE",
            target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"
        ),
        require = 0   // optional: if mappings change, just skip rather than crash
    )
    private void worldguardneo$silenceDestroyMismatch(Logger logger, String msg, Object a, Object b) {
        // Intentionally empty — swallow the "Mismatch in destroy block pos" warning. Only this
        // specific 2-arg warn call inside handleBlockBreakAction is affected; all other logging
        // in the class uses different call sites and is untouched.
        if (msg != null && msg.startsWith("Mismatch in destroy block pos")) {
            return; // suppressed
        }
        // Defensive: if this overload is ever reused for a different message, preserve it.
        logger.warn(msg, a, b);
    }
}
