// FlagRegistryTest — flag registry mechanics & invariants (distinct from FlagContractTest's
// per-flag table): lookup, duplicate-registration guard, name/permission/descriptionKey format
// for EVERY flag, value-hint non-empty, equals/hashCode contract, and the default-state invariant.
// Pure JVM.
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.flags.*;

import java.util.Set;
import java.util.regex.Pattern;

public final class FlagRegistryTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

    public static void main(String[] args) {
        Flags.bootstrap();

        check("registry non-empty", Flags.all().size() == 95);

        // Every registered flag: round-trips through get(), well-formed name/permission/descKey/hint.
        for (Flag<?> f : Flags.all()) {
            String name = f.name();
            check("get(" + name + ") returns same instance", Flags.get(name) == f);
            check("isRegistered(" + name + ")", Flags.isRegistered(name));
            check("name valid (" + name + ")", NAME.matcher(name).matches());
            check("permission format (" + name + ")", f.permission().equals("worldguardneo.flag." + name));
            check("descriptionKey format (" + name + ")", f.descriptionKey().equals("flag." + name + ".desc"));
            check("valueHint non-empty (" + name + ")", f.valueHint() != null && !f.valueHint().isEmpty());
            check("equals self (" + name + ")", f.equals(f) && f.hashCode() == f.hashCode());
        }

        // Unknown lookups.
        check("get(unknown) null", Flags.get("definitely-not-a-flag") == null);
        check("isRegistered(unknown) false", !Flags.isRegistered("definitely-not-a-flag"));
        check("get(null) null-safe", safeGetNull());

        // Duplicate registration is rejected (and does NOT mutate the registry).
        int before = Flags.all().size();
        boolean threw = false;
        try { Flags.register(new StateFlag("pvp", true)); } catch (IllegalStateException e) { threw = true; }
        check("duplicate register throws", threw);
        check("registry unchanged after rejected dup", Flags.all().size() == before);

        // equals is by NAME (two distinct instances with the same name are equal).
        StateFlag a = new StateFlag("temp-equality-flag", true);
        StateFlag b = new StateFlag("temp-equality-flag", false);
        check("equals by name (distinct instances)", a.equals(b) && a.hashCode() == b.hashCode());
        check("not-equals different name", !a.equals(new StateFlag("other-temp-flag", true)));

        // Default-state invariant: exactly invincible / keep-inventory / keep-xp default to DENY.
        Set<String> expectedDeny = Set.of("invincible", "keep-inventory", "keep-xp");
        int denyCount = 0;
        for (Flag<?> f : Flags.all()) {
            if (f instanceof StateFlag sf) {
                boolean deny = !sf.defaultAllow();
                if (deny) {
                    denyCount++;
                    check("expected default-deny flag: " + f.name(), expectedDeny.contains(f.name()));
                }
            }
        }
        check("exactly 3 default-deny state flags", denyCount == 3);

        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static boolean safeGetNull() {
        try { return Flags.get(null) == null; } catch (Exception e) { return false; }
    }
}
