// Per-flag test battery + structured report writer.
//
// For EVERY registered flag this runs a tailored battery (contract, resolution, build-access,
// groups, priority, parent inheritance, JSON round-trip — and for value flags: parse + resolve +
// round-trip), then writes a detailed, machine-readable report to a file (default
// tests/FLAG_REPORT.txt). Runs on a plain JVM against the real mod classes.
//
// Run: see tests/run.sh (it invokes this and points it at the report path).

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.storage.RegionJsonCodec;
import dev.thefather007.worldguardneo.util.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;

public final class PerFlagReport {

    static final Gson GSON = new Gson();
    static final UUID OWNER    = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID MEMBER   = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000a3");

    static final Set<String> DEFAULT_DENY = Set.of("invincible", "keep-inventory", "keep-xp");
    static final Set<String> BUILD_FLAGS = Set.of(
            "build", "block-break", "block-place", "interact", "use", "chest-access");
    // v1.3: no declared-only flags remain (receive-chat is enforced via mixin; allowed-enchants removed).
    static final Set<String> DECLARED_ONLY = Set.of();

    static int totalPass = 0, totalFail = 0;
    static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws IOException {
        Flags.bootstrap();
        Path out = Path.of(args.length > 0 ? args[0] : "tests/FLAG_REPORT.txt");

        report.append("WorldGuardNeo — per-flag test report\n");
        report.append("generated: ").append(OffsetDateTime.now()).append("\n");
        report.append("flags: ").append(Flags.all().size()).append("\n");
        report.append("=".repeat(78)).append("\n\n");

        List<Flag<?>> flags = new ArrayList<>(Flags.all());
        flags.sort(Comparator.comparing(Flag::name));
        for (Flag<?> f : flags) reportFlag(f);

        // machine-readable summary footer
        report.append("\n").append("=".repeat(78)).append("\n");
        report.append("SUMMARY: ").append(totalPass).append(" passed, ").append(totalFail).append(" failed");
        report.append(" across ").append(flags.size()).append(" flags\n");
        report.append("RESULT: ").append(totalFail == 0 ? "ALL GREEN" : "FAILURES PRESENT").append("\n");

        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, report.toString());

        System.out.println("==== per-flag: " + totalPass + " passed, " + totalFail
                + " failed; report → " + out + " ====");
        if (totalFail > 0) System.exit(1);
    }

    /* ---------- per-flag driver ---------- */

    static void reportFlag(Flag<?> f) {
        String name = f.name();
        String type = f.getClass().getSimpleName();
        boolean enforced = !DECLARED_ONLY.contains(name);
        String dflt = (f instanceof StateFlag sf) ? (sf.defaultAllow() ? "allow" : "deny") : "—";
        report.append("=== FLAG: ").append(name).append(" ===\n");
        report.append("  type=").append(type).append(" | default=").append(dflt)
              .append(" | perm=").append(f.permission())
              .append(" | enforced=").append(enforced ? "yes" : "DECLARED-ONLY")
              .append(" | hint=").append(f.valueHint()).append("\n");

        int before = totalFail;

        // --- contract (all flags) ---
        ck(name, "perm node", f.permission().equals("worldguardneo.flag." + name));
        ck(name, "desc key", f.descriptionKey().equals("flag." + name + ".desc"));
        ck(name, "registry round-trip", Flags.get(name) == f);
        ck(name, "value hint non-empty", f.valueHint() != null && !f.valueHint().isEmpty());

        if (f instanceof StateFlag sf) {
            reportState(name, sf);
        } else {
            reportValue(name, f);
        }

        int failed = totalFail - before;
        report.append("  -> ").append(failed == 0 ? "OK" : (failed + " FAILED")).append("\n\n");
    }

    static void reportState(String name, StateFlag f) {
        boolean expectDeny = DEFAULT_DENY.contains(name);
        ck(name, "default==" + (expectDeny ? "deny" : "allow"), f.defaultAllow() == !expectDeny);

        // wilderness → flag default
        RegionManager wild = new RegionManager("t");
        ck(name, "wilderness=default", wild.testState(f, STRANGER, 500, 5, 500) == f.defaultAllow());

        // explicit DENY applies to everyone
        RegionManager d = mgrWith(name, f, "deny");
        ck(name, "deny→stranger blocked", !d.testState(f, STRANGER, 5, 5, 5));
        ck(name, "deny→owner blocked",    !d.testState(f, OWNER, 5, 5, 5));
        ck(name, "deny→null blocked",     !d.testState(f, null, 5, 5, 5));

        // explicit ALLOW applies to everyone
        RegionManager a = mgrWith(name, f, "allow");
        ck(name, "allow→stranger allowed", a.testState(f, STRANGER, 5, 5, 5));

        // priority: high DENY beats low ALLOW
        RegionManager pr = new RegionManager("t");
        CuboidRegion low = claim("low", 0); set(low, f, "allow"); pr.add(low);
        CuboidRegion high = claim("high", 10); set(high, f, "deny"); pr.add(high);
        ck(name, "priority high-deny wins", !pr.testState(f, STRANGER, 5, 5, 5));

        // parent inheritance
        RegionManager pa = new RegionManager("t");
        CuboidRegion parent = box("parent", 0, 0, 63); set(parent, f, "deny");
        CuboidRegion child = claim("child", 0); child.setParent(parent); pa.add(child);
        ck(name, "parent deny inherited", !pa.testState(f, STRANGER, 5, 5, 5));

        // group filter: deny -g OWNERS → owner denied, stranger falls to default
        RegionManager g = new RegionManager("t");
        CuboidRegion r = claim("g", 0); set(r, f, "deny"); r.setFlagGroup(f, RegionGroup.OWNERS); g.add(r);
        ck(name, "deny -g OWNERS blocks owner", !g.testState(f, OWNER, 5, 5, 5));
        ck(name, "deny -g OWNERS spares stranger(default)", g.testState(f, STRANGER, 5, 5, 5) == f.defaultAllow());

        // build-access membership semantics for the build flags
        if (BUILD_FLAGS.contains(name)) {
            RegionManager m = mgrPlain();
            ck(name, "buildAccess owner", m.testBuildAccess(f, 5, 5, 5, OWNER));
            ck(name, "buildAccess member", m.testBuildAccess(f, 5, 5, 5, MEMBER));
            ck(name, "buildAccess stranger denied", !m.testBuildAccess(f, 5, 5, 5, STRANGER));
            ck(name, "buildAccess null denied", !m.testBuildAccess(f, 5, 5, 5, null));
            RegionManager m2 = mgrWith(name, f, "allow");
            ck(name, "buildAccess explicit-allow opens stranger", m2.testBuildAccess(f, 5, 5, 5, STRANGER));
        }

        // JSON round-trip preserves both states
        for (StateFlag.State st : StateFlag.State.values()) {
            RegionManager src = new RegionManager("t");
            CuboidRegion rr = claim("rt", 0); rr.setFlag(f, st); src.add(rr);
            ProtectedRegion back = roundTrip(src).get("rt").orElse(null);
            ck(name, "round-trip " + st, back != null && back.getFlag(f) == st);
        }
    }

    static void reportValue(String name, Flag<?> f) {
        String sample = switch (f.getClass().getSimpleName()) {
            case "IntegerFlag" -> "5";
            case "DoubleFlag"  -> "0.5";
            case "BooleanFlag" -> "true";
            case "SetFlag"     -> "alpha,beta";
            default            -> "sample-value";
        };
        Object parsed;
        try { parsed = f.parse(sample); }
        catch (Flag.FlagParseException e) { ck(name, "parse '" + sample + "'", false); return; }
        ck(name, "parse '" + sample + "' non-null", parsed != null);

        // set + resolveValue
        RegionManager m = new RegionManager("t");
        CuboidRegion r = claim("v", 0);
        applyRaw(r, f, parsed);
        m.add(r);
        Object resolved = m.resolveValue(f, m.getApplicable(5, 5, 5), STRANGER);
        ck(name, "resolveValue returns set value", eq(resolved, parsed));

        // group scoping: MEMBERS-only value → member sees it, stranger gets null
        RegionManager mg = new RegionManager("t");
        CuboidRegion rg = claim("vg", 0);
        applyRaw(rg, f, parsed);
        rg.setFlagGroup(f, RegionGroup.MEMBERS);
        mg.add(rg);
        ck(name, "group MEMBERS → member sees value",
                eq(mg.resolveValue(f, mg.getApplicable(5,5,5), MEMBER), parsed));
        ck(name, "group MEMBERS → stranger gets null",
                mg.resolveValue(f, mg.getApplicable(5,5,5), STRANGER) == null);

        // JSON round-trip
        RegionManager src = new RegionManager("t");
        CuboidRegion rr = claim("rt", 0); applyRaw(rr, f, parsed); src.add(rr);
        ProtectedRegion back = roundTrip(src).get("rt").orElse(null);
        ck(name, "round-trip value", back != null && eq(back.getFlag(f), parsed));
    }

    /* ---------- helpers ---------- */

    static void ck(String flag, String what, boolean cond) {
        if (cond) { totalPass++; report.append("  [PASS] ").append(what).append("\n"); }
        else      { totalFail++; report.append("  [FAIL] ").append(what).append("\n"); }
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    static void applyRaw(ProtectedRegion r, Flag f, Object v) { r.setFlag(f, v); }

    @SuppressWarnings({"unchecked","rawtypes"})
    static void set(ProtectedRegion r, Flag f, String input) {
        try { f.parseAndApply(r, input, null); } catch (Flag.FlagParseException e) { throw new RuntimeException(e); }
    }

    static boolean eq(Object a, Object b) { return Objects.equals(a, b); }

    static CuboidRegion claim(String id, int prio) {
        CuboidRegion r = new CuboidRegion(id, new Vec3(0,0,0), new Vec3(31,63,31));
        r.setPriority(prio); r.owners().add(OWNER); r.members().add(MEMBER);
        return r;
    }
    static CuboidRegion box(String id, int prio, int a, int b) {
        CuboidRegion r = new CuboidRegion(id, new Vec3(a,0,a), new Vec3(b,63,b));
        r.setPriority(prio); r.owners().add(OWNER); return r;
    }
    static RegionManager mgrPlain() { RegionManager m = new RegionManager("t"); m.add(claim("p",0)); return m; }
    static RegionManager mgrWith(String tag, Flag<?> f, String input) {
        RegionManager m = new RegionManager("t");
        CuboidRegion r = claim("r_"+tag, 0); set(r, f, input); m.add(r); return m;
    }
    static RegionManager roundTrip(RegionManager src) {
        JsonObject json = RegionJsonCodec.toJson(src);
        JsonObject re = JsonParser.parseString(GSON.toJson(json)).getAsJsonObject();
        RegionManager dst = new RegionManager(src.world());
        RegionJsonCodec.applyJson(re, dst);
        return dst;
    }
}
