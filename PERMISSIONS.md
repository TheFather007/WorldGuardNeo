<div align="center">

# 🔑 WorldGuardNeo Permissions

**Every permission node, its default OP level, and what it controls.**

**English** · [Русский](PERMISSIONS_RU.md)

[🏠 Home](README.md) · [🔨 Building](BUILD.md) · **🔑 Permissions** · [🚩 Flags](FLAGS.md) · [⚙️ API](API.md) · [🧩 KubeJS](KUBEJS.md) · [📋 Changelog](CHANGELOG.md)

</div>

---

## How permissions work

WorldGuardNeo checks every command and protected action against a permission node. There are two backends:

- **With LuckPerms installed** — LuckPerms is the **sole authority** once a player is loaded. OP levels are ignored; grant nodes with `/lp group <g> permission set <node> true`.
- **Without LuckPerms** — each node maps to an **OP level** (built-in `OpResolver`). A player passes if their OP level is ≥ the node's level. Unknown nodes default to **OP 2**.

The admin/mod tiers are configurable in `config.toml` (`default-op-level-admin` = 3, `default-op-level-mod` = 2 by default) and re-read on `/rg reload`.

> **OP 5 = never by OP.** Minecraft's max OP level is 4, so a node mapped to 5 (`region.bypass`) can **only** be granted explicitly via LuckPerms — it's never satisfied by OP level alone.

## Region nodes

| Node | Default | Controls |
| --- | --- | --- |
| `worldguardneo.region.claim` | OP 0 | `/rg claim` — create a region. All limits apply (per-player/group counts, volume, area, overlap). |
| `worldguardneo.region.delete` | OP 0 | `/rg remove` — delete **your own** region. |
| `worldguardneo.region.delete.others` | OP 3 | Delete **anyone's** region. |
| `worldguardneo.region.undo` | OP 2 | `/rg undo` — restore the most recently removed region in this world (session trash). |
| `worldguardneo.region.redefine` | OP 2 | `/rg redefine` — resize a region to your selection. |
| `worldguardneo.region.rename` | OP 2 | `/rg rename` — change a region's id, keeping all its data. |
| `worldguardneo.region.select` | OP 2 | `/rg select` — load a region's geometry into your selection. |
| `worldguardneo.region.transfer` | OP 3 | `/rg transfer` — hand sole ownership to another player. |
| `worldguardneo.region.info` | OP 0 | `/rg info` — view a region you can see. |
| `worldguardneo.region.info.others` | OP 2 | View regions owned by others. |
| `worldguardneo.region.info.global` | OP 4 | View the global (`__global__`) region. |
| `worldguardneo.region.list` | OP 0 | `/rg list` — list your regions. |
| `worldguardneo.region.list.others` | OP 2 | List another player's regions. |
| `worldguardneo.region.lists.radius` | OP 2 | `/rg lists <radius>` — list nearby regions. |
| `worldguardneo.region.teleport` | OP 0 | `/rg teleport` — TP to a region's `teleport` flag. |

## Membership nodes

| Node | Default | Controls |
| --- | --- | --- |
| `worldguardneo.region.addowner` | OP 2 | Add an owner. |
| `worldguardneo.region.removeowner` | OP 2 | Remove an owner. |
| `worldguardneo.region.addmember` | OP 2 | Add a member. |
| `worldguardneo.region.removemember` | OP 2 | Remove a member. |

## Flag nodes

| Node | Default | Controls |
| --- | --- | --- |
| `worldguardneo.region.flag` | OP 2 | `/rg flag` — set a flag on a region (own regions also gated by ownership). |
| `worldguardneo.region.flag.others` | OP 2 | Set flags on foreign regions. |
| `worldguardneo.region.flag.bypass` | OP 3 | Skip the per-flag group restriction check. |
| `worldguardneo.region.flag.group` | OP 2 | Use the `-g <group>` flag syntax. |
| `worldguardneo.region.flag.priority` | OP 2 | `/rg priority` — set region priority. Additionally requires being an owner of the region (or `flag.others`/`bypass`). |
| `worldguardneo.region.flag.parent` | OP 2 | `/rg setparent` — set a region's parent. Same ownership requirement as `flag.priority`. |
| `worldguardneo.region.flags.list` | OP 2 | `/rg flags` — list every available flag with its value hint and description. |

In addition, **each individual flag** has its own node `worldguardneo.flag.<name>` (default OP 2), checked when setting that specific flag. `region.flag.bypass` (or `region.bypass`) skips the per-flag check. The full list of per-flag nodes is in **[FLAGS.md](FLAGS.md)**.

## Selection & admin nodes

| Node | Default | Controls |
| --- | --- | --- |
| `worldguardneo.selection.use` | OP 0 | Using the wand item itself (clicking blocks to pick corners/points). |
| `worldguardneo.selection.wand` | OP 0 | `/rg wand` — receive the built-in selection wand item (one per player). |
| `worldguardneo.selection.mode` | OP 0 | `/rg sel <cuboid\|poly\|clear>` — switch selection mode / clear. |
| `worldguardneo.selection.pos1` | OP 0 | `/rg pos1` — set cuboid corner 1 at your position. |
| `worldguardneo.selection.pos2` | OP 0 | `/rg pos2` — set cuboid corner 2 at your position. |
| `worldguardneo.selection.point` | OP 0 | `/rg point` — add a polygon vertex at your position. |
| `worldguardneo.selection.pos.coords` | OP 4 | `/rg pos1 <x y z>` / `pos2 <x y z>` — explicit coords (console-capable via `/execute as`). |
| `worldguardneo.region.admin` | OP 3 | Administrative region operations. |
| `worldguardneo.region.bypass` | **OP 5** | Bypass region protection entirely. Never granted by OP — LuckPerms only. |
| `worldguardneo.backup` | OP 4 | `/rg backup` — manual backup. |
| `worldguardneo.migrate` | OP 4 | `/rg migrate <json\|sqlite\|h2\|mysql>` — convert region data to another storage backend (activates on restart). |
| `worldguardneo.reload` | OP 4 | `/rg reload` — reload config & language. Also gates `/rg cleanup` (run the claim-expiry scan now). |
| `worldguardneo.notify` | OP 2 | Receive broadcast messages when players cross regions flagged `notify-enter` / `notify-leave`. |

## LuckPerms examples

Open claiming to everyone (it's already OP 0, but explicit is clearer):

```
/lp group default permission set worldguardneo.region.claim true
```

Give your `builder` group region management without OP:

```
/lp group builder permission set worldguardneo.region.redefine true
/lp group builder permission set worldguardneo.region.flag true
/lp group builder permission set worldguardneo.region.addmember true
```

Grant trusted staff full bypass (the only way to get `region.bypass`):

```
/lp group admin permission set worldguardneo.region.bypass true
```

Lock the selection wand away from normal players (see [Permissions FAQ in README](README.md#permissions)):

```
/lp group default permission set worldguardneo.selection.use false
/lp group default permission set worldguardneo.selection.wand false
```

## License

Released under the **GNU General Public License v3.0 or later**. See [`LICENSE`](LICENSE) for the full text.
