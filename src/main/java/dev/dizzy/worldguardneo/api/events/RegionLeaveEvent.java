package dev.dizzy.worldguardneo.api.events;

import dev.dizzy.worldguardneo.region.ProtectedRegion;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * Fired when a player exits a region.
 *
 * <p>Mirrors {@link RegionEnterEvent}. Fires AFTER WorldGuardNeo has determined the
 * player is now outside the region. The {@code farewell} message (if any) has not yet
 * been shown at dispatch time.
 *
 * <p>Not cancellable: blocking exit is the {@code exit} flag's job. Use
 * {@link RegionFlagDeniedEvent} to observe exit-denials.
 *
 * <p>One event per region per crossing. On a multi-region exit (e.g. /tp out of nested
 * regions), several events fire in priority order — highest-priority region first.
 *
 * <p>Fired on the server thread. Listeners must not block.
 */
public final class RegionLeaveEvent extends Event {

    private final ServerPlayer player;
    private final ProtectedRegion region;

    public RegionLeaveEvent(ServerPlayer player, ProtectedRegion region) {
        this.player = player;
        this.region = region;
    }

    public ServerPlayer getPlayer() { return player; }
    public ProtectedRegion getRegion() { return region; }
}
