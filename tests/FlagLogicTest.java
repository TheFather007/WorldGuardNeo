// Standalone flag-resolution test harness for WorldGuardNeo.
// The resolution core (region/, flags/) has no Minecraft dependencies, so this runs on a plain JVM.
// Run:
//   ./gradlew build -x test
//   javac -cp build/classes/java/main:<fastutil.jar> -d /tmp/wgntest tests/FlagLogicTest.java
//   java  -cp /tmp/wgntest:build/classes/java/main:<fastutil.jar>:<gson.jar> FlagLogicTest
// Exit code != 0 on any failure.
import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Standalone flag-resolution test harness. Runs against the mod's REAL compiled classes
 * (the resolution core has no Minecraft dependencies). Exit code != 0 on any failure.
 */
public final class FlagLogicTest {

    static int passed = 0, failed = 0;

    static void check(String name, boolean cond) {
        if (cond) { passed++; }
        else { failed++; System.out.println("FAIL: " + name); }
    }

    static final UUID OWNER    = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID MEMBER   = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    static RegionManager freshManager(ProtectedRegion... regions) {
        RegionManager mgr = new RegionManager("test:world");
        for (ProtectedRegion r : regions) mgr.add(r);
        return mgr;
    }

    static CuboidRegion claim(String id) {
        CuboidRegion r = new CuboidRegion(id, new Vec3(0, 0, 0), new Vec3(31, 63, 31));
        r.owners().add(OWNER);
        r.members().add(MEMBER);
        return r;
    }

    public static void main(String[] args) throws Exception {
        Flags.bootstrap();

        /* ============ A. USER SCENARIO: use=allow + interact=allow, stranger at door ============ */
        {
            CuboidRegion r = claim("home");
            // Set flags exactly the way /rg flag does (parseAndApply with default group ALL).
            Flags.USE.parseAndApply(r, "allow", RegionGroup.ALL);
            Flags.INTERACT.parseAndApply(r, "allow", RegionGroup.ALL);
            RegionManager mgr = freshManager(r);
            check("A1 stranger USE allowed (plates path)",
                    mgr.testBuildAccess(Flags.USE, 5, 5, 5, STRANGER));
            check("A2 stranger INTERACT allowed (door path part 1)",
                    mgr.testBuildAccess(Flags.INTERACT, 5, 5, 5, STRANGER));
            check("A3 stranger door path: INTERACT && USE allowed",
                    mgr.testBuildAccess(Flags.INTERACT, 5, 5, 5, STRANGER)
                 && mgr.testBuildAccess(Flags.USE, 5, 5, 5, STRANGER));
            check("A4 stranger CHEST_ACCESS still denied (unset → membership)",
                    !mgr.testBuildAccess(Flags.CHEST_ACCESS, 5, 5, 5, STRANGER));
            check("A5 owner CHEST_ACCESS allowed", mgr.testBuildAccess(Flags.CHEST_ACCESS, 5, 5, 5, OWNER));
            check("A6 stranger BUILD still denied", !mgr.testBuildAccess(Flags.BUILD, 5, 5, 5, STRANGER));
        }

        /* ============ A'. Same but with null group (config default-region-group unset) ============ */
        {
            CuboidRegion r = claim("home2");
            Flags.USE.parseAndApply(r, "allow", null);
            Flags.INTERACT.parseAndApply(r, "allow", null);
            RegionManager mgr = freshManager(r);
            check("A7 stranger INTERACT+USE allowed with null group",
                    mgr.testBuildAccess(Flags.INTERACT, 5, 5, 5, STRANGER)
                 && mgr.testBuildAccess(Flags.USE, 5, 5, 5, STRANGER));
        }

        /* ============ A''. Overlapping sibling region without flags must not shadow ============ */
        {
            CuboidRegion a = claim("withflags");
            Flags.USE.parseAndApply(a, "allow", null);
            Flags.INTERACT.parseAndApply(a, "allow", null);
            CuboidRegion b = new CuboidRegion("noflags", new Vec3(0, 0, 0), new Vec3(31, 63, 31));
            RegionManager mgr = freshManager(a, b);
            check("A8 overlap: sibling without flags doesn't shadow explicit allow",
                    mgr.testBuildAccess(Flags.INTERACT, 5, 5, 5, STRANGER)
                 && mgr.testBuildAccess(Flags.USE, 5, 5, 5, STRANGER));
        }

        /* ============ B. Every state flag individually: default / DENY / ALLOW ============ */
        for (Flag<?> f : Flags.all()) {
            if (!(f instanceof StateFlag sf)) continue;
            // default (unset)
            {
                CuboidRegion r = claim("d-" + sf.name());
                RegionManager mgr = freshManager(r);
                boolean got = mgr.testState(sf, STRANGER, 5, 5, 5);
                check("B default '" + sf.name() + "' == defaultAllow(" + sf.defaultAllow() + ")",
                        got == sf.defaultAllow());
            }
            // explicit DENY applies to everyone (group ALL)
            {
                CuboidRegion r = claim("y-" + sf.name());
                sf.parseAndApply(r, "deny", null);
                RegionManager mgr = freshManager(r);
                check("B deny '" + sf.name() + "' stranger", !mgr.testState(sf, STRANGER, 5, 5, 5));
                check("B deny '" + sf.name() + "' owner",    !mgr.testState(sf, OWNER, 5, 5, 5));
                check("B deny '" + sf.name() + "' null",     !mgr.testState(sf, null, 5, 5, 5));
            }
            // explicit ALLOW applies to everyone
            {
                CuboidRegion r = claim("a-" + sf.name());
                sf.parseAndApply(r, "allow", null);
                RegionManager mgr = freshManager(r);
                check("B allow '" + sf.name() + "' stranger", mgr.testState(sf, STRANGER, 5, 5, 5));
            }
            // build-access: explicit value always wins over membership
            {
                CuboidRegion r = claim("ba-" + sf.name());
                sf.parseAndApply(r, "allow", null);
                RegionManager mgr = freshManager(r);
                check("B buildAccess allow '" + sf.name() + "' stranger",
                        mgr.testBuildAccess(sf, 5, 5, 5, STRANGER));
            }
            // unset parse round-trip
            check("B parse none '" + sf.name() + "'", sf.parse("none") == null);
        }

        /* ============ C. Implicit membership protection (flag unset) ============ */
        {
            CuboidRegion r = claim("plain");
            RegionManager mgr = freshManager(r);
            check("C1 owner build",    mgr.testBuildAccess(Flags.BUILD, 5, 5, 5, OWNER));
            check("C2 member build",   mgr.testBuildAccess(Flags.BUILD, 5, 5, 5, MEMBER));
            check("C3 stranger build denied", !mgr.testBuildAccess(Flags.BUILD, 5, 5, 5, STRANGER));
            check("C4 null actor denied",     !mgr.testBuildAccess(Flags.BUILD, 5, 5, 5, null));
            check("C5 wilderness default allow", mgr.testBuildAccess(Flags.BUILD, 500, 5, 500, STRANGER));
        }

        /* ============ D. Group filters ============ */
        {
            CuboidRegion r = claim("grouped");
            Flags.PVP.parseAndApply(r, "deny", RegionGroup.NON_MEMBERS);
            RegionManager mgr = freshManager(r);
            check("D1 pvp deny -g NON_MEMBERS blocks stranger", !mgr.testState(Flags.PVP, STRANGER, 5, 5, 5));
            check("D2 pvp deny -g NON_MEMBERS lets member fight", mgr.testState(Flags.PVP, MEMBER, 5, 5, 5));
            check("D3 pvp deny -g NON_MEMBERS lets owner fight",  mgr.testState(Flags.PVP, OWNER, 5, 5, 5));
        }
        {
            CuboidRegion r = claim("grouped2");
            Flags.USE.parseAndApply(r, "allow", RegionGroup.MEMBERS);
            RegionManager mgr = freshManager(r);
            check("D4 use allow -g MEMBERS: member allowed", mgr.testBuildAccess(Flags.USE, 5, 5, 5, MEMBER));
            // Documented semantics: group-filtered value doesn't contribute for non-matching
            // actors → falls back to flag default (allow for 'use').
            check("D5 use allow -g MEMBERS: stranger falls to default(allow)",
                    mgr.testBuildAccess(Flags.USE, 5, 5, 5, STRANGER));
        }

        /* ============ E. Priority resolution ============ */
        {
            CuboidRegion low = claim("low");
            Flags.PVP.parseAndApply(low, "allow", null);
            CuboidRegion high = new CuboidRegion("high", new Vec3(0, 0, 0), new Vec3(31, 63, 31));
            high.setPriority(10);
            Flags.PVP.parseAndApply(high, "deny", null);
            RegionManager mgr = freshManager(low, high);
            check("E1 higher-priority DENY beats lower ALLOW", !mgr.testState(Flags.PVP, STRANGER, 5, 5, 5));
        }
        {
            CuboidRegion a = claim("samet-a");
            Flags.PVP.parseAndApply(a, "allow", null);
            CuboidRegion b = new CuboidRegion("samet-b", new Vec3(0, 0, 0), new Vec3(31, 63, 31));
            Flags.PVP.parseAndApply(b, "deny", null);
            RegionManager mgr = freshManager(a, b);
            check("E2 same tier: DENY beats ALLOW", !mgr.testState(Flags.PVP, STRANGER, 5, 5, 5));
        }
        {
            CuboidRegion noval = new CuboidRegion("hp-noval", new Vec3(0, 0, 0), new Vec3(31, 63, 31));
            noval.setPriority(10);
            CuboidRegion withval = claim("lp-val");
            Flags.PVP.parseAndApply(withval, "deny", null);
            RegionManager mgr = freshManager(noval, withval);
            check("E3 valueless high-prio doesn't shadow lower value",
                    !mgr.testState(Flags.PVP, STRANGER, 5, 5, 5));
        }

        /* ============ F. Parent inheritance ============ */
        {
            CuboidRegion parent = new CuboidRegion("parent", new Vec3(0, 0, 0), new Vec3(63, 63, 63));
            Flags.USE.parseAndApply(parent, "deny", null);
            CuboidRegion child = claim("child");
            child.setParent(parent);
            RegionManager mgr = freshManager(child); // only child is at the point
            check("F1 child inherits parent's USE deny", !mgr.testState(Flags.USE, STRANGER, 5, 5, 5));
            check("F2 inherited deny via buildAccess too", !mgr.testBuildAccess(Flags.USE, 5, 5, 5, STRANGER));
        }
        {
            // Group filter on inherited flag must apply against the PARENT's membership.
            CuboidRegion parent = new CuboidRegion("parent2", new Vec3(0, 0, 0), new Vec3(63, 63, 63));
            parent.owners().add(OWNER);
            Flags.USE.parseAndApply(parent, "deny", RegionGroup.NON_OWNERS);
            CuboidRegion child = new CuboidRegion("child2", new Vec3(0, 0, 0), new Vec3(31, 63, 31));
            child.setParent(parent);
            RegionManager mgr = freshManager(child);
            check("F3 parent's group: parent-owner passes", mgr.testState(Flags.USE, OWNER, 5, 5, 5));
            check("F4 parent's group: stranger denied",     !mgr.testState(Flags.USE, STRANGER, 5, 5, 5));
        }

        /* ============ G. Geometry: boundaries, negatives, polygon ============ */
        {
            CuboidRegion r = new CuboidRegion("geo", new Vec3(-16, -32, -16), new Vec3(-1, 0, -1));
            RegionManager mgr = freshManager(r);
            check("G1 negative coords inside",  mgr.hasAnyAt(-8.5, -10, -8.5));
            check("G2 maxY+0.9 still inside",   mgr.hasAnyAt(-8.5, 0.9, -8.5));
            check("G3 maxY+1.0 outside",        !mgr.hasAnyAt(-8.5, 1.0, -8.5));
            check("G4 minX-0.1 outside",        !mgr.hasAnyAt(-16.1, -10, -8.5));
            check("G5 block -1 inclusive",      mgr.hasAnyAt(-0.5, -10, -0.5));
            check("G6 0.0 outside (max=-1)",    !mgr.hasAnyAt(0.0, -10, -0.5));
        }
        {
            PolygonalRegion tri = new PolygonalRegion("tri",
                    List.of(new PolygonalRegion.Point2(0, 0),
                            new PolygonalRegion.Point2(20, 0),
                            new PolygonalRegion.Point2(0, 20)), 0, 10);
            RegionManager mgr = freshManager(tri);
            check("G7 polygon centroid inside", mgr.hasAnyAt(5, 5, 5));
            check("G8 polygon outside hypotenuse", !mgr.hasAnyAt(15, 5, 15));
            check("G9 polygon above maxY outside", !mgr.hasAnyAt(5, 11.5, 5));
        }

        /* ============ H. Value flags parse/round-trip ============ */
        {
            check("H1 IntegerFlag parse", Flags.HEAL_AMOUNT.parse(" 5 ") == 5);
            check("H2 DoubleFlag parse",  Flags.MAX_SPEED.parse("0.25") == 0.25);
            check("H3 SetFlag parse splits", Flags.BLOCKED_CMDS.parse("home, tpa;sethome").size() == 3);
            check("H4 StateFlag bad value throws", threw(() -> Flags.PVP.parse("banana")));
            check("H5 BooleanFlag parse", Flags.NOTIFY_ENTER.parse("yes"));
            // unset via empty input through parseAndApply
            CuboidRegion r = claim("unset");
            Flags.PVP.parseAndApply(r, "deny", null);
            Flags.PVP.parseAndApply(r, "", null);
            check("H6 empty input unsets flag", r.getFlag(Flags.PVP) == null);
        }

        /* ============ I. Global region fallback ============ */
        {
            RegionManager mgr = freshManager();
            mgr.globalRegion().setFlag(Flags.PVP, StateFlag.State.DENY);
            check("I1 global deny applies in wilderness", !mgr.testState(Flags.PVP, STRANGER, 500, 5, 500));
            CuboidRegion r = claim("over");
            Flags.PVP.parseAndApply(r, "allow", null);
            mgr.add(r);
            check("I2 region ALLOW overrides global deny", mgr.testState(Flags.PVP, STRANGER, 5, 5, 5));
        }

        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    interface Thrower { void run() throws Exception; }
    static boolean threw(Thrower t) {
        try { t.run(); return false; } catch (Exception e) { return true; }
    }
}
