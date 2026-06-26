<div align="center">

# 🚩 WorldGuardNeo — Флаги

**Каждый флаг региона: тип, значение по умолчанию, право и назначение.**

[English](FLAGS.md) · **Русский**

[🏠 Главная](README_RU.md) · [🔨 Сборка](BUILD_RU.md) · [🔑 Права](PERMISSIONS_RU.md) · [🚩 Флаги](FLAGS_RU.md) · [⚙️ API](API_RU.md) · [🧩 KubeJS](KUBEJS.md) · [📋 История](CHANGELOG.md)

</div>

---

## Как работают флаги

Установка: `/rg flag <регион> <флаг> [значение]`; без значения — снять. Полный список в игре: `/rg flags`.

- **state**-флаги принимают `allow` / `deny` / `none` (снять). Колонка **По умолчанию** — значение, когда флаг не задан *и* нет региона. Внутри региона build-флаги (`build`, `block-break`, `block-place`, `interact`, `use`, `chest-access`) при незаданном значении опираются на **членство** — владельцы/участники проходят, чужие получают отказ («приватно по умолчанию»).
- **value**-флаги (текст, целое, число, список) хранят данные; незаданное значение = функция выключена.
- Каждый флаг защищён своим правом `worldguardneo.flag.<имя>` (по умолчанию OP 2). `region.flag.bypass` или `region.bypass` пропускает проверку.
- `-g <группа>` перед значением ограничивает флаг группой `OWNERS` / `MEMBERS` / `NON_OWNERS` / `NON_MEMBERS` / `ALL` (нужно право `region.flag.group`).

Флаги `on-entry` / `on-exit` выполняют консольную команду (плейсхолдеры `%player%`, `%region%`, `%world%`) при пересечении границы региона — задаются только администраторами, т.к. команда исполняется с правами консоли.

## Все флаги

| Флаг | Тип | По умолчанию | Право | Описание |
| --- | --- | --- | --- | --- |
| `allowed-cmds` | список | — | `worldguardneo.flag.allowed-cmds` | Белый список разрешённых команд (имеет приоритет над blocked). |
| `on-entry` | текст | — | `worldguardneo.flag.on-entry` | Консольная команда при входе игрока (`%player%`/`%region%`/`%world%`). |
| `on-exit` | текст | — | `worldguardneo.flag.on-exit` | Консольная команда при выходе игрока (`%player%`/`%region%`/`%world%`). |
| `block-break` | состояние (allow/deny) | allow | `worldguardneo.flag.block-break` | Разрешить или запретить именно ломание блоков. |
| `block-place` | состояние (allow/deny) | allow | `worldguardneo.flag.block-place` | Разрешить или запретить именно установку блоков. |
| `blocked-cmds` | список | — | `worldguardneo.flag.blocked-cmds` | Команды, запрещённые в регионе. |
| `blocked-effects` | список | — | `worldguardneo.flag.blocked-effects` | Эффекты зелий, подавляемые в регионе. |
| `build` | состояние (allow/deny) | allow | `worldguardneo.flag.build` | Разрешить или запретить ломать и ставить блоки в целом. |
| `chest-access` | состояние (allow/deny) | allow | `worldguardneo.flag.chest-access` | Разрешить или запретить открытие контейнеров (сундуки, бочки, воронки). |
| `chorus-teleport` | состояние (allow/deny) | allow | `worldguardneo.flag.chorus-teleport` | Разрешить или запретить телепорт плодом коруса. |
| `creeper-explosion` | состояние (allow/deny) | allow | `worldguardneo.flag.creeper-explosion` | Разрешить или запретить урон блокам от взрыва крипера. |
| `crop-growth` | состояние (allow/deny) | allow | `worldguardneo.flag.crop-growth` | Разрешить или запретить рост культур (пшеница, свёкла и т.д.). |
| `deny-message` | текст | — | `worldguardneo.flag.deny-message` | Своё сообщение при отказе защиты. |
| `deny-spawn` | список | — | `worldguardneo.flag.deny-spawn` | ID сущностей, спавн которых запрещён. |
| `spawn-limit` | список | — | `worldguardneo.flag.spawn-limit` | Лимиты на тип в виде `entity-id:max` (напр. `minecraft:zombie:5`); спавн типа запрещается, когда в регионе уже `max` таких сущностей. |
| `dispenser-output` | состояние (allow/deny) | allow | `worldguardneo.flag.dispenser-output` | Разрешить или запретить выдачу из раздатчика/дропера (предметы, жидкости, снаряды). Также блокирует стрельбу через границу региона. |
| `drown-damage` | состояние (allow/deny) | allow | `worldguardneo.flag.drown-damage` | Разрешить или запретить урон от утопления. |
| `enderdragon` | состояние (allow/deny) | allow | `worldguardneo.flag.enderdragon` | Разрешить или запретить разрушение блоков драконом Края. |
| `enderpearl` | состояние (allow/deny) | allow | `worldguardneo.flag.enderpearl` | Разрешить или запретить телепорт жемчугом Края. |
| `entity-leash` | состояние (allow/deny) | allow | `worldguardneo.flag.entity-leash` | Разрешить или запретить привязывание поводка к мобу. В регионе не-участники блокируются через доступ `interact`; явный `deny` блокирует всех. |
| `entry` | состояние (allow/deny) | allow | `worldguardneo.flag.entry` | Разрешить или запретить вход в регион (для игроков без bypass). |
| `entry-deny-message` | текст | — | `worldguardneo.flag.entry-deny-message` | Своё сообщение при запрете входа. |
| `entry-vehicle` | состояние (allow/deny) | allow | `worldguardneo.flag.entry-vehicle` | Разрешить или запретить вход в регион верхом на транспорте. |
| `exit` | состояние (allow/deny) | allow | `worldguardneo.flag.exit` | Разрешить или запретить выход из региона. |
| `exit-deny-message` | текст | — | `worldguardneo.flag.exit-deny-message` | Своё сообщение при запрете выхода. |
| `exit-vehicle` | состояние (allow/deny) | allow | `worldguardneo.flag.exit-vehicle` | Разрешить или запретить выход из региона верхом на транспорте. |
| `exp-drops` | состояние (allow/deny) | allow | `worldguardneo.flag.exp-drops` | Разрешить или запретить выпадение сфер опыта. |
| `fall-damage` | состояние (allow/deny) | allow | `worldguardneo.flag.fall-damage` | Разрешить или запретить урон от падения. |
| `farewell` | текст | — | `worldguardneo.flag.farewell` | Сообщение при выходе игрока из региона. |
| `farewell-title` | текст | — | `worldguardneo.flag.farewell-title` | Заголовок (title) при выходе. |
| `feed-amount` | целое | — | `worldguardneo.flag.feed-amount` | Сколько голода восстанавливается за тик. |
| `feed-delay` | целое | — | `worldguardneo.flag.feed-delay` | Секунды между тиками авто-кормления. |
| `feed-max-hunger` | целое | — | `worldguardneo.flag.feed-max-hunger` | Авто-кормление не превысит этот голод. |
| `feed-min-hunger` | целое | — | `worldguardneo.flag.feed-min-hunger` | Авто-кормление не сработает выше этого голода. |
| `fire-damage` | состояние (allow/deny) | allow | `worldguardneo.flag.fire-damage` | Разрешить или запретить урон от огня. |
| `fire-spread` | состояние (allow/deny) | allow | `worldguardneo.flag.fire-spread` | Разрешить или запретить распространение огня по блокам. |
| `frosted-ice-melt` | состояние (allow/deny) | allow | `worldguardneo.flag.frosted-ice-melt` | Разрешить или запретить таяние льда от Ледоступа. |
| `game-mode` | текст | — | `worldguardneo.flag.game-mode` | Принудительный режим игры (survival, creative, ...). |
| `ghast-fireball` | состояние (allow/deny) | allow | `worldguardneo.flag.ghast-fireball` | Разрешить или запретить урон блокам от взрыва гаста/огненного шара. |
| `grass-spread` | состояние (allow/deny) | allow | `worldguardneo.flag.grass-spread` | Разрешить или запретить распространение травы. |
| `greeting` | текст | — | `worldguardneo.flag.greeting` | Сообщение при входе игрока в регион. |
| `greeting-title` | текст | — | `worldguardneo.flag.greeting-title` | Заголовок (title) при входе. |
| `heal-amount` | целое | — | `worldguardneo.flag.heal-amount` | Сколько HP лечится за тик. |
| `heal-delay` | целое | — | `worldguardneo.flag.heal-delay` | Секунды между тиками авто-лечения. |
| `heal-max-hp` | целое | — | `worldguardneo.flag.heal-max-hp` | Авто-лечение не превысит этот HP. |
| `heal-min-hp` | целое | — | `worldguardneo.flag.heal-min-hp` | Авто-лечение не сработает ниже этого HP. |
| `hunger-drain` | состояние (allow/deny) | allow | `worldguardneo.flag.hunger-drain` | Разрешить или запретить расход голода в регионе. |
| `ice-form` | состояние (allow/deny) | allow | `worldguardneo.flag.ice-form` | Разрешить или запретить образование льда. |
| `ice-melt` | состояние (allow/deny) | allow | `worldguardneo.flag.ice-melt` | Разрешить или запретить таяние льда. |
| `interact` | состояние (allow/deny) | allow | `worldguardneo.flag.interact` | Разрешить или запретить ПКМ-взаимодействие с блоками и сущностями. |
| `invincible` | состояние (allow/deny) | deny | `worldguardneo.flag.invincible` | Сделать игроков неуязвимыми в регионе. |
| `item-drop` | состояние (allow/deny) | allow | `worldguardneo.flag.item-drop` | Разрешить или запретить выброс предметов на землю. |
| `item-pickup` | состояние (allow/deny) | allow | `worldguardneo.flag.item-pickup` | Разрешить или запретить подъём предметов с земли. |
| `keep-inventory` | состояние (allow/deny) | deny | `worldguardneo.flag.keep-inventory` | Сохранять предметы игрока при смерти в этом регионе. |
| `keep-xp` | состояние (allow/deny) | deny | `worldguardneo.flag.keep-xp` | Сохранять опыт игрока при смерти в этом регионе. |
| `lava-fire` | состояние (allow/deny) | allow | `worldguardneo.flag.lava-fire` | Разрешить или запретить поджигание лавой соседних блоков. |
| `lava-flow` | состояние (allow/deny) | allow | `worldguardneo.flag.lava-flow` | Разрешить или запретить течение лавы. |
| `leaf-decay` | состояние (allow/deny) | allow | `worldguardneo.flag.leaf-decay` | Разрешить или запретить естественное опадание листвы. |
| `lightning` | состояние (allow/deny) | allow | `worldguardneo.flag.lightning` | Разрешить или запретить удары молний в регионе. |
| `max-speed` | число | — | `worldguardneo.flag.max-speed` | Максимальная скорость передвижения в регионе. |
| `mob-damage` | состояние (allow/deny) | allow | `worldguardneo.flag.mob-damage` | Разрешить или запретить атаку игроками мобов. |
| `mob-grief` | состояние (allow/deny) | allow | `worldguardneo.flag.mob-grief` | Разрешить или запретить изменение блоков мобами (эндермен берёт/ставит, овца ест траву и т.п.). |
| `mob-spawning` | состояние (allow/deny) | allow | `worldguardneo.flag.mob-spawning` | Разрешить или запретить естественный спавн мобов. |
| `mob-teleport` | состояние (allow/deny) | allow | `worldguardneo.flag.mob-teleport` | Разрешить или запретить телепорт мобов (Эндермены, выстрелы Шалкера). |
| `mycelium-spread` | состояние (allow/deny) | allow | `worldguardneo.flag.mycelium-spread` | Разрешить или запретить распространение мицелия. |
| `notify-enter` | true/false | — | `worldguardneo.flag.notify-enter` | Оповещать админов о входе игроков. |
| `notify-leave` | true/false | — | `worldguardneo.flag.notify-leave` | Оповещать админов о выходе игроков. |
| `other-explosion` | состояние (allow/deny) | allow | `worldguardneo.flag.other-explosion` | Разрешить или запретить взрывы, не покрытые более конкретными флагами. |
| `pistons` | состояние (allow/deny) | allow | `worldguardneo.flag.pistons` | Разрешить или запретить поршням двигать блоки через границу региона. |
| `player-damage` | состояние (allow/deny) | allow | `worldguardneo.flag.player-damage` | Разрешить или запретить общий урон игрокам от не-игроков. |
| `pvp` | состояние (allow/deny) | allow | `worldguardneo.flag.pvp` | Разрешить или запретить урон между игроками. |
| `receive-chat` | состояние (allow/deny) | allow | `worldguardneo.flag.receive-chat` | Разрешить или запретить получение чужого чата внутри региона. |
| `redstone` | состояние (allow/deny) | allow | `worldguardneo.flag.redstone` | Разрешить или запретить распространение редстоун-сигнала в регионе. |
| `ride` | состояние (allow/deny) | allow | `worldguardneo.flag.ride` | Разрешить или запретить езду на мобах (лошадь, свинья, лавоход). В регионе не-участники блокируются через доступ `interact`; явный `deny` блокирует всех. Вагонетки/лодки — `vehicle-enter`. |
| `send-chat` | состояние (allow/deny) | allow | `worldguardneo.flag.send-chat` | Разрешить или запретить отправку чата из региона. |
| `sleep` | состояние (allow/deny) | allow | `worldguardneo.flag.sleep` | Разрешить или запретить сон в кроватях. |
| `snow-fall` | состояние (allow/deny) | allow | `worldguardneo.flag.snow-fall` | Разрешить или запретить накопление снега. |
| `snow-melt` | состояние (allow/deny) | allow | `worldguardneo.flag.snow-melt` | Разрешить или запретить таяние снега. |
| `spawn` | текст | — | `worldguardneo.flag.spawn` | Координаты возрождения для игроков региона. |
| `suffocation-damage` | состояние (allow/deny) | allow | `worldguardneo.flag.suffocation-damage` | Разрешить или запретить урон от удушья (в блоках). |
| `teleport` | текст | — | `worldguardneo.flag.teleport` | Точка назначения для /rg teleport, формат x,y,z. |
| `time-lock` | текст | — | `worldguardneo.flag.time-lock` | Фиксация клиентского времени (day, night, sunrise, sunset или число). |
| `tnt` | состояние (allow/deny) | allow | `worldguardneo.flag.tnt` | Разрешить или запретить урон блокам от взрыва ТНТ. |
| `use` | состояние (allow/deny) | allow | `worldguardneo.flag.use` | Разрешить или запретить использование предметов (еда, зелья, двери). |
| `vehicle-destroy` | состояние (allow/deny) | allow | `worldguardneo.flag.vehicle-destroy` | Разрешить или запретить уничтожение вагонеток и лодок. |
| `vehicle-place` | состояние (allow/deny) | allow | `worldguardneo.flag.vehicle-place` | Разрешить или запретить установку вагонеток и лодок. |
| `vehicle-enter` | состояние (allow/deny) | allow | `worldguardneo.flag.vehicle-enter` | Разрешить или запретить посадку игроков в вагонетки и лодки. |
| `item-frame-rotate` | состояние (allow/deny) | allow | `worldguardneo.flag.item-frame-rotate` | Разрешить или запретить вращение предмета в заполненной рамке (установка/изъятие — по build-доступу). |
| `sign-edit` | состояние (allow/deny) | allow | `worldguardneo.flag.sign-edit` | Разрешить или запретить редактирование табличек. |
| `lectern-take` | состояние (allow/deny) | allow | `worldguardneo.flag.lectern-take` | Разрешить или запретить использование кафедры с книгой. |
| `armor-stand-use` | состояние (allow/deny) | allow | `worldguardneo.flag.armor-stand-use` | Разрешить или запретить использование (смену экипировки) стоек для брони. |
| `glide` | состояние (allow/deny) | allow | `worldguardneo.flag.glide` | Разрешить или запретить полёт на элитрах; запрет принудительно прерывает планирование в регионе. |
| `bucket-fill` | состояние (allow/deny) | allow | `worldguardneo.flag.bucket-fill` | Разрешить или запретить наполнение вёдер (вода, лава, снег). |
| `bucket-empty` | состояние (allow/deny) | allow | `worldguardneo.flag.bucket-empty` | Разрешить или запретить выливание вёдер (установку жидкостей). |
| `villager-trade` | состояние (allow/deny) | allow | `worldguardneo.flag.villager-trade` | Разрешить или запретить открытие меню торговли жителей / странствующего торговца. В регионе не-участники блокируются через доступ `interact`; явный `deny` блокирует всех. |
| `vine-growth` | состояние (allow/deny) | allow | `worldguardneo.flag.vine-growth` | Разрешить или запретить рост лиан. |
| `water-flow` | состояние (allow/deny) | allow | `worldguardneo.flag.water-flow` | Разрешить или запретить течение воды. |
| `weather-lock` | текст | — | `worldguardneo.flag.weather-lock` | Фиксация клиентской погоды (clear, rain, thunder). |

## Лицензия

Распространяется под **GNU General Public License v3.0 или новее**. Полный текст — в [`LICENSE`](LICENSE).
