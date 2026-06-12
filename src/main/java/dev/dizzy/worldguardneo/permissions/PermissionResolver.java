package dev.dizzy.worldguardneo.permissions;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** Abstraction over LuckPerms / op-level / always-allow permission checks. */
public interface PermissionResolver {

    /** Has a permission node (LuckPerms style: "worldguardneo.region.claim"). */
    boolean has(ServerPlayer player, String node);

    /** Like {@link #has} but for any command source (console/cmd block returns true). */
    default boolean has(CommandSourceStack src, String node) {
        if (src.getEntity() instanceof ServerPlayer p) return has(p, node);
        return true;
    }

    /** Best-effort group lookup. May return null. */
    default String primaryGroup(ServerPlayer player) { return null; }

    /** Does the player belong to a permissions group with given name? */
    default boolean isInGroup(ServerPlayer player, String group) { return false; }

    /**
     * All groups a player inherits (including the primary). Used by per-group region-limit
     * resolution: we pick the maximum limit across all matching groups. Default impl
     * returns an empty collection — backends without a real group concept (e.g. OpResolver)
     * fall through to the global limit cleanly.
     */
    default java.util.Collection<String> allGroups(ServerPlayer player) { return java.util.List.of(); }

    /**
     * Re-read any config-driven state from {@code WGConfig}. Called by {@code /rg reload}
     * so admins can change resolver settings (e.g. {@code defaultOpLevelAdmin}) at runtime.
     * Default is a no-op for resolvers without config state.
     */
    default void reload() {}

    String name();
}
