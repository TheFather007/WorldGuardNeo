package dev.thefather007.worldguardneo.permissions;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;

/**
 * Production permission backend that delegates to LuckPerms.
 * Only constructed when LuckPerms is detected on the mod list.
 */
public final class LuckPermsResolver implements PermissionResolver {

    /**
     * Lazily-resolved LuckPerms API handle. We must NOT call {@link LuckPermsProvider#get()}
     * in the constructor: WorldGuardNeo builds its permission service inside the mod
     * constructor, which runs during the FML loading phase BEFORE LuckPerms has finished
     * registering its API. Calling get() there throws {@code NotLoadedException} and forces a
     * permanent fall-back to OP even though LuckPerms is present and working. Instead we fetch
     * the handle on first real use (a permission check during gameplay), by which point the
     * API is always available. Cached after the first successful lookup.
     */
    private LuckPerms apiHandle;
    private final OpResolver opFallback = new OpResolver();

    public LuckPermsResolver() {
        // Intentionally empty — see apiHandle doc. Construction must never touch the LP API.
    }

    /**
     * Returns the LuckPerms API, or null if it isn't loaded yet. Callers fall back to OP
     * when null, so an early call (before LP finished init) degrades gracefully instead of
     * throwing.
     */
    private LuckPerms api() {
        LuckPerms h = apiHandle;
        if (h == null) {
            try {
                h = LuckPermsProvider.get();
                apiHandle = h;
            } catch (IllegalStateException notLoaded) {
                // LP not ready yet — caller will use the OP fallback for this lookup.
                return null;
            }
        }
        return h;
    }

    @Override
    public boolean has(ServerPlayer player, String node) {
        LuckPerms api = api();
        if (api == null) return opFallback.has(player, node);
        User user = api.getUserManager().getUser(player.getUUID());
        if (user == null) {
            // User not loaded by LP yet (e.g. just joined). Defer to OP-level rather than
            // outright deny — otherwise the first few ticks of a player's session are broken.
            return opFallback.has(player, node);
        }
        QueryOptions opts = api.getContextManager().getQueryOptions(user).orElseGet(api.getContextManager()::getStaticQueryOptions);
        CachedPermissionData data = user.getCachedData().getPermissionData(opts);
        Tristate t = data.checkPermission(node);
        // LuckPerms overrides, OP level is the default — the standard model players expect:
        //   TRUE      → explicitly granted in LP ⇒ allow.
        //   FALSE     → explicitly denied in LP ⇒ deny (LP can revoke an OP's access).
        //   UNDEFINED → not mentioned in LP ⇒ fall back to the per-node OP-level default
        //               (OpResolver). This is what makes the mod work out of the box: an
        //               operator (or any player, for OP-0 nodes like claim/info/list) gets the
        //               documented defaults without an admin having to grant every worldguardneo
        //               node in LP first. Previously UNDEFINED hard-denied, which hid the whole
        //               /rg command tree from admins who manage perms via groups — "commands
        //               don't exist". region.bypass stays OP-5 in OpResolver, so it is still
        //               never granted by OP alone; it must be an explicit LP grant.
        if (t == Tristate.TRUE)  return true;
        if (t == Tristate.FALSE) return false;
        return opFallback.has(player, node);
    }

    @Override
    public String primaryGroup(ServerPlayer player) {
        LuckPerms api = api();
        if (api == null) return null;
        User user = api.getUserManager().getUser(player.getUUID());
        return user != null ? user.getPrimaryGroup() : null;
    }

    @Override
    public boolean isInGroup(ServerPlayer player, String group) {
        LuckPerms api = api();
        if (api == null) return false;
        User user = api.getUserManager().getUser(player.getUUID());
        if (user == null) return false;
        return user.getInheritedGroups(QueryOptions.nonContextual()).stream()
                .anyMatch(g -> g.getName().equalsIgnoreCase(group));
    }

    /**
     * Returns the names of every inherited group of the player. Used by per-group
     * region-limit resolution. Returns empty if the user isn't loaded yet — the caller
     * falls back to the global limit, matching the OpResolver behaviour.
     */
    @Override
    public java.util.Collection<String> allGroups(ServerPlayer player) {
        LuckPerms api = api();
        if (api == null) return java.util.List.of();
        User user = api.getUserManager().getUser(player.getUUID());
        if (user == null) return java.util.List.of();
        return user.getInheritedGroups(QueryOptions.nonContextual()).stream()
                .map(net.luckperms.api.model.group.Group::getName)
                .toList();
    }

    /**
     * Refresh config-driven state. LuckPerms itself manages its own permission cache, so we
     * just propagate to the OP fallback resolver — that's what holds config-dependent
     * level mappings.
     */
    @Override public void reload() { opFallback.reload(); }

    @Override public String name() { return "luckperms"; }
}
