# Movia — unified provider registry wired into on-demand + filler

Дата: 4 октября 2026.

## Что реализовано

Добавлен общий backend boundary:

`backend/runtime/provider_discovery.py`

Он теперь является точкой, через которую clean-room LazyMedia-derived adapters могут одинаково использоваться:

- user-triggered `resolve_on_demand_streams()`;
- background `content_filler`.

### On-demand

Новый clean provider registry запускается параллельно с текущим balancer.

Результаты не заменяют друг друга:

```text
balancer rows
      +
clean provider rows
      +
torrent rows
      ↓
identity/sanitize/ranking/cache
```

Если clean provider закончил после READY budget, применяется существующий late-provider persistence path.

Общий direct budget не увеличен выше 4 s.

### Background filler

`content_filler._process_row()` теперь получает:

```text
clean provider outcome
        +
balancer outcome
        ↓
one validated union
        ↓
save_content()
```

Существующая additive persistence в `database.save_content()` остаётся в силе, поэтому новый provider не должен стирать ранее найденные variants.

## Filmix

Filmix был первым adapter, подключённым к registry для differential/injected testing.

Но live activation сознательно закрыта gate:

`MOVIA_ENABLE_FILMIX_CLEAN_PROVIDER=1`

По умолчанию:

`PROVIDER_DISABLED`

### Почему gate нужен

Read-only live probe:

- catalog card: Интерстеллар, ID 158, 2014;
- clean registry: `PROVIDER_ERROR`;
- elapsed: 136 ms;
- streams: 0.

Legacy search endpoint из LazyMedia 3.466:

- `http://5.61.56.18/partner_api/list...`
- HTTP **403**.

Сам Filmix жив:

- `filmix.ac` redirects to `filmix.gg`;
- final HTTP **200**.

### Что было недостающим куском 3.466

Из `FILMIX_ListArticles` подтверждено:

1. cookie name: `x424`;
2. preference key: `cookie_filmix`;
3. если guest cookie не сохранён, LazyMedia сначала открывает Filmix base URL;
4. извлекает `x424`;
5. search отправляет:
   - `Cookie: x424=<guest value>;`
   - `X-Requested-With: XMLHttpRequest`.

Текущий публичный Filmix сейчас выдаёт guest cookies:

- `FILMIXNET`;
- `minotaurs`;
- `x-a-key`.

`x424` больше не выдаётся.

Даже если передать текущую публичную guest-session на legacy partner endpoint, ответ остаётся **403**.

Cookie values не логировались и не сохранялись.

Вывод: provider architecture перенесена корректно, но конкретный Filmix 3.466 search transport устарел. Включать его в production сейчас нельзя.

## Tests

Focused integration:

**12/12 PASS**

Проверено:

- exact title/year;
- ambiguous search fail-closed;
- series fail-closed;
- production Filmix gate;
- provider registry + balancer union;
- late-provider persistence;
- background filler union.

Полный isolated backend:

**260/260 PASS**

Дополнительно:

- `py_compile`: PASS;
- `git diff --check`: PASS.

Остаются прежние sqlite ResourceWarning; новых failures нет.

## Production

Active parser не менялся.

- PID: **23603**
- `streamer.py --check`: **RUNNING**
- active Zona hashes без изменений.

Android UI/APK/system settings не трогались.

## Что изменилось архитектурно

До:

```text
Playback: balancer + torrent
Filler:   balancer -> optional torrent
Lazy:     отдельный Android/Dex world
```

После этого блока в ветке:

```text
                 ┌─ clean ProviderRegistry
Playback request ├─ balancer
                 └─ torrent
                        ↓
                   one inventory

                 ┌─ clean ProviderRegistry
Background row   └─ balancer
                        ↓
                   one inventory
```

То есть infrastructure для переноса LazyMedia providers теперь действительно включена в обе discovery дороги, а не лежит отдельным экспериментом.

## Что НЕ завершено

- Ни один clean provider пока не включён live.
- Filmix live search не работает из-за legacy 403 contract.
- Filmix series branch не активирован.
- Active parser пока не использует новый registry.
- Random-1000 coverage delta после live provider migration ещё не измерялась.

## Следующий provider

Следующий adapter надо выбирать уже по двум условиям одновременно:

1. LazyMedia Article/ListClasses восстановлены достаточно чисто.
2. Его **текущий live search/article transport** воспроизводится без private account state.

После первого такого provider:
- включить его по умолчанию;
- активировать registry в media-parser;
- повторить тот же deterministic random-1000 audit;
- сравнить CURRENT **119/1000** cards with ≥2 voice + ≥2 quality против нового ACTUAL.
