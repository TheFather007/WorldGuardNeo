package dev.thefather007.worldguardneo.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flag;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.config.WGConfig;
import dev.thefather007.worldguardneo.selection.SelectionStore;
import dev.thefather007.worldguardneo.selection.WandItem;
import dev.thefather007.worldguardneo.util.Vec3;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Registers the /region, /rg, //pos1, //pos2 command tree.
 * Subcommands gate on permission nodes; node names are documented in PERMISSIONS.md.
 */
public final class WGCommands {

    private WGCommands() {}

    /**
     * Should the {@code /rg flag} subcommand (and its tab-complete) be visible to this source?
     * True for op-2+ or anyone holding a flag-related node. The exact per-flag permission and
     * region ownership are still re-checked inside setFlag(); this is just the "can see the
     * command at all" gate, so we keep it broad (any flag node, or op-2).
     */
    private static boolean canUseFlags(CommandSourceStack s, WorldGuardNeo mod) {
        return s.hasPermission(2)
            || mod.perms().has(s, "worldguardneo.region.flag")
            || mod.perms().has(s, "worldguardneo.region.flag.others")
            || mod.perms().has(s, "worldguardneo.region.flag.group")
            || mod.perms().has(s, "worldguardneo.region.flag.bypass")
            || mod.perms().has(s, "worldguardneo.region.bypass");
    }

    /**
     * Should the privileged {@code -g <group>} syntax be visible? Per the spec this requires
     * {@code region.flag.group} OR {@code region.flag.bypass} OR {@code region.bypass} OR OP.
     * Used as the Brigadier {@code .requires()} on the {@code -g} literal so the hint doesn't
     * appear in tab-complete for players who can't use it.
     */
    private static boolean canUseFlagGroup(CommandSourceStack s, WorldGuardNeo mod) {
        return s.hasPermission(4)
            || mod.perms().has(s, "worldguardneo.region.flag.group")
            || mod.perms().has(s, "worldguardneo.region.flag.bypass")
            || mod.perms().has(s, "worldguardneo.region.bypass");
    }

    public static void register(CommandDispatcher<CommandSourceStack> d, WorldGuardNeo mod) {
        d.register(buildRoot("region", mod));
        d.register(buildRoot("rg",     mod));

    }

    /* ---- tab-completion suggestion providers (shared across the command tree) ---- */

    /** Suggest region ids in the source's current world (works for players and console). */
    private static SuggestionProvider<CommandSourceStack> regionIdSuggest(WorldGuardNeo mod) {
        return (c, b) -> SharedSuggestionProvider.suggest(
                mod.regions().get(c.getSource().getLevel()).all().stream()
                        .map(dev.thefather007.worldguardneo.region.ProtectedRegion::id), b);
    }

    /** Suggest every registered flag name. */
    private static SuggestionProvider<CommandSourceStack> flagNameSuggest() {
        return (c, b) -> SharedSuggestionProvider.suggest(Flags.all().stream().map(Flag::name), b);
    }

    /**
     * Suggest values for the flag already typed in the {@code flag} argument: allow/deny/none for
     * state flags, true/false/none for boolean flags. Other flag types get no suggestion (free text).
     */
    private static SuggestionProvider<CommandSourceStack> flagValueSuggest() {
        return (c, b) -> {
            try {
                Flag<?> f = Flags.get(StringArgumentType.getString(c, "flag"));
                if (f instanceof dev.thefather007.worldguardneo.flags.StateFlag)
                    return SharedSuggestionProvider.suggest(java.util.List.of("allow", "deny", "none"), b);
                if (f instanceof dev.thefather007.worldguardneo.flags.BooleanFlag)
                    return SharedSuggestionProvider.suggest(java.util.List.of("true", "false", "none"), b);
            } catch (IllegalArgumentException ignored) { /* flag arg not present yet */ }
            return b.buildFuture();
        };
    }

    /** Suggest online player names. */
    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGEST =
            (c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b);

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String alias, WorldGuardNeo mod) {
        final SuggestionProvider<CommandSourceStack> RID = regionIdSuggest(mod);
        final SuggestionProvider<CommandSourceStack> FLAGN = flagNameSuggest();
        final SuggestionProvider<CommandSourceStack> FLAGV = flagValueSuggest();
        final SuggestionProvider<CommandSourceStack> PLR = PLAYER_SUGGEST;
        return Commands.literal(alias)
                .then(Commands.literal("claim")
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.claim"))
                        // /rg claim — auto-generate id from player name + counter
                        .executes(c -> claimRegion(c.getSource(), null, mod))
                        // /rg claim <id> — explicit id
                        // No id suggestions here — claim needs a NEW id, not an existing one.
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(c -> claimRegion(c.getSource(), StringArgumentType.getString(c, "id"), mod))))
                .then(Commands.literal("redefine")
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.redefine"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .executes(c -> redefineRegion(c.getSource(), StringArgumentType.getString(c, "id"), mod))))
                .then(Commands.literal("wand")
                        // Hand out the built-in selection wand (configurable item). Obtainable once.
                        .requires(s -> mod.perms().has(s, "worldguardneo.selection.wand"))
                        .executes(c -> giveWand(c.getSource(), mod)))
                .then(Commands.literal("sel")
                        // Switch selection mode: cuboid (two corners) or poly (3+ points).
                        .requires(s -> mod.perms().has(s, "worldguardneo.selection.mode"))
                        .then(Commands.literal("cuboid")
                                .executes(c -> setSelMode(c.getSource(), mod, SelectionStore.Mode.CUBOID)))
                        .then(Commands.literal("poly")
                                .executes(c -> setSelMode(c.getSource(), mod, SelectionStore.Mode.POLYGON)))
                        .then(Commands.literal("clear")
                                .executes(c -> clearSelection(c.getSource(), mod))))
                .then(Commands.literal("pos1")
                        // Set cuboid corner 1 to the player's current block position.
                        .requires(s -> mod.perms().has(s, "worldguardneo.selection.pos1"))
                        .executes(c -> setPosHere(c.getSource(), mod, 1))
                        // /rg pos1 <x y z> — explicit coords (also usable from console via
                        // /execute as <player>). Higher bar: OP 4 + its own node.
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .requires(s -> mod.perms().has(s, "worldguardneo.selection.pos.coords"))
                                .executes(c -> setPosCoords(c, mod, 1))))
                .then(Commands.literal("pos2")
                        .requires(s -> mod.perms().has(s, "worldguardneo.selection.pos2"))
                        .executes(c -> setPosHere(c.getSource(), mod, 2))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .requires(s -> mod.perms().has(s, "worldguardneo.selection.pos.coords"))
                                .executes(c -> setPosCoords(c, mod, 2))))
                .then(Commands.literal("point")
                        // Append a polygon vertex at the player's current block position.
                        .requires(s -> mod.perms().has(s, "worldguardneo.selection.point"))
                        .executes(c -> addPointHere(c.getSource(), mod)))
                .then(Commands.literal("remove")
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.delete")
                                    || mod.perms().has(s, "worldguardneo.region.delete.others"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .executes(c -> removeRegion(c.getSource(), StringArgumentType.getString(c, "id"), mod))))
                .then(Commands.literal("undo")
                        // Restore the most recently removed region in this world (session trash).
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.undo"))
                        .executes(c -> undoRemove(c.getSource(), mod)))
                .then(Commands.literal("info")
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.info"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .executes(c -> infoRegion(c.getSource(), StringArgumentType.getString(c, "id"), mod)))
                        .executes(c -> infoRegionAtPlayer(c.getSource(), mod)))
                .then(Commands.literal("select")
                        // Load an existing region's geometry into your selection (for redefine /
                        // expand). OP 2 + its own node.
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.select"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .executes(c -> selectRegion(c.getSource(),
                                        StringArgumentType.getString(c, "id"), mod))))
                .then(Commands.literal("transfer")
                        // Transfer sole ownership of a region to another player. OP 3 + its own node.
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.transfer"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLR)
                                        .executes(c -> transferRegion(c, mod)))))
                .then(Commands.literal("list")
                        // Базовый /rg list (без аргумента) — свои регионы, доступно всем
                        // с базовым пермишионом region.list (default OP 0 = все игроки).
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.list"))
                        .executes(c -> listOwnRegions(c.getSource(), mod))
                        // /rg list <player> — регионы указанного игрока. Принимает имя
                        // или строковый UUID. Требует region.list.others (default OP 2).
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLR)
                                .requires(s -> mod.perms().has(s, "worldguardneo.region.list.others"))
                                .executes(c -> listPlayerRegions(c.getSource(),
                                        StringArgumentType.getString(c, "player"), mod))))
                .then(Commands.literal("lists")
                        // /rg lists [radius] — регионы в радиусе вокруг себя. Радиус опционален
                        // (default 50). Требует region.lists.radius (default OP 2).
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.lists.radius"))
                        .executes(c -> listInRadius(c.getSource(), 50, mod))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 1000))
                                .executes(c -> listInRadius(c.getSource(),
                                        IntegerArgumentType.getInteger(c, "radius"), mod))))
                .then(Commands.literal("teleport")
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.teleport"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .executes(c -> teleportToRegion(c.getSource(), StringArgumentType.getString(c, "id"), mod))))
                .then(Commands.literal("flag")
                        // Hide the whole `flag` subcommand unless the player can set at least one
                        // flag. The fine-grained per-flag node and ownership are still enforced
                        // inside setFlag(); this gate just stops the command (and its tab-complete)
                        // from showing to players who can't use any flag at all. We treat op-2 OR
                        // any flag-related node as "may see flags".
                        .requires(s -> canUseFlags(s, mod))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .then(Commands.argument("flag", StringArgumentType.word()).suggests(FLAGN)
                                        .then(Commands.literal("-g")
                                                // The `-g <group>` syntax is privileged: only show
                                                // it to players who actually hold the group node
                                                // (or a bypass/op). Without this, the "-g" branch
                                                // appeared in tab-complete for everyone even though
                                                // setFlag() would reject it.
                                                .requires(s -> canUseFlagGroup(s, mod))
                                                .then(Commands.argument("group", StringArgumentType.word())
                                                        .then(Commands.argument("value", StringArgumentType.greedyString()).suggests(FLAGV)
                                                                .executes(c -> setFlagGrouped(c, mod)))))
                                        .then(Commands.argument("value", StringArgumentType.greedyString()).suggests(FLAGV)
                                                .executes(c -> setFlag(c.getSource(),
                                                        StringArgumentType.getString(c, "id"),
                                                        StringArgumentType.getString(c, "flag"),
                                                        StringArgumentType.getString(c, "value"), null, mod)))
                                        .executes(c -> setFlag(c.getSource(),
                                                StringArgumentType.getString(c, "id"),
                                                StringArgumentType.getString(c, "flag"), "", null, mod)))))
                .then(Commands.literal("priority")
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.flag.priority"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .then(Commands.argument("value", IntegerArgumentType.integer())
                                        .executes(c -> setPriority(c.getSource(),
                                                StringArgumentType.getString(c, "id"),
                                                IntegerArgumentType.getInteger(c, "value"), mod)))))
                .then(Commands.literal("setparent")
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.flag.parent"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .then(Commands.argument("parent", StringArgumentType.word()).suggests(RID)
                                        .executes(c -> setParent(c.getSource(),
                                                StringArgumentType.getString(c, "id"),
                                                StringArgumentType.getString(c, "parent"), mod)))
                                .executes(c -> setParent(c.getSource(),
                                        StringArgumentType.getString(c, "id"), null, mod))))
                .then(Commands.literal("addowner")
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.addowner"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLR)
                                        .executes(c -> changeMembership(c, "id", "player", mod, true,  true)))))
                .then(Commands.literal("removeowner")
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.removeowner"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLR)
                                        .executes(c -> changeMembership(c, "id", "player", mod, true,  false)))))
                .then(Commands.literal("addmember")
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.addmember"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLR)
                                        .executes(c -> changeMembership(c, "id", "player", mod, false, true)))))
                .then(Commands.literal("removemember")
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.removemember"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(RID)
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLR)
                                        .executes(c -> changeMembership(c, "id", "player", mod, false, false)))))
                .then(Commands.literal("reload")
                        .requires(s -> mod.perms().has(s, "worldguardneo.reload"))
                        .executes(c -> reload(c.getSource(), mod)))
                .then(Commands.literal("save")
                        .requires(s -> mod.perms().has(s, "worldguardneo.reload"))
                        .executes(c -> { mod.regions().saveAll(); ok(c.getSource(), mod, "msg.saved"); return 1; }))
                .then(Commands.literal("debug")
                        .requires(s -> mod.perms().has(s, "worldguardneo.reload"))
                        .executes(c -> debugInfo(c.getSource(), mod)))
                .then(Commands.literal("backup")
                        // Dedicated permission node, separate from /rg reload — admins may
                        // want to grant backup access to ops or trusted automation without
                        // also granting reload (which can swap config/lang state).
                        .requires(s -> mod.perms().has(s, "worldguardneo.backup"))
                        // /rg backup — trigger immediate backup
                        .executes(c -> doBackupNow(c.getSource(), mod, null))
                        // /rg backup list — show recent backups
                        .then(Commands.literal("list")
                                .executes(c -> listBackups(c.getSource(), mod)))
                        // /rg backup <label> — manual backup with custom suffix
                        .then(Commands.argument("label", StringArgumentType.word())
                                .executes(c -> doBackupNow(c.getSource(), mod,
                                        StringArgumentType.getString(c, "label")))))
                .then(Commands.literal("cleanup")
                        // Admin maintenance: trigger the claim-expiry scan immediately.
                        .requires(s -> mod.perms().has(s, "worldguardneo.reload"))
                        .executes(c -> cleanupClaims(c.getSource(), mod)))
                .then(Commands.literal("migrate")
                        // Convert all region data to another storage backend (takes effect after
                        // a restart). OP 4 + its own node.
                        .requires(s -> mod.perms().has(s, "worldguardneo.migrate"))
                        .then(Commands.literal("json").executes(c -> migrateStorage(c.getSource(), mod, "json")))
                        .then(Commands.literal("sqlite").executes(c -> migrateStorage(c.getSource(), mod, "sqlite")))
                        .then(Commands.literal("h2").executes(c -> migrateStorage(c.getSource(), mod, "h2")))
                        .then(Commands.literal("mysql").executes(c -> migrateStorage(c.getSource(), mod, "mysql"))))
                .then(Commands.literal("flags")
                        .requires(s -> mod.perms().has(s, "worldguardneo.region.flags.list"))
                        .executes(c -> listFlags(c.getSource(), mod)));
    }

    /** /rg cleanup — run the claim-expiry scan now (admin). */
    private static int cleanupClaims(CommandSourceStack src, WorldGuardNeo mod) {
        if (!mod.config().global().claimExpiryEnabled) {
            err(src, mod, "msg.cleanup.disabled");
            return 0;
        }
        int n = mod.expiry().runCleanup(mod);
        ok(src, mod, "msg.cleanup.done", "count", n);
        return 1;
    }

    /**
     * /rg migrate &lt;json|sqlite|h2|mysql&gt; — convert all region data to another storage backend.
     * Writes every world's regions into a freshly-constructed target backend, then updates
     * {@code storage-format} in config.toml. The live backend is not hot-swapped (open handles),
     * so the switch takes effect on the next server start. DB backends silently fall back to JSON
     * if their JDBC driver is absent — the message notes this.
     */
    private static int migrateStorage(CommandSourceStack src, WorldGuardNeo mod, String fmt) {
        var g = mod.config().global();
        String current = g.storageFormat == null ? "json" : g.storageFormat.trim().toLowerCase(java.util.Locale.ROOT);
        if (fmt.equals(current)) { err(src, mod, "msg.migrate.same", "format", fmt); return 0; }
        // Flush in-memory edits to the CURRENT backend first so nothing is lost mid-migration.
        mod.regions().saveAll();
        java.nio.file.Path dataDir =
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(WorldGuardNeo.MOD_ID);
        int worlds = 0, regions = 0;
        dev.thefather007.worldguardneo.storage.RegionStorage target;
        try {
            target = WorldGuardNeo.createStorage(fmt, g, dataDir);
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.error("[WorldGuardNeo] migrate: could not open '{}' backend", fmt, t);
            err(src, mod, "msg.migrate.failed", "format", fmt);
            return 0;
        }
        try {
            for (var entry : mod.regions().allManagers().entrySet()) {
                target.save(entry.getKey(), entry.getValue());
                worlds++;
                regions += entry.getValue().size();
            }
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error("[WorldGuardNeo] migrate to '{}' failed", fmt, ex);
            try { target.close(); } catch (Exception ignored) {}
            err(src, mod, "msg.migrate.failed", "format", fmt);
            return 0;
        }
        try { target.close(); } catch (Exception ignored) {}
        // Persist the new backend choice; it activates on next start.
        g.storageFormat = fmt;
        mod.config().save();
        mod.audit().record(src, "migrate", "-", current + "->" + fmt);
        ok(src, mod, "msg.migrate.done", "format", fmt, "worlds", worlds, "regions", regions);
        return 1;
    }

    /* -------------------- selection -------------------- */

    /** /rg wand — hand out the built-in selection wand (once per player). */
    private static int giveWand(CommandSourceStack src, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        if (WandItem.hasWand(p)) { err(src, mod, "msg.wand.already"); return 0; }
        var wand = WandItem.create(mod);
        if (wand.isEmpty()) {
            err(src, mod, "msg.wand.bad-item", "item", mod.config().global().wandItem);
            return 0;
        }
        // Give it; if the inventory is full, drop it at the player's feet so it's never lost.
        if (!p.getInventory().add(wand)) p.drop(wand, false);
        ok(src, mod, "msg.wand.given", "item", mod.config().global().wandItem);
        return 1;
    }

    /** /rg sel cuboid|poly — switch selection mode. */
    private static int setSelMode(CommandSourceStack src, WorldGuardNeo mod, SelectionStore.Mode mode)
            throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        mod.selections().setMode(p, mode);
        ok(src, mod, mode == SelectionStore.Mode.CUBOID ? "msg.selection.mode-cuboid" : "msg.selection.mode-poly");
        return 1;
    }

    /** /rg sel clear — drop the player's pending selection and clear the outline. */
    private static int clearSelection(CommandSourceStack src, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        mod.selections().reset(p);
        ok(src, mod, "msg.selection.poly-cleared");
        return 1;
    }

    /** /rg pos1 | /rg pos2 — set a cuboid corner to the player's current block position. */
    private static int setPosHere(CommandSourceStack src, WorldGuardNeo mod, int which)
            throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        // pos1/pos2 are cuboid operations — switch into cuboid mode so the corner is actually used
        // (mirrors /rg point switching to polygon). No-op if already in cuboid mode.
        mod.selections().setMode(p, SelectionStore.Mode.CUBOID);
        Vec3 v = new Vec3(p.getBlockX(), p.getBlockY(), p.getBlockZ());
        if (which == 1) {
            mod.selections().setPos1(p, v);
            ok(src, mod, "msg.selection.pos1", "pos", v.x() + "," + v.y() + "," + v.z());
        } else {
            mod.selections().setPos2(p, v);
            ok(src, mod, "msg.selection.pos2", "pos", v.x() + "," + v.y() + "," + v.z());
        }
        return 1;
    }

    /** /rg point — append a polygon vertex at the player's current block position. */
    private static int addPointHere(CommandSourceStack src, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        // Auto-switch to polygon mode so /rg point "just works" without a prior /rg sel poly.
        mod.selections().setMode(p, SelectionStore.Mode.POLYGON);
        Vec3 v = new Vec3(p.getBlockX(), p.getBlockY(), p.getBlockZ());
        int n = mod.selections().addPolyPoint(p, v);
        ok(src, mod, "msg.selection.point", "n", n, "pos", v.x() + "," + v.y() + "," + v.z());
        return 1;
    }

    /**
     * /rg pos1|pos2 &lt;x y z&gt; — set a cuboid corner to explicit coordinates. Supports relative
     * (~) coords and runs from console via {@code /execute as <player>}; a pure-console invocation
     * with no player context is rejected (a selection is always tied to a player).
     */
    private static int setPosCoords(CommandContext<CommandSourceStack> c, WorldGuardNeo mod, int which)
            throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) { err(src, mod, "msg.selection.needs-player"); return 0; }
        BlockPos bp = BlockPosArgument.getBlockPos(c, "pos");
        mod.selections().setMode(p, SelectionStore.Mode.CUBOID);
        Vec3 v = new Vec3(bp.getX(), bp.getY(), bp.getZ());
        if (which == 1) {
            mod.selections().setPos1(p, v);
            ok(src, mod, "msg.selection.pos1", "pos", v.x() + "," + v.y() + "," + v.z());
        } else {
            mod.selections().setPos2(p, v);
            ok(src, mod, "msg.selection.pos2", "pos", v.x() + "," + v.y() + "," + v.z());
        }
        return 1;
    }

    /** /rg select &lt;id&gt; — load an existing region's geometry into the caller's selection. */
    private static int selectRegion(CommandSourceStack src, String id, WorldGuardNeo mod)
            throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        var ropt = mod.regions().get(p.serverLevel()).get(id);
        if (ropt.isEmpty()) { err(src, mod, "msg.region.unknown", "id", id); return 0; }
        ProtectedRegion r = ropt.get();
        if (r instanceof GlobalRegion) { err(src, mod, "msg.region.global-select", "id", id); return 0; }
        mod.selections().selectRegion(p, r);
        ok(src, mod, "msg.region.selected", "id", id);
        return 1;
    }

    /**
     * /rg transfer &lt;id&gt; &lt;player&gt; — hand sole ownership to another player. The caller must
     * be an owner of the region (or hold {@code region.bypass}); members are kept untouched.
     */
    private static int transferRegion(CommandContext<CommandSourceStack> c, WorldGuardNeo mod)
            throws CommandSyntaxException {
        ServerPlayer self = c.getSource().getPlayerOrException();
        String id  = StringArgumentType.getString(c, "id");
        String who = StringArgumentType.getString(c, "player");
        var mgr = mod.regions().get(self.serverLevel());
        var ropt = mgr.get(id);
        if (ropt.isEmpty()) { err(c.getSource(), mod, "msg.region.unknown", "id", id); return 0; }
        ProtectedRegion r = ropt.get();
        if (r instanceof GlobalRegion) { err(c.getSource(), mod, "msg.region.global-select", "id", id); return 0; }
        boolean bypass  = mod.perms().has(self, "worldguardneo.region.bypass");
        if (!bypass && !r.isOwner(self.getUUID())) {
            err(c.getSource(), mod, "msg.region.notyours", "id", id);
            return 0;
        }
        var uuidOpt = dev.thefather007.worldguardneo.util.UuidResolver.resolve(self.getServer(), who);
        if (uuidOpt.isEmpty()) { err(c.getSource(), mod, "msg.player.unknown", "player", who); return 0; }
        UUID target = uuidOpt.get();
        // Sole-ownership transfer: clear ALL existing ownership — both UUID owners and owner GROUPS
        // (LuckPerms group-owners) — then set the target as the only owner. Without clearing the
        // groups, members of a former owner group would keep owner rights after a "sole" transfer.
        r.owners().clear();
        if (!r.ownerGroupsView().isEmpty()) r.ownerGroups().clear();
        r.owners().add(target);
        mod.regions().saveRegion(self.serverLevel(), r.id());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new dev.thefather007.worldguardneo.api.events.RegionModifyEvent(
                        self.serverLevel(), r,
                        dev.thefather007.worldguardneo.api.events.RegionModifyEvent.ModifyType.UPDATED, self));
        mod.audit().record(c.getSource(), "transfer", id, "to=" + target);
        ok(c.getSource(), mod, "msg.region.transferred", "id", id,
                "player", dev.thefather007.worldguardneo.util.UuidResolver.nameOf(self.getServer(), target));
        return 1;
    }

    /* -------------------- handlers -------------------- */

    /**
     * Single region-creation entry point.
     *
     * Players with {@code worldguardneo.region.bypass} skip per-player count, area and
     * overlap limits — this is the admin/staff path that replaces the former /rg define.
     * Everyone else is bound by the values in the global config:
     *   - {@code maxRegionsPerPlayer} - hard cap on owned region count (or per-group
     *     override via {@code groupRegionLimits}; see {@link WorldGuardNeo#effectiveRegionLimit})
     *   - {@code maxClaimableArea}    - hard cap on volume for a single claim
     *   - {@code maxRegionVolume}     - absolute upper bound (also applies to bypassers)
     */
    private static int claimRegion(CommandSourceStack src, String id, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        // Claiming reads the player's built-in selection (see SelectionStore) — made with the
        // /rg wand item or /rg pos1 //rg pos2 //rg point. No WorldEdit required.
        RegionManager mgr = mod.regions().get(p.serverLevel());
        boolean bypass = mod.perms().has(p, "worldguardneo.region.bypass");

        if (!bypass) {
            // Limit is GLOBAL across all dimensions — count the player's regions everywhere,
            // not just in the current world, or the cap could be bypassed per-dimension.
            int owned = mod.regions().countOwnedGlobal(p.getUUID());
            int limit = mod.effectiveRegionLimit(p);
            if (owned >= limit) {
                err(src, mod, "msg.claim.limit", "limit", limit);
                return 0;
            }
        }

        // Auto-generate an id if none was provided. Format: "<playername-sanitized>-<n>" where
        // <n> is the lowest positive integer that produces a free id. The sanitize step keeps
        // only [a-z0-9_-] so non-Latin or special-character names still get a usable id.
        // If the player's name sanitizes to empty (e.g. all-emoji nick), fall back to "claim".
        final boolean wasAutoGen = (id == null);
        if (id == null) {
            String base = sanitizeForId(p.getName().getString());
            if (base.isEmpty()) base = "claim";
            for (int n = 1; n < 10_000; n++) {
                String candidate = base + "-" + n;
                if (mgr.get(candidate).isEmpty()) { id = candidate; break; }
            }
            if (id == null) {
                // Astronomically unlikely — player has 10k+ regions starting with that prefix.
                err(src, mod, "msg.claim.autogen-failed");
                return 0;
            }
        } else if (!isValidRegionId(id)) {
            // User-supplied id: restrict to a safe charset. Brigadier's word() also allows '.', '+'
            // etc., which would let an id collide with the storage quarantine marker ('.corrupt-')
            // — such a region loads back invisible and can't be pruned — or with the reserved
            // global-region id. Auto-generated ids are already sanitized, so this only gates the
            // explicit-id path.
            err(src, mod, "msg.region.bad-id", "id", id);
            return 0;
        }

        if (mgr.get(id).isPresent()) { err(src, mod, "msg.region.exists", "id", id); return 0; }

        final String finalId = id; // for use in the lambda below
        return mod.selections().buildRegion(p, finalId).map(region -> {
            long vol = region.volume();
            var gc = mod.config().global();
            // Absolute hard cap — applies even to bypass holders to keep storage sane.
            if (vol > gc.maxRegionVolume) {
                err(src, mod, "msg.region.toolarge", "volume", vol, "limit", gc.maxRegionVolume);
                return 0;
            }
            if (!bypass) {
                int minVol = Math.max(1, gc.minRegionVolume);
                if (vol < minVol) {
                    err(src, mod, "msg.region.toosmall", "volume", vol, "limit", minVol);
                    return 0;
                }
                if (vol > gc.maxClaimableArea) {
                    err(src, mod, "msg.region.toolarge", "volume", vol, "limit", gc.maxClaimableArea);
                    return 0;
                }
                List<ProtectedRegion> overlap = mgr.overlapping(region.minimumBound(), region.maximumBound());
                // Allow overlap only with regions the player already owns.
                UUID uid = p.getUUID();
                overlap.removeIf(r -> r.isOwner(uid));
                if (!overlap.isEmpty()) {
                    err(src, mod, "msg.claim.overlap", "other", overlap.get(0).id());
                    return 0;
                }
            }
            // Apply automatic vertical expansion (config, per-world). Horizontal size limits were
            // already checked above on the ORIGINAL selection, so expansion never eats a player's
            // area allowance. We re-check overlap on the FINAL geometry below.
            var ws = mod.config().worldOrGlobal(p.serverLevel());
            ProtectedRegion finalRegion = applyVerticalExpansion(region, p.serverLevel(), ws);

            if (!bypass) {
                // Re-check overlap on the (possibly taller) final region, since vertical expansion
                // can introduce new Y-overlaps with existing regions.
                List<ProtectedRegion> overlap2 =
                        mgr.overlapping(finalRegion.minimumBound(), finalRegion.maximumBound());
                UUID uid2 = p.getUUID();
                overlap2.removeIf(r -> r.isOwner(uid2));
                if (!overlap2.isEmpty()) {
                    err(src, mod, "msg.claim.overlap", "other", overlap2.get(0).id());
                    return 0;
                }
            }

            finalRegion.owners().add(p.getUUID());
            // Apply automatic flags (config, per-world) to the freshly claimed region.
            applyAutoFlags(finalRegion, ws, mod);
            mgr.add(finalRegion);
            mod.regions().saveRegion(p.serverLevel(), finalId); // incremental: just this new region
            // Notify map integrations of the new region. No-op if no integration is active.
            var bm = dev.thefather007.worldguardneo.integrations.BluemapIntegration.get();
            if (bm != null) bm.updateRegion(p.serverLevel(), finalRegion);
            var sq = dev.thefather007.worldguardneo.integrations.SquaremapIntegration.get();
            if (sq != null) sq.updateRegion(p.serverLevel(), finalRegion);
            // Public API event — other mods can subscribe to track region changes.
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new dev.thefather007.worldguardneo.api.events.RegionModifyEvent(
                            p.serverLevel(), finalRegion,
                            dev.thefather007.worldguardneo.api.events.RegionModifyEvent.ModifyType.CREATED, p));
            mod.audit().record(src, "claim", finalId, "vol=" + finalRegion.volume());
            ok(src, mod, "msg.region.defined", "id", finalId, "volume", finalRegion.volume());
            // If the id was auto-generated, also tell the player what id was chosen so they
            // know how to address the region later (/rg info, /rg flag, etc.).
            if (wasAutoGen) {
                p.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                mod.i18n().format("msg.claim.autogen", "id", finalId)),
                        false);
            }
            return 1;
        }).orElseGet(() -> { err(src, mod, "msg.selection.none"); return 0; });
    }

    /**
     * Auto-expand a freshly claimed region vertically per the world's config, so members are
     * protected from someone tunnelling in from below or bridging in from above. Horizontal
     * limits are enforced before this is called, so expansion is "free" area-wise.
     *
     * @return the region to actually store — a new, taller region, or the original if expansion
     *         is off / not applicable (e.g. a global region).
     */
    private static ProtectedRegion applyVerticalExpansion(ProtectedRegion region, ServerLevel lvl,
                                                          WGConfig.WorldSection ws) {
        String mode = ws.verticalExpansion == null ? "none"
                : ws.verticalExpansion.trim().toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("none")) return region;

        int worldMin = lvl.getMinBuildHeight();
        int worldMax = lvl.getMaxBuildHeight() - 1; // last placeable block

        int curMinY, curMaxY;
        if (region instanceof CuboidRegion c) {
            curMinY = c.minimumBound().y();
            curMaxY = c.maximumBound().y();
        } else if (region instanceof PolygonalRegion poly) {
            curMinY = poly.minY();
            curMaxY = poly.maxY();
        } else {
            return region; // global / unknown — nothing to expand
        }

        int newMinY, newMaxY;
        switch (mode) {
            case "full":
                newMinY = worldMin;
                newMaxY = worldMax;
                break;
            case "fixed":
                newMinY = Math.max(worldMin, curMinY - Math.max(0, ws.verticalExpandDown));
                newMaxY = Math.min(worldMax, curMaxY + Math.max(0, ws.verticalExpandUp));
                break;
            default:
                WorldGuardNeo.LOGGER.warn(
                        "[WorldGuardNeo] Unknown vertical-expansion mode '{}' — leaving region as selected.",
                        ws.verticalExpansion);
                return region;
        }
        // Safety: never invert the bounds.
        if (newMinY > newMaxY) return region;

        if (region instanceof CuboidRegion c) {
            Vec3 mn = c.minimumBound(), mx = c.maximumBound();
            return new CuboidRegion(region.id(),
                    new Vec3(mn.x(), newMinY, mn.z()), new Vec3(mx.x(), newMaxY, mx.z()));
        } else {
            PolygonalRegion poly = (PolygonalRegion) region;
            return new PolygonalRegion(region.id(), poly.points(), newMinY, newMaxY);
        }
    }

    /**
     * Apply the world's configured auto-flags (e.g. "pvp=deny") to a freshly claimed region.
     * Unknown flag names or unparseable values are skipped with a console warning so one bad
     * config entry can't block a claim.
     */
    private static void applyAutoFlags(ProtectedRegion region, WGConfig.WorldSection ws, WorldGuardNeo mod) {
        if (ws.autoFlags == null || ws.autoFlags.isEmpty()) return;
        for (String entry : ws.autoFlags) {
            if (entry == null) continue;
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] auto-flags: malformed entry '{}' (expected flag=value).", entry);
                continue;
            }
            String flagName = entry.substring(0, eq).trim();
            String value    = entry.substring(eq + 1).trim();
            Flag<?> flag = Flags.get(flagName);
            if (flag == null) {
                WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] auto-flags: unknown flag '{}' — skipped.", flagName);
                continue;
            }
            try {
                flag.parseAndApply(region, value, null);
            } catch (Exception e) {
                WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] auto-flags: bad value '{}' for '{}' — {}",
                        value, flagName, e.getMessage());
            }
        }
    }

    private static int redefineRegion(CommandSourceStack src, String id, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        // Redefine reads the player's built-in selection (see SelectionStore). No WorldEdit required.
        RegionManager mgr = mod.regions().get(p.serverLevel());
        boolean bypass = mod.perms().has(p, "worldguardneo.region.bypass");
        return mgr.get(id).map(existing -> mod.selections().buildRegion(p, id).map(newRegion -> {
            // Global region cannot be redefined — it's a singleton without geometry. Allowing
            // this would create a fake cuboid with id "__global__" in the regions map and
            // break global-region semantics. Refuse explicitly.
            if (existing instanceof dev.thefather007.worldguardneo.region.GlobalRegion) {
                err(src, mod, "msg.region.global-select", "id", id);
                return 0;
            }
            // Permission check: non-bypass redefiners must be an owner of the original region.
            if (!bypass && !existing.isOwner(p.getUUID())) {
                err(src, mod, "msg.region.notyours", "id", id);
                return 0;
            }
            // Apply the SAME volume/overlap limits as /rg claim — otherwise redefine becomes
            // an escape hatch around the claim-time caps. Players could claim a tiny region
            // then redefine it to span the world. The absolute maxRegionVolume check applies
            // even to bypass holders (it's a storage-sanity guard, not a privilege gate).
            long vol = newRegion.volume();
            var gc = mod.config().global();
            if (vol > gc.maxRegionVolume) {
                err(src, mod, "msg.region.toolarge", "volume", vol, "limit", gc.maxRegionVolume);
                return 0;
            }
            if (!bypass) {
                int minVol = Math.max(1, gc.minRegionVolume);
                if (vol < minVol) {
                    err(src, mod, "msg.region.toosmall", "volume", vol, "limit", minVol);
                    return 0;
                }
                if (vol > gc.maxClaimableArea) {
                    err(src, mod, "msg.region.toolarge", "volume", vol, "limit", gc.maxClaimableArea);
                    return 0;
                }
                // Overlap check: same rule as claim — only allow overlap with regions the
                // player already owns. Exclude the region being redefined itself (it's about
                // to be removed) — without this exclusion every redefine self-overlaps.
                List<ProtectedRegion> overlap = mgr.overlapping(newRegion.minimumBound(), newRegion.maximumBound());
                UUID uid = p.getUUID();
                overlap.removeIf(r -> r == existing || r.isOwner(uid));
                if (!overlap.isEmpty()) {
                    err(src, mod, "msg.claim.overlap", "other", overlap.get(0).id());
                    return 0;
                }
            }
            // *View() reads don't lazy-allocate; addAll of an empty View is a cheap no-op.
            // The mutating getters (owners/members/...) on newRegion still lazy-allocate as
            // needed if existing actually has data to copy.
            if (!existing.ownersView().isEmpty())       newRegion.owners().addAll(existing.ownersView());
            if (!existing.membersView().isEmpty())      newRegion.members().addAll(existing.membersView());
            if (!existing.ownerGroupsView().isEmpty())  newRegion.ownerGroups().addAll(existing.ownerGroupsView());
            if (!existing.memberGroupsView().isEmpty()) newRegion.memberGroups().addAll(existing.memberGroupsView());
            // Copy all flag values + group filters in one typed call (no raw Flag here).
            newRegion.copyFlagsFrom(existing);
            newRegion.setPriority(existing.priority());
            if (existing.parent() != null) newRegion.setParent(existing.parent());
            // Collect children before mgr.remove unlinks them, so we can rewire to newRegion.
            java.util.List<ProtectedRegion> orphans = new java.util.ArrayList<>();
            for (ProtectedRegion r : mgr.all()) {
                if (r.parent() == existing) orphans.add(r);
            }
            mgr.remove(id);
            mgr.add(newRegion);
            // Re-link orphaned children to the new region instance.
            for (ProtectedRegion r : orphans) {
                try { r.setParent(newRegion); }
                catch (IllegalStateException ignored) { /* cycle: drop link */ }
            }
            mod.regions().save(p.serverLevel());
            var bm = dev.thefather007.worldguardneo.integrations.BluemapIntegration.get();
            if (bm != null) bm.updateRegion(p.serverLevel(), newRegion);
            var sq = dev.thefather007.worldguardneo.integrations.SquaremapIntegration.get();
            if (sq != null) sq.updateRegion(p.serverLevel(), newRegion);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new dev.thefather007.worldguardneo.api.events.RegionModifyEvent(
                            p.serverLevel(), newRegion,
                            dev.thefather007.worldguardneo.api.events.RegionModifyEvent.ModifyType.UPDATED, p));
            mod.audit().record(src, "redefine", id, null);
            ok(src, mod, "msg.region.redefined", "id", id);
            return 1;
        }).orElseGet(() -> { err(src, mod, "msg.selection.none"); return 0; }))
                .orElseGet(() -> { err(src, mod, "msg.region.unknown", "id", id); return 0; });
    }

    /**
     * /rg remove &lt;id&gt; — semantics:
     *   - {@code region.delete}        → may remove regions where the player is an owner
     *   - {@code region.delete.others} → may remove any region (with or without ownership)
     *   - {@code region.bypass}        → implies {@code delete.others}
     */
    private static int removeRegion(CommandSourceStack src, String id, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        RegionManager mgr = mod.regions().get(p.serverLevel());
        var r = mgr.get(id);
        if (r.isEmpty()) { err(src, mod, "msg.region.unknown", "id", id); return 0; }
        // Global region cannot be deleted — it's a singleton holding world-wide defaults.
        // Without this guard the command silently no-ops (mgr.remove returns false for it)
        // but reports success, which is misleading.
        if (r.get() instanceof dev.thefather007.worldguardneo.region.GlobalRegion) {
            err(src, mod, "msg.region.global-remove");
            return 0;
        }
        boolean canOthers = mod.perms().has(p, "worldguardneo.region.delete.others")
                         || mod.perms().has(p, "worldguardneo.region.bypass");
        boolean canOwn    = mod.perms().has(p, "worldguardneo.region.delete");
        boolean isOwner   = r.get().isOwner(p.getUUID());
        if (!canOthers && !(canOwn && isOwner)) {
            err(src, mod, "msg.region.notyours", "id", id); return 0;
        }
        // Fire DELETED event BEFORE actual removal so listeners see the region state.
        // Reuse the lookup from above — r.isPresent() is guaranteed by the empty-check.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new dev.thefather007.worldguardneo.api.events.RegionModifyEvent(
                        p.serverLevel(), r.get(),
                        dev.thefather007.worldguardneo.api.events.RegionModifyEvent.ModifyType.DELETED, p));
        // Soft-delete: keep the region object in the per-world trash so /rg undo can restore it.
        mod.trash().push(p.serverLevel().dimension(), r.get());
        mgr.remove(id);
        mod.regions().save(p.serverLevel());
        var bm = dev.thefather007.worldguardneo.integrations.BluemapIntegration.get();
        if (bm != null) bm.removeRegion(p.serverLevel(), id);
        var sq = dev.thefather007.worldguardneo.integrations.SquaremapIntegration.get();
        if (sq != null) sq.removeRegion(p.serverLevel(), id);
        mod.audit().record(src, "remove", id, null);
        ok(src, mod, "msg.region.removed", "id", id);
        return 1;
    }

    /**
     * /rg undo — restore the most recently removed region in the caller's current world from the
     * session trash. No-op (with a message) if the trash is empty or the id is now taken again.
     */
    private static int undoRemove(CommandSourceStack src, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        RegionManager mgr = mod.regions().get(p.serverLevel());
        ProtectedRegion restored = mod.trash().pop(p.serverLevel().dimension());
        if (restored == null) { err(src, mod, "msg.undo.empty"); return 0; }
        if (mgr.get(restored.id()).isPresent()) {
            err(src, mod, "msg.region.exists", "id", restored.id());
            return 0;
        }
        mgr.add(restored);
        mod.regions().saveRegion(p.serverLevel(), restored.id());
        var bm = dev.thefather007.worldguardneo.integrations.BluemapIntegration.get();
        if (bm != null) bm.updateRegion(p.serverLevel(), restored);
        var sq = dev.thefather007.worldguardneo.integrations.SquaremapIntegration.get();
        if (sq != null) sq.updateRegion(p.serverLevel(), restored);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new dev.thefather007.worldguardneo.api.events.RegionModifyEvent(
                        p.serverLevel(), restored,
                        dev.thefather007.worldguardneo.api.events.RegionModifyEvent.ModifyType.CREATED, p));
        mod.audit().record(src, "undo", restored.id(), null);
        ok(src, mod, "msg.undo.restored", "id", restored.id());
        return 1;
    }

    /**
     * /rg info &lt;id&gt;
     *
     * <p>Visibility tiers:
     *   <ul>
     *     <li>Owner / member → always allowed
     *     <li>Other regions → requires {@code worldguardneo.region.info.others} (OP 2)
     *     <li>Global region → requires {@code worldguardneo.region.info.global} (OP 4)
     *   </ul>
     */
    private static int infoRegion(CommandSourceStack src, String id, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        RegionManager mgr = mod.regions().get(p.serverLevel());
        var ropt = mgr.get(id);
        if (ropt.isEmpty()) { err(src, mod, "msg.region.unknown", "id", id); return 0; }
        ProtectedRegion r = ropt.get();
        InfoVisibility v = checkInfoVisibility(p, r, mod);
        if (v == InfoVisibility.DENIED) {
            err(src, mod, "msg.region.info.denied", "id", id);
            return 0;
        }
        if (v == InfoVisibility.DENIED_GLOBAL) {
            err(src, mod, "msg.region.info.global-denied");
            return 0;
        }
        printRegion(src, mod, r);
        return 1;
    }

    /**
     * /rg info (no-arg) — describe whatever region the player is standing in.
     *
     * <p>Semantics: returns ONLY a region where the caller is an owner or member. If the topmost
     * region under the player belongs to someone else (or is the global region), behaves as if
     * no region were here. This matches "your own status" intuition — players invoke /rg info
     * to remember their own region details, not to probe others. The /rg info &lt;id&gt; form
     * remains available for cross-region inspection, with its own permission gate.
     */
    private static int infoRegionAtPlayer(CommandSourceStack src, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        RegionManager mgr = mod.regions().get(p.serverLevel());
        List<ProtectedRegion> here = mgr.getApplicable(p.getX(), p.getY(), p.getZ());
        UUID uid = p.getUUID();
        // Walk applicable list (already priority-sorted desc) and pick the first one where
        // the caller is owner or member. Skips global region implicitly — global is never in
        // the applicable list (it's the fallback in resolution, not stored in the spatial index).
        ProtectedRegion own = null;
        for (int i = 0, n = here.size(); i < n; i++) {
            ProtectedRegion r = here.get(i);
            if (r.isMember(uid)) { own = r; break; }
        }
        if (own == null) {
            err(src, mod, "msg.region.none-here-own");
            return 0;
        }
        printRegion(src, mod, own);
        return 1;
    }

    /** Tri-state result of the info visibility check. */
    private enum InfoVisibility { ALLOWED, DENIED, DENIED_GLOBAL }

    /**
     * Visibility rules for {@code /rg info &lt;id&gt;}:
     * <ul>
     *   <li>Global region needs {@code region.info.global} (OP 4) — special case so OP-2 mods
     *       can't snoop on the server's hidden defaults.
     *   <li>Owners and members of normal regions can always see them.
     *   <li>Otherwise needs {@code region.info.others} (OP 2) or {@code region.bypass} (OP 4, top-tier).
     * </ul>
     */
    private static InfoVisibility checkInfoVisibility(ServerPlayer p, ProtectedRegion r, WorldGuardNeo mod) {
        if (r instanceof GlobalRegion) {
            // Global region: requires either the dedicated info.global node OR bypass.
            // Both default to OP 4 — bypass is the universal "I'm superadmin" key, so
            // gating one top-tier capability behind another top-tier node would create
            // a paradox (bypass can override any region but not view server defaults).
            // Admins can still grant info.global standalone via LP if they want to
            // separate it (e.g. a config-auditor account without bypass).
            if (mod.perms().has(p, "worldguardneo.region.bypass")) return InfoVisibility.ALLOWED;
            return mod.perms().has(p, "worldguardneo.region.info.global")
                    ? InfoVisibility.ALLOWED : InfoVisibility.DENIED_GLOBAL;
        }
        if (r.isMember(p.getUUID())) return InfoVisibility.ALLOWED;
        if (mod.perms().has(p, "worldguardneo.region.bypass")) return InfoVisibility.ALLOWED;
        if (mod.perms().has(p, "worldguardneo.region.info.others")) return InfoVisibility.ALLOWED;
        return InfoVisibility.DENIED;
    }

    /**
     * Pretty-print a region's metadata, owners, members and flags. For non-global regions the
     * AABB-center coordinates are included so the caller knows WHERE the region lives.
     */
    private static void printRegion(CommandSourceStack src, WorldGuardNeo mod, ProtectedRegion r) {
        var server = src.getServer();
        var i18n = mod.i18n();
        src.sendSuccess(() -> Component.literal(i18n.format("msg.info.header", "id", r.id())), false);
        src.sendSuccess(() -> Component.literal(i18n.format("msg.info.type",     "type", r.type())), false);
        src.sendSuccess(() -> Component.literal(i18n.format("msg.info.priority", "value", r.priority())), false);
        // Geometry block — size (dimensions + block count) and bounds. Skipped for the global
        // region, whose Integer.MIN/MAX bounds would print nonsense.
        if (!(r instanceof GlobalRegion)) {
            Vec3 mn = r.minimumBound(), mx = r.maximumBound();
            int dx = mx.x() - mn.x() + 1, dy = mx.y() - mn.y() + 1, dz = mx.z() - mn.z() + 1;
            src.sendSuccess(() -> Component.literal(i18n.format("msg.info.size",
                    "w", dx, "h", dy, "l", dz, "blocks", r.volume())), false);
            src.sendSuccess(() -> Component.literal(i18n.format("msg.info.bounds",
                    "min", mn.x() + "," + mn.y() + "," + mn.z(),
                    "max", mx.x() + "," + mx.y() + "," + mx.z())), false);
            int cx = (mn.x() + mx.x()) / 2, cy = (mn.y() + mx.y()) / 2, cz = (mn.z() + mx.z()) / 2;
            src.sendSuccess(() -> Component.literal(i18n.format("msg.info.coord",
                    "x", cx, "y", cy, "z", cz)), false);
        }
        if (r.parent() != null)
            src.sendSuccess(() -> Component.literal(i18n.format("msg.info.parent", "parent", r.parent().id())), false);
        // Resolve UUIDs to player names where possible (online or cached). Falls back to UUID
        // string for truly unknown profiles so admins still see SOMETHING actionable.
        // *View() reads avoid lazy-init allocation for regions with no owners/members.
        String none = i18n.raw("msg.info.none");
        String owners = r.ownersView().isEmpty() ? none : r.ownersView().stream()
                .map(u -> dev.thefather007.worldguardneo.util.UuidResolver.nameOf(server, u))
                .collect(Collectors.joining(", "));
        src.sendSuccess(() -> Component.literal(i18n.format("msg.info.owners", "list", owners)), false);
        String members = r.membersView().isEmpty() ? none : r.membersView().stream()
                .map(u -> dev.thefather007.worldguardneo.util.UuidResolver.nameOf(server, u))
                .collect(Collectors.joining(", "));
        src.sendSuccess(() -> Component.literal(i18n.format("msg.info.members", "list", members)), false);
        String flagDump = r.flagsRaw().isEmpty() ? none : r.flagsRaw().entrySet().stream().map(e -> {
            Flag<?> f = e.getKey();
            return f.name() + "=" + f.displayRaw(e.getValue());
        }).collect(Collectors.joining(", "));
        src.sendSuccess(() -> Component.literal(i18n.format("msg.info.flags", "list", flagDump)), false);

        // Show the region outline to the player via the built-in CUI sender (WorldEditCUI renders
        // it client-side). This replaces the old standalone /rg select command — viewing a region
        // now also highlights it. No-op for console senders and the geometry-less global region,
        // and harmless for clients without WorldEditCUI.
        ServerPlayer viewer = src.getPlayer();
        if (viewer != null && !(r instanceof GlobalRegion)) {
            mod.selections().renderRegion(viewer, r);
        }
    }

    /* =========================================================================
     * /rg list  — three variants:
     *   /rg list              → own regions (member OR owner)
     *   /rg list <player>     → another player's regions  (perm: region.list.others)
     *   /rg lists [radius]    → regions near my position  (perm: region.lists.radius)
     * Output format per entry: "<id> (<role>, x y z)"
     *   role: владелец / участник (own/other variants)
     *   role: <owner-name>        (radius variant — first owner, or "—" if none)
     * Coordinates: center of region's AABB (rounded to int).
     * ========================================================================= */

    /** Compute the AABB center as "x y z" (int). For global region we omit coords. */
    private static String regionCenter(ProtectedRegion r) {
        if (r instanceof GlobalRegion) return "global";
        Vec3 mn = r.minimumBound(), mx = r.maximumBound();
        int cx = (mn.x() + mx.x()) / 2;
        int cy = (mn.y() + mx.y()) / 2;
        int cz = (mn.z() + mx.z()) / 2;
        return cx + " " + cy + " " + cz;
    }

    /** Resolve the role of {@code uid} in {@code r} as a localized string. */
    private static String roleOf(WorldGuardNeo mod, ProtectedRegion r, java.util.UUID uid) {
        if (r.isOwner(uid))  return mod.i18n().raw("msg.list.role.owner");
        return mod.i18n().raw("msg.list.role.member");
    }

    /** Resolve a primary "owner name" for radius listings — first owner or "—". */
    private static String primaryOwnerName(net.minecraft.server.MinecraftServer server, ProtectedRegion r) {
        var ownersView = r.ownersView();
        if (ownersView.isEmpty()) return "—";
        java.util.UUID first = ownersView.iterator().next();
        return dev.thefather007.worldguardneo.util.UuidResolver.nameOf(server, first);
    }

    /** /rg list — список регионов, где игрок owner или member. Доступно всем. */
    private static int listOwnRegions(CommandSourceStack src, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        java.util.UUID uid = p.getUUID();
        List<ProtectedRegion> own = new java.util.ArrayList<>();
        for (ProtectedRegion r : mod.regions().get(p.serverLevel()).all()) {
            if (r.isMember(uid)) own.add(r);   // isMember включает в себя isOwner
        }
        if (own.isEmpty()) {
            src.sendSuccess(() -> Component.literal(mod.i18n().raw("msg.list.empty.own")), false);
            return 1;
        }
        String world = p.serverLevel().dimension().location().toString();
        src.sendSuccess(() -> Component.literal(mod.i18n().format("msg.list.header.own",
                "world", world, "count", own.size())), false);
        int max = 40;
        int shown = Math.min(max, own.size());
        for (int i = 0; i < shown; i++) {
            ProtectedRegion r = own.get(i);
            String role  = roleOf(mod, r, uid);
            String coord = regionCenter(r);
            src.sendSuccess(() -> Component.literal(mod.i18n().format("msg.list.entry.own",
                    "id", r.id(), "role", role, "coord", coord)), false);
        }
        if (own.size() > max) {
            int hidden = own.size() - max;
            src.sendSuccess(() -> Component.literal(mod.i18n().format("msg.list.truncated",
                    "hidden", hidden)), false);
        }
        return 1;
    }

    /**
     * /rg list &lt;player&gt; — список регионов указанного игрока, где он owner или member.
     * Требует region.list.others (default OP 2). Принимает имя или UUID-string.
     */
    private static int listPlayerRegions(CommandSourceStack src, String playerArg, WorldGuardNeo mod)
            throws CommandSyntaxException {
        ServerPlayer caller = src.getPlayerOrException();
        var server = src.getServer();
        // Резолвим input — поддерживает и UUID-string и имя (через online players + ProfileCache).
        var resolved = dev.thefather007.worldguardneo.util.UuidResolver.resolve(server, playerArg);
        if (resolved.isEmpty()) {
            err(src, mod, "msg.list.player.notfound", "player", playerArg);
            return 0;
        }
        final java.util.UUID uid = resolved.get();
        String resolvedName = dev.thefather007.worldguardneo.util.UuidResolver.nameOf(server, uid);

        List<ProtectedRegion> regs = new java.util.ArrayList<>();
        for (ProtectedRegion r : mod.regions().get(caller.serverLevel()).all()) {
            if (r.isMember(uid)) regs.add(r);
        }
        if (regs.isEmpty()) {
            src.sendSuccess(() -> Component.literal(mod.i18n().format("msg.list.empty.player",
                    "player", resolvedName)), false);
            return 1;
        }
        String world = caller.serverLevel().dimension().location().toString();
        src.sendSuccess(() -> Component.literal(mod.i18n().format("msg.list.header.player",
                "player", resolvedName, "world", world, "count", regs.size())), false);
        int max = 40;
        int shown = Math.min(max, regs.size());
        for (int i = 0; i < shown; i++) {
            ProtectedRegion r = regs.get(i);
            String role  = roleOf(mod, r, uid);
            String coord = regionCenter(r);
            src.sendSuccess(() -> Component.literal(mod.i18n().format("msg.list.entry.own",
                    "id", r.id(), "role", role, "coord", coord)), false);
        }
        if (regs.size() > max) {
            int hidden = regs.size() - max;
            src.sendSuccess(() -> Component.literal(mod.i18n().format("msg.list.truncated",
                    "hidden", hidden)), false);
        }
        return 1;
    }

    /**
     * /rg lists [radius] — регионы в радиусе вокруг игрока. Y range — весь мир (мы хотим
     * найти регион даже если он только сверху/снизу нас). Default radius = 50.
     * Требует region.lists.radius (default OP 2).
     */
    private static int listInRadius(CommandSourceStack src, int radius, WorldGuardNeo mod)
            throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        var lvl = p.serverLevel();
        int px = (int) Math.floor(p.getX());
        int pz = (int) Math.floor(p.getZ());
        // Y range — весь мир. Хотим найти регион даже если он строго сверху/снизу нас (другой
        // этаж в build'е). getMinBuildHeight/getMaxBuildHeight на ServerLevel дают актуальные
        // границы (учитывая моды на extended world height).
        int worldMinY = lvl.getMinBuildHeight();
        int worldMaxY = lvl.getMaxBuildHeight();
        Vec3 corner1 = new Vec3(px - radius, worldMinY, pz - radius);
        Vec3 corner2 = new Vec3(px + radius, worldMaxY, pz + radius);
        // ВНИМАНИЕ: используется AABB-overlap. Polygon-регион с большим bounding box, но
        // узкой геометрией может попасть в результат, даже если все его vertex-точки
        // далеко от позиции игрока. Это сознательный компромисс — точная geometric
        // intersection слишком дорога для UI-команды.
        List<ProtectedRegion> regs = mod.regions().get(lvl).overlapping(corner1, corner2);

        if (regs.isEmpty()) {
            src.sendSuccess(() -> Component.literal(mod.i18n().format("msg.list.empty.radius",
                    "radius", radius)), false);
            return 1;
        }
        src.sendSuccess(() -> Component.literal(mod.i18n().format("msg.list.header.radius",
                "radius", radius, "count", regs.size())), false);
        int max = 40;
        int shown = Math.min(max, regs.size());
        var server = src.getServer();
        for (int i = 0; i < shown; i++) {
            ProtectedRegion r = regs.get(i);
            String ownerName = primaryOwnerName(server, r);
            String coord     = regionCenter(r);
            src.sendSuccess(() -> Component.literal(mod.i18n().format("msg.list.entry.radius",
                    "id", r.id(), "owner", ownerName, "coord", coord)), false);
        }
        if (regs.size() > max) {
            int hidden = regs.size() - max;
            src.sendSuccess(() -> Component.literal(mod.i18n().format("msg.list.truncated",
                    "hidden", hidden)), false);
        }
        return 1;
    }

    private static int teleportToRegion(CommandSourceStack src, String id, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        var ropt = mod.regions().get(p.serverLevel()).get(id);
        if (ropt.isEmpty()) { err(src, mod, "msg.region.unknown", "id", id); return 0; }
        ProtectedRegion r = ropt.get();
        String loc = r.getFlag(Flags.TELE_LOC);
        ServerLevel lvl = p.serverLevel();
        double tx, ty, tz;
        if (loc != null) {
            // Accept both comma- AND space-separated formats:
            //   "100,64,-50"          / "100, 64, -50"
            //   "100 64 -50"          / "100 64 -50 90 0"  (with optional yaw+pitch)
            // Older configs may use either; docs say space-separated so support both.
            String[] parts = loc.trim().split("[,\\s]+");
            if (parts.length >= 3) {
                try {
                    tx = Double.parseDouble(parts[0]);
                    ty = Double.parseDouble(parts[1]);
                    tz = Double.parseDouble(parts[2]);
                    p.teleportTo(tx, ty, tz);
                    ok(src, mod, "msg.teleport.done", "id", id);
                    return 1;
                } catch (NumberFormatException ignored) {}
            }
        }
        // Fallback: teleport to AABB top-center if flag missing/invalid.
        if (r instanceof dev.thefather007.worldguardneo.region.GlobalRegion) {
            err(src, mod, "msg.region.global-select", "id", id);
            return 0;
        }
        Vec3 mn = r.minimumBound(), mx = r.maximumBound();
        tx = (mn.x() + mx.x()) / 2.0 + 0.5;
        tz = (mn.z() + mx.z()) / 2.0 + 0.5;
        ty = Math.min(mx.y() + 1, lvl.getMaxBuildHeight() - 1);
        p.teleportTo(tx, ty, tz);
        ok(src, mod, "msg.teleport.done", "id", id);
        return 1;
    }

    /**
     * /rg flag &lt;id&gt; &lt;flag&gt; [-g group] [value]
     *
     * Access rules (must satisfy ALL):
     *   1. <b>Region access</b>: the player is an owner of the region, OR holds
     *      {@code worldguardneo.region.flag.others}, OR holds {@code worldguardneo.region.bypass}.
     *   2. <b>Flag access</b>: the player holds the per-flag permission node,
     *      {@code worldguardneo.flag.<flag-name>} (dots in the flag name are kept).
     *   3. <b>Group syntax access</b>: if the {@code -g group} qualifier was used, the player
     *      must additionally hold {@code worldguardneo.region.flag.group}. This protects against
     *      exploit cases like {@code invincible -g OWNERS allow} that would otherwise let
     *      non-admin owners create asymmetric PvP advantages. Players without this node can
     *      still set flags WITHOUT {@code -g} — the default group from config applies.
     *   4. (Implicit) the player went through the brigadier tree, which only enforces the
     *      top-level {@code /region} or {@code /rg} root.
     *
     * Players with {@code worldguardneo.region.flag.bypass} skip per-flag node checks AND
     * the group-syntax check.
     */
    private static int setFlag(CommandSourceStack src, String id, String flagName, String value,
                               String group, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        RegionManager mgr = mod.regions().get(p.serverLevel());
        var ropt = mgr.get(id);
        if (ropt.isEmpty()) { err(src, mod, "msg.region.unknown", "id", id); return 0; }
        ProtectedRegion region = ropt.get();

        // Region access.
        boolean bypass    = mod.perms().has(p, "worldguardneo.region.bypass");
        boolean isOwner   = region.isOwner(p.getUUID());
        boolean canOthers = bypass || mod.perms().has(p, "worldguardneo.region.flag.others");
        if (!isOwner && !canOthers) {
            err(src, mod, "msg.region.notyours", "id", id);
            return 0;
        }

        Flag<?> flag = Flags.get(flagName);
        if (flag == null) { err(src, mod, "msg.flag.unknown", "flag", flagName); return 0; }

        // Per-flag permission. The bypass node covers all flags.
        boolean flagBypass = bypass || mod.perms().has(p, "worldguardneo.region.flag.bypass");
        if (!flagBypass && !mod.perms().has(p, flag.permission())) {
            err(src, mod, "msg.flag.denied", "flag", flagName);
            return 0;
        }

        // Group-syntax permission. The {@code -g group} qualifier requires its own node so
        // ordinary players can't craft asymmetric flags (e.g. "invincible -g OWNERS allow").
        // If the player lacks this node and tried to use -g, we reject the command entirely
        // rather than silently downgrading — silent downgrade hides intent and is confusing.
        if (group != null && !flagBypass
                && !mod.perms().has(p, "worldguardneo.region.flag.group")) {
            err(src, mod, "msg.flag.group-denied");
            return 0;
        }

        try {
            // Resolve the effective group up front; null means "leave group untouched".
            String effectiveGroup = group != null ? group : mod.config().global().defaultRegionGroup;
            RegionGroup rg = (effectiveGroup != null && !effectiveGroup.isEmpty())
                    ? RegionGroup.parse(effectiveGroup) : null;
            // parseAndApply runs entirely inside Flag<T>'s generic context, so there's no raw
            // type or unchecked cast here — the flag binds its own T for parse + setFlag.
            Object parsed = flag.parseAndApply(region, value, rg);
            mod.regions().saveRegion(p.serverLevel(), region.id()); // incremental: just this region

            mod.audit().record(src, "flag", id, flagName + "=" + (parsed == null ? "<unset>" : parsed));
        ok(src, mod, "msg.flag.set", "id", id, "flag", flagName, "value", parsed == null ? "<unset>" : parsed);
            return 1;
        } catch (Flag.FlagParseException e) {
            err(src, mod, "msg.flag.parse-error", "flag", flagName, "error", e.getMessage());
            return 0;
        }
    }

    private static int setFlagGrouped(CommandContext<CommandSourceStack> c, WorldGuardNeo mod) throws CommandSyntaxException {
        return setFlag(c.getSource(),
                StringArgumentType.getString(c, "id"),
                StringArgumentType.getString(c, "flag"),
                StringArgumentType.getString(c, "value"),
                StringArgumentType.getString(c, "group"),
                mod);
    }

    private static int setPriority(CommandSourceStack src, String id, int value, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        var ropt = mod.regions().get(p.serverLevel()).get(id);
        if (ropt.isEmpty()) { err(src, mod, "msg.region.unknown", "id", id); return 0; }
        // Global region has no geometry — priority is meaningless on it (it's the resolution
        // fallback, never part of the applicable list).
        if (ropt.get() instanceof GlobalRegion) {
            err(src, mod, "msg.region.global-select", "id", id);
            return 0;
        }
        // Same region-access rule as /rg flag: owner OR flag.others OR bypass. Without this,
        // anyone holding the priority node could re-prioritise FOREIGN regions — e.g. raise
        // their own claim above a neighbour's overlapping one to flip flag resolution.
        if (!region2AccessOk(p, ropt.get(), mod)) {
            err(src, mod, "msg.region.notyours", "id", id);
            return 0;
        }
        ropt.get().setPriority(value);
        mod.regions().saveRegion(p.serverLevel(), ropt.get().id()); // incremental: just this region
        mod.audit().record(src, "priority", id, String.valueOf(value));
        ok(src, mod, "msg.priority.set", "id", id, "value", value);
        return 1;
    }

    /** Shared region-access rule for region-shape/meta edits: owner OR flag.others OR bypass. */
    private static boolean region2AccessOk(ServerPlayer p, ProtectedRegion r, WorldGuardNeo mod) {
        return r.isOwner(p.getUUID())
            || mod.perms().has(p, "worldguardneo.region.bypass")
            || mod.perms().has(p, "worldguardneo.region.flag.others");
    }

    private static int setParent(CommandSourceStack src, String id, String parent, WorldGuardNeo mod) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        RegionManager mgr = mod.regions().get(p.serverLevel());
        var ropt = mgr.get(id);
        if (ropt.isEmpty()) { err(src, mod, "msg.region.unknown", "id", id); return 0; }
        if (ropt.get() instanceof GlobalRegion) {
            err(src, mod, "msg.region.global-select", "id", id);
            return 0;
        }
        // Owner/bypass/others gate — mirrors setPriority. Re-parenting is flag-inheritance
        // surgery: without this check a player could attach their region under a foreign admin
        // region and inherit its flags.
        if (!region2AccessOk(p, ropt.get(), mod)) {
            err(src, mod, "msg.region.notyours", "id", id);
            return 0;
        }
        if (parent == null) { ropt.get().setParent(null); ok(src, mod, "msg.parent.cleared", "child", id); }
        else {
            var popt = mgr.get(parent);
            if (popt.isEmpty()) { err(src, mod, "msg.region.unknown", "id", parent); return 0; }
            // Parenting under the global region is redundant (it's already the universal
            // fallback in flag resolution) and would create a bogus link in save data.
            if (popt.get() instanceof GlobalRegion) {
                err(src, mod, "msg.region.global-select", "id", parent);
                return 0;
            }
            try { ropt.get().setParent(popt.get()); }
            catch (Exception e) { err(src, mod, "msg.parent.cycle"); return 0; }
            ok(src, mod, "msg.parent.set", "child", id, "parent", parent);
        }
        mod.regions().saveRegion(p.serverLevel(), ropt.get().id()); // incremental: just this child
        mod.audit().record(src, "setparent", id, parent == null ? "<none>" : parent);
        return 1;
    }

    /**
     * Add/remove a player as an owner or a member.
     *
     * The {@code playerArg} argument is a plain string and is resolved by
     * {@link dev.thefather007.worldguardneo.util.UuidResolver}: it may be a raw UUID, an online
     * player name, or a name cached in the server's {@code usercache.json}. Offline
     * players outside the cache cannot be looked up (we don't hit the Mojang API).
     */
    private static int changeMembership(CommandContext<CommandSourceStack> c,
                                        String idArg, String playerArg, WorldGuardNeo mod,
                                        boolean owner, boolean add) throws CommandSyntaxException {
        ServerPlayer self = c.getSource().getPlayerOrException();
        String id   = StringArgumentType.getString(c, idArg);
        String who  = StringArgumentType.getString(c, playerArg);

        var ropt = mod.regions().get(self.serverLevel()).get(id);
        if (ropt.isEmpty()) { err(c.getSource(), mod, "msg.region.unknown", "id", id); return 0; }

        // Region access — must be an owner OR hold region.bypass. Without this gate, any
        // OP-2 player (who has region.addowner by default) could add themselves to any
        // region. This matches original WorldGuard's "owner or admin" semantics.
        ProtectedRegion r = ropt.get();
        boolean bypass  = mod.perms().has(self, "worldguardneo.region.bypass");
        boolean isOwner = r.isOwner(self.getUUID());
        if (!bypass && !isOwner) {
            err(c.getSource(), mod, "msg.region.notyours", "id", id);
            return 0;
        }

        var uuidOpt = dev.thefather007.worldguardneo.util.UuidResolver.resolve(self.getServer(), who);
        if (uuidOpt.isEmpty()) {
            err(c.getSource(), mod, "msg.player.unknown", "player", who);
            return 0;
        }
        UUID target = uuidOpt.get();
        var set = owner ? r.owners() : r.members();
        boolean changed = add ? set.add(target) : set.remove(target);
        if (!changed) {
            err(c.getSource(), mod, add ? "msg.member.already" : "msg.member.notpresent",
                    "player", dev.thefather007.worldguardneo.util.UuidResolver.nameOf(self.getServer(), target),
                    "id", id, "role", owner ? "owner" : "member");
            return 0;
        }
        mod.regions().saveRegion(self.serverLevel(), r.id()); // incremental: just this region
        mod.audit().record(c.getSource(), (add ? "add-" : "remove-") + (owner ? "owner" : "member"),
                id, "player=" + target);
        ok(c.getSource(), mod, add ? "msg.member.added" : "msg.member.removed",
                "player", dev.thefather007.worldguardneo.util.UuidResolver.nameOf(self.getServer(), target),
                "id", id, "role", owner ? "owner" : "member");
        return 1;
    }

    private static int reload(CommandSourceStack src, WorldGuardNeo mod) {
        // Save first — admins running /rg reload after a config edit shouldn't lose any
        // in-memory region changes that haven't been flushed yet.
        mod.regions().saveAll();
        // Re-read disk: config (global + per-world overrides) AND localization (lang/<tag>.json).
        // The config reload also clears the per-Level WorldSection cache.
        mod.config().reload();
        try {
            // Locale may have changed in config.toml — pick up the new tag.
            mod.i18n().reload(
                    net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("worldguardneo"),
                    mod.config().locale());
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.warn("Localization reload failed", t);
        }
        // Permission resolver may depend on config (op-level mappings) — refresh too.
        try { mod.perms().reload(); }
        catch (Throwable t) { WorldGuardNeo.LOGGER.warn("Permission reload failed", t); }
        // Push markers to any active map integrations after config reload.
        var bm = dev.thefather007.worldguardneo.integrations.BluemapIntegration.get();
        if (bm != null) bm.publishAll();
        var sq = dev.thefather007.worldguardneo.integrations.SquaremapIntegration.get();
        if (sq != null) sq.publishAll();
        ok(src, mod, "msg.reloaded");
        return 1;
    }

    /**
     * Pretty list of all known flags with value hints and (if available) a localized
     * description from the lang file. Description is shown only if its key is defined;
     * otherwise the value hint is used as the only hint.
     */
    private static int listFlags(CommandSourceStack src, WorldGuardNeo mod) {
        src.sendSuccess(() -> Component.literal(mod.i18n().format("msg.flags.header",
                "count", Flags.all().size())), false);
        // Group flags by Java type for readable output.
        java.util.List<Flag<?>> sorted = new java.util.ArrayList<>(Flags.all());
        sorted.sort(java.util.Comparator.comparing(Flag::name));
        for (Flag<?> f : sorted) {
            String descKey = f.descriptionKey();
            String desc = mod.i18n().has(descKey) ? mod.i18n().raw(descKey) : "";
            String line = "§7- §e" + f.name() + " §8[" + f.valueHint() + "]"
                    + (desc.isEmpty() ? "" : " §7— §r" + desc);
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** Diagnostic dump: spatial-index stats per world, permission backend, integrations. */
    private static int debugInfo(CommandSourceStack src, WorldGuardNeo mod) {
        // Works from both player and console. For console (no player level) we report
        // aggregate stats across all loaded levels — admins running this from a cron
        // need to see the totals, not be told "you're not a player".
        ServerPlayer p = src.getPlayer();
        if (p != null) {
            RegionManager mgr = mod.regions().get(p.serverLevel());
            SpatialIndex idx = mgr.index();
            src.sendSuccess(() -> Component.literal("§7━━ §6WorldGuardNeo debug§7 ━━"), false);
            src.sendSuccess(() -> Component.literal(
                    "§7world: §f" + p.serverLevel().dimension().location()
                            + " §7regions: §f" + mgr.size()
                            + " §7buckets: §f" + idx.bucketCount()
                            + " §7oversized: §f" + idx.oversizedCount()
                            + " §7refs: §f" + idx.totalRefs()), false);
            src.sendSuccess(() -> Component.literal(
                    "§7perms: §f" + mod.perms().name()
                            + " §7luckperms: §f" + net.neoforged.fml.ModList.get().isLoaded("luckperms")
                            + " §7bluemap: §f" + net.neoforged.fml.ModList.get().isLoaded("bluemap")
                            + " §7squaremap: §f" + net.neoforged.fml.ModList.get().isLoaded("squaremap")), false);
            var here = mgr.getApplicable(p.getX(), p.getY(), p.getZ());
            src.sendSuccess(() -> Component.literal(
                    "§7applicable here (§f" + here.size() + "§7): §f"
                            + here.stream().map(ProtectedRegion::id).collect(Collectors.joining(", "))), false);
        } else {
            // Console path — no player, no current world. Report mod-level overview.
            src.sendSuccess(() -> Component.literal("§7━━ §6WorldGuardNeo debug (console)§7 ━━"), false);
            src.sendSuccess(() -> Component.literal(
                    "§7worlds tracked: §f" + mod.regions().size()
                            + " §7perms: §f" + mod.perms().name()
                            + " §7luckperms: §f" + net.neoforged.fml.ModList.get().isLoaded("luckperms")
                            + " §7bluemap: §f" + net.neoforged.fml.ModList.get().isLoaded("bluemap")
                            + " §7squaremap: §f" + net.neoforged.fml.ModList.get().isLoaded("squaremap")), false);
        }
        return 1;
    }

    /**
     * Trigger an immediate async backup. The command returns success once the task is
     * queued — the actual write happens off-thread. Result (or failure) appears in the
     * server log; player gets a "started" notice now.
     */
    private static int doBackupNow(CommandSourceStack src, WorldGuardNeo mod, String label) {
        boolean queued = mod.backups().runAsync(label);
        if (queued) {
            ok(src, mod, "msg.backup.started", "label", label == null ? "" : label);
        } else {
            err(src, mod, "msg.backup.skipped");
        }
        return queued ? 1 : 0;
    }

    /**
     * Show the list of existing backup directories, newest first. Output is paged
     * client-side via Minecraft chat (no explicit pagination — admins reading via
     * console see the full list).
     */
    private static int listBackups(CommandSourceStack src, WorldGuardNeo mod) {
        var list = mod.backups().listBackups();
        if (list.isEmpty()) {
            ok(src, mod, "msg.backup.list-empty");
            return 1;
        }
        ok(src, mod, "msg.backup.list-header", "count", list.size());
        for (String name : list) {
            // We don't reuse ok() here because the line includes a §-code prefix to mark
            // entries visually. ok() also routes through sendSuccess() which is what we want.
            src.sendSuccess(() -> Component.literal(
                    mod.i18n().format("msg.backup.list-entry", "name", name)), false);
        }
        return 1;
    }

    /* helpers */
    private static void ok(CommandSourceStack s, WorldGuardNeo m, String key, Object... a) {
        s.sendSuccess(() -> Component.literal(m.i18n().format(key, a)), false);
    }
    private static void err(CommandSourceStack s, WorldGuardNeo m, String key, Object... a) {
        // sendFailure already styles in italic red by default for chat-source senders;
        // we let lang §-codes override that for player-source chats.
        s.sendFailure(Component.literal(m.i18n().format(key, a)));
    }

    /**
     * Validates a user-supplied region id: 1–40 chars of {@code [A-Za-z0-9_-]}, and not the
     * reserved global-region id. This keeps ids safe for every storage backend (no '.' that could
     * collide with the {@code .corrupt-} quarantine marker, no chars that break JSON filenames or
     * SQL), matching WorldGuard's id conventions.
     */
    private static boolean isValidRegionId(String id) {
        if (id == null || id.isEmpty() || id.length() > 40) return false;
        if (id.equalsIgnoreCase(dev.thefather007.worldguardneo.region.GlobalRegion.ID)) return false;
        for (int i = 0, n = id.length(); i < n; i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                      || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Lowercases the input and keeps only {@code [a-z0-9_-]}. Used by /rg claim auto-id
     * generation to turn a player display name (which can contain Unicode, spaces, capitals)
     * into a usable region id. Empty result is the caller's signal to fall back to "claim".
     */
    private static String sanitizeForId(String s) {
        if (s == null || s.isEmpty()) return "";
        // Single pass: walk original chars, lowercase ASCII inline. Avoids the
        // toLowerCase() intermediate String allocation. For non-ASCII chars (e.g.
        // Cyrillic player names), Character.toLowerCase wouldn't help anyway since
        // they won't match the [a-z0-9_-] filter.
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // ASCII uppercase → lowercase
            if (ch >= 'A' && ch <= 'Z') ch = (char) (ch + 32);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-') {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
