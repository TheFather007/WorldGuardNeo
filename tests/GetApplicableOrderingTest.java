// GetApplicableOrderingTest — getApplicable() ordering (priority desc, then id asc), the
// list-vs-point resolution equivalence, hasAnyAt consistency, and ownership-at-point queries.
// Pure JVM.
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.List;
import java.util.UUID;

public final class GetApplicableOrderingTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID STRANGER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    static CuboidRegion overlap(String id, int prio) {
        CuboidRegion r = new CuboidRegion(id, new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        r.setPriority(prio);
        return r;
    }

    public static void main(String[] args) {
        Flags.bootstrap();
        ordering();
        listVsPoint();
        hasAnyAtAndWilderness();
        ownershipAt();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static void ordering() {
        RegionManager m = new RegionManager("w");
        // Insertion order deliberately scrambled; expected order is priority desc, then id asc.
        m.add(overlap("a", 5));
        m.add(overlap("b", 10));
        m.add(overlap("c", 5));
        m.add(overlap("d", 10));
        List<ProtectedRegion> here = m.getApplicable(5, 5, 5);
        check("getApplicable count", here.size() == 4);
        // Expected: b(10), d(10) [id asc within tier], a(5), c(5).
        check("order[0] = b (p10, id b)", here.get(0).id().equals("b"));
        check("order[1] = d (p10, id d)", here.get(1).id().equals("d"));
        check("order[2] = a (p5, id a)", here.get(2).id().equals("a"));
        check("order[3] = c (p5, id c)", here.get(3).id().equals("c"));
        // Single-region fast path still returns the region.
        RegionManager m1 = new RegionManager("w");
        m1.add(overlap("solo", 0));
        check("single-region applicable", m1.getApplicable(5, 5, 5).size() == 1);
    }

    static void listVsPoint() {
        RegionManager m = new RegionManager("w");
        CuboidRegion hi = overlap("hi", 10), lo = overlap("lo", 0);
        hi.owners().add(OWNER);
        hi.setFlag(Flags.PVP, StateFlag.State.ALLOW);
        hi.setFlagGroup(Flags.PVP, RegionGroup.OWNERS);
        lo.setFlag(Flags.PVP, StateFlag.State.DENY);
        m.add(hi); m.add(lo);
        // The list overload and the point overload must agree for every actor.
        for (UUID actor : new UUID[]{OWNER, STRANGER, null}) {
            List<ProtectedRegion> applicable = m.getApplicable(5, 5, 5);
            boolean viaList = m.testState(Flags.PVP, applicable, actor);
            boolean viaPoint = m.testState(Flags.PVP, actor, 5, 5, 5);
            check("list==point for actor " + actor, viaList == viaPoint);
        }
        // Same for resolveValue.
        hi.setFlag(Flags.HEAL_AMOUNT, 9);
        lo.setFlag(Flags.HEAL_AMOUNT, 1);
        List<ProtectedRegion> applicable = m.getApplicable(5, 5, 5);
        check("resolveValue list==point",
                java.util.Objects.equals(m.resolveValue(Flags.HEAL_AMOUNT, applicable, STRANGER),
                                         m.resolveValue(Flags.HEAL_AMOUNT, 5, 5, 5, STRANGER)));
    }

    static void hasAnyAtAndWilderness() {
        RegionManager m = new RegionManager("w");
        m.add(overlap("r", 0));
        check("hasAnyAt true inside", m.hasAnyAt(5, 5, 5));
        check("hasAnyAt == !getApplicable.isEmpty (inside)", m.hasAnyAt(5, 5, 5) == !m.getApplicable(5, 5, 5).isEmpty());
        check("hasAnyAt false outside", !m.hasAnyAt(500, 5, 500));
        check("getApplicable empty in wilderness", m.getApplicable(500, 5, 500).isEmpty());
        check("testState wilderness → default (pvp allow)", m.testState(Flags.PVP, STRANGER, 500, 5, 500));
    }

    static void ownershipAt() {
        RegionManager m = new RegionManager("w");
        CuboidRegion r = overlap("r", 0);
        r.owners().add(OWNER);
        m.add(r);
        check("isOwnerAt owner", m.isOwnerAt(OWNER, 5, 5, 5));
        check("isMemberAt owner (owner is member)", m.isMemberAt(OWNER, 5, 5, 5));
        check("isOwnerAt stranger false", !m.isOwnerAt(STRANGER, 5, 5, 5));
        check("isOwnerAt wilderness false", !m.isOwnerAt(OWNER, 500, 5, 500));
    }
}
