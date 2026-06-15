<div align="center">

# WorldGuardNeo

**Server-side region protection for NeoForge — a WorldGuard-model reimplementation. Regions are created from WorldEdit selections and protected by 80+ per-region flags.**

![Version](https://img.shields.io/badge/Version-1.2-44cc11)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-44cc11)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-e87b1e)
![Optional](https://img.shields.io/badge/Optional-WorldEdit%207.3%2B-3b82f6)
![Optional](https://img.shields.io/badge/Optional-LuckPerms%205.4%2B-9b59b6)
![Side](https://img.shields.io/badge/Side-Server%20only-6b7280)
![License](https://img.shields.io/badge/License-GPL--3.0-3b82f6)

**English** · [Русский](README_RU.md)

[🏠 Home](README.md) · [🔨 Building](BUILD.md) · [🔑 Permissions](PERMISSIONS.md) · [🚩 Flags](FLAGS.md) · [⚙️ API](API.md) · [📋 Changelog](CHANGELOG.md)

</div>

---

WorldGuardNeo brings WorldGuard-style land protection to NeoForge. You select an area with **WorldEdit**, claim it as a region, and control what can happen inside with a large set of flags — building, PvP, mob spawning, fire, explosions, redstone, fluids, and much more. It also protects **between adjacent regions**, so a neighbour can't grief you across a shared border. Everything is configured in a commented TOML file and applies live with `/rg reload`.

## Features

- **Regions from WorldEdit** — select with `//wand` (cuboid or polygon) and claim. No separate wand to learn.
- **80+ flags** — build, block-break/place, interact, use, chest-access, pvp, mob-spawning, mob-damage, tnt/creeper/other explosions, fire-spread, lava-fire, lightning, redstone, pistons, dispenser-output, fluids, growth, entry/exit, greetings, game-mode/time/weather locks, keep-inventory/xp, and more.
- **Per-region & per-world** — flags per region; world-wide toggles per dimension via override files.
- **Membership model** — owners and members; build-type flags respect them (WorldGuard "private by default").
- **Parents & priority** — regions inherit flags from a parent; overlapping regions resolve by priority (DENY beats ALLOW).
- **Adjacency-grief protection** — lava/water/fire spread, pistons, dispensers and tree growth are blocked from crossing a region boundary into a foreign claim.
- **Crop & farm protection** — non-members can't trample farmland (jump-destroying crops); stripping logs, tilling dirt and path-making by strangers go through the same interact/build checks.
- **Mob-griefing & trap control** — the `mob-grief` flag stops mobs altering blocks in a claim (endermen, sheep, etc.); pressure plates and tripwires can't be triggered by strangers, mobs or thrown items.
- **Pluggable storage** — `json` (default), `sqlite`, `h2`, or `mysql`. Any DB backend falls back to JSON if its driver is missing.
- **LuckPerms integration** — permissions resolved through LuckPerms when installed (OP-level fallback otherwise), plus per-group region limits.
- **Web-map integration** — regions render on **BlueMap** and **squaremap** if present.
- **Automatic backups** — scheduled, rotating, gzip-compressed region backups.
- **Localized** — English and Russian out of the box; drop a `<tag>.json` for more.

## Requirements

**Required**

- Minecraft **1.21.1**
- NeoForge **21.1.x**
- **WorldEdit** for NeoForge **7.3+** — *optional*, needed only to create regions (`/rg claim`, `/rg redefine`) from its selections; the mod loads and all other features work without it

**Optional**

| Dependency | Enables |
| --- | --- |
| **LuckPerms** 5.4+ | Permission nodes + per-group region limits (otherwise OP levels) |
| **BlueMap** | Region rendering on the 3D web map |
| **squaremap** | Region rendering on the 2D web map |
| **sqlite-jdbc** jar | `storage-format = "sqlite"` |
| **H2** jar | `storage-format = "h2"` (LuckPerms already ships H2) |
| **mysql-connector-j** jar | `storage-format = "mysql"` |
| **JDK 21** | Building from source only |

The mod is **server-side only**. Vanilla clients connect normally; no client-side installation is required. WorldEdit renders the selection box (via WECUI) for clients that have it. Any database backend whose driver is absent falls back to JSON automatically, so a missing optional jar never stops the server.

## Installation

1. Drop WorldGuardNeo's `.jar` into the server's `mods/` folder. Add **WorldEdit** too if you want to create regions (`/rg claim`).
2. *(Optional)* add LuckPerms, BlueMap, or squaremap.
3. Start the server once to generate `config/worldguardneo/config.toml`.
4. Adjust settings if needed and run `/rg reload`.

## Quick start

```
//wand                 (WorldEdit) left/right-click two corners
/rg claim myregion     claim the selection as a region named "myregion"
/rg flag myregion pvp deny
/rg addmember myregion Steve
```

`/rg info myregion` also highlights the region's outline by loading its bounds into your WorldEdit selection (rendered by WECUI), so `/rg redefine myregion` can resize it after making a new selection.

## Configuration

All settings live in `config/worldguardneo/config.toml` — a TOML file with a comment on every key. Per-world overrides live in `config/worldguardneo/worlds/<dimension>.toml` (`:` replaced by `_`) and inherit from the global `[defaults]` section. Regions themselves are stored separately under `config/worldguardneo/regions/`.

### Storage

```toml
# json | sqlite | h2 | mysql  (requires a server restart)
storage-format = "json"
```

- **json** — one file per world, no dependencies (default).
- **sqlite** — embedded `regions.sqlite`; needs an sqlite-jdbc jar.
- **h2** — embedded `regions_h2`; needs an H2 jar (LuckPerms ships one).
- **mysql** — external server; configure `[mysql]` below and add `mysql-connector-j`.

Any DB backend that can't load its driver falls back to `json` automatically.

```toml
[mysql]
host = "localhost"
port = 3306
database = "worldguardneo"
user = "root"
password = ""
use-ssl = false
table = "world_regions"            # change to share one DB between servers
connection-timeout-seconds = 10
properties = ["serverTimezone=UTC"] # extra "key=value" JDBC params appended to the URL
```

The MySQL backend accepts the **Connector/J** *or* the **MariaDB** driver, and opens connections through the driver directly (not `DriverManager`), so it works even when the driver jar is loaded by a different classloader than the mod — the cause of the old "DB init failed → fell back to JSON" reports. The same direct-driver path is used for SQLite and H2.

### Per-group region limits (LuckPerms)

```toml
[group-region-limits]
default = 7
vip = 15
premium = 30
```

A player gets the **highest** limit across their LuckPerms groups. Without LuckPerms, `max-regions-per-player` applies to everyone.

### Backups

```toml
[backup]
enabled = true
interval-minutes = 60
retain-count = 10
compress = true
```

### Per-world defaults

```toml
[defaults]
use-regions = true
disable-explosions-around-regions = true
prevent-fire-spread = false
prevent-lava-fire = false
# ...see the file for the full list, each documented inline
```

### Auto-flags & vertical protection (per world)

Every newly claimed region in a world can be auto-configured. This is per-world (set it under `[defaults]` or in a `worlds/<dimension>.toml` override):

```toml
[defaults]
# Flags applied to every new region, as "flag=value".
auto-flags = ["pvp=deny", "mob-spawning=deny", "creeper-explosion=deny"]

# Auto-expand new regions vertically so nobody can tunnel in from below or bridge in
# from above: "none" | "full" | "fixed".
vertical-expansion = "full"
vertical-expand-down = 0   # used when "fixed"
vertical-expand-up = 0     # used when "fixed"
```

- **`vertical-expansion = "full"`** stretches the claim from bedrock to the build limit, so a region claimed on the surface is also protected underground and in the sky — the recommended anti-grief setting.
- **`"fixed"`** expands a set number of blocks down/up from the selection.
- Horizontal claim-size limits are checked on the player's **original** selection, so vertical expansion never counts against their area allowance.
- Auto-flags and expansion apply only to **new** claims; existing regions are untouched.

## Commands

Both `/region` and `/rg` work. Region ids are single words.

| Command | Description |
| --- | --- |
| `/rg claim <id>` | Claim your WorldEdit selection as a new region |
| `/rg redefine <id>` | Resize a region to your current selection |
| `/rg remove <id>` | Delete a region |
| `/rg info [id]` | Show a region's type, size, bounds, owners, members, flags — and highlight its outline |
| `/rg list [player]` | List regions (optionally for a player) |
| `/rg lists [radius]` | List regions near you (default radius 50) |
| `/rg flag <id> <flag> [-g group] [value]` | Set/clear a flag (omit value to clear) |
| `/rg flags` | List every available flag with its value hint and description |
| `/rg priority <id> <n>` | Set region priority (owner or admin only) |
| `/rg setparent <id> [parent]` | Set/clear a region's parent (owner or admin only) |
| `/rg addmember/removemember <id> <player>` | Manage members |
| `/rg addowner/removeowner <id> <player>` | Manage owners |
| `/rg teleport <id>` | Teleport to a region's `teleport` flag location |
| `/rg backup [label]` | Create a region backup now (optional name suffix) |
| `/rg backup list` | List existing backups, newest first |
| `/rg save` | Flush regions to storage |
| `/rg reload` | Reload config and language |
| `/rg debug` | Diagnostics: spatial-index stats, active integrations |

## Permissions

With LuckPerms installed it is the sole authority (OP levels are ignored once a user is loaded). Without it, each node maps to an OP level. Note the defaults are **permissive for basic use** — claiming, viewing and deleting your own regions are OP 0 (everyone), while management and admin actions require higher tiers.

| Node | Default | Purpose |
| --- | --- | --- |
| `worldguardneo.region.claim` | OP 0 | Claim regions |
| `worldguardneo.region.delete` | OP 0 | Delete your own regions |
| `worldguardneo.region.info` / `.list` | OP 0 | View / list your regions |
| `worldguardneo.region.teleport` | OP 0 | Teleport to regions |
| `worldguardneo.selection.use` | OP 0 | Receive the custom `//wand` |
| `worldguardneo.region.redefine` | OP 2 | Resize regions |
| `worldguardneo.region.flag` | OP 2 | Set flags |
| `worldguardneo.region.addmember` / `addowner` | OP 2 | Manage members / owners |
| `worldguardneo.region.delete.others` | OP 3 | Delete anyone's regions |
| `worldguardneo.region.bypass` | **OP 5** | Bypass protection (LuckPerms only — never by OP) |
| `worldguardneo.backup` / `reload` | OP 4 | Manual backup / reload config |

**See [PERMISSIONS.md](PERMISSIONS.md) for the complete list** of all nodes, exact OP levels, and LuckPerms examples.

## Flags

WorldGuardNeo ships 80+ flags. Build-type flags (build, block-break, block-place, interact, use, chest-access) follow the membership model; the rest are environmental state flags (allow/deny) or value flags (greetings, locks, limits). Run `/rg flags` in-game to list every flag with its value hint and description.

**See [FLAGS.md](FLAGS.md) for the complete table** — every flag with its type, default, permission node, and description.

## Building

JDK 21 is required. From the project folder:

```
./gradlew clean build
```

The finished `.jar` is written to `build/libs/`. WorldEdit is **not** a compile-time dependency — the mod talks to it purely through reflection, so it builds without WorldEdit on the classpath. WorldEdit is an **optional** runtime dependency (declared in `neoforge.mods.toml`); it is only needed to create regions.

## License

Released under the **GNU General Public License v3.0 or later** (GPL-3.0-or-later). See [`LICENSE`](LICENSE) for the full text.
