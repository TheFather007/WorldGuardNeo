package dev.thefather007.worldguardneo.permissions;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fallback resolver when LuckPerms is not installed. Maps permission nodes to required OP levels
 * (defaults: admin=3, mod=2; unknown nodes default to 2). Uses {@link Object2IntOpenHashMap} to
 * avoid boxing on the lookup path, which runs on most player actions.
 */
public final class OpResolver implements PermissionResolver {

    private static final int DEFAULT_LEVEL = 2;

    /** Permission-node → required OP level. Primitive-valued to avoid boxing on every check. */
    private final Object2IntOpenHashMap<String> nodeToLevel = new Object2IntOpenHashMap<>();

    public OpResolver() {
        nodeToLevel.defaultReturnValue(DEFAULT_LEVEL);
        applyConfigLevels();
    }

    /**
     * Rebuild the node→level table from the current config snapshot. Called by {@code /rg reload}
     * to pick up changed {@code defaultOpLevelAdmin}/{@code Mod}. Safe to call repeatedly; clears
     * any custom levels added via {@link #setLevel}.
     */
    public void applyConfigLevels() {
        nodeToLevel.clear();

        // Defer to config's admin/mod levels when WGN is booted; else vanilla-style 3/2.
        int admin = 3, mod = 2;
        try {
            var modInst = dev.thefather007.worldguardneo.WorldGuardNeo.get();
            if (modInst != null && modInst.config() != null && modInst.config().global() != null) {
                admin = modInst.config().global().defaultOpLevelAdmin;
                mod   = modInst.config().global().defaultOpLevelMod;
            }
        } catch (Throwable ignored) {}

        // Region-management nodes.
        // region.bypass is level 5 — ABOVE vanilla's max op level (4) — so OP never auto-grants this
        // protection-critical node. It must be granted explicitly (e.g. via LuckPerms).
        nodeToLevel.put("worldguardneo.region.bypass",        5);
        nodeToLevel.put("worldguardneo.region.admin",         admin);
        nodeToLevel.put("worldguardneo.region.claim",         0);
        nodeToLevel.put("worldguardneo.region.info",          0);
        nodeToLevel.put("worldguardneo.region.info.others",   mod);
        // OP 4 — global flags can reveal server-internal balance choices, so keep it admin-only.
        nodeToLevel.put("worldguardneo.region.info.global",   4);
        nodeToLevel.put("worldguardneo.region.list",          0);
        nodeToLevel.put("worldguardneo.region.list.others",   mod);
        nodeToLevel.put("worldguardneo.region.lists.radius",  mod);
        nodeToLevel.put("worldguardneo.region.delete",        0);   // own regions
        nodeToLevel.put("worldguardneo.region.delete.others", admin);   // arbitrary regions
        nodeToLevel.put("worldguardneo.region.redefine",      mod);
        nodeToLevel.put("worldguardneo.region.rename",        mod);   // /rg rename — change a region's id (OP 2)
        nodeToLevel.put("worldguardneo.region.audit",         mod);   // /rg audit — view a region's recent changes (OP 2)
        nodeToLevel.put("worldguardneo.region.select",        mod);   // /rg select — load into selection (OP 2)
        nodeToLevel.put("worldguardneo.region.undo",          mod);   // /rg undo — restore last deleted (OP 2)
        nodeToLevel.put("worldguardneo.region.transfer",      admin); // /rg transfer — sole-ownership handoff (OP 3)
        nodeToLevel.put("worldguardneo.region.addowner",      mod);
        nodeToLevel.put("worldguardneo.region.addmember",     mod);
        nodeToLevel.put("worldguardneo.region.removeowner",   mod);
        nodeToLevel.put("worldguardneo.region.removemember",  mod);
        nodeToLevel.put("worldguardneo.region.teleport",      mod);  // /rg teleport — OP 2+ or the node (was OP 0)

        // Flag-editing nodes.
        nodeToLevel.put("worldguardneo.region.flag.others",   mod);   // edit foreign regions
        nodeToLevel.put("worldguardneo.region.flag.bypass",   admin); // skip per-flag check
        nodeToLevel.put("worldguardneo.region.flag.group",    mod);   // use -g group syntax
        nodeToLevel.put("worldguardneo.region.flag.priority", mod);
        nodeToLevel.put("worldguardneo.region.flag.parent",   mod);
        nodeToLevel.put("worldguardneo.region.flags.list",    mod);  // /rg flags: op-2 or node

        // Misc. Selection nodes are OP 0 (everyone) to mirror open claiming; each has its own
        // node so admins can restrict them individually. selection.use gates the wand item.
        nodeToLevel.put("worldguardneo.selection.use",        0);
        nodeToLevel.put("worldguardneo.selection.mode",       0);   // /rg sel cuboid|poly|clear
        nodeToLevel.put("worldguardneo.selection.pos1",       0);   // /rg pos1
        nodeToLevel.put("worldguardneo.selection.pos2",       0);   // /rg pos2
        nodeToLevel.put("worldguardneo.selection.point",      0);   // /rg point
        // Explicit-coords pos can place a corner anywhere (and from console), so OP 4.
        nodeToLevel.put("worldguardneo.selection.pos.coords", 4);   // /rg pos1|pos2 <x y z>
        nodeToLevel.put("worldguardneo.selection.wand",       0);   // /rg wand — mirrors selection.use
        // reload (swaps live config) and backup (disk write + rotation) are OP 4 — dangerous enough
        // to sit alongside bypass/info.global; automation should use the explicit LP node.
        nodeToLevel.put("worldguardneo.reload",               4);
        nodeToLevel.put("worldguardneo.backup",               4);
        nodeToLevel.put("worldguardneo.migrate",              4);   // /rg migrate — convert storage backend
        nodeToLevel.put("worldguardneo.notify",               mod);
    }

    public void setLevel(String node, int level) { nodeToLevel.put(node, level); }

    /** Re-read config values and rebuild the node→level mapping. */
    @Override public void reload() { applyConfigLevels(); }

    @Override
    public boolean has(ServerPlayer player, String node) {
        // Unknown nodes fall to the default level (2). getInt avoids Integer allocation.
        int required = nodeToLevel.getInt(node);
        if (required == 0) return true;
        return player.hasPermissions(required);
    }

    @Override public String name() { return "op-level"; }
}
