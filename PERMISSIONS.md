# WorldGuardNeo — Права (permissions)

Все узлы прав имеют префикс `worldguardneo.`. При отсутствии LuckPerms маппинг идёт на op-level. Уровни OP'а для admin- и mod-нодов настраиваются через `defaultOpLevelAdmin` и `defaultOpLevelMod` в `config.json` (по умолчанию 3 и 2 соответственно).

## Содержание

1. [Базовые узлы регионов](#базовые-узлы-регионов)
2. [Узлы изменения флагов](#узлы-изменения-флагов-регионов)
3. [Per-flag узлы — детально по каждому флагу](#per-flag-узлы)
4. [Доступ владельца / участника](#доступ-владельца--участника)
5. [Пример LuckPerms-конфигурации](#пример-luckperms-конфигурации)
6. [Лимиты регионов](#лимиты-регионов)

---

## Базовые узлы регионов

### Глобальные обходы

| Узел | Описание | Op fallback |
|---|---|---|
| `worldguardneo.region.bypass` | **Сильнейший узел (top-tier).** Полный обход ВСЕХ проверок защиты, лимитов клейма, оверлапа, и пер-флаговых ограничений. **Также имплицитно открывает `info.global`** (просмотр глобального региона). **Default OP 4** — используйте только для верхнего эшелона админов. | op 4 |
| `worldguardneo.region.admin` | Алиас для админских операций. Сейчас не проверяется в коде самостоятельно, оставлен для будущей расширяемости — задумывался как "может делать всё что может региональная команда". | op 3 |
| `worldguardneo.build` | Дополнительный per-world bypass-нод. Имя ноды настраивается через `buildPermNode` в конфиге мира (по умолчанию `worldguardneo.build`). Используется для рангов "trusted-builder" — игрок может ломать/ставить блоки в любых регионах этого мира, но НЕ имеет других admin-прав. | все |

### Создание и удаление

| Узел | Описание | Op fallback |
|---|---|---|
| `worldguardneo.region.claim` | Команда `/rg claim`. Применяются все лимиты: `maxRegionsPerPlayer`/`groupRegionLimits`, `maxRegionVolume`, `maxClaimableArea`, `minRegionVolume`, проверка оверлапа. | все |
| `worldguardneo.region.redefine` | Команда `/rg redefine`. Переопределение границ. Без bypass можно только свои регионы. Сохраняет владельцев, участников, флаги, parent, детей. | op 2 |
| `worldguardneo.region.delete` | Команда `/rg remove`. Удаление **своих** регионов. Дети получают `parent = null`. | все |
| `worldguardneo.region.delete.others` | Удаление **любых** регионов, включая чужие. Сильный нод — на live-серверах давать только админам. | op 3 |

### Просмотр

| Узел | Описание | Op fallback |
|---|---|---|
| `worldguardneo.region.info` | Команды `/rg info` и `/rg info <id>` для своих регионов (где владелец или участник). | все |
| `worldguardneo.region.info.others` | Просмотр чужих регионов через `/rg info <id>` (где игрок не owner/member). Полезно для модераторов разбирающих жалобы. Не даёт доступа к глобальному региону. | op 2 |
| `worldguardneo.region.info.global` | Просмотр глобального региона через `/rg info __global__`. Включается также через `bypass`. Если хотите дать просмотр глобала **без** полного обхода защиты (например config-аудитор) — выдавайте этот узел напрямую без `bypass`. | op 4 |
| `worldguardneo.region.list` | Команда `/rg list` (свои регионы). Не требуется для базового использования — выдан всем по умолчанию. | все |
| `worldguardneo.region.list.others` | Команда `/rg list <player>` — посмотреть регионы другого игрока. | op 2 |
| `worldguardneo.region.lists.radius` | Команда `/rg lists [radius]` — регионы в радиусе вокруг своей позиции. | op 2 |
| `worldguardneo.region.teleport` | Команда `/rg teleport`. Использует флаг `teleport` региона или центр AABB. Может быть ограничен per-region через флаг (например установить `teleport` в координаты безопасной точки). | все |

### Управление членством

| Узел | Описание | Op fallback |
|---|---|---|
| `worldguardneo.region.addowner` | Команда `/rg addowner`. Принимает имя онлайн-игрока, имя из profile-cache (для оффлайн), или строковый UUID. Без bypass — только своим регионам. | op 2 |
| `worldguardneo.region.removeowner` | Команда `/rg removeowner`. | op 2 |
| `worldguardneo.region.addmember` | Команда `/rg addmember`. Семантика как у addowner. | op 2 |
| `worldguardneo.region.removemember` | Команда `/rg removemember`. | op 2 |

### Выделение

| Узел | Описание | Op fallback |
|---|---|---|
| `worldguardneo.selection.use` | Использование палочки-топора `/rg wand` и команд `//pos1 //pos2`. Если установлен WorldEdit — выделение читается из его LocalSession, и этот нод не требуется. | все |

### Администрирование

| Узел | Описание | Op fallback |
|---|---|---|
| `worldguardneo.reload` | Команды `/rg reload`, `/rg save`, `/rg debug`. **Top-tier (OP 4)** — перезагрузка свапает live config и может сломать busy-сервер если config.json содержит ошибки. | op 4 |
| `worldguardneo.backup` | Команды `/rg backup`, `/rg backup <label>`, `/rg backup list`. **Top-tier (OP 4)** — пишет на диск и ротирует retention. Для service-аккаунтов автоматизации лучше выдавать через LP узел, чем через OP-уровень. | op 4 |
| `worldguardneo.notify` | Получение уведомлений админам когда флаги `notify-enter`/`notify-leave` сработали. Игроки с этим нодом получат сообщение "X вошёл в регион Y". | op 2 |

---

## Узлы изменения флагов регионов

Чтобы выполнить `/rg flag <id> <flag> [value]`, нужно ОДНОВРЕМЕННО:

1. **Быть владельцем региона**, ИЛИ иметь `worldguardneo.region.flag.others`, ИЛИ иметь `worldguardneo.region.bypass`.
2. **Иметь право на конкретный флаг** — `worldguardneo.flag.<flag-name>` (см. таблицу ниже), ИЛИ иметь `worldguardneo.region.flag.bypass`, ИЛИ иметь `worldguardneo.region.bypass`.
3. **Если используется `-g <group>` синтаксис** — дополнительно нужно `worldguardneo.region.flag.group`, ИЛИ `worldguardneo.region.flag.bypass`, ИЛИ `worldguardneo.region.bypass`. Без `-g` команда работает с дефолтной группой из `defaultRegionGroup` и эта проверка не применяется.

| Узел | Описание | Op fallback |
|---|---|---|
| `worldguardneo.region.flag.others` | Менять флаги на чужих регионах (где игрок не owner/member). | op 2 |
| `worldguardneo.region.flag.bypass` | Пропустить per-flag проверки. Без него для каждого флага нужно отдельное право. | op 3 |
| `worldguardneo.region.flag.group` | Использовать синтаксис `-g <group>` (`OWNERS`, `MEMBERS`, `NON_OWNERS`, `NON_MEMBERS`). Без этого права команда `/rg flag ... -g ... ...` отклоняется. Команды БЕЗ `-g` работают как обычно — применяется `defaultRegionGroup` из конфига. Защищает от exploit'ов типа `invincible -g OWNERS allow` (асимметричные PvP-зоны). | op 2 |
| `worldguardneo.region.flag.priority` | Команда `/rg priority`. Изменение приоритета региона. | op 2 |
| `worldguardneo.region.flag.parent` | Команда `/rg setparent`. Установить/снять родителя. Защита от циклов. | op 2 |
| `worldguardneo.region.flags.list` | Команда `/rg flags`. Просмотр всех зарегистрированных флагов с подсказками. | все |
| `worldguardneo.flag.<flag-name>` | Право на конкретный флаг. См. список ниже. | op 2 |

---

## Per-flag узлы

Узлы вида `worldguardneo.flag.<flag-name>`. Описание поведения каждого флага — в [README.md](README.md#флаги--полный-список). Здесь — рекомендации по выдаче.

### Защита блоков

| Узел | Назначение | Кому давать |
|---|---|---|
| `worldguardneo.flag.build` | Общий break+place. | Модераторам, чтобы делать арены/спавны |
| `worldguardneo.flag.block-break` | Только break. | Модераторам |
| `worldguardneo.flag.block-place` | Только place. | Модераторам |
| `worldguardneo.flag.interact` | Правый клик. | Модераторам |
| `worldguardneo.flag.use` | Использование предметов. | Модераторам |
| `worldguardneo.flag.chest-access` | Сундуки и контейнеры. | Модераторам |
| `worldguardneo.flag.pistons` | Поршни. | Модераторам, для технических зон |

### PvP и урон

| Узел | Назначение | Кому давать |
|---|---|---|
| `worldguardneo.flag.pvp` | Бои игрок-vs-игрок. | Игрокам — для своих регионов (отключать PvP в доме). Модераторам — для арен. |
| `worldguardneo.flag.invincible` | Полная неуязвимость. | Только админам — сильный флаг. |
| `worldguardneo.flag.mob-damage` | Урон мобам от игроков. | Модераторам |
| `worldguardneo.flag.player-damage` | Урон игроку от env/non-players. | Модераторам |
| `worldguardneo.flag.fall-damage` | Падения. | Модераторам, для парков и арен |
| `worldguardneo.flag.fire-damage` | Огонь/лава. | Модераторам |
| `worldguardneo.flag.drown-damage` | Удушение в воде. | Модераторам |
| `worldguardneo.flag.suffocation-damage` | Удушение в стене. | Модераторам |
| `worldguardneo.flag.vehicle-destroy` | Ломать boats/minecarts. | Модераторам |

### Взрывы

Все по умолчанию allow — давать только если хотите явно разрешить игрокам менять.

| Узел | Назначение |
|---|---|
| `worldguardneo.flag.tnt` | TNT |
| `worldguardneo.flag.creeper-explosion` | Крипер |
| `worldguardneo.flag.ghast-fireball` | Гаст |
| `worldguardneo.flag.enderdragon` | Дракон |
| `worldguardneo.flag.other-explosion` | Wither, end crystal, моды |

### Огонь и жидкости

| Узел | Назначение |
|---|---|
| `worldguardneo.flag.fire-spread` | Распространение огня |
| `worldguardneo.flag.lava-fire` | Поджигание лавой |
| `worldguardneo.flag.water-flow` | Создание блоков от воды |
| `worldguardneo.flag.lava-flow` | Создание блоков от лавы |

### Сущности

| Узел | Назначение |
|---|---|
| `worldguardneo.flag.mob-spawning` | Natural спаун |
| `worldguardneo.flag.deny-spawn` | Запрет спауна конкретных типов сущностей |
| `worldguardneo.flag.lightning` | Удары молний |

### Перемещение

| Узел | Назначение | Кому давать |
|---|---|---|
| `worldguardneo.flag.entry` | Запрет входа в регион. | Игрокам для приватных зон |
| `worldguardneo.flag.exit` | Запрет выхода. | Только модераторам/админам — может trap игроков |
| `worldguardneo.flag.entry-vehicle` | Запрет входа на транспорте. | Игрокам |
| `worldguardneo.flag.exit-vehicle` | Запрет выхода на транспорте. | Модераторам |
| `worldguardneo.flag.enderpearl` | Телепорт жемчугом В регион. | Модераторам |
| `worldguardneo.flag.chorus-teleport` | Телепорт хорус-фруктом В регион. | Модераторам |
| `worldguardneo.flag.ender-build` | Эндермены ломают/ставят блоки. | Модераторам |
| `worldguardneo.flag.teleport` | Кастомная точка телепорта `/rg teleport`. | Игрокам |
| `worldguardneo.flag.entry-deny-message` | Сообщение при отказе во входе. | Игрокам |
| `worldguardneo.flag.exit-deny-message` | Сообщение при отказе в выходе. | Модераторам |
| `worldguardneo.flag.deny-message` | Универсальное сообщение для любого denied action. | Игрокам |

### Сообщения и уведомления

| Узел | Назначение | Кому давать |
|---|---|---|
| `worldguardneo.flag.greeting` | Приветствие в чат. | Игрокам |
| `worldguardneo.flag.farewell` | Прощание в чат. | Игрокам |
| `worldguardneo.flag.greeting-title` | Приветствие как title. | Игрокам |
| `worldguardneo.flag.farewell-title` | Прощание как title. | Игрокам |
| `worldguardneo.flag.notify-enter` | Уведомления админам о входе. | Только модераторам/админам |
| `worldguardneo.flag.notify-leave` | Уведомления админам о выходе. | Только модераторам/админам |

### Команды

| Узел | Назначение | Кому давать |
|---|---|---|
| `worldguardneo.flag.blocked-cmds` | Список запрещённых команд. | Модераторам — может trap игроков без /spawn |
| `worldguardneo.flag.allowed-cmds` | Whitelist команд. | Модераторам |
| `worldguardneo.flag.send-chat` | Возможность писать в чат. | Модераторам — может silence игрока |
| `worldguardneo.flag.receive-chat` | Получение чата (не реализован). | — |

### Геймплей-модификации

| Узел | Назначение | Кому давать |
|---|---|---|
| `worldguardneo.flag.game-mode` | Принудительный геймрежим. | Только админам — сильный флаг |
| `worldguardneo.flag.time-lock` | Локальное время суток. | Модераторам |
| `worldguardneo.flag.weather-lock` | Заблокированная погода. | Модераторам |
| `worldguardneo.flag.max-speed` | Max-speed модификатор. | Модераторам |
| `worldguardneo.flag.hunger-drain` | Отключение траты голода. | Модераторам |

### Регенерация и питание

| Узел | Назначение |
|---|---|
| `worldguardneo.flag.heal-delay` | Интервал лечения (сек) |
| `worldguardneo.flag.heal-amount` | HP за тик |
| `worldguardneo.flag.heal-max-hp` | Верхний порог |
| `worldguardneo.flag.heal-min-hp` | Нижний порог |
| `worldguardneo.flag.feed-delay` | Интервал кормления |
| `worldguardneo.flag.feed-amount` | Единиц голода за тик |
| `worldguardneo.flag.feed-max-hunger` | Верхний порог |
| `worldguardneo.flag.feed-min-hunger` | Нижний порог |

### Сон, опыт, эффекты

| Узел | Назначение |
|---|---|
| `worldguardneo.flag.sleep` | Сон в кровати |
| `worldguardneo.flag.exp-drops` | Падение опыта |
| `worldguardneo.flag.crop-growth` | Рост растений |
| `worldguardneo.flag.blocked-effects` | Блокировка mob-эффектов |
| `worldguardneo.flag.item-pickup` | Поднимать предметы |
| `worldguardneo.flag.item-drop` | Выбрасывать предметы |
| `worldguardneo.flag.allowed-enchants` | Whitelist зачарований (не реализован) |
| `worldguardneo.flag.spawn` | Точка спауна (резерв) |

### Random-tick флаги

| Узел | Назначение |
|---|---|
| `worldguardneo.flag.ice-form` | Образование льда |
| `worldguardneo.flag.ice-melt` | Таяние льда |
| `worldguardneo.flag.frosted-ice-melt` | Таяние Frost Walker-льда |
| `worldguardneo.flag.snow-fall` | Снегопад |
| `worldguardneo.flag.snow-melt` | Таяние снега |
| `worldguardneo.flag.grass-spread` | Распространение травы |
| `worldguardneo.flag.mycelium-spread` | Распространение мицелия |
| `worldguardneo.flag.vine-growth` | Рост лиан |
| `worldguardneo.flag.leaf-decay` | Опадание листвы |

---

## Доступ владельца / участника

### Принадлежность к региону

- **Owners** — добавляются через `/rg addowner` или при создании региона. Имеют ВСЕ права над регионом (менять флаги, добавлять/удалять других owners, удалять регион).
- **Members** — добавляются через `/rg addmember`. Имеют доступ к ресурсам региона (build, chest, interact в защищённой зоне), но не могут менять флаги.

### Как это взаимодействует с флагами

Большинство защитных проверок (build, block-break, interact, chest-access) учитывают принадлежность игрока:

- Флаг `block-break = deny` без `group` → запрещает ВСЕМ.
- Флаг `block-break = deny -g NON_MEMBERS` → запрещает только не-участникам. Owners/members могут ломать.
- Флаг `block-break = deny -g NON_OWNERS` → разрешено только owners (members тоже не могут).

Узел `worldguardneo.region.bypass` сильнее всего этого — игрок с bypass обходит ЛЮБЫЕ region-флаги.

### Просмотр и список

- `/rg info` (без аргумента) — показывает регион под ногами, **только** если игрок там owner или member. Иначе сообщение "вы не в своём регионе".
- `/rg info <id>` — для своих регионов нужен только `region.info`. Для чужих — `region.info.others` (OP 2) или `region.bypass`. Для глобального региона (`__global__`) — `region.info.global` (OP 4) или `region.bypass`.
- `/rg list` (без аргумента) — всегда только свои регионы (где вы owner или member). Доступно всем.
- `/rg list <player>` — требует `region.list.others` (default OP 2).
- `/rg lists [radius]` — требует `region.lists.radius` (default OP 2). Радиус опционален (default 50, max 1000 блоков).

---

## Пример LuckPerms-конфигурации

### Минимальный setup для среднего сервера

```bash
# === default — обычные игроки ===
# Создание и просмотр своих регионов
/lp group default permission set worldguardneo.region.info true
/lp group default permission set worldguardneo.region.list true
/lp group default permission set worldguardneo.region.claim true
/lp group default permission set worldguardneo.region.delete true
/lp group default permission set worldguardneo.region.teleport true
/lp group default permission set worldguardneo.region.redefine true
/lp group default permission set worldguardneo.selection.use true
/lp group default permission set worldguardneo.region.flags.list true

# Управление своей командой
/lp group default permission set worldguardneo.region.addowner true
/lp group default permission set worldguardneo.region.addmember true
/lp group default permission set worldguardneo.region.removeowner true
/lp group default permission set worldguardneo.region.removemember true

# Безопасные флаги для своих регионов
/lp group default permission set worldguardneo.flag.greeting true
/lp group default permission set worldguardneo.flag.farewell true
/lp group default permission set worldguardneo.flag.greeting-title true
/lp group default permission set worldguardneo.flag.farewell-title true
/lp group default permission set worldguardneo.flag.teleport true
/lp group default permission set worldguardneo.flag.deny-message true
/lp group default permission set worldguardneo.flag.entry-deny-message true
/lp group default permission set worldguardneo.flag.pvp true
/lp group default permission set worldguardneo.flag.chest-access true
/lp group default permission set worldguardneo.flag.entry true

# === vip — премиум игроки ===
/lp group vip parent add default
/lp group vip permission set worldguardneo.flag.heal-delay true
/lp group vip permission set worldguardneo.flag.heal-amount true
/lp group vip permission set worldguardneo.flag.feed-delay true
/lp group vip permission set worldguardneo.flag.feed-amount true
/lp group vip permission set worldguardneo.flag.max-speed true

# === moderator — модераторы ===
/lp group moderator parent add vip
/lp group moderator permission set worldguardneo.region.info.others true
/lp group moderator permission set worldguardneo.region.list.others true
/lp group moderator permission set worldguardneo.region.lists.radius true
/lp group moderator permission set worldguardneo.region.flag.others true
/lp group moderator permission set worldguardneo.region.flag.group true
/lp group moderator permission set worldguardneo.region.flag.priority true
/lp group moderator permission set worldguardneo.region.flag.parent true
/lp group moderator permission set worldguardneo.flag.mob-spawning true
/lp group moderator permission set worldguardneo.flag.mob-damage true
/lp group moderator permission set worldguardneo.flag.tnt true
/lp group moderator permission set worldguardneo.flag.creeper-explosion true
/lp group moderator permission set worldguardneo.flag.fire-spread true
/lp group moderator permission set worldguardneo.flag.lava-fire true
/lp group moderator permission set worldguardneo.flag.lightning true
/lp group moderator permission set worldguardneo.flag.fall-damage true
/lp group moderator permission set worldguardneo.flag.fire-damage true
/lp group moderator permission set worldguardneo.flag.exp-drops true
/lp group moderator permission set worldguardneo.flag.crop-growth true
/lp group moderator permission set worldguardneo.flag.blocked-effects true
/lp group moderator permission set worldguardneo.flag.time-lock true
/lp group moderator permission set worldguardneo.flag.weather-lock true
/lp group moderator permission set worldguardneo.flag.notify-enter true
/lp group moderator permission set worldguardneo.flag.notify-leave true
/lp group moderator permission set worldguardneo.notify true

# === admin — администраторы (OP 3 эквивалент) ===
/lp group admin parent add moderator
/lp group admin permission set worldguardneo.region.delete.others true
/lp group admin permission set worldguardneo.region.flag.bypass true
/lp group admin permission set worldguardneo.region.admin true
/lp group admin permission set worldguardneo.flag.invincible true
/lp group admin permission set worldguardneo.flag.game-mode true
/lp group admin permission set worldguardneo.flag.allowed-cmds true
/lp group admin permission set worldguardneo.flag.blocked-cmds true
/lp group admin permission set worldguardneo.flag.send-chat true

# === superadmin — верхний эшелон (OP 4 эквивалент) ===
# Все 4 top-tier узла: bypass + info.global (доступ) + reload + backup (управление).
# Service-аккаунту автоматизации лучше выдавать конкретные узлы (например только backup)
# вместо членства в superadmin — принцип минимальных прав.
/lp group superadmin parent add admin
/lp group superadmin permission set worldguardneo.region.bypass true
/lp group superadmin permission set worldguardneo.region.info.global true
/lp group superadmin permission set worldguardneo.reload true
/lp group superadmin permission set worldguardneo.backup true
```

### Использование `*` для админов

Если хотите упростить — можно дать админам wildcard:

```bash
/lp group admin permission set worldguardneo.* true
```

⚠️ Но это даёт ВСЕ права включая `worldguardneo.region.bypass` и `worldguardneo.flag.invincible` — убедитесь что админам можно доверять.

### Per-world rights

LuckPerms поддерживает контекстные права. Например, чтобы дать игрокам клейм только в overworld:

```bash
/lp group default permission set worldguardneo.region.claim true world=minecraft:overworld
/lp group default permission set worldguardneo.region.claim false world=minecraft:the_nether
```

---

## Лимиты регионов

В `config/worldguardneo/config.json`:

| Поле | Default | Что значит |
|---|---|---|
| `maxRegionsPerPlayer` | 7 | Максимум регионов у одного игрока (без bypass). |
| `maxRegionVolume` | 50,000,000 | **Абсолютный** потолок объёма. Применяется ВСЕГДА, даже с bypass. Защита от случайного выделения "от bedrock до build limit". |
| `maxClaimableArea` | 1,000,000 | Потолок объёма для `/rg claim` без bypass. |
| `minRegionVolume` | 27 | Минимум (3×3×3). Защита от спам-микрорегионов. |

### Лимиты по LuckPerms-группам

Поле `groupRegionLimits` задаёт отдельный лимит на количество регионов для каждой LP-группы. **Эффективный лимит** игрока — максимум из глобального `maxRegionsPerPlayer` и всех его LP-групп. Имена групп нечувствительны к регистру.

```json
"groupRegionLimits": {
  "default":   7,
  "vip":      15,
  "premium":  30,
  "donator":  50,
  "moderator": 100,
  "admin":   1000
}
```

**Пример:** игрок состоит в группах `default + vip` → может создать **15** регионов.

**Если LuckPerms не установлен** — поле `groupRegionLimits` игнорируется, применяется только глобальный `maxRegionsPerPlayer`.

**Если игрок не в одной из перечисленных групп** — применяется глобальный `maxRegionsPerPlayer` (то есть default 7).

---

## Краткая шпаргалка по выдаче прав

| Уровень | Сильнейшие узлы | Что игрок может |
|---|---|---|
| **Гость** | (ничего) | Только ходить, не клеймить |
| **Обычный игрок** | `region.claim`, `region.flag.*` (безопасные) | Создавать свои регионы, настраивать pvp/entry/greeting |
| **VIP** | + расширенные флаги (heal, feed, max-speed) | Тонкая настройка своих регионов |
| **Модератор** | + `region.flag.others`, `region.list.others`, `region.lists.radius`, мощные флаги (tnt, fire, time-lock) | Помощь игрокам, настройка серверных зон, поиск чужих регионов |
| **Админ** | + `region.delete.others`, `flag.bypass`, опасные флаги (game-mode, invincible) | Управление регионами, мощные флаги |
| **Суперадмин** | + `region.bypass`, `region.info.global`, `reload`, `backup` | Полный обход защиты, просмотр глобального региона, перезагрузка config, backup. **Top-tier — давать только трасту.** |
