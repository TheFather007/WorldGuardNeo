<div align="center">

# ⚙️ WorldGuardNeo — Public API

**Programmatic access to regions, events, and custom flag registration.**

**English** · [Русский](API_RU.md)

[🏠 Home](README.md) · [🔨 Building](BUILD.md) · [🔑 Permissions](PERMISSIONS.md) · [🚩 Flags](FLAGS.md) · **⚙️ API** · [📋 Changelog](CHANGELOG.md)

</div>

---

## Contents

1. [Getting the API](#getting-the-api)
2. [Queries — `WorldGuardNeoAPI`](#queries--worldguardneoapi)
3. [Events](#events)
4. [Registering custom flags](#registering-custom-flags)

## Getting the API

WorldGuardNeo exposes a static facade at `dev.thefather007.worldguardneo.api.WorldGuardNeoAPI`. Make WorldGuardNeo a soft/optional dependency of your mod and guard your hooks behind a presence check:

```java
if (ModList.get().isLoaded("worldguardneo")
        && WorldGuardNeoAPI.isAvailable()) {
    // safe to call the API
}
```

## Queries — `WorldGuardNeoAPI`

All methods are static, null-safe, and intended for use on the server thread.

### Finding regions

```java
Optional<ProtectedRegion> r = WorldGuardNeoAPI.getRegionAt(level, pos);
List<ProtectedRegion> rs    = WorldGuardNeoAPI.getRegionsAt(level, pos);
Optional<ProtectedRegion> byId = WorldGuardNeoAPI.getRegion(level, "spawn");
Collection<ProtectedRegion> all = WorldGuardNeoAPI.getRegions(level);
List<ProtectedRegion> mine  = WorldGuardNeoAPI.getOwnedRegions(level, player.getUUID());
```

### Permission checks

```java
boolean canBuild        = WorldGuardNeoAPI.canBuild(player, pos);
boolean canPlace        = WorldGuardNeoAPI.canPlace(player, pos);
boolean canInteract     = WorldGuardNeoAPI.canInteract(player, pos);
boolean canAccessChests = WorldGuardNeoAPI.canAccessChests(player, pos);
boolean canPvP          = WorldGuardNeoAPI.canPvP(player, pos);
boolean isAdmin         = WorldGuardNeoAPI.hasBypass(player);
```

These mirror the mod's own event handlers exactly, including the implicit membership protection: inside a claimed region with no explicit flags, strangers get `false` while owners/members get `true` — the same verdict the break/place/interact handlers enforce.

### Arbitrary flags

```java
boolean allowed = WorldGuardNeoAPI.queryFlag(level, Flags.PVP, player.getUUID(), pos);
Optional<String>  msg  = WorldGuardNeoAPI.queryValue(level, Flags.GREETING, null, pos);
Optional<Integer> heal = WorldGuardNeoAPI.queryValue(level, Flags.HEAL_AMOUNT, null, pos);
```

### Owner / member

```java
boolean isOwner  = WorldGuardNeoAPI.isOwner(r, uuid);
boolean isMember = WorldGuardNeoAPI.isMember(r, uuid);  // includes owners
```

## Events

All events are posted on the NeoForge game event bus (`NeoForge.EVENT_BUS`). Subscribe with `@SubscribeEvent`.

### `RegionEnterEvent`

Fired when a player crosses into a region. Carries the player, the region, and the level. Not cancelable (movement has already happened) — use it for notifications, effects, logging.

```java
@SubscribeEvent
public void onRegionEnter(RegionEnterEvent event) {
    ServerPlayer player = event.getPlayer();
    ProtectedRegion region = event.getRegion();
    // ...
}
```

### `RegionLeaveEvent`

The mirror of `RegionEnterEvent`, fired when a player leaves a region.

### `RegionFlagDeniedEvent`

Fired when a flag denies an action (e.g. a build attempt blocked by the `build` flag). Lets you react to or audit denials. Carries the player, region, the flag involved, and the position.

### `RegionModifyEvent`

Fired when a region is created, redefined, or deleted. Carries the level, region, a `ModifyType` (`CREATED` / `UPDATED` / `DELETED`), and the actor (may be null for programmatic changes).

```java
@SubscribeEvent
public void onRegionModify(RegionModifyEvent event) {
    if (event.getType() == RegionModifyEvent.ModifyType.CREATED) {
        // a new region was claimed
    }
}
```

## Registering custom flags

Other mods can register their own flags during setup. Register early (before regions load) so flags resolve from storage. Use the same flag types WorldGuardNeo uses (`StateFlag`, `StringFlag`, `IntegerFlag`, `DoubleFlag`, `BooleanFlag`, `SetFlag`) and add a matching `flag.<name>.desc` lang entry so the flag shows a description in-game.

Flag names must match `[a-z][a-z0-9-]*` (lowercase, digits, dashes — no dots), so prefix with your mod name dash-style to avoid collisions:

```java
// during common setup; second StateFlag argument is the default (true = allow when unset)
StateFlag MY_FLAG = new StateFlag("mymod-no-magic", true);
WorldGuardNeoAPI.registerFlag(MY_FLAG);
```

Once registered, the flag is settable via `/rg flag <id> my-custom-flag <value>` and queryable through `queryFlag` / `queryValue` exactly like a built-in flag.

## License

Released under the **GNU General Public License v3.0 or later**. See [`LICENSE`](LICENSE) for the full text.
