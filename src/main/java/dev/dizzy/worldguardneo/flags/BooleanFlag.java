package dev.dizzy.worldguardneo.flags;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class BooleanFlag extends Flag<Boolean> {
    public BooleanFlag(String name) { super(name); }
    @Override public Boolean parse(String input) throws FlagParseException {
        if (input == null || input.isEmpty()) return null;
        // Same trim+lowercase rationale as StateFlag — greedyString may carry whitespace.
        return switch (input.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "" -> null;
            case "true","yes","on","1"  -> Boolean.TRUE;
            case "false","no","off","0" -> Boolean.FALSE;
            default -> throw new FlagParseException("Expected true/false, got '" + input + "'");
        };
    }
    @Override public JsonElement toJson(Boolean v) { return v == null ? null : new JsonPrimitive(v); }
    @Override public Boolean fromJson(JsonElement j) {
        if (j == null || j.isJsonNull()) return null;
        try { return j.getAsBoolean(); }
        catch (RuntimeException ex) { return null; }
    }
    @Override public String valueHint() { return "true|false"; }
}
