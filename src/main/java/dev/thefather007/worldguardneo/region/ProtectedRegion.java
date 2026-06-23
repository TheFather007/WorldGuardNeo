package dev.thefather007.worldguardneo.region;

import dev.thefather007.worldguardneo.flags.Flag;
import dev.thefather007.worldguardneo.util.Vec3;

import java.util.*;

/**
 * A region of space in a single world that holds flags, owners and members. Subclasses implement
 * geometry (contains/minimumBound/maximumBound).
 *
 * <p>Memory layout: the six collections are lazily allocated on first write so a quiet region costs
 * no collection objects — significant heap saving across thousands of regions. Reads tolerate null.
 */
public abstract class ProtectedRegion {

    /**
     * Global mutation epoch, bumped on every flag/group/parent/priority change on any region. Lets
     * consumers (e.g. the per-player tick cache) invalidate cached state with one volatile read.
     * Single-writer (server thread), so the unguarded increment is race-free.
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
        // Manual validation (not regex) avoids compiling a Pattern per region at boot. Spec: [a-zA-Z0-9_\-:.]+
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

    /** Mutable owners set; lazy-allocated on first call. Use {@link #ownersView()}/{@link #isOwner} for allocation-free reads. */
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
     * Copy every flag value AND group filter from {@code other} into this region. Used by
     * {@code /rg redefine}. Done here so the unchecked storage copy stays encapsulated — the values
     * came from typed {@link #setFlag} calls, so re-storing verbatim preserves flag→value types.
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
