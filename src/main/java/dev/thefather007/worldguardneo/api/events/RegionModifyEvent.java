package dev.thefather007.worldguardneo.api.events;

import dev.thefather007.worldguardneo.region.ProtectedRegion;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

/**
 * Fired when a region is created, modified, or deleted.
 *
 * <p>This is the integration point for mods that need to mirror WGN's region state
 * elsewhere — alternate map renderers, sidebar plugins, audit logs, etc.
 *
 * <p>Dispatched AFTER the change has been persisted to the in-memory RegionManager.
 * Disk persistence may not have happened yet (saves are debounced ~5s), but the
 * regional manager will return the new state for subsequent queries.
 *
 * <p>Fired on the server thread, synchronously, from the command handler.
 *
 * <p>Not cancellable — undoing a region change cleanly is too complex for an event
 * (children, owners, parent links may all need to be reverted).
 */
public final class RegionModifyEvent extends Event {

    public enum ModifyType {
        /** Region was created (e.g. {@code /rg claim}). */
        CREATED,
        /** Region was updated — bounds redefined, owners changed, flags set, etc. */
        UPDATED,
        /** Region was deleted ({@code /rg remove}). The region object reflects pre-delete state. */
        DELETED
    }

    private final ServerLevel level;
    private final ProtectedRegion region;
    private final ModifyType type;
    private final @Nullable net.minecraft.world.entity.Entity actor;

    public RegionModifyEvent(ServerLevel level, ProtectedRegion region, ModifyType type,
                              @Nullable net.minecraft.world.entity.Entity actor) {
        this.level = level;
        this.region = region;
        this.type = type;
        this.actor = actor;
    }

    public ServerLevel getLevel() { return level; }
    public ProtectedRegion getRegion() { return region; }
    public ModifyType getType() { return type; }

    /** The entity that initiated the change. Null if from console or background task. */
    public @Nullable net.minecraft.world.entity.Entity getActor() { return actor; }
}
