package dev.thefather007.worldguardneo.selection;

import dev.thefather007.worldguardneo.WorldGuardNeo;
import dev.thefather007.worldguardneo.region.CuboidRegion;
import dev.thefather007.worldguardneo.region.PolygonalRegion;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import dev.thefather007.worldguardneo.util.Vec3;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Built-in region selection — the replacement for WorldEdit. Holds one {@link Selection} per
 * player (cuboid corners or polygon points), and renders it to the client via the WorldEdit-CUI
 * plugin channel (see {@link CuiPayload}).
 *
 * <p>The wand item ({@code /rg wand}, configurable via {@code selection.wand-item}) drives this:
 * left-click sets position 1 / adds a polygon point, right-click sets position 2. The same state
 * can also be set with {@code /rg pos1}, {@code /rg pos2}, {@code /rg point}, and the selection
 * mode toggled with {@code /rg sel cuboid|poly}.
 *
 * <p>All access is from the server thread (interaction + command handlers, logout cleanup), so a
 * plain {@link HashMap} suffices — no synchronisation needed.
 */
public final class SelectionStore {

    public enum Mode { CUBOID, POLYGON }

    /** Mutable per-player selection state. Pure data; no behaviour. */
    public static final class Selection {
        public Mode mode = Mode.CUBOID;
        /** Dimension the corners/points belong to — switching worlds resets the selection. */
        public ResourceKey<Level> world;
        public Vec3 pos1;
        public Vec3 pos2;
        public final List<PolygonalRegion.Point2> polyPoints = new ArrayList<>();
        public int polyMinY = Integer.MAX_VALUE;
        public int polyMaxY = Integer.MIN_VALUE;
    }

    private final Map<UUID, Selection> selections = new HashMap<>();

    public Selection get(UUID id)         { return selections.get(id); }
    public Selection getOrCreate(UUID id) { return selections.computeIfAbsent(id, k -> new Selection()); }
    public void clear(UUID id)            { selections.remove(id); }

    /** Switch a player's selection mode, clearing any state that doesn't carry over. */
    public Selection setMode(ServerPlayer p, Mode mode) {
        Selection sel = getOrCreate(p.getUUID());
        if (sel.mode != mode) {
            sel.mode = mode;
            // Mode change starts a fresh selection so stale cuboid corners don't leak into a
            // polygon (and vice-versa). The client outline is cleared too.
            resetGeometry(sel);
            render(p, sel);
        }
        return sel;
    }

    /** Record position 1 (cuboid corner / first polygon point). */
    public void setPos1(ServerPlayer p, Vec3 pos) {
        Selection sel = getOrCreate(p.getUUID());
        ensureWorld(p, sel);
        sel.pos1 = pos;
        render(p, sel);
    }

    /** Record position 2 (cuboid corner). For polygons this is treated as an added point. */
    public void setPos2(ServerPlayer p, Vec3 pos) {
        Selection sel = getOrCreate(p.getUUID());
        ensureWorld(p, sel);
        sel.pos2 = pos;
        render(p, sel);
    }

    /** Append a polygon vertex (x/z), widening the vertical span to include this Y. */
    public int addPolyPoint(ServerPlayer p, Vec3 pos) {
        Selection sel = getOrCreate(p.getUUID());
        ensureWorld(p, sel);
        sel.polyPoints.add(new PolygonalRegion.Point2(pos.x(), pos.z()));
        sel.polyMinY = Math.min(sel.polyMinY, pos.y());
        sel.polyMaxY = Math.max(sel.polyMaxY, pos.y());
        render(p, sel);
        return sel.polyPoints.size();
    }

    /**
     * Load an existing region's geometry into the player's selection so it can be redefined,
     * expanded, or just outlined ({@code /rg select}). Global regions have no geometry and are
     * ignored. The selection's world is set to the player's current dimension.
     */
    public void selectRegion(ServerPlayer p, ProtectedRegion r) {
        Selection sel = getOrCreate(p.getUUID());
        sel.world = p.serverLevel().dimension();
        resetGeometry(sel);
        if (r instanceof CuboidRegion c) {
            sel.mode = Mode.CUBOID;
            sel.pos1 = c.minimumBound();
            sel.pos2 = c.maximumBound();
        } else if (r instanceof PolygonalRegion poly) {
            sel.mode = Mode.POLYGON;
            sel.polyPoints.addAll(poly.points());
            sel.polyMinY = poly.minY();
            sel.polyMaxY = poly.maxY();
        } else {
            return; // global / geometry-less — nothing to load
        }
        render(p, sel);
    }

    /** Drop the player's entire selection and clear the client outline. */
    public void reset(ServerPlayer p) {
        selections.remove(p.getUUID());
        // An empty cuboid shape with no points tells WorldEditCUI to drop the box.
        send(p, "s|cuboid");
    }

    /**
     * Build a region of the given id from the player's current selection, or empty if the
     * selection is incomplete (no two cuboid corners / fewer than 3 polygon points) or belongs
     * to a different world than the player currently stands in.
     */
    public Optional<ProtectedRegion> buildRegion(ServerPlayer p, String id) {
        Selection sel = selections.get(p.getUUID());
        if (sel == null) return Optional.empty();
        // Corners/points are world-relative; refuse to build in a different world.
        if (sel.world != null && sel.world != p.serverLevel().dimension()) return Optional.empty();
        if (sel.mode == Mode.CUBOID) {
            if (sel.pos1 == null || sel.pos2 == null) return Optional.empty();
            return Optional.of(new CuboidRegion(id, sel.pos1, sel.pos2));
        } else {
            if (sel.polyPoints.size() < 3) return Optional.empty();
            return Optional.of(new PolygonalRegion(id, new ArrayList<>(sel.polyPoints),
                    sel.polyMinY, sel.polyMaxY));
        }
    }

    /* ----------------------------------------------------------------- internals */

    private static void resetGeometry(Selection sel) {
        sel.pos1 = sel.pos2 = null;
        sel.polyPoints.clear();
        sel.polyMinY = Integer.MAX_VALUE;
        sel.polyMaxY = Integer.MIN_VALUE;
    }

    /** Reset corners/points when the player has moved to a different dimension than the selection. */
    private static void ensureWorld(ServerPlayer p, Selection sel) {
        ResourceKey<Level> cur = p.serverLevel().dimension();
        if (sel.world != cur) {
            sel.world = cur;
            resetGeometry(sel);
        }
    }

    /* ----------------------------------------------------------------- CUI rendering */

    /**
     * Push the current selection to the player's WorldEditCUI client. No-op (other than dropped
     * packets) for clients without WorldEditCUI. Also used by {@code /rg info} to outline an
     * existing region — see {@link #renderRegion}.
     */
    public void render(ServerPlayer p, Selection sel) {
        if (sel.mode == Mode.CUBOID) {
            send(p, "s|cuboid");
            if (sel.pos1 != null) send(p, point3(0, sel.pos1, cuboidVolume(sel)));
            if (sel.pos2 != null) send(p, point3(1, sel.pos2, cuboidVolume(sel)));
        } else {
            send(p, "s|polygon2d");
            int i = 0;
            for (PolygonalRegion.Point2 pt : sel.polyPoints) {
                send(p, "p|" + i++ + "|" + pt.x() + "|" + pt.z() + "|" + sel.polyPoints.size());
            }
            if (sel.polyMinY <= sel.polyMaxY) send(p, "minmax|" + sel.polyMinY + "|" + sel.polyMaxY);
        }
    }

    /** Outline an existing region for {@code /rg info} (does not change the player's selection). */
    public void renderRegion(ServerPlayer p, ProtectedRegion r) {
        if (r instanceof CuboidRegion c) {
            Vec3 mn = c.minimumBound(), mx = c.maximumBound();
            long vol = c.volume();
            send(p, "s|cuboid");
            send(p, point3(0, mn, vol));
            send(p, point3(1, mx, vol));
        } else if (r instanceof PolygonalRegion poly) {
            send(p, "s|polygon2d");
            int i = 0;
            List<PolygonalRegion.Point2> pts = poly.points();
            for (PolygonalRegion.Point2 pt : pts) {
                send(p, "p|" + i++ + "|" + pt.x() + "|" + pt.z() + "|" + pts.size());
            }
            send(p, "minmax|" + poly.minY() + "|" + poly.maxY());
        }
    }

    private static long cuboidVolume(Selection sel) {
        return sel.pos1 != null && sel.pos2 != null ? sel.pos1.volumeWith(sel.pos2) : 0L;
    }

    private static String point3(int id, Vec3 v, long size) {
        return "p|" + id + "|" + v.x() + "|" + v.y() + "|" + v.z() + "|" + size;
    }

    /**
     * Send one CUI command, but only to clients that actually negotiated the {@code worldedit:cui}
     * channel (i.e. have WorldEditCUI). Gating on {@code hasChannel} rather than catching a send
     * failure means vanilla clients cost nothing — no exception thrown/caught per selection action.
     */
    private static void send(ServerPlayer p, String command) {
        if (!p.connection.hasChannel(CuiPayload.TYPE)) return;
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new CuiPayload(command));
        } catch (Throwable t) {
            WorldGuardNeo.LOGGER.debug("[WorldGuardNeo] CUI send failed ({})", t.toString());
        }
    }
}
