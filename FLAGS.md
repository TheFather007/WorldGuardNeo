<div align="center">

# 🚩 WorldGuardNeo — Flags

**Every region flag, its type, default, permission node, and what it does.**

**English** · [Русский](FLAGS_RU.md)

[🏠 Home](README.md) · [🔨 Building](BUILD.md) · [🔑 Permissions](PERMISSIONS.md) · [🚩 Flags](FLAGS.md) · [⚙️ API](API.md) · [📋 Changelog](CHANGELOG.md)

</div>

---

## How flags work

Set a flag with `/rg flag <region> <flag> [value]`; omit the value to clear it. List every flag in-game with `/rg flags`.

- **state** flags take `allow` / `deny` / `none` (clear). The **Default** column is the value used when the flag is unset *and* no region applies. Inside a claimed region, the build-type flags (`build`, `block-break`, `block-place`, `interact`, `use`, `chest-access`) additionally fall back to **membership** when unset — owners/members pass, strangers are denied ("private by default").
- **value** flags (text, integer, number, list) hold data; an unset value means the feature is off.
- Each flag is gated by its own permission node `worldguardneo.flag.<name>` (default OP 2). `region.flag.bypass` or `region.bypass` skips the per-flag check.
- Add `-g <group>` before the value to scope a flag to `OWNERS` / `MEMBERS` / `NON_OWNERS` / `NON_MEMBERS` / `ALL` (requires `region.flag.group`).

Two flags are **declared but not yet enforced** (kept for save-format stability): `receive-chat` and `allowed-enchants`.

## All flags

| Flag | Type | Default | Permission | Description |
| --- | --- | --- | --- | --- |
| `allowed-cmds` | list | — | `worldguardneo.flag.allowed-cmds` | Whitelist of allowed commands (overrides blocked). |
| `allowed-enchants` | list | — | `worldguardneo.flag.allowed-enchants` | Whitelist of permitted enchantments (DECLARED — not yet enforced). |
| `block-break` | state (allow/deny) | allow | `worldguardneo.flag.block-break` | Allow or deny block breaking specifically. |
| `block-place` | state (allow/deny) | allow | `worldguardneo.flag.block-place` | Allow or deny block placement specifically. |
| `blocked-cmds` | list | — | `worldguardneo.flag.blocked-cmds` | Commands forbidden inside the region. |
| `blocked-effects` | list | — | `worldguardneo.flag.blocked-effects` | Potion effects suppressed inside the region. |
| `build` | state (allow/deny) | allow | `worldguardneo.flag.build` | Allow or deny breaking and placing blocks in general. |
| `chest-access` | state (allow/deny) | allow | `worldguardneo.flag.chest-access` | Allow or deny opening containers (chests, barrels, hoppers). |
| `chorus-teleport` | state (allow/deny) | allow | `worldguardneo.flag.chorus-teleport` | Allow or deny chorus-fruit teleports. |
| `creeper-explosion` | state (allow/deny) | allow | `worldguardneo.flag.creeper-explosion` | Allow or deny creeper explosion damage to blocks. |
| `crop-growth` | state (allow/deny) | allow | `worldguardneo.flag.crop-growth` | Allow or deny crop growth (wheat, beets, etc). |
| `deny-message` | text | — | `worldguardneo.flag.deny-message` | Custom message shown on protection denial. |
| `deny-spawn` | list | — | `worldguardneo.flag.deny-spawn` | Entity IDs whose spawn is denied. |
| `dispenser-output` | state (allow/deny) | allow | `worldguardneo.flag.dispenser-output` | Allow or deny dispenser/dropper output (items, fluids, projectiles). Also blocks firing across a region border. |
| `drown-damage` | state (allow/deny) | allow | `worldguardneo.flag.drown-damage` | Allow or deny drowning damage. |
| `enderdragon` | state (allow/deny) | allow | `worldguardneo.flag.enderdragon` | Allow or deny ender-dragon block destruction. |
| `enderpearl` | state (allow/deny) | allow | `worldguardneo.flag.enderpearl` | Allow or deny ender-pearl teleports. |
| `entry` | state (allow/deny) | allow | `worldguardneo.flag.entry` | Allow or deny entering the region (non-bypass players). |
| `entry-deny-message` | text | — | `worldguardneo.flag.entry-deny-message` | Custom message shown when entry is denied. |
| `entry-vehicle` | state (allow/deny) | allow | `worldguardneo.flag.entry-vehicle` | Allow or deny entering the region while riding a vehicle. |
| `exit` | state (allow/deny) | allow | `worldguardneo.flag.exit` | Allow or deny leaving the region. |
| `exit-deny-message` | text | — | `worldguardneo.flag.exit-deny-message` | Custom message shown when exit is denied. |
| `exit-vehicle` | state (allow/deny) | allow | `worldguardneo.flag.exit-vehicle` | Allow or deny leaving the region while riding a vehicle. |
| `exp-drops` | state (allow/deny) | allow | `worldguardneo.flag.exp-drops` | Allow or deny experience-orb drops. |
| `fall-damage` | state (allow/deny) | allow | `worldguardneo.flag.fall-damage` | Allow or deny fall damage to players. |
| `farewell` | text | — | `worldguardneo.flag.farewell` | Message shown to players leaving the region. |
| `farewell-title` | text | — | `worldguardneo.flag.farewell-title` | Title shown on exit. |
| `feed-amount` | integer | — | `worldguardneo.flag.feed-amount` | Hunger restored per auto-feed tick. |
| `feed-delay` | integer | — | `worldguardneo.flag.feed-delay` | Seconds between auto-feed ticks. |
| `feed-max-hunger` | integer | — | `worldguardneo.flag.feed-max-hunger` | Auto-feed will not go above this hunger. |
| `feed-min-hunger` | integer | — | `worldguardneo.flag.feed-min-hunger` | Auto-feed will not trigger above this hunger. |
| `fire-damage` | state (allow/deny) | allow | `worldguardneo.flag.fire-damage` | Allow or deny fire-related damage to players. |
| `fire-spread` | state (allow/deny) | allow | `worldguardneo.flag.fire-spread` | Allow or deny fire propagating across blocks. |
| `frosted-ice-melt` | state (allow/deny) | allow | `worldguardneo.flag.frosted-ice-melt` | Allow or deny frost-walker ice melting. |
| `game-mode` | text | — | `worldguardneo.flag.game-mode` | Force a specific game mode (survival, creative, ...). |
| `ghast-fireball` | state (allow/deny) | allow | `worldguardneo.flag.ghast-fireball` | Allow or deny ghast/fireball explosion damage to blocks. |
| `grass-spread` | state (allow/deny) | allow | `worldguardneo.flag.grass-spread` | Allow or deny grass block spreading. |
| `greeting` | text | — | `worldguardneo.flag.greeting` | Message shown to players entering the region. |
| `greeting-title` | text | — | `worldguardneo.flag.greeting-title` | Title shown on entry. |
| `heal-amount` | integer | — | `worldguardneo.flag.heal-amount` | HP healed per auto-heal tick. |
| `heal-delay` | integer | — | `worldguardneo.flag.heal-delay` | Seconds between auto-heal ticks. |
| `heal-max-hp` | integer | — | `worldguardneo.flag.heal-max-hp` | Auto-heal will not go above this HP. |
| `heal-min-hp` | integer | — | `worldguardneo.flag.heal-min-hp` | Auto-heal will not trigger below this HP. |
| `hunger-drain` | state (allow/deny) | allow | `worldguardneo.flag.hunger-drain` | Allow or deny hunger draining inside the region. |
| `ice-form` | state (allow/deny) | allow | `worldguardneo.flag.ice-form` | Allow or deny ice forming. |
| `ice-melt` | state (allow/deny) | allow | `worldguardneo.flag.ice-melt` | Allow or deny ice melting. |
| `interact` | state (allow/deny) | allow | `worldguardneo.flag.interact` | Allow or deny right-click interactions with blocks and entities. |
| `invincible` | state (allow/deny) | deny | `worldguardneo.flag.invincible` | Make players invincible inside the region. |
| `item-drop` | state (allow/deny) | allow | `worldguardneo.flag.item-drop` | Allow or deny dropping items on the ground. |
| `item-pickup` | state (allow/deny) | allow | `worldguardneo.flag.item-pickup` | Allow or deny picking up ground items. |
| `keep-inventory` | state (allow/deny) | deny | `worldguardneo.flag.keep-inventory` | Keep player items on death inside this region. |
| `keep-xp` | state (allow/deny) | deny | `worldguardneo.flag.keep-xp` | Keep player XP on death inside this region. |
| `lava-fire` | state (allow/deny) | allow | `worldguardneo.flag.lava-fire` | Allow or deny lava igniting nearby blocks. |
| `lava-flow` | state (allow/deny) | allow | `worldguardneo.flag.lava-flow` | Allow or deny lava flowing. |
| `leaf-decay` | state (allow/deny) | allow | `worldguardneo.flag.leaf-decay` | Allow or deny natural leaf decay. |
| `lightning` | state (allow/deny) | allow | `worldguardneo.flag.lightning` | Allow or deny lightning strikes affecting the region. |
| `max-speed` | number | — | `worldguardneo.flag.max-speed` | Maximum movement speed inside the region. |
| `mob-damage` | state (allow/deny) | allow | `worldguardneo.flag.mob-damage` | Allow or deny players attacking mobs. |
| `mob-grief` | state (allow/deny) | allow | `worldguardneo.flag.mob-grief` | Allow or deny mobs changing blocks (enderman pick/place, sheep eating grass, etc.). |
| `mob-spawning` | state (allow/deny) | allow | `worldguardneo.flag.mob-spawning` | Allow or deny natural mob spawning. |
| `mob-teleport` | state (allow/deny) | allow | `worldguardneo.flag.mob-teleport` | Allow or deny mob teleports (Endermen, Shulker bullets). |
| `mycelium-spread` | state (allow/deny) | allow | `worldguardneo.flag.mycelium-spread` | Allow or deny mycelium spreading. |
| `notify-enter` | true/false | — | `worldguardneo.flag.notify-enter` | Notify admins when players enter. |
| `notify-leave` | true/false | — | `worldguardneo.flag.notify-leave` | Notify admins when players leave. |
| `other-explosion` | state (allow/deny) | allow | `worldguardneo.flag.other-explosion` | Allow or deny explosions not covered by the more specific flags. |
| `pistons` | state (allow/deny) | allow | `worldguardneo.flag.pistons` | Allow or deny pistons pushing/pulling blocks across the region border. |
| `player-damage` | state (allow/deny) | allow | `worldguardneo.flag.player-damage` | Allow or deny generic damage to players from non-player sources. |
| `pvp` | state (allow/deny) | allow | `worldguardneo.flag.pvp` | Allow or deny player-vs-player damage. |
| `receive-chat` | state (allow/deny) | allow | `worldguardneo.flag.receive-chat` | Allow or deny receiving chat while inside the region (DECLARED — not yet enforced). |
| `redstone` | state (allow/deny) | allow | `worldguardneo.flag.redstone` | Allow or deny redstone signal propagation within the region. |
| `send-chat` | state (allow/deny) | allow | `worldguardneo.flag.send-chat` | Allow or deny sending chat from within the region. |
| `sleep` | state (allow/deny) | allow | `worldguardneo.flag.sleep` | Allow or deny sleeping in beds. |
| `snow-fall` | state (allow/deny) | allow | `worldguardneo.flag.snow-fall` | Allow or deny snow accumulating. |
| `snow-melt` | state (allow/deny) | allow | `worldguardneo.flag.snow-melt` | Allow or deny snow melting. |
| `spawn` | text | — | `worldguardneo.flag.spawn` | Respawn coordinates for region-bound players. |
| `suffocation-damage` | state (allow/deny) | allow | `worldguardneo.flag.suffocation-damage` | Allow or deny suffocation damage (in walls). |
| `teleport` | text | — | `worldguardneo.flag.teleport` | Destination for /rg teleport, format x,y,z. |
| `time-lock` | text | — | `worldguardneo.flag.time-lock` | Lock client-side time (day, night, sunrise, sunset, or numeric). |
| `tnt` | state (allow/deny) | allow | `worldguardneo.flag.tnt` | Allow or deny TNT explosion damage to blocks. |
| `use` | state (allow/deny) | allow | `worldguardneo.flag.use` | Allow or deny using items (food, potions, doors). |
| `vehicle-destroy` | state (allow/deny) | allow | `worldguardneo.flag.vehicle-destroy` | Allow or deny destroying minecarts and boats. |
| `vine-growth` | state (allow/deny) | allow | `worldguardneo.flag.vine-growth` | Allow or deny vines growing. |
| `water-flow` | state (allow/deny) | allow | `worldguardneo.flag.water-flow` | Allow or deny water flowing. |
| `weather-lock` | text | — | `worldguardneo.flag.weather-lock` | Lock client-side weather (clear, rain, thunder). |

## License

Released under the **GNU General Public License v3.0 or later**. See [`LICENSE`](LICENSE) for the full text.
