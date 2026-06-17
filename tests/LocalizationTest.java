// LocalizationTest — Localization.format() placeholder substitution edge cases, driven by a temp
// override lang file (so we control the templates). Exercises only valid paths (the error/odd-arg
// branches touch Minecraft logging by design). Pure JVM.
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.lang.Localization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class LocalizationTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }
    static void eq(String n, String got, String exp) {
        if (exp.equals(got)) passed++; else { failed++; System.out.println("FAIL: " + n + " — got <" + got + "> expected <" + exp + ">"); }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("wgn-lang");
        Path lang = dir.resolve("lang");
        Files.createDirectories(lang);
        // Custom 'zz' locale templates with placeholder edge cases.
        Files.writeString(lang.resolve("zz.json"),
                "{\n" +
                "  \"t.simple\": \"Hello %name%!\",\n" +
                "  \"t.multi\": \"%a% and %b% and %a%\",\n" +
                "  \"t.unknown\": \"Missing %nope% here\",\n" +
                "  \"t.pct\": \"100%% done\",\n" +
                "  \"t.trailing\": \"ends with %\",\n" +
                "  \"t.plain\": \"no placeholders\",\n" +
                "  \"t.num\": \"x=%n%\"\n" +
                "}\n");

        Localization i18n = Localization.load(dir, Locale.of("zz"));

        eq("simple substitution", i18n.format("t.simple", "name", "Bob"), "Hello Bob!");
        eq("repeated + multiple placeholders", i18n.format("t.multi", "a", "X", "b", "Y"), "X and Y and X");
        eq("unknown placeholder kept verbatim", i18n.format("t.unknown", "other", "Z"), "Missing %nope% here");
        eq("%% kept (no args)", i18n.format("t.pct"), "100%% done");
        eq("%% kept (with args)", i18n.format("t.pct", "x", "y"), "100%% done");
        eq("trailing % kept", i18n.format("t.trailing", "x", "y"), "ends with %");
        eq("plain no-placeholder", i18n.format("t.plain", "x", "y"), "no placeholders");
        eq("numeric arg stringified", i18n.format("t.num", "n", 42), "x=42");
        eq("missing key returns key", i18n.format("t.does-not-exist", "a", "b"), "t.does-not-exist");
        eq("raw returns template unsubstituted", i18n.raw("t.simple"), "Hello %name%!");
        check("has present key", i18n.has("t.simple"));
        check("has absent key", !i18n.has("t.nope"));

        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }
}
