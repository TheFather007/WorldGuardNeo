// ResolutionFuzzTest — property-based flag resolution over thousands of random region stacks:
//   (1) INVARIANT: the list-overload and point-overload of testState/resolveValue must always
//       agree (two code paths, same answer) — needs no reference implementation.
//   (2) CONTROLLED OUTCOMES: a randomly-built stack with a strictly-highest-priority region whose
//       flag is set with group ALL must resolve to that value (DENY/ALLOW dominance); an all-unset
//       stack must resolve to the flag default.
// Deterministic seed. Pure JVM.
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

public final class ResolutionFuzzTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; if (failed <= 40) System.out.println("FAIL: " + n); } }

    static final int M = 16000;        // doubled (was 8000)
    static final UUID[] POOL = new UUID[4];
    static { for (int i = 0; i < POOL.length; i++) POOL[i] = new UUID(7, i + 1); }
    static final UUID[] ACTORS = {POOL[0], POOL[1], POOL[2], POOL[3], null};
    static final RegionGroup[] RG = RegionGroup.values();

    public static void main(String[] args) {
        Flags.bootstrap();
        invariants(new Random(0xCAFE));
        controlled(new Random(0xD00D));
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    /* (1) list overload == point overload, for testState AND resolveValue, across many actors. */
    static void invariants(Random rng) {
        for (int s = 0; s < M; s++) {
            RegionManager m = new RegionManager("w");
            int k = 1 + rng.nextInt(4);
            for (int i = 0; i < k; i++) {
                CuboidRegion r = new CuboidRegion("s" + s + "_" + i, new Vec3(0, 0, 0), new Vec3(20, 20, 20));
                r.setPriority(rng.nextInt(7) - 3);
                // random membership
                if (rng.nextBoolean()) r.owners().add(POOL[rng.nextInt(POOL.length)]);
                if (rng.nextBoolean()) r.members().add(POOL[rng.nextInt(POOL.length)]);
                // random PVP state + group
                int v = rng.nextInt(3);
                if (v < 2) {
                    r.setFlag(Flags.PVP, v == 0 ? StateFlag.State.ALLOW : StateFlag.State.DENY);
                    if (rng.nextBoolean()) r.setFlagGroup(Flags.PVP, RG[rng.nextInt(RG.length)]);
                }
                // random HEAL_AMOUNT value + group
                if (rng.nextBoolean()) {
                    r.setFlag(Flags.HEAL_AMOUNT, rng.nextInt(100));
                    if (rng.nextBoolean()) r.setFlagGroup(Flags.HEAL_AMOUNT, RG[rng.nextInt(RG.length)]);
                }
                m.add(r);
            }
            List<ProtectedRegion> applicable = m.getApplicable(10, 10, 10);
            for (UUID actor : ACTORS) {
                boolean viaPoint = m.testState(Flags.PVP, actor, 10, 10, 10);
                boolean viaList  = m.testState(Flags.PVP, applicable, actor);
                check("state list==point", viaPoint == viaList);
                Object vPoint = m.resolveValue(Flags.HEAL_AMOUNT, 10, 10, 10, actor);
                Object vList  = m.resolveValue(Flags.HEAL_AMOUNT, applicable, actor);
                check("value list==point", Objects.equals(vPoint, vList));
            }
        }
    }

    /* (2) controlled outcomes: dominance of the strictly-highest tier + all-unset default. */
    static void controlled(Random rng) {
        for (int s = 0; s < 6000; s++) {   // doubled (was 3000)
            RegionManager m = new RegionManager("w");
            int k = 1 + rng.nextInt(4);
            int maxPrio = Integer.MIN_VALUE;
            for (int i = 0; i < k; i++) {
                CuboidRegion r = new CuboidRegion("d" + s + "_" + i, new Vec3(0, 0, 0), new Vec3(20, 20, 20));
                int p = rng.nextInt(10);
                r.setPriority(p);
                maxPrio = Math.max(maxPrio, p);
                int v = rng.nextInt(3); // random noise flags below the top
                if (v < 2) r.setFlag(Flags.PVP, v == 0 ? StateFlag.State.ALLOW : StateFlag.State.DENY);
                m.add(r);
            }
            // Strictly-highest-priority region with PVP set, group ALL → it dominates.
            CuboidRegion top = new CuboidRegion("d" + s + "_top", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
            top.setPriority(maxPrio + 1);
            boolean allow = rng.nextBoolean();
            top.setFlag(Flags.PVP, allow ? StateFlag.State.ALLOW : StateFlag.State.DENY);
            // group ALL (default) → applies to every actor
            m.add(top);
            UUID actor = ACTORS[rng.nextInt(ACTORS.length)];
            check("top-tier ALL dominance", m.testState(Flags.PVP, actor, 10, 10, 10) == allow);
        }
        // all-unset → flag default
        for (int s = 0; s < 2000; s++) {   // doubled (was 1000)
            RegionManager m = new RegionManager("w");
            int k = 1 + rng.nextInt(4);
            for (int i = 0; i < k; i++) {
                CuboidRegion r = new CuboidRegion("u" + s + "_" + i, new Vec3(0, 0, 0), new Vec3(20, 20, 20));
                r.setPriority(rng.nextInt(10));
                m.add(r);
            }
            UUID actor = ACTORS[rng.nextInt(ACTORS.length)];
            check("all-unset → PVP default(allow)", m.testState(Flags.PVP, actor, 10, 10, 10));
            check("all-unset → INVINCIBLE default(deny)", !m.testState(Flags.INVINCIBLE, actor, 10, 10, 10));
        }
    }
}
