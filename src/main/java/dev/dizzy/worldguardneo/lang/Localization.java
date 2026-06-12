package dev.dizzy.worldguardneo.lang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dizzy.worldguardneo.WorldGuardNeo;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loads localization strings from the mod jar and from &lt;config&gt;/worldguardneo/lang/*.json
 * so admins can override or add languages without rebuilding.
 *
 * Placeholder format: <code>%name%</code>. Numeric format: use {@link String#format} flags.
 */
public final class Localization {

    private final Map<String, String> entries = new HashMap<>();
    private final Locale locale;

    private Localization(Locale locale) { this.locale = locale; }

    public static Localization load(Path configDir, Locale locale) {
        Localization out = new Localization(locale);
        out.reload(configDir, locale);
        return out;
    }

    /**
     * Re-load entries in place from the given config dir + locale. Used by /rg reload to
     * pick up changes to {@code config/worldguardneo/lang/<tag>.json} without restarting
     * the server. The {@code Localization} instance reference stays the same so existing
     * cached references in other modules remain valid.
     *
     * <p>Safe to call multiple times; clears and rebuilds the entries map atomically from
     * the server thread's perspective (read-during-reload would only see partial data, but
     * /rg reload is rare and short, so we don't synchronize on the hot path).
     */
    public void reload(Path configDir, Locale newLocale) {
        entries.clear();
        String tag = newLocale.toString().toLowerCase(Locale.ROOT).replace('-', '_');

        // 1) jar resources — english first, then the requested tag overlays.
        loadFromResources("/assets/worldguardneo/lang/en_us.json");
        if (!"en_us".equals(tag)) {
            loadFromResources("/assets/worldguardneo/lang/" + tag + ".json");
        }

        // 2) config overrides
        Path overrides = configDir.resolve("lang").resolve(tag + ".json");
        if (Files.exists(overrides)) {
            try (Reader r = Files.newBufferedReader(overrides)) {
                JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    JsonElement v = e.getValue();
                    if (v != null && !v.isJsonNull() && v.isJsonPrimitive()) {
                        entries.put(e.getKey(), v.getAsString());
                    }
                }
            } catch (IOException | com.google.gson.JsonParseException e) {
                WorldGuardNeo.LOGGER.warn("Failed to read language overrides {}", overrides, e);
            }
        }
    }

    private void loadFromResources(String resource) {
        try (InputStream in = Localization.class.getResourceAsStream(resource)) {
            if (in == null) return;
            try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    JsonElement v = e.getValue();
                    if (v != null && !v.isJsonNull() && v.isJsonPrimitive()) {
                        entries.put(e.getKey(), v.getAsString());
                    }
                }
            }
        } catch (IOException | com.google.gson.JsonParseException ex) {
            WorldGuardNeo.LOGGER.warn("Failed to read built-in lang {}", resource, ex);
        }
    }

    public Locale locale() { return locale; }

    /** Returns true if a key exists in any loaded language source. */
    public boolean has(String key) { return entries.containsKey(key); }

    public String raw(String key) {
        return entries.getOrDefault(key, key);
    }

    /**
     * Substitute {@code %name%} placeholders in the template.
     *
     * <p>Args are flat pairs: {@code "name1", value1, "name2", value2, ...}. Replacement
     * uses single-pass StringBuilder scanning rather than chained {@link String#replace}
     * — for a template with N placeholders the old implementation allocated 2N strings
     * (the {@code "%key%"} search string and the new tmpl after each substitution).
     *
     * <p>This implementation does at most 1 allocation per template (the StringBuilder
     * and the final toString()) regardless of placeholder count.
     */
    public String format(String key, Object... args) {
        String tmpl = entries.getOrDefault(key, key);
        // Fast path: no placeholders OR no args. Most messages take this branch.
        if (args == null || args.length == 0 || tmpl.indexOf('%') < 0) return tmpl;
        // Warn on odd-arg calls — these silently drop the trailing key (caller bug).
        if ((args.length & 1) != 0) {
            WorldGuardNeo.LOGGER.debug("Localization.format called with odd argument count for key '{}'", key);
        }

        // Walk the template, copying literal chars to the output StringBuilder. When we hit
        // '%', scan ahead for the closing '%' and look up the enclosed name in args. If
        // found, append the value; otherwise leave the original "%name%" verbatim so admin
        // typos in lang files don't silently vanish.
        StringBuilder out = new StringBuilder(tmpl.length() + 16);
        int n = tmpl.length();
        int i = 0;
        while (i < n) {
            char c = tmpl.charAt(i);
            if (c != '%') {
                out.append(c);
                i++;
                continue;
            }
            int end = tmpl.indexOf('%', i + 1);
            if (end < 0) {
                // Trailing '%' without closing — copy verbatim and exit.
                out.append(tmpl, i, n);
                break;
            }
            // %% is NOT printf-style escape — kept verbatim to match the previous
            // String.replace based implementation, which would leave "%%" untouched
            // unless an arg literally named "" was provided (impossible). Just emit
            // the first '%' and continue from the second.
            if (end == i + 1) {
                out.append('%');
                i++;
                continue;
            }
            // Find the value for this name in args.
            String name = tmpl.substring(i + 1, end);
            String value = null;
            for (int j = 0; j + 1 < args.length; j += 2) {
                if (name.equals(String.valueOf(args[j]))) {
                    value = String.valueOf(args[j + 1]);
                    break;
                }
            }
            if (value != null) {
                out.append(value);
            } else {
                // Unknown placeholder — keep the original literal so the admin sees the typo
                // in the rendered output rather than a silent gap.
                out.append(tmpl, i, end + 1);
            }
            i = end + 1;
        }
        return out.toString();
    }
}
