package dev.thefather007.worldguardneo.api.events;

import dev.thefather007.worldguardneo.flags.Flag;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Fired (synchronously, cancellable) when a region flag denies an action, letting other mods
 * OVERRIDE the denial: {@code setCanceled(true)} permits the action. Covers block-break/place,
 * interact, chest-access and PvP denials; purely environmental ones (spread, ticks, dispensers)
 * don't fire it. The actor may be null for non-player triggers.
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

    /**
     * Post a denial event for {@code region} and return whether a listener OVERRODE it
     * ({@code setCanceled(true)} → the caller should permit the action). Central helper used by
     * every denial path so coverage stays consistent.
     */
    public static boolean isOverridden(ProtectedRegion region, Flag<?> flag,
                                       @Nullable Entity actor, String reason) {
        RegionFlagDeniedEvent e = new RegionFlagDeniedEvent(region, flag, actor, reason);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(e);
        return e.isCanceled();
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
