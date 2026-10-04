# Movia — clean-room ProviderDefinition + VariantTree contract

Дата: 4 октября 2026.

## Результат

В изолированной ветке добавлен первый собственный provider/parser boundary, который переносит архитектурные идеи LazyMedia Deluxe 3.466 без зависимости от Android/Dex и без provider-specific алгоритмов.

Добавлено:

- `ProviderRequest`;
- `ProviderRequestProfile`;
- `ProviderDefinition`;
- `ProviderSearchResult`;
- `ProviderArticle`;
- `DeferredVariantLoader`;
- `VariantFolder`;
- `VariantStream`;
- `flatten_variant_tree()`.

Новый слой не подключён к active parser и пока не меняет production provider discovery.

## Основные инварианты

1. `season` и `episode` задаются только вместе.
2. Для сериала принимается только leaf с точной парой season/episode.
3. Movie request не использует episodic branch как fallback.
4. Lazy loader не вызывается, если ветка уже структурно не соответствует запрошенной серии.
5. Voice/quality наследуются из дерева, но leaf может явно переопределить их.
6. Discovery/article request profile отделён от playback headers: общие provider headers не копируются в Media3 автоматически.
7. `logical_source_id` строится без зависимости от конкретного CDN/signed URL.
8. Один URL с разными voice/quality остаётся разными variant entries.

## Найденный существующий boundary bug

Проверка нового слоя обнаружила, что backend `sanitize_streams()` удалял `logical_source_id`.

Это было системной потерей данных: Android `DomainPlaybackResolver` уже умеет читать `logical_source_id`, но backend не сохранял это поле через sanitization.

Исправлено:

- `stream_validation.py` теперь сохраняет и нормализует `logical_source_id / logicalSourceId`;
- `database.py` при идентификации обновившегося direct stream сначала использует явный `logical_source_id`, затем provider/public stream ID и только потом более слабую provider/voice/quality эвристику.

Это позволяет связывать новый signed/CDN URL с тем же логическим потоком без зависимости от самого URL.

## Red → Green

Первый RED:
- новый тест не импортировался: `ModuleNotFoundError: provider_contract`.

После реализации базового контракта:
- **8/8 PASS**.

Затем отдельный boundary RED:
- **1 failure + 1 error**;
- доказано, что sanitization удаляет `logical_source_id` и database reload identity его игнорирует.

После исправления:
- focused provider + stream pipeline: **21/21 PASS**;
- полный isolated backend suite: **246/246 PASS**;
- `py_compile`: PASS;
- `git diff --check`: PASS.

Остаются прежние `sqlite3 ResourceWarning`; новых test failures нет.

## Production/runtime

Active parser этим блоком не менялся.

Контроль:
- `streamer.py` PID: **23603**;
- `streamer.py --check`: **RUNNING**;
- hashes активных Zona файлов совпадают с checkpoint активации Kinoplay.

Android UI не использовался, APK не устанавливался, системные настройки не менялись.

Пользовательский Android worktree не записывался:
- HEAD: `c5d1d3c882e07238c6f2a8fd308e8cec32f85936`;
- index SHA-256: `0a6e8e0d5143d26ed257c1686d849ab9b4d92eb244fb4f1b8cbd843c0771ac7a`;
- current status hash: `065a8153e9a074e097e7d0465c68a6da153d33bef67e7a9c4ee648790729e48f`, 114 строк.

## Следующий архитектурный шаг

Не переносить очередной provider прямо в общий flat stream API.

Следующий шаг — сделать первый clean-room adapter, лучше для одного из активных LazyMedia providers с хорошо декомпилированными List/Article классами, который выдаёт:

`search → ProviderSearchResult → ProviderArticle → VariantTree → StreamCandidate-compatible rows`.

После этого результат можно differential-сравнить с pinned LazyMedia 3.466 engine на fixtures и только затем подключать к discovery queue.

## Не завершено

- Ни один provider ещё не переведён на новый clean-room contract.
- Active parser не использует новый contract.
- Новых real-movie проверок в этом блоке нет.
- 500 полных native voice × quality матриц не завершены.
- APK 327 не проверен как установленная сборка.
