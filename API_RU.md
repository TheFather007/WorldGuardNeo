<div align="center">

# ⚙️ WorldGuardNeo — Public API

**Программный доступ к регионам, события и регистрация своих флагов.**

[English](API.md) · **Русский**

[🏠 Главная](README_RU.md) · [🔨 Сборка](BUILD_RU.md) · [🔑 Права](PERMISSIONS_RU.md) · [🚩 Флаги](FLAGS_RU.md) · **⚙️ API** · [🧩 KubeJS](KUBEJS.md) · [📋 История](CHANGELOG.md)

</div>

---

## Содержание

1. [Получение API](#получение-api)
2. [Запросы — `WorldGuardNeoAPI`](#запросы--worldguardneoapi)
3. [События](#события)
4. [Регистрация собственных флагов](#регистрация-собственных-флагов)
5. [Примеры интеграции](#примеры-интеграции)

---

## Получение API

WorldGuardNeo — `optional` зависимость. Объяви это в `neoforge.mods.toml`:

```toml
[[dependencies.yourmod]]
modId = "worldguardneo"
type = "optional"
versionRange = "[1.3,)"
ordering = "AFTER"
side = "BOTH"
```

Перед использованием API проверяй наличие:

```java
if (ModList.get().isLoaded("worldguardneo")
        && WorldGuardNeoAPI.isAvailable()) {
    // safe to call WGN API
}
```

Если WGN отсутствует — твой мод работает в своём обычном режиме без какой-либо защиты регионов.

---

## Запросы — `WorldGuardNeoAPI`

Все методы статические в `dev.thefather007.worldguardneo.api.WorldGuardNeoAPI`. Все NULL-safe и thread-safe для использования с server thread.

### Поиск регионов

```java
// Регион с наивысшим priority в точке (или empty в "wilderness")
Optional<ProtectedRegion> r = WorldGuardNeoAPI.getRegionAt(level, pos);

// Все регионы покрывающие точку (по убыванию приоритета)
List<ProtectedRegion> rs = WorldGuardNeoAPI.getRegionsAt(level, pos);

// По id
Optional<ProtectedRegion> r = WorldGuardNeoAPI.getRegion(level, "spawn");

// Все регионы в мире
Collection<ProtectedRegion> all = WorldGuardNeoAPI.getRegions(level);

// Регионы во владении игрока
List<ProtectedRegion> mine = WorldGuardNeoAPI.getOwnedRegions(level, player.getUUID());
```

### Проверки прав

```java
// Полная проверка "может ли игрок построить здесь" — включая build flag, 
// owner/member, bypass, world kill-switch
boolean canBuild = WorldGuardNeoAPI.canBuild(player, pos);

// Аналогично для других защит
boolean canPlace        = WorldGuardNeoAPI.canPlace(player, pos);
boolean canInteract     = WorldGuardNeoAPI.canInteract(player, pos);
boolean canAccessChests = WorldGuardNeoAPI.canAccessChests(player, pos);
boolean canPvP          = WorldGuardNeoAPI.canPvP(player, pos);

// У игрока bypass-permission?
boolean isAdmin = WorldGuardNeoAPI.hasBypass(player);
```

### Произвольные флаги

```java
// State-flag (boolean)
boolean allowed = WorldGuardNeoAPI.queryFlag(level, Flags.PVP, player.getUUID(), pos);

// Любой типизированный флаг
Optional<String> msg = WorldGuardNeoAPI.queryValue(level, Flags.GREETING, null, pos);
Optional<Integer> heal = WorldGuardNeoAPI.queryValue(level, Flags.HEAL_AMOUNT, null, pos);
```

### Owner/member

```java
ProtectedRegion r = ...;
boolean isOwner  = WorldGuardNeoAPI.isOwner(r, uuid);
boolean isMember = WorldGuardNeoAPI.isMember(r, uuid);  // includes owners
```

---

## События

Все события находятся в пакете `dev.thefather007.worldguardneo.api.events`. Постятся в `NeoForge.EVENT_BUS`. Подписка стандартная:

```java
@SubscribeEvent
public void onRegionEnter(RegionEnterEvent event) {
    ServerPlayer p = event.getPlayer();
    ProtectedRegion r = event.getRegion();
    // your code
}
```

Регистрируй handler в конструкторе своего мода:

```java
NeoForge.EVENT_BUS.register(new YourEventHandler());
```

### `RegionEnterEvent`

Игрок вошёл в регион. Срабатывает ДО greeting/farewell сообщения, так что можно подавить штатные сообщения, проверив флаги самостоятельно. Не cancellable — вход контролируется флагом `entry`, не событием.

**Поля:**
- `getPlayer()` — `ServerPlayer`, кто вошёл
- `getRegion()` — `ProtectedRegion`, во что вошёл

**Multi-region note:** при входе в стек nested-регионов (parent + child + grandchild) событие фаерится по разу на каждый, в порядке убывания priority.

### `RegionLeaveEvent`

Игрок вышел из региона. Зеркальное к Enter. Срабатывает ДО farewell.

**Поля:** `getPlayer()`, `getRegion()`

### `RegionFlagDeniedEvent`

Регион отменил действие через флаг — покрываются **ломание/установка блока, interact, контейнеры (chest-access) и PvP** (чисто средовые отказы без действующей сущности — распространение огня/жидкостей, раздатчики — событие не порождают). **Cancellable** — listener'ы могут вызвать `setCanceled(true)`, чтобы ПЕРЕОПРЕДЕЛИТЬ отказ и позволить действие.

**Поля:**
- `getRegion()` — регион чьим флагом ограничено
- `getFlag()` — какой флаг отказал
- `getActor()` — `@Nullable Entity` — кто пытался (null для world-effects)
- `getReason()` — текстовая причина (для логов; subject to change)
- `isCanceled()` / `setCanceled(boolean)`

**Использование:**
```java
@SubscribeEvent
public void onDenied(RegionFlagDeniedEvent e) {
    if (!(e.getActor() instanceof ServerPlayer p)) return;
    // Permission node from your mod overrides region protection
    if (p.hasPermissions(4) && hasTrustedRole(p)) {
        e.setCanceled(true); // override the denial
    }
}
```

`reason` принимает значения `"block-break"`, `"block-place"`, `"interact"`, `"container"`, `"pvp"`. Чисто средовые отказы (огонь/жидкости/раздатчики) событие не порождают — для них подписывайтесь на соответствующие события NeoForge напрямую.

### `RegionModifyEvent`

Регион был создан / изменён / удалён через команду.

**Поля:**
- `getLevel()` — `ServerLevel`
- `getRegion()` — состояние региона ПОСЛЕ изменения (или ДО для DELETED)
- `getType()` — `ModifyType.CREATED`, `UPDATED`, или `DELETED`
- `getActor()` — `@Nullable Entity` — кто изменил (null если из консоли)

**Использование:**
```java
@SubscribeEvent
public void onModify(RegionModifyEvent e) {
    if (e.getType() == RegionModifyEvent.ModifyType.CREATED) {
        myLogger.info("Region {} created by {}", 
                e.getRegion().id(), 
                e.getActor() != null ? e.getActor().getName().getString() : "console");
    }
}
```

---

## Регистрация собственных флагов

Другие моды могут регистрировать свои флаги, которые будут полноценно сохраняться/загружаться в WGN-storage и устанавливаться через `/rg flag`. Это делается в `FMLCommonSetupEvent`:

```java
public static final StateFlag MY_MAGIC_PROTECTION =
        WorldGuardNeoAPI.registerFlag(new StateFlag("mymod-no-magic", true));

@SubscribeEvent
public void onCommonSetup(FMLCommonSetupEvent e) {
    // No-op — fields are statically initialized when class is loaded
    // But access them here to force class loading early
}
```

Затем у тебя есть полноценный флаг с типом `StateFlag`. Можешь спрашивать его:

```java
boolean allowed = WorldGuardNeoAPI.queryFlag(level, MY_MAGIC_PROTECTION, uuid, pos);
```

**Доступные базовые типы флагов:**
- `StateFlag` — `ALLOW` / `DENY`
- `BooleanFlag` — `true` / `false`
- `IntegerFlag` — целое число
- `DoubleFlag` — дробное число
- `StringFlag` — строка
- `SetFlag<String>` — множество строк

Все находятся в `dev.thefather007.worldguardneo.flags`.

**Naming convention:** имя флага должно соответствовать `[a-z][a-z0-9-]*` (строчные буквы, цифры, дефисы — точки запрещены валидатором). Префиксуй именем своего мода через дефис: `mymod-no-magic` — хорошо. `cool-flag` — риск конфликта с базовыми флагами и другими модами.

---

## Примеры интеграции

### Пример 1: магический мод не разрешает кастовать в "no-magic" зонах

```java
public class MyMagicMod {
    public static final StateFlag NO_MAGIC;
    
    static {
        if (ModList.get().isLoaded("worldguardneo")) {
            NO_MAGIC = WorldGuardNeoAPI.registerFlag(
                new StateFlag("mymagicmod-no-magic", true));  // default allow
        } else {
            NO_MAGIC = null;
        }
    }
    
    public boolean canCastSpell(ServerPlayer p) {
        if (NO_MAGIC == null) return true;  // WGN not present
        return WorldGuardNeoAPI.queryFlag(
                p.serverLevel(), NO_MAGIC, p.getUUID(), p.blockPosition());
    }
}
```

Теперь админ может запретить магию в регионе через `/rg flag spawn mymagicmod-no-magic deny`.

### Пример 2: мод респаун-логирования

```java
@SubscribeEvent
public void onModify(RegionModifyEvent e) {
    if (e.getType() == RegionModifyEvent.ModifyType.DELETED) {
        myAuditLog.log("Region deleted: " + e.getRegion().id() 
                + " by " + (e.getActor() != null 
                        ? e.getActor().getName().getString() 
                        : "<system>"));
    }
}
```

### Пример 3: VIP-обход региональной защиты

```java
@SubscribeEvent
public void onDenied(RegionFlagDeniedEvent e) {
    if (!(e.getActor() instanceof ServerPlayer p)) return;
    if (myVipManager.isVip(p) && !"spawn".equals(e.getRegion().id())) {
        e.setCanceled(true);  // VIP overrides, but not in spawn
    }
}
```

### Пример 4: переименование регионов через свой UI

```java
public void renameRegion(ServerLevel level, String oldId, String newId, ServerPlayer admin) {
    Optional<ProtectedRegion> old = WorldGuardNeoAPI.getRegion(level, oldId);
    if (old.isEmpty()) return;
    // ... your rename logic ...
}
```

---

## Стабильность API

Методы `WorldGuardNeoAPI` НЕ изменят свои сигнатуры между минорными версиями. Новые методы могут добавляться. Поведение существующих может уточняться.

Breaking changes — только в major-версионных переходах (1.x → 2.0).

События в пакете `api.events` также стабильны. Новые поля могут добавляться (через новые getter'ы, не через изменение constructor signature, который уже фиксирован).

Для обратной совместимости, листенеры лучше писать как `@SubscribeEvent(priority = LOWEST)` если они только наблюдают (не cancellable), чтобы не блокировать другие моды.
