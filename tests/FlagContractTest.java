// Per-flag contract test: asserts every registered flag's TYPE, DEFAULT, permission node and
// value-hint match the mod's intended design, and that name<->lookup round-trips. This pins the
// behaviour of each flag individually so a future change that flips a default or drops a flag is
// caught immediately. Pure-JVM.
//
// Run: see tests/run.sh.

import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.RegionGroup;

import java.util.Map;
import java.util.Set;

public final class FlagContractTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    // The ONLY state flags that default to deny-when-unset (false). Everything else defaults allow.
    static final Set<String> DEFAULT_DENY = Set.of("invincible", "keep-inventory", "keep-xp");

    // The build-type flags (membership "private by default" applies to these via testBuildAccess).
    static final Set<String> BUILD_FLAGS = Set.of(
            "build", "block-break", "block-place", "interact", "use", "chest-access");

    // Expected Java type per flag name.
    static final Map<String, Class<?>> TYPES = Map.ofEntries(
            Map.entry("greeting", StringFlag.class), Map.entry("farewell", StringFlag.class),
            Map.entry("greeting-title", StringFlag.class), Map.entry("farewell-title", StringFlag.class),
            Map.entry("deny-message", StringFlag.class), Map.entry("entry-deny-message", StringFlag.class),
            Map.entry("exit-deny-message", StringFlag.class), Map.entry("game-mode", StringFlag.class),
            Map.entry("time-lock", StringFlag.class), Map.entry("weather-lock", StringFlag.class),
            Map.entry("teleport", StringFlag.class), Map.entry("spawn", StringFlag.class),
            Map.entry("heal-delay", IntegerFlag.class), Map.entry("heal-amount", IntegerFlag.class),
            Map.entry("heal-max-hp", IntegerFlag.class), Map.entry("heal-min-hp", IntegerFlag.class),
            Map.entry("feed-delay", IntegerFlag.class), Map.entry("feed-amount", IntegerFlag.class),
            Map.entry("feed-max-hunger", IntegerFlag.class), Map.entry("feed-min-hunger", IntegerFlag.class),
            Map.entry("max-speed", DoubleFlag.class),
            Map.entry("notify-enter", BooleanFlag.class), Map.entry("notify-leave", BooleanFlag.class),
            Map.entry("blocked-cmds", SetFlag.class), Map.entry("allowed-cmds", SetFlag.class),
            Map.entry("deny-spawn", SetFlag.class),
            Map.entry("blocked-effects", SetFlag.class),
            Map.entry("on-entry", StringFlag.class), Map.entry("on-exit", StringFlag.class));

    public static void main(String[] args) {
        Flags.bootstrap();

        int stateCount = 0, buildSeen = 0;
        for (Flag<?> f : Flags.all()) {
            String name = f.name();

            // 1. permission node is exactly worldguardneo.flag.<name>
            check("perm '" + name + "'", f.permission().equals("worldguardneo.flag." + name));
            // 2. description key
            check("desc-key '" + name + "'", f.descriptionKey().equals("flag." + name + ".desc"));
            // 3. value hint non-empty
            check("hint '" + name + "'", f.valueHint() != null && !f.valueHint().isEmpty());
            // 4. registry round-trip
            check("lookup '" + name + "'", Flags.get(name) == f);
            check("isRegistered '" + name + "'", Flags.isRegistered(name));

            // 5. explicit type where specified
            Class<?> expected = TYPES.get(name);
            if (expected != null) {
                check("type '" + name + "' == " + expected.getSimpleName(), expected.isInstance(f));
            }

            // 6. state-flag default contract
            if (f instanceof StateFlag sf) {
                stateCount++;
                boolean expectDeny = DEFAULT_DENY.contains(name);
                check("default '" + name + "' = " + (expectDeny ? "deny" : "allow"),
                        sf.defaultAllow() == !expectDeny);
            } else {
                // non-state flags must NOT be in the build set or default-deny set
                check("non-state '" + name + "' not build-flag", !BUILD_FLAGS.contains(name));
            }
            if (BUILD_FLAGS.contains(name)) {
                buildSeen++;
                check("build flag '" + name + "' is StateFlag default allow",
                        f instanceof StateFlag sf2 && sf2.defaultAllow());
            }
        }

        check("all 6 build flags present", buildSeen == 6);
        check("at least 50 state flags", stateCount >= 50);
        check("95 flags registered total", Flags.all().size() == 95);

        // 7. RegionGroup.parse is total and case/locale-insensitive
        check("group parse upper", RegionGroup.parse("OWNERS") == RegionGroup.OWNERS);
        check("group parse lower", RegionGroup.parse("non_members") == RegionGroup.NON_MEMBERS);
        check("group parse dash", RegionGroup.parse("non-owners") == RegionGroup.NON_OWNERS);
        check("group parse junk → ALL", RegionGroup.parse("nonsense") == RegionGroup.ALL);
        check("group parse null → ALL", RegionGroup.parse(null) == RegionGroup.ALL);

        // 8. StateFlag.test() semantics: null → default; ALLOW → true; DENY → false
        check("test null=default(true)", Flags.PVP.test(null));
        check("test null=default(false) invincible", !Flags.INVINCIBLE.test(null));
        check("test ALLOW", Flags.PVP.test(StateFlag.State.ALLOW));
        check("test DENY", !Flags.PVP.test(StateFlag.State.DENY));

        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }
}
