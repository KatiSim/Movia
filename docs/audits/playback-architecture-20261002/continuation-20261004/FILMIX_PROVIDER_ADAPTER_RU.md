# Movia — первый LazyMedia-style provider adapter: Filmix

Дата: 4 октября 2026.

## Выбор провайдера

Первым clean-room adapter выбран **Filmix**.

Причины:
- Filmix входит в 12 active providers, подтверждённых runtime-пробами LazyMedia Deluxe 3.466;
- `FILMIX_Article` декомпилирован без явного high-level bad-method marker;
- `FILMIX_ListArticles` также декомпилирован без такого marker;
- уже существуют проверенные fixture-контракты Filmix для request/response и exact episode.

HDRezka сознательно не выбран первым: он важнее по охвату, но его `parseContent()` в JADX частично повреждён.

## Что подтверждено из LazyMedia

`FILMIX_Article`:
- использует custom parse;
- Filmix content строится через общий `obf.qi`.

В `qi.OooOOO()` подтверждена иерархия:
- provider translation;
- season folder;
- episode folder;
- затем concrete file/quality leaves.

`qi.OooOOOO()` создаёт конкретные media leaves с URL и quality.

## Реализация

Добавлен:
- `backend/runtime/filmix_provider_adapter.py`;
- `backend/tests/test_filmix_provider_adapter.py`.

Новый путь:

```text
Filmix source ref
  -> ProviderArticle
  -> VariantTree
       voice
       -> season
       -> episode
       -> concrete quality/file
  -> flatten_variant_tree()
  -> StreamCandidate-compatible rows
```

Provider identity:
- `provider_id = lazy:filmix`;
- `source_type_id = 3`.

Для series season/episode помещаются в VariantTree **до flatten**, поэтому неправильный episode request даёт пустой результат и не может получить sibling/movie fallback.

Filmix trailers не попадают в дерево, поскольку используется только подтверждённый `translations.video` map.

Opaque packed values не превращаются в выдуманные URL:
- результат: `filmix:OPAQUE_LINK_DECODER_REQUIRED`.

Сохранён mirror fallback:
- `https://filmix.ac`;
- затем `http://filmixapp.cyou`.

Playback headers принадлежат concrete stream leaf:
- User-Agent;
- Referer;
- Origin.

Discovery-only headers, например `X-Requested-With`, в playback profile не переносятся.

## Differential fixture

Для movie fixture новый VariantTree после flatten сравнен с существующим проверенным Filmix resolver.

Совпадают фактические измерения:
- voice;
- quality;
- URL.

Fixture даёт:
- 3 concrete streams;
- voices: Дубляж / Original;
- qualities: 1080p / 720p / 480p.

Это fixture-сравнение, не новый live-movie test.

## Red → Green

До реализации:
- `ModuleNotFoundError: filmix_provider_adapter`.

После реализации:
- Filmix + generic provider contract: **15/15 PASS**;
- полный isolated backend: **251/251 PASS**;
- `py_compile`: PASS;
- `git diff --check`: PASS.

Остаются прежние `sqlite3 ResourceWarning`; failures отсутствуют.

## Важное ограничение

Filmix orchestration и VariantTree теперь собственные, но новый adapter пока **переиспользует уже проверенные Filmix request/response parsing primitives из `zona_legacy_adapters.py`**.

Поэтому это первый перенос архитектуры, но ещё не полная независимость Filmix provider от legacy module.

Следующая фаза для Filmix:
1. вынести эти Filmix primitives в собственный provider module;
2. добавить search → ProviderSearchResult;
3. differential fixtures против pinned LazyMedia engine;
4. после этого подключать adapter в discovery queue.

## Production/runtime

Active parser не менялся:
- PID 23603;
- `streamer.py --check` → RUNNING.

Android UI, APK и системные настройки не использовались.

Пользовательский Android worktree не записывался:
- HEAD `c5d1d3c882e07238c6f2a8fd308e8cec32f85936`;
- index SHA-256 `0a6e8e0d5143d26ed257c1686d849ab9b4d92eb244fb4f1b8cbd843c0771ac7a`;
- status SHA-256 `065a8153e9a074e097e7d0465c68a6da153d33bef67e7a9c4ee648790729e48f`, 114 строк.

## Не завершено

- Search adapter Filmix ещё не перенесён.
- Filmix пока зависит от legacy parsing primitives.
- Active parser не использует новый Filmix VariantTree adapter.
- Новых real-movie tests в этом блоке нет.
- 500 native voice × quality matrices не завершены.
