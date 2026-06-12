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
 * Soft-dependency Bluemap integration.
 *
 * <p>Connects to Bluemap via reflection if {@code bluemap} mod is loaded. Publishes one
 * marker per WorldGuardNeo region, grouped in a {@code MarkerSet} called "WorldGuardNeo".
 * The set is created per Bluemap-world; markers are {@code ShapeMarker}s (cuboid /
 * polygon footprint on the XZ plane).
 *
 * <p>Why reflection: we don't want to compile-link {@code bluemap-api} (would force
 * users to install it). Reflection lets us no-op gracefully when Bluemap is absent.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #init} — called once at server-start. Detects Bluemap, registers
 *       {@code onEnable}/{@code onDisable} callbacks via the API.</li>
 *   <li>{@link #publishAll} — called from the onEnable callback and from /rg reload.
 *       Walks every region in every world and creates/updates markers.</li>
 *   <li>{@link #updateRegion} / {@link #removeRegion} — called by command handlers
 *       after /rg claim, /rg flag, /rg remove etc. Incremental sync.</li>
 * </ul>
 *
 * <p>If Bluemap is absent, all methods become no-ops and {@code isActive()} returns false.
 *
 * <p>This class lives on the server thread. Bluemap's API is thread-safe per their docs,
 * but we still avoid background-thread mutation to keep things simple.
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
    /** Color(int r,int g,int b,float a). */
    private java.lang.reflect.Constructor<?> colorCtor;

    private final Map<String, Object> serverLevelToBmWorld = new HashMap<>();

    /** Set this to true if the {@code bluemap} mod is loaded. */
    public boolean isActive() { return active; }

    /**
     * One-time init. Resolves reflective handles, registers the onEnable/onDisable hooks.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    public static synchronized void init() {
        if (INSTANCE != null) return;
        INSTANCE = new BluemapIntegration();
        // Silent unless reflection fails — the "Detected integrations: ..." line in WorldGuardNeo
        // already reports presence. Avoids the per-integration "not present" console spam.
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
        // getWorld accepts the platform world object (ServerLevel) and returns Optional.
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
    }

    private void registerCallbacks() throws Exception {
        Consumer<Object> onEnable = api -> {
            try {
                WorldGuardNeo mod = WorldGuardNeo.get();
                if (mod == null) return;
                // Schedule a full republish — runs on whichever thread Bluemap invoked us;
                // we route through ServerLifecycleHooks to get back on the server thread.
                MinecraftServer srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (srv != null) srv.execute(this::publishAll);
            } catch (Throwable t) {
                WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Bluemap onEnable failed", t);
            }
        };
        Consumer<Object> onDisable = api -> serverLevelToBmWorld.clear();

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
                // Wipe old markers — easier than diff'ing.
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
            // remove old then add new — id stays same so this just replaces
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

        // We re-use the same MarkerSet across all maps of a world (Bluemap docs explicitly
        // suggest this). Cache by dimension key to avoid recreating the set; the same key is
        // stable across the server-runtime for a given world.
        String key = lvl.dimension().location().toString();
        Object markerSet = serverLevelToBmWorld.get(key);
        if (markerSet == null) {
            markerSet = markerSetCtor.newInstance("WorldGuardNeo");
            serverLevelToBmWorld.put(key, markerSet);
        }
        // Ensure the set is registered in every map of the world. Idempotent put() — if a
        // new map was loaded via Bluemap reload, this picks it up. Safe to repeat: Map.put
        // with the same key+value is a no-op write.
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
            // Use minBound.y as the display height. Bluemap renders the shape on a flat plane,
            // so we pick the lower bound to make the marker hug the terrain rather than float.
            Vec3 min = c.minimumBound();
            Vec3 max = c.maximumBound();
            displayY = min.y();
            Object[] corners = {
                vec2dCtor.newInstance((double) min.x(), (double) min.z()),
                vec2dCtor.newInstance((double) max.x() + 1.0, (double) min.z()),
                vec2dCtor.newInstance((double) max.x() + 1.0, (double) max.z() + 1.0),
                vec2dCtor.newInstance((double) min.x(), (double) max.z() + 1.0)
            };
            // Reflective array — Bluemap's Shape constructor takes a Vector2d[].
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
        markers.put(r.id(), marker);
    }
}
