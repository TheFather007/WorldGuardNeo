// FuzzCodecTest — property-based: generate many randomized regions (cuboid + polygon, random flags
// of every type with random groups, random owners/members/groups, priorities, acyclic parents),
// round-trip them through BOTH the whole-world codec AND the per-region (incremental) codec, and
// assert every property survives identically. Deterministic seed → reproducible. Pure JVM.
//
// Run via tests/run.sh.

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.storage.RegionJsonCodec;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.*;

public final class FuzzCodecTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; if (failed <= 30) System.out.println("FAIL: " + n); } }

    static final Gson GSON = new Gson();
    static final int N = 200;
    static final UUID[] POOL = new UUID[6];
    static { for (int i = 0; i < POOL.length; i++) POOL[i] = new UUID(0x1000 + i, 0x2000 + i); }
    static final String[] GROUPS = {"admins", "vip", "trusted", "builders"};
    // Representative flags spanning every value type.
    static final StateFlag   F_STATE = Flags.PVP;
    static final BooleanFlag F_BOOL  = Flags.NOTIFY_ENTER;
    static final IntegerFlag F_INT   = Flags.HEAL_AMOUNT;
    static final DoubleFlag  F_DBL   = Flags.MAX_SPEED;
    static final StringFlag  F_STR   = Flags.GREETING;
    static final SetFlag     F_SET   = Flags.BLOCKED_CMDS;
    static final RegionGroup[] RG = RegionGroup.values();

    public static void main(String[] args) {
        Flags.bootstrap();
        Random rng = new Random(0xC0FFEE);
        RegionManager src = new RegionManager("fuzz:world");
        List<String> ids = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            String id = "r" + i;
            ProtectedRegion r = rng.nextBoolean() ? randomCuboid(id, rng) : randomPolygon(id, rng);
            r.setPriority(rng.nextInt(41) - 20);
            addSome(r.owners(), rng);
            addSome(r.members(), rng);
            addGroups(r.ownerGroups(), rng);
            addGroups(r.memberGroups(), rng);
            setRandomFlags(r, rng);
            src.add(r);
            ids.add(id);
            // 30% chance to parent under an earlier region (acyclic by construction).
            if (i > 0 && rng.nextInt(100) < 30) {
                ProtectedRegion par = src.get(ids.get(rng.nextInt(i))).orElse(null);
                if (par != null) try { r.setParent(par); } catch (IllegalStateException ignored) {}
            }
        }
        // Random global flags too.
        if (rng.nextBoolean()) src.globalRegion().setFlag(Flags.TNT, StateFlag.State.DENY);

        RegionManager dstWhole = roundTripWhole(src);
        RegionManager dstPer   = roundTripPerRegion(src);

        check("fuzz: whole-world region count", dstWhole.size() == src.size());
        check("fuzz: per-region region count", dstPer.size() == src.size());
        check("fuzz: global flag (whole)", dstWhole.globalRegion().getFlag(Flags.TNT) == src.globalRegion().getFlag(Flags.TNT));
        check("fuzz: global flag (per)", dstPer.globalRegion().getFlag(Flags.TNT) == src.globalRegion().getFlag(Flags.TNT));

        for (String id : ids) {
            ProtectedRegion s = src.get(id).orElseThrow();
            matches("whole/" + id, s, dstWhole.get(id).orElse(null));
            matches("per/" + id, s, dstPer.get(id).orElse(null));
        }

        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    /* ---------- generation ---------- */

    static CuboidRegion randomCuboid(String id, Random rng) {
        int x1 = rng.nextInt(4000) - 2000, x2 = x1 + rng.nextInt(64);
        int z1 = rng.nextInt(4000) - 2000, z2 = z1 + rng.nextInt(64);
        int y1 = rng.nextInt(200),         y2 = y1 + rng.nextInt(55);
        return new CuboidRegion(id, new Vec3(x1, y1, z1), new Vec3(x2, y2, z2));
    }

    static PolygonalRegion randomPolygon(String id, Random rng) {
        // Axis-aligned rectangle expressed as a 4-point polygon → always non-degenerate.
        int x1 = rng.nextInt(4000) - 2000, x2 = x1 + 1 + rng.nextInt(64);
        int z1 = rng.nextInt(4000) - 2000, z2 = z1 + 1 + rng.nextInt(64);
        int y1 = rng.nextInt(200),         y2 = y1 + rng.nextInt(55);
        return new PolygonalRegion(id, List.of(
                new PolygonalRegion.Point2(x1, z1), new PolygonalRegion.Point2(x2, z1),
                new PolygonalRegion.Point2(x2, z2), new PolygonalRegion.Point2(x1, z2)), y1, y2);
    }

    static void addSome(Set<UUID> set, Random rng) {
        int n = rng.nextInt(4);
        for (int i = 0; i < n; i++) set.add(POOL[rng.nextInt(POOL.length)]);
    }
    static void addGroups(Set<String> set, Random rng) {
        int n = rng.nextInt(3);
        for (int i = 0; i < n; i++) set.add(GROUPS[rng.nextInt(GROUPS.length)]);
    }

    static void setRandomFlags(ProtectedRegion r, Random rng) {
        if (rng.nextBoolean()) { r.setFlag(F_STATE, rng.nextBoolean() ? StateFlag.State.ALLOW : StateFlag.State.DENY); maybeGroup(r, F_STATE, rng); }
        if (rng.nextBoolean()) { r.setFlag(F_BOOL, rng.nextBoolean()); maybeGroup(r, F_BOOL, rng); }
        if (rng.nextBoolean()) { r.setFlag(F_INT, rng.nextInt(2000) - 1000); maybeGroup(r, F_INT, rng); }
        if (rng.nextBoolean()) { r.setFlag(F_DBL, (rng.nextInt(2000) - 1000) / 10.0); maybeGroup(r, F_DBL, rng); }
        if (rng.nextBoolean()) { r.setFlag(F_STR, randomStr(rng)); maybeGroup(r, F_STR, rng); }
        if (rng.nextBoolean()) { r.setFlag(F_SET, randomSet(rng)); maybeGroup(r, F_SET, rng); }
    }
    static void maybeGroup(ProtectedRegion r, Flag<?> f, Random rng) {
        if (rng.nextBoolean()) r.setFlagGroup(f, RG[rng.nextInt(RG.length)]);
    }
    static String randomStr(Random rng) {
        String[] w = {"hello", "welcome %player%", "no entry", "zone: %region%", "café", "a b c", "1/2 way"};
        return w[rng.nextInt(w.length)];
    }
    static Set<String> randomSet(Random rng) {
        String[] w = {"tpa", "home", "sethome", "spawn", "kit", "warp"};
        Set<String> s = new LinkedHashSet<>();
        int n = 1 + rng.nextInt(4);
        for (int i = 0; i < n; i++) s.add(w[rng.nextInt(w.length)]);
        return s;
    }

    /* ---------- round-trips ---------- */

    static RegionManager roundTripWhole(RegionManager src) {
        JsonObject root = JsonParser.parseString(GSON.toJson(RegionJsonCodec.toJson(src))).getAsJsonObject();
        RegionManager dst = new RegionManager(src.world());
        RegionJsonCodec.applyJson(root, dst);
        return dst;
    }

    static RegionManager roundTripPerRegion(RegionManager src) {
        RegionManager dst = new RegionManager(src.world());
        Map<String, String> parents = new HashMap<>();
        for (ProtectedRegion r : src.all()) {
            JsonObject o = JsonParser.parseString(GSON.toJson(RegionJsonCodec.regionToJson(r))).getAsJsonObject();
            ProtectedRegion got = RegionJsonCodec.readRegion(r.id(), o, parents);
            if (got != null) dst.add(got);
        }
        JsonObject g = JsonParser.parseString(GSON.toJson(RegionJsonCodec.globalToJson(src.globalRegion()))).getAsJsonObject();
        RegionJsonCodec.applyGlobalJson(g, dst);
        RegionJsonCodec.linkParents(parents, dst);
        return dst;
    }

    /* ---------- assertion ---------- */

    static void matches(String label, ProtectedRegion s, ProtectedRegion d) {
        if (d == null) { check(label + " loaded", false); return; }
        check(label + " type", s.type().equals(d.type()));
        check(label + " min", s.minimumBound().equals(d.minimumBound()));
        check(label + " max", s.maximumBound().equals(d.maximumBound()));
        check(label + " priority", s.priority() == d.priority());
        check(label + " owners", s.ownersView().equals(d.ownersView()));
        check(label + " members", s.membersView().equals(d.membersView()));
        check(label + " ownerGroups", s.ownerGroupsView().equals(d.ownerGroupsView()));
        check(label + " memberGroups", s.memberGroupsView().equals(d.memberGroupsView()));
        for (Flag<?> f : List.of(F_STATE, F_BOOL, F_INT, F_DBL, F_STR, F_SET)) {
            check(label + " flag " + f.name(), Objects.equals(s.getFlag(f), d.getFlag(f)));
            check(label + " group " + f.name(), s.getFlagGroup(f) == d.getFlagGroup(f));
        }
        if (s.parent() == null) check(label + " no parent", d.parent() == null);
        else check(label + " parent", d.parent() != null && d.parent().id().equals(s.parent().id()));
        if (s instanceof PolygonalRegion sp && d instanceof PolygonalRegion dp) {
            check(label + " poly points", sp.points().equals(dp.points()));
            check(label + " poly Y", sp.minY() == dp.minY() && sp.maxY() == dp.maxY());
        }
    }
}
