// JsonStorageTest — exercises the REAL JSON storage backend (disk I/O), not just the in-memory
// codec: round-trip across multiple worlds, empty world, world-key filename sanitization, atomic
// rewrite (save over an existing file), and the corrupt-file → ".corrupt-" quarantine that prevents
// the next save from destroying recoverable data. Uses a temp dir. Needs gson + mojang-logging on
// the classpath (run.sh provides them).
//
// Run via tests/run.sh.

import dev.thefather007.worldguardneo.flags.*;
import dev.thefather007.worldguardneo.region.*;
import dev.thefather007.worldguardneo.storage.JsonRegionStorage;
import dev.thefather007.worldguardneo.util.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.stream.Stream;

public final class JsonStorageTest {

    static int passed = 0, failed = 0;
    static void check(String n, boolean c) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }

    static final UUID U1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID U2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    public static void main(String[] args) throws Exception {
        Flags.bootstrap();
        Path tmp = Files.createTempDirectory("wgn-store");

        roundTrip(tmp);
        multiWorld(tmp);
        emptyWorld(tmp);
        sanitizedKey(tmp);
        quarantine(tmp);

        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static CuboidRegion sample(String id) {
        CuboidRegion r = new CuboidRegion(id, new Vec3(-10, 5, -10), new Vec3(20, 70, 20));
        r.setPriority(4);
        r.owners().add(U1);
        r.members().add(U2);
        r.ownerGroups().add("admins");
        r.setFlag(Flags.PVP, StateFlag.State.DENY);
        r.setFlagGroup(Flags.PVP, RegionGroup.NON_MEMBERS);
        r.setFlag(Flags.GREETING, "hi %player%");
        r.setFlag(Flags.HEAL_AMOUNT, 5);
        r.setFlag(Flags.ON_ENTRY, "say hello");
        return r;
    }

    static void roundTrip(Path tmp) throws IOException {
        JsonRegionStorage st = new JsonRegionStorage(tmp);
        RegionManager src = new RegionManager("minecraft:overworld");
        CuboidRegion parent = new CuboidRegion("estate", new Vec3(-50, 0, -50), new Vec3(50, 100, 50));
        CuboidRegion child = sample("house");
        src.add(parent); src.add(child); child.setParent(parent);
        src.globalRegion().setFlag(Flags.TNT, StateFlag.State.DENY);
        st.save("minecraft:overworld", src);

        RegionManager dst = new RegionManager("minecraft:overworld");
        new JsonRegionStorage(tmp).load("minecraft:overworld", dst); // fresh storage instance reads disk
        check("roundtrip: count", dst.size() == 2);
        ProtectedRegion h = dst.get("house").orElse(null);
        check("roundtrip: region present", h != null);
        if (h != null) {
            check("roundtrip: priority", h.priority() == 4);
            check("roundtrip: owner", h.isOwner(U1));
            check("roundtrip: member", h.isMember(U2));
            check("roundtrip: owner-group", h.ownerGroupsView().contains("admins"));
            check("roundtrip: state flag", h.getFlag(Flags.PVP) == StateFlag.State.DENY);
            check("roundtrip: flag group", h.getFlagGroup(Flags.PVP) == RegionGroup.NON_MEMBERS);
            check("roundtrip: string flag", "hi %player%".equals(h.getFlag(Flags.GREETING)));
            check("roundtrip: int flag", Integer.valueOf(5).equals(h.getFlag(Flags.HEAL_AMOUNT)));
            check("roundtrip: on-entry flag", "say hello".equals(h.getFlag(Flags.ON_ENTRY)));
            check("roundtrip: parent linked", h.parent() != null && h.parent().id().equals("estate"));
        }
        check("roundtrip: global flag", dst.globalRegion().getFlag(Flags.TNT) == StateFlag.State.DENY);

        // Atomic rewrite: saving again over the existing file leaves a single valid file.
        src.add(sample("shop"));
        st.save("minecraft:overworld", src);
        RegionManager dst2 = new RegionManager("minecraft:overworld");
        new JsonRegionStorage(tmp).load("minecraft:overworld", dst2);
        check("rewrite: updated count", dst2.size() == 3 && dst2.get("shop").isPresent());
        check("rewrite: no leftover .tmp", noTmpFiles(tmp));
    }

    static void multiWorld(Path tmp) throws IOException {
        JsonRegionStorage st = new JsonRegionStorage(tmp);
        RegionManager nether = new RegionManager("minecraft:the_nether");
        nether.add(sample("fortress"));
        st.save("minecraft:the_nether", nether);
        RegionManager loaded = new RegionManager("minecraft:the_nether");
        new JsonRegionStorage(tmp).load("minecraft:the_nether", loaded);
        check("multiworld: nether isolated", loaded.size() == 1 && loaded.get("fortress").isPresent());
        // Overworld (from roundTrip) is unaffected.
        RegionManager ow = new RegionManager("minecraft:overworld");
        new JsonRegionStorage(tmp).load("minecraft:overworld", ow);
        check("multiworld: overworld intact", ow.get("house").isPresent());
    }

    static void emptyWorld(Path tmp) throws IOException {
        JsonRegionStorage st = new JsonRegionStorage(tmp);
        RegionManager empty = new RegionManager("test:empty");
        st.save("test:empty", empty);
        RegionManager loaded = new RegionManager("test:empty");
        new JsonRegionStorage(tmp).load("test:empty", loaded);
        check("empty world loads to 0 regions", loaded.size() == 0);
    }

    static void sanitizedKey(Path tmp) throws IOException {
        // Modded dimension ids contain ':' (and possibly other fs-unsafe chars) → sanitized filename.
        String key = "modded:weird/dim";
        JsonRegionStorage st = new JsonRegionStorage(tmp);
        RegionManager m = new RegionManager(key);
        m.add(sample("zone"));
        st.save(key, m);
        RegionManager loaded = new RegionManager(key);
        new JsonRegionStorage(tmp).load(key, loaded);
        check("sanitized key round-trips", loaded.get("zone").isPresent());
        check("no ':' or '/' in any region filename", noUnsafeNames(tmp));
    }

    static void quarantine(Path tmp) throws IOException {
        // Save a valid world, then corrupt its file on disk; loading must NOT wipe-and-overwrite —
        // it quarantines the bad file to <name>.corrupt-<ts> and leaves the manager empty.
        String key = "test:corrupt";
        JsonRegionStorage st = new JsonRegionStorage(tmp);
        RegionManager m = new RegionManager(key);
        m.add(sample("doomed"));
        st.save(key, m);
        Path file = findRegionFile(tmp, "test_corrupt");
        check("quarantine: file written", file != null);
        if (file == null) return;
        Files.writeString(file, "{ this is not valid json ", StandardCharsets.UTF_8);
        RegionManager loaded = new RegionManager(key);
        new JsonRegionStorage(tmp).load(key, loaded);
        check("quarantine: manager left empty", loaded.size() == 0);
        check("quarantine: .corrupt- backup created", hasCorruptBackup(tmp));
    }

    /* ---- fs helpers ---- */
    static boolean noTmpFiles(Path tmp) throws IOException {
        try (Stream<Path> s = Files.list(tmp.resolve("regions"))) {
            return s.noneMatch(p -> p.toString().endsWith(".tmp"));
        }
    }
    static boolean noUnsafeNames(Path tmp) throws IOException {
        try (Stream<Path> s = Files.list(tmp.resolve("regions"))) {
            return s.map(p -> p.getFileName().toString()).noneMatch(n -> n.contains(":") || n.contains("/"));
        }
    }
    static Path findRegionFile(Path tmp, String namePrefix) throws IOException {
        try (Stream<Path> s = Files.list(tmp.resolve("regions"))) {
            return s.filter(p -> p.getFileName().toString().startsWith(namePrefix)
                              && p.getFileName().toString().endsWith(".json")).findFirst().orElse(null);
        }
    }
    static boolean hasCorruptBackup(Path tmp) throws IOException {
        try (Stream<Path> s = Files.list(tmp.resolve("regions"))) {
            return s.anyMatch(p -> p.getFileName().toString().contains(".corrupt-"));
        }
    }
}
