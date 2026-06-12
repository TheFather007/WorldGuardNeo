package dev.dizzy.worldguardneo.flags;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * Tri-state flag: ALLOW / DENY / unset (null). Most protection toggles use this type.
 *
 * Resolution rule: DENY wins, then explicit ALLOW, then default.
 */
public final class StateFlag extends Flag<StateFlag.State> {

    public enum State { ALLOW, DENY }

    private final boolean defaultAllow;

    public StateFlag(String name, boolean defaultAllow) {
        super(name);
        this.defaultAllow = defaultAllow;
    }

    public boolean defaultAllow() { return defaultAllow; }

    @Override
    public State parse(String input) throws FlagParseException {
        if (input == null) return null;
        // Trim AND lowercase. Brigadier's greedyString preserves leading whitespace if the
        // user typed `flag x pvp " allow"`. Other Flag types (Integer, Double) trim already
        // — match that behaviour for consistency. Locale-safe: Turkish locale lowercases
        // I → ı which would break "allow"/"deny" matching.
        switch (input.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "allow": case "yes": case "true":  return State.ALLOW;
            case "deny":  case "no":  case "false": return State.DENY;
            case "":      case "none": case "unset": return null;
            default: throw new FlagParseException("Expected allow/deny/none, got '" + input + "'");
        }
    }

    @Override public JsonElement toJson(State value) {
        // null indicates "unset" — storage layer skips null elements, so return null here
        // and rely on writeFlags(...) to drop unset entries from the saved JSON.
        return value == null ? null : new JsonPrimitive(value.name());
    }

    @Override public State fromJson(JsonElement json) {
        if (json == null || json.isJsonNull()) return null;
        String s = json.getAsString();
        if (s == null || s.isEmpty() || s.equalsIgnoreCase("none")) return null;
        // Tolerate junk data in saved files: unknown values become "unset" instead of crashing load.
        try { return State.valueOf(s.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return null; }
    }

    /** Most accurate "evaluate" — returns true if action is allowed, false if denied. */
    public boolean test(State current) {
        if (current == null) return defaultAllow;
        return current == State.ALLOW;
    }

    @Override public String valueHint() { return "allow|deny|none"; }
}
