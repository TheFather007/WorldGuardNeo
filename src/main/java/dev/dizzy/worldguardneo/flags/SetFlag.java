package dev.dizzy.worldguardneo.flags;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.LinkedHashSet;
import java.util.Set;

/** A flag holding a set of strings, e.g. blocked-mobs, allowed-cmds. */
public final class SetFlag extends Flag<Set<String>> {

    /** Pre-compiled split pattern — avoids re-compiling the regex on every parse call. */
    private static final java.util.regex.Pattern SPLIT = java.util.regex.Pattern.compile("[,;\\s]+");

    public SetFlag(String name) { super(name); }

    @Override public Set<String> parse(String input) {
        if (input == null || input.isEmpty()) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String tok : SPLIT.split(input)) {
            // SPLIT consumes runs of whitespace/comma/semicolon so tok already has no
            // leading/trailing whitespace — no extra trim() needed.
            if (!tok.isEmpty()) out.add(tok);
        }
        return out.isEmpty() ? null : out;
    }

    @Override public JsonElement toJson(Set<String> value) {
        if (value == null) return null;
        JsonArray arr = new JsonArray();
        for (String s : value) arr.add(s);
        return arr;
    }

    @Override public Set<String> fromJson(JsonElement json) {
        if (json == null || json.isJsonNull() || !json.isJsonArray()) return null;
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement e : json.getAsJsonArray()) {
            if (e == null || e.isJsonNull()) continue;
            try { out.add(e.getAsString()); }
            catch (RuntimeException ignored) { /* skip non-string element */ }
        }
        return out.isEmpty() ? null : out;
    }

    @Override public String display(Set<String> v) {
        return v == null ? "<unset>" : String.join(", ", v);
    }
    @Override public String valueHint() { return "comma-separated list"; }
}
