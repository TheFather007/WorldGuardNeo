package dev.dizzy.worldguardneo.flags;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class DoubleFlag extends Flag<Double> {
    public DoubleFlag(String name) { super(name); }
    @Override public Double parse(String input) throws FlagParseException {
        if (input == null || input.isEmpty()) return null;
        try { return Double.parseDouble(input.trim()); }
        catch (NumberFormatException e) { throw new FlagParseException("Expected number, got '" + input + "'"); }
    }
    @Override public JsonElement toJson(Double v) { return v == null ? null : new JsonPrimitive(v); }
    @Override public Double fromJson(JsonElement j) {
        if (j == null || j.isJsonNull()) return null;
        try { return j.getAsDouble(); }
        catch (RuntimeException ex) { return null; }
    }
    @Override public String valueHint() { return "number"; }
}
