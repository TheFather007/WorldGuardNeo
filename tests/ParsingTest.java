// ParsingTest — every flag type's parse()/fromJson() + RegionGroup.parse + Flag name validation
// + parseAndApply. Pure JVM.
//
// Run via tests/run.sh.

import com.google.gson.JsonParser;
import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.Set;

public final class ParsingTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static Object parse(Flag<?> f, String in) { try { return f.parse(in); } catch (Flag.FlagParseException e) { return "THREW"; } }
    static void ok(String n, Flag<?> f, String in, Object exp) { check(n, java.util.Objects.equals(parse(f, in), exp)); }
    static void isNull(String n, Flag<?> f, String in) { check(n, parse(f, in) == null); }
    static void throwsP(String n, Flag<?> f, String in) { check(n, "THREW".equals(parse(f, in))); }

    public static void main(String[] args) {
        Flags.bootstrap();
        state();
        bool();
        integer();
        dbl();
        string();
        set();
        group();
        nameValidation();
        parseAndApply();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static void state() {
        StateFlag f = Flags.PVP;
        ok("state allow", f, "allow", StateFlag.State.ALLOW);
        ok("state yes", f, "yes", StateFlag.State.ALLOW);
        ok("state true", f, "true", StateFlag.State.ALLOW);
        ok("state deny", f, "deny", StateFlag.State.DENY);
        ok("state no", f, "no", StateFlag.State.DENY);
        ok("state false", f, "false", StateFlag.State.DENY);
        ok("state case-insensitive", f, "ALLOW", StateFlag.State.ALLOW);
        ok("state trims", f, "  deny  ", StateFlag.State.DENY);
        isNull("state none", f, "none");
        isNull("state unset", f, "unset");
        isNull("state empty", f, "");
        isNull("state null", f, null);
        isNull("state whitespace", f, "   ");
        throwsP("state invalid", f, "maybe");
        check("state.test ALLOW", f.test(StateFlag.State.ALLOW));
        check("state.test DENY", !f.test(StateFlag.State.DENY));
        check("state.test null→default(pvp allow)", f.test(null));
        check("state.test null→default(invincible deny)", !Flags.INVINCIBLE.test(null));
    }

    static void bool() {
        BooleanFlag f = Flags.NOTIFY_ENTER;
        ok("bool true", f, "true", Boolean.TRUE);
        ok("bool yes", f, "yes", Boolean.TRUE);
        ok("bool on", f, "on", Boolean.TRUE);
        ok("bool 1", f, "1", Boolean.TRUE);
        ok("bool false", f, "false", Boolean.FALSE);
        ok("bool off", f, "off", Boolean.FALSE);
        ok("bool 0", f, "0", Boolean.FALSE);
        ok("bool case", f, "TRUE", Boolean.TRUE);
        isNull("bool empty", f, "");
        isNull("bool whitespace", f, "  ");
        throwsP("bool invalid", f, "maybe");
    }

    static void integer() {
        IntegerFlag f = Flags.HEAL_AMOUNT;
        ok("int 5", f, "5", 5);
        ok("int -3", f, "-3", -3);
        ok("int trims", f, "  7  ", 7);
        ok("int 0", f, "0", 0);
        isNull("int empty", f, "");
        isNull("int null", f, null);
        throwsP("int non-numeric", f, "abc");
        throwsP("int decimal", f, "1.5");
        // fromJson tolerance
        check("int fromJson number", Integer.valueOf(42).equals(f.fromJson(JsonParser.parseString("42"))));
        check("int fromJson null", f.fromJson(JsonParser.parseString("null")) == null);
    }

    static void dbl() {
        DoubleFlag f = Flags.MAX_SPEED;
        ok("double 0.5", f, "0.5", 0.5);
        ok("double -2", f, "-2", -2.0);
        ok("double int-form", f, "3", 3.0);
        ok("double trims", f, " 1.25 ", 1.25);
        isNull("double empty", f, "");
        throwsP("double invalid", f, "fast");
        check("double fromJson", Double.valueOf(2.5).equals(f.fromJson(JsonParser.parseString("2.5"))));
    }

    static void string() {
        StringFlag f = Flags.GREETING;
        ok("string passthrough", f, "Welcome!", "Welcome!");
        ok("string keeps spaces", f, "  hi there  ", "  hi there  ");
        isNull("string empty", f, "");
        isNull("string null", f, null);
        check("string fromJson", "x".equals(f.fromJson(JsonParser.parseString("\"x\""))));
    }

    static void set() {
        SetFlag f = Flags.BLOCKED_CMDS;
        check("set comma", setEq(parse(f, "a,b,c"), "a", "b", "c"));
        check("set semicolon", setEq(parse(f, "a;b;c"), "a", "b", "c"));
        check("set space", setEq(parse(f, "a b c"), "a", "b", "c"));
        check("set mixed+spaces", setEq(parse(f, "a, b ;c"), "a", "b", "c"));
        check("set dedup", setEq(parse(f, "a,a,b,b"), "a", "b"));
        check("set single", setEq(parse(f, "solo"), "solo"));
        isNull("set empty", f, "");
        isNull("set whitespace only", f, "   ");
        // fromJson
        check("set fromJson array", setEq(f.fromJson(JsonParser.parseString("[\"x\",\"y\"]")), "x", "y"));
        check("set fromJson non-array → null", f.fromJson(JsonParser.parseString("\"x\"")) == null);
        check("set fromJson null", f.fromJson(JsonParser.parseString("null")) == null);
    }

    @SuppressWarnings("unchecked")
    static boolean setEq(Object o, String... items) {
        if (!(o instanceof Set)) return false;
        Set<String> s = (Set<String>) o;
        return s.size() == items.length && s.containsAll(Set.of(items));
    }

    static void group() {
        check("group ALL", RegionGroup.parse("ALL") == RegionGroup.ALL);
        check("group lowercase", RegionGroup.parse("owners") == RegionGroup.OWNERS);
        check("group members", RegionGroup.parse("members") == RegionGroup.MEMBERS);
        check("group dash→underscore", RegionGroup.parse("non-owners") == RegionGroup.NON_OWNERS);
        check("group non_members", RegionGroup.parse("non_members") == RegionGroup.NON_MEMBERS);
        check("group mixed case+dash", RegionGroup.parse("Non-Members") == RegionGroup.NON_MEMBERS);
        check("group empty → ALL", RegionGroup.parse("") == RegionGroup.ALL);
        check("group null → ALL", RegionGroup.parse(null) == RegionGroup.ALL);
        check("group garbage → ALL", RegionGroup.parse("nonsense") == RegionGroup.ALL);
    }

    static void nameValidation() {
        check("valid name", construct("good-flag-9"));
        check("reject space", !construct("bad name"));
        check("reject empty", !construct(""));
        check("reject leading digit", !construct("9bad"));
        check("reject uppercase", !construct("Bad"));
        check("reject dot", !construct("a.b"));
        check("reject underscore", !construct("a_b"));
    }
    static boolean construct(String name) {
        try { new StateFlag(name, true); return true; } catch (IllegalArgumentException e) { return false; }
    }

    static void parseAndApply() {
        CuboidRegion r = new CuboidRegion("r", new Vec3(0, 0, 0), new Vec3(5, 5, 5));
        try {
            Flags.PVP.parseAndApply(r, "deny", RegionGroup.NON_MEMBERS);
            check("apply sets value", r.getFlag(Flags.PVP) == StateFlag.State.DENY);
            check("apply sets group", r.getFlagGroup(Flags.PVP) == RegionGroup.NON_MEMBERS);
            // empty input unsets.
            Flags.PVP.parseAndApply(r, "", null);
            check("apply empty unsets", r.getFlag(Flags.PVP) == null);
            // group is only stored when there's a value.
            Flags.PVP.parseAndApply(r, null, RegionGroup.OWNERS);
            check("apply null value no group leak", r.getFlagGroup(Flags.PVP) == RegionGroup.ALL);
        } catch (Flag.FlagParseException e) {
            check("parseAndApply unexpected throw", false);
        }
    }
}
