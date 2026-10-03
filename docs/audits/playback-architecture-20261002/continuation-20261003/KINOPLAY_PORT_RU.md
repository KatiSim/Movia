# Zona type 26 / Kinoplay — порт контракта, 3 октября 2026

## Результат

В изолированной ветке портирован extractor **26 `kinoplay`** из reference Zona 3.0.68. Число реализованных Zona extractor-контрактов в реестре увеличилось:

**18 → 19 из 51 зарегистрированного extractor ID.**

Это изменение пока **не активировано** в локальном production parser и **не считается live-проверенным провайдером**.

## Что восстановлено из reference

Использованы декомпилированные классы `C13484b`, `C13483a`, `C3916b0`, `C3176a`, `C2348c`, `C12400e`, `C3894G`.

Подтверждён контракт:

- fallback host: `https://21hd.freekinoplay4.online`;
- player path: `/iplayer/videodb.php?kp=<source-key>`;
- dynamic UA config: `/static/ext26.txt`, поле `u`;
- config mirrors: `vsr01.zonasearch.com` и `vsw01.zonasearch.com`;
- если config недоступен, reference использует встроенный fallback UA;
- первый GET получает runtime token из `String["fromCharCode"](...)`;
- `Set-Cookie` первого GET переносится в `Cookie` на подписанный POST и player GET, cookie со значением `deleted` исключается;
- подпись: SHA-1 от hex-строки MD5 для `token;host;userAgent`;
- signed POST отправляет form field `hash` и runtime `Authorization: Bearer …`;
- secrets/credentials из APK в Movia не копировались.

Парсер player payload:

- фильм: voice из `title`;
- сериал: точный путь `"<season> сезон" → folder → "<episode> серия" → folder`;
- voice сериала: `comment`;
- variants: `file` в формате `[quality]URL`;
- отсутствие точной серии возвращает ошибку, соседняя серия не подставляется;
- direct-file fallback reference соответствует language `ru`, quality `480p`.

## Изменения кода

- `backend/runtime/zona_legacy_adapters.py`: extractor 26, handshake, cookies, UA config, movie/series parser.
- `backend/runtime/zona_contract.py`: отдельный fetcher, который возвращает body + повторяющиеся response headers. Старый `fetch_text(body,error)` оставлен совместимым.
- `backend/tests/test_zona_kinoplay_adapter.py`: synthetic fixtures без реальных tokens/cookies/media URLs.

## Red → Green

До реализации новый тест падал ожидаемо на импорте отсутствующего `KINOPLAY_BASE_URL`.

После реализации:

- Kinoplay focused tests: **7/7 PASS**;
- полный isolated backend: **236/236 PASS**;
- `py_compile`: PASS;
- `git diff --check`: PASS.

В полном suite остаются прежние `sqlite3 ResourceWarning`; failures отсутствуют.

## Ограниченный live probe

Проверена одна точная карточка «Интерстеллар» (2014) без вывода provider ID, source key, cookies, token или stream URL.

- exact suggestions: **1**, suggestion errors: **0**;
- запрос только source type 26: **0 refs**, **5 source errors**;
- стандартный source envelope: **0 rows**, **5 source errors**;
- локальная `catalog.db` и JSON cache: raw type 26 refs не найдены.

Следствие: живой Kinoplay handshake в этом checkpoint **не запускался**, потому что Zona `getVideoSources` не выдал raw source ref. Это не считается live-успехом провайдера.

## Сохранность

Пользовательский Android worktree не менялся:

- HEAD: `c5d1d3c882e07238c6f2a8fd308e8cec32f85936`;
- Git index SHA-256: `0a6e8e0d5143d26ed257c1686d849ab9b4d92eb244fb4f1b8cbd843c0771ac7a`;
- hash `git status --porcelain`: `6d3b0fcfe7e556ccb5cf0f07c927363eedf8df987f1a1c75cd73491fb0fdac1f`.

APK не устанавливался, UI телефона и системные настройки не использовались.

## Не завершено

- type 26 ещё не синхронизирован в active parser;
- реальный Kinoplay source/stream не декодирован в этом checkpoint;
- полный перенос остальных Zona/LazyMedia провайдеров не завершён;
- 500 полных native voice × quality матриц не выполнены;
- внешний hosting/HTTPS/PostgreSQL не развёрнут.
