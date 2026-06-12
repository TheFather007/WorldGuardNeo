package dev.thefather007.worldguardneo.region;

import dev.thefather007.worldguardneo.flags.Flag;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.*;

/**
 * A region of space in a single world that holds flags, owners and members.
 *
 * Subclasses must implement geometry: {@link #contains(double, double, double)},
 * {@link #minimumBound()} and {@link #maximumBound()}.
 *
 * <p><b>Memory layout</b>: the six collections (owners, members, owner-groups,
 * member-groups, flag values, flag groups) are lazily allocated on first write.
 * On a large server with thousands of mostly-quiet regions this saves significant
 * heap — a region with no flags, no owners, no members costs no collection objects.
 * Read methods tolerate null collections and return safe empty results.
 */
public abstract class ProtectedRegion {

    /**
     * Global mutation epoch, bumped on every flag/group/parent/priority change on ANY region.
     * Consumers (e.g. the per-player tick cache in PlayerEventHandler) compare a stored epoch
     * against this to know whether previously computed flag-derived state is still valid —
     * a single volatile read instead of re-resolving a dozen flags every tick.
     *
     * <p>Single-writer: all mutations happen on the server thread, so the unguarded volatile
     * increment is race-free; same-thread readers always observe the latest value.
     */
    private static volatile long flagEpoch;
    public  static long flagEpoch()     { return flagEpoch; }
    private static void bumpFlagEpoch() { flagEpoch++; }

    private final String id;
    private int priority = 0;
    private ProtectedRegion parent;

    // Nullable — lazily allocated on first write. Reads tolerate null and substitute
    // empty collections so callers can iterate uniformly without null checks.
    private Set<UUID>   owners;
    private Set<String> ownerGroups;
    private Set<UUID>   members;
    private Set<String> memberGroups;

    /** Flag → value (typed). Group of a flag is stored alongside in flagGroups. Both nullable. */
    private Map<Flag<?>, Object>      flagValues;
    private Map<Flag<?>, RegionGroup> flagGroups;

    protected ProtectedRegion(String id) {
        // Validate id manually instead of regex.matches — runs on every region load,
        // and on a server with thousands of regions this compiles thousands of Patterns
        // at boot. Spec: one or more [a-zA-Z0-9_\-:.]
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Invalid region id: empty");
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                         || (c >= 'A' && c <= 'Z')
                         || (c >= '0' && c <= '9')
                         || c == '_' || c == '-' || c == ':' || c == '.';
            if (!valid) {
                throw new IllegalArgumentException("Invalid region id (only A-Za-z0-9_-:. allowed): " + id);
            }
        }
        this.id = id;
    }

    public final String id()                       { return id; }
    public final int    priority()                 { return priority; }
    public final void   setPriority(int p)         { this.priority = p; bumpFlagEpoch(); }
    public final ProtectedRegion parent()          { return parent; }
    public final void   setParent(ProtectedRegion p) {
        // Cycle check, bounded at 32 hops to defend against pre-corrupted data.
        ProtectedRegion cursor = p;
        for (int hops = 0; cursor != null && hops < 32; hops++) {
            if (cursor == this) throw new IllegalStateException("Parent cycle: " + id);
            cursor = cursor.parent;
        }
        this.parent = p;
        bumpFlagEpoch();
    }

    /** Allocation-free "does this region set any flag values?" probe for cache relevance. */
    public final boolean hasFlags() { return flagValues != null && !flagValues.isEmpty(); }

    /**
     * Mutable owners set. Lazy-allocated — the first call to this method materializes the
     * underlying collection. Use {@link #ownersView()} for read-only access without allocation,
     * or {@link #isOwner} to test single membership.
     */
    public final Set<UUID>   owners()        {
        if (owners == null) owners = new LinkedHashSet<>(2);
        return owners;
    }
    public final Set<String> ownerGroups()   {
        if (ownerGroups == null) ownerGroups = new LinkedHashSet<>(2);
        return ownerGroups;
    }
    public final Set<UUID>   members()       {
        if (members == null) members = new LinkedHashSet<>(2);
        return members;
    }
    public final Set<String> memberGroups()  {
        if (memberGroups == null) memberGroups = new LinkedHashSet<>(2);
        return memberGroups;
    }

    /** Read-only views — return {@link Collections#emptySet} when underlying collection is null. */
    public final Set<UUID>   ownersView()       { return owners       == null ? Collections.emptySet() : owners; }
    public final Set<String> ownerGroupsView()  { return ownerGroups  == null ? Collections.emptySet() : ownerGroups; }
    public final Set<UUID>   membersView()      { return members      == null ? Collections.emptySet() : members; }
    public final Set<String> memberGroupsView() { return memberGroups == null ? Collections.emptySet() : memberGroups; }

    /** Allocation-free owner test. */
    public final boolean isOwner(UUID pid)   { return owners != null && owners.contains(pid); }
    /** Allocation-free member test (owners count as members). */
    public final boolean isMember(UUID pid)  {
        if (owners != null && owners.contains(pid)) return true;
        return members != null && members.contains(pid);
    }

    @SuppressWarnings("unchecked")
    public final <T> T  getFlag(Flag<T> flag)            {
        // Null-safe — a region with no flags ever set returns null directly.
        return flagValues == null ? null : (T) flagValues.get(flag);
    }
    public final RegionGroup getFlagGroup(Flag<?> flag)  {
        if (flagGroups == null) return RegionGroup.ALL;
        return flagGroups.getOrDefault(flag, RegionGroup.ALL);
    }

    public final <T> void setFlag(Flag<T> flag, T value)            {
        if (value == null) {
            if (flagValues != null) flagValues.remove(flag);
            if (flagGroups != null) flagGroups.remove(flag);
        } else {
            if (flagValues == null) flagValues = new LinkedHashMap<>(4);
            flagValues.put(flag, value);
        }
        bumpFlagEpoch();
    }
    /**
     * Set the group filter for a flag. Setting a group on a flag with no value is harmless
     * (the group is silently dropped) — it would otherwise leak an orphan entry into save data.
     * Passing {@code null} or {@code RegionGroup.ALL} clears the entry to save space.
     */
    public final void setFlagGroup(Flag<?> flag, RegionGroup g)     {
        if (flagValues == null || !flagValues.containsKey(flag)) return;
        if (g == null || g == RegionGroup.ALL) {
            if (flagGroups != null) flagGroups.remove(flag);
        } else {
            if (flagGroups == null) flagGroups = new LinkedHashMap<>(2);
            flagGroups.put(flag, g);
        }
        bumpFlagEpoch();
    }
    public final Map<Flag<?>, Object>      flagsRaw()     {
        return flagValues == null ? Collections.emptyMap() : Collections.unmodifiableMap(flagValues);
    }

    /**
     * Copy every flag value AND its group filter from {@code other} into this region. Used by
     * {@code /rg redefine}, which builds a fresh region object at the new geometry and must
     * carry over all existing flags. Done here (rather than element-by-element in the command
     * layer with a raw {@code Flag}) so the unchecked storage copy stays encapsulated: the
     * values already came out of a region's own typed {@link #setFlag} calls, so re-storing
     * them verbatim preserves the flag→value type pairing without any cast.
     */
    public final void copyFlagsFrom(ProtectedRegion other) {
        if (other.flagValues != null && !other.flagValues.isEmpty()) {
            if (flagValues == null) flagValues = new LinkedHashMap<>(other.flagValues.size());
            flagValues.putAll(other.flagValues);
        }
        if (other.flagGroups != null && !other.flagGroups.isEmpty()) {
            if (flagGroups == null) flagGroups = new LinkedHashMap<>(other.flagGroups.size());
            flagGroups.putAll(other.flagGroups);
        }
        bumpFlagEpoch();
    }
    public final Map<Flag<?>, RegionGroup> flagGroupsRaw(){
        return flagGroups == null ? Collections.emptyMap() : Collections.unmodifiableMap(flagGroups);
    }

    /* -------------------- abstract geometry -------------------- */
    public abstract boolean contains(double x, double y, double z);
    public abstract Vec3    minimumBound();
    public abstract Vec3    maximumBound();
    public abstract String  type();
    public abstract long    volume();

    /** Cheap AABB-vs-AABB pre-check used in spatial indexing. */
    public boolean intersectsBounds(ProtectedRegion o) {
        Vec3 a1 = minimumBound(), a2 = maximumBound();
        Vec3 b1 = o.minimumBound(), b2 = o.maximumBound();
        return !(a2.x() < b1.x() || a1.x() > b2.x()
              || a2.y() < b1.y() || a1.y() > b2.y()
              || a2.z() < b1.z() || a1.z() > b2.z());
    }
}
