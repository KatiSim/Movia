# Movia — выбор архитектурного источника: LazyMedia Deluxe vs Zona

Дата проверки: 2026-10-04.

## Вывод

Не переносить архитектуру целиком ни из LazyMedia Deluxe, ни из Zona.

Целевая архитектура Movia должна быть гибридной:

1. **Provider discovery / provider registry / search / article parsing / hierarchical variant tree** — переносить по архитектурной модели **LazyMedia Deluxe 3.466**.
2. **Playback identity / logical source vs concrete stream / ranking / failure memory / reload / DataSource retry / exact voice-quality switching / episode cancellation** — сохранять и доводить по архитектурной модели **Zona 3.0.68**.
3. Старый LazyMedia APK/Dex использовать только как pinned compatibility oracle и переходный fallback, а не как постоянную runtime-зависимость Movia.

## Что подтверждено в LazyMedia Deluxe 3.466

### Registry

- enum provider IDs: **34**.
- ранее runtime-пробой подтверждено **12 active providers**.
- `Services` хранит централизованный registry `gs0[]`.
- `gs0` описывает provider/service capabilities.
- `vq0` хранит классы list/article parser и provider-specific templates/settings.

### Parser layer

В декомпилированной копии:
- `*_Article*.java`: **38** классов, из них только **4** содержат явный high-level JADX bad/decompile marker;
- `*_ListArticles*.java`: **39** классов, явных bad-method markers в этой проверке **0**.

Базовый `OooO00o` задаёт общий Article contract:
- provider identity;
- base URL;
- per-provider cookies and headers;
- `parseBase()`;
- `parseContent()`;
- `parseSimilar()`;
- `parseTorrent()`;
- task tracking/cancellation;
- provider HTTP GET/POST.

### Hierarchical stream model

- `k30` — folder/tree node.
- `j30` — concrete media/file leaf.
- `h30` — deferred/lazy parser for a folder.
- Lazy folder expands only when requested.
- HDRezka uses this explicitly for translation → season → episode → concrete quality/file branches.
- Folder labels carry translation/season/episode context; file leaves carry concrete URL/format/quality and playback options.

Это существенно лучше подходит для переноса множества heterogeneous provider parsers, чем плоский набор site-specific функций.

## Что подтверждено в Zona 3.0.68

Zona сильнее не provider-parser слоем, а playback orchestration.

- **51 source-type registrations / 50 extractor classes**.
- **27** registry entries point to classes with at least one high-level JADX bad-method warning.
- Logical `VideoSource` отделён от concrete `StreamInfo`.
- `StreamInfo` имеет **22 поля**, включая translation, language, quality, resolution, UA, headers, subtitles, audio/video track index, download URL/headers, reloadData, duration/size.
- provider branches выполняются независимо;
- provider failure изолирован;
- есть provider-aware dedupe;
- BEST/BETTER ranking;
- explicit voice/quality preference;
- problem-stream memory;
- same-logical-stream reload before fallback;
- DataSource-level URI transform + one retry;
- exact episode identity + cancellation of stale callbacks;
- voice/quality switching selects a concrete stream wrapper, а не только меняет UI state.

## Что уже есть в Movia

Текущий Movia уже в значительной степени использует правильные части обеих моделей.

### Из Zona-подобной модели

`StreamCandidate` уже содержит:
- stableStreamId / logicalSourceId / providerItemId;
- source/content type;
- voice / language / quality / resolution;
- headers / UA;
- subtitles;
- audio/video track index;
- reloadData / reloadSupported;
- download URL/headers;
- provider/source identity;
- canonical movie/episode identity.

`DomainPlaybackResolver` уже реализует identity filtering, discovered-vs-cached replacement и same-logical-stream reload.

### Из LazyMedia

`LegacyProviderEngine` уже:
- загружает pinned 3.466 engine;
- получает registry через `Services`;
- ищет по active providers;
- создаёт Article parser через registry;
- вызывает `parseBase/parseContent`;
- обходит `k30/j30`;
- вызывает `h30` lazy loader;
- ограничивает обход точной веткой season/episode;
- публикует кандидатов по мере завершения providers.

Проблема: сейчас этот слой всё ещё завязан на reflection/Dex и рано flatten'ит оригинальное дерево в JSON rows.

## Целевая схема

```text
CanonicalPlaybackRequest
        |
        v
ProviderRegistry                 <- LazyMedia architecture
        |
        +--> ProviderSearchAdapter
        +--> ProviderArticleAdapter
        |
        v
ProviderItem
        |
        v
VariantTree                      <- LazyMedia k30/h30/j30 concept
  Translation
    -> Season
      -> Episode
        -> Quality/File
        |
        v
LogicalSourceDescriptor          <- Movia clean-room type
        |
        v
Concrete StreamCandidate[]       <- Zona StreamInfo semantics
        |
        v
identity guard
provider-aware dedupe
ranking / requested voice+quality
problem memory
reload same logical stream
fallback
        |
        v
Media3 DataSource + exact headers/tracks
```

## Что НЕ переносить

Из LazyMedia:
- UI/Activities;
- player implementation;
- AsyncTask orchestration как целевую concurrency model;
- глобальный BaseApplication state;
- Dex/reflection как permanent production architecture.

Из Zona:
- зависимость Movia от Zona `/getVideoSources` как фундаментальной server-side discovery системы;
- обфусцированные классы/реализацию verbatim;
- extractor-specific алгоритмы без доказательств.

## Практический приоритет переноса

### Этап 1 — вынести clean-room provider contracts из LazyMedia

Создать собственные типы:
- `ProviderDefinition`;
- `ProviderSearchResult`;
- `ProviderArticle`;
- `VariantNode.Folder`;
- `VariantNode.Stream`;
- `DeferredVariantLoader`;
- `ProviderRequestProfile`.

Они должны жить в backend/provider layer и не зависеть от Android/Dex.

### Этап 2 — переносить active LazyMedia providers

Начинать с реально активных и полезных providers из 12 active registry entries, а не с всех 34 подряд.

Для каждого:
search → exact card identity → article → exact episode branch → translation → quality → concrete stream.

### Этап 3 — использовать Zona как дополнительный extractor pool

Zona type adapters остаются вторым семейством adapters и полезны там, где:
- LazyMedia provider отсутствует;
- Zona имеет более прямой source contract;
- существует подтверждённый raw source ref.

Оба семейства должны выдавать один и тот же Movia `StreamCandidate`.

### Этап 4 — убрать Dex dependency

После того как собственные adapters покрывают provider и проходят differential fixtures против pinned LazyMedia engine, Dex engine удаляется из runtime path и остаётся только regression reference.

## Решение

**Для дальнейшего переноса архитектуры providers основным источником считать LazyMedia Deluxe 3.466. Для playback architecture основным источником оставить Zona 3.0.68.**

Следующий кодовый блок должен быть не ещё один случайный Zona extractor, а clean-room `ProviderDefinition + VariantTree` contract и adapter boundary, совместимый одновременно с LazyMedia-derived providers и Zona-derived extractors.
