package dev.dizzy.worldguardneo;

import com.mojang.logging.LogUtils;
import dev.dizzy.worldguardneo.commands.WGCommands;
import dev.dizzy.worldguardneo.config.WGConfig;
import dev.dizzy.worldguardneo.flags.Flags;
import dev.dizzy.worldguardneo.lang.Localization;
import dev.dizzy.worldguardneo.listeners.BlockEventHandler;
import dev.dizzy.worldguardneo.listeners.EntityEventHandler;
import dev.dizzy.worldguardneo.listeners.PlayerEventHandler;
import dev.dizzy.worldguardneo.listeners.WandCommandHandler;
import dev.dizzy.worldguardneo.listeners.WorldEventHandler;
import dev.dizzy.worldguardneo.permissions.PermissionService;
import dev.dizzy.worldguardneo.region.RegionContainer;
import dev.dizzy.worldguardneo.storage.JsonRegionStorage;
import dev.dizzy.worldguardneo.storage.RegionStorage;
import dev.dizzy.worldguardneo.storage.SqliteRegionStorage;
import dev.dizzy.worldguardneo.storage.H2RegionStorage;
import dev.dizzy.worldguardneo.storage.MySqlRegionStorage;
import dev.dizzy.worldguardneo.worldedit.WorldEditAdapter;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * WorldGuardNeo — region protection system for NeoForge 1.21.1.
 * Author: Dizzy. Version 1.0.
 */
@Mod(WorldGuardNeo.MOD_ID)
public final class WorldGuardNeo {

    public static final String MOD_ID = "worldguardneo";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static WorldGuardNeo INSTANCE;

    private final WGConfig config;
    private final Localization localization;
    private final PermissionService permissions;
    private final RegionContainer regionContainer;
    private final WorldEditAdapter worldEditAdapter;
    private final dev.dizzy.worldguardneo.backup.BackupManager backupManager;
    private final dev.dizzy.worldguardneo.util.ViolationLog violationLog;
    private WorldEventHandler worldEvents;

    public WorldGuardNeo(IEventBus modBus, ModContainer container) {
        INSTANCE = this;
        LOGGER.info("[WorldGuardNeo] Bootstrapping v1.0 for NeoForge 1.21.1");

        // Pre-register all built-in flags FIRST so that storage and config can resolve them
        // by name without races. None of the subsequent constructors take a flag identity,
        // but moving this up keeps the invariant cheap to verify.
        Flags.bootstrap();

        Path configDir = FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
        // Regions now live under config/worldguardneo/regions (was gameDir/worldguardneo/regions)
        // so all of the mod's persistent data sits in one place next to config.toml.
        Path dataDir   = configDir;

        this.config           = WGConfig.loadOrCreate(configDir);
        this.localization     = Localization.load(configDir, this.config.locale());
        this.permissions      = PermissionService.detect(this.config.global().useLuckPerms);
        RegionStorage storage = createStorage(this.config.global(), dataDir);
        this.regionContainer  = new RegionContainer(storage);
        this.worldEditAdapter = WorldEditAdapter.detect();
        // Backups also go under config/worldguardneo (passing dataDir, the mod's data root).
        this.backupManager    = new dev.dizzy.worldguardneo.backup.BackupManager(dataDir);
        // Violations go to logs/worldguardneo-violations.log, separate from the main console,
        // so routine "player tried to grief a claim" events don't bury real errors.
        this.violationLog     = new dev.dizzy.worldguardneo.util.ViolationLog(
                FMLPaths.GAMEDIR.get().resolve("logs"));

        // Lifecycle.
        modBus.addListener(this::onCommonSetup);

        // Game events.
        IEventBus forge = NeoForge.EVENT_BUS;
        forge.addListener(this::onRegisterCommands);
        forge.addListener(this::onServerStarting);
        forge.addListener(this::onServerStarted);
        forge.addListener(this::onServerStopping);
        forge.addListener(this::onServerTick);
        forge.addListener(this::onLevelUnload);

        forge.register(new BlockEventHandler(this));
        forge.register(new EntityEventHandler(this));
        this.worldEvents = new WorldEventHandler(this);
        forge.register(this.worldEvents);
        forge.register(new PlayerEventHandler(this));
        forge.register(new WandCommandHandler(this));

        // Report only the optional integrations that are actually present, instead of printing
        // a "detected: false" line for every soft-dep. Cleaner console, and it makes it obvious
        // at a glance which integrations are live. Order: mod-id soft-deps first, then the
        // sqlite-jdbc library (detected by classpath, not ModList, since it's a library mod).
        String[][] softDeps = {
                {"luckperms", "LuckPerms"},
                {"worldedit", "WorldEdit"},
                {"bluemap",   "BlueMap"},
                {"squaremap", "squaremap"},
                {"sqlite_jdbc", "SQLite JDBC"},
        };
        java.util.List<String> present = new java.util.ArrayList<>();
        for (String[] dep : softDeps) {
            if (ModList.get().isLoaded(dep[0])) present.add(dep[1]);
        }
        // sqlite-jdbc may be present as a plain library (no mod id) — detect the JDBC driver
        // class too, so it's reported even when ModList doesn't list it.
        if (!present.contains("SQLite JDBC")) {
            try {
                Class.forName("org.sqlite.JDBC");
                present.add("SQLite JDBC");
            } catch (ClassNotFoundException ignored) { /* not on classpath */ }
        }
        if (present.isEmpty()) {
            LOGGER.info("[WorldGuardNeo] No optional integrations detected.");
        } else {
            LOGGER.info("[WorldGuardNeo] Detected integrations: {}", String.join(", ", present));
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> LOGGER.info("[WorldGuardNeo] Common setup complete."));
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        WGCommands.register(event.getDispatcher(), this);
    }

    private void onServerStarting(ServerStartingEvent event) {
        regionContainer.loadAllForServer(event.getServer());
        int totalRegions = 0;
        for (var m : regionContainer.allManagers().values()) totalRegions += m.size();
        LOGGER.info("[WorldGuardNeo] Loaded {} regions across {} worlds.",
                totalRegions, regionContainer.size());
        // Soft-dep integrations. Each checks ModList and no-ops if mod isn't present.
        dev.dizzy.worldguardneo.integrations.BluemapIntegration.init();
        dev.dizzy.worldguardneo.integrations.SquaremapIntegration.init();
    }

    private void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("[WorldGuardNeo] Ready.");
        // Initial sync for any active map integrations. Idempotent — runs once at start.
        var bm = dev.dizzy.worldguardneo.integrations.BluemapIntegration.get();
        if (bm != null && bm.isActive()) bm.publishAll();
        var sq = dev.dizzy.worldguardneo.integrations.SquaremapIntegration.get();
        if (sq != null && sq.isActive()) sq.publishAll();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // Each shutdown step independently try-catched so a failure in one doesn't prevent
        // the others (e.g. if saveAll throws, we still want to close the storage handle).
        try { regionContainer.saveAll(); }
        catch (Exception ex) { LOGGER.warn("[WorldGuardNeo] saveAll failed", ex); }
        try { regionContainer.storage().close(); }
        catch (Exception ex) { LOGGER.warn("[WorldGuardNeo] storage close failed", ex); }
        // Flush the backup executor — wait briefly for any in-flight backup to finish.
        try { backupManager.close(); }
        catch (Exception ex) { LOGGER.warn("[WorldGuardNeo] backup manager close failed", ex); }
        try { violationLog.close(); }
        catch (Exception ex) { LOGGER.warn("[WorldGuardNeo] violation log close failed", ex); }
        LOGGER.info("[WorldGuardNeo] All regions saved.");
    }

    /**
     * Server-tick hook: drains pending region-save writes AND schedules async backups.
     * Both operations are cheap when nothing is due — a single counter comparison.
     */
    private void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        long t = currentServerTick();
        regionContainer.flushDirty(t);
        if (config.global().backupEnabled) {
            backupManager.tick(t, config.global().backupIntervalMinutes);
        }
    }

    /**
     * Pick a region-storage backend from {@code storage-format}. Every DB backend degrades to
     * JSON internally if its driver/connection is unavailable, so this never fails hard.
     */
    private static RegionStorage createStorage(WGConfig.GlobalSection g, java.nio.file.Path dataDir) {
        String fmt = g.storageFormat == null ? "json" : g.storageFormat.trim().toLowerCase(java.util.Locale.ROOT);
        switch (fmt) {
            case "sqlite":
                return new SqliteRegionStorage(dataDir);
            case "h2":
                return new H2RegionStorage(dataDir);
            case "mysql":
                return new MySqlRegionStorage(dataDir, g.mysqlHost, g.mysqlPort, g.mysqlDatabase,
                        g.mysqlUser, g.mysqlPassword, g.mysqlUseSsl);
            case "json":
                return new JsonRegionStorage(dataDir);
            default:
                LOGGER.warn("[WorldGuardNeo] Unknown storage-format '{}' — using json.", g.storageFormat);
                return new JsonRegionStorage(dataDir);
        }
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        // Drop the Level→RegionManager identity-map entry so unloaded worlds (e.g. via
        // dynamic-dimension mods) don't pin the Level instance via the identity-hash bucket.
        if (event.getLevel() instanceof net.minecraft.world.level.Level lvl) {
            regionContainer.evict(lvl);
            config.evictLevel(lvl);
        }
    }

    public static WorldGuardNeo get() { return INSTANCE; }
    public WGConfig            config()         { return config; }
    public Localization        i18n()           { return localization; }
    public PermissionService   perms()          { return permissions; }
    public RegionContainer     regions()        { return regionContainer; }
    public WorldEditAdapter    worldEdit()      { return worldEditAdapter; }
    public dev.dizzy.worldguardneo.util.ViolationLog violations() { return violationLog; }
    public WorldEventHandler   worldEvents()    { return worldEvents; }
    public dev.dizzy.worldguardneo.backup.BackupManager backups() { return backupManager; }

    /**
     * Returns the current tick of the running MinecraftServer's overworld, or 0 if no
     * server is currently active. Used by the selection-store TTL.
     */
    public long currentServerTick() {
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null) return 0L;
            var ow = srv.overworld();
            return ow != null ? ow.getGameTime() : 0L;
        } catch (Throwable t) { return 0L; }
    }

    /**
     * World-level kill-switch: returns true if WGN protections are active in this world.
     * If the per-world {@code useRegions} flag is false (or world override sets it to false),
     * all region-protection listeners short-circuit. Useful for creative/admin dimensions.
     */
    public boolean isProtectionActive(net.minecraft.world.level.Level lvl) {
        if (lvl == null || lvl.isClientSide()) return false;
        try {
            var ws = config.worldOrGlobal(lvl);
            return ws == null || ws.useRegions;
        } catch (Throwable t) { return true; } // fail-open: protections stay on
    }

    /**
     * Returns the effective region-count cap for the given player. Combines the global
     * {@code maxRegionsPerPlayer} with any per-LuckPerms-group overrides from
     * {@code groupRegionLimits}: the player gets the maximum across all their groups
     * and the global cap.
     *
     * <p>If LuckPerms isn't installed the global cap applies. If the player isn't loaded
     * by LP yet, we also fall through to global so we don't accidentally deny claims to
     * just-joined players.
     */
    public int effectiveRegionLimit(net.minecraft.server.level.ServerPlayer player) {
        int base = config.global().maxRegionsPerPlayer;
        java.util.Map<String,Integer> overrides = config.global().groupRegionLimits;
        if (overrides == null || overrides.isEmpty()) return base;
        java.util.Collection<String> groups = permissions.allGroups(player);
        if (groups.isEmpty()) return base;
        int max = base;
        // Build a case-insensitive view once — group names from LP are arbitrary case.
        for (String g : groups) {
            for (java.util.Map.Entry<String,Integer> e : overrides.entrySet()) {
                if (e.getKey().equalsIgnoreCase(g) && e.getValue() != null && e.getValue() > max) {
                    max = e.getValue();
                }
            }
        }
        return max;
    }
}
