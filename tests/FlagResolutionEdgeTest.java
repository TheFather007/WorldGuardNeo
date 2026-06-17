// Edge-case resolution + spatial-index tests: cross-priority group shadowing, inherited
// build-access, group-scoped value flags, oversized (world-spanning) region indexing, and
// area/overlap queries. Pure-JVM, runs against the real classes.
//
// Run (after ./gradlew build -x test): see tests/run.sh.

import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.List;
import java.util.UUID;

public final class FlagResolutionEdgeTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000b3");

    static CuboidRegion box(String id, int prio, int a, int b) {
        CuboidRegion r = new CuboidRegion(id, new Vec3(a, 0, a), new Vec3(b, 63, b));
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

    public static void main(String[] args) {
        Flags.bootstrap();

        /* ===== A. Cross-priority group shadowing: a higher-prio region whose group EXCLUDES the
                 actor must NOT shadow a lower-prio region that includes them. ===== */
        {
            CuboidRegion high = box("high", 10, 0, 31);
            high.setFlag(Flags.PVP, StateFlag.State.DENY);
            high.setFlagGroup(Flags.PVP, RegionGroup.OWNERS); // deny applies only to owners
            CuboidRegion low = box("low", 1, 0, 31);
            low.setFlag(Flags.PVP, StateFlag.State.ALLOW);     // allow for all
            RegionManager m = mgr(high, low);
            check("A stranger: high(OWNERS deny) skipped → low allow",
                    m.testState(Flags.PVP, STRANGER, 5, 5, 5));
            check("A owner: high deny applies", !m.testState(Flags.PVP, OWNER, 5, 5, 5));
        }

        /* ===== B. Same-tier mixed groups: matching DENY beats matching ALLOW; non-matching ignored ===== */
        {
            CuboidRegion a = box("a", 5, 0, 31);
            a.setFlag(Flags.PVP, StateFlag.State.ALLOW);
            a.setFlagGroup(Flags.PVP, RegionGroup.NON_OWNERS); // allow for non-owners
            CuboidRegion b = box("b", 5, 0, 31);
            b.setFlag(Flags.PVP, StateFlag.State.DENY);        // deny for all
            RegionManager m = mgr(a, b);
            // stranger: a(allow, matches non-owner) + b(deny, matches all) same tier → DENY wins
            check("B same tier DENY beats ALLOW for stranger", !m.testState(Flags.PVP, STRANGER, 5, 5, 5));
        }

        /* ===== C. Inherited build-access: an explicit flag on the PARENT opens the child to strangers ===== */
        {
            CuboidRegion parent = new CuboidRegion("p", new Vec3(0,0,0), new Vec3(63,63,63));
            parent.setFlag(Flags.BUILD, StateFlag.State.ALLOW); // explicit allow for everyone
            CuboidRegion child = box("c", 0, 0, 31);
            child.setParent(parent);
            RegionManager m = mgr(child);
            check("C stranger builds via inherited explicit allow",
                    m.testBuildAccess(Flags.BUILD, 5, 5, 5, STRANGER));
            // sanity: without the parent flag, stranger is denied (membership)
            parent.setFlag(Flags.BUILD, null);
            check("C without inherited flag, stranger denied (membership)",
                    !m.testBuildAccess(Flags.BUILD, 5, 5, 5, STRANGER));
        }

        /* ===== D. Group-scoped value flag via resolveValue ===== */
        {
            CuboidRegion r = box("v", 0, 0, 31);
            r.setFlag(Flags.GREETING, "members only");
            r.setFlagGroup(Flags.GREETING, RegionGroup.MEMBERS);
            RegionManager m = mgr(r);
            check("D member gets group-scoped value", "members only".equals(
                    m.resolveValue(Flags.GREETING, m.getApplicable(5,5,5), MEMBER)));
            check("D stranger does NOT get members-only value (null)",
                    m.resolveValue(Flags.GREETING, m.getApplicable(5,5,5), STRANGER) == null);
        }

        /* ===== E. Oversized (world-spanning) region indexing ===== */
        {
            // >1,000,000 chunk columns → goes to the oversized fallback, not buckets.
            CuboidRegion huge = new CuboidRegion("world", new Vec3(0, 0, 0), new Vec3(20000, 63, 20000));
            RegionManager m = mgr(huge);
            check("E oversized counted", m.index().oversizedCount() == 1);
            check("E oversized not bucketed", m.index().bucketCount() == 0);
            check("E hasAnyAt finds oversized far out", m.hasAnyAt(10000, 5, 10000));
            check("E getApplicable finds oversized", m.getApplicable(10000, 5, 10000).size() == 1);
            check("E point outside oversized AABB excluded", m.getApplicable(25000, 5, 25000).isEmpty());

            // Oversized + a normal bucketed region overlapping at a point → both returned.
            CuboidRegion small = box("small", 5, 0, 15);
            m.add(small);
            check("E combine oversized + bucketed", m.getApplicable(5, 5, 5).size() == 2);
            // priority order: small(5) before huge(0)
            check("E combined ordering by priority", m.getApplicable(5, 5, 5).get(0).id().equals("small"));

            m.remove("world");
            check("E oversized removed", m.index().oversizedCount() == 0);
            check("E after removal only small remains", m.getApplicable(5, 5, 5).size() == 1);
        }

        /* ===== F. countOwned / getOwnedBy across the manager ===== */
        {
            CuboidRegion r1 = box("o1", 0, 0, 15);
            CuboidRegion r2 = box("o2", 0, 100, 115);
            CuboidRegion r3 = new CuboidRegion("o3", new Vec3(200,0,200), new Vec3(215,63,215)); // no owner
            RegionManager m = mgr(r1, r2, r3);
            check("F countOwned counts only owned", m.countOwned(OWNER) == 2);
            check("F countOwned member-not-owner = 0", m.countOwned(MEMBER) == 0);
            check("F getOwnedBy size", m.getOwnedBy(OWNER).size() == 2);
            check("F getOwnedBy stranger empty", m.getOwnedBy(STRANGER).isEmpty());
        }

        /* ===== G. overlapping() with an oversized region included ===== */
        {
            CuboidRegion huge = new CuboidRegion("huge", new Vec3(-50000,0,-50000), new Vec3(50000,63,50000));
            CuboidRegion near = box("near", 0, 0, 15);
            RegionManager m = mgr(huge, near);
            List<ProtectedRegion> ov = m.overlapping(new Vec3(5,5,5), new Vec3(8,8,8));
            check("G overlapping includes oversized + near", ov.size() == 2);
        }

        /* ===== H. get(id) is case-insensitive; global id resolves to the global region ===== */
        {
            RegionManager m = mgr(box("MixedCase", 0, 0, 15));
            check("H case-insensitive lookup", m.get("mixedcase").isPresent());
            check("H global id resolves", m.get(GlobalRegion.ID).orElse(null) instanceof GlobalRegion);
            check("H unknown id empty", m.get("nope").isEmpty());
        }

        /* ===== I. Duplicate add rejected (case-insensitive) ===== */
        {
            RegionManager m = mgr(box("dup", 0, 0, 15));
            check("I duplicate add returns false", !m.add(box("DUP", 0, 0, 15)));
            check("I size stays 1", m.size() == 1);
        }

        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }
}
