// ParentChainTest — flag inheritance along parent chains: near-ancestor inheritance, the 32-hop
// resolution bound, group filters on an ancestor, and cycle prevention at depth. Pure JVM.
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.UUID;

public final class ParentChainTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID STRANGER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    static CuboidRegion at0(String id) { return new CuboidRegion(id, new Vec3(0, 0, 0), new Vec3(10, 10, 10)); }

    public static void main(String[] args) {
        Flags.bootstrap();
        nearInheritance();
        hopLimit();
        groupOnAncestor();
        cycleAtDepth();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    /** Build a chain leaf → p1 → p2 → … of the given length; only leaf is added to the manager. */
    static CuboidRegion buildChain(RegionManager m, int depth) {
        CuboidRegion leaf = at0("leaf");
        m.add(leaf);
        CuboidRegion cur = leaf;
        for (int i = 1; i <= depth; i++) {
            CuboidRegion p = at0("p" + i);
            cur.setParent(p);
            cur = p;
        }
        return leaf;
    }
    static ProtectedRegion ancestor(CuboidRegion leaf, int hop) {
        ProtectedRegion c = leaf;
        for (int i = 0; i < hop; i++) c = c.parent();
        return c;
    }

    static void nearInheritance() {
        RegionManager m = new RegionManager("w");
        CuboidRegion leaf = buildChain(m, 5);
        ancestor(leaf, 5).setFlag(Flags.PVP, StateFlag.State.DENY); // flag on the 5th ancestor
        check("inherits flag from 5th ancestor", !m.testState(Flags.PVP, STRANGER, 5, 5, 5));
        // Nearer ancestor overrides farther one (priority by proximity).
        ancestor(leaf, 2).setFlag(Flags.PVP, StateFlag.State.ALLOW);
        check("nearer ancestor overrides farther", m.testState(Flags.PVP, STRANGER, 5, 5, 5));
        // Leaf overrides all.
        leaf.setFlag(Flags.PVP, StateFlag.State.DENY);
        check("leaf overrides ancestors", !m.testState(Flags.PVP, STRANGER, 5, 5, 5));
    }

    static void hopLimit() {
        // A flag within the 32-hop window is inherited; beyond it is not.
        RegionManager m1 = new RegionManager("w");
        CuboidRegion leaf1 = buildChain(m1, 40);
        ancestor(leaf1, 20).setFlag(Flags.PVP, StateFlag.State.DENY);
        check("hop 20 inherited", !m1.testState(Flags.PVP, STRANGER, 5, 5, 5));

        RegionManager m2 = new RegionManager("w");
        CuboidRegion leaf2 = buildChain(m2, 40);
        ancestor(leaf2, 35).setFlag(Flags.PVP, StateFlag.State.DENY); // beyond the 32-hop bound
        check("hop 35 NOT inherited (bounded walk)", m2.testState(Flags.PVP, STRANGER, 5, 5, 5));
    }

    static void groupOnAncestor() {
        RegionManager m = new RegionManager("w");
        CuboidRegion leaf = at0("leaf");
        CuboidRegion parent = at0("parent");
        parent.owners().add(OWNER);
        parent.setFlag(Flags.PVP, StateFlag.State.DENY);
        parent.setFlagGroup(Flags.PVP, RegionGroup.NON_OWNERS); // deny for non-owners of the PARENT
        m.add(leaf);
        leaf.setParent(parent);
        check("ancestor group: stranger denied", !m.testState(Flags.PVP, STRANGER, 5, 5, 5));
        check("ancestor group: parent-owner allowed (default)", m.testState(Flags.PVP, OWNER, 5, 5, 5));
    }

    static void cycleAtDepth() {
        CuboidRegion a = at0("a"), b = at0("b"), c = at0("c");
        a.setParent(b);
        b.setParent(c);
        boolean threw = false;
        try { c.setParent(a); } catch (IllegalStateException e) { threw = true; }
        check("cycle at depth rejected", threw);
        check("cycle attempt leaves parent null", c.parent() == null);
    }
}
