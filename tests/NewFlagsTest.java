// NewFlagsTest — covers the v1.3 protection flags (vehicle-place/enter, item-frame-rotate,
// sign-edit, lectern-take, armor-stand-use, glide, bucket-fill/empty, spawn-limit) across every
// engine direction: registration + type, default state, parse(), per-region codec round-trip, and
// live resolution (deny / priority / parent inheritance / region-group). Pure JVM.
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
import java.util.Set;
import java.util.UUID;

public final class NewFlagsTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static final Gson GSON = new Gson();
    static final UUID OWNER    = UUID.fromString("0a000000-0000-0000-0000-000000000001");
    static final UUID STRANGER = UUID.fromString("0b000000-0000-0000-0000-000000000002");

    /** The nine new StateFlags (all default ALLOW). */
    static final StateFlag[] NEW_STATE = {
        Flags.VEHICLE_PLACE, Flags.VEHICLE_ENTER, Flags.ITEM_FRAME_ROTATE, Flags.SIGN_EDIT,
        Flags.LECTERN_TAKE, Flags.ARMOR_STAND_USE, Flags.GLIDE, Flags.BUCKET_FILL, Flags.BUCKET_EMPTY,
    };

    public static void main(String[] args) {
        Flags.bootstrap();
        registration();
        defaults();
        parsing();
        codecRoundTrip();
        resolution();
        spawnLimit();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    /* ---- registration + type ---- */
    static void registration() {
        for (StateFlag f : NEW_STATE) {
            check("registered: " + f.name(), Flags.get(f.name()) == f);
            check("is StateFlag: " + f.name(), Flags.get(f.name()) instanceof StateFlag);
        }
        check("spawn-limit registered", Flags.get("spawn-limit") == Flags.SPAWN_LIMIT);
        check("spawn-limit is SetFlag", Flags.get("spawn-limit") instanceof SetFlag);
        // Names match the documented ids exactly.
        check("name vehicle-place", "vehicle-place".equals(Flags.VEHICLE_PLACE.name()));
        check("name vehicle-enter", "vehicle-enter".equals(Flags.VEHICLE_ENTER.name()));
        check("name item-frame-rotate", "item-frame-rotate".equals(Flags.ITEM_FRAME_ROTATE.name()));
        check("name sign-edit", "sign-edit".equals(Flags.SIGN_EDIT.name()));
        check("name lectern-take", "lectern-take".equals(Flags.LECTERN_TAKE.name()));
        check("name armor-stand-use", "armor-stand-use".equals(Flags.ARMOR_STAND_USE.name()));
        check("name glide", "glide".equals(Flags.GLIDE.name()));
        check("name bucket-fill", "bucket-fill".equals(Flags.BUCKET_FILL.name()));
        check("name bucket-empty", "bucket-empty".equals(Flags.BUCKET_EMPTY.name()));
    }

    /* ---- default state (unset → ALLOW) ---- */
    static void defaults() {
        for (StateFlag f : NEW_STATE) {
            check("default allow: " + f.name(), f.test(null));
            check("test ALLOW: " + f.name(), f.test(StateFlag.State.ALLOW));
            check("test DENY: " + f.name(), !f.test(StateFlag.State.DENY));
        }
    }

    /* ---- parse() ---- */
    static void parsing() {
        for (StateFlag f : NEW_STATE) {
            check("parse allow: " + f.name(), tryParse(f, "allow") == StateFlag.State.ALLOW);
            check("parse deny: " + f.name(),  tryParse(f, "deny")  == StateFlag.State.DENY);
            check("parse none→null: " + f.name(), tryParse(f, "none") == null);
            check("parse invalid→throws: " + f.name(), tryParse(f, "maybe") == THREW);
        }
        // spawn-limit is a set of "type:max" tokens; colons survive the comma/space split.
        Object v = tryParse(Flags.SPAWN_LIMIT, "minecraft:zombie:5, creeper:2");
        check("spawn-limit parses to set", v instanceof Set);
        if (v instanceof Set<?> s) {
            check("spawn-limit has zombie entry", s.contains("minecraft:zombie:5"));
            check("spawn-limit has creeper entry", s.contains("creeper:2"));
            check("spawn-limit size 2", s.size() == 2);
        }
    }

    static final Object THREW = new Object();
    static Object tryParse(Flag<?> f, String in) {
        try { return f.parse(in); } catch (Flag.FlagParseException e) { return THREW; }
    }

    /* ---- per-region codec round-trip ---- */
    static void codecRoundTrip() {
        CuboidRegion r = new CuboidRegion("nf", new Vec3(0, 0, 0), new Vec3(16, 60, 16));
        // Alternate deny/allow across the new state flags so we exercise both stored values.
        boolean deny = true;
        for (StateFlag f : NEW_STATE) {
            r.setFlag(f, deny ? StateFlag.State.DENY : StateFlag.State.ALLOW);
            deny = !deny;
        }
        r.setFlag(Flags.SPAWN_LIMIT, Set.of("minecraft:zombie:5", "creeper:2"));
        ProtectedRegion got = RegionJsonCodec.readRegion(
                r.id(), JsonParser.parseString(GSON.toJson(RegionJsonCodec.regionToJson(r))).getAsJsonObject(),
                new HashMap<>());
        deny = true;
        for (StateFlag f : NEW_STATE) {
            StateFlag.State exp = deny ? StateFlag.State.DENY : StateFlag.State.ALLOW;
            check("codec round-trip: " + f.name(), got.getFlag(f) == exp);
            deny = !deny;
        }
        Object caps = got.getFlag(Flags.SPAWN_LIMIT);
        check("codec round-trip spawn-limit is set", caps instanceof Set);
        if (caps instanceof Set<?> s) {
            check("codec spawn-limit zombie", s.contains("minecraft:zombie:5"));
            check("codec spawn-limit creeper", s.contains("creeper:2"));
        }
    }

    /* ---- live resolution: deny / priority / parent inheritance / region-group ---- */
    static void resolution() {
        // Per-flag: a region setting DENY resolves to deny at a contained point; unset → allow.
        for (StateFlag f : NEW_STATE) {
            RegionManager m = new RegionManager("w");
            CuboidRegion r = new CuboidRegion("r", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
            r.setFlag(f, StateFlag.State.DENY);
            m.add(r);
            check("resolve deny: " + f.name(), !m.testState(f, STRANGER, 10, 10, 10));
            check("resolve wilderness allow: " + f.name(), m.testState(f, STRANGER, 500, 10, 500));
        }
        // Priority: higher-priority ALLOW beats lower DENY (use glide).
        RegionManager mp = new RegionManager("w");
        CuboidRegion lo = new CuboidRegion("lo", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        CuboidRegion hi = new CuboidRegion("hi", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        lo.setPriority(0); hi.setPriority(10);
        lo.setFlag(Flags.GLIDE, StateFlag.State.DENY);
        hi.setFlag(Flags.GLIDE, StateFlag.State.ALLOW);
        mp.add(lo); mp.add(hi);
        check("priority: higher ALLOW wins (glide)", mp.testState(Flags.GLIDE, STRANGER, 10, 10, 10));
        // Same-tier DENY beats ALLOW.
        RegionManager mt = new RegionManager("w");
        CuboidRegion a = new CuboidRegion("a", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        CuboidRegion b = new CuboidRegion("b", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        a.setFlag(Flags.BUCKET_EMPTY, StateFlag.State.ALLOW);
        b.setFlag(Flags.BUCKET_EMPTY, StateFlag.State.DENY);
        mt.add(a); mt.add(b);
        check("same-tier DENY beats ALLOW (bucket-empty)", !mt.testState(Flags.BUCKET_EMPTY, STRANGER, 10, 10, 10));
        // Parent inheritance: child with no value inherits parent's DENY.
        RegionManager mi = new RegionManager("w");
        CuboidRegion parent = new CuboidRegion("parent", new Vec3(0, 0, 0), new Vec3(40, 40, 40));
        CuboidRegion child  = new CuboidRegion("child",  new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        parent.setFlag(Flags.VEHICLE_ENTER, StateFlag.State.DENY);
        child.setParent(parent); child.setPriority(5);
        mi.add(parent); mi.add(child);
        check("child inherits parent deny (vehicle-enter)", !mi.testState(Flags.VEHICLE_ENTER, STRANGER, 10, 10, 10));
        child.setFlag(Flags.VEHICLE_ENTER, StateFlag.State.ALLOW);
        check("child overrides parent (vehicle-enter)", mi.testState(Flags.VEHICLE_ENTER, STRANGER, 10, 10, 10));
        // Region-group: DENY only for non-members; owner still allowed.
        RegionManager mg = new RegionManager("w");
        CuboidRegion g = new CuboidRegion("g", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        g.owners().add(OWNER);
        g.setFlag(Flags.SIGN_EDIT, StateFlag.State.DENY);
        g.setFlagGroup(Flags.SIGN_EDIT, RegionGroup.NON_MEMBERS);
        mg.add(g);
        check("group: stranger denied (sign-edit)", !mg.testState(Flags.SIGN_EDIT, STRANGER, 10, 10, 10));
        check("group: owner allowed (sign-edit)", mg.testState(Flags.SIGN_EDIT, OWNER, 10, 10, 10));
    }

    /* ---- spawn-limit value resolution (set value flag) ---- */
    static void spawnLimit() {
        RegionManager m = new RegionManager("w");
        CuboidRegion r = new CuboidRegion("sl", new Vec3(0, 0, 0), new Vec3(20, 20, 20));
        r.setFlag(Flags.SPAWN_LIMIT, Set.of("minecraft:zombie:5", "creeper:2"));
        m.add(r);
        Object v = m.resolveValue(Flags.SPAWN_LIMIT, 10, 10, 10, null);
        check("spawn-limit resolves to set", v instanceof Set);
        if (v instanceof Set<?> s) check("spawn-limit resolved has zombie", s.contains("minecraft:zombie:5"));
        check("spawn-limit wilderness → null", m.resolveValue(Flags.SPAWN_LIMIT, 500, 10, 500, null) == null);
    }
}
