package dev.dizzy.worldguardneo.flags;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class IntegerFlag extends Flag<Integer> {
    public IntegerFlag(String name) { super(name); }
    @Override public Integer parse(String input) throws FlagParseException {
        if (input == null || input.isEmpty()) return null;
        try { return Integer.parseInt(input.trim()); }
        catch (NumberFormatException e) { throw new FlagParseException("Expected integer, got '" + input + "'"); }
    }
    @Override public JsonElement toJson(Integer v) { return v == null ? null : new JsonPrimitive(v); }
    @Override public Integer fromJson(JsonElement j) {
        if (j == null || j.isJsonNull()) return null;
        try { return j.getAsInt(); }
        catch (RuntimeException ex) { return null; }
    }
    @Override public String valueHint() { return "integer"; }
}
