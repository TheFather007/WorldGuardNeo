package dev.thefather007.worldguardneo.flags;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class DoubleFlag extends Flag<Double> {
    public DoubleFlag(String name) { super(name); }
    @Override public Double parse(String input) throws FlagParseException {
        if (input == null || input.isEmpty()) return null;
        double d;
        try { d = Double.parseDouble(input.trim()); }
        catch (NumberFormatException e) { throw new FlagParseException("Expected number, got '" + input + "'"); }
        // Reject NaN / ±Infinity: they silently break downstream math (e.g. a max-speed of NaN
        // makes every speed comparison false, disabling the cap) and don't round-trip through JSON.
        if (!Double.isFinite(d)) throw new FlagParseException("Expected a finite number, got '" + input + "'");
        return d;
    }
    @Override public JsonElement toJson(Double v) { return v == null ? null : new JsonPrimitive(v); }
    @Override public Double fromJson(JsonElement j) {
        if (j == null || j.isJsonNull()) return null;
        try { return j.getAsDouble(); }
        catch (RuntimeException ex) { return null; }
    }
    @Override public String valueHint() { return "number"; }
}
