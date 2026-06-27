<div align="center">

# WorldGuardNeo

**Server-side region protection for NeoForge — a WorldGuard-model reimplementation. Regions are created with a built-in selection wand and protected by 90+ per-region flags.**

![Version](https://img.shields.io/badge/Version-1.3-44cc11)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-44cc11)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-e87b1e)
![Optional](https://img.shields.io/badge/Optional-LuckPerms%205.4%2B-9b59b6)
![Side](https://img.shields.io/badge/Side-Server%20only-6b7280)
![License](https://img.shields.io/badge/License-GPL--3.0-3b82f6)

**English** · [Русский](README_RU.md)

[🏠 Home](README.md) · [🔨 Building](BUILD.md) · [🔑 Permissions](PERMISSIONS.md) · [🚩 Flags](FLAGS.md) · [⚙️ API](API.md) · [🧩 KubeJS](KUBEJS.md) · [📋 Changelog](CHANGELOG.md)

</div>

---

WorldGuardNeo brings WorldGuard-style land protection to NeoForge. You select an area with the **built-in selection wand**, claim it as a region, and control what can happen inside with a large set of flags — building, PvP, mob spawning, fire, explosions, redstone, fluids, and much more. It also protects **between adjacent regions**, so a neighbour can't grief you across a shared border. Everything is configured in a commented TOML file and applies live with `/rg reload`.

## Features

- **Built-in selection wand** — `/rg wand` hands out a selection item (a stick by default, configurable). Left/right-click two corners for a cuboid, or build a polygon point-by-point. The outline renders client-side for players who have [WorldEditCUI](https://modrinth.com/mod/worldeditcui-forge).
- **90+ flags** — build, block-break/place, interact, use, chest-access, pvp, mob-spawning, mob-damage, tnt/creeper/other explosions, fire-spread, lava-fire, lightning, redstone, pistons, dispenser-output, fluids, growth, entry/exit, greetings, game-mode/time/weather locks, keep-inventory/xp, and more.
- **Per-region & per-world** — flags per region; world-wide toggles per dimension via override files.
- **Membership model** — owners and members; build-type flags respect them (WorldGuard "private by default").
- **Parents & priority** — regions inherit flags from a parent; overlapping regions resolve by priority (DENY beats ALLOW).
- **Adjacency-grief protection** — lava/water/fire spread, pistons, dispensers and tree growth are blocked from crossing a region boundary into a foreign claim.
- **Crop & farm protection** — non-members can't trample farmland (jump-destroying crops); stripping logs, tilling dirt and path-making by strangers go through the same interact/build checks.
- **Mob-griefing & trap control** — the `mob-grief` flag stops mobs altering blocks in a claim (endermen, sheep, etc.); pressure plates and tripwires can't be triggered by strangers, mobs or thrown items.
- **Pluggable storage** — `json` (default), `sqlite`, `h2`, or `mysql`. Any DB backend falls back to JSON if its driver is missing.
- **LuckPerms integration** — permissions resolved through LuckPerms when installed (OP-level fallback otherwise), plus per-group region limits.
- **Web-map integration** — regions render on **BlueMap** and **squaremap** if present.
- **Automatic backups** — scheduled, rotating, gzip-compressed region backups; trigger one with `/rg backup`.
- **Soft-delete & undo** — `/rg remove` trashes a region recoverably; `/rg undo` restores the one most recently removed this session.
- **Inactive-claim expiry** — optionally auto-delete player regions whose owners have all been offline longer than a configurable number of days (`/rg cleanup` runs the scan on demand).
- **Audit & violation logs** — region changes (create/redefine/remove/transfer/flag/…) go to `logs/worldguardneo-audit.log`; denied griefing attempts to `logs/worldguardneo-violations.log`.
- **Storage migration** — `/rg migrate <json|sqlite|h2|mysql>` converts existing region data to another backend.
- **Localized** — English, Russian, German, Spanish, French and Simplified Chinese out of the box; drop a `<tag>.json` for more.

## Requirements

**Required**

- Minecraft **1.21.1**
- NeoForge **21.1.x** — accepts any `21.1.0`+ (loader range `[21.1.0,)`). Built against **21.1.234**; CI verifies **21.1.209**, **21.1.221** and **21.1.234**. A recent 21.1.x build is recommended.

**Optional**

| Dependency | Enables |
| --- | --- |
| [**LuckPerms**](https://luckperms.net) 5.4+ | Permission nodes + per-group region limits (otherwise OP levels) |
| [**BlueMap**](https://modrinth.com/mod/bluemap) | Region rendering on the 3D web map |
| [**squaremap**](https://modrinth.com/mod/squaremap) | Region rendering on the 2D web map |
| [**WorldEditCUI**](https://modrinth.com/mod/worldeditcui-forge) (client) | Renders the selection/region outline client-side |
| [**sqlite-jdbc**](https://github.com/xerial/sqlite-jdbc) jar | `storage-format = "sqlite"` |
| [**H2**](https://www.h2database.com) jar | `storage-format = "h2"` (LuckPerms already ships H2) |
| [**mysql-connector-j**](https://dev.mysql.com/downloads/connector/j/) or [**MariaDB**](https://mariadb.com/downloads/connectors/) jar | `storage-format = "mysql"` |
| [**JDK 21**](https://adoptium.net) | Building from source only |

The mod is **server-side only**. Vanilla clients connect normally; no client-side installation is required. The selection outline is drawn for clients that have the **WorldEditCUI** client mod (the server speaks the `worldedit:cui` protocol directly); clients without it still select and claim normally, just without the visual box. Any database backend whose driver is absent falls back to JSON automatically, so a missing optional jar never stops the server.

## Installation

1. Drop WorldGuardNeo's `.jar` into the server's `mods/` folder.
2. *(Optional)* add LuckPerms, BlueMap, or squaremap.
3. Start the server once to generate `config/worldguardneo/config.toml`.
4. Adjust settings if needed and run `/rg reload`.

## Quick start

```
/rg wand               get the selection wand (a stick by default)
                       left-click corner 1, right-click corner 2
/rg claim myregion     claim the selection as a region named "myregion"
/rg flag myregion pvp deny
/rg addmember myregion Steve
```

For a polygon: `/rg sel poly`, then left/right-click each vertex (or `/rg point` at your feet) — at least 3 — and `/rg claim`. You can also set cuboid corners without the wand via `/rg pos1` and `/rg pos2`.

`/rg info myregion` highlights the region's outline (rendered by WorldEditCUI), so `/rg redefine myregion` can resize it after making a new selection.

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
| `/rg wand` | Get the built-in selection wand item (one per player) |
| `/rg sel <cuboid\|poly\|clear>` | Switch selection mode, or clear the current selection |
| `/rg pos1` / `/rg pos2` `[x y z]` | Set cuboid corner 1 / 2 to your position, or explicit coords (OP 4) |
| `/rg point` | Add a polygon vertex at your current position |
| `/rg select <id>` | Load a region's geometry into your selection (to redefine/expand) |
| `/rg claim <id>` | Claim your current selection as a new region |
| `/rg redefine <id>` | Resize a region to your current selection |
| `/rg rename <id> <newId>` | Change a region's id, keeping all its data |
| `/rg remove <id>` | Delete a region (recoverable with `/rg undo`) |
| `/rg undo` | Restore the most recently removed region in this world |
| `/rg transfer <id> <player>` | Hand sole ownership to another player |
| `/rg info [id]` | Show a region's type, size, bounds, owners, members, flags — and highlight its outline |
| `/rg list [player]` | List regions (optionally for a player) |
| `/rg lists [radius]` | List regions near you (default radius 50) |
| `/rg flag <id> <flag> [-g group] [value]` | Set/clear a flag (omit value to clear) |
| `/rg flags` | List every available flag with its value hint and description |
| `/rg flags <id>` | Clickable per-region flag editor (allow/deny/clear/set buttons) |
| `/rg audit <id> [limit]` | Show a region's most recent administrative changes |
| `/rg priority <id> <n>` | Set region priority (owner or admin only) |
| `/rg setparent <id> [parent]` | Set/clear a region's parent (owner or admin only) |
| `/rg addmember/removemember <id> <player>` | Manage members |
| `/rg addowner/removeowner <id> <player>` | Manage owners |
| `/rg teleport <id>` | Teleport to a region's `teleport` flag location |
| `/rg backup [label]` | Create a region backup now (optional name suffix) |
| `/rg backup list` | List existing backups, newest first |
| `/rg save` | Flush regions to storage |
| `/rg reload` | Reload config and language |
| `/rg cleanup` | Run the claim-expiry scan now (admin) |
| `/rg migrate <json\|sqlite\|h2\|mysql>` | Convert region data to another storage backend (activates on restart) |
| `/rg debug` | Diagnostics: spatial-index stats, active integrations |

The id-addressed commands (`info`, `remove`, `flag`, `priority`, `setparent`, `addowner`/`removeowner`/`addmember`/`removemember`) accept an optional `-w <world>` operand to target a region in another world without traveling there — e.g. `/rg flag spawn pvp deny -w minecraft:the_nether`. For `flag` the operand goes before the id (`/rg flag -w <world> <id> <flag> [value]`) because the value is greedy; for the others it trails the arguments.

## Permissions

With LuckPerms installed it is the sole authority (OP levels are ignored once a user is loaded). Without it, each node maps to an OP level. Note the defaults are **permissive for basic use** — claiming, viewing and deleting your own regions are OP 0 (everyone), while management and admin actions require higher tiers.

| Node | Default | Purpose |
| --- | --- | --- |
| `worldguardneo.region.claim` | OP 0 | Claim regions |
| `worldguardneo.region.delete` | OP 0 | Delete your own regions |
| `worldguardneo.region.info` / `.list` | OP 0 | View / list your regions |
| `worldguardneo.region.teleport` | OP 2 | Teleport to regions |
| `worldguardneo.selection.*` | OP 0 | Selection: `use` (wand clicks), `wand`, `mode`, `pos1`, `pos2`, `point` — each granted separately |
| `worldguardneo.region.redefine` | OP 2 | Resize regions |
| `worldguardneo.region.flag` | OP 2 | Set flags |
| `worldguardneo.region.addmember` / `addowner` | OP 2 | Manage members / owners |
| `worldguardneo.region.delete.others` | OP 3 | Delete anyone's regions |
| `worldguardneo.region.bypass` | **OP 5** | Bypass protection (LuckPerms only — never by OP) |
| `worldguardneo.backup` / `reload` | OP 4 | Manual backup / reload config |

**See [PERMISSIONS.md](PERMISSIONS.md) for the complete list** of all nodes, exact OP levels, and LuckPerms examples.

## Flags

WorldGuardNeo ships 90+ flags. Build-type flags (build, block-break, block-place, interact, use, chest-access) follow the membership model; the rest are environmental state flags (allow/deny) or value flags (greetings, locks, limits). Run `/rg flags` in-game to list every flag with its value hint and description.

**See [FLAGS.md](FLAGS.md) for the complete table** — every flag with its type, default, permission node, and description.

## Building

JDK 21 is required. From the project folder:

```
./gradlew clean build
```

The finished `.jar` is written to `build/libs/`. The only runtime soft-deps are LuckPerms, BlueMap and squaremap — all optional and declared in `neoforge.mods.toml`.

## License

Released under the **GNU General Public License v3.0 or later** (GPL-3.0-or-later). See [`LICENSE`](LICENSE) for the full text.
