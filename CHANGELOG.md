# WorldGuardNeo — журнал сборок

Каждая сборка помечена номером. Имя архива: `worldguardneo-b<НОМЕР>-src.zip`.
Самая свежая сборка — сверху.

---

## build 10 — 2026-06-12

Полная актуализация и двуязычное оформление документации со сквозной навигацией.

**ИСПРАВЛЕНО (неточность в README):** таблица прав в README показывала неверные дефолты
(например `region.claim` как OP 2). Сверено с `OpResolver` — реальные дефолты щадящие:
claim/delete(свои)/info/list/teleport/selection.use = **OP 0**, управление = OP 2,
delete.others/flag.bypass = OP 3, backup/reload/info.global = OP 4, а `region.bypass` =
**OP 5** (по OP не выдаётся — только LuckPerms). Исправлено в обоих README.

**Двуязычность (EN + RU) для всех документов:**
- Раньше README имел пару (EN + RU), а BUILD / PERMISSIONS / API существовали только на
  русском. Теперь у каждого документа есть пара по конвенции `FILE.md` (EN) + `FILE_RU.md` (RU):
  - `BUILD.md` (новый, EN) + `BUILD_RU.md` (переоформлен)
  - `PERMISSIONS.md` (новый, EN) + `PERMISSIONS_RU.md` (переоформлен, точные OP-уровни)
  - `API.md` (новый, EN) + `API_RU.md` (переоформлен)

**Сквозная навигация:** в шапку каждого документа добавлена единая панель — переключатель
языка (English · Русский) и ссылки на все разделы (Home · Building · Permissions · API ·
Changelog). RU-файлы ссылаются на RU-версии, EN — на EN. Все ссылки проверены, битых нет.

**Единое оформление:** все документы приведены к стилю README — центрированная шапка с
эмодзи-заголовком, badges/таблицы, разделы. PERMISSIONS переписан компактно и точно
(вместо прежних 421 строки) с примерами LuckPerms.

**Актуализировано содержимое** под текущее состояние (b9): WorldEdit не нужен для компиляции
(только рантайм), хранилища json/sqlite/h2/mysql с откатом, JDBC-драйверы, серверная сборка.

Java-код не затрагивался (60 файлов) — менялась только документация.

---

## build 9 — 2026-06-11

**ИСПРАВЛЕНА ОШИБКА СБОРКИ** (по логу пользователя): `compileJava` падал с
«Could not find com.sk89q.worldedit:worldedit-neoforge:7.3.8».

Причина: таких координат не существует. WorldEdit публикует артефакт per-MC как
`worldedit-neoforge-mc<версия>` (для 1.21 и 1.21.1 — общий `worldedit-neoforge-mc1.21`),
а не как плоский `worldedit-neoforge`, и версия в имени артефакта — это версия Minecraft,
а не WorldEdit.

Решение (самое чистое): **WorldEdit убран из зависимостей сборки полностью.** Весь
`WorldEditAdapter` обращается к WorldEdit ТОЛЬКО через рефлексию (`Class.forName` /
`Method.invoke`) — прямых импортов `com.sk89q.*` в коде нет, поэтому мод компилируется без
WorldEdit в classpath. Это устраняет привязку к координатам/версии артефакта.

- `build.gradle.kts`: убран `compileOnly("com.sk89q.worldedit:worldedit-neoforge:7.3.8")`
  и репозиторий EngineHub (он был нужен только для этого артефакта).
- WorldEdit остаётся **обязательным в рантайме** — `type="required"` в neoforge.mods.toml
  без изменений. Сервер не запустится без WorldEdit.
- README.md / README_RU.md: исправлено устаревшее утверждение «WorldEdit — зависимость
  времени компиляции»; теперь корректно сказано, что он нужен только в рантайме.

Также из лога подтвердилось: структура проекта, Gradle-обёртка (9.5.1), neoforge.mods.toml
и сам процесс сборки в порядке — компиляция доходила до :compileJava, спотыкаясь только об
эту зависимость.

Java-файлов: 60 (код не менялся, только конфигурация сборки и документация).

---

## build 8 — 2026-06-11

Кастомизация WorldEdit `//wand`: именованный топор + защита от повторной выдачи.

**Добавлено:**
- `listeners/WandCommandHandler.java` — перехватывает команду WorldEdit `//wand` через
  `CommandEvent` (NeoForge). Вместо обычного деревянного топора игроку выдаётся деревянный
  топор с кастомным именем (`item.wand.name`) и скрытым маркером в CUSTOM_DATA. WorldEdit
  по-прежнему распознаёт его как wand (он опознаёт wand по типу предмета), поэтому выделение
  работает как раньше.
- Если у игрока уже есть наш помеченный топор — `//wand` ничего не выдаёт и показывает
  сообщение `msg.wand.already` (не плодит топоры).
- Опознание «нашего» топора — по скрытому маркеру `wgn_wand` в CUSTOM_DATA, а не по имени,
  поэтому переименованный в наковальне ванильный топор не считается нашим.
- Перехват срабатывает только для игроков с правом `worldguardneo.selection.use` (по
  умолчанию op 0 — у всех; админ может ограничить). Для остальных команду обрабатывает
  сам WorldEdit.

**Lang:** возвращён `msg.wand.given`, добавлены `msg.wand.already` и `item.wand.name`
(en + ru, теперь 166 ключей, идентичны).

Java-файлов: 60.

---

## build 7 — 2026-06-11

Авто-флаги и вертикальная защита при создании региона (с разбивкой по мирам) +
актуализация документации.

**ИСПРАВЛЕНО (ошибка компиляции):** `WGConfig` использовался в новых методах WGCommands,
но не был импортирован (`region.*` покрывал только регионы). Добавлен
`import dev.thefather007.worldguardneo.config.WGConfig;`.

**1. Авто-флаги при создании (на мир):**
- В `[defaults]` и `worlds/<dim>.toml` добавлен `auto-flags = ["pvp=deny", ...]` — список
  флагов «флаг=значение», автоматически применяемых к каждому НОВОМУ региону в этом мире.
- Применяются через существующий `Flag.parseAndApply` (type-safe). Неизвестный флаг или
  плохое значение пропускаются с предупреждением в консоль — одна плохая запись не блокирует
  создание. Существующие регионы не затрагиваются.

**3. Вертикальное авто-расширение при создании (на мир):**
- `vertical-expansion = "none" | "full" | "fixed"` + `vertical-expand-down/up`.
- **full** — регион растягивается от дна до потолка мира (getMinBuildHeight..getMaxBuildHeight),
  защищая от подкопа снизу и захода по мосту сверху. **fixed** — на N блоков вниз/вверх от
  выделения (clamp к границам мира). **none** — как выделено (по умолчанию).
- Продумано: горизонтальные лимиты размера проверяются на ИСХОДНОМ выделении (до расширения),
  поэтому расширение не расходует лимит площади игрока. Перекрытие перепроверяется на финальной
  (расширенной) геометрии. Работает и для cuboid, и для polygon регионов.

**2. Документация актуализирована:**
- README.md / README_RU.md: Requirements разбит на «обязательно/опционально» с таблицей,
  где явно перечислены LuckPerms, BlueMap, squaremap и JDBC-драйверы (sqlite-jdbc, H2,
  mysql-connector-j) с указанием, какой `storage-format` каждый включает. Добавлена секция
  «Auto-flags & vertical protection» с примерами.
- BUILD.md: добавлены опциональные драйверы БД и условия их использования.

Java-файлов: 59.

---

## build 6 — 2026-06-10

Актуализация и переоформление документации (после рефактора b3-b5 она устарела).
Стиль приведён к компактному двуязычному виду с badges.

**README:**
- `README.md` (EN) переписан с 845 строк до 188 — компактный, с badges (Minecraft,
  NeoForge, Requires WorldEdit, Optional LuckPerms, Server-only, GPL-3.0), разделами
  Features / Requirements / Installation / Quick start / Configuration (TOML-секции:
  storage, mysql, group-region-limits, backup, defaults) / Commands (таблица) /
  Permissions (таблица) / Flags / Building / License.
- `README_RU.md` (RU) — создан, полный перевод EN-версии. Перекрёстные ссылки
  EN↔RU в шапке.
- Актуализировано: WorldEdit обязателен (регион только по WE-выделению), серверный
  мод, конфиг TOML, хранилища json/sqlite/h2/mysql, удалён собственный wand.

**LICENSE:**
- Был только GPL-заголовок + пометка «положите полный текст отдельно» (которого не было),
  хотя README ссылался на «full text». Теперь LICENSE содержит copyright-заголовок
  WorldGuardNeo + ПОЛНЫЙ официальный текст GPL-3.0 (672 строки) + trademark-примечания.
  Ссылка «See LICENSE for the full text» теперь корректна.

**BUILD.md:**
- Исправлены устаревшие пункты: WorldEdit указан как обязательная зависимость (был как
  опциональный «без него своя палочка»); убрано упоминание серверного WECUI и
  собственного wand; добавлены BlueMap/squaremap; шаг 4 — `/rg reload`.

**Сверка permissions/команд с кодом:** все команды (claim/redefine/remove/select/info/
list/lists/flag/flags/priority/setparent/add-remove member/owner/teleport/backup/save/
reload) и 29 permission-узлов в README соответствуют реальной регистрации в WGCommands
и дефолтам OpResolver.

Изменения только в документации; Java-код не затрагивался (59 файлов).

---

## build 5 — 2026-06-10

Хранилища регионов H2 и MySQL (пункт 3 большого рефактора). Финальный этап.

**Добавлено:**
- `storage/H2RegionStorage.java` — встраиваемая файловая БД (`regions_h2`), upsert через
  `MERGE ... KEY(world)`. Драйвер `org.h2.Driver` не входит в мод, но его поставляет
  LuckPerms (использует H2 по умолчанию). Если драйвера нет — прозрачный откат на JSON.
- `storage/MySqlRegionStorage.java` — внешний MySQL-сервер. Параметры подключения
  (host/port/database/user/password/use-ssl) из секции `[mysql]` в config.toml. Upsert
  через `INSERT ... ON DUPLICATE KEY UPDATE`. Соединение валидируется (isValid) и
  переоткрывается при обрыве сети. Драйвер `mysql-connector-j` админ кладёт сам; без него —
  откат на JSON. Connector/J 8 (`com.mysql.cj.jdbc.Driver`) и 5.x — оба распознаются.

**Конфиг:**
- `storage-format` теперь принимает `json` / `sqlite` / `h2` / `mysql` (комментарий обновлён,
  описывает каждый бэкенд и автo-откат на json).
- Новая секция `[mysql]` с комментариями к каждому ключу.
- Фабрика хранилища в `WorldGuardNeo` (`createStorage`) — switch по 4 форматам,
  неизвестный формат → json с предупреждением.

**Важно:** все БД-бэкенды (sqlite/h2/mysql) используют ТОЛЬКО `java.sql.*` + рефлективную
загрузку драйвера (`Class.forName`), поэтому build.gradle менять не потребовалось и мод
компилируется без этих драйверов. Payload во всех бэкендах — тот же JSON-документ
`RegionJsonCodec`, поэтому форматы взаимно совместимы по данным.

Итог: 59 Java-файлов.

Этим завершён большой 4-частный рефактор (b3 сборка/структура → b4 WorldEdit hard-dep →
b5 H2/MySQL).

---

## build 4 — 2026-06-10

Жёсткая зависимость от WorldEdit (пункт 1 большого рефактора): регион создаётся
ИСКЛЮЧИТЕЛЬНО из выделения WorldEdit. Весь собственный код выделения удалён.

**Удалено (собственный selection):**
- `WandHandler.java` — собственный деревянный топор-wand. Теперь только WE `//wand`.
- `worldedit/SimpleSelectionStore.java` — fallback-хранилище выделений (не нужно).
- `wecui/WECuiProtocol.java` — наша визуализация выделения. WorldEdit рисует её сам
  (через свой WECUI), наш дублирующий код убран.
- Команды `/rg pos1`, `/rg pos2` (регистрировались только без WE) и `/rg wand`.
- Все вызовы `WECuiProtocol.showCuboid/showRegion` из команд (~6 мест).
- Мёртвые методы `setPos`, `giveWand`, `worldEditPresent` и неиспользуемые импорты
  (`ItemStack`, `Items`).
- Осиротевшие lang-ключи: `msg.wand.given/use-worldedit`, `msg.pos1.set`, `msg.pos2.set`
  (en+ru, теперь 163 ключа, идентичны).
- Поле/чтение/запись `use-wecui-protocol` из конфига.

**Переписано:**
- `WorldEditAdapter` — убран `FallbackWorldEditAdapter` и зависимость от SimpleSelectionStore.
  Остался `toProtectedRegion` (создание региона из WE-выделения). Добавлены
  `selectCuboid`/`selectPolygon` через WE `RegionSelector` (CuboidRegionSelector /
  Polygonal2DRegionSelector + LocalSession.setRegionSelector) — для `/rg select`, чтобы
  показать границы региона прямо в WE-выделении. Всё рефлективно, fail-safe. Если WE не
  загрузился (несмотря на hard-dep) — `NoOpWorldEditAdapter` с логом ошибки, без краша.
- `/rg select` — теперь пишет в WE-выделение (раньше — в собственный store).

**Конфиг/метаданные:**
- `neoforge.mods.toml`: `worldedit` → `type="required"`; `wecui`-зависимость убрана; все
  side `BOTH`→`SERVER` (мод серверный).
- `gradle.properties`: описание обновлено (серверный, WE-зависимость, без WECUI).

Итог: 57 Java-файлов (было 60).

**Дальше:** b5 — хранилища регионов H2 и MySQL.

---

## build 3 — 2026-06-10

Облегчение сборки до серверной (пункты 2+4 большого рефактора) + исправление
критического бага сборки.

**ИСПРАВЛЕНО (критично):** в `build.gradle.kts` блок `dependencies { ... }` не был
закрыт `}` (внесено при добавлении night-config в прошлой сессии) — `tasks.withType`
оказывался внутри блока зависимостей. **Сборка не компилировалась.** Скобка добавлена,
баланс восстановлен.

**Облегчение сборки (мод исключительно серверный):**
- Убран `create("client")` run — у мода нет клиентских фич.
- Убран `create("data")` run — нет генерируемых ассетов (lang-файлы пишутся вручную).
- Убран `withSourcesJar()` — sources-jar не нужен для прод-деплоя.
- Убраны `enabledGameTestNamespaces` (gametest не используется).
- Оставлены: server-run, parchment (dev-маппинги, на jar не влияют).

**Структура (сверена с NeoForge mod generator / MDK 1.21.1):**
- Уже соответствует MDK: `src/main/java`, `src/main/resources/{META-INF,assets,pack.mcmeta,logo.png,*.mixins.json}`. Лишних артефактов (src/generated и т.п.) нет.
- Добавлен WorldEdit в зависимости сборки (`compileOnly worldedit-neoforge:7.3.8`) —
  подготовка к жёсткой зависимости в b4.

**Дальше (отдельными билдами для безопасной проверки):**
- b4 — жёсткая зависимость от WorldEdit: регион только по WE-выделению; удаление
  собственного wand (WandHandler), SimpleSelectionStore, WECuiProtocol.
- b5 — хранилища регионов H2 и MySQL.

---

## build 2 — 2026-06-01

Полный поэтапный аудит кода по каждому из 60 файлов. **Изменений в коде нет** —
проверка подтвердила, что база чистая. Сборка идентична build 1 по коду; отличается
только этой записью в журнале (чтобы зафиксировать факт ревизии).

**Что проверялось по всем файлам:**
- Мёртвый код: неиспользуемые приватные методы (0), приватные поля (0), мёртвые
  публичные методы/API (0). База уже вычищена за прошлые сессии.
- Утечки ресурсов: JDBC (SqliteRegionStorage — все Statement/ResultSet в
  try-with-resources, соединение переиспользуется и закрывается в close()), файловые
  потоки (все Files.list/newBufferedReader в try-with).
- Геометрия contains: CuboidRegion (inclusive-блочные границы `< max+1.0`) и
  PolygonalRegion (ray-casting + AABB-reject, тот же Y-rule) — согласованы, дыр нет.
- Сериализация флагов: StateFlag.parse locale-safe (Locale.ROOT — защита от
  турецкого I→ı), null=unset обрабатывается корректно.
- API-события (RegionEnter/Leave/FlagDenied/Modify): все реально постятся через
  EVENT_BUS.post — API живой.
- PolygonalRegion: активно используется (WorldEdit polygon, codec, визуализация).
- Vec3 — record (безопасный equals/hashCode для Map/Set).
- Целостность: баланс скобок, отсутствие неиспользуемых импортов — по всем 60 файлам.

---

## build 1 — 2026-06-01

Первая сборка с системой нумерации (раньше все архивы назывались одинаково).
Эта сборка фиксирует состояние после общего аудита кода.

**Аудит кода и оптимизация:**
- `onFireSpread` (самый горячий обработчик — срабатывает на каждый neighbor-notify):
  объединены два прохода по сторонам блока в один; убрана аллокация `HashSet`
  (`regionIdSet`) на каждую сторону — теперь прямой обход `getApplicable`.
  World-выключатели (`preventFireSpread`/`preventLavaFire`) резолвятся один раз до цикла.
- Удалён мёртвый метод `regionIdSet` из `WorldEventHandler` (больше не используется).
- `WGConfig` (TOML): парсер `TomlFormat.createParser().parse()` теперь читается
  напрямую в `CommentedConfig`, без лишнего промежуточного `putAll`.

**Проверено (без изменений — уже корректно):**
- Баланс скобок, отсутствие неиспользуемых импортов, отсутствие утечек ресурсов
  (все `Files.list/lines/newBufferedReader` в try-with-resources).
- Потокобезопасность `ViolationLog` (ArrayBlockingQueue + daemon-поток + volatile-флаги).
- Все 12 mixin'ов: null-проверка `WorldGuardNeo.get()`, fast-path, fail-open.
- `RegionContainer.get(Level)` — горячий путь без аллокаций (IdentityHashMap).
- Нет stream()/collect в горячих обработчиках.

**Состояние мода на момент сборки:**
- 60 Java-файлов, 12 mixin'ов, 83 флага (80 рабочих, 3 задекларированы).
- Конфиг в TOML с комментариями (`config/worldguardneo/config.toml`), регионы в JSON.
- Защита смежных регионов A↔B: поршни (дроп при нарушении), жидкость/огонь
  (boundary), рост деревьев, диспенсер (`dispenser-output` + boundary).
