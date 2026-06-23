package dev.thefather007.worldguardneo.region;

import dev.thefather007.worldguardneo.flags.Flag;
import dev.thefather007.worldguardneo.flags.StateFlag;
import dev.thefather007.worldguardneo.util.Vec3;

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

    /**
     * Rename a region, preserving its geometry, priority, parent, owners/members and flags, and
     * re-pointing any child regions to the renamed instance. Returns the new region, or empty if
     * the source is missing, the target id is taken, or the target is the reserved global id.
     */
    public Optional<ProtectedRegion> rename(String oldId, String newId) {
        if (newId == null || GlobalRegion.ID.equalsIgnoreCase(newId)) return Optional.empty();
        ProtectedRegion old = regions.get(key(oldId));
        if (old == null) return Optional.empty();
        if (regions.containsKey(key(newId))) return Optional.empty();

        ProtectedRegion fresh = old.withId(newId);
        fresh.setPriority(old.priority());
        fresh.setParent(old.parent());
        if (!old.ownersView().isEmpty())       fresh.owners().addAll(old.ownersView());
        if (!old.ownerGroupsView().isEmpty())  fresh.ownerGroups().addAll(old.ownerGroupsView());
        if (!old.membersView().isEmpty())      fresh.members().addAll(old.membersView());
        if (!old.memberGroupsView().isEmpty()) fresh.memberGroups().addAll(old.memberGroupsView());
        fresh.copyFlagsFrom(old);

        regions.put(key(newId), fresh);
        index.add(fresh);
        // Re-point children before dropping the old instance (remove() would otherwise null them).
        for (ProtectedRegion r : regions.values()) {
            if (r != fresh && r.parent() == old) r.setParent(fresh);
        }
        regions.remove(key(oldId));
        index.remove(old);
        return Optional.of(fresh);
    }

    /** Regions containing the point, sorted by priority desc, then by id (stable). */
    public List<ProtectedRegion> getApplicable(double x, double y, double z) {
        List<ProtectedRegion> candidates = index.candidates(x, z);
        if (candidates.isEmpty()) return List.of();
        // Hot path: the common 0/1-region case avoids the ArrayList via List.of(first); only ≥2
        // matches allocate the mutable list for sorting.
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
     * Allocation-free probe: does propagation from source→target cross INTO a region boundary?
     * True if any region containing the TARGET does not also contain the SOURCE — i.e. fire/fluid
     * spreading would ENTER a region the source isn't part of (wilderness→claim, claimA→claimB).
     * Propagation LEAVING a region into wilderness is intentionally not flagged. Walks spatial-index
     * candidates directly (no list alloc or sort) — hot on NeighborNotifyEvent (every flow tick).
     */
    public boolean crossesBoundary(double sx, double sy, double sz,
                                   double tx, double ty, double tz) {
        List<ProtectedRegion> cand = index.candidates(tx, tz);
        for (int i = 0, n = cand.size(); i < n; i++) {
            ProtectedRegion r = cand.get(i);
            if (r.contains(tx, ty, tz) && !r.contains(sx, sy, sz)) return true;
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
     * Build-access test implementing WorldGuard's "regions are private by default" behaviour, which
     * plain {@link #testState} doesn't: build-type flags default to ALLOW (so wilderness is open),
     * but a claim must still keep non-members out unless a flag explicitly re-allows.
     *
     * <p>Rules (highest-priority applicable region wins):
     * <ol>
     *   <li>No region → wilderness → global flag default (ALLOW).</li>
     *   <li>Flag EXPLICITLY set here → that value wins (via {@link #testState}); lets admins open a
     *       region or lock wilderness globally.</li>
     *   <li>Flag not set → membership decides: owners/members build, others denied.</li>
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
        // Walk in priority order (sorted desc). Take the value from the highest priority tier that
        // actually contributes one (flag set AND group matches the actor): a higher-priority region
        // whose group EXCLUDES the player must not shadow a lower one that includes them, so we only
        // "lock" onto a tier once a match is found there. Within the locked tier, DENY beats ALLOW.
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
     * Resolve a state flag for ONE region by walking its parent chain, applying the SOURCE region's
     * group filter (not the starting region's). Null if no region in the chain sets it or the
     * source's group excludes the actor.
     *
     * <p>Used by the EXIT case: the player is already OUTSIDE the leaving region, so position-based
     * {@link #testState} would never see it. Mirrors testState's "group filter belongs to the source"
     * semantics so group-scoped exit denials inherited from a parent are honoured.
     */
    public StateFlag.State resolveStateForRegion(StateFlag flag, ProtectedRegion region, UUID actor) {
        ProtectedRegion source = resolveSourceWithParents(region, flag);
        if (source == null) return null;
        if (!groupMatches(source.getFlagGroup(flag), source, actor)) return null;
        return source.getFlag(flag);
    }

    /**
     * Walks the parent chain for the first region with the flag set, returning that SOURCE region
     * (not the starting one) so callers apply the source's group filter — an inherited flag from a
     * parent may carry a different {@link RegionGroup} than the child.
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
