# LazyMedia Deluxe 3.466 — random-200 provider architecture benchmark

Дата: 4 октября 2026.

## Что именно проверено

Проверка выполнялась через pinned LazyMedia Deluxe **3.466 provider engine** с SHA-256:

`637705386744f29445223fded2ad2df6aeb72295cfc9ce327609e7f0c7a72a00`

Engine загружался через тот же `DexClassLoader`/provider registry, который был перенесён в Movia compatibility layer.

Не запускались:
- Activity LazyMedia;
- её старый player;
- WorkManager/background workers;
- UI LazyMedia.

Запускался непосредственно её provider pipeline:

```text
Services registry
 -> active provider
 -> ListArticles search
 -> exact title/year filter
 -> Article
 -> parseBase / parseContent
 -> k30 folder tree
 -> h30 deferred expansion
 -> j30 concrete file leaves
```

Каждый lazy folder раскрывался программно. Это эквивалентно переходу по вложенным provider/voice/season/episode/quality веткам, но без сотен медленных UI taps.

В audit не сохранялись provider URLs, cookies, headers, tokens или signed media locators.

## Выборка

Основная выборка:
- **200 случайных movie-карточек** из реального `catalog.db`;
- deterministic seed: **20261004**;
- годы: 1920–2026;
- итоговый harness result: **200/200 OK** после повторного запуска transient stdout-loss cases.

Дополнительно:
- **9 collision/franchise cases**:
  - Человек-паук 2002;
  - Новый Человек-паук 2012;
  - Новый Человек-паук: Высокое напряжение 2014;
  - Человек-паук: Возвращение домой 2017;
  - Человек-паук: Нет пути домой 2021;
  - Дюна 1984;
  - Дюна 2021;
  - Бэтмен 1989;
  - Бэтмен 2022.

Контроль timeout:
- основной benchmark: **6500 ms** engine budget;
- deep-control: **20 titles × 20000 ms**.

## Random-200 — результат

### Coverage

Из 200 фильмов:

- concrete candidate найден: **77/200 = 38,5%**;
- candidate не найден: **123/200 = 61,5%**;
- ≥2 providers: **27/200 = 13,5%**;
- первый candidate ≤5 s: **70/200 = 35,0%**.

Среди 77 playable structural results:
- median first-candidate: **2220 ms**;
- p90: **4784 ms**;
- min: **1580 ms**;
- max: **6274 ms**.

### Concrete leaves

Всего engine раскрыл:

- **191 concrete leaves**;
- video: **121**;
- torrent: **70**.

Распределение candidate-count по фильмам:

- 0 leaves: **123**;
- 1 leaf: **10**;
- 2 leaves: **36**;
- 3 leaves: **19**;
- 4 leaves: **8**;
- 5 leaves: **4**.

### Providers, которые реально дали leaves

Только две provider-family дали concrete leaves в random-200:

- **Zona.mobi**: resolved у **61** фильмов, **121 leaves**;
- **Filmix**: resolved у **43** фильмов, **70 leaves**.

Пересечение:
- Filmix-only: **16**;
- Zona-only: **34**;
- Filmix + Zona: **27**;
- всего resolved: **77**.

## Озвучки

На random-200 engine не извлёк ни одного нормализованного конкретного voice/studio label:

- voice ≥1: **0/200**;
- voice ≥2: **0/200**;
- voice ≥3: **0/200**.

Все работающие Filmix/Zona leaves фактически дошли как:
- `Не указано`, либо
- без отдельной voice-ветки.

Это важный результат: текущая 2026 provider-реальность уже не соответствует предположению «LazyMedia всегда даёт богатый список озвучек».

Архитектура voice-tree в LazyMedia существует, но работающие в этой выборке providers сейчас её не наполняют конкретными studio labels.

## Качества

Распознанные concrete quality labels:

- ≥1 quality: **22/200 = 11,0%**;
- ≥2 qualities: **10/200 = 5,0%**;
- ≥3 qualities: **0/200**;
- voice≥3 + quality≥3: **0/200**.

Из 191 concrete leaves:
- `Не указано`: **159**;
- `1080p`: **17**;
- `720p`: **15**.

Основные labels Filmix:
- `BDRip`;
- `BDRip 720p`;
- `BDRip 1080p`;
- `WEB-DL 720p`;
- `WEB-DL 1080p`;
- вариации с кириллической `р`.

Zona в основном возвращила:
- `MP4 • HQ`;
- `MP4 • LQ`.

То есть Zona имеет фактическое различие HQ/LQ, но текущий Legacy quality normalizer не переводит эти labels в 720p/1080p.

## Provider status matrix — 200/200

### Filmix

- RESOLVED: **43**
- IOException: **116**
- NO_EXACT_MATCH: **24**
- NO_PLAYABLE_EPISODE: **17**

### Zona.mobi

- RESOLVED: **61**
- NO_EXACT_MATCH: **109**
- TIMEOUT: **28**
- NO_PLAYABLE_EPISODE: **2**

### HDRezka

- NO_EXACT_MATCH: **98**
- NO_PLAYABLE_EPISODE: **96**
- TIMEOUT: **6**
- RESOLVED: **0**

Search/article identity у HDRezka часто находится, но concrete playable leaf в этом benchmark не получен.

### Octopus

- IOException: **200/200**

### AniLibria v1

- IOException: **103**
- TIMEOUT: **97**

### AniLibria v3

- IOException: **101**
- TIMEOUT: **99**

### Zombie

- TIMEOUT: **200/200**

### Seasonvar

- TIMEOUT: **200/200**

### Zetflix

- TIMEOUT: **200/200**

### KinoDB

- IOException: **47**
- TIMEOUT: **110**
- NO_EXACT_MATCH: **24**
- NO_PLAYABLE_EPISODE: **19**
- RESOLVED: **0**

### Encyclopedia

- IOException: **30**
- TIMEOUT: **77**
- NO_EXACT_MATCH: **82**
- NO_PLAYABLE_EPISODE: **11**
- RESOLVED: **0**

### Eneyida

- NO_EXACT_MATCH: **197**
- IOException: **1**
- TIMEOUT: **2**

## Deep timeout control: 6.5 s → 20 s

20 фильмов были повторены с engine budget **20 s**.

Результат:
- новый candidate появился только у **1/20**;
- новый provider появился у **1/20**;
- новых voice labels: **0**;
- новых quality labels: **0**.

Единственный заметный случай:

`Поиск (2012)`

- 6.5 s: 0 candidates;
- 20 s: 4 Zona candidates;
- first candidate: **7686 ms**;
- voice labels: 0;
- quality labels: 0.

Вывод: короткий READY budget действительно теряет небольшой хвост медленных Zona результатов, но **не является основной причиной отсутствия озвучек/качеств**.

## Collision / franchise test

Все 9 cases завершились `probeStatus=OK`.

### Человек-паук

**Человек-паук (2002)**
- 1 candidate;
- Zona;
- first: 4123 ms.

**Новый Человек-паук (2012)**
- 4 candidates;
- Filmix + Zona;
- 720p, 1080p;
- first: 1779 ms.

**Новый Человек-паук: Высокое напряжение (2014)**
- 5 candidates;
- Filmix + Zona;
- 720p, 1080p;
- first: 1780 ms.

**Человек-паук: Возвращение домой (2017)**
- 6 candidates;
- Filmix;
- 720p, 1080p;
- first: 1846 ms.

**Человек-паук: Нет пути домой (2021)**
- 2 candidates;
- Filmix;
- 1080p;
- first: 1770 ms.

Exact title/year filtering не склеил эти фильмы между собой.

### Дюна

**Дюна (1984)**
- 0 candidates.

**Дюна (2021)**
- 2 Zona candidates;
- first: 5030 ms.

Карточки не смешаны.

### Бэтмен

**Бэтмен (1989)**
- 0 candidates.

**Бэтмен (2022)**
- 2 Zona candidates;
- first: 4133 ms.

Карточки не смешаны.

## Как фактически работает LazyMedia architecture

Сильная часть LazyMedia — не конкретные сегодняшние endpoints, а структура.

### Registry

`Services` содержит provider descriptors и конфигурацию parser-class для каждого source.

В pinned runtime:
- registry enum: 34;
- active providers: 12.

### Search

Каждый provider получает собственный `ListArticles` parser и query template.

Search возвращает provider-native Article references.

### Identity

Перед article parse выполняется:
- exact normalized title match;
- optional year match;
- optional season match.

Это критично для франшиз и одноимённых фильмов.

### Article

Article parser:
- загружает provider page/API;
- `parseBase` подтверждает metadata;
- `parseContent` строит content hierarchy.

### Variant tree

Главные типы:

- `k30` — folder/branch;
- `h30` — deferred branch loader;
- `j30` — concrete playable/download leaf.

Иерархия способна представлять:

```text
Voice
 -> Season
    -> Episode
       -> Quality/File
```

но порядок зависит от provider.

### Lazy expansion

Если `k30` содержит `h30`, branch раскрывается только когда она нужна.

Это особенно важно для сериалов: не требуется заранее загружать все сезоны/серии.

### Concrete leaf

`j30` хранит:
- locator;
- format;
- quality;
- request headers;
- subtitles;
- provider context.

Именно этот слой должен стать Movia `StreamCandidate`.

## Главный вывод

**Архитектура LazyMedia правильная и её стоит переносить.**

Но утверждение «LazyMedia 3.466 сегодня уже сама даёт всем фильмам ≥3 озвучки × ≥3 качества» данными не подтверждается.

На случайных 200 реальных фильмах текущая сеть/provider drift дала:

- playable structural result: **38,5%**;
- concrete voice labels: **0%**;
- ≥2 normalized qualities: **5%**;
- ≥3 qualities: **0%**.

То есть копирование APK/parser-классов без обновления provider transports не решит Movia автоматически.

## Что переносить в Movia напрямую

Приоритет P0:

1. `Services / gs0 / vq0` registry model.
2. Provider-specific `ListArticles` + `Article` split.
3. Exact title/year identity guard.
4. `k30 / h30 / j30` hierarchical VariantTree.
5. Deferred branch expansion.
6. Provider-specific request headers/subtitles.
7. Per-provider error isolation.
8. Bounded parallel search.
9. Additive union нескольких providers.
10. Exact series season/episode branch.

## Что не копировать как production solution

Не стоит делать постоянной зависимостью:

- LazyMedia Activities/UI;
- старый player;
- AsyncTask/WorkManager architecture;
- global BaseApplication state;
- DexClassLoader как финальный runtime;
- старые dead endpoints без проверки;
- static provider tokens/hash/key values;
- provider-specific TLS bypasses.

## Самые быстрые следующие исправления Movia

### 1. Filmix

Filmix сейчас дал 43/200 successful title resolutions и реальные 720p/1080p torrent leaves.

Приоритет:
- восстановить current search transport;
- сохранить его VariantTree;
- улучшить voice extraction из translation metadata.

### 2. Zona

Zona дала 61/200 — максимальное coverage.

Приоритет:
- перенести HQ/LQ mapping в actual quality/track probe;
- current signed stream refresh;
- сохранить exact title/year identity.

### 3. HDRezka

96/200 дошли до exact item, но остановились на `NO_PLAYABLE_EPISODE`.

Это очень высокий-value target:
- search работает заметно лучше concrete extraction;
- нужно чинить current article/player decoder;
- именно Rezka потенциально может дать voice hierarchy.

### 4. Остальные

Octopus, AniLibria, Zombie, Seasonvar, Zetflix, KinoDB требуют current transport/parser repair до включения.

## Ограничение benchmark

Этот тест полностью проверил:
- provider search;
- identity;
- article parse;
- folder/deferred expansion;
- concrete leaf extraction;
- structural voices/qualities/providers.

Но он **не проиграл каждый из 191 concrete leaves через Media3**.

Следующий отдельный acceptance-layer:
- открыть representative concrete leaves;
- проверить HTTP/HLS/DASH;
- Media3 READY;
- first decoded frame;
- actual selected audio/video track;
- position-preserving switch.

Это нужно считать отдельным native playback benchmark, а не смешивать с provider architecture benchmark.
