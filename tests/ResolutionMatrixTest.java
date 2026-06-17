// ResolutionMatrixTest — exhaustive RegionManager resolution: group×actor matrix, priority tiers,
// DENY-wins, group shadowing, parent inheritance, build-access "private by default", resolveValue,
// crossesBoundary, overlapping, ownership queries. Pure JVM.
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.UUID;

public final class ResolutionMatrixTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static final UUID OWNER   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID MEMBER  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID STRANGER= UUID.fromString("33333333-3333-3333-3333-333333333333");

    public static void main(String[] args) {
        Flags.bootstrap();
        groupMatrix();
        priorityAndDeny();
        groupShadowing();
        parents();
        buildAccess();
        valueFlags();
        crossBoundary();
        overlapAndOwnership();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static CuboidRegion claim(String id) {
        CuboidRegion r = new CuboidRegion(id, new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        r.owners().add(OWNER);
        r.members().add(MEMBER);
        return r;
    }

    static void groupMatrix() {
        CuboidRegion r = claim("g");
        Object[][] exp = {
            // group,        owner, member, stranger, null
            {RegionGroup.ALL,         true,  true,  true,  true},
            {RegionGroup.OWNERS,      true,  false, false, false},
            {RegionGroup.MEMBERS,     true,  true,  false, false},
            {RegionGroup.NON_OWNERS,  false, true,  true,  true},
            {RegionGroup.NON_MEMBERS, false, false, true,  true},
        };
        for (Object[] row : exp) {
            RegionGroup g = (RegionGroup) row[0];
            check("groupMatches " + g + " owner",    RegionManager.groupMatches(g, r, OWNER)    == (boolean) row[1]);
            check("groupMatches " + g + " member",   RegionManager.groupMatches(g, r, MEMBER)   == (boolean) row[2]);
            check("groupMatches " + g + " stranger", RegionManager.groupMatches(g, r, STRANGER) == (boolean) row[3]);
            check("groupMatches " + g + " null",     RegionManager.groupMatches(g, r, null)     == (boolean) row[4]);
        }
        // null group defaults to ALL.
        check("groupMatches null-group = ALL", RegionManager.groupMatches(null, r, STRANGER));
    }

    static void priorityAndDeny() {
        RegionManager m = new RegionManager("w");
        CuboidRegion lo = new CuboidRegion("lo", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        CuboidRegion hi = new CuboidRegion("hi", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        lo.setPriority(0); hi.setPriority(10);
        lo.setFlag(Flags.PVP, StateFlag.State.DENY);
        hi.setFlag(Flags.PVP, StateFlag.State.ALLOW);
        m.add(lo); m.add(hi);
        check("higher priority ALLOW wins over lower DENY", m.testState(Flags.PVP, STRANGER, 10, 10, 10));
        // Same tier: DENY beats ALLOW.
        RegionManager m2 = new RegionManager("w");
        CuboidRegion x = new CuboidRegion("x", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        CuboidRegion y = new CuboidRegion("y", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        x.setFlag(Flags.PVP, StateFlag.State.ALLOW);
        y.setFlag(Flags.PVP, StateFlag.State.DENY);
        m2.add(x); m2.add(y); // same priority 0
        check("same tier DENY beats ALLOW", !m2.testState(Flags.PVP, STRANGER, 10, 10, 10));
        // Unset → flag default (PVP default allow).
        RegionManager m3 = new RegionManager("w");
        m3.add(new CuboidRegion("p", new Vec3(0, 0, 0), new Vec3(20, 20, 20)));
        check("unset → default allow", m3.testState(Flags.PVP, STRANGER, 10, 10, 10));
        check("wilderness → default allow", m3.testState(Flags.PVP, STRANGER, 999, 10, 999));
    }

    static void groupShadowing() {
        // High-priority region whose group EXCLUDES the actor must NOT shadow a lower one that includes them.
        RegionManager m = new RegionManager("w");
        CuboidRegion hi = new CuboidRegion("hi", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        CuboidRegion lo = new CuboidRegion("lo", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        hi.setPriority(10); lo.setPriority(0);
        hi.owners().add(OWNER);
        hi.setFlag(Flags.PVP, StateFlag.State.ALLOW);
        hi.setFlagGroup(Flags.PVP, RegionGroup.OWNERS); // only applies to owner
        lo.setFlag(Flags.PVP, StateFlag.State.DENY);    // applies to all
        m.add(hi); m.add(lo);
        // Owner: hi's OWNERS-allow applies → allowed.
        check("shadowing: owner gets hi ALLOW", m.testState(Flags.PVP, OWNER, 10, 10, 10));
        // Stranger: hi excludes them → falls to lo DENY → denied.
        check("shadowing: stranger falls through to lo DENY", !m.testState(Flags.PVP, STRANGER, 10, 10, 10));
    }

    static void parents() {
        RegionManager m = new RegionManager("w");
        CuboidRegion parent = new CuboidRegion("parent", new Vec3(0, 0, 0), new Vec3(40, 40, 40));
        CuboidRegion child  = new CuboidRegion("child",  new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        parent.setFlag(Flags.PVP, StateFlag.State.DENY); // only on parent
        m.add(parent); m.add(child);
        child.setParent(parent);
        child.setPriority(5); // child wins position, but has no PVP → inherits parent's DENY
        check("child inherits parent flag", !m.testState(Flags.PVP, STRANGER, 10, 10, 10));
        // Child overrides parent.
        child.setFlag(Flags.PVP, StateFlag.State.ALLOW);
        check("child overrides parent flag", m.testState(Flags.PVP, STRANGER, 10, 10, 10));
        // Group on an inherited flag applies to the PARENT (source) membership.
        RegionManager m2 = new RegionManager("w");
        CuboidRegion par2 = new CuboidRegion("par2", new Vec3(0, 0, 0), new Vec3(40, 40, 40));
        CuboidRegion ch2  = new CuboidRegion("ch2",  new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        par2.owners().add(OWNER);
        par2.setFlag(Flags.PVP, StateFlag.State.DENY);
        par2.setFlagGroup(Flags.PVP, RegionGroup.NON_OWNERS); // deny for non-owners of par2
        m2.add(par2); m2.add(ch2);
        ch2.setParent(par2);
        check("inherited flag group: stranger denied", !m2.testState(Flags.PVP, STRANGER, 10, 10, 10));
        check("inherited flag group: owner allowed (default)", m2.testState(Flags.PVP, OWNER, 10, 10, 10));
    }

    static void buildAccess() {
        RegionManager m = new RegionManager("w");
        // Wilderness → global default (BUILD allow).
        check("buildAccess wilderness allow", m.testBuildAccess(Flags.BUILD, 500, 5, 500, STRANGER));
        // Membership-only claim (BUILD unset): owner/member build, stranger/null don't.
        CuboidRegion claim = claim("claim");
        m.add(claim);
        check("buildAccess owner (membership)",    m.testBuildAccess(Flags.BUILD, 10, 10, 10, OWNER));
        check("buildAccess member (membership)",   m.testBuildAccess(Flags.BUILD, 10, 10, 10, MEMBER));
        check("buildAccess stranger denied",      !m.testBuildAccess(Flags.BUILD, 10, 10, 10, STRANGER));
        check("buildAccess null denied",          !m.testBuildAccess(Flags.BUILD, 10, 10, 10, null));
        // Explicit BUILD=allow opens it to everyone.
        claim.setFlag(Flags.BUILD, StateFlag.State.ALLOW);
        check("buildAccess explicit allow → stranger", m.testBuildAccess(Flags.BUILD, 10, 10, 10, STRANGER));
        // Explicit BUILD=deny closes it for everyone (even owner).
        claim.setFlag(Flags.BUILD, StateFlag.State.DENY);
        check("buildAccess explicit deny → owner blocked", !m.testBuildAccess(Flags.BUILD, 10, 10, 10, OWNER));
    }

    static void valueFlags() {
        RegionManager m = new RegionManager("w");
        CuboidRegion lo = new CuboidRegion("lo", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        CuboidRegion hi = new CuboidRegion("hi", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        lo.setPriority(0); hi.setPriority(10);
        lo.setFlag(Flags.HEAL_AMOUNT, 1);
        hi.setFlag(Flags.HEAL_AMOUNT, 9);
        m.add(lo); m.add(hi);
        check("value flag: higher priority wins", Integer.valueOf(9).equals(m.resolveValue(Flags.HEAL_AMOUNT, 10, 10, 10, STRANGER)));
        // Group-scoped value: only owner sees hi's value; stranger falls to lo.
        hi.owners().add(OWNER);
        hi.setFlagGroup(Flags.HEAL_AMOUNT, RegionGroup.OWNERS);
        check("value flag: owner gets hi", Integer.valueOf(9).equals(m.resolveValue(Flags.HEAL_AMOUNT, 10, 10, 10, OWNER)));
        check("value flag: stranger gets lo", Integer.valueOf(1).equals(m.resolveValue(Flags.HEAL_AMOUNT, 10, 10, 10, STRANGER)));
        check("value flag: unset → null", m.resolveValue(Flags.GREETING, 10, 10, 10, STRANGER) == null);
    }

    static void crossBoundary() {
        RegionManager m = new RegionManager("w");
        m.add(new CuboidRegion("a", new Vec3(0, 0, 0), new Vec3(10, 10, 10)));
        m.add(new CuboidRegion("b", new Vec3(20, 0, 0), new Vec3(30, 10, 10)));
        check("cross out→out false", !m.crossesBoundary(50, 5, 5, 51, 5, 5));
        check("cross out→in true",   m.crossesBoundary(50, 5, 5, 5, 5, 5));
        check("cross in→in same false", !m.crossesBoundary(5, 5, 5, 6, 5, 5));
        check("cross A→B true", m.crossesBoundary(5, 5, 5, 25, 5, 5));
        check("cross in→out false", !m.crossesBoundary(5, 5, 5, 50, 5, 5));
    }

    static void overlapAndOwnership() {
        RegionManager m = new RegionManager("w");
        CuboidRegion a = claim("a");
        CuboidRegion b = new CuboidRegion("b", new Vec3(100, 0, 100), new Vec3(120, 20, 120));
        b.owners().add(OWNER);
        m.add(a); m.add(b);
        check("overlapping finds a", m.overlapping(new Vec3(5, 5, 5), new Vec3(6, 6, 6)).stream().anyMatch(r -> r.id().equals("a")));
        check("overlapping excludes far b", m.overlapping(new Vec3(5, 5, 5), new Vec3(6, 6, 6)).stream().noneMatch(r -> r.id().equals("b")));
        check("countOwned", m.countOwned(OWNER) == 2);
        check("countOwned stranger 0", m.countOwned(STRANGER) == 0);
        check("getOwnedBy size", m.getOwnedBy(OWNER).size() == 2);
        check("isOwnerAt", m.isOwnerAt(OWNER, 10, 10, 10));
        check("isMemberAt member", m.isMemberAt(MEMBER, 10, 10, 10));
        check("isOwnerAt stranger false", !m.isOwnerAt(STRANGER, 10, 10, 10));
        // remove unlinks + drops.
        m.remove("a");
        check("after remove, gone", m.get("a").isEmpty());
        check("after remove, countOwned drops", m.countOwned(OWNER) == 1);
    }
}
