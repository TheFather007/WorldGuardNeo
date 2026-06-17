// PerRegionCodecTest — exhaustive per-region storage codec (the incremental DB path):
// regionToJson / readRegion / globalToJson / applyGlobalJson / linkParents, for every flag type,
// groups, owners/members, priority, polygon geometry, and valid parent links.
// Only valid paths (malformed paths in the codec touch Minecraft logging, by design). Pure JVM.
//
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
import java.util.Set;
import java.util.UUID;

public final class PerRegionCodecTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static final Gson GSON = new Gson();
    static final UUID U1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID U2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    static JsonObject rt(JsonObject o) { return JsonParser.parseString(GSON.toJson(o)).getAsJsonObject(); }

    /** Round-trip a single region through the per-region codec. */
    static ProtectedRegion roundTrip(ProtectedRegion r) {
        Map<String, String> parents = new HashMap<>();
        ProtectedRegion got = RegionJsonCodec.readRegion(r.id(), rt(RegionJsonCodec.regionToJson(r)), parents);
        return got;
    }

    public static void main(String[] args) {
        Flags.bootstrap();
        allFlagTypes();
        membersAndMeta();
        polygon();
        global();
        parentLink();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static void allFlagTypes() {
        CuboidRegion r = new CuboidRegion("r", new Vec3(-5, 0, -5), new Vec3(15, 60, 15));
        r.setFlag(Flags.PVP, StateFlag.State.DENY);             // StateFlag
        r.setFlagGroup(Flags.PVP, RegionGroup.NON_MEMBERS);
        r.setFlag(Flags.NOTIFY_ENTER, Boolean.TRUE);            // BooleanFlag
        r.setFlag(Flags.HEAL_AMOUNT, 6);                        // IntegerFlag
        r.setFlag(Flags.MAX_SPEED, 0.42);                       // DoubleFlag
        r.setFlag(Flags.GREETING, "Welcome, traveler %player%");// StringFlag
        r.setFlag(Flags.ON_ENTRY, "tp %player% 0 64 0");        // StringFlag (v1.3)
        r.setFlag(Flags.BLOCKED_CMDS, new java.util.LinkedHashSet<>(List.of("tpa", "home"))); // SetFlag
        r.setFlagGroup(Flags.BLOCKED_CMDS, RegionGroup.NON_OWNERS);

        ProtectedRegion g = roundTrip(r);
        check("codec: region loaded", g != null);
        if (g == null) return;
        check("codec: StateFlag", g.getFlag(Flags.PVP) == StateFlag.State.DENY);
        check("codec: StateFlag group", g.getFlagGroup(Flags.PVP) == RegionGroup.NON_MEMBERS);
        check("codec: BooleanFlag", Boolean.TRUE.equals(g.getFlag(Flags.NOTIFY_ENTER)));
        check("codec: IntegerFlag", Integer.valueOf(6).equals(g.getFlag(Flags.HEAL_AMOUNT)));
        check("codec: DoubleFlag", Double.valueOf(0.42).equals(g.getFlag(Flags.MAX_SPEED)));
        check("codec: StringFlag", "Welcome, traveler %player%".equals(g.getFlag(Flags.GREETING)));
        check("codec: StringFlag on-entry", "tp %player% 0 64 0".equals(g.getFlag(Flags.ON_ENTRY)));
        Object cmds = g.getFlag(Flags.BLOCKED_CMDS);
        check("codec: SetFlag", cmds instanceof Set && ((Set<?>) cmds).containsAll(Set.of("tpa", "home")));
        check("codec: SetFlag group", g.getFlagGroup(Flags.BLOCKED_CMDS) == RegionGroup.NON_OWNERS);
        check("codec: geometry preserved", g.minimumBound().equals(new Vec3(-5, 0, -5))
                                        && g.maximumBound().equals(new Vec3(15, 60, 15)));
    }

    static void membersAndMeta() {
        CuboidRegion r = new CuboidRegion("m", new Vec3(0, 0, 0), new Vec3(10, 10, 10));
        r.setPriority(13);
        r.owners().add(U1);
        r.members().add(U2);
        r.ownerGroups().add("admins");
        r.memberGroups().add("trusted");
        ProtectedRegion g = roundTrip(r);
        check("codec: priority", g.priority() == 13);
        check("codec: owner", g.isOwner(U1));
        check("codec: member", g.isMember(U2));
        check("codec: owner-group", g.ownerGroupsView().contains("admins"));
        check("codec: member-group", g.memberGroupsView().contains("trusted"));
        // Empty region (no owners/flags) round-trips cleanly.
        CuboidRegion empty = new CuboidRegion("e", new Vec3(0, 0, 0), new Vec3(1, 1, 1));
        ProtectedRegion ge = roundTrip(empty);
        check("codec: empty region loads", ge != null && !ge.hasFlags() && ge.ownersView().isEmpty());
    }

    static void polygon() {
        PolygonalRegion p = new PolygonalRegion("poly", List.of(
                new PolygonalRegion.Point2(0, 0), new PolygonalRegion.Point2(30, 0),
                new PolygonalRegion.Point2(30, 30), new PolygonalRegion.Point2(0, 30)), 10, 80);
        p.setFlag(Flags.TNT, StateFlag.State.DENY);
        ProtectedRegion g = roundTrip(p);
        check("codec: polygon type", g instanceof PolygonalRegion);
        if (g instanceof PolygonalRegion pg) {
            check("codec: polygon points", pg.points().size() == 4);
            check("codec: polygon minY/maxY", pg.minY() == 10 && pg.maxY() == 80);
            check("codec: polygon flag", pg.getFlag(Flags.TNT) == StateFlag.State.DENY);
            check("codec: polygon contains", pg.contains(15, 40, 15));
        }
    }

    static void global() {
        RegionManager src = new RegionManager("w");
        src.globalRegion().setFlag(Flags.TNT, StateFlag.State.DENY);
        src.globalRegion().setFlag(Flags.MOB_SPAWNING, StateFlag.State.DENY);
        RegionManager dst = new RegionManager("w");
        RegionJsonCodec.applyGlobalJson(rt(RegionJsonCodec.globalToJson(src.globalRegion())), dst);
        check("codec: global TNT", dst.globalRegion().getFlag(Flags.TNT) == StateFlag.State.DENY);
        check("codec: global mob-spawning", dst.globalRegion().getFlag(Flags.MOB_SPAWNING) == StateFlag.State.DENY);
    }

    static void parentLink() {
        CuboidRegion parent = new CuboidRegion("estate", new Vec3(0, 0, 0), new Vec3(100, 100, 100));
        CuboidRegion child  = new CuboidRegion("house",  new Vec3(10, 10, 10), new Vec3(20, 20, 20));
        child.setParent(parent);
        RegionManager dst = new RegionManager("w");
        Map<String, String> parents = new HashMap<>();
        dst.add(RegionJsonCodec.readRegion("estate", rt(RegionJsonCodec.regionToJson(parent)), parents));
        dst.add(RegionJsonCodec.readRegion("house",  rt(RegionJsonCodec.regionToJson(child)),  parents));
        check("codec: parent deferred (not yet linked)", dst.get("house").get().parent() == null);
        RegionJsonCodec.linkParents(parents, dst);
        check("codec: parent linked after linkParents",
                dst.get("house").get().parent() == dst.get("estate").orElse(null));
    }
}
