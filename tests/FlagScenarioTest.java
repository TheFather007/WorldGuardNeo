// Deep scenario matrices for flag resolution, complementing FlagLogicTest. Runs on a plain JVM
// against the real region/flags classes (no Minecraft dependencies).
//
// Run (after ./gradlew build -x test):
//   javac -cp build/classes/java/main:<fastutil.jar> -d /tmp/wgntest tests/FlagScenarioTest.java
//   java  -cp /tmp/wgntest:build/classes/java/main:<fastutil.jar>:<gson.jar> FlagScenarioTest
// Exit code != 0 on any failure.

import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.List;
import java.util.UUID;

public final class FlagScenarioTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000a3");

    static CuboidRegion claim(String id, int prio) {
        CuboidRegion r = new CuboidRegion(id, new Vec3(0, 0, 0), new Vec3(31, 63, 31));
        r.setPriority(prio);
        r.owners().add(OWNER);
        r.members().add(MEMBER);
        return r;
    }
    static RegionManager mgr(ProtectedRegion... rs) {
        RegionManager m = new RegionManager("test:world");
        for (ProtectedRegion r : rs) m.add(r);
        return m;
    }

    static final StateFlag[] BUILD_FLAGS = {
            Flags.BUILD, Flags.BLOCK_BREAK, Flags.BLOCK_PLACE, Flags.INTERACT, Flags.USE, Flags.CHEST_ACCESS
    };

    public static void main(String[] args) {
        Flags.bootstrap();

        /* ===== A. testBuildAccess matrix: every build flag, unset → membership ===== */
        for (StateFlag f : BUILD_FLAGS) {
            RegionManager m = mgr(claim("a", 0));
            check("A " + f.name() + " owner (unset→member)",    m.testBuildAccess(f, 5, 5, 5, OWNER));
            check("A " + f.name() + " member (unset→member)",   m.testBuildAccess(f, 5, 5, 5, MEMBER));
            check("A " + f.name() + " stranger denied",         !m.testBuildAccess(f, 5, 5, 5, STRANGER));
            check("A " + f.name() + " null denied",             !m.testBuildAccess(f, 5, 5, 5, null));
        }
        /* explicit DENY overrides membership even for owners; explicit ALLOW opens to strangers */
        for (StateFlag f : BUILD_FLAGS) {
            CuboidRegion deny = claim("d", 0); deny.setFlag(f, StateFlag.State.DENY);
            check("A " + f.name() + " explicit deny blocks owner", !mgr(deny).testBuildAccess(f, 5, 5, 5, OWNER));
            CuboidRegion allow = claim("al", 0); allow.setFlag(f, StateFlag.State.ALLOW);
            check("A " + f.name() + " explicit allow opens stranger", mgr(allow).testBuildAccess(f, 5, 5, 5, STRANGER));
        }

        /* ===== B. Full RegionGroup contribution matrix for a state flag (deny scoped to group) ===== */
        // deny -g <grp>: actors the group matches are denied; actors it doesn't match fall to default (allow).
        record Case(RegionGroup grp, boolean ownerDenied, boolean memberDenied, boolean strangerDenied) {}
        Case[] cases = {
                new Case(RegionGroup.ALL,         true,  true,  true),
                new Case(RegionGroup.OWNERS,      true,  false, false),
                new Case(RegionGroup.MEMBERS,     true,  true,  false),  // owners count as members
                new Case(RegionGroup.NON_OWNERS,  false, true,  true),
                new Case(RegionGroup.NON_MEMBERS, false, false, true),
        };
        for (Case cs : cases) {
            CuboidRegion r = claim("grp", 0);
            r.setFlag(Flags.PVP, StateFlag.State.DENY);
            r.setFlagGroup(Flags.PVP, cs.grp());
            RegionManager m = mgr(r);
            check("B " + cs.grp() + " owner",    (!m.testState(Flags.PVP, OWNER, 5, 5, 5))    == cs.ownerDenied());
            check("B " + cs.grp() + " member",   (!m.testState(Flags.PVP, MEMBER, 5, 5, 5))   == cs.memberDenied());
            check("B " + cs.grp() + " stranger", (!m.testState(Flags.PVP, STRANGER, 5, 5, 5)) == cs.strangerDenied());
        }

        /* ===== C. Three-tier priority with id tie-break ===== */
        {
            CuboidRegion p5a = claim("aaa", 5); p5a.setFlag(Flags.PVP, StateFlag.State.ALLOW);
            CuboidRegion p5b = claim("bbb", 5); p5b.setFlag(Flags.PVP, StateFlag.State.DENY);
            CuboidRegion p1  = claim("zzz", 1); p1.setFlag(Flags.PVP, StateFlag.State.ALLOW);
            RegionManager m = mgr(p1, p5b, p5a);
            // same top tier (5): DENY wins regardless of insertion order
            check("C same-tier DENY wins over ALLOW", !m.testState(Flags.PVP, STRANGER, 5, 5, 5));
            // applicable list ordered priority desc then id asc
            List<ProtectedRegion> ap = m.getApplicable(5, 5, 5);
            check("C ordering: highest priority first", ap.get(0).priority() == 5);
            check("C ordering: id tie-break aaa before bbb",
                    ap.get(0).id().equals("aaa") && ap.get(1).id().equals("bbb"));
        }

        /* ===== D. Parent chain depth + value inheritance + cycle guard ===== */
        {
            CuboidRegion gp = new CuboidRegion("grandparent", new Vec3(0,0,0), new Vec3(63,63,63));
            gp.setFlag(Flags.GREETING, "hi from gp");
            CuboidRegion p = new CuboidRegion("parent", new Vec3(0,0,0), new Vec3(47,63,47));
            p.setParent(gp);
            CuboidRegion c = claim("child", 0);
            c.setParent(p);
            RegionManager m = mgr(c);
            check("D inherits grandparent value flag",
                    "hi from gp".equals(m.resolveValue(Flags.GREETING, 5, 5, 5, STRANGER)));
            // child's own value shadows ancestor
            c.setFlag(Flags.GREETING, "child wins");
            check("D child value shadows ancestor",
                    "child wins".equals(m.resolveValue(Flags.GREETING, 5, 5, 5, STRANGER)));
            // cycle guard
            boolean threw = false;
            try { gp.setParent(c); } catch (IllegalStateException e) { threw = true; }
            check("D parent cycle rejected", threw);
        }

        /* ===== E. resolveValue picks highest-priority region that sets the value ===== */
        {
            CuboidRegion low = claim("low", 1);  low.setFlag(Flags.GAME_MODE, "survival");
            CuboidRegion high = claim("high", 9); high.setFlag(Flags.GAME_MODE, "adventure");
            RegionManager m = mgr(low, high);
            check("E highest-priority value wins",
                    "adventure".equals(m.resolveValue(Flags.GAME_MODE, 5, 5, 5, STRANGER)));
            // high doesn't set HEAL_AMOUNT → falls through to low
            low.setFlag(Flags.HEAL_AMOUNT, 3);
            check("E falls through to lower tier when higher unset",
                    Integer.valueOf(3).equals(m.resolveValue(Flags.HEAL_AMOUNT, 5, 5, 5, STRANGER)));
        }

        /* ===== F. Geometry edges ===== */
        {
            CuboidRegion r = new CuboidRegion("cube", new Vec3(0, 0, 0), new Vec3(0, 0, 0)); // single block
            RegionManager m = mgr(r);
            check("F single-block volume == 1", r.volume() == 1);
            check("F inside at 0.5", m.hasAnyAt(0.5, 0.5, 0.5));
            check("F inside at 0.0 (min inclusive)", m.hasAnyAt(0.0, 0.0, 0.0));
            check("F inside at 0.999", m.hasAnyAt(0.999, 0.999, 0.999));
            check("F outside at 1.0 (max+1 exclusive)", !m.hasAnyAt(1.0, 0.5, 0.5));
            check("F outside at -0.001", !m.hasAnyAt(-0.001, 0.5, 0.5));
        }
        {
            // Concave (L-shaped) polygon: point in the notch must be OUTSIDE.
            PolygonalRegion l = new PolygonalRegion("L", List.of(
                    new PolygonalRegion.Point2(0, 0), new PolygonalRegion.Point2(20, 0),
                    new PolygonalRegion.Point2(20, 8), new PolygonalRegion.Point2(8, 8),
                    new PolygonalRegion.Point2(8, 20), new PolygonalRegion.Point2(0, 20)), 0, 5);
            RegionManager m = mgr(l);
            check("F concave: inside lower arm", m.hasAnyAt(4, 2, 4));
            check("F concave: inside left arm", m.hasAnyAt(2, 2, 15));
            check("F concave: notch is outside", !m.hasAnyAt(15, 2, 15));
        }

        /* ===== G. overlapping() ===== */
        {
            RegionManager m = mgr(
                    new CuboidRegion("r1", new Vec3(0,0,0), new Vec3(10,10,10)),
                    new CuboidRegion("r2", new Vec3(5,5,5), new Vec3(15,15,15)),
                    new CuboidRegion("r3", new Vec3(100,0,100), new Vec3(110,10,110)));
            List<ProtectedRegion> ov = m.overlapping(new Vec3(8,8,8), new Vec3(12,12,12));
            check("G overlapping finds r1+r2", ov.size() == 2);
            check("G overlapping excludes distant r3", ov.stream().noneMatch(r -> r.id().equals("r3")));
        }

        /* ===== H. Value-flag parsing edge cases ===== */
        try {
            check("H int negative", Flags.MIN_HEAL.parse("-5") == -5);
            check("H int whitespace", Flags.HEAL_AMOUNT.parse("  7 ") == 7);
            check("H double", Flags.MAX_SPEED.parse("1.5") == 1.5);
            check("H set dedup+split", Flags.DENY_SPAWN.parse("zombie, zombie; creeper  skeleton").size() == 3);
            check("H bool on/off", Flags.NOTIFY_ENTER.parse("on") && !Flags.NOTIFY_LEAVE.parse("off"));
            check("H state synonyms allow", Flags.PVP.parse("yes") == StateFlag.State.ALLOW);
            check("H state synonyms deny", Flags.PVP.parse("false") == StateFlag.State.DENY);
            check("H state none", Flags.PVP.parse("none") == null);
            check("H string passthrough", "x,y,z".equals(Flags.TELE_LOC.parse("x,y,z")));
        } catch (Flag.FlagParseException e) {
            check("H no unexpected parse exception", false);
        }
        check("H int rejects text", threw(() -> Flags.HEAL_AMOUNT.parse("abc")));
        check("H double rejects text", threw(() -> Flags.MAX_SPEED.parse("fast")));
        check("H bool rejects text", threw(() -> Flags.NOTIFY_ENTER.parse("maybe")));

        /* ===== I. Vec3 volume uses long math (no overflow) ===== */
        {
            Vec3 a = new Vec3(0,0,0), b = new Vec3(3000,256,3000);
            long expected = 3001L * 257L * 3001L; // ~2.31e9, deliberately > Integer.MAX_VALUE
            check("I large volume no overflow", a.volumeWith(b) == expected && expected > Integer.MAX_VALUE);
        }

        /* ===== J. Flag name validation ===== */
        check("J rejects uppercase flag name", threw(() -> new StateFlag("Bad", true)));
        check("J rejects dotted flag name", threw(() -> new StateFlag("a.b", true)));
        check("J accepts dashed name", new StateFlag("my-flag", true).name().equals("my-flag"));
        check("J region id rejects space", threw(() -> new CuboidRegion("a b", new Vec3(0,0,0), new Vec3(1,1,1))));
        check("J region id accepts colon/dot", new CuboidRegion("a:b.c-1", new Vec3(0,0,0), new Vec3(1,1,1)).id().equals("a:b.c-1"));

        /* ===== K. remove() unlinks children ===== */
        {
            CuboidRegion parent = claim("par", 0);
            CuboidRegion child = new CuboidRegion("ch", new Vec3(0,0,0), new Vec3(5,5,5));
            child.setParent(parent);
            RegionManager m = mgr(parent, child);
            m.remove("par");
            check("K child parent cleared after parent removal", child.parent() == null);
            check("K parent gone", m.get("par").isEmpty());
        }

        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    interface Thrower { void run() throws Exception; }
    static boolean threw(Thrower t) { try { t.run(); return false; } catch (Exception e) { return true; } }
}
