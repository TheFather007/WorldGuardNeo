plugins {
    id("net.neoforged.moddev") version "2.0.141"
    `java-library`
}

val modId       = providers.gradleProperty("mod_id").get()
val modVersion  = providers.gradleProperty("mod_version").get()
val modGroupId  = providers.gradleProperty("mod_group_id").get()
val javaVersion = providers.gradleProperty("java_version").get()
val neoVersion  = providers.gradleProperty("neo_version").get()

group   = modGroupId
version = modVersion
base { archivesName.set(modId) }

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion.toInt()))
    // No withSourcesJar(): this is a server-side production mod, a sources jar is just
    // extra build output nobody deploys.
}

repositories {
    mavenCentral()
    maven {
        name = "LuckPerms"
        url  = uri("https://repo.lucko.me/")
    }
}

neoForge {
    version = neoVersion

    parchment {
        // Parameter mappings for nicer names in the IDE. Dev-only — does not affect the jar.
        mappingsVersion = "2024.07.28"
        minecraftVersion = "1.21"
    }

    runs {
        // Server-only mod: a dedicated-server run is the only one we ship/test against.
        // (No client run and no data-generation run — this mod has no client features and
        // no generated assets; its lang files are authored by hand.)
        create("server") {
            server()
            programArgument("--nogui")
        }
    }

    mods {
        create(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    // LuckPerms API — compile-time only; soft dependency at runtime (OP-level fallback if absent).
    compileOnly("net.luckperms:api:5.4")

    // FastUtil — provided transitively by Minecraft 1.21.x at runtime. The VERSION MUST match what
    // Minecraft pins (1.21.1 declares `strictly 8.5.12`); any other version fails classpath
    // resolution. Used by SpatialIndex to avoid Long autoboxing on the region-lookup hot path.
    compileOnly("it.unimi.dsi:fastutil:8.5.12")

    // NOTE: WorldGuardNeo has NO WorldEdit dependency (compile or runtime). Region selection is
    // built in (see the dev.thefather007.worldguardneo.selection package); the selection outline is
    // pushed to clients over the worldedit:cui plugin channel, which WorldEditCUI renders — but that
    // is a purely optional CLIENT mod and is never on the server classpath.

    // Mixin annotations — must match the SpongeMixin version NeoForge 1.21.1 bundles at runtime.
    compileOnly("org.spongepowered:mixin:0.8.6")
    compileOnly("io.github.llamalad7:mixinextras-common:0.4.1")

    // night-config (TOML) for the human-editable config.toml. NeoForge ships core+toml at
    // runtime (it uses them for its own configs), so these are compile-only.
    compileOnly("com.electronwill.night-config:core:3.6.6")
    compileOnly("com.electronwill.night-config:toml:3.6.6")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaVersion.toInt())
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Specification-Title"    to modId,
            "Specification-Vendor"   to "TheFather007",
            "Specification-Version"  to "1",
            "Implementation-Title"   to project.name,
            "Implementation-Version" to project.version,
            "Implementation-Vendor"  to "TheFather007"
        )
    }
}

tasks.named<ProcessResources>("processResources") {
    val replacements = mapOf(
        "mod_id"                  to modId,
        "mod_name"                to providers.gradleProperty("mod_name").get(),
        "mod_license"             to providers.gradleProperty("mod_license").get(),
        "mod_version"             to modVersion,
        "mod_authors"             to providers.gradleProperty("mod_authors").get(),
        "mod_description"         to providers.gradleProperty("mod_description").get(),
        "minecraft_version_range" to providers.gradleProperty("minecraft_version_range").get(),
        "neo_version_range"       to providers.gradleProperty("neo_version_range").get(),
        "loader_version_range"    to providers.gradleProperty("loader_version_range").get()
    )
    inputs.properties(replacements)
    filesMatching(listOf("META-INF/neoforge.mods.toml", "pack.mcmeta")) {
        expand(replacements)
    }
}
