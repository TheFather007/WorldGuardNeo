package dev.dizzy.worldguardneo.worldedit;

import dev.dizzy.worldguardneo.WorldGuardNeo;
import dev.dizzy.worldguardneo.region.CuboidRegion;
import dev.dizzy.worldguardneo.region.PolygonalRegion;
import dev.dizzy.worldguardneo.region.ProtectedRegion;
import dev.dizzy.worldguardneo.util.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bridge to WorldEdit. WorldEdit is a REQUIRED dependency (declared in neoforge.mods.toml),
 * so regions are created exclusively from a WorldEdit selection made with {@code //wand},
 * {@code //pos1}/{@code //pos2}, {@code //sel poly}, etc. There is no built-in wand or
 * selection store any more.
 *
 * <p>Everything here is reflective so the mod still compiles without WE on the classpath and
 * degrades gracefully (logs, returns empty) in the unlikely event WE fails to load at runtime
 * despite the hard dependency.
 */
public abstract class WorldEditAdapter {

    public static WorldEditAdapter detect() {
        if (ModList.get().isLoaded("worldedit")) {
            try {
                ReflectiveWorldEditAdapter r = new ReflectiveWorldEditAdapter();
                WorldGuardNeo.LOGGER.info("[WorldGuardNeo] WorldEdit reflective bridge bound.");
                return r;
            } catch (Throwable t) {
                WorldGuardNeo.LOGGER.error(
                        "[WorldGuardNeo] WorldEdit is present but the reflective bridge failed to bind. "
                      + "Region selection will not work until this is resolved.", t);
            }
        } else {
            WorldGuardNeo.LOGGER.error(
                    "[WorldGuardNeo] WorldEdit is not loaded, but it is a required dependency. "
                  + "Region selection is unavailable.");
        }
        return new NoOpWorldEditAdapter();
    }

    /** Build a region of the given id from the player's current WorldEdit selection. */
    public abstract Optional<ProtectedRegion> toProtectedRegion(ServerPlayer player, String id);

    /** Replace the player's WorldEdit selection with the given cuboid (used by /rg select). */
    public abstract void selectCuboid(ServerPlayer player, Vec3 min, Vec3 max);

    /** Replace the player's WorldEdit selection with the given polygon (used by /rg select). */
    public abstract void selectPolygon(ServerPlayer player, List<PolygonalRegion.Point2> points,
                                       int minY, int maxY);

    /* --------------- no-op used only if WE somehow isn't available --------------- */
    public static final class NoOpWorldEditAdapter extends WorldEditAdapter {
        @Override public Optional<ProtectedRegion> toProtectedRegion(ServerPlayer player, String id) {
            return Optional.empty();
        }
        @Override public void selectCuboid(ServerPlayer player, Vec3 min, Vec3 max) { }
        @Override public void selectPolygon(ServerPlayer player, List<PolygonalRegion.Point2> points,
                                            int minY, int maxY) { }
    }

    /* --------------- reflective WE binding --------------- */
    public static final class ReflectiveWorldEditAdapter extends WorldEditAdapter {

        private final Object   weInstance;
        private final Method   weGetSessionManager;
        private final Method   smGet;
        private final Method   sessionGetSelection;
        private final Method   sessionSetRegionSelector;
        private final Method   weAdaptPlayer;
        private final Method   wePlayerGetWorld;
        private final Class<?> cuboidClass;
        private final Class<?> polygonalClass;
        private final Method   regionGetMin;
        private final Method   regionGetMax;
        private final Method   vec3GetX, vec3GetY, vec3GetZ;
        private final Method   vec3At;
        private final Method   polyGetPoints;
        private final Method   bv2GetX, bv2GetZ;
        private final Method   bv2At;
        private final java.lang.reflect.Constructor<?> cuboidSelectorCtor;
        private final java.lang.reflect.Constructor<?> polySelectorCtor;
        private final Class<?> worldClass;

        public ReflectiveWorldEditAdapter() throws ReflectiveOperationException {
            Class<?> weClass = Class.forName("com.sk89q.worldedit.WorldEdit");
            this.weInstance  = weClass.getMethod("getInstance").invoke(null);
            this.weGetSessionManager = weClass.getMethod("getSessionManager");

            Class<?> weActor = Class.forName("com.sk89q.worldedit.entity.Player");
            Class<?> session = Class.forName("com.sk89q.worldedit.LocalSession");
            this.worldClass  = Class.forName("com.sk89q.worldedit.world.World");
            Class<?> region  = Class.forName("com.sk89q.worldedit.regions.Region");
            Class<?> selector = Class.forName("com.sk89q.worldedit.regions.RegionSelector");

            // SessionManager.get takes SessionOwner (supertype of Player). Reflection matches the
            // declared parameter type exactly, so look it up by SessionOwner (fall back to Actor).
            Class<?> sessionOwner;
            try {
                sessionOwner = Class.forName("com.sk89q.worldedit.session.SessionOwner");
            } catch (ClassNotFoundException cnfe) {
                sessionOwner = Class.forName("com.sk89q.worldedit.extension.platform.Actor");
            }
            Method getMethod;
            try {
                getMethod = Class.forName("com.sk89q.worldedit.session.SessionManager")
                                 .getMethod("get", sessionOwner);
            } catch (NoSuchMethodException nsme) {
                getMethod = null;
                for (Method m : Class.forName("com.sk89q.worldedit.session.SessionManager").getMethods()) {
                    if (m.getName().equals("get") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isAssignableFrom(weActor)) {
                        getMethod = m; break;
                    }
                }
                if (getMethod == null) throw nsme;
            }
            this.smGet                   = getMethod;
            this.sessionGetSelection     = session.getMethod("getSelection", worldClass);
            this.sessionSetRegionSelector = session.getMethod("setRegionSelector", worldClass, selector);
            this.wePlayerGetWorld        = weActor.getMethod("getWorld");

            Class<?> adapter = Class.forName("com.sk89q.worldedit.neoforge.NeoForgeAdapter");
            Method adaptM;
            try {
                adaptM = adapter.getMethod("adaptPlayer", ServerPlayer.class);
            } catch (NoSuchMethodException nsme) {
                adaptM = null;
                for (Method m : adapter.getMethods()) {
                    if (m.getName().equals("adaptPlayer") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isAssignableFrom(ServerPlayer.class)) {
                        adaptM = m; break;
                    }
                }
                if (adaptM == null) throw nsme;
            }
            this.weAdaptPlayer = adaptM;

            this.cuboidClass    = Class.forName("com.sk89q.worldedit.regions.CuboidRegion");
            this.polygonalClass = Class.forName("com.sk89q.worldedit.regions.Polygonal2DRegion");

            this.regionGetMin = region.getMethod("getMinimumPoint");
            this.regionGetMax = region.getMethod("getMaximumPoint");

            Class<?> bv3 = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            Method gx, gy, gz;
            try { gx = bv3.getMethod("x"); gy = bv3.getMethod("y"); gz = bv3.getMethod("z"); }
            catch (NoSuchMethodException nsme) {
                gx = bv3.getMethod("getX"); gy = bv3.getMethod("getY"); gz = bv3.getMethod("getZ");
            }
            this.vec3GetX = gx; this.vec3GetY = gy; this.vec3GetZ = gz;
            this.vec3At   = bv3.getMethod("at", int.class, int.class, int.class);

            this.polyGetPoints = polygonalClass.getMethod("getPoints");
            Class<?> bv2 = Class.forName("com.sk89q.worldedit.math.BlockVector2");
            Method bx, bz;
            try { bx = bv2.getMethod("x"); bz = bv2.getMethod("z"); }
            catch (NoSuchMethodException nsme) {
                bx = bv2.getMethod("getX"); bz = bv2.getMethod("getZ");
            }
            this.bv2GetX = bx; this.bv2GetZ = bz;
            this.bv2At   = bv2.getMethod("at", int.class, int.class);

            // RegionSelector implementations used to push a selection back into WE.
            Class<?> cuboidSelectorClass =
                    Class.forName("com.sk89q.worldedit.regions.selector.CuboidRegionSelector");
            this.cuboidSelectorCtor = cuboidSelectorClass.getConstructor(worldClass, bv3, bv3);
            Class<?> polySelectorClass =
                    Class.forName("com.sk89q.worldedit.regions.selector.Polygonal2DRegionSelector");
            this.polySelectorCtor = polySelectorClass.getConstructor(worldClass, List.class, int.class, int.class);
        }

        private Object weActorFor(ServerPlayer player) throws ReflectiveOperationException {
            return weAdaptPlayer.invoke(null, player);
        }
        private Object sessionFor(Object weActor) throws ReflectiveOperationException {
            Object sessionMgr = weGetSessionManager.invoke(weInstance);
            return smGet.invoke(sessionMgr, weActor);
        }

        private Object weSelectionFor(ServerPlayer player) throws ReflectiveOperationException {
            Object weActor      = weActorFor(player);
            Object localSession = sessionFor(weActor);
            Object world        = wePlayerGetWorld.invoke(weActor);
            try {
                return sessionGetSelection.invoke(localSession, world);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                return null; // IncompleteRegionException etc. → no selection
            }
        }

        @Override
        public Optional<ProtectedRegion> toProtectedRegion(ServerPlayer player, String id) {
            try {
                Object sel = weSelectionFor(player);
                if (sel == null) return Optional.empty();

                if (cuboidClass.isInstance(sel)) {
                    Vec3 mn = readVec3(regionGetMin.invoke(sel));
                    Vec3 mx = readVec3(regionGetMax.invoke(sel));
                    return Optional.of(new CuboidRegion(id, mn, mx));
                }
                if (polygonalClass.isInstance(sel)) {
                    @SuppressWarnings("unchecked")
                    List<Object> pts = (List<Object>) polyGetPoints.invoke(sel);
                    List<PolygonalRegion.Point2> points = new ArrayList<>(pts.size());
                    for (Object pt : pts) {
                        points.add(new PolygonalRegion.Point2(
                                ((Number) bv2GetX.invoke(pt)).intValue(),
                                ((Number) bv2GetZ.invoke(pt)).intValue()));
                    }
                    Vec3 mn = readVec3(regionGetMin.invoke(sel));
                    Vec3 mx = readVec3(regionGetMax.invoke(sel));
                    return Optional.of(new PolygonalRegion(id, points, mn.y(), mx.y()));
                }
                WorldGuardNeo.LOGGER.warn("[WorldGuardNeo] Unsupported WE selection: {}", sel.getClass().getName());
                return Optional.empty();
            } catch (Throwable t) {
                WorldGuardNeo.LOGGER.debug("[WorldGuardNeo] Reflective WE selection read failed", t);
                return Optional.empty();
            }
        }

        @Override
        public void selectCuboid(ServerPlayer player, Vec3 min, Vec3 max) {
            try {
                Object weActor = weActorFor(player);
                Object session = sessionFor(weActor);
                Object world   = wePlayerGetWorld.invoke(weActor);
                Object p1 = vec3At.invoke(null, min.x(), min.y(), min.z());
                Object p2 = vec3At.invoke(null, max.x(), max.y(), max.z());
                Object selector = cuboidSelectorCtor.newInstance(world, p1, p2);
                sessionSetRegionSelector.invoke(session, world, selector);
            } catch (Throwable t) {
                WorldGuardNeo.LOGGER.debug("[WorldGuardNeo] selectCuboid via WE failed", t);
            }
        }

        @Override
        public void selectPolygon(ServerPlayer player, List<PolygonalRegion.Point2> points,
                                  int minY, int maxY) {
            try {
                Object weActor = weActorFor(player);
                Object session = sessionFor(weActor);
                Object world   = wePlayerGetWorld.invoke(weActor);
                List<Object> bvPoints = new ArrayList<>(points.size());
                for (PolygonalRegion.Point2 pt : points) {
                    bvPoints.add(bv2At.invoke(null, pt.x(), pt.z()));
                }
                Object selector = polySelectorCtor.newInstance(world, bvPoints, minY, maxY);
                sessionSetRegionSelector.invoke(session, world, selector);
            } catch (Throwable t) {
                WorldGuardNeo.LOGGER.debug("[WorldGuardNeo] selectPolygon via WE failed", t);
            }
        }

        private Vec3 readVec3(Object bv3) throws ReflectiveOperationException {
            return new Vec3(
                    ((Number) vec3GetX.invoke(bv3)).intValue(),
                    ((Number) vec3GetY.invoke(bv3)).intValue(),
                    ((Number) vec3GetZ.invoke(bv3)).intValue());
        }
    }
}
