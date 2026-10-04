# Movia — 1000-content variant coverage audit + plan

Дата: 4 октября 2026.

## Короткий вывод

Проблема воспроизведена массово.

На детерминированной случайной выборке **1000 карточек из 96 980** текущего каталога:

- **671/1000 = 67,1%** вообще не имеют валидных stream records;
- **876/1000 = 87,6%** не имеют двух озвучек;
- **787/1000 = 78,7%** не имеют двух качеств;
- только **119/1000 = 11,9%** имеют одновременно ≥2 voice и ≥2 quality;
- следовательно **881/1000 = 88,1%** не дают нормального выбора хотя бы по одной из двух осей.

Это практически совпадает с наблюдением «примерно 90% контента».

## Почему предыдущие проверки выглядели хорошо

Контрольный срез top-1000 по популярности:

- streams: 960/1000;
- ≥2 voices: 903/1000;
- ≥2 qualities: 943/1000;
- обе оси: 899/1000.

То есть проблема сильно сконцентрирована в long-tail. Проверять только популярные фильмы нельзя.

## Movies vs TV

### Movies

603 случайных фильма:

- 327 без streams;
- 114 имеют ≥2 voice и ≥2 quality;
- **489/603 = 81,1%** не имеют нормального выбора по одной или обеим осям.

### TV

397 сериалов/TV:

- 344 без streams;
- только 5 имеют ≥2 voice и ≥2 quality;
- **392/397 = 98,7%** не имеют нормального выбора;
- из 53 TV-карточек со streams:
  - 45 имеют streams без season+episode identity;
  - только 8 имеют хотя бы один episode-tagged stream.

Для сериалов это отдельный критический дефект архитектуры хранения.

## Transport/provider composition

Из 329 карточек, где stream вообще есть:

- 317 имеют P2P;
- **316/329 = 96,0% — P2P-only**;
- только 13 имеют любой HTTP/direct stream;
- это всего **1,3% от всей random-1000 выборки**.

Самые частые composition:
- Rutor only: 102;
- Rutor + YTS: 86;
- YTS only: 80;
- Apibay only: 14;
- torrent_fallback only: 14;
- Collaps only: 7.

Следовательно большая часть long-tail вообще не проходит через богатую provider hierarchy с озвучками/качествами.

## Provider asymmetry

На хорошо наполненном top-1000:

- Rutor: 866 cards; обе оси ≥2 у 746;
- YTS: 738 cards; quality ≥2 у 707, но voice ≥2 у **0**;
- Apibay: 246; обе оси у 122;
- Collaps: 52; voice ≥2 у 52, quality ≥2 у **0**;
- torrent_fallback: 49; обе оси у 47.

То есть часть provider-источников по природе даёт только одну ось. Нельзя ожидать полноценную matrix, если discovery ограничился YTS/Collaps.

## Главная архитектурная причина

Current background path:

```text
content_filler
  -> resolve_balancer
       -> Collaps
       -> Zona
  -> optional torrent fallback
  -> flat streams[]
```

**LazyMedia 3.466 provider registry / Article / k30-h30-j30 VariantTree в этот pipeline не входит.**

Получается два мира:

1. Android-side LazyMedia engine умеет provider discovery и hierarchical variants.
2. Catalog/background enrichment заполняет базу другим, гораздо беднее устроенным pipeline.

Именно поэтому то, что уже работает в LazyMedia, не превращается автоматически в богатые voice/quality options по всему Movia.

## Background filler фактически не догоняет каталог

State:

- pass_number: 13;
- processed_total: 483 451;
- success_total: 33 057;
- resolver_hit_total: 57 878;
- no_source_total: 77 784;
- provider_error_total: 4 327 031.

Status totals:

- provider_error: **347 994**;
- no_source: 50 981;
- persisted: **17 903**;
- rejected_by_identity: 1 927;
- persistence_error: 9.

14 191 записей находятся в retry table; 13 121 уже due.

Свежие логи многократно показывают:

- `metered_default`;
- `quota_probe_failed:RuntimeError`.

То есть worker запускается, видит десятки тысяч unresolved rows и часто прекращает проход почти сразу.

## Android UI — не первичная причина

Android строит меню из `PlaybackSession.candidates / streamOptions`.

`voiceMenu()` и `qualityMenu()` не могут восстановить variants, которых upstream вообще не передал.

Есть UX/compatibility проблемы, которые надо исправить позже, но «добавить кнопки» не решит 88,1% failure rate.

## Отдельная проблема сериалов

`catalogPlaybackStreams()` требует:

```text
stream.seasonNumber == requested season
stream.episodeNumber == requested episode
```

Это правильный identity guard.

Но 45 из 53 sampled TV cards со streams содержат card-level streams без exact episode identity. Такие записи не должны считаться episode inventory и почти бесполезны для реального playback.

LazyMedia как раз решает это структурно:

```text
Voice
  -> Season
      -> Episode
          -> Quality/File
```

Это и надо перенести в единый backend contract.

# План исправления

## P0 — один provider pipeline

Сделать созданный нами:

```text
ProviderDefinition
ProviderSearchResult
ProviderArticle
VariantTree
DeferredVariantLoader
VariantStream
```

единственным discovery contract.

Не должно быть отдельной архитектуры:
- catalog filler;
- Android Lazy engine;
- Zona;
- torrents.

Все они должны выдавать один `StreamCandidate` contract через общий VariantTree boundary.

## P0 — перенести активные LazyMedia providers на backend

Filmix уже первый самостоятельный adapter.

Дальше переносить реальные active providers 3.466 по качеству декомпиляции и value:
- Filmix;
- Zona.mobi where applicable;
- Zombie;
- Octopus;
- Zetflix;
- Seasonvar;
- AniLibria v1/v3;
- KinoDB;
- HDRezka после восстановления damaged parser portions;
- Eneyida после damaged portions.

Zona adapters остаются второй extractor family.

Цель — не копировать UI/player LazyMedia, а перенести её сильную provider architecture:
`registry → search → exact article → hierarchy → deferred branch → concrete files`.

## P0 — user-triggered discovery не должен зависеть от bulk network policy

`metered_default` / quota guard может останавливать только background prefetch.

Нажатие пользователем `Смотреть`:
- запускает bounded provider discovery;
- получает incremental results;
- кэширует их;
- не зависит от того, разрешён ли массовый filler.

## P0 — series cache только exact episode

Ключ:

```text
movie: contentId
series: contentId + season + episode
```

Никаких untagged card-level streams как источника эпизода.

## P1 — не пытаться заранее наполнить все 96k

Для каталога такого размера full-prefill будет постоянно отставать.

Нужны:
1. on-demand discovery;
2. persistent cache;
3. background prioritization:
   recently opened → popular → recently added → remaining long-tail.

То есть первый пользователь может инициировать discovery, следующие получают cache.

## P1 — compatibility graph

Вместо независимых списков Source / Voice / Quality построить graph реально существующих комбинаций:

```text
source
  -> voice
      -> quality
          -> exact StreamCandidate
```

или для adaptive stream:

```text
logical source + voice
  -> Media3 video track qualities
```

UI никогда не должен предлагать combination, которой нет.

## P1 — provider truth

- YTS Original-only не превращать в fake Russian voices.
- Collaps adaptive `Auto` не превращать в фиктивные static qualities до Media3 probe.
- Media3 internal qualities можно объединять только с voice того же logical stream.
- разные provider URLs нельзя cross-product'ить между собой.

## P1 — 1000-title regression gate

Зафиксировать deterministic cohorts:
- random 1000;
- popular 1000;
- movies;
- series exact episodes;
- recent titles;
- long-tail.

После каждого provider migration считать:
- any playable;
- direct provider;
- voices ≥2;
- qualities ≥2;
- valid source×voice×quality pairs;
- episode identity;
- errors/timeouts;
- invalid combinations.

## P2 — Differential testing с LazyMedia 3.466

Для каждого provider:

```text
same title/year/episode
LazyMedia pinned 3.466 tree
vs
Movia clean-room ProviderArticle/VariantTree
```

Сравнивать:
- article identity;
- voices;
- seasons;
- episodes;
- qualities;
- URL/file leaves;
- headers;
- subtitles.

После покрытия provider можно удалять Dex fallback для него.

# Acceptance

Предлагаю не использовать сырую цифру «100% каталога», потому что в 96 980 есть future/unreleased/obscure metadata, где поток объективно может отсутствовать.

Для released/eligible cohort:

- any playable target: ≥80%;
- exact episode identity для всех exposed series candidates: 100%;
- invalid offered combinations: 0;
- cross-title / cross-episode contamination: 0;
- user-triggered discovery blocked by metered policy: 0;
- для provider, который реально возвращает несколько variants, UI должен сохранить все variants, не только top-1.

## Что этот audit НЕ утверждает

Это 1000-card emulation текущего catalog/pipeline, а не 1000 live provider network calls.

Он достаточно точно показывает **где** исчезают choices:
главная потеря происходит **до UI**, в discovery/enrichment architecture.
