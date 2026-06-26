package dev.thefather007.worldguardneo.flags;

import com.google.gson.JsonElement;

/**
 * A typed key/value attached to a region.
 * Each flag knows how to (de)serialize itself to JSON, parse player input,
 * report its permission node, and explain itself for the /rg flags listing.
 *
 * @param <T> the runtime value type
 */
public abstract class Flag<T> {

    private final String name;
    private final String permission;     // worldguardneo.flag.<name>
    private final String descriptionKey; // localization key for help text
    private final int    hash;           // cached for HashMap lookups (hot path)

    protected Flag(String name) {
        // Validate manually instead of name.matches(regex) — same semantics but doesn't
        // compile a Pattern on every Flag construction. Spec: [a-z][a-z0-9-]*
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Invalid flag name: empty");
        }
        char first = name.charAt(0);
        if (first < 'a' || first > 'z') {
            throw new IllegalArgumentException("Invalid flag name (must start with a-z): " + name);
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                throw new IllegalArgumentException("Invalid flag name (only a-z, 0-9, '-' allowed): " + name);
            }
        }
        this.name = name;
        // Per-flag permission node. Dashes in the flag name are preserved verbatim, which
        // matches the convention used by LuckPerms for compound keys.
        this.permission     = "worldguardneo.flag." + name;
        this.descriptionKey = "flag." + name + ".desc";
        this.hash           = name.hashCode();
    }

    public final String name()           { return name; }
    public final String permission()     { return permission; }
    public final String descriptionKey() { return descriptionKey; }

    /** Parse a value from raw player input. */
    public abstract T parse(String input) throws FlagParseException;

    /**
     * Parse {@code input} and store the result on {@code region} (plus group if given). Done HERE,
     * where {@code T} is bound, so the command layer working with {@code Flag<?>} stays type-safe
     * and avoids an unchecked cast.
     *
     * @param input the raw value, or null/empty to UNSET the flag
     * @param group optional region-group to attach (null to leave group untouched)
     * @return the parsed value that was stored (null when unset), for display/logging
     */
    public final T parseAndApply(dev.thefather007.worldguardneo.region.ProtectedRegion region,
                                 String input,
                                 dev.thefather007.worldguardneo.region.RegionGroup group)
            throws FlagParseException {
        T parsed = (input == null || input.isEmpty()) ? null : parse(input);
        region.setFlag(this, parsed);
        if (parsed != null && group != null) {
            region.setFlagGroup(this, group);
        }
        return parsed;
    }

    /** Serialize for storage. */
    public abstract JsonElement toJson(T value);

    /** Deserialize from storage. */
    public abstract T fromJson(JsonElement json);

    /** Human-friendly representation for /rg info. */
    public String display(T value) { return String.valueOf(value); }

    /**
     * Display a value from a region's raw {@code Map<Flag<?>, Object>} store. The cast is safe: the
     * value was stored via the typed {@code setFlag}, so it's guaranteed to be this flag's {@code T}.
     */
    @SuppressWarnings("unchecked")
    public final String displayRaw(Object rawValue) {
        return display((T) rawValue);
    }

    /** Short value-form hint used by /rg flags (e.g. "allow|deny|none", "integer", "x,y,z"). */
    public abstract String valueHint();

    @Override public boolean equals(Object o) {
        return o instanceof Flag<?> f && f.name.equals(name);
    }
    @Override public int hashCode() { return hash; }
    @Override public String toString() { return name; }

    public static final class FlagParseException extends Exception {
        public FlagParseException(String msg) { super(msg); }
    }
}
