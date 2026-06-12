package dev.dizzy.worldguardneo.region;

import dev.dizzy.worldguardneo.flags.Flag;
import dev.dizzy.worldguardneo.flags.StateFlag;
import dev.dizzy.worldguardneo.util.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * All regions in a single world. Provides fast lookup of regions at a point and
 * flag resolution that respects priority and parent inheritance.
 *
 * Concurrency: callers should hold a lock or use the manager only from the server thread.
 * Reads are linearisable as long as no mutation interleaves them on another thread.
 */
public final class RegionManager {

    /** Maximum length of parent chains we will walk. Defends against corrupted saves. */
    private static final int MAX_PARENT_HOPS = 32;

    private static final Comparator<ProtectedRegion> PRIORITY_DESC =
            Comparator.comparingInt(ProtectedRegion::priority).reversed()
                    .thenComparing(ProtectedRegion::id);

    private final String worldKey;
    private final GlobalRegion globalRegion = new GlobalRegion();
    /** Keyed by lowercased id. Iteration order is insertion order, which matches /rg list. */
    private final Map<String, ProtectedRegion> regions = new LinkedHashMap<>();
    private final SpatialIndex index = new SpatialIndex();

    public RegionManager(String worldKey) { this.worldKey = worldKey; }

    public String world()              { return worldKey; }
    public GlobalRegion globalRegion() { return globalRegion; }
    public SpatialIndex index()        { return index; }

    private static String key(String id) { return id.toLowerCase(Locale.ROOT); }

    public Optional<ProtectedRegion> get(String id) {
        if (id == null) return Optional.empty();
        if (GlobalRegion.ID.equalsIgnoreCase(id)) return Optional.of(globalRegion);
        return Optional.ofNullable(regions.get(key(id)));
    }

    public Collection<ProtectedRegion> all() { return regions.values(); }
    public int size()                        { return regions.size(); }

    public boolean add(ProtectedRegion region) {
        // Single map probe instead of contains() + put().
        ProtectedRegion prev = regions.putIfAbsent(key(region.id()), region);
        if (prev != null) return false;
        index.add(region);
        return true;
    }

    public boolean remove(String id) {
        ProtectedRegion removed = regions.remove(key(id));
        if (removed == null) return false;
        index.remove(removed);
        // Unlink children whose parent was the removed region.
        for (ProtectedRegion r : regions.values()) {
            if (r.parent() == removed) r.setParent(null);
        }
        return true;
    }

    public void rebuildIndex() { index.rebuild(regions.values()); }

    /** Regions containing the point, sorted by priority desc, then by id (stable). */
    public List<ProtectedRegion> getApplicable(double x, double y, double z) {
        List<ProtectedRegion> candidates = index.candidates(x, z);
        if (candidates.isEmpty()) return List.of();
        // Hot-path optimization: walk first to count hits and capture the first hit. For the
        // overwhelmingly common case of exactly 1 region at a position (or 0), we avoid the
        // ArrayList allocation entirely by using List.of(first). Only when ≥2 regions match
        // do we allocate the mutable list for sorting.
        ProtectedRegion first = null;
        List<ProtectedRegion> out = null;
        for (int i = 0, n = candidates.size(); i < n; i++) {
            ProtectedRegion r = candidates.get(i);
            if (r.contains(x, y, z)) {
                if (first == null) {
                    first = r;
                } else {
                    if (out == null) {
                        out = new ArrayList<>(4);
                        out.add(first);
                    }
                    out.add(r);
                }
            }
        }
        if (out == null) return first == null ? List.of() : List.of(first);
        out.sort(PRIORITY_DESC);
        return out;
    }

    /**
     * Allocation-free "is there any region at this point?" probe. Use this when the caller
     * only needs a boolean (e.g. {@code invincibleRegions} world flag) instead of the full
     * applicable list — avoids the ArrayList allocation in {@link #getApplicable}.
     */
    public boolean hasAnyAt(double x, double y, double z) {
        List<ProtectedRegion> candidates = index.candidates(x, z);
        for (int i = 0, n = candidates.size(); i < n; i++) {
            if (candidates.get(i).contains(x, y, z)) return true;
        }
        return false;
    }

    /**
     * Test whether the given player UUID may perform an action governed by {@code flag} at point.
     * Resolution order: highest-priority region's value wins; parent inherited; DENY beats ALLOW
     * on equal priority; falls back to global region; falls back to flag default.
     *
     * If {@code playerId} is null the membership-based group rules treat the actor as non-member.
     */
    public boolean testState(StateFlag flag, UUID playerId, double x, double y, double z) {
        return testState(flag, getApplicable(x, y, z), playerId);
    }

    /**
     * Build-access test that implements WorldGuard's core "regions are private by default"
     * behaviour, which a plain {@link #testState} does NOT provide.
     *
     * <p>The problem this solves: build-type flags (build, block-break, block-place, interact…)
     * default to ALLOW so the wilderness stays unprotected. But that also means a freshly
     * claimed region does nothing to keep strangers out — testState returns ALLOW because the
     * flag was never explicitly set. In real WorldGuard, simply being inside a region you are
     * not a member of denies building unless a flag explicitly re-allows it.
     *
     * <p>Rules (highest-priority region that applies wins, matching WG):
     * <ol>
     *   <li>No region here → wilderness → fall back to the global flag default (ALLOW).</li>
     *   <li>If the flag is EXPLICITLY set on the applicable region(s), that value wins — this
     *       lets admins open a region with {@code build allow} or lock wilderness with a global
     *       {@code build deny}. Resolved via {@link #testState}.</li>
     *   <li>Otherwise (flag not set), membership decides: owners/members may build, everyone
     *       else is denied. This is the implicit protection a claim grants.</li>
     * </ol>
     *
     * @param flag     the build-type state flag being tested
     * @param x,y,z    block position
     * @param playerId acting player (null = treat as non-member, e.g. a machine)
     * @return true if the action is permitted
     */
    public boolean testBuildAccess(StateFlag flag, double x, double y, double z, UUID playerId) {
        List<ProtectedRegion> applicable = getApplicable(x, y, z);
        if (applicable.isEmpty()) {
            // Wilderness — honour the global/default flag value (ALLOW unless admin set deny).
            return flag.test(globalRegion.getFlag(flag));
        }
        // If ANY applicable region (walking parents) explicitly sets this flag, defer to the
        // normal priority/group resolution — explicit admin intent always wins.
        boolean explicitlySet = false;
        for (ProtectedRegion r : applicable) {
            if (resolveSourceWithParents(r, flag) != null) { explicitlySet = true; break; }
        }
        if (explicitlySet) {
            return testState(flag, applicable, playerId);
        }
        // Flag not set anywhere here → implicit membership protection. The player may build
        // only if they own or are a member of the HIGHEST-priority region at this spot (that
        // region "controls" the location).
        if (playerId == null) return false;
        ProtectedRegion top = applicable.get(0); // sorted desc by priority
        return top.isMember(playerId); // isMember includes owners
    }

    /** Variant accepting a precomputed applicable list — see {@link #resolveValue}. */
    public boolean testState(StateFlag flag, List<ProtectedRegion> applicable, UUID playerId) {
        if (applicable.isEmpty()) {
            return flag.test(globalRegion.getFlag(flag));
        }
        // Walk regions in priority order (the list is sorted desc). We want the value from
        // the HIGHEST priority tier that actually *contributes* a value for this player —
        // i.e. a region where the flag is set AND its group filter matches the actor. A
        // higher-priority region whose group EXCLUDES the player must NOT shadow a lower
        // one that does include them, so we only "lock" to a priority tier once we've found
        // a matching value there. Within the locked tier, DENY beats ALLOW.
        StateFlag.State winning = null;
        int winningPriority = 0;
        boolean locked = false;
        for (int i = 0, n = applicable.size(); i < n; i++) {
            ProtectedRegion r = applicable.get(i);
            if (locked && r.priority() < winningPriority) break;
            ProtectedRegion source = resolveSourceWithParents(r, flag);
            if (source == null) continue;
            if (!groupMatches(source.getFlagGroup(flag), source, playerId)) continue;
            StateFlag.State v = source.getFlag(flag);
            if (v == null) continue;
            if (!locked) {
                // First matching region — lock onto its priority tier.
                locked = true;
                winningPriority = r.priority();
                winning = v;
            } else {
                // Same tier (priority == winningPriority, guaranteed by the break above).
                winning = v;
            }
            if (v == StateFlag.State.DENY) { winning = StateFlag.State.DENY; break; }
        }
        if (winning == null) {
            return flag.test(globalRegion.getFlag(flag));
        }
        return winning == StateFlag.State.ALLOW;
    }

    public <T> T resolveValue(Flag<T> flag, double x, double y, double z, UUID actor) {
        return resolveValue(flag, getApplicable(x, y, z), actor);
    }

    /**
     * Variant that reuses an already-computed "applicable" list. The list MUST be the result
     * of {@link #getApplicable(double, double, double)} for the same point — semantics are
     * identical otherwise.
     *
     * Hot-path optimisation: callers that test many flags at the same point (e.g. the
     * per-player tick) compute the list once and pass it to every resolution.
     */
    public <T> T resolveValue(Flag<T> flag, List<ProtectedRegion> applicable, UUID actor) {
        if (applicable.isEmpty()) return globalRegion.getFlag(flag);
        for (int i = 0, n = applicable.size(); i < n; i++) {
            ProtectedRegion r = applicable.get(i);
            // Walk parents to find the source — same reasoning as testState: the group
            // filter on an inherited flag applies to the parent that owns it, not the child.
            ProtectedRegion source = resolveSourceWithParents(r, flag);
            if (source == null) continue;
            if (!groupMatches(source.getFlagGroup(flag), source, actor)) continue;
            T v = source.getFlag(flag);
            if (v != null) return v;
        }
        return globalRegion.getFlag(flag);
    }

    public boolean isOwnerAt(UUID player, double x, double y, double z) {
        List<ProtectedRegion> here = getApplicable(x, y, z);
        for (int i = 0, n = here.size(); i < n; i++) if (here.get(i).isOwner(player)) return true;
        return false;
    }

    public boolean isMemberAt(UUID player, double x, double y, double z) {
        List<ProtectedRegion> here = getApplicable(x, y, z);
        for (int i = 0, n = here.size(); i < n; i++) if (here.get(i).isMember(player)) return true;
        return false;
    }

    public int countOwned(UUID player) {
        int n = 0;
        for (ProtectedRegion r : regions.values()) if (r.isOwner(player)) n++;
        return n;
    }

    public List<ProtectedRegion> getOwnedBy(UUID player) {
        List<ProtectedRegion> out = new ArrayList<>();
        for (ProtectedRegion r : regions.values()) if (r.isOwner(player)) out.add(r);
        return out;
    }

    /**
     * Returns regions whose AABB overlaps the given pair of corners.
     * Callers must pass canonical corners (any order — we don't recompute min/max).
     */
    public List<ProtectedRegion> overlapping(Vec3 a, Vec3 b) {
        int mnx = Math.min(a.x(), b.x()), mxx = Math.max(a.x(), b.x());
        int mny = Math.min(a.y(), b.y()), mxy = Math.max(a.y(), b.y());
        int mnz = Math.min(a.z(), b.z()), mxz = Math.max(a.z(), b.z());
        List<ProtectedRegion> out = new ArrayList<>();
        for (ProtectedRegion r : index.candidatesInArea(mnx, mnz, mxx, mxz)) {
            Vec3 rmn = r.minimumBound(), rmx = r.maximumBound();
            if (!(mxx < rmn.x() || mnx > rmx.x()
               || mxy < rmn.y() || mny > rmx.y()
               || mxz < rmn.z() || mnz > rmx.z())) {
                out.add(r);
            }
        }
        return out;
    }

    /* --------------- internal helpers --------------- */

    /**
     * Walks the parent chain looking for the first region that has the flag set. Returns
     * the source region (not the starting one) so callers can apply the source's group
     * filter — important for inherited flags from a parent that may have a different
     * {@link RegionGroup} than the child.
     *
     * <p>This was previously inlined and returned only the value; callers then applied the
     * CHILD's group to the inherited value, which was wrong. The fix surfaces the source.
     */
    private static ProtectedRegion resolveSourceWithParents(ProtectedRegion r, Flag<?> flag) {
        ProtectedRegion cursor = r;
        for (int hops = 0; cursor != null && hops < MAX_PARENT_HOPS; hops++) {
            if (cursor.getFlag(flag) != null) return cursor;
            cursor = cursor.parent();
        }
        return null;
    }

    /**
     * Whether the given {@link RegionGroup} matches the actor for the given region.
     * Public so listeners can reuse the same semantics outside the manager itself
     * (e.g. when manually walking parents on a leaving region).
     */
    public static boolean groupMatches(RegionGroup grp, ProtectedRegion r, UUID actor) {
        if (grp == null) grp = RegionGroup.ALL;
        if (actor == null) {
            return grp == RegionGroup.ALL || grp == RegionGroup.NON_MEMBERS || grp == RegionGroup.NON_OWNERS;
        }
        return switch (grp) {
            case ALL         -> true;
            case OWNERS      -> r.isOwner(actor);
            case MEMBERS     -> r.isMember(actor);
            case NON_OWNERS  -> !r.isOwner(actor);
            case NON_MEMBERS -> !r.isMember(actor);
        };
    }
}
