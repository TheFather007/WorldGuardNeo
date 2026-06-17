// EngineExtrasTest — covers the v1.3 region-engine additions/fixes that the older suites predate:
//   1. Per-region storage codec (RegionJsonCodec.regionToJson/readRegion/globalToJson/
//      applyGlobalJson/linkParents) — the path the incremental SQLite/H2/MySQL backends use.
//   2. RegionManager.crossesBoundary — the allocation-free NeighborNotify boundary probe.
//   3. PolygonalRegion.contains floor-consistency (the +X/+Z edge protection fix).
//   4. SpatialIndex handling of extreme-coordinate regions (routed to the oversized fallback).
//
// Pure JVM (no Minecraft): RegionJsonCodec only touches MC classes on malformed input.
// Run via tests/run.sh.

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.storage.RegionJsonCodec;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EngineExtrasTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static final Gson GSON = new Gson();
    static final UUID U1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID U2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    static JsonObject reparse(JsonObject o) { return JsonParser.parseString(GSON.toJson(o)).getAsJsonObject(); }

    public static void main(String[] args) {
        Flags.bootstrap();
        perRegionCodec();
        crossesBoundary();
        polygonEdge();
        spatialIndexExtreme();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    /* 1. Per-region codec round-trip (mirrors the incremental DB backends). */
    static void perRegionCodec() {
        RegionManager src = new RegionManager("test:world");
        CuboidRegion parent = new CuboidRegion("estate", new Vec3(0, 0, 0), new Vec3(100, 100, 100));
        CuboidRegion child  = new CuboidRegion("house",  new Vec3(10, 5, 10), new Vec3(20, 40, 20));
        child.setPriority(5);
        child.owners().add(U1);
        child.members().add(U2);
        child.ownerGroups().add("admins");
        child.setFlag(Flags.PVP, StateFlag.State.DENY);
        child.setFlagGroup(Flags.PVP, RegionGroup.NON_MEMBERS);
        child.setFlag(Flags.GREETING, "hi");
        child.setFlag(Flags.ON_ENTRY, "say %player% entered %region%"); // new v1.3 flag
        child.setFlag(Flags.MAX_SPEED, 0.25);
        src.add(parent);
        src.add(child);
        child.setParent(parent);
        src.globalRegion().setFlag(Flags.TNT, StateFlag.State.DENY);

        // Serialize each region per-region + the global row, then rebuild (as the DB load does).
        RegionManager dst = new RegionManager("test:world");
        Map<String, String> parents = new HashMap<>();
        for (ProtectedRegion r : src.all()) {
            JsonObject o = reparse(RegionJsonCodec.regionToJson(r));
            ProtectedRegion got = RegionJsonCodec.readRegion(r.id(), o, parents);
            if (got != null) dst.add(got);
        }
        RegionJsonCodec.applyGlobalJson(reparse(RegionJsonCodec.globalToJson(src.globalRegion())), dst);
        RegionJsonCodec.linkParents(parents, dst);

        ProtectedRegion h = dst.get("house").orElse(null);
        check("per-region: child loaded", h != null);
        if (h != null) {
            check("per-region: priority", h.priority() == 5);
            check("per-region: owner", h.isOwner(U1));
            check("per-region: member", h.isMember(U2));
            check("per-region: owner-group", h.ownerGroupsView().contains("admins"));
            check("per-region: state flag", h.getFlag(Flags.PVP) == StateFlag.State.DENY);
            check("per-region: flag group", h.getFlagGroup(Flags.PVP) == RegionGroup.NON_MEMBERS);
            check("per-region: string flag", "hi".equals(h.getFlag(Flags.GREETING)));
            check("per-region: on-entry flag", "say %player% entered %region%".equals(h.getFlag(Flags.ON_ENTRY)));
            check("per-region: double flag", Double.valueOf(0.25).equals(h.getFlag(Flags.MAX_SPEED)));
            check("per-region: parent linked", h.parent() != null && h.parent().id().equals("estate"));
            check("per-region: geometry", h.minimumBound().equals(new Vec3(10, 5, 10))
                                       && h.maximumBound().equals(new Vec3(20, 40, 20)));
        }
        check("per-region: global flag round-trips", dst.globalRegion().getFlag(Flags.TNT) == StateFlag.State.DENY);
        check("per-region: count", dst.size() == 2);
    }

    /* 2. crossesBoundary: true iff a region contains the target but not the source. */
    static void crossesBoundary() {
        RegionManager m = new RegionManager("test:world");
        m.add(new CuboidRegion("a", new Vec3(0, 0, 0), new Vec3(10, 10, 10)));
        m.add(new CuboidRegion("b", new Vec3(20, 0, 0), new Vec3(30, 10, 10)));
        // wilderness → wilderness
        check("boundary: out→out false", !m.crossesBoundary(50, 5, 5, 51, 5, 5));
        // wilderness → into A
        check("boundary: out→inA true", m.crossesBoundary(50, 5, 5, 5, 5, 5));
        // within A
        check("boundary: inA→inA false", !m.crossesBoundary(5, 5, 5, 6, 5, 5));
        // A → B (cross into a foreign region)
        check("boundary: inA→inB true", m.crossesBoundary(5, 5, 5, 25, 5, 5));
        // A → wilderness (no foreign region at target)
        check("boundary: inA→out false", !m.crossesBoundary(5, 5, 5, 50, 5, 5));
    }

    /* 3. Polygon contains: a continuous entity coord resolves to the same block as the integer
          coord (the floor fix), so entities on a block aren't mis-classified vs block edits. */
    static void polygonEdge() {
        PolygonalRegion poly = new PolygonalRegion("p",
                List.of(new PolygonalRegion.Point2(0, 0), new PolygonalRegion.Point2(20, 0),
                        new PolygonalRegion.Point2(20, 20), new PolygonalRegion.Point2(0, 20)), 0, 20);
        check("polygon: center inside", poly.contains(10, 10, 10));
        // Floor-consistency: a position anywhere within a block gives the same verdict as the block.
        check("polygon: floor-consistent inside", poly.contains(10, 10, 10) == poly.contains(10.9, 10, 10.9));
        check("polygon: floor-consistent edge", poly.contains(0, 10, 0) == poly.contains(0.8, 10, 0.8));
        check("polygon: outside −X", !poly.contains(-1, 10, 10));
        check("polygon: outside block −1", !poly.contains(-0.5, 10, 10)); // floors to block -1
        check("polygon: outside above maxY", !poly.contains(10, 21, 10));
        check("polygon: inside at maxY block", poly.contains(10, 20, 10)); // block 20 is within [0,20]
    }

    /* 4. SpatialIndex must still find a region whose bounds reach an extreme int coordinate
          (routed to the oversized fallback rather than silently dropped). */
    static void spatialIndexExtreme() {
        RegionManager m = new RegionManager("test:world");
        CuboidRegion edge = new CuboidRegion("worldedge",
                new Vec3(Integer.MIN_VALUE, 0, 0), new Vec3(10, 10, 10));
        m.add(edge);
        check("extreme: oversized count", m.index().oversizedCount() >= 1);
        List<ProtectedRegion> here = m.getApplicable(5, 5, 5);
        check("extreme: found at point", here.stream().anyMatch(r -> r.id().equals("worldedge")));
        check("extreme: hasAnyAt", m.hasAnyAt(5, 5, 5));
        // A normal region is still bucketed (not oversized).
        m.add(new CuboidRegion("normal", new Vec3(100, 0, 100), new Vec3(110, 10, 110)));
        check("extreme: normal still found", m.getApplicable(105, 5, 105).stream()
                .anyMatch(r -> r.id().equals("normal")));
    }
}
