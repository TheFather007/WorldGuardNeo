package dev.thefather007.worldguardneo.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import dev.thefather007.worldguardneo.WorldGuardNeo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Mod configuration, stored as a human-editable TOML file with inline comments — the same
 * format other NeoForge mods use. A single global config plus per-world overrides.
 *
 * <p>On first run {@code config/worldguardneo/config.toml} is written with every key documented
 * by a comment. Admins edit it directly and run {@code /rg reload}. Per-world overrides live in
 * {@code config/worldguardneo/worlds/<dimension>.toml} and inherit from the global {@code
 * [defaults]} section.
 *
 * <p>Comments live in the file itself (TOML supports them natively), so there is no separate
 * help document — the previous {@code CONFIG_HELP.md} has been removed.
 */
public final class WGConfig {

    private final Path root;
    private GlobalSection global;
    private final Map<String, WorldSection> worldOverrides = new HashMap<>();

    private WGConfig(Path root) { this.root = root; }

    /* =====================================================================================
     * Loading
     * ===================================================================================== */

    public static WGConfig loadOrCreate(Path configDir) {
        WGConfig cfg = new WGConfig(configDir);
        try {
            Files.createDirectories(configDir);
            Files.createDirectories(configDir.resolve("worlds"));

            // Clean up the obsolete JSON config + help doc from older builds, if present.
            cleanupLegacyFiles(configDir);

            Path main = configDir.resolve("config.toml");
            if (!Files.exists(main)) {
                cfg.global = GlobalSection.defaults();
                cfg.save();
            } else {
                cfg.global = readGlobal(main);
            }

            try (var stream = Files.list(configDir.resolve("worlds"))) {
                stream.filter(p -> p.toString().endsWith(".toml")).forEach(p -> {
                    String key = p.getFileName().toString().replace(".toml", "");
                    try {
                        cfg.worldOverrides.put(key, readWorld(p));
                    } catch (Exception ex) {
                        WorldGuardNeo.LOGGER.warn("Failed to read world config {}", p, ex);
                    }
                });
            }
        } catch (IOException ex) {
            WorldGuardNeo.LOGGER.error("Failed to load WorldGuardNeo config", ex);
            cfg.global = GlobalSection.defaults();
        }
        return cfg;
    }

    /** Delete config.json / CONFIG_HELP.md left over from the pre-TOML format. */
    private static void cleanupLegacyFiles(Path configDir) {
        for (String legacy : new String[]{"config.json", "CONFIG_HELP.md"}) {
            try { Files.deleteIfExists(configDir.resolve(legacy)); }
            catch (IOException ignored) { /* not critical */ }
        }
    }

    private static GlobalSection readGlobal(Path file) {
        GlobalSection g = GlobalSection.defaults();
        try {
            CommentedConfig toml;
            try (var reader = Files.newBufferedReader(file)) {
                toml = TomlFormat.instance().createParser().parse(reader);
            }
            g.locale                  = str(toml, "locale", g.locale);
            g.storageFormat           = str(toml, "storage-format", g.storageFormat);
            g.useLuckPerms            = bool(toml, "use-luckperms", g.useLuckPerms);
            g.defaultOpLevelAdmin     = intOf(toml, "default-op-level-admin", g.defaultOpLevelAdmin);
            g.defaultOpLevelMod       = intOf(toml, "default-op-level-mod", g.defaultOpLevelMod);
            g.maxRegionsPerPlayer     = intOf(toml, "max-regions-per-player", g.maxRegionsPerPlayer);
            g.maxRegionVolume         = intOf(toml, "max-region-volume", g.maxRegionVolume);
            g.maxClaimableArea        = intOf(toml, "max-claimable-area", g.maxClaimableArea);
            g.minRegionVolume         = intOf(toml, "min-region-volume", g.minRegionVolume);
            g.wandItemSelectionTicks  = intOf(toml, "wand-item-selection-ticks", g.wandItemSelectionTicks);
            g.announceGreetings       = bool(toml, "announce-greetings", g.announceGreetings);
            g.announceFarewells       = bool(toml, "announce-farewells", g.announceFarewells);
            g.announceRegionActionBar = bool(toml, "announce-region-action-bar", g.announceRegionActionBar);
            g.blockedEntityHostile    = bool(toml, "blocked-entity-hostile", g.blockedEntityHostile);
            g.defaultRegionGroup      = str(toml, "default-region-group", g.defaultRegionGroup);

            g.backupEnabled           = bool(toml, "backup.enabled", g.backupEnabled);
            g.backupIntervalMinutes   = intOf(toml, "backup.interval-minutes", g.backupIntervalMinutes);
            g.backupRetainCount       = intOf(toml, "backup.retain-count", g.backupRetainCount);
            g.backupCompress          = bool(toml, "backup.compress", g.backupCompress);

            g.claimExpiryEnabled      = bool(toml, "claim-expiry.enabled", g.claimExpiryEnabled);
            g.claimExpiryDays         = intOf(toml, "claim-expiry.days", g.claimExpiryDays);
            g.claimExpiryCheckHours   = intOf(toml, "claim-expiry.check-hours", g.claimExpiryCheckHours);

            g.wandItem                = str(toml, "selection.wand-item", g.wandItem);

            g.mysqlHost              = str(toml, "mysql.host", g.mysqlHost);
            g.mysqlPort              = intOf(toml, "mysql.port", g.mysqlPort);
            g.mysqlDatabase          = str(toml, "mysql.database", g.mysqlDatabase);
            g.mysqlUser              = str(toml, "mysql.user", g.mysqlUser);
            g.mysqlPassword          = str(toml, "mysql.password", g.mysqlPassword);
            g.mysqlUseSsl            = bool(toml, "mysql.use-ssl", g.mysqlUseSsl);
            g.mysqlTable             = str(toml, "mysql.table", g.mysqlTable);
            g.mysqlConnectionTimeout = intOf(toml, "mysql.connection-timeout-seconds", g.mysqlConnectionTimeout);
            g.mysqlProperties        = readStrList(toml, "mysql.properties", g.mysqlProperties);

            g.groupRegionLimits = readIntMap(toml, "group-region-limits", g.groupRegionLimits);

            g.defaults.useRegions                     = bool(toml, "defaults.use-regions", g.defaults.useRegions);
            g.defaults.protectVehicles                = bool(toml, "defaults.protect-vehicles", g.defaults.protectVehicles);
            g.defaults.invincibleRegions              = bool(toml, "defaults.invincible-regions", g.defaults.invincibleRegions);
            g.defaults.disableExplosionsAroundRegions = bool(toml, "defaults.disable-explosions-around-regions", g.defaults.disableExplosionsAroundRegions);
            g.defaults.preventFireSpread              = bool(toml, "defaults.prevent-fire-spread", g.defaults.preventFireSpread);
            g.defaults.preventLavaFire                = bool(toml, "defaults.prevent-lava-fire", g.defaults.preventLavaFire);
            g.defaults.preventLightningFire           = bool(toml, "defaults.prevent-lightning-fire", g.defaults.preventLightningFire);
            g.defaults.preventMobDamage               = bool(toml, "defaults.prevent-mob-damage", g.defaults.preventMobDamage);
            g.defaults.blockedItems    = readStrMap(toml, "defaults.blocked-items", g.defaults.blockedItems);
            g.defaults.blockedBlocks   = readStrMap(toml, "defaults.blocked-blocks", g.defaults.blockedBlocks);
            g.defaults.blockedEntities = readStrMap(toml, "defaults.blocked-entities", g.defaults.blockedEntities);
            g.defaults.autoFlags          = readStrList(toml, "defaults.auto-flags", g.defaults.autoFlags);
            g.defaults.verticalExpansion  = str(toml, "defaults.vertical-expansion", g.defaults.verticalExpansion);
            g.defaults.verticalExpandDown = intOf(toml, "defaults.vertical-expand-down", g.defaults.verticalExpandDown);
            g.defaults.verticalExpandUp   = intOf(toml, "defaults.vertical-expand-up", g.defaults.verticalExpandUp);
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error(
                    "config.toml is malformed — keeping values parsed so far, defaults for the rest. " +
                    "Fix the file or delete it to regenerate.", ex);
            // Return the partially-populated section (g started as defaults and was overwritten
            // field-by-field up to the failure), NOT a fresh defaults() — otherwise one bad key
            // deep in the file silently discards every admin setting parsed before it.
            return g;
        }
        return g;
    }

    private static WorldSection readWorld(Path file) throws IOException {
        WorldSection ws = new WorldSection();
        CommentedConfig toml;
        try (var reader = Files.newBufferedReader(file)) {
            toml = TomlFormat.instance().createParser().parse(reader);
        }
        ws.useRegions                     = bool(toml, "use-regions", ws.useRegions);
        ws.protectVehicles                = bool(toml, "protect-vehicles", ws.protectVehicles);
        ws.invincibleRegions              = bool(toml, "invincible-regions", ws.invincibleRegions);
        ws.disableExplosionsAroundRegions = bool(toml, "disable-explosions-around-regions", ws.disableExplosionsAroundRegions);
        ws.preventFireSpread              = bool(toml, "prevent-fire-spread", ws.preventFireSpread);
        ws.preventLavaFire                = bool(toml, "prevent-lava-fire", ws.preventLavaFire);
        ws.preventLightningFire           = bool(toml, "prevent-lightning-fire", ws.preventLightningFire);
        ws.preventMobDamage               = bool(toml, "prevent-mob-damage", ws.preventMobDamage);
        ws.blockedItems    = readStrMap(toml, "blocked-items", ws.blockedItems);
        ws.blockedBlocks   = readStrMap(toml, "blocked-blocks", ws.blockedBlocks);
        ws.blockedEntities = readStrMap(toml, "blocked-entities", ws.blockedEntities);
        ws.autoFlags          = readStrList(toml, "auto-flags", ws.autoFlags);
        ws.verticalExpansion  = str(toml, "vertical-expansion", ws.verticalExpansion);
        ws.verticalExpandDown = intOf(toml, "vertical-expand-down", ws.verticalExpandDown);
        ws.verticalExpandUp   = intOf(toml, "vertical-expand-up", ws.verticalExpandUp);
        return ws;
    }

    /* ---- typed readers tolerant of missing keys / wrong types ---- */
    private static String str(Config c, String path, String def) {
        Object v = c.get(path);
        // Type-guard like bool()/intOf(): a non-string value (e.g. storage-format = true) falls
        // back to the default instead of being coerced to garbage ("true") via String.valueOf.
        return v instanceof String s ? s : def;
    }
    private static boolean bool(Config c, String path, boolean def) {
        Object v = c.get(path);
        return v instanceof Boolean b ? b : def;
    }
    private static int intOf(Config c, String path, int def) {
        Object v = c.get(path);
        if (v instanceof Number n) return n.intValue();
        return def;
    }

    private static Map<String, Integer> readIntMap(Config c, String path, Map<String, Integer> def) {
        Object v = c.get(path);
        if (!(v instanceof Config sub)) return def;
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Config.Entry e : sub.entrySet()) {
            if (e.getValue() instanceof Number n) out.put(e.getKey(), n.intValue());
        }
        return out;
    }
    private static Map<String, String> readStrMap(Config c, String path, Map<String, String> def) {
        Object v = c.get(path);
        if (!(v instanceof Config sub)) return def;
        Map<String, String> out = new LinkedHashMap<>();
        for (Config.Entry e : sub.entrySet()) out.put(e.getKey(), String.valueOf(e.getValue()));
        return out;
    }
    @SuppressWarnings("unchecked")
    private static java.util.List<String> readStrList(Config c, String path, java.util.List<String> def) {
        Object v = c.get(path);
        if (!(v instanceof java.util.List<?> list)) return def;
        java.util.List<String> out = new java.util.ArrayList<>(list.size());
        for (Object o : list) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    /* =====================================================================================
     * Saving — writes TOML with a comment on every key.
     * ===================================================================================== */

    public void save() {
        try {
            Files.createDirectories(root);
            writeGlobal(root.resolve("config.toml"));
            for (Map.Entry<String, WorldSection> e : worldOverrides.entrySet()) {
                writeWorld(root.resolve("worlds").resolve(e.getKey() + ".toml"), e.getValue());
            }
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error("Failed to save WorldGuardNeo config", ex);
        }
    }

    private void writeGlobal(Path file) {
        CommentedConfig c = CommentedConfig.inMemory();
        GlobalSection g = global;

        // night-config writes keys in insertion order, so the order below IS the file layout.
        // Keys are grouped into labelled sections; each section's first key carries a banner
        // comment so the generated config.toml reads top-to-bottom in a logical order.

        /* ───────────────────────── GENERAL ───────────────────────── */
        c.setComment("locale",
                " ======================= GENERAL =======================\n" +
                " Language for the mod's messages. Built-in: en_us, ru_ru.\n" +
                " For a custom language, drop <tag>.json into config/worldguardneo/lang/.");
        c.set("locale", g.locale);

        /* ───────────────────────── STORAGE ───────────────────────── */
        c.setComment("storage-format",
                " ======================= STORAGE =======================\n" +
                " Region storage backend: \"json\", \"sqlite\", \"h2\" or \"mysql\". REQUIRES A SERVER RESTART.\n" +
                " json   — one file per world (default, no dependencies).\n" +
                " sqlite — embedded regions.sqlite; needs an sqlite-jdbc jar.\n" +
                " h2     — embedded regions_h2; needs an H2 jar (LuckPerms ships one).\n" +
                " mysql  — external server; configure [mysql] below and add a Connector/J or MariaDB jar.\n" +
                " Any DB backend that can't load its driver falls back to json automatically.");
        c.set("storage-format", g.storageFormat);

        c.setComment("mysql",
                " MySQL/MariaDB connection — used ONLY when storage-format = \"mysql\". Drop a\n" +
                " mysql-connector-j-*.jar (or the MariaDB driver) into the server; otherwise\n" +
                " storage transparently falls back to JSON.");
        CommentedConfig my = CommentedConfig.inMemory();
        my.setComment("host", " Server host.");
        my.set("host", g.mysqlHost);
        my.setComment("port", " Server port (default 3306).");
        my.set("port", g.mysqlPort);
        my.setComment("database", " Database name. It must already exist; the table is created automatically.");
        my.set("database", g.mysqlDatabase);
        my.setComment("user", " Username.");
        my.set("user", g.mysqlUser);
        my.setComment("password", " Password.");
        my.set("password", g.mysqlPassword);
        my.setComment("use-ssl", " Connect with SSL/TLS.");
        my.set("use-ssl", g.mysqlUseSsl);
        my.setComment("table",
                " Table name. Change it to host several servers' regions in one database\n" +
                " (e.g. \"survival_regions\"). Allowed chars: letters, digits, underscore.");
        my.set("table", g.mysqlTable);
        my.setComment("connection-timeout-seconds",
                " How long to wait when opening or validating the connection (seconds).");
        my.set("connection-timeout-seconds", g.mysqlConnectionTimeout);
        my.setComment("properties",
                " Extra JDBC parameters appended to the connection URL, each as \"key=value\".\n" +
                " Examples: \"serverTimezone=UTC\", \"tcpKeepAlive=true\", \"allowPublicKeyRetrieval=true\".");
        my.set("properties", g.mysqlProperties);
        c.set("mysql", my);

        /* ───────────────────────── PERMISSIONS ───────────────────────── */
        c.setComment("use-luckperms",
                " ======================= PERMISSIONS =======================\n" +
                " Use LuckPerms for permissions when installed. false forces the built-in op\n" +
                " resolver. REQUIRES A SERVER RESTART (permission backend is chosen at boot).");
        c.set("use-luckperms", g.useLuckPerms);

        c.setComment("default-op-level-admin",
                " Op level (0-4) for admin permission nodes (delete.others, flag.bypass,\n" +
                " region.admin). Does NOT affect bypass/info.global/reload/backup (hardcoded to\n" +
                " op 4). Picked up by /rg reload.");
        c.set("default-op-level-admin", g.defaultOpLevelAdmin);

        c.setComment("default-op-level-mod",
                " Op level (0-4) for moderator nodes (info.others, list.others, lists.radius,\n" +
                " flag.others, redefine, addowner, ...). Picked up by /rg reload.");
        c.set("default-op-level-mod", g.defaultOpLevelMod);

        /* ───────────────────────── REGION LIMITS ───────────────────────── */
        c.setComment("max-regions-per-player",
                " ======================= REGION LIMITS =======================\n" +
                " Max regions a player without region.bypass may own (counted across ALL worlds).\n" +
                " Can be raised per LuckPerms group via [group-region-limits].");
        c.set("max-regions-per-player", g.maxRegionsPerPlayer);

        c.setComment("max-region-volume",
                " ABSOLUTE ceiling on region volume in blocks (50000000 ~= 368x368x368). Even\n" +
                " players with bypass cannot exceed this.");
        c.set("max-region-volume", g.maxRegionVolume);

        c.setComment("max-claimable-area",
                " Volume ceiling for /rg claim without bypass (1000000 ~= 100x100x100).");
        c.set("max-claimable-area", g.maxClaimableArea);

        c.setComment("min-region-volume",
                " Minimum region volume in blocks (default 27 = 3x3x3). Stops micro-region spam.\n" +
                " Players with region.bypass skip this check.");
        c.set("min-region-volume", g.minRegionVolume);

        c.setComment("group-region-limits",
                " Per-LuckPerms-group overrides for max-regions-per-player. A player gets the MAX\n" +
                " limit across all their groups. Keys are matched case-insensitively. Only works\n" +
                " with LuckPerms installed; otherwise max-regions-per-player applies to everyone.\n" +
                " Edit or remove these example entries to match your groups.");
        CommentedConfig limits = CommentedConfig.inMemory();
        for (Map.Entry<String, Integer> e : g.groupRegionLimits.entrySet()) {
            limits.set(e.getKey(), e.getValue());
        }
        c.set("group-region-limits", limits);

        /* ───────────────────────── SELECTION ───────────────────────── */
        c.setComment("wand-item-selection-ticks",
                " ======================= SELECTION =======================\n" +
                " How long a wand selection stays visible, in ticks (20 = 1 second). 200 = 10s.");
        c.set("wand-item-selection-ticks", g.wandItemSelectionTicks);

        /* ───────────────────────── FLAG DEFAULTS ───────────────────────── */
        c.setComment("default-region-group",
                " ======================= FLAG DEFAULTS =======================\n" +
                " Default group for new flags when -g isn't given.\n" +
                " One of: ALL, OWNERS, MEMBERS, NON_OWNERS, NON_MEMBERS.");
        c.set("default-region-group", g.defaultRegionGroup);

        /* ───────────────────────── ANNOUNCEMENTS ───────────────────────── */
        c.setComment("announce-greetings",
                " ======================= ANNOUNCEMENTS =======================\n" +
                " Show greeting / greeting-title when a player enters a region.");
        c.set("announce-greetings", g.announceGreetings);
        c.setComment("announce-farewells", " Show farewell / farewell-title when a player leaves a region.");
        c.set("announce-farewells", g.announceFarewells);
        c.setComment("announce-region-action-bar",
                " Show an action-bar with the region name + PvP status on boundary crossings.");
        c.set("announce-region-action-bar", g.announceRegionActionBar);

        /* ───────────────────────── ENTITIES ───────────────────────── */
        c.setComment("blocked-entity-hostile",
                " ======================= ENTITIES =======================\n" +
                " Global peaceful mode: cancel the spawn of every hostile (Monster) entity,\n" +
                " vanilla and modded alike.");
        c.set("blocked-entity-hostile", g.blockedEntityHostile);

        /* ───────────────────────── BACKUPS ───────────────────────── */
        c.setComment("backup",
                " ======================= BACKUPS =======================\n" +
                " Automatic region backups.");
        c.setComment("backup.enabled",
                " Master switch for automatic backups. false disables the scheduler, but\n" +
                " /rg backup still works manually.");
        c.set("backup.enabled", g.backupEnabled);
        c.setComment("backup.interval-minutes",
                " Minutes between automatic backups (0-1440). 0 disables the scheduler. 15-120 recommended.");
        c.set("backup.interval-minutes", g.backupIntervalMinutes);
        c.setComment("backup.retain-count",
                " How many backup directories to keep. Older ones are deleted after a new backup.");
        c.set("backup.retain-count", g.backupRetainCount);
        c.setComment("backup.compress",
                " Gzip each region file inside a backup (typically 6-12x smaller). false = no compression.");
        c.set("backup.compress", g.backupCompress);

        /* ───────────────────────── CLAIM EXPIRY ───────────────────────── */
        c.setComment("claim-expiry",
                " ======================= CLAIM EXPIRY =======================\n" +
                " Auto-delete player regions whose owners have ALL been offline too long.\n" +
                " Admin/unowned regions are never touched. Scan runs at start + every check-hours.");
        c.setComment("claim-expiry.enabled", " Master switch (default false).");
        c.set("claim-expiry.enabled", g.claimExpiryEnabled);
        c.setComment("claim-expiry.days", " Days all owners must be offline before a region expires.");
        c.set("claim-expiry.days", g.claimExpiryDays);
        c.setComment("claim-expiry.check-hours", " Hours between expiry scans.");
        c.set("claim-expiry.check-hours", g.claimExpiryCheckHours);

        /* ───────────────────────── SELECTION ───────────────────────── */
        c.setComment("selection",
                " ======================= REGION SELECTION =======================\n" +
                " Built-in selection wand (no WorldEdit required). Give it with /rg wand.\n" +
                " Left-click sets position 1 (cuboid) or adds a polygon point; right-click sets\n" +
                " position 2. The selection is drawn client-side via WorldEdit-CUI if installed.");
        c.setComment("selection.wand-item",
                " Item id handed out by /rg wand (default minecraft:stick). The wand can only be\n" +
                " obtained once per player and is consumed for selection only.");
        c.set("selection.wand-item", g.wandItem);

        /* ───────────────────────── PER-WORLD DEFAULTS ───────────────────────── */
        c.setComment("defaults",
                " ======================= PER-WORLD DEFAULTS =======================\n" +
                " Applied to every world that has no override file in worlds/.\n" +
                " Create worlds/<dimension>.toml (':' replaced by '_') to override per dimension.");
        CommentedConfig d = CommentedConfig.inMemory();
        writeWorldDefaultsBody(d, g.defaults.useRegions, g.defaults.protectVehicles,
                g.defaults.invincibleRegions, g.defaults.disableExplosionsAroundRegions,
                g.defaults.preventFireSpread, g.defaults.preventLavaFire, g.defaults.preventLightningFire,
                g.defaults.preventMobDamage, g.defaults.blockedItems, g.defaults.blockedBlocks,
                g.defaults.blockedEntities, g.defaults.autoFlags, g.defaults.verticalExpansion,
                g.defaults.verticalExpandDown, g.defaults.verticalExpandUp);
        c.set("defaults", d);

        writeToml(file, c);
    }

    private void writeWorld(Path file, WorldSection ws) {
        CommentedConfig c = CommentedConfig.inMemory();
        writeWorldDefaultsBody(c, ws.useRegions, ws.protectVehicles, ws.invincibleRegions,
                ws.disableExplosionsAroundRegions, ws.preventFireSpread, ws.preventLavaFire,
                ws.preventLightningFire, ws.preventMobDamage, ws.blockedItems, ws.blockedBlocks,
                ws.blockedEntities, ws.autoFlags, ws.verticalExpansion,
                ws.verticalExpandDown, ws.verticalExpandUp);
        writeToml(file, c);
    }

    /** Shared body writer for [defaults] and per-world files (same field set). */
    private static void writeWorldDefaultsBody(CommentedConfig c,
            boolean useRegions, boolean protectVehicles, boolean invincibleRegions,
            boolean disableExplosionsAroundRegions, boolean preventFireSpread, boolean preventLavaFire,
            boolean preventLightningFire, boolean preventMobDamage,
            Map<String, String> blockedItems, Map<String, String> blockedBlocks,
            Map<String, String> blockedEntities,
            java.util.List<String> autoFlags, String verticalExpansion,
            int verticalExpandDown, int verticalExpandUp) {
        c.setComment("use-regions", " Master switch for region protection in this world. false = no protection here.");
        c.set("use-regions", useRegions);
        c.setComment("protect-vehicles", " Protect minecarts/boats from destruction by non-owners.");
        c.set("protect-vehicles", protectVehicles);
        c.setComment("invincible-regions", " Players inside ANY region take no damage of any kind.");
        c.set("invincible-regions", invincibleRegions);
        c.setComment("disable-explosions-around-regions",
                " Explosions near regions don't break blocks inside/around them.");
        c.set("disable-explosions-around-regions", disableExplosionsAroundRegions);
        c.setComment("prevent-fire-spread", " Disable fire spread world-wide (overrides per-region fire-spread).");
        c.set("prevent-fire-spread", preventFireSpread);
        c.setComment("prevent-lava-fire", " Lava can't set blocks on fire (overrides per-region lava-fire).");
        c.set("prevent-lava-fire", preventLavaFire);
        c.setComment("prevent-lightning-fire", " Lightning can't set blocks on fire.");
        c.set("prevent-lightning-fire", preventLightningFire);
        c.setComment("prevent-mob-damage", " Mobs can't damage players in this world.");
        c.set("prevent-mob-damage", preventMobDamage);
        c.setComment("auto-flags",
                " Flags automatically applied to every NEWLY claimed region in this world.\n" +
                " Each entry is \"flag-name=value\", e.g. \"pvp=deny\". Existing regions are not\n" +
                " touched. Unknown flag names or bad values are skipped with a console warning.\n" +
                " Example: auto-flags = [\"pvp=deny\", \"mob-spawning=deny\", \"creeper-explosion=deny\"]");
        c.set("auto-flags", autoFlags);
        c.setComment("vertical-expansion",
                " Automatically expand a region vertically when it is claimed, so players are\n" +
                " protected from tunnelling in from below and bridging in from above:\n" +
                "   \"none\"  — keep the selection's height (default).\n" +
                "   \"full\"  — expand to the world's full build height (bedrock-to-sky). Best\n" +
                "             protection: no digging under or building over the claim.\n" +
                "   \"fixed\" — expand vertical-expand-down blocks down and vertical-expand-up\n" +
                "             blocks up from the selection (each clamped to build limits).\n" +
                " Horizontal claim-size limits are checked on your ORIGINAL selection, so turning\n" +
                " this on does not eat into a player's area allowance.");
        c.set("vertical-expansion", verticalExpansion);
        c.setComment("vertical-expand-down", " Blocks to expand downward when vertical-expansion = \"fixed\".");
        c.set("vertical-expand-down", verticalExpandDown);
        c.setComment("vertical-expand-up", " Blocks to expand upward when vertical-expansion = \"fixed\".");
        c.set("vertical-expand-up", verticalExpandUp);
        c.setComment("blocked-items",
                " Item-use restrictions, id = \"deny\"/\"allow\". e.g. \"minecraft:ender_pearl\" = \"deny\".");
        c.set("blocked-items", strMapToConfig(blockedItems));
        c.setComment("blocked-blocks", " Block-place restrictions, id = \"deny\"/\"allow\".");
        c.set("blocked-blocks", strMapToConfig(blockedBlocks));
        c.setComment("blocked-entities", " Entity-spawn restrictions, id = \"deny\"/\"allow\".");
        c.set("blocked-entities", strMapToConfig(blockedEntities));
    }

    private static CommentedConfig strMapToConfig(Map<String, String> m) {
        CommentedConfig sub = CommentedConfig.inMemory();
        for (Map.Entry<String, String> e : m.entrySet()) sub.set(e.getKey(), e.getValue());
        return sub;
    }

    private static void writeToml(Path file, CommentedConfig c) {
        try {
            Files.createDirectories(file.getParent());
            // night-config writes to a temp file then renames when given a Path (atomic-ish).
            TomlFormat.instance().createWriter().write(c, file, WritingMode.REPLACE);
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.error("Failed to write {}", file, ex);
        }
    }

    /* =====================================================================================
     * Accessors / reload (unchanged behaviour)
     * ===================================================================================== */

    public GlobalSection global() { return global; }

    public void reload() {
        WGConfig fresh = loadOrCreate(root);
        this.global = fresh.global;
        this.worldOverrides.clear();
        this.worldOverrides.putAll(fresh.worldOverrides);
        clearLevelCache();
    }

    public WorldSection worldOrGlobal(String dimensionId) {
        String key = dimensionId.indexOf(':') >= 0 ? dimensionId.replace(':', '_') : dimensionId;
        WorldSection override = worldOverrides.get(key);
        return override != null ? override : global.asWorldDefaults();
    }

    private final java.util.IdentityHashMap<net.minecraft.world.level.Level, WorldSection> levelCache =
            new java.util.IdentityHashMap<>();

    public WorldSection worldOrGlobal(net.minecraft.world.level.Level level) {
        WorldSection cached = levelCache.get(level);
        if (cached != null) return cached;
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            WorldSection ws = worldOrGlobal(sl.dimension().location().toString());
            levelCache.put(level, ws);
            return ws;
        }
        return global.asWorldDefaults();
    }

    public void clearLevelCache() { levelCache.clear(); }
    public void evictLevel(net.minecraft.world.level.Level level) { levelCache.remove(level); }

    public Locale locale() {
        try { return Locale.forLanguageTag(global.locale.replace('_', '-')); }
        catch (Exception e) { return Locale.ENGLISH; }
    }

    /* ------------------------------- sections ------------------------------ */

    public static final class GlobalSection {
        public String  locale                = "en_us";
        public String  storageFormat         = "json";          // json | sqlite (sqlite reserved)
        public boolean useLuckPerms          = true;
        public int     defaultOpLevelAdmin   = 3;
        public int     defaultOpLevelMod     = 2;
        public int     maxRegionsPerPlayer   = 7;
        public int     maxRegionVolume       = 50_000_000;      // hard cap to avoid abuse
        public int     maxClaimableArea      = 1_000_000;
        public Map<String,Integer> groupRegionLimits = new LinkedHashMap<>();
        public int     minRegionVolume       = 27;
        public int     wandItemSelectionTicks = 200;            // visual selection retention
        public boolean announceGreetings      = true;
        public boolean announceFarewells      = true;
        public boolean announceRegionActionBar = true;
        public boolean blockedEntityHostile   = false;
        public String  defaultRegionGroup     = "ALL";          // ALL/NON_OWNERS/MEMBERS/OWNERS/NON_MEMBERS

        public boolean backupEnabled         = true;
        public int     backupIntervalMinutes = 60;
        public int     backupRetainCount     = 10;
        public boolean backupCompress        = true;

        // Claim expiry — automatically delete player regions whose owners have ALL been offline
        // for longer than claim-expiry.days. Admin/unowned regions are never touched. Disabled by
        // default. The scan runs at server start and every claim-expiry.check-hours.
        public boolean claimExpiryEnabled    = false;
        public int     claimExpiryDays       = 60;
        public int     claimExpiryCheckHours = 6;

        // Selection wand — the item handed out by /rg wand for picking region corners/points.
        // Built in, no WorldEdit needed. Defaults to a wooden stick; change to any item id.
        public String  wandItem             = "minecraft:stick";

        // MySQL connection settings — used only when storage-format = "mysql".
        public String  mysqlHost     = "localhost";
        public int     mysqlPort     = 3306;
        public String  mysqlDatabase = "worldguardneo";
        public String  mysqlUser     = "root";
        public String  mysqlPassword = "";
        public boolean mysqlUseSsl   = false;
        public String  mysqlTable    = "world_regions";   // table name (lets several servers share one DB)
        public int     mysqlConnectionTimeout = 10;        // seconds for connect/validate
        public java.util.List<String> mysqlProperties = new java.util.ArrayList<>(); // extra "key=value" JDBC params

        public WorldDefaults defaults         = new WorldDefaults();

        public static GlobalSection defaults() {
            GlobalSection g = new GlobalSection();
            // Seed per-group limits with a working VIP/premium example so admins see the format.
            g.groupRegionLimits.put("default", 7);
            g.groupRegionLimits.put("vip",     15);
            g.groupRegionLimits.put("premium", 30);
            return g;
        }

        public WorldSection asWorldDefaults() {
            WorldSection ws = new WorldSection();
            ws.useRegions      = defaults.useRegions;
            ws.protectVehicles = defaults.protectVehicles;
            ws.invincibleRegions = defaults.invincibleRegions;
            ws.disableExplosionsAroundRegions = defaults.disableExplosionsAroundRegions;
            ws.preventFireSpread = defaults.preventFireSpread;
            ws.preventLavaFire   = defaults.preventLavaFire;
            ws.preventLightningFire = defaults.preventLightningFire;
            ws.preventMobDamage  = defaults.preventMobDamage;
            ws.blockedItems     = new LinkedHashMap<>(defaults.blockedItems);
            ws.blockedBlocks    = new LinkedHashMap<>(defaults.blockedBlocks);
            ws.blockedEntities  = new LinkedHashMap<>(defaults.blockedEntities);
            ws.autoFlags        = new java.util.ArrayList<>(defaults.autoFlags);
            ws.verticalExpansion  = defaults.verticalExpansion;
            ws.verticalExpandDown = defaults.verticalExpandDown;
            ws.verticalExpandUp   = defaults.verticalExpandUp;
            return ws;
        }
    }

    /** What a world inherits if no override exists. */
    public static final class WorldDefaults {
        public boolean useRegions                     = true;
        public boolean protectVehicles                = true;
        public boolean invincibleRegions              = false;
        public boolean disableExplosionsAroundRegions = true;
        public boolean preventFireSpread              = false;
        public boolean preventLavaFire                = false;
        public boolean preventLightningFire           = false;
        public boolean preventMobDamage               = false;
        public Map<String, String> blockedItems     = new LinkedHashMap<>();
        public Map<String, String> blockedBlocks    = new LinkedHashMap<>();
        public Map<String, String> blockedEntities  = new LinkedHashMap<>();
        // Flags auto-applied to every newly claimed region in this world. Each entry is
        // "flag-name=value" (e.g. "pvp=deny"). Empty list = none.
        public java.util.List<String> autoFlags     = new java.util.ArrayList<>();
        // Automatic vertical expansion on claim: "none" | "full" | "fixed".
        //   none  — keep the selection's Y span.
        //   full  — expand to the world's full build height (protects against tunnelling from
        //           below and bridging in from above).
        //   fixed — expand vertical-expand-down blocks down and vertical-expand-up blocks up
        //           from the selection (each clamped to the world's build limits).
        public String  verticalExpansion             = "none";
        public int     verticalExpandDown            = 0;
        public int     verticalExpandUp              = 0;
    }

    /** Per-world override. */
    public static final class WorldSection {
        public boolean useRegions                     = true;
        public boolean protectVehicles                = true;
        public boolean invincibleRegions              = false;
        public boolean disableExplosionsAroundRegions = true;
        public boolean preventFireSpread              = false;
        public boolean preventLavaFire                = false;
        public boolean preventLightningFire           = false;
        public boolean preventMobDamage               = false;
        public Map<String, String> blockedItems     = new LinkedHashMap<>();
        public Map<String, String> blockedBlocks    = new LinkedHashMap<>();
        public Map<String, String> blockedEntities  = new LinkedHashMap<>();
        public java.util.List<String> autoFlags     = new java.util.ArrayList<>();
        public String  verticalExpansion             = "none";
        public int     verticalExpandDown            = 0;
        public int     verticalExpandUp              = 0;
    }
}
