// BenchHotPaths — throughput micro-benchmarks for the region-lookup hot paths that run thousands
// of times per server tick: getApplicable, testState, hasAnyAt, resolveValue. Pure JVM (no Minecraft).
//
// Purpose: numeric perf tracking + a CATASTROPHIC-regression gate. Absolute ops/sec are printed for
// humans to eyeball over time; the pass/fail gate is a deliberately loose wall-clock ceiling for a
// fixed workload, so it trips on an algorithmic regression (e.g. a lookup that silently went O(n))
// but NOT on slow/noisy CI hardware. Deterministic (fixed-seed Random) so runs are comparable.
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.List;
import java.util.Random;
import java.util.UUID;

public final class BenchHotPaths {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    // Workload sizing. REGIONS spread across a grid so the spatial index has many chunk buckets;
    // the query span is a bit larger than the populated area so ~half the lookups are wilderness misses.
    static final int REGIONS   = 2000;
    static final int GRID      = 50;        // regions placed on a GRID x GRID layout, 64 blocks apart
    static final int SPACING   = 64;
    static final int WARMUP    = 300_000;
    static final int ITERS     = 2_000_000;
    // Loose ceiling: real hardware runs the whole battery in well under a second. 30s only trips on a
    // genuine algorithmic blowup, never on a slow runner.
    static final long MAX_WALL_NANOS = 30L * 1_000_000_000L;

    static final UUID OWNER    = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID STRANGER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    public static void main(String[] args) {
        Flags.bootstrap();
        RegionManager mgr = buildWorld();

        // Precompute a fixed set of query points so every benchmarked call hits the same distribution.
        Random rnd = new Random(20260627L);
        int span = GRID * SPACING + SPACING; // slightly past the populated grid → mix of hits/misses
        int[][] pts = new int[ITERS][3];
        for (int i = 0; i < ITERS; i++) {
            pts[i][0] = rnd.nextInt(span) - SPACING; // allow some negatives (wilderness)
            pts[i][1] = rnd.nextInt(40) + 60;
            pts[i][2] = rnd.nextInt(span) - SPACING;
        }

        long t0 = System.nanoTime();

        // ---- warm up the JIT on the same code paths ----
        long sink = 0;
        for (int i = 0; i < WARMUP; i++) {
            int[] p = pts[i % ITERS];
            sink += mgr.getApplicable(p[0], p[1], p[2]).size();
            if (mgr.hasAnyAt(p[0], p[1], p[2])) sink++;
        }

        // ---- getApplicable ----
        int hits = 0;
        long s = System.nanoTime();
        for (int i = 0; i < ITERS; i++) {
            int[] p = pts[i];
            List<ProtectedRegion> a = mgr.getApplicable(p[0], p[1], p[2]);
            if (!a.isEmpty()) { hits++; sink += a.size(); }
        }
        double getApp = throughput(ITERS, System.nanoTime() - s);

        // ---- hasAnyAt (allocation-free boolean probe) ----
        int anyHits = 0;
        s = System.nanoTime();
        for (int i = 0; i < ITERS; i++) {
            int[] p = pts[i];
            if (mgr.hasAnyAt(p[0], p[1], p[2])) anyHits++;
        }
        double hasAny = throughput(ITERS, System.nanoTime() - s);

        // ---- testState (priority + parent + group resolution) ----
        s = System.nanoTime();
        for (int i = 0; i < ITERS; i++) {
            int[] p = pts[i];
            if (mgr.testState(Flags.PVP, (i & 1) == 0 ? OWNER : STRANGER, p[0], p[1], p[2])) sink++;
        }
        double testSt = throughput(ITERS, System.nanoTime() - s);

        // ---- resolveValue (value-flag resolution) ----
        s = System.nanoTime();
        for (int i = 0; i < ITERS; i++) {
            int[] p = pts[i];
            if (mgr.resolveValue(Flags.HEAL_AMOUNT, p[0], p[1], p[2], STRANGER) != null) sink++;
        }
        double resVal = throughput(ITERS, System.nanoTime() - s);

        long wall = System.nanoTime() - t0;

        System.out.printf("  regions=%d  iters=%,d  per-op throughput (Mops/s):%n", REGIONS, ITERS);
        System.out.printf("    getApplicable=%.2f  hasAnyAt=%.2f  testState=%.2f  resolveValue=%.2f%n",
                getApp, hasAny, testSt, resVal);
        System.out.printf("    getApplicable hit-rate=%.0f%%  (wall=%.2fs, sink=%d)%n",
                100.0 * hits / ITERS, wall / 1e9, sink);

        // ---- gates ----
        // Workload sanity: the query distribution must actually exercise both hits AND misses, else the
        // benchmark (and any regression it would catch) is meaningless.
        check("workload has region hits", hits > ITERS / 100);
        check("workload has wilderness misses", hits < ITERS * 99 / 100);
        check("hasAnyAt and getApplicable agree on hit-rate", Math.abs(anyHits - hits) <= ITERS / 1000);
        // Catastrophic-regression gate: the whole battery (4 x ITERS lookups over REGIONS regions)
        // must finish within a very generous wall-clock budget. Trips only on an algorithmic blowup.
        check("hot paths within wall-clock budget", wall < MAX_WALL_NANOS);
        // Throughput floors set FAR below real numbers (millions/s) so they catch a lookup that went
        // linear in region count, without being flaky on shared CI cores.
        check("getApplicable throughput floor", getApp > 0.10);   // > 100k ops/s
        check("hasAnyAt throughput floor", hasAny > 0.10);
        check("testState throughput floor", testSt > 0.10);

        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    /** Million-ops-per-second for {@code n} operations taking {@code nanos}. */
    static double throughput(long n, long nanos) { return (n / (nanos / 1e9)) / 1e6; }

    /** A grid of overlapping cuboid regions with priorities, owners, parents and a few flags set —
     *  representative of a busy survival world, sized to populate many spatial-index buckets. */
    static RegionManager buildWorld() {
        RegionManager m = new RegionManager("bench");
        int made = 0;
        ProtectedRegion lastParent = null;
        for (int gx = 0; gx < GRID && made < REGIONS; gx++) {
            for (int gz = 0; gz < GRID && made < REGIONS; gz++) {
                int x = gx * SPACING, z = gz * SPACING;
                // Regions are 48 wide on a 64 grid → adjacent ones leave gaps (→ wilderness misses),
                // but every 7th is oversized to overlap its neighbours (→ multi-region resolution).
                int w = (made % 7 == 0) ? 80 : 48;
                CuboidRegion r = new CuboidRegion("r" + made,
                        new Vec3(x, 60, z), new Vec3(x + w, 100, z + w));
                r.setPriority(made % 5);
                if (made % 3 == 0) r.owners().add(OWNER);
                if (made % 4 == 0) {
                    r.setFlag(Flags.PVP, (made % 8 == 0) ? StateFlag.State.DENY : StateFlag.State.ALLOW);
                    r.setFlagGroup(Flags.PVP, RegionGroup.NON_OWNERS);
                }
                if (made % 6 == 0) r.setFlag(Flags.HEAL_AMOUNT, made);
                // Chain every 10th region under the previous parent to exercise parent-walking.
                if (lastParent != null && made % 10 == 0) {
                    try { r.setParent(lastParent); } catch (RuntimeException ignored) {}
                }
                if (made % 10 == 0) lastParent = r;
                m.add(r);
                made++;
            }
        }
        return m;
    }
}
