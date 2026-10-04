# Movia — Filmix: независимый clean-room adapter + search contract

Дата: 4 октября 2026.

## Результат

Filmix adapter в изолированной ветке больше не импортирует и не использует `zona_legacy_adapters.py` в runtime.

Теперь собственный модуль Filmix владеет:
- source key / info merge;
- извлечением Filmix post ID;
- проверкой mirror base URL;
- quality parsing;
- извлечением concrete direct URLs;
- разбором player payload;
- построением VariantTree;
- Filmix search request;
- разбором search result;
- преобразованием search result в `ProviderSearchResult`.

## Search contract из LazyMedia Deluxe 3.466

Чтобы не угадывать endpoint/JSON schema, безопасные публичные строки декодированы непосредственно из pinned 3.466 APK.

Цепочка декодирования:
`md.OooO00o(long) -> nd.OooO0O0 -> do0`.

Подтверждено:

```text
base: http://5.61.56.18/partner_api/
search path: list?{params}
params: page=[P]&sort=date&search=[S]
encoding of S: Android Uri.encode
response array: items
excluded category: category == s87
fields:
  id
  poster
  title
  year
  ratingImdb
  quality
```

Cookies, credentials, tokens и signing material не декодировались и не копировались.

## Search adapter

Добавлен путь:

```text
title
  -> UTF-8 percent encoding
  -> Filmix partner_api/list
  -> items[]
  -> filter invalid / category s87
  -> ProviderSearchResult[]
```

Fixture проверяет точный URL для запроса:

`page=2&sort=date&search=<UTF-8 encoded title>`.

Результат хранит:
- provider identity;
- Filmix item ID;
- title;
- year;
- article/content ref.

Неполные записи без ID/title отклоняются.

## Playback adapter

Предыдущая VariantTree логика сохранена:
- voice;
- season;
- episode;
- concrete file/quality.

Для series exact season/episode остаются структурными узлами до flatten.

Opaque packed link по-прежнему не превращается в фиктивный media URL.

## Проверки

Focused Filmix + generic contract:
- **18/18 PASS**.

Full isolated backend:
- **254/254 PASS**.

Дополнительно:
- `py_compile`: PASS;
- `git diff --check`: PASS;
- runtime dependency check: строка/import `zona_legacy_adapters` в Filmix module отсутствует;
- прежний differential fixture против legacy Filmix resolver продолжает проходить.

Известные `sqlite3 ResourceWarning` остаются; новых failures нет.

## Почему live search не запускался

Декомпилированный Filmix search endpoint использует plain HTTP и относится к старому provider contract.

В этом блоке его реальную доступность и guest-cookie behavior не проверяли. Это сознательно не выдаётся за live provider success.

Перед production-включением нужно отдельно:
1. проверить доступность endpoint;
2. проверить, нужен ли guest cookie;
3. оценить безопасный HTTPS/серверный proxy boundary;
4. только после этого подключать adapter в discovery queue.

## Production/runtime

Active parser не изменён:
- PID **23603**;
- `streamer.py --check` → **RUNNING**;
- hashes активных Zona файлов без изменений.

Android UI/APK/system settings не трогались.

Пользовательский Android worktree не записывался:
- HEAD: `c5d1d3c882e07238c6f2a8fd308e8cec32f85936`;
- index SHA-256: `0a6e8e0d5143d26ed257c1686d849ab9b4d92eb244fb4f1b8cbd843c0771ac7a`;
- status SHA-256: `065a8153e9a074e097e7d0465c68a6da153d33bef67e7a9c4ee648790729e48f`, 114 строк.

## Следующий шаг

Filmix теперь первый provider, у которого собственны и search, и article/variant orchestration.

Следующий блок должен проверять его реальный network boundary отдельно от active parser:
- availability;
- redirect/TLS behavior;
- guest session requirement;
- response schema;
- exact-match identity.

Только после успешного probe имеет смысл подключать Filmix в bounded discovery queue.
