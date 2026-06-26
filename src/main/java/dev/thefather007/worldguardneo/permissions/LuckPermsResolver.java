package dev.thefather007.worldguardneo.permissions;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;

/**
 * Production permission backend delegating to LuckPerms. Only constructed when LuckPerms is
 * detected on the mod list.
 */
public final class LuckPermsResolver implements PermissionResolver {

    /**
     * Lazily-resolved LuckPerms API handle. We must NOT call {@link LuckPermsProvider#get()} in the
     * constructor: it runs during FML loading before LP has registered its API, which would throw
     * and force a permanent OP fallback. We fetch on first real use (a gameplay check) instead, and
     * cache the handle.
     */
    private LuckPerms apiHandle;
    private final OpResolver opFallback = new OpResolver();

    public LuckPermsResolver() {
        // Intentionally empty — construction must never touch the LP API (see apiHandle).
    }

    /** Returns the LuckPerms API, or null if not loaded yet (caller falls back to OP). */
    private LuckPerms api() {
        LuckPerms h = apiHandle;
        if (h == null) {
            try {
                h = LuckPermsProvider.get();
                apiHandle = h;
            } catch (IllegalStateException notLoaded) {
                return null; // LP not ready yet — caller uses the OP fallback
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
            // User not loaded by LP yet (e.g. just joined) — defer to OP rather than deny.
            return opFallback.has(player, node);
        }
        QueryOptions opts = api.getContextManager().getQueryOptions(user).orElseGet(api.getContextManager()::getStaticQueryOptions);
        CachedPermissionData data = user.getCachedData().getPermissionData(opts);
        Tristate t = data.checkPermission(node);
        // "LP overrides, OP is the default": TRUE → allow, FALSE → deny (LP can revoke OP),
        // UNDEFINED → fall back to the per-node OP-level default so the mod works out of the box.
        // region.bypass stays OP-5 in OpResolver, so OP alone never grants it — explicit LP only.
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
     * Names of every inherited group, for per-group region-limit resolution. Empty if the user
     * isn't loaded yet — caller falls back to the global limit.
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

    /** LP manages its own cache; just propagate to the OP fallback (holds config-driven levels). */
    @Override public void reload() { opFallback.reload(); }

    @Override public String name() { return "luckperms"; }
}
