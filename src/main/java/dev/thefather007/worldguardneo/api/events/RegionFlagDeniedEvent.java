package dev.thefather007.worldguardneo.api.events;

import dev.thefather007.worldguardneo.flags.Flag;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Fired when a region flag denies a block-break action.
 *
 * <p>NOTE: As of current implementation, this event fires only on {@code BLOCK_BREAK} /
 * {@code BUILD} denials in {@link dev.thefather007.worldguardneo.listeners.BlockEventHandler}.
 * Other denial paths (place, interact, PVP, mob damage, fire spread, etc.) do NOT
 * currently fire this event — they cancel the underlying NeoForge event directly.
 * Listeners that want to grant exceptions on those paths should subscribe to the
 * NeoForge events directly with a HIGH priority and uncancel as needed.
 *
 * <p>Future versions may extend coverage to other denial paths. The event signature
 * is stable; what changes is the set of triggers.
 *
 * <p>Implements {@link ICancellableEvent} — listeners can call {@code setCanceled(true)}
 * to OVERRIDE the denial and let the action proceed. This is the integration point for
 * mods that want to grant special exceptions (e.g. "creative mode players bypass all
 * region restrictions in their own claim"). Use cautiously — bypassing protection
 * defeats its purpose.
 *
 * <p>Cancellation semantics:
 * <ul>
 *   <li>{@code setCanceled(false)} (default) — the denial stands; the action stays blocked</li>
 *   <li>{@code setCanceled(true)} — the denial is overridden; the action is permitted</li>
 * </ul>
 *
 * <p>Fired SYNCHRONOUSLY from the protection-check call site, so the underlying
 * Minecraft event isn't yet cancelled when listeners run. WorldGuardNeo will check
 * this event's {@code isCanceled()} state after dispatch.
 *
 * <p>The actor may be null for non-player triggers (mob attacks, explosions, dispensers).
 */
public final class RegionFlagDeniedEvent extends Event implements ICancellableEvent {

    private final ProtectedRegion region;
    private final Flag<?> flag;
    private final @Nullable Entity actor;
    private final String reason;
    private boolean canceled;

    public RegionFlagDeniedEvent(ProtectedRegion region, Flag<?> flag,
                                  @Nullable Entity actor, String reason) {
        this.region = region;
        this.flag = flag;
        this.actor = actor;
        this.reason = reason;
    }

    /** The region whose flag caused the denial. */
    public ProtectedRegion getRegion() { return region; }

    /** The flag that denied the action (e.g. Flags.BUILD, Flags.PVP). */
    public Flag<?> getFlag() { return flag; }

    /**
     * The entity attempting the action. May be null if the action wasn't entity-attributable
     * (e.g. world-level effects like explosion damage to blocks).
     */
    public @Nullable Entity getActor() { return actor; }

    /** Free-text reason — for logging/debugging only. Subject to change without notice. */
    public String getReason() { return reason; }

    @Override public boolean isCanceled() { return canceled; }
    @Override public void setCanceled(boolean cancel) { this.canceled = cancel; }
}
