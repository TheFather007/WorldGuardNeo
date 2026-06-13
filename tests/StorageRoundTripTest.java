// Storage round-trip test: RegionManager -> RegionJsonCodec.toJson -> Gson string ->
// parse -> RegionJsonCodec.applyJson -> fresh RegionManager, asserting every region property
// survives. RegionJsonCodec only touches Minecraft classes on malformed-input paths, so a
// valid round-trip runs on a plain JVM.
//
// Run (after ./gradlew build -x test):
//   javac -cp build/classes/java/main:<gson.jar>:<fastutil.jar> -d /tmp/wgntest tests/StorageRoundTripTest.java
//   java  -cp /tmp/wgntest:build/classes/java/main:<gson.jar>:<fastutil.jar> StorageRoundTripTest
// Exit code != 0 on any failure.

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.storage.RegionJsonCodec;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class StorageRoundTripTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static final Gson GSON = new Gson();
    static final UUID U1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID U2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID U3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    static RegionManager roundTrip(RegionManager src) {
        JsonObject json = RegionJsonCodec.toJson(src);
        String text = GSON.toJson(json);
        JsonObject reparsed = JsonParser.parseString(text).getAsJsonObject();
        RegionManager dst = new RegionManager(src.world());
        RegionJsonCodec.applyJson(reparsed, dst);
        return dst;
    }

    public static void main(String[] args) {
        Flags.bootstrap();

        /* ---- 1. Cuboid with owners/members/groups/priority and every value-flag type ---- */
        {
            RegionManager m = new RegionManager("test:world");
            CuboidRegion r = new CuboidRegion("house", new Vec3(-10, 5, -10), new Vec3(20, 70, 20));
            r.setPriority(7);
            r.owners().add(U1); r.owners().add(U2);
            r.members().add(U3);
            r.ownerGroups().add("admins");
            r.memberGroups().add("trusted");
            r.setFlag(Flags.PVP, StateFlag.State.DENY);
            r.setFlag(Flags.GREETING, "Welcome home");
            r.setFlag(Flags.HEAL_AMOUNT, 4);
            r.setFlag(Flags.MAX_SPEED, 0.35);
            r.setFlag(Flags.NOTIFY_ENTER, Boolean.TRUE);
            r.setFlag(Flags.BLOCKED_CMDS, new java.util.LinkedHashSet<>(List.of("tpa", "home", "sethome")));
            r.setFlagGroup(Flags.PVP, RegionGroup.NON_MEMBERS);
            m.add(r);

            RegionManager m2 = roundTrip(m);
            ProtectedRegion r2 = m2.get("house").orElse(null);
            check("1 region survives", r2 instanceof CuboidRegion);
            check("1 priority", r2.priority() == 7);
            check("1 bounds min", r2.minimumBound().equals(new Vec3(-10, 5, -10)));
            check("1 bounds max", r2.maximumBound().equals(new Vec3(20, 70, 20)));
            check("1 owners", r2.ownersView().equals(Set.of(U1, U2)));
            check("1 members", r2.membersView().equals(Set.of(U3)));
            check("1 owner-groups", r2.ownerGroupsView().equals(Set.of("admins")));
            check("1 member-groups", r2.memberGroupsView().equals(Set.of("trusted")));
            check("1 state flag", r2.getFlag(Flags.PVP) == StateFlag.State.DENY);
            check("1 string flag", "Welcome home".equals(r2.getFlag(Flags.GREETING)));
            check("1 integer flag", Integer.valueOf(4).equals(r2.getFlag(Flags.HEAL_AMOUNT)));
            check("1 double flag", Double.valueOf(0.35).equals(r2.getFlag(Flags.MAX_SPEED)));
            check("1 boolean flag", Boolean.TRUE.equals(r2.getFlag(Flags.NOTIFY_ENTER)));
            check("1 set flag", r2.getFlag(Flags.BLOCKED_CMDS).equals(Set.of("tpa", "home", "sethome")));
            check("1 flag group", r2.getFlagGroup(Flags.PVP) == RegionGroup.NON_MEMBERS);
        }

        /* ---- 2. Polygon geometry round-trip ---- */
        {
            RegionManager m = new RegionManager("test:world");
            PolygonalRegion poly = new PolygonalRegion("plot",
                    List.of(new PolygonalRegion.Point2(0, 0),
                            new PolygonalRegion.Point2(16, 0),
                            new PolygonalRegion.Point2(16, 16),
                            new PolygonalRegion.Point2(0, 16)), 60, 80);
            m.add(poly);
            ProtectedRegion p2 = roundTrip(m).get("plot").orElse(null);
            check("2 polygon type", p2 instanceof PolygonalRegion);
            PolygonalRegion pp = (PolygonalRegion) p2;
            check("2 polygon points", pp.points().size() == 4);
            check("2 polygon minY", pp.minY() == 60);
            check("2 polygon maxY", pp.maxY() == 80);
            check("2 polygon volume preserved", pp.volume() == poly.volume());
        }

        /* ---- 3. Parent links resolve after load (deferred) ---- */
        {
            RegionManager m = new RegionManager("test:world");
            CuboidRegion parent = new CuboidRegion("city", new Vec3(0, 0, 0), new Vec3(99, 99, 99));
            CuboidRegion child  = new CuboidRegion("district", new Vec3(0, 0, 0), new Vec3(49, 49, 49));
            child.setParent(parent);
            m.add(parent); m.add(child);
            RegionManager m2 = roundTrip(m);
            ProtectedRegion c2 = m2.get("district").orElse(null);
            check("3 parent re-linked", c2.parent() != null && c2.parent().id().equals("city"));
            check("3 parent is the loaded instance", c2.parent() == m2.get("city").orElse(null));
        }

        /* ---- 4. Global region flags persist ---- */
        {
            RegionManager m = new RegionManager("test:world");
            m.globalRegion().setFlag(Flags.PVP, StateFlag.State.DENY);
            m.globalRegion().setFlag(Flags.GREETING, "global");
            RegionManager m2 = roundTrip(m);
            check("4 global state flag", m2.globalRegion().getFlag(Flags.PVP) == StateFlag.State.DENY);
            check("4 global value flag", "global".equals(m2.globalRegion().getFlag(Flags.GREETING)));
        }

        /* ---- 5. Empty region (no owners/members/flags) round-trips cleanly ---- */
        {
            RegionManager m = new RegionManager("test:world");
            m.add(new CuboidRegion("bare", new Vec3(0, 0, 0), new Vec3(1, 1, 1)));
            ProtectedRegion b2 = roundTrip(m).get("bare").orElse(null);
            check("5 bare region survives", b2 != null);
            check("5 bare no owners", b2.ownersView().isEmpty());
            check("5 bare no flags", b2.flagsRaw().isEmpty());
        }

        /* ---- 6. Every StateFlag survives both DENY and ALLOW ---- */
        for (Flag<?> f : Flags.all()) {
            if (!(f instanceof StateFlag sf)) continue;
            for (StateFlag.State st : StateFlag.State.values()) {
                RegionManager m = new RegionManager("test:world");
                CuboidRegion r = new CuboidRegion("s", new Vec3(0, 0, 0), new Vec3(3, 3, 3));
                r.setFlag(sf, st);
                m.add(r);
                ProtectedRegion r2 = roundTrip(m).get("s").orElse(null);
                check("6 '" + sf.name() + "'=" + st + " round-trips", r2.getFlag(sf) == st);
            }
        }

        /* ---- 7. Group filter persists for every RegionGroup ---- */
        for (RegionGroup grp : RegionGroup.values()) {
            RegionManager m = new RegionManager("test:world");
            CuboidRegion r = new CuboidRegion("g", new Vec3(0, 0, 0), new Vec3(3, 3, 3));
            r.setFlag(Flags.USE, StateFlag.State.DENY);
            r.setFlagGroup(Flags.USE, grp);
            m.add(r);
            ProtectedRegion r2 = roundTrip(m).get("g").orElse(null);
            // ALL is the default and is stored as "no group entry" → getFlagGroup returns ALL.
            check("7 group " + grp + " round-trips", r2.getFlagGroup(Flags.USE) == grp);
        }

        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }
}
