package dev.thefather007.worldguardneo.integrations;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.region.CuboidRegion;
import dev.thefather007.worldguardneo.region.PolygonalRegion;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.region.RegionManager;
import dev.thefather007.worldguardneo.util.Vec3;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Soft-dependency Bluemap integration. Publishes one {@code ShapeMarker} per region (XZ footprint)
 * into a per-world {@code MarkerSet} named "WorldGuardNeo". Uses reflection so we don't compile-link
 * {@code bluemap-api}: when Bluemap is absent every method no-ops and {@code isActive()} is false.
 *
 * <p>Lifecycle: {@link #init} registers the API onEnable/onDisable callbacks; {@link #publishAll}
 * does a full re-sync; {@link #updateRegion}/{@link #removeRegion} do incremental sync. All access
 * is on the server thread.
 */
public final class BluemapIntegration {

    /** Single instance per WGN. Initialized in {@link #init}. */
    private static BluemapIntegration INSTANCE;
    public static BluemapIntegration get() { return INSTANCE; }

    private boolean active;
    /** Reflective handle to {@code BlueMapAPI.getInstance()} → Optional. */
    private Method getInstance;
    private Method optionalGet, optionalIsPresent;
    private Method apiGetWorld;     // (Object) -> Optional<BlueMapWorld>
    private Method apiOnEnable;     // (Consumer) -> void
    private Method apiOnDisable;    // (Consumer) -> void
    private Method bmWorldGetMaps;  // () -> Collection<BlueMapMap>
    private Method bmMapGetMarkerSets; // () -> Map<String, MarkerSet>
    /** Constructor of MarkerSet(String label). */
    private java.lang.reflect.Constructor<?> markerSetCtor;
    /** Constructor of ShapeMarker(String label, Shape shape, float y). */
    private java.lang.reflect.Constructor<?> shapeMarkerCtor;
    /** Constructor of Shape from a list of Vector2d. */
    private java.lang.reflect.Constructor<?> shapeCtor; // Shape(Vector2d[])
    private java.lang.reflect.Constructor<?> vec2dCtor; // Vector2d(double, double)
    /** Marker setters. */
    private Method markerSetMarkers; // MarkerSet.getMarkers() -> Map<String,Marker>
    private Method markerSetFillColor, markerSetLineColor, markerSetLineWidth;
    /** Popup: label (title) + detail (HTML shown on click). */
    private Method markerSetLabel, markerSetDetail;
    /** Color(int r,int g,int b,float a). */
    private java.lang.reflect.Constructor<?> colorCtor;

    private final Map<String, Object> serverLevelToBmWorld = new HashMap<>();

    public boolean isActive() { return active; }

    /** One-time init: resolve reflective handles, register onEnable/onDisable. Safe to repeat. */
    public static synchronized void init() {
        if (INSTANCE != null) return;
        INSTANCE = new BluemapIntegration();
        // Silent unless reflection fails — presence is reported by WGN's "Detected integrations" line.
        if (!ModList.get().isLoaded("bluemap")) {
            return;
        }
        try {
            INSTANCE.resolveReflection();
            INSTANCE.registerCallbacks();
            INSTANCE.active = true;
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Bluemap detected but reflection failed; integration disabled.", t);
            INSTANCE.active = false;
        }
    }

    private void resolveReflection() throws Exception {
        Class<?> apiCls   = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI");
        Class<?> worldCls = Class.forName("de.bluecolored.bluemap.api.BlueMapWorld");
        Class<?> mapCls   = Class.forName("de.bluecolored.bluemap.api.BlueMapMap");
        Class<?> msCls    = Class.forName("de.bluecolored.bluemap.api.markers.MarkerSet");
        Class<?> smCls    = Class.forName("de.bluecolored.bluemap.api.markers.ShapeMarker");
        Class<?> shapeCls = Class.forName("de.bluecolored.bluemap.api.math.Shape");
        Class<?> vec2dCls = Class.forName("com.flowpowered.math.vector.Vector2d");
        Class<?> colorCls = Class.forName("de.bluecolored.bluemap.api.math.Color");

        getInstance        = apiCls.getMethod("getInstance");
        optionalGet        = java.util.Optional.class.getMethod("get");
        optionalIsPresent  = java.util.Optional.class.getMethod("isPresent");
        apiGetWorld        = apiCls.getMethod("getWorld", Object.class);
        apiOnEnable        = apiCls.getMethod("onEnable", Consumer.class);
        apiOnDisable       = apiCls.getMethod("onDisable", Consumer.class);
        bmWorldGetMaps     = worldCls.getMethod("getMaps");
        bmMapGetMarkerSets = mapCls.getMethod("getMarkerSets");

        markerSetCtor      = msCls.getConstructor(String.class);
        shapeMarkerCtor    = smCls.getConstructor(String.class, shapeCls, float.class);
        shapeCtor          = shapeCls.getConstructor(vec2dCls.arrayType());
        vec2dCtor          = vec2dCls.getConstructor(double.class, double.class);
        colorCtor          = colorCls.getConstructor(int.class, int.class, int.class, float.class);
        markerSetMarkers   = msCls.getMethod("getMarkers");
        markerSetFillColor = smCls.getMethod("setFillColor", colorCls);
        markerSetLineColor = smCls.getMethod("setLineColor", colorCls);
        markerSetLineWidth = smCls.getMethod("setLineWidth", int.class);
        markerSetLabel     = smCls.getMethod("setLabel", String.class);
        markerSetDetail    = smCls.getMethod("setDetail", String.class);
    }

    private void registerCallbacks() throws Exception {
        Consumer<Object> onEnable = api -> {
            try {
                WorldGuardNeo mod = WorldGuardNeo.get();
                if (mod == null) return;
                // Route back onto the server thread — Bluemap may invoke us from any thread.
                MinecraftServer srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (srv != null) srv.execute(this::publishAll);
            } catch (Throwable t) {
                WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Bluemap onEnable failed", t);
            }
        };
        // Bluemap fires onDisable from its own thread too; route the clear back onto the server
        // thread so it can't race a concurrent ensureMarkerSet/publishAll on the (non-thread-safe)
        // serverLevelToBmWorld map.
        Consumer<Object> onDisable = api -> {
            MinecraftServer srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv != null) srv.execute(serverLevelToBmWorld::clear);
            else serverLevelToBmWorld.clear();
        };

        apiOnEnable.invoke(null, onEnable);
        apiOnDisable.invoke(null, onDisable);
    }

    /* ----------------- Public mutation API ----------------- */

    /** Republish every region in every loaded world. Called from /rg reload too. */
    public void publishAll() {
        if (!active) return;
        try {
            MinecraftServer srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null) return;
            WorldGuardNeo mod = WorldGuardNeo.get();
            if (mod == null) return;
            for (ServerLevel lvl : srv.getAllLevels()) {
                RegionManager mgr = mod.regions().get(lvl);
                Object markerSet = ensureMarkerSet(lvl);
                if (markerSet == null) continue;
                // Wipe and rebuild — simpler than diffing.
                @SuppressWarnings("unchecked")
                Map<String,Object> markers = (Map<String,Object>) markerSetMarkers.invoke(markerSet);
                markers.clear();
                for (ProtectedRegion r : mgr.all()) {
                    addMarker(markers, r);
                }
            }
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("Bluemap publishAll failed", t);
        }
    }

    /** Incremental: add or replace a single region's marker. */
    public void updateRegion(ServerLevel lvl, ProtectedRegion r) {
        if (!active) return;
        try {
            Object markerSet = ensureMarkerSet(lvl);
            if (markerSet == null) return;
            @SuppressWarnings("unchecked")
            Map<String,Object> markers = (Map<String,Object>) markerSetMarkers.invoke(markerSet);
            // Same id, so remove+add just replaces.
            markers.remove(r.id());
            addMarker(markers, r);
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("Bluemap updateRegion failed for {}", r.id(), t);
        }
    }

    /** Incremental: remove a region's marker. */
    public void removeRegion(ServerLevel lvl, String regionId) {
        if (!active) return;
        try {
            Object markerSet = ensureMarkerSet(lvl);
            if (markerSet == null) return;
            @SuppressWarnings("unchecked")
            Map<String,Object> markers = (Map<String,Object>) markerSetMarkers.invoke(markerSet);
            markers.remove(regionId);
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("Bluemap removeRegion failed for {}", regionId, t);
        }
    }

    /* ----------------- internals ----------------- */

    /** Get-or-create the MarkerSet for a server level. Null if Bluemap doesn't know the world. */
    private Object ensureMarkerSet(ServerLevel lvl) throws Exception {
        Object apiOpt = getInstance.invoke(null);
        if (!(boolean) optionalIsPresent.invoke(apiOpt)) return null;
        Object api = optionalGet.invoke(apiOpt);
        Object worldOpt = apiGetWorld.invoke(api, lvl);
        if (!(boolean) optionalIsPresent.invoke(worldOpt)) return null;
        Object bmWorld = optionalGet.invoke(worldOpt);

        // One MarkerSet re-used across all maps of a world, cached by dimension key.
        String key = lvl.dimension().location().toString();
        Object markerSet = serverLevelToBmWorld.get(key);
        if (markerSet == null) {
            markerSet = markerSetCtor.newInstance("WorldGuardNeo");
            serverLevelToBmWorld.put(key, markerSet);
        }
        // Idempotent register into every map — picks up maps added by a Bluemap reload.
        Iterable<?> maps = (Iterable<?>) bmWorldGetMaps.invoke(bmWorld);
        for (Object map : maps) {
            @SuppressWarnings("unchecked")
            Map<String,Object> sets = (Map<String,Object>) bmMapGetMarkerSets.invoke(map);
            sets.put("worldguardneo", markerSet);
        }
        return markerSet;
    }

    /** Build a ShapeMarker for the region and put it in the markers map. */
    private void addMarker(Map<String,Object> markers, ProtectedRegion r) throws Exception {
        Object shape;
        float displayY;
        if (r instanceof CuboidRegion c) {
            // Display at the lower Y bound so the flat marker hugs the terrain rather than floats.
            Vec3 min = c.minimumBound();
            Vec3 max = c.maximumBound();
            displayY = min.y();
            Object[] corners = {
                vec2dCtor.newInstance((double) min.x(), (double) min.z()),
                vec2dCtor.newInstance((double) max.x() + 1.0, (double) min.z()),
                vec2dCtor.newInstance((double) max.x() + 1.0, (double) max.z() + 1.0),
                vec2dCtor.newInstance((double) min.x(), (double) max.z() + 1.0)
            };
            // Shape constructor takes a Vector2d[].
            Object arr = java.lang.reflect.Array.newInstance(
                    Class.forName("com.flowpowered.math.vector.Vector2d"), corners.length);
            for (int i = 0; i < corners.length; i++) java.lang.reflect.Array.set(arr, i, corners[i]);
            shape = shapeCtor.newInstance(arr);
        } else if (r instanceof PolygonalRegion poly) {
            displayY = poly.minY();
            var points = poly.points();
            Object arr = java.lang.reflect.Array.newInstance(
                    Class.forName("com.flowpowered.math.vector.Vector2d"), points.size());
            for (int i = 0; i < points.size(); i++) {
                var p = points.get(i);
                java.lang.reflect.Array.set(arr, i, vec2dCtor.newInstance((double) p.x(), (double) p.z()));
            }
            shape = shapeCtor.newInstance(arr);
        } else {
            // Global region — no geometry to render on a map.
            return;
        }
        Object marker = shapeMarkerCtor.newInstance(r.id(), shape, displayY);
        // Fill: semi-transparent blue; line: solid blue, 2px.
        Object fillColor = colorCtor.newInstance(80, 130, 220, 0.25f);
        Object lineColor = colorCtor.newInstance(80, 130, 220, 1.0f);
        markerSetFillColor.invoke(marker, fillColor);
        markerSetLineColor.invoke(marker, lineColor);
        markerSetLineWidth.invoke(marker, 2);
        // Click-popup: region id as the title, owners + flags as the detail body.
        markerSetLabel.invoke(marker, r.id());
        markerSetDetail.invoke(marker, MarkerPopup.html(r));
        markers.put(r.id(), marker);
    }
}
