// FlagSerializationTest — toJson/fromJson for every flag type: round-trip, null handling, and
// tolerance of junk JSON (returns null instead of throwing). Pure JVM.
//
// Run via tests/run.sh.

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import dev.thefather007.worldguardneo.flags.*;

import java.util.LinkedHashSet;
import java.util.Set;

public final class FlagSerializationTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }
    static JsonElement json(String s) { return JsonParser.parseString(s); }

    public static void main(String[] args) {
        Flags.bootstrap();
        state();
        bool();
        integer();
        dbl();
        string();
        set();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static void state() {
        StateFlag f = Flags.PVP;
        check("state toJson ALLOW", f.toJson(StateFlag.State.ALLOW).getAsString().equals("ALLOW"));
        check("state toJson DENY", f.toJson(StateFlag.State.DENY).getAsString().equals("DENY"));
        check("state toJson null", f.toJson(null) == null);
        check("state round-trip ALLOW", f.fromJson(f.toJson(StateFlag.State.ALLOW)) == StateFlag.State.ALLOW);
        check("state round-trip DENY", f.fromJson(f.toJson(StateFlag.State.DENY)) == StateFlag.State.DENY);
        check("state fromJson null elem", f.fromJson(JsonNull.INSTANCE) == null);
        check("state fromJson lowercase", f.fromJson(json("\"allow\"")) == StateFlag.State.ALLOW);
        check("state fromJson none", f.fromJson(json("\"none\"")) == null);
        check("state fromJson junk → null (tolerant)", f.fromJson(json("\"garbage\"")) == null);
        check("state fromJson empty → null", f.fromJson(json("\"\"")) == null);
    }

    static void bool() {
        BooleanFlag f = Flags.NOTIFY_ENTER;
        check("bool toJson true", f.toJson(Boolean.TRUE).getAsBoolean());
        check("bool toJson null", f.toJson(null) == null);
        check("bool round-trip true", Boolean.TRUE.equals(f.fromJson(f.toJson(Boolean.TRUE))));
        check("bool round-trip false", Boolean.FALSE.equals(f.fromJson(f.toJson(Boolean.FALSE))));
        check("bool fromJson null elem", f.fromJson(JsonNull.INSTANCE) == null);
    }

    static void integer() {
        IntegerFlag f = Flags.HEAL_AMOUNT;
        check("int toJson", f.toJson(42).getAsInt() == 42);
        check("int toJson null", f.toJson(null) == null);
        check("int round-trip", Integer.valueOf(-7).equals(f.fromJson(f.toJson(-7))));
        check("int fromJson string → null (tolerant)", f.fromJson(json("\"abc\"")) == null);
        check("int fromJson null elem", f.fromJson(JsonNull.INSTANCE) == null);
    }

    static void dbl() {
        DoubleFlag f = Flags.MAX_SPEED;
        check("double toJson", f.toJson(0.5).getAsDouble() == 0.5);
        check("double toJson null", f.toJson(null) == null);
        check("double round-trip", Double.valueOf(3.14).equals(f.fromJson(f.toJson(3.14))));
        check("double fromJson string → null (tolerant)", f.fromJson(json("\"fast\"")) == null);
    }

    static void string() {
        StringFlag f = Flags.GREETING;
        check("string toJson", f.toJson("hello %player%").getAsString().equals("hello %player%"));
        check("string toJson null", f.toJson(null) == null);
        check("string round-trip with special chars", "a\"b\\c\nd".equals(f.fromJson(f.toJson("a\"b\\c\nd"))));
        check("string fromJson null elem", f.fromJson(JsonNull.INSTANCE) == null);
    }

    static void set() {
        SetFlag f = Flags.BLOCKED_CMDS;
        Set<String> s = new LinkedHashSet<>();
        s.add("tpa"); s.add("home");
        check("set toJson array size", f.toJson(s).getAsJsonArray().size() == 2);
        check("set toJson null", f.toJson(null) == null);
        Set<String> back = f.fromJson(f.toJson(s));
        check("set round-trip", back != null && back.size() == 2 && back.containsAll(s));
        check("set fromJson non-array → null", f.fromJson(json("\"x\"")) == null);
        check("set fromJson null elem", f.fromJson(JsonNull.INSTANCE) == null);
        // Non-stringifiable elements (objects/arrays) are skipped; numbers get stringified by Gson.
        check("set fromJson skips object elems", setEq(f.fromJson(json("[\"a\", {}, \"b\"]")), "a", "b"));
        check("set fromJson stringifies numbers", setEq(f.fromJson(json("[\"a\", 5]")), "a", "5"));
    }

    static boolean setEq(Set<String> s, String... items) {
        return s != null && s.size() == items.length && s.containsAll(Set.of(items));
    }
}
