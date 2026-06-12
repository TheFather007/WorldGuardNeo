package dev.dizzy.worldguardneo.integrations;

import dev.dizzy.worldguardneo.WorldGuardNeo;
import dev.dizzy.worldguardneo.region.CuboidRegion;
import dev.dizzy.worldguardneo.region.PolygonalRegion;
import dev.dizzy.worldguardneo.region.ProtectedRegion;
import dev.dizzy.worldguardneo.region.RegionManager;
import dev.dizzy.worldguardneo.util.Vec3;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.awt.Color;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Soft-dependency Squaremap integration.
 *
 * <p>Connects to Squaremap via reflection if {@code squaremap} mod is loaded. Publishes
 * one marker per WorldGuardNeo region into a {@code SimpleLayerProvider} called
 * "WorldGuardNeo". One layer per Squaremap-{@code MapWorld}.
 *
 * <p>Squaremap is fundamentally 2D — markers are drawn on the XZ plane regardless of
 * the region's Y range. For cuboid regions we draw a rectangle covering minX/minZ to
 * maxX/maxZ; for polygons we draw the polygon footprint; the global region is skipped
 * (no geometry).
 *
 * <p>API surface (from squaremap-api 1.3.x):
 * <ul>
 *   <li>{@code xyz.jpenilla.squaremap.api.SquaremapProvider.get()} → singleton</li>
 *   <li>{@code Squaremap.getWorldIfEnabled(WorldIdentifier)} → {@code Optional<MapWorld>}</li>
 *   <li>{@code MapWorld.layerRegistry()} → {@code Registry<LayerProvider>}</li>
 *   <li>{@code SimpleLayerProvider.builder(String name)} → builder → {@code build()}</li>
 *   <li>{@code SimpleLayerProvider.addMarker(Key, Marker)} / {@code removeMarker(Key)}</li>
 *   <li>{@code Marker.rectangle(Point, Point)}, {@code Marker.polygon(List<Point>)}</li>
 * </ul>
 *
 * <p>If Squaremap is absent, all methods are no-ops. Reflection failures are caught
 * and logged at debug-level; the server keeps running.
 */
public final class SquaremapIntegration {

    private static SquaremapIntegration INSTANCE;
    public static SquaremapIntegration get() { return INSTANCE; }

    private boolean active;

    /* Reflective handles — resolved once in init(). */
    private Method providerGet;
    private Method squaremapGetWorldIfEnabled;
    private Method worldIdentifierParse;          // WorldIdentifier.parse(String)
    private Method mapWorldLayerRegistry;
    private Method registryRegister;              // Registry.register(Key, LayerProvider)
    private Method registryHasEntry;              // Registry.hasEntry(Key) — null-safe lookup
    private Method registryEntry;                 // Registry.entry(Key) → Optional
    private Method simpleLayerBuilder;            // SimpleLayerProvider.builder(String)
    private Method builderShowControls, builderDefaultHidden;
    private Method builderBuild;
    private Method providerAddMarker;             // SimpleLayerProvider.addMarker(Key, Marker)
    private Method providerRemoveMarker;
    private Method markerRectangle;               // Marker.rectangle(Point, Point)
    private Method markerPolygon;                 // Marker.polygon(List<Point>)
    private Method pointOf;                       // Point.of(double, double)
    private Method keyOf;                         // Key.of(String) or Key.key(String)
    private Method optionalIsPresent, optionalGet;
    /* Marker styling — MarkerOptions builder. */
    private Method markerMarkerOptions;           // Marker.markerOptions(MarkerOptions)
    private Method markerOptionsBuilder;          // MarkerOptions.builder()
    private Method optionsStrokeColor, optionsFillColor, optionsStrokeWeight, optionsStrokeOpacity, optionsFillOpacity;
    private Method optionsBuilderBuild;

    /** Layer key used everywhere — same Key namespace for register/lookup. */
    private Object layerKey;

    /**
     * Per-dimension-id → SimpleLayerProvider cache. We re-use one layer per world rather
     * than recreating it on every update. Cleared on disable.
     */
    private final Map<String, Object> layerByWorld = new HashMap<>();

    public boolean isActive() { return active; }

    /** One-time init. Safe to call multiple times — subsequent calls no-op. */
    public static synchronized void init() {
        if (INSTANCE != null) return;
        INSTANCE = new SquaremapIntegration();
        // No "not present" / "active" log here — the single "Detected integrations: ..." line
        // in WorldGuardNeo already reports which integrations are live. We stay silent and only
        // warn on an actual reflection failure (a real problem worth surfacing).
        if (!ModList.get().isLoaded("squaremap")) {
            return;
        }
        try {
            INSTANCE.resolveReflection();
            INSTANCE.active = true;
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Squaremap detected but reflection failed; integration disabled.", t);
            INSTANCE.active = false;
        }
    }

    private void resolveReflection() throws Exception {
        Class<?> providerCls   = Class.forName("xyz.jpenilla.squaremap.api.SquaremapProvider");
        Class<?> squaremapCls  = Class.forName("xyz.jpenilla.squaremap.api.Squaremap");
        Class<?> worldIdCls    = Class.forName("xyz.jpenilla.squaremap.api.WorldIdentifier");
        Class<?> mapWorldCls   = Class.forName("xyz.jpenilla.squaremap.api.MapWorld");
        Class<?> registryCls   = Class.forName("xyz.jpenilla.squaremap.api.Registry");
        Class<?> layerProvCls  = Class.forName("xyz.jpenilla.squaremap.api.LayerProvider");
        Class<?> simpleLayerCls= Class.forName("xyz.jpenilla.squaremap.api.SimpleLayerProvider");
        Class<?> simpleBuilderCls = Class.forName("xyz.jpenilla.squaremap.api.SimpleLayerProvider$Builder");
        Class<?> markerCls     = Class.forName("xyz.jpenilla.squaremap.api.marker.Marker");
        Class<?> pointCls      = Class.forName("xyz.jpenilla.squaremap.api.Point");
        Class<?> keyCls        = Class.forName("xyz.jpenilla.squaremap.api.Key");
        Class<?> mOptsCls      = Class.forName("xyz.jpenilla.squaremap.api.marker.MarkerOptions");
        Class<?> mOptsBuilderCls = Class.forName("xyz.jpenilla.squaremap.api.marker.MarkerOptions$Builder");

        providerGet                  = providerCls.getMethod("get");
        squaremapGetWorldIfEnabled   = squaremapCls.getMethod("getWorldIfEnabled", worldIdCls);
        worldIdentifierParse         = worldIdCls.getMethod("parse", String.class);
        mapWorldLayerRegistry        = mapWorldCls.getMethod("layerRegistry");
        registryRegister             = registryCls.getMethod("register", keyCls, layerProvCls);
        registryHasEntry             = registryCls.getMethod("hasEntry", keyCls);
        registryEntry                = registryCls.getMethod("entry", keyCls);
        simpleLayerBuilder           = simpleLayerCls.getMethod("builder", String.class);
        builderShowControls          = simpleBuilderCls.getMethod("showControls", boolean.class);
        builderDefaultHidden         = simpleBuilderCls.getMethod("defaultHidden", boolean.class);
        builderBuild                 = simpleBuilderCls.getMethod("build");
        providerAddMarker            = simpleLayerCls.getMethod("addMarker", keyCls, markerCls);
        providerRemoveMarker         = simpleLayerCls.getMethod("removeMarker", keyCls);
        markerRectangle              = markerCls.getMethod("rectangle", pointCls, pointCls);
        markerPolygon                = markerCls.getMethod("polygon", List.class);
        pointOf                      = pointCls.getMethod("of", double.class, double.class);
        keyOf                        = keyCls.getMethod("of", String.class);
        optionalIsPresent            = java.util.Optional.class.getMethod("isPresent");
        optionalGet                  = java.util.Optional.class.getMethod("get");

        markerMarkerOptions          = markerCls.getMethod("markerOptions", mOptsCls);
        markerOptionsBuilder         = mOptsCls.getMethod("builder");
        optionsStrokeColor           = mOptsBuilderCls.getMethod("strokeColor", Color.class);
        optionsFillColor             = mOptsBuilderCls.getMethod("fillColor",   Color.class);
        optionsStrokeWeight          = mOptsBuilderCls.getMethod("strokeWeight", int.class);
        optionsStrokeOpacity         = mOptsBuilderCls.getMethod("strokeOpacity", double.class);
        optionsFillOpacity           = mOptsBuilderCls.getMethod("fillOpacity", double.class);
        optionsBuilderBuild          = mOptsBuilderCls.getMethod("build");

        // Single shared Key for the layer registry — same identifier per world.
        layerKey = keyOf.invoke(null, "worldguardneo");
    }

    /* ----------------- Public mutation API ----------------- */

    /** Republish every region in every loaded world. Called from /rg reload and from /rg server-started. */
    public void publishAll() {
        if (!active) return;
        try {
            MinecraftServer srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null) return;
            WorldGuardNeo mod = WorldGuardNeo.get();
            if (mod == null) return;
            for (ServerLevel lvl : srv.getAllLevels()) {
                RegionManager mgr = mod.regions().get(lvl);
                Object layer = ensureLayer(lvl);
                if (layer == null) continue;
                // We rebuild markers per region. Squaremap's SimpleLayerProvider.addMarker
                // overwrites markers with the same key, so this acts as full re-sync.
                for (ProtectedRegion r : mgr.all()) {
                    putMarker(layer, r);
                }
            }
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("Squaremap publishAll failed", t);
        }
    }

    public void updateRegion(ServerLevel lvl, ProtectedRegion r) {
        if (!active) return;
        try {
            Object layer = ensureLayer(lvl);
            if (layer == null) return;
            putMarker(layer, r);
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("Squaremap updateRegion failed for {}", r.id(), t);
        }
    }

    public void removeRegion(ServerLevel lvl, String regionId) {
        if (!active) return;
        try {
            Object layer = ensureLayer(lvl);
            if (layer == null) return;
            Object markerKey = keyOf.invoke(null, sanitizeKey("wgn-" + regionId));
            providerRemoveMarker.invoke(layer, markerKey);
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("Squaremap removeRegion failed for {}", regionId, t);
        }
    }

    /* ----------------- internals ----------------- */

    /** Get-or-create the SimpleLayerProvider for the given server level. */
    private Object ensureLayer(ServerLevel lvl) throws Exception {
        String dimId = lvl.dimension().location().toString();
        Object cached = layerByWorld.get(dimId);
        if (cached != null) return cached;

        Object squaremap = providerGet.invoke(null);
        if (squaremap == null) return null;
        Object worldId = worldIdentifierParse.invoke(null, dimId);
        Object mapWorldOpt = squaremapGetWorldIfEnabled.invoke(squaremap, worldId);
        if (!(boolean) optionalIsPresent.invoke(mapWorldOpt)) return null;  // world disabled
        Object mapWorld = optionalGet.invoke(mapWorldOpt);
        Object registry = mapWorldLayerRegistry.invoke(mapWorld);

        // If layer already exists (e.g. server reload), reuse it. Otherwise build+register.
        Object layer;
        boolean has = (boolean) registryHasEntry.invoke(registry, layerKey);
        if (has) {
            Object entryOpt = registryEntry.invoke(registry, layerKey);
            if (!(boolean) optionalIsPresent.invoke(entryOpt)) return null;
            layer = optionalGet.invoke(entryOpt);
        } else {
            Object builder = simpleLayerBuilder.invoke(null, "WorldGuardNeo");
            builderShowControls.invoke(builder, true);
            builderDefaultHidden.invoke(builder, false);
            layer = builderBuild.invoke(builder);
            registryRegister.invoke(registry, layerKey, layer);
        }
        layerByWorld.put(dimId, layer);
        return layer;
    }

    /** Build and put the marker for the region. */
    private void putMarker(Object layer, ProtectedRegion r) throws Exception {
        Object marker;
        if (r instanceof CuboidRegion c) {
            Vec3 min = c.minimumBound();
            Vec3 max = c.maximumBound();
            // Add 1 to max so the rectangle covers the full block range — block (10,10,10)
            // really extends from (10,10,10) to (11,11,11) in world coords.
            Object p1 = pointOf.invoke(null, (double) min.x(),        (double) min.z());
            Object p2 = pointOf.invoke(null, (double) max.x() + 1.0,  (double) max.z() + 1.0);
            marker = markerRectangle.invoke(null, p1, p2);
        } else if (r instanceof PolygonalRegion poly) {
            var pts = poly.points();
            List<Object> points = new ArrayList<>(pts.size());
            for (var p : pts) {
                points.add(pointOf.invoke(null, (double) p.x(), (double) p.z()));
            }
            marker = markerPolygon.invoke(null, points);
        } else {
            return; // global region — nothing to render on a 2D map
        }

        // Build MarkerOptions: blue stroke + semi-transparent blue fill.
        Object optsBuilder = markerOptionsBuilder.invoke(null);
        Color blue = new Color(80, 130, 220);
        optionsStrokeColor.invoke(optsBuilder, blue);
        optionsFillColor.invoke(optsBuilder, blue);
        optionsStrokeWeight.invoke(optsBuilder, 2);
        optionsStrokeOpacity.invoke(optsBuilder, 1.0d);
        optionsFillOpacity.invoke(optsBuilder, 0.25d);
        Object opts = optionsBuilderBuild.invoke(optsBuilder);
        markerMarkerOptions.invoke(marker, opts);

        Object markerKey = keyOf.invoke(null, sanitizeKey("wgn-" + r.id()));
        providerAddMarker.invoke(layer, markerKey, marker);
    }

    /**
     * Sanitize a string for use as a Squaremap {@code Key}. Squaremap keys are restricted
     * to the [a-z0-9/._-] character set per the Adventure Key spec they use internally;
     * region ids in WGN can be any word, so we lowercase and replace disallowed chars
     * with underscore to avoid IllegalArgumentException from Key.of().
     */
    private static String sanitizeKey(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '.' || ch == '/') {
                out.append(ch);
            } else if (ch >= 'A' && ch <= 'Z') {
                out.append((char)(ch + 32));
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }
}
