// RegionMechanicsTest — ProtectedRegion mechanics: id validation, membership, lazy collections,
// parent-cycle detection, flagEpoch, copyFlagsFrom, flag-group semantics, immutable raw views.
// Pure JVM.
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.UUID;

public final class RegionMechanicsTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static final UUID U1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID U2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID U3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    static CuboidRegion r(String id) { return new CuboidRegion(id, new Vec3(0, 0, 0), new Vec3(5, 5, 5)); }

    public static void main(String[] args) {
        Flags.bootstrap();
        idValidation();
        membership();
        lazyCollections();
        parentCycles();
        flagEpoch();
        flagMechanics();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static boolean idOk(String id) {
        try { r(id); return true; } catch (IllegalArgumentException e) { return false; }
    }

    static void idValidation() {
        check("id simple", idOk("home"));
        check("id with dims chars", idOk("Region:1.2-3_4")); // region ids allow A-Za-z0-9_-:.
        check("id reject empty", !idOk(""));
        check("id reject space", !idOk("my home"));
        check("id reject slash", !idOk("a/b"));
        check("id reject hash", !idOk("a#b"));
    }

    static void membership() {
        CuboidRegion x = r("x");
        x.owners().add(U1);
        x.members().add(U2);
        check("isOwner owner", x.isOwner(U1));
        check("isOwner non-owner", !x.isOwner(U2));
        check("isMember owner counts as member", x.isMember(U1));
        check("isMember member", x.isMember(U2));
        check("isMember stranger", !x.isMember(U3));
        check("isOwner null-safe", !x.isOwner(U3));
    }

    static void lazyCollections() {
        CuboidRegion x = r("x");
        check("ownersView empty when unset", x.ownersView().isEmpty());
        check("membersView empty when unset", x.membersView().isEmpty());
        check("ownerGroupsView empty when unset", x.ownerGroupsView().isEmpty());
        check("isOwner false when unset (no NPE)", !x.isOwner(U1));
        x.owners().add(U1);
        check("ownersView reflects write", x.ownersView().contains(U1));
        x.ownerGroups().add("vip");
        check("ownerGroupsView reflects write", x.ownerGroupsView().contains("vip"));
    }

    static void parentCycles() {
        CuboidRegion a = r("a"), b = r("b"), c = r("c");
        // self-parent rejected
        boolean self = false;
        try { a.setParent(a); } catch (IllegalStateException e) { self = true; }
        check("self-parent rejected", self);
        // valid chain a → b → c
        a.setParent(b);
        b.setParent(c);
        check("chain parent set", a.parent() == b && b.parent() == c);
        // closing the cycle c → a rejected
        boolean cyc = false;
        try { c.setParent(a); } catch (IllegalStateException e) { cyc = true; }
        check("cycle rejected", cyc);
        check("cycle attempt left parent unchanged", c.parent() == null);
        // clearing parent
        a.setParent(null);
        check("parent cleared", a.parent() == null);
        // deep but acyclic chain within the 32-hop guard is fine
        CuboidRegion[] chain = new CuboidRegion[20];
        for (int i = 0; i < 20; i++) chain[i] = r("n" + i);
        boolean deepOk = true;
        try { for (int i = 0; i < 19; i++) chain[i].setParent(chain[i + 1]); }
        catch (IllegalStateException e) { deepOk = false; }
        check("deep acyclic chain ok", deepOk);
    }

    static void flagEpoch() {
        CuboidRegion x = r("x");
        long e0 = ProtectedRegion.flagEpoch();
        x.setFlag(Flags.PVP, StateFlag.State.DENY);
        long e1 = ProtectedRegion.flagEpoch();
        check("epoch bumps on setFlag", e1 > e0);
        x.setFlagGroup(Flags.PVP, RegionGroup.OWNERS);
        check("epoch bumps on setFlagGroup", ProtectedRegion.flagEpoch() > e1);
        long e2 = ProtectedRegion.flagEpoch();
        x.setPriority(3);
        check("epoch bumps on setPriority", ProtectedRegion.flagEpoch() > e2);
        long e3 = ProtectedRegion.flagEpoch();
        CuboidRegion p = r("p");
        x.setParent(p);
        check("epoch bumps on setParent", ProtectedRegion.flagEpoch() > e3);
    }

    static void flagMechanics() {
        CuboidRegion x = r("x");
        check("hasFlags false initially", !x.hasFlags());
        x.setFlag(Flags.PVP, StateFlag.State.DENY);
        x.setFlag(Flags.HEAL_AMOUNT, 7);
        check("hasFlags true after set", x.hasFlags());
        check("getFlag value", x.getFlag(Flags.HEAL_AMOUNT) == 7);
        check("getFlagGroup default ALL", x.getFlagGroup(Flags.PVP) == RegionGroup.ALL);
        // setFlag(null) removes
        x.setFlag(Flags.HEAL_AMOUNT, null);
        check("setFlag null removes", x.getFlag(Flags.HEAL_AMOUNT) == null);
        // setFlagGroup with no value is a no-op (no orphan group)
        CuboidRegion y = r("y");
        y.setFlagGroup(Flags.PVP, RegionGroup.OWNERS); // PVP has no value on y
        check("setFlagGroup without value is no-op", y.getFlagGroup(Flags.PVP) == RegionGroup.ALL);
        // copyFlagsFrom copies values + groups
        CuboidRegion src = r("src");
        src.setFlag(Flags.PVP, StateFlag.State.DENY);
        src.setFlagGroup(Flags.PVP, RegionGroup.NON_MEMBERS);
        src.setFlag(Flags.GREETING, "hi");
        CuboidRegion dst = r("dst");
        dst.copyFlagsFrom(src);
        check("copyFlagsFrom value", dst.getFlag(Flags.PVP) == StateFlag.State.DENY);
        check("copyFlagsFrom group", dst.getFlagGroup(Flags.PVP) == RegionGroup.NON_MEMBERS);
        check("copyFlagsFrom string", "hi".equals(dst.getFlag(Flags.GREETING)));
        // flagsRaw is unmodifiable
        boolean immut = false;
        try { dst.flagsRaw().clear(); } catch (UnsupportedOperationException e) { immut = true; }
        check("flagsRaw unmodifiable", immut);
    }
}
