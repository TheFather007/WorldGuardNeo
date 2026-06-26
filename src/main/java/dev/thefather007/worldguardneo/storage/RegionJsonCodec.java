package dev.thefather007.worldguardneo.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.flags.Flag;
import dev.thefather007.worldguardneo.flags.Flags;
import dev.thefather007.worldguardneo.region.CuboidRegion;
import dev.thefather007.worldguardneo.region.PolygonalRegion;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.region.RegionGroup;
import dev.thefather007.worldguardneo.region.RegionManager;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure-in-memory codec between {@link RegionManager} and a JSON document, so all backends share one
 * on-the-wire shape without duplicating parser/writer code. No I/O here.
 *
 * <p>Robustness: malformed entries are logged and skipped rather than aborting the load, so one
 * corrupt region doesn't erase the others.
 */
public final class RegionJsonCodec {

    private RegionJsonCodec() {}

    /* ------------------ to JSON ------------------ */

    public static JsonObject toJson(RegionManager from) {
        JsonObject root = new JsonObject();
        JsonObject regions = new JsonObject();
        for (ProtectedRegion r : from.all()) {
            regions.add(r.id(), writeRegion(r));
        }
        root.add("regions", regions);
        root.add("global", writeFlagsOnly(from.globalRegion()));
        return root;
    }

    /* ------------------ from JSON ------------------ */

    public static void applyJson(JsonObject root, RegionManager into) {
        JsonObject regions = root.has("regions") && root.get("regions").isJsonObject()
                ? root.getAsJsonObject("regions") : null;
        Map<String, String> parentDeferred = new HashMap<>();
        if (regions != null) {
            for (Map.Entry<String, JsonElement> e : regions.entrySet()) {
                String id = e.getKey();
                if (!e.getValue().isJsonObject()) {
                    WorldGuardNeo.LOGGER.warn("Skipping malformed region entry '{}' (not an object)", id);
                    continue;
                }
                JsonObject obj = e.getValue().getAsJsonObject();
                ProtectedRegion region;
                try {
                    region = parseRegion(id, obj);
                } catch (Exception ex) {
                    WorldGuardNeo.LOGGER.warn("Skipping region '{}' due to parse error", id, ex);
                    continue;
                }
                if (region == null) continue;
                if (obj.has("priority") && obj.get("priority").isJsonPrimitive()) {
                    try { region.setPriority(obj.get("priority").getAsInt()); }
                    catch (Exception ignored) {}
                }
                if (obj.has("parent") && obj.get("parent").isJsonPrimitive()) {
                    parentDeferred.put(id, obj.get("parent").getAsString());
                }
                fillOwnersMembers(region, obj);
                fillFlags(region, obj);
                fillMetadata(region, obj);
                into.add(region);
            }
            // Resolve parent links once all regions exist.
            for (Map.Entry<String, String> e : parentDeferred.entrySet()) {
                var child  = into.get(e.getKey()).orElse(null);
                var parent = into.get(e.getValue()).orElse(null);
                if (child == null || parent == null) {
                    WorldGuardNeo.LOGGER.warn(
                            "Dropping parent link {} → {}: target missing", e.getKey(), e.getValue());
                    continue;
                }
                try { child.setParent(parent); }
                catch (IllegalStateException ex) {
                    WorldGuardNeo.LOGGER.warn(
                            "Dropping parent link {} → {}: would create a cycle", e.getKey(), e.getValue());
                }
            }
        }
        if (root.has("global") && root.get("global").isJsonObject()) {
            fillFlags(into.globalRegion(), root.getAsJsonObject("global"));
        }
    }

    /* ------------------ per-region (incremental storage) ------------------ */

    /** JSON for a single region — identical shape to one entry of the whole-world document. */
    public static JsonObject regionToJson(ProtectedRegion r) { return writeRegion(r); }

    /** JSON holding just the global region's flags (the per-world {@code __global__} row). */
    public static JsonObject globalToJson(ProtectedRegion global) { return writeFlagsOnly(global); }

    /**
     * Parse one region from its JSON (geometry, priority, owners/members, flags). The parent link
     * is deferred into {@code parentOut} (childId → parentId) so the caller resolves it via
     * {@link #linkParents} once all regions are loaded. Returns null on unusable JSON (logged).
     */
    public static ProtectedRegion readRegion(String id, JsonObject obj, Map<String, String> parentOut) {
        ProtectedRegion region;
        try {
            region = parseRegion(id, obj);
        } catch (Exception ex) {
            WorldGuardNeo.LOGGER.warn("Skipping region '{}' due to parse error", id, ex);
            return null;
        }
        if (region == null) return null;
        if (obj.has("priority") && obj.get("priority").isJsonPrimitive()) {
            try { region.setPriority(obj.get("priority").getAsInt()); } catch (Exception ignored) {}
        }
        if (obj.has("parent") && obj.get("parent").isJsonPrimitive()) {
            parentOut.put(id, obj.get("parent").getAsString());
        }
        fillOwnersMembers(region, obj);
        fillFlags(region, obj);
        fillMetadata(region, obj);
        return region;
    }

    /** Apply a per-row {@code __global__} flags payload onto the manager's global region. */
    public static void applyGlobalJson(JsonObject obj, RegionManager into) {
        fillFlags(into.globalRegion(), obj);
    }

    /** Resolve deferred parent links collected by {@link #readRegion}. */
    public static void linkParents(Map<String, String> parentDeferred, RegionManager into) {
        for (Map.Entry<String, String> e : parentDeferred.entrySet()) {
            var child  = into.get(e.getKey()).orElse(null);
            var parent = into.get(e.getValue()).orElse(null);
            if (child == null || parent == null) {
                WorldGuardNeo.LOGGER.warn("Dropping parent link {} → {}: target missing", e.getKey(), e.getValue());
                continue;
            }
            try { child.setParent(parent); }
            catch (IllegalStateException ex) {
                WorldGuardNeo.LOGGER.warn("Dropping parent link {} → {}: would create a cycle", e.getKey(), e.getValue());
            }
        }
    }

    /* ------------------ parse helpers ------------------ */

    private static ProtectedRegion parseRegion(String id, JsonObject obj) {
        String type = obj.has("type") ? obj.get("type").getAsString() : "cuboid";
        return switch (type) {
            case "cuboid" -> {
                if (!obj.has("min") || !obj.has("max")) {
                    WorldGuardNeo.LOGGER.warn("Cuboid '{}' missing min/max — skipping", id);
                    yield null;
                }
                Vec3 a = readVec(obj.getAsJsonObject("min"));
                Vec3 b = readVec(obj.getAsJsonObject("max"));
                yield new CuboidRegion(id, a, b);
            }
            case "polygonal" -> {
                if (!obj.has("points") || !obj.has("min-y") || !obj.has("max-y")) {
                    WorldGuardNeo.LOGGER.warn("Polygonal '{}' missing required keys — skipping", id);
                    yield null;
                }
                JsonArray pa = obj.getAsJsonArray("points");
                List<PolygonalRegion.Point2> pts = new ArrayList<>(pa.size());
                for (JsonElement pe : pa) {
                    // Skip a malformed point rather than dropping the whole region; the <3 guard below
                    // still rejects degenerate polygons.
                    try {
                        JsonObject po = pe.getAsJsonObject();
                        pts.add(new PolygonalRegion.Point2(po.get("x").getAsInt(), po.get("z").getAsInt()));
                    } catch (Exception ex) {
                        WorldGuardNeo.LOGGER.warn("Polygonal '{}' has a malformed point — skipping that point", id);
                    }
                }
                if (pts.size() < 3) {
                    WorldGuardNeo.LOGGER.warn("Polygonal '{}' has fewer than 3 valid points — skipping", id);
                    yield null;
                }
                int minY = obj.get("min-y").getAsInt();
                int maxY = obj.get("max-y").getAsInt();
                yield new PolygonalRegion(id, pts, minY, maxY);
            }
            default -> {
                WorldGuardNeo.LOGGER.warn("Unknown region type '{}' for '{}' — skipping", type, id);
                yield null;
            }
        };
    }

    private static Vec3 readVec(JsonObject o) {
        return new Vec3(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
    }

    private static void fillOwnersMembers(ProtectedRegion r, JsonObject obj) {
        // Only call the lazy-allocating getters if there's data to load, so empty regions keep their
        // collections null (saves ~4 collections per region on large servers).
        if (hasArray(obj, "owners"))        addUuids(obj,   "owners",        r.owners());
        if (hasArray(obj, "owner-groups"))  addStrings(obj, "owner-groups",  r.ownerGroups());
        if (hasArray(obj, "members"))       addUuids(obj,   "members",       r.members());
        if (hasArray(obj, "member-groups")) addStrings(obj, "member-groups", r.memberGroups());
    }

    /** Restore lifecycle metadata. Must run AFTER fillFlags/fillOwnersMembers/setPriority, since
     *  those touch modifiedAt; here we overwrite it with the persisted value. Missing keys are left
     *  at their constructor defaults (now), which is the right behaviour for legacy save files. */
    private static void fillMetadata(ProtectedRegion r, JsonObject obj) {
        if (obj.has("created-at") && obj.get("created-at").isJsonPrimitive()) {
            try { r.setCreatedAt(obj.get("created-at").getAsLong()); } catch (Exception ignored) {}
        }
        if (obj.has("modified-at") && obj.get("modified-at").isJsonPrimitive()) {
            try { r.setModifiedAt(obj.get("modified-at").getAsLong()); } catch (Exception ignored) {}
        }
        if (obj.has("created-by") && obj.get("created-by").isJsonPrimitive()) {
            try { r.setCreatedBy(UUID.fromString(obj.get("created-by").getAsString())); }
            catch (Exception ex) { WorldGuardNeo.LOGGER.warn("Invalid created-by UUID on '{}'", r.id()); }
        }
    }

    private static boolean hasArray(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonArray() && obj.getAsJsonArray(key).size() > 0;
    }

    private static void addUuids(JsonObject obj, String key, java.util.Set<UUID> out) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return;
        for (JsonElement e : obj.getAsJsonArray(key)) {
            try { out.add(UUID.fromString(e.getAsString())); }
            catch (Exception ex) {
                WorldGuardNeo.LOGGER.warn("Skipping invalid UUID in {}: {}", key, e);
            }
        }
    }

    private static void addStrings(JsonObject obj, String key, java.util.Set<String> out) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return;
        for (JsonElement e : obj.getAsJsonArray(key)) {
            try { out.add(e.getAsString()); }
            catch (Exception ignored) {}
        }
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static void fillFlags(ProtectedRegion r, JsonObject obj) {
        if (!obj.has("flags") || !obj.get("flags").isJsonObject()) return;
        JsonObject f = obj.getAsJsonObject("flags");
        for (Map.Entry<String, JsonElement> e : f.entrySet()) {
            Flag flag = Flags.get(e.getKey());
            if (flag == null) continue;
            try {
                JsonElement value = e.getValue();
                JsonElement actual = value;
                RegionGroup group = RegionGroup.ALL;
                if (value.isJsonObject() && value.getAsJsonObject().has("value")) {
                    JsonObject vo = value.getAsJsonObject();
                    actual = vo.get("value");
                    if (vo.has("group")) group = RegionGroup.parse(vo.get("group").getAsString());
                }
                Object parsed = flag.fromJson(actual);
                r.setFlag(flag, parsed);
                r.setFlagGroup(flag, group);
            } catch (Exception ex) {
                WorldGuardNeo.LOGGER.warn("Skipping malformed flag '{}' on region '{}'",
                        e.getKey(), r.id(), ex);
            }
        }
    }

    /* ------------------ write helpers ------------------ */

    @SuppressWarnings({"unchecked","rawtypes"})
    private static JsonObject writeRegion(ProtectedRegion r) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", r.type());
        obj.addProperty("priority", r.priority());
        if (r.parent() != null) obj.addProperty("parent", r.parent().id());

        if (r instanceof CuboidRegion c) {
            obj.add("min", writeVec(c.minimumBound()));
            obj.add("max", writeVec(c.maximumBound()));
        } else if (r instanceof PolygonalRegion p) {
            JsonArray pts = new JsonArray(p.points().size());
            for (PolygonalRegion.Point2 pt : p.points()) {
                JsonObject po = new JsonObject();
                po.addProperty("x", pt.x()); po.addProperty("z", pt.z());
                pts.add(po);
            }
            obj.add("points", pts);
            obj.addProperty("min-y", p.minY());
            obj.addProperty("max-y", p.maxY());
        }

        // *View() reads return emptySet() for null underlying sets — no empty-collection allocation
        // for regions with no owners/members.
        writeUuidArray(obj, "owners",        r.ownersView());
        writeStringArray(obj, "owner-groups", r.ownerGroupsView());
        writeUuidArray(obj, "members",       r.membersView());
        writeStringArray(obj, "member-groups", r.memberGroupsView());

        obj.add("flags", writeFlags(r));

        // Lifecycle metadata. createdBy is omitted when unknown (legacy/console-created regions).
        obj.addProperty("created-at",  r.createdAt());
        obj.addProperty("modified-at", r.modifiedAt());
        if (r.createdBy() != null) obj.addProperty("created-by", r.createdBy().toString());
        return obj;
    }

    private static void writeUuidArray(JsonObject obj, String key, java.util.Set<UUID> set) {
        JsonArray arr = new JsonArray(set.size());
        for (UUID u : set) arr.add(u.toString());
        obj.add(key, arr);
    }

    private static void writeStringArray(JsonObject obj, String key, java.util.Set<String> set) {
        JsonArray arr = new JsonArray(set.size());
        for (String s : set) arr.add(s);
        obj.add(key, arr);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static JsonObject writeFlags(ProtectedRegion r) {
        JsonObject flags = new JsonObject();
        for (Map.Entry<Flag<?>, Object> e : r.flagsRaw().entrySet()) {
            Flag flag = e.getKey();
            JsonElement raw = flag.toJson(e.getValue());
            if (raw == null) continue;
            RegionGroup grp = r.getFlagGroup(flag);
            if (grp != null && grp != RegionGroup.ALL) {
                JsonObject wrap = new JsonObject();
                wrap.add("value", raw);
                wrap.addProperty("group", grp.name());
                flags.add(flag.name(), wrap);
            } else {
                flags.add(flag.name(), raw);
            }
        }
        return flags;
    }

    private static JsonObject writeFlagsOnly(ProtectedRegion r) {
        JsonObject obj = new JsonObject();
        obj.add("flags", writeFlags(r));
        return obj;
    }

    private static JsonObject writeVec(Vec3 v) {
        JsonObject o = new JsonObject();
        o.addProperty("x", v.x()); o.addProperty("y", v.y()); o.addProperty("z", v.z());
        return o;
    }
}
