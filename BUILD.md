<div align="center">

# 🔨 Building WorldGuardNeo

**How to build the server-side mod from source.**

**English** · [Русский](BUILD_RU.md)

[🏠 Home](README.md) · **🔨 Building** · [🔑 Permissions](PERMISSIONS.md) · [⚙️ API](API.md) · [📋 Changelog](CHANGELOG.md)

</div>

---

## Requirements

| Tool | Version |
| --- | --- |
| JDK | **21** (required) |
| NeoForge | 21.1.233 (range `[21.1.0,)`) |
| Minecraft | 1.21.1 |
| Gradle | bundled wrapper (`./gradlew`) — no separate install needed |

The build is set up for NeoForge 1.21.1 and Java 21. Gradle downloads the right JDK on first run if it's missing, but some Java install is still needed to bootstrap the wrapper.

## Quick build

```bash
./gradlew clean build
```

The finished `.jar` lands in `build/libs/`. That's the file for the server's `mods/` folder.

## Project layout

The structure follows the NeoForge MDK / mod-generator template:

```
worldguardneo/
├── build.gradle.kts          # build config (server-only)
├── gradle.properties         # versions, mod metadata
├── settings.gradle.kts
├── gradlew / gradlew.bat     # Gradle wrapper
└── src/main/
    ├── java/                 # source (60 files)
    └── resources/
        ├── META-INF/neoforge.mods.toml
        ├── assets/worldguardneo/lang/   # en_us, ru_ru
        ├── worldguardneo.mixins.json
        ├── pack.mcmeta
        └── logo.png
```

## Dependencies

* **WorldEdit** (≥ 7.3) — **required at runtime**. Regions are created exclusively from WorldEdit selections (`//wand`, `//pos1`/`//pos2`, `//sel poly`). The server won't load without it. **WorldEdit is NOT needed to compile** — the mod talks to it purely through reflection, so it builds without WorldEdit on the classpath.
* **LuckPerms** (≥ 5.4) — optional. Fine-grained permissions via groups/contexts + per-group region limits. Without it, all permissions map to OP levels.
* **BlueMap** / **squaremap** — optional. Region rendering on the web map.
* **WorldEditCUI** (client only) — selection highlighting. Provided by WorldEdit itself; nothing to install server-side.

For alternative region storage (`storage-format`), the matching JDBC driver must be on the server classpath: **sqlite-jdbc** for `sqlite`, **H2** for `h2` (LuckPerms already ships H2), **mysql-connector-j** for `mysql`. Without the driver the mod falls back to `json` automatically.

## Building without compile dependencies

The mod is deliberately designed to compile without any third-party mods on the classpath:

- **WorldEdit** — reflection only (`Class.forName`), no direct imports.
- **LuckPerms**, **night-config (TOML)**, **fastutil**, **Mixin** — declared `compileOnly` and provided at runtime by NeoForge or installed mods.
- **JDBC drivers** (sqlite/H2/MySQL) — loaded reflectively at runtime, not part of the build.

So `./gradlew build` works out of the box with nothing to install manually.

## Installing the built mod

1. Drop `worldguardneo-1.0.jar` **and WorldEdit** into the server's `mods/` folder.
2. *(Optional)* add LuckPerms, BlueMap, squaremap, or a JDBC driver.
3. Start the server once to generate `config/worldguardneo/config.toml`.
4. Adjust the config if needed and run `/rg reload`.

## Troubleshooting

**`Could not find com.sk89q.worldedit:worldedit-neoforge:...`** — this shouldn't happen in the current version: WorldEdit was removed from build dependencies. If you hit it, make sure you're using the current `build.gradle.kts` (no `compileOnly("com.sk89q.worldedit...")` line).

**Gradle can't find JDK 21** — install JDK 21 or let Gradle download it automatically (any Java is needed to start the wrapper).

## License

Released under the **GNU General Public License v3.0 or later**. See [`LICENSE`](LICENSE) for the full text.
