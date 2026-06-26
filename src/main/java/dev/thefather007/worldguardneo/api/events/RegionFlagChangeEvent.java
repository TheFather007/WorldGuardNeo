package dev.thefather007.worldguardneo.api.events;

import dev.thefather007.worldguardneo.flags.Flag;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.region.RegionGroup;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

/**
 * Fired when a flag's value on a region is changed through {@code /rg flag} (set, clear, or group
 * change). Complements {@link RegionModifyEvent} with the specific flag delta, so integrations can
 * react to (or mirror/log) a single flag edit without diffing the whole region.
 *
 * <p>Dispatched on the server thread, synchronously, AFTER the change has been applied to the
 * in-memory region (disk persistence may be queued). Not cancellable — the change is already in
 * effect; use it for observation, mirroring and logging.
 */
public final class RegionFlagChangeEvent extends Event {

    private final ServerLevel level;
    private final ProtectedRegion region;
    private final Flag<?> flag;
    private final @Nullable Object oldValue;
    private final @Nullable Object newValue;
    private final RegionGroup group;
    private final @Nullable net.minecraft.world.entity.Entity actor;

    public RegionFlagChangeEvent(ServerLevel level, ProtectedRegion region, Flag<?> flag,
                                 @Nullable Object oldValue, @Nullable Object newValue,
                                 RegionGroup group, @Nullable net.minecraft.world.entity.Entity actor) {
        this.level = level;
        this.region = region;
        this.flag = flag;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.group = group;
        this.actor = actor;
    }

    public ServerLevel getLevel()        { return level; }
    public ProtectedRegion getRegion()   { return region; }
    public Flag<?> getFlag()             { return flag; }

    /** The value before the change ({@code null} if the flag was unset). */
    public @Nullable Object getOldValue() { return oldValue; }
    /** The value after the change ({@code null} if the flag was cleared). */
    public @Nullable Object getNewValue() { return newValue; }
    /** The region-group filter in effect for the flag after the change. */
    public RegionGroup getGroup()        { return group; }

    /** The entity that initiated the change. Null if from console or a background task. */
    public @Nullable net.minecraft.world.entity.Entity getActor() { return actor; }
}
