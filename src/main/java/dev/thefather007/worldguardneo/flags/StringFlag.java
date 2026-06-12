package dev.thefather007.worldguardneo.flags;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class StringFlag extends Flag<String> {
    public StringFlag(String name) { super(name); }
    @Override public String parse(String input) { return (input == null || input.isEmpty()) ? null : input; }
    @Override public JsonElement toJson(String v) { return v == null ? null : new JsonPrimitive(v); }
    @Override public String fromJson(JsonElement j) {
        if (j == null || j.isJsonNull()) return null;
        try { return j.getAsString(); }
        catch (RuntimeException ex) { return null; }
    }
    @Override public String valueHint() { return "text"; }
}
