<div align="center">

# 🔑 Права WorldGuardNeo

**Все узлы прав, их OP-уровень по умолчанию и за что отвечает каждый.**

[English](PERMISSIONS.md) · **Русский**

[🏠 Главная](README_RU.md) · [🔨 Сборка](BUILD_RU.md) · **🔑 Права** · [⚙️ API](API_RU.md) · [📋 История](CHANGELOG.md)

</div>

---

## Как работают права

WorldGuardNeo проверяет каждую команду и защищённое действие по узлу прав. Есть два механизма:

- **С установленным LuckPerms** — LuckPerms становится **единственным источником** прав после загрузки игрока. Уровни OP игнорируются; выдавайте узлы через `/lp group <g> permission set <узел> true`.
- **Без LuckPerms** — каждый узел маппится на **уровень OP** (встроенный `OpResolver`). Игрок проходит проверку, если его уровень OP ≥ уровня узла. Неизвестные узлы по умолчанию — **OP 2**.

Уровни admin/mod настраиваются в `config.toml` (`default-op-level-admin` = 3, `default-op-level-mod` = 2 по умолчанию) и перечитываются по `/rg reload`.

> **OP 5 = никогда по OP.** Максимальный уровень OP в Minecraft — 4, поэтому узел с уровнем 5 (`region.bypass`) можно выдать **только** явно через LuckPerms — одним лишь уровнем OP он не покрывается.

## Узлы регионов

| Узел | По умолчанию | За что отвечает |
| --- | --- | --- |
| `worldguardneo.region.claim` | OP 0 | `/rg claim` — создать регион. Применяются все лимиты (счётчики на игрока/группу, объём, площадь, оверлап). |
| `worldguardneo.region.delete` | OP 0 | `/rg remove` — удалить **свой** регион. |
| `worldguardneo.region.delete.others` | OP 3 | Удалить **любой** регион. |
| `worldguardneo.region.redefine` | OP 2 | `/rg redefine` — изменить размер региона под выделение. |
| `worldguardneo.region.info` | OP 0 | `/rg info` — посмотреть видимый регион. |
| `worldguardneo.region.info.others` | OP 2 | Просмотр чужих регионов. |
| `worldguardneo.region.info.global` | OP 4 | Просмотр глобального региона (`__global__`). |
| `worldguardneo.region.list` | OP 0 | `/rg list` — список своих регионов. |
| `worldguardneo.region.list.others` | OP 2 | Список регионов другого игрока. |
| `worldguardneo.region.lists.radius` | OP 2 | `/rg lists <радиус>` — список регионов рядом. |
| `worldguardneo.region.teleport` | OP 0 | `/rg teleport` — телепорт к флагу `teleport` региона. |

## Узлы членства

| Узел | По умолчанию | За что отвечает |
| --- | --- | --- |
| `worldguardneo.region.addowner` | OP 2 | Добавить владельца. |
| `worldguardneo.region.removeowner` | OP 2 | Удалить владельца. |
| `worldguardneo.region.addmember` | OP 2 | Добавить участника. |
| `worldguardneo.region.removemember` | OP 2 | Удалить участника. |

## Узлы флагов

| Узел | По умолчанию | За что отвечает |
| --- | --- | --- |
| `worldguardneo.region.flag` | OP 2 | `/rg flag` — установить флаг (на своих регионах также проверяется владение). |
| `worldguardneo.region.flag.others` | OP 2 | Флаги на чужих регионах. |
| `worldguardneo.region.flag.bypass` | OP 3 | Обход проверки групповых ограничений флага. |
| `worldguardneo.region.flag.group` | OP 2 | Использование синтаксиса `-g <группа>`. |
| `worldguardneo.region.flag.priority` | OP 2 | `/rg priority` — задать приоритет региона. |
| `worldguardneo.region.flag.parent` | OP 2 | `/rg setparent` — задать родителя региона. |
| `worldguardneo.region.flags.list` | OP 2 | `/rg flags` — список установленных флагов. |

## Узлы выделения и администрирования

| Узел | По умолчанию | За что отвечает |
| --- | --- | --- |
| `worldguardneo.selection.use` | OP 0 | `/rg select` и получение кастомного `//wand`. |
| `worldguardneo.region.admin` | OP 3 | Административные операции с регионами. |
| `worldguardneo.region.bypass` | **OP 5** | Полный обход защиты регионов. По OP не выдаётся — только LuckPerms. |
| `worldguardneo.backup` | OP 4 | `/rg backup` — ручной бэкап. |
| `worldguardneo.reload` | OP 4 | `/rg reload` — перезагрузка конфига и языка. |
| `worldguardneo.notify` | OP 2 | Получение уведомлений о нарушениях. |

## Примеры LuckPerms

Открыть создание регионов всем (узел и так OP 0, но явно — понятнее):

```
/lp group default permission set worldguardneo.region.claim true
```

Дать группе `builder` управление регионами без OP:

```
/lp group builder permission set worldguardneo.region.redefine true
/lp group builder permission set worldguardneo.region.flag true
/lp group builder permission set worldguardneo.region.addmember true
```

Выдать доверенному персоналу полный обход (единственный способ получить `region.bypass`):

```
/lp group admin permission set worldguardneo.region.bypass true
```

Закрыть палочку WorldEdit от обычных игроков (см. [FAQ по правам в README](README_RU.md#права)):

```
/lp group default permission set worldguardneo.selection.use false
```

## Лицензия

Распространяется под **GNU General Public License v3.0 или новее**. Полный текст — в файле [`LICENSE`](LICENSE).