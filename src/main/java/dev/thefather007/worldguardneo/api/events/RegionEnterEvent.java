package dev.thefather007.worldguardneo.api.events;

import dev.thefather007.worldguardneo.region.ProtectedRegion;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * Fired when a player crosses into a region.
 *
 * <p>This fires AFTER WorldGuardNeo has determined the player is now inside the region —
 * the player's position is already inside at the time of dispatch. The {@code greeting}
 * message (if any) has NOT yet been shown when this fires, so listeners can suppress it
 * by reading the region's flag values themselves.
 *
 * <p>Not cancellable: deciding whether a player can enter is a {@code entry} flag concern,
 * not an event concern. Use the {@link RegionFlagDeniedEvent} if you want to react to
 * blocked entries.
 *
 * <p>One event per region per crossing. If a player enters 3 nested regions simultaneously
 * (e.g. teleport into a parent + child + grandchild), 3 events fire in priority order.
 *
 * <p>Fired on the server thread. Listeners must not block.
 */
public final class RegionEnterEvent extends Event {

    private final ServerPlayer player;
    private final ProtectedRegion region;

    public RegionEnterEvent(ServerPlayer player, ProtectedRegion region) {
        this.player = player;
        this.region = region;
    }

    /** The player that entered. Never null. */
    public ServerPlayer getPlayer() { return player; }

    /** The region that was entered. Never null. */
    public ProtectedRegion getRegion() { return region; }
}
