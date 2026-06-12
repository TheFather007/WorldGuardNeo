package dev.thefather007.worldguardneo.permissions;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * Selects a {@link PermissionResolver} backend. LuckPerms is preferred when present
 * and enabled by the caller; otherwise falls back to OP-level mapping.
 */
public final class PermissionService implements PermissionResolver {

    private final PermissionResolver delegate;

    private PermissionService(PermissionResolver delegate) { this.delegate = delegate; }

    /**
     * Pick the best resolver. Caller passes {@code useLuckPerms} explicitly to avoid
     * forming a cycle with the still-constructing {@code WorldGuardNeo} instance.
     */
    public static PermissionService detect(boolean useLuckPerms) {
        boolean lpAvailable = ModList.get().isLoaded("luckperms");
        if (lpAvailable && useLuckPerms) {
            try {
                WorldGuardNeo.LOGGER.info("[WorldGuardNeo] Using LuckPerms for permissions.");
                return new PermissionService(new LuckPermsResolver());
            } catch (Throwable t) {
                WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] LuckPerms init failed, falling back to OP", t);
            }
        }
        WorldGuardNeo.LOGGER.info("[WorldGuardNeo] Using OP-level permissions.");
        return new PermissionService(new OpResolver());
    }

    @Override public boolean has(ServerPlayer p, String node)     { return delegate.has(p, node); }
    @Override public boolean has(CommandSourceStack s, String n)  { return delegate.has(s, n); }
    @Override public String  primaryGroup(ServerPlayer p)         { return delegate.primaryGroup(p); }
    @Override public boolean isInGroup(ServerPlayer p, String g)  { return delegate.isInGroup(p, g); }
    @Override public java.util.Collection<String> allGroups(ServerPlayer p) { return delegate.allGroups(p); }
    @Override public void    reload()                             { delegate.reload(); }
    @Override public String  name()                               { return delegate.name(); }

    public PermissionResolver delegate() { return delegate; }
}
