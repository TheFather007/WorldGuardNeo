package dev.thefather007.worldguardneo.permissions;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fallback resolver when LuckPerms is not installed. Maps known permission node prefixes
 * to OP levels (defaults: admin=3, mod=2). Unknown nodes default to op-level 2.
 *
 * Server operators can tune the mapping by calling {@link #setLevel}.
 *
 * Uses {@link Object2IntOpenHashMap} to avoid boxing on the lookup path — permission
 * checks happen on most player actions and the difference adds up on busy servers.
 */
public final class OpResolver implements PermissionResolver {

    private static final int DEFAULT_LEVEL = 2;

    /**
     * Permission-node → required OP level. Primitive-valued map so {@link #has} doesn't
     * box/unbox Integer on every check. The {@code defaultReturnValue} substitutes
     * {@link Map#getOrDefault} cleanly without a wrapper instance.
     */
    private final Object2IntOpenHashMap<String> nodeToLevel = new Object2IntOpenHashMap<>();

    public OpResolver() {
        nodeToLevel.defaultReturnValue(DEFAULT_LEVEL);
        applyConfigLevels();
    }

    /**
     * Rebuild the node→level table from the current config snapshot. Called by
     * {@code /rg reload} so admins can change {@code defaultOpLevelAdmin}/{@code Mod}
     * without restarting the server.
     *
     * <p>Safe to call multiple times. Clears any custom levels added via {@link #setLevel}
     * — admins who want runtime-set levels must reapply them after reload (typically not
     * an issue since {@code setLevel} isn't exposed to commands).
     */
    public void applyConfigLevels() {
        nodeToLevel.clear();

        // Defer to the global config's defaultOpLevelAdmin/Mod when available — if WGN
        // isn't booted yet (e.g. detect() is called before getInstance()), fall back to
        // vanilla-style 3/2 levels.
        int admin = 3, mod = 2;
        try {
            var modInst = dev.thefather007.worldguardneo.WorldGuardNeo.get();
            if (modInst != null && modInst.config() != null && modInst.config().global() != null) {
                admin = modInst.config().global().defaultOpLevelAdmin;
                mod   = modInst.config().global().defaultOpLevelMod;
            }
        } catch (Throwable ignored) {}

        // Region-management nodes.
        // region.bypass is deliberately set to level 5 — ABOVE the maximum vanilla op level (4).
        // This means being an operator does NOT auto-grant bypass via the OP fallback. Bypass
        // is a protection-critical permission: on servers where builders/staff hold op for
        // unrelated reasons, auto-granting bypass would silently let them ignore every claim,
        // which is exactly the "protection doesn't work for my admin" trap. To get bypass now,
        // grant the node EXPLICITLY (e.g. LuckPerms `/lp user X permission set
        // worldguardneo.region.bypass true`, or a group). A plain op no longer bypasses.
        nodeToLevel.put("worldguardneo.region.bypass",        5);
        nodeToLevel.put("worldguardneo.region.admin",         admin);
        nodeToLevel.put("worldguardneo.region.claim",         0);
        nodeToLevel.put("worldguardneo.region.info",          0);
        nodeToLevel.put("worldguardneo.region.info.others",   mod);
        // OP 4 — strongest fallback. The global region holds world-wide defaults, accidental
        // disclosure of its flags to a moderator could reveal server-internal balance choices
        // (creative-mode safeguards, custom respawn behaviour, etc).
        nodeToLevel.put("worldguardneo.region.info.global",   4);
        nodeToLevel.put("worldguardneo.region.list",          0);
        nodeToLevel.put("worldguardneo.region.list.others",   mod);
        nodeToLevel.put("worldguardneo.region.lists.radius",  mod);
        nodeToLevel.put("worldguardneo.region.delete",        0);   // own regions
        nodeToLevel.put("worldguardneo.region.delete.others", admin);   // arbitrary regions
        nodeToLevel.put("worldguardneo.region.redefine",      mod);
        nodeToLevel.put("worldguardneo.region.select",        mod);   // /rg select — load into selection (OP 2)
        nodeToLevel.put("worldguardneo.region.transfer",      admin); // /rg transfer — sole-ownership handoff (OP 3)
        nodeToLevel.put("worldguardneo.region.addowner",      mod);
        nodeToLevel.put("worldguardneo.region.addmember",     mod);
        nodeToLevel.put("worldguardneo.region.removeowner",   mod);
        nodeToLevel.put("worldguardneo.region.removemember",  mod);
        nodeToLevel.put("worldguardneo.region.teleport",      0);

        // Flag-editing nodes.
        nodeToLevel.put("worldguardneo.region.flag.others",   mod);   // edit foreign regions
        nodeToLevel.put("worldguardneo.region.flag.bypass",   admin); // skip per-flag check
        nodeToLevel.put("worldguardneo.region.flag.group",    mod);   // use -g group syntax
        nodeToLevel.put("worldguardneo.region.flag.priority", mod);
        nodeToLevel.put("worldguardneo.region.flag.parent",   mod);
        nodeToLevel.put("worldguardneo.region.flags.list",    mod);  // /rg flags: op-2 or node

        // Misc.
        // selection.use gates the *wand item* (clicking blocks to pick corners). The selection
        // commands each have their own node so admins can hand them out individually — all OP 0
        // by default (everyone) to mirror open claiming.
        nodeToLevel.put("worldguardneo.selection.use",        0);
        nodeToLevel.put("worldguardneo.selection.mode",       0);   // /rg sel cuboid|poly|clear
        nodeToLevel.put("worldguardneo.selection.pos1",       0);   // /rg pos1
        nodeToLevel.put("worldguardneo.selection.pos2",       0);   // /rg pos2
        nodeToLevel.put("worldguardneo.selection.point",      0);   // /rg point
        // Explicit-coords pos1/pos2 — higher bar than the "here" variants since it can place a
        // corner anywhere (and run from console via /execute as). OP 4.
        nodeToLevel.put("worldguardneo.selection.pos.coords", 4);   // /rg pos1|pos2 <x y z>
        // The wand-give command. OP 0 (everyone) by default so any player can grab the selection
        // wand and claim land, mirroring selection.use. Admins can restrict via LuckPerms.
        nodeToLevel.put("worldguardneo.selection.wand",       0);
        // reload and backup are top-tier (OP 4) — reload swaps live config state and could
        // brick a busy server if mis-edited config.toml reaches production; backup writes
        // to disk and rotates retention. Both are dangerous enough to keep alongside bypass
        // and info.global. Service accounts that need /rg backup as part of automation
        // should be granted the explicit LP node rather than relying on OP level.
        nodeToLevel.put("worldguardneo.reload",               4);
        nodeToLevel.put("worldguardneo.backup",               4);
        nodeToLevel.put("worldguardneo.notify",               mod);
    }

    public void setLevel(String node, int level) { nodeToLevel.put(node, level); }

    /** Re-read config values and rebuild the node→level mapping. */
    @Override public void reload() { applyConfigLevels(); }

    @Override
    public boolean has(ServerPlayer player, String node) {
        // Per-flag nodes default to op-level 2 — admins can grant them individually via LP.
        // Without LP, all per-flag editing requires op 2.
        // getInt + defaultReturnValue — no Integer allocation on the lookup path.
        int required = nodeToLevel.getInt(node);
        if (required == 0) return true;
        return player.hasPermissions(required);
    }

    @Override public String name() { return "op-level"; }
}
