package dev.thefather007.worldguardneo.region;

/**
 * Determines which players a flag applies to. Combined with each flag on a region.
 *
 * Resolution rules at lookup time:
 *  - OWNERS: only owners of the region
 *  - MEMBERS: owners or members
 *  - NON_OWNERS: anyone except the owners
 *  - NON_MEMBERS: anyone who is neither owner nor member
 *  - ALL: everyone (the default)
 */
public enum RegionGroup {
    ALL, OWNERS, MEMBERS, NON_OWNERS, NON_MEMBERS;

    public static RegionGroup parse(String s) {
        if (s == null) return ALL;
        RegionGroup g = parseStrict(s);
        return g == null ? ALL : g;
    }

    /** Like {@link #parse} but returns {@code null} for an unrecognized name instead of silently
     *  falling back to {@link #ALL} — used by the command layer to reject typo'd {@code -g} groups. */
    public static RegionGroup parseStrict(String s) {
        if (s == null) return null;
        // Locale-safe — Turkish locale lowercases I → ı which would break "MEMBERS"/"OWNERS" matching.
        try { return RegionGroup.valueOf(s.toUpperCase(java.util.Locale.ROOT).replace('-','_')); }
        catch (IllegalArgumentException e) { return null; }
    }
}
