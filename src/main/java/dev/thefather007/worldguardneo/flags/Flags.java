package dev.thefather007.worldguardneo.flags;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Central registry of all known flags. Built-in flags are registered at boot;
 * other mods can add new flags by calling {@link #register(Flag)} during their setup.
 */
public final class Flags {

    /* ---------- Built-in state flags (allow / deny / unset). ---------- */
    public static final StateFlag BUILD             = new StateFlag("build",             true);
    public static final StateFlag BLOCK_BREAK       = new StateFlag("block-break",       true);
    public static final StateFlag BLOCK_PLACE       = new StateFlag("block-place",       true);
    public static final StateFlag INTERACT          = new StateFlag("interact",          true);
    public static final StateFlag USE               = new StateFlag("use",               true);
    public static final StateFlag CHEST_ACCESS      = new StateFlag("chest-access",      true);
    public static final StateFlag PVP               = new StateFlag("pvp",               true);
    public static final StateFlag SLEEP             = new StateFlag("sleep",             true);
    public static final StateFlag TNT               = new StateFlag("tnt",               true);
    public static final StateFlag CREEPER_EXPLOSION = new StateFlag("creeper-explosion", true);
    public static final StateFlag GHAST_FIREBALL    = new StateFlag("ghast-fireball",    true);
    public static final StateFlag ENDERDRAGON       = new StateFlag("enderdragon",       true);
    public static final StateFlag OTHER_EXPLOSION   = new StateFlag("other-explosion",   true);
    public static final StateFlag FIRE_SPREAD       = new StateFlag("fire-spread",       true);
    public static final StateFlag LAVA_FIRE         = new StateFlag("lava-fire",         true);
    public static final StateFlag LIGHTNING         = new StateFlag("lightning",         true);
    public static final StateFlag MOB_SPAWNING      = new StateFlag("mob-spawning",      true);
    public static final StateFlag MOB_DAMAGE        = new StateFlag("mob-damage",        true);
    public static final StateFlag PLAYER_DAMAGE     = new StateFlag("player-damage",     true);
    public static final StateFlag FALL_DAMAGE       = new StateFlag("fall-damage",       true);
    public static final StateFlag FIRE_DAMAGE       = new StateFlag("fire-damage",       true);
    public static final StateFlag DROWN_DAMAGE      = new StateFlag("drown-damage",      true);
    public static final StateFlag SUFFOCATION_DAMAGE= new StateFlag("suffocation-damage",true);
    public static final StateFlag VEHICLE_DESTROY   = new StateFlag("vehicle-destroy",   true);
    public static final StateFlag VEHICLE_PLACE     = new StateFlag("vehicle-place",     true);
    public static final StateFlag VEHICLE_ENTER     = new StateFlag("vehicle-enter",     true);
    public static final StateFlag ITEM_FRAME_ROTATE = new StateFlag("item-frame-rotate", true);
    public static final StateFlag SIGN_EDIT         = new StateFlag("sign-edit",         true);
    public static final StateFlag LECTERN_TAKE      = new StateFlag("lectern-take",      true);
    public static final StateFlag ARMOR_STAND_USE   = new StateFlag("armor-stand-use",   true);
    public static final StateFlag GLIDE             = new StateFlag("glide",             true);
    public static final StateFlag BUCKET_FILL       = new StateFlag("bucket-fill",       true);
    public static final StateFlag BUCKET_EMPTY      = new StateFlag("bucket-empty",      true);
    public static final StateFlag ITEM_PICKUP       = new StateFlag("item-pickup",       true);
    public static final StateFlag ITEM_DROP         = new StateFlag("item-drop",         true);
    public static final StateFlag ENDER_BUILD       = new StateFlag("enderpearl",        true);
    public static final StateFlag CHORUS_FRUIT      = new StateFlag("chorus-teleport",   true);
    public static final StateFlag ENTRY             = new StateFlag("entry",             true);
    public static final StateFlag EXIT              = new StateFlag("exit",              true);
    public static final StateFlag ENTRY_VEHICLE     = new StateFlag("entry-vehicle",     true);
    public static final StateFlag EXIT_VEHICLE      = new StateFlag("exit-vehicle",      true);
    public static final StateFlag PISTONS           = new StateFlag("pistons",           true);
    public static final StateFlag REDSTONE          = new StateFlag("redstone",          true);
    public static final StateFlag DISPENSER_OUTPUT  = new StateFlag("dispenser-output",  true);
    public static final StateFlag LEAF_DECAY        = new StateFlag("leaf-decay",        true);
    public static final StateFlag GRASS_SPREAD      = new StateFlag("grass-spread",      true);
    public static final StateFlag MYCELIUM_SPREAD   = new StateFlag("mycelium-spread",   true);
    public static final StateFlag VINE_GROWTH       = new StateFlag("vine-growth",       true);
    public static final StateFlag CROP_GROWTH       = new StateFlag("crop-growth",       true);
    public static final StateFlag ICE_FORM          = new StateFlag("ice-form",          true);
    public static final StateFlag ICE_MELT          = new StateFlag("ice-melt",          true);
    public static final StateFlag SNOW_FALL         = new StateFlag("snow-fall",         true);
    public static final StateFlag SNOW_MELT         = new StateFlag("snow-melt",         true);
    public static final StateFlag FROSTED_ICE_MELT  = new StateFlag("frosted-ice-melt",  true);
    public static final StateFlag WATER_FLOW        = new StateFlag("water-flow",        true);
    public static final StateFlag LAVA_FLOW         = new StateFlag("lava-flow",         true);
    public static final StateFlag SEND_CHAT         = new StateFlag("send-chat",         true);
    public static final StateFlag RECEIVE_CHAT      = new StateFlag("receive-chat",      true);
    public static final StateFlag EXP_DROPS         = new StateFlag("exp-drops",         true);
    public static final StateFlag INVINCIBLE        = new StateFlag("invincible",        false);
    public static final StateFlag HUNGER_DRAIN      = new StateFlag("hunger-drain",      true);

    /* ---------- Built-in value flags. ---------- */
    public static final StringFlag GREETING          = new StringFlag("greeting");
    public static final StringFlag FAREWELL          = new StringFlag("farewell");
    public static final StringFlag GREETING_TITLE    = new StringFlag("greeting-title");
    public static final StringFlag FAREWELL_TITLE    = new StringFlag("farewell-title");
    public static final StringFlag DENY_MESSAGE      = new StringFlag("deny-message");
    public static final StringFlag ENTRY_DENY_MESSAGE= new StringFlag("entry-deny-message");
    public static final StringFlag EXIT_DENY_MESSAGE = new StringFlag("exit-deny-message");

    public static final IntegerFlag HEAL_DELAY        = new IntegerFlag("heal-delay");
    public static final IntegerFlag HEAL_AMOUNT       = new IntegerFlag("heal-amount");
    public static final IntegerFlag MAX_HEAL          = new IntegerFlag("heal-max-hp");
    public static final IntegerFlag MIN_HEAL          = new IntegerFlag("heal-min-hp");
    public static final IntegerFlag FEED_DELAY        = new IntegerFlag("feed-delay");
    public static final IntegerFlag FEED_AMOUNT       = new IntegerFlag("feed-amount");
    public static final IntegerFlag FEED_MAX          = new IntegerFlag("feed-max-hunger");
    public static final IntegerFlag FEED_MIN          = new IntegerFlag("feed-min-hunger");
    public static final DoubleFlag  MAX_SPEED         = new DoubleFlag("max-speed");
    public static final BooleanFlag NOTIFY_ENTER      = new BooleanFlag("notify-enter");
    public static final BooleanFlag NOTIFY_LEAVE      = new BooleanFlag("notify-leave");

    public static final SetFlag    BLOCKED_CMDS      = new SetFlag("blocked-cmds");
    public static final SetFlag    ALLOWED_CMDS      = new SetFlag("allowed-cmds");
    public static final SetFlag    DENY_SPAWN        = new SetFlag("deny-spawn");
    // Per-type spawn caps: each entry is "<entity-id>:<max>" (e.g. "minecraft:zombie:5"). When a
    // region is at/over the cap for that type within its bounds, further spawns of it are cancelled.
    public static final SetFlag    SPAWN_LIMIT       = new SetFlag("spawn-limit");
    public static final SetFlag    BLOCKED_EFFECTS   = new SetFlag("blocked-effects");

    /**
     * Command run (from the server console, elevated) when a player ENTERS / LEAVES the region.
     * Placeholders: {@code %player%}, {@code %region%}, {@code %world%}. A leading '/' is optional.
     * Settable only via the per-flag node (admins), since the command runs with console authority.
     */
    public static final StringFlag ON_ENTRY          = new StringFlag("on-entry");
    public static final StringFlag ON_EXIT           = new StringFlag("on-exit");

    public static final StringFlag GAME_MODE         = new StringFlag("game-mode");
    public static final StringFlag TIME_LOCK         = new StringFlag("time-lock");
    public static final StringFlag WEATHER_LOCK      = new StringFlag("weather-lock");
    public static final StringFlag TELE_LOC          = new StringFlag("teleport");
    public static final StringFlag SPAWN_LOC         = new StringFlag("spawn");

    /** Region-scoped keep-inventory: ALLOW = player keeps items on death inside region. */
    public static final StateFlag  KEEP_INVENTORY    = new StateFlag("keep-inventory",  false);
    /** Region-scoped keep-xp: ALLOW = player keeps XP on death inside region. */
    public static final StateFlag  KEEP_XP           = new StateFlag("keep-xp",         false);
    /** Allows mob interactions (Endermen teleport, villager trade) gated per-region. */
    public static final StateFlag  MOB_TELEPORT      = new StateFlag("mob-teleport",    true);
    /** DENY = mobs can't change blocks here (enderman pick/place, sheep eat grass, etc.). */
    public static final StateFlag  MOB_GRIEF         = new StateFlag("mob-grief",       true);
    /** DENY = players can't open a villager / wandering-trader trade GUI here. */
    public static final StateFlag  VILLAGER_TRADE    = new StateFlag("villager-trade",  true);
    /** DENY = players can't mount rideable mobs (horse, pig, strider, …) here. Minecarts/boats use vehicle-enter. */
    public static final StateFlag  RIDE              = new StateFlag("ride",            true);
    /** DENY = players can't attach a lead to a mob here. */
    public static final StateFlag  ENTITY_LEASH      = new StateFlag("entity-leash",    true);

    private static final Map<String, Flag<?>> REGISTRY = new LinkedHashMap<>();

    private Flags() {}

    public static void bootstrap() {
        // Idempotency check on a CORE built-in (BUILD is always registered first if bootstrap
        // has run). Originally we checked REGISTRY.isEmpty(), but that could be subverted if a
        // 3rd-party mod registered a flag before our bootstrap fired — leading to silently
        // dropping ALL built-in flags. Using a specific known flag is safer.
        if (REGISTRY.containsKey(BUILD.name())) return;
        // Reflection-free: enumerate via known fields.
        register(BUILD); register(BLOCK_BREAK); register(BLOCK_PLACE); register(INTERACT);
        register(USE); register(CHEST_ACCESS); register(PVP); register(SLEEP); register(TNT);
        register(CREEPER_EXPLOSION); register(GHAST_FIREBALL); register(ENDERDRAGON);
        register(OTHER_EXPLOSION); register(FIRE_SPREAD); register(LAVA_FIRE); register(LIGHTNING);
        register(MOB_SPAWNING); register(MOB_DAMAGE); register(PLAYER_DAMAGE);
        register(FALL_DAMAGE); register(FIRE_DAMAGE); register(DROWN_DAMAGE); register(SUFFOCATION_DAMAGE);
        register(VEHICLE_DESTROY); register(VEHICLE_PLACE); register(VEHICLE_ENTER);
        register(ITEM_FRAME_ROTATE); register(SIGN_EDIT); register(LECTERN_TAKE);
        register(ARMOR_STAND_USE); register(GLIDE); register(BUCKET_FILL); register(BUCKET_EMPTY);
        register(ITEM_PICKUP); register(ITEM_DROP);
        register(ENDER_BUILD); register(CHORUS_FRUIT); register(ENTRY); register(EXIT);
        register(ENTRY_VEHICLE); register(EXIT_VEHICLE); register(PISTONS);
        register(REDSTONE); register(DISPENSER_OUTPUT);
        register(LEAF_DECAY); register(GRASS_SPREAD); register(MYCELIUM_SPREAD); register(VINE_GROWTH);
        register(CROP_GROWTH); register(ICE_FORM); register(ICE_MELT); register(SNOW_FALL);
        register(SNOW_MELT); register(FROSTED_ICE_MELT); register(WATER_FLOW); register(LAVA_FLOW);
        register(SEND_CHAT); register(RECEIVE_CHAT); register(EXP_DROPS); register(INVINCIBLE);
        register(HUNGER_DRAIN);

        register(GREETING); register(FAREWELL); register(GREETING_TITLE); register(FAREWELL_TITLE);
        register(DENY_MESSAGE); register(ENTRY_DENY_MESSAGE); register(EXIT_DENY_MESSAGE);

        register(HEAL_DELAY); register(HEAL_AMOUNT); register(MAX_HEAL); register(MIN_HEAL);
        register(FEED_DELAY); register(FEED_AMOUNT); register(FEED_MAX); register(FEED_MIN);
        register(MAX_SPEED); register(NOTIFY_ENTER); register(NOTIFY_LEAVE);

        register(BLOCKED_CMDS); register(ALLOWED_CMDS); register(DENY_SPAWN); register(SPAWN_LIMIT);
        register(BLOCKED_EFFECTS); register(ON_ENTRY); register(ON_EXIT);

        register(GAME_MODE); register(TIME_LOCK); register(WEATHER_LOCK);
        register(TELE_LOC); register(SPAWN_LOC);
        register(KEEP_INVENTORY); register(KEEP_XP); register(MOB_TELEPORT); register(MOB_GRIEF);
        register(VILLAGER_TRADE); register(RIDE); register(ENTITY_LEASH);
    }

    public static <F extends Flag<?>> F register(F flag) {
        Objects.requireNonNull(flag, "flag");
        if (REGISTRY.containsKey(flag.name()))
            throw new IllegalStateException("Flag already registered: " + flag.name());
        REGISTRY.put(flag.name(), flag);
        return flag;
    }

    public static Flag<?> get(String name)                  { return REGISTRY.get(name); }
    public static Collection<Flag<?>> all()                 { return REGISTRY.values(); }
    public static boolean isRegistered(String name)         { return REGISTRY.containsKey(name); }
}
