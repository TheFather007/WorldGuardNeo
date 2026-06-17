<div align="center">

# 🧩 WorldGuardNeo — KubeJS / scripting

**Query regions and flags from KubeJS (or any JVM scripting layer) through the public static API.**

[🏠 Home](README.md) · [⚙️ API](API.md) · [🚩 Flags](FLAGS.md)

</div>

---

WorldGuardNeo exposes a stable static facade, `dev.thefather007.worldguardneo.api.WorldGuardNeoAPI`.
KubeJS scripts can call it directly with `Java.loadClass` — no extra binding mod, no compile step.
There is nothing to install beyond WorldGuardNeo itself.

> **Thread/timing:** call the API on the server thread (inside server-side KubeJS events). It is
> null-safe — if WGN isn't loaded yet, queries return safe defaults (allow / empty).

## Getting the facade

```js
// server_scripts/worldguardneo.js
const WGN = Java.loadClass('dev.thefather007.worldguardneo.api.WorldGuardNeoAPI')
```

## Common recipes

### Gate a machine / block use by region flag

```js
const WGN = Java.loadClass('dev.thefather007.worldguardneo.api.WorldGuardNeoAPI')

BlockEvents.rightClicked('minecraft:lever', event => {
  const { level, player, block } = event
  if (level.isClientSide()) return
  // string-keyed flag query — no need to import flag constants
  if (!WGN.queryFlag(level, 'use', player.uuid, block.x, block.y, block.z)) {
    event.cancel()
    player.tell('You can\'t use that here.')
  }
})
```

### Only run custom logic inside a named region

```js
const WGN = Java.loadClass('dev.thefather007.worldguardneo.api.WorldGuardNeoAPI')

PlayerEvents.tick(event => {
  const p = event.player
  if (p.level.isClientSide()) return
  const region = WGN.getRegionAt(p.level, p.x | 0, p.y | 0, p.z | 0)
  if (region.isPresent() && region.get().id() === 'arena') {
    // ...arena-only behaviour...
  }
})
```

### Read a value flag (custom or built-in)

```js
const WGN = Java.loadClass('dev.thefather007.worldguardneo.api.WorldGuardNeoAPI')
const greeting = WGN.queryValue(level, 'greeting', x, y, z) // Optional<Object>
if (greeting.isPresent()) console.info(greeting.get())
```

### Register your own flag from a startup script

```js
// startup_scripts/wgn_flags.js — runs before the server, when flag registration is allowed
const WGN     = Java.loadClass('dev.thefather007.worldguardneo.api.WorldGuardNeoAPI')
const StateFlag = Java.loadClass('dev.thefather007.worldguardneo.flags.StateFlag')

// default-allow state flag "mypack-machines"
WGN.registerFlag(new StateFlag('mypack-machines', true))
```

Once registered, the flag is first-class: settable with `/rg flag <id> mypack-machines deny`,
listed in `/rg flags`, persisted by every storage backend, and queryable with
`WGN.queryFlag(level, 'mypack-machines', uuid, x, y, z)`.

## Handy methods

| Method | Returns | Use |
| --- | --- | --- |
| `isAvailable()` | boolean | WGN loaded & ready |
| `canBuild(player, pos)` / `canPlace` / `canInteract` / `canAccessChests` / `canPvP` | boolean | Mirror the engine's own checks |
| `queryFlag(level, "flag", uuid, x, y, z)` | boolean | State flag by name |
| `queryValue(level, "flag", x, y, z)` | `Optional<Object>` | Value flag by name |
| `getRegionAt(level, x, y, z)` | `Optional<ProtectedRegion>` | Highest-priority region at a point |
| `getRegionsAt(level, pos)` | `List<ProtectedRegion>` | All regions at a point (priority order) |
| `getRegion(level, id)` | `Optional<ProtectedRegion>` | By id |
| `getOwnedRegions(level, uuid)` | `List<ProtectedRegion>` | A player's regions |
| `isOwner(region, uuid)` / `isMember(region, uuid)` / `hasBypass(player)` | boolean | Membership / bypass |
| `registerFlag(flag)` | the flag | Register a custom flag (startup) |

See **[API.md](API.md)** for the full Java API, events (`RegionEnterEvent`, `RegionLeaveEvent`,
`RegionFlagDeniedEvent`, `RegionModifyEvent`) and stability guarantees.
