# Movia — live provider survey + Octopus clean discovery adapter

Дата: 4 октября 2026.

## Что сделано за блок

Проверены следующие активные/ценные LazyMedia 3.466 providers не по предположению, а по их точным декомпилированным search/article contracts:

- AniLibria v3;
- Zombie;
- Octopus;
- Seasonvar.

### AniLibria v3

Точный search template из `Services.java` восстановлен:

`/title/search?search=[S]&filter=id,names,posters,type,description&limit=25`

Точный article endpoint:

`/title?id={s}`

CURRENT:
- API base отвечает;
- exact search → **HTTP 410**;
- exact article → **HTTP 410**.

Вывод: контракт 3.466 устарел, live adapter не включать.

### Zombie

Точный search template восстановлен из `Services.java`.

CURRENT:
- API host timeout;
- основной Zombie host TLS/timeout.

Вывод: transport сейчас не подходит для live clean adapter.

### Seasonvar

CURRENT:
- основной сайт → **HTTP 200**;
- exact LazyMedia search URL → **HTTP 200**;
- старый DOM marker `pgs-search-wrap` отсутствует.

Playback в 3.466 передаётся в общий resolver `mh1`, который строит:
- HLS;
- DASH/1080p;
- season;
- episode.

Публичный default endpoint `mh1` после безопасного декодирования структуры:

`https://api.luxembd.ws/embed/kp/{s}`

Но current probe → **HTTP 422**.

Вывод: сайт жив, однако parser/resolver contract drift требует отдельного восстановления.

## Octopus — первый реально живой discovery transport

Exact search template 3.466:

`[U]/index.php?do=search&subaction=search&from_page=0&story=[S]`

Live:
- search → **HTTP 200**;
- current redirect host → `008.ultradox.lol`;
- ожидаемый LazyMedia marker `top__slider_div__item` присутствует;
- article → **HTTP 200**;
- `full-story_iframe-block iframe` присутствует;
- iframe → **HTTP 200**.

На запрос «Интерстеллар» Octopus вернул другую карточку 2019 года. Это подтвердило, почему exact-title/year guard нельзя ослаблять: такой результат не должен загрязнить «Интерстеллар 2014».

## Реализация

Добавлен:

`backend/runtime/octopus_provider_adapter.py`

Он умеет:

```text
query
  -> exact LazyMedia search route
  -> top__slider_div__item
  -> ProviderSearchResult
  -> exact title/year guard
  -> article
  -> explicit iframe reference
```

Никакой URL по title template не генерируется.

Если title/year не совпали:
- `NO_MATCH`.

Если exact card неоднозначна:
- `AMBIGUOUS`.

## Почему playback пока fail-closed

Octopus в LazyMedia передаёт iframe в общий HDVB decoder `ns`.

При исследовании `ns` обнаружены статические legacy token/hash/key значения. Они **не перенесены** в Movia и не записаны в audit.

Текущий public iframe тоже не содержит готового m3u8/mp4 locator.

Поэтому новый Octopus adapter в этом блоке **не создаёт fake StreamCandidate**.

Live registry probe exact карточки:

- provider: Octopus;
- status: **PLAYBACK_DECODER_REQUIRED**;
- streams: **0**;
- errors: **0**;
- elapsed: **3214 ms**.

Это правильнее, чем показывать пользователю несуществующие voice/quality options.

## Исправлен defect ProviderRegistry

До этого блока Filmix gate стоял как ранний return:

```text
Filmix disabled
 -> PROVIDER_DISABLED
 -> остальные adapters никогда не исполняются
```

Это было неверно для будущего multi-provider registry.

Теперь:

```text
ProviderRegistry
  -> Filmix gate
  -> Octopus gate
  -> future provider gates
  -> aggregate terminal status / first real streams
```

Filmix и Octopus независимы.

Gates:

- `MOVIA_ENABLE_FILMIX_CLEAN_PROVIDER=1`
- `MOVIA_ENABLE_OCTOPUS_DISCOVERY_ONLY=1`

По умолчанию оба выключены.

## Tests

Focused:
- **16/16 PASS**.

Полный isolated backend:
- **264/264 PASS**.

Также:
- `py_compile`: PASS;
- `git diff --check`: PASS.

Старые sqlite ResourceWarning остаются, новых failures нет.

## Production

Active parser **не менялся**, потому что Octopus пока не выдаёт playable clean stream.

- PID: **23603**
- state: **RUNNING**
- active Zona files hashes без изменений.

Android UI/APK/system settings не трогались.

## CURRENT → TARGET → ACTUAL

Coverage baseline:
- CURRENT: **119/1000** random cards имеют ≥2 voice + ≥2 quality.
- TARGET: значительно увеличить coverage через live clean providers.
- ACTUAL этого блока: coverage не менялась, потому что ни один непроверенный provider не был включён.
- DELTA: **0** production coverage, но появился первый live clean search/article adapter и исправлен multi-provider registry.

Это сознательный fail-closed результат: не увеличивать цифру за счёт фиктивных streams.

## Следующий шаг

Нужен первый provider, который проходит весь путь до concrete media без static legacy secrets.

Для Octopus два безопасных пути:

1. восстановить current HDVB runtime bootstrap из публичного player flow, не копируя token/hash из 3.466;
2. если current transport не воспроизводим, оставить Octopus discovery-only и перенести следующий provider с открытым direct media contract.

После первого playable clean provider:
- enable adapter;
- activate registry в active parser;
- повторить deterministic random-1000 audit;
- сравнить CURRENT 119/1000 с новым ACTUAL.
