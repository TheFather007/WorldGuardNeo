// ConfigTest — exercises the REAL config layer (night-config TOML): loadOrCreate writes a default
// config.toml when absent; reads custom values back (including the v1.3 claim-expiry / economy
// keys); and a wrong-typed value falls back to the field default rather than throwing.
// Uses temp dirs. Needs night-config (toml+core) + mojang-logging on the classpath (run.sh provides).
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.config.WGConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class ConfigTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    public static void main(String[] args) throws Exception {
        defaults();
        customValues();
        malformedValues();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static void defaults() throws Exception {
        Path dir = Files.createTempDirectory("wgn-cfg-def");
        WGConfig cfg = WGConfig.loadOrCreate(dir);
        check("defaults: config.toml created", Files.exists(dir.resolve("config.toml")));
        WGConfig.GlobalSection g = cfg.global();
        check("defaults: max-regions-per-player", g.maxRegionsPerPlayer == 7);
        check("defaults: storage json", "json".equals(g.storageFormat));
        check("defaults: economy off", !g.economyEnabled);
        check("defaults: economy start 0", g.economyStartBalance == 0.0);
        check("defaults: currency coins", "coins".equals(g.economyCurrency));
        check("defaults: claim-expiry off", !g.claimExpiryEnabled);
        check("defaults: claim-expiry days 60", g.claimExpiryDays == 60);
        // Re-loading the freshly-written file reproduces the defaults (write→read round-trip).
        WGConfig again = WGConfig.loadOrCreate(dir);
        check("defaults: round-trip max-regions", again.global().maxRegionsPerPlayer == 7);
        check("defaults: round-trip currency", "coins".equals(again.global().economyCurrency));
    }

    static void customValues() throws Exception {
        Path dir = Files.createTempDirectory("wgn-cfg-custom");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.toml"),
                "max-regions-per-player = 99\n" +
                "storage-format = \"sqlite\"\n" +
                "[economy]\n" +
                "enabled = true\n" +
                "starting-balance = 250.0\n" +
                "currency-name = \"gold\"\n" +
                "[claim-expiry]\n" +
                "enabled = true\n" +
                "days = 30\n" +
                "check-hours = 12\n", StandardCharsets.UTF_8);
        WGConfig.GlobalSection g = WGConfig.loadOrCreate(dir).global();
        check("custom: max-regions 99", g.maxRegionsPerPlayer == 99);
        check("custom: storage sqlite", "sqlite".equals(g.storageFormat));
        check("custom: economy on", g.economyEnabled);
        check("custom: starting-balance 250", g.economyStartBalance == 250.0);
        check("custom: currency gold", "gold".equals(g.economyCurrency));
        check("custom: claim-expiry on", g.claimExpiryEnabled);
        check("custom: claim-expiry days 30", g.claimExpiryDays == 30);
        check("custom: claim-expiry check-hours 12", g.claimExpiryCheckHours == 12);
    }

    static void malformedValues() throws Exception {
        Path dir = Files.createTempDirectory("wgn-cfg-bad");
        Files.createDirectories(dir);
        // Wrong-typed values (string where a number/bool is expected) → field defaults, no crash.
        Files.writeString(dir.resolve("config.toml"),
                "max-region-volume = \"huge\"\n" +
                "max-regions-per-player = \"lots\"\n" +
                "[economy]\n" +
                "enabled = \"yes-please\"\n" +
                "starting-balance = \"plenty\"\n", StandardCharsets.UTF_8);
        WGConfig.GlobalSection g = WGConfig.loadOrCreate(dir).global();
        check("malformed: int → default", g.maxRegionVolume == 50_000_000);
        check("malformed: int2 → default", g.maxRegionsPerPlayer == 7);
        check("malformed: bool → default", !g.economyEnabled);
        check("malformed: double → default", g.economyStartBalance == 0.0);
    }
}
