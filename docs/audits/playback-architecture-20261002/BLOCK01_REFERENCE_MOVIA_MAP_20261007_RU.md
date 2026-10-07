# Movia — блок 1: карта архитектуры LazyMedia → Movia
Дата: 7 октября 2026. Область: аудит исходных материалов, контрактов и существующих доказательств. Код приложения и рабочего backend в этом блоке не менялся.

## Итог
Movia уже имеет собственный provider pipeline и Media3-плеер, без загрузки APK/Dex LazyMedia. Перенос всей архитектуры ещё не завершён. Самые существенные пробелы — применение deferred tree в рабочих адаптерах, стабильность leaf identity, доставка поздних результатов, выбор здоровой ветки, обновление ссылок, неизвестные язык/качество и точная привязка истории. Количество вариантов должно определяться источниками: ограничение 3×3 не является ни контрактом, ни условием завершения.

Этот документ заменяет устаревшие утверждения аудита 4 октября о загружаемом LegacyProviderEngine. Класс LegacyProviderEngine.java и встроенный lazy-playback-engine.apk в текущем app отсутствуют. LegacyPlaybackResolver.kt — собственная совместимая точка входа Movia, вызывающая MoviaProviderRegistry.

## Основание проверки
- Worktree: /data/data/com.termux/files/home/.cache/movia-architecture-20261002/repo.
- Начальный HEAD: 106fbb0; полный SHA зафиксирован в соседнем JSON.
- Reference: /data/data/com.termux/files/home/.cache/movia-implementation-20261001/lazy-decompiled/sources.
- Локальный LazyMedia-Deluxe-3.466.apk: SHA-256 1e613dfe176821acf74150c0b134d61635d5cc5063278fe991a4ff8b990306e0.
- Исторический compatibility engine: SHA-256 637705386744f29445223fded2ad2df6aeb72295cfc9ce327609e7f0c7a72a00. Он остался материалом прежних проверок, в приложение не добавлялся.
- Изучены 13 основных reference-файлов, 44 файла Movia и 16 существующих тестовых файлов. Пути, SHA-256, число строк и привязки символов находятся в BLOCK01_REFERENCE_MOVIA_EVIDENCE_20261007.json.
- Для восьми основных backend-файлов проверено совпадение SHA между worktree и ~/projects/media-parser: все совпали. Это не утверждение о совпадении всего дерева.
- /health: HTTP 200. movia-media-parser и movia-stream-enricher: RUNNING.
- Launch scripts подтверждают HDREZKA=1, TORRENT=1, ZONA_MOBI=0. Остальные флаги gated; текущая доступность их endpoint в этом блоке заново не проверялась.
- Чужие незакоммиченные изменения сохранены. В частности MoviaApp.kt и PlayerScreen.kt уже были изменены пользователем; найденный title fallback прогресса дополнительно подтверждён в HEAD.
- 11 существующих тестов test_provider_contract.py заново выполнены: PASS. Диагностика identity/language — чистая функция, без сети и записи в catalog.db.
- Полный прогон 411 backend / 208 Android и live playback из отчётов 6 октября — исторические доказательства, не новые прогоны 7 октября. Instrumentation ранее только компилировалась; новый запуск здесь не заявляется.

Статусы: «реализовано» означает найденный исполняемый путь; «подтверждено ранее» — есть конкретное прежнее runtime-доказательство; «частично» — есть механизм, но остаётся указанный дефект; «не доказано» — для готовности нужен отдельный сценарий; «внешне заблокировано» — прежняя live-проверка не дала пригодного результата.

## Что именно найдено в LazyMedia
Пути ниже относительно reference root; сокращённые content/ и models/service/ означают соответственно com/lazycatsoftware/mediaservices/content/ и com/lazycatsoftware/lazymediadeluxe/models/service/. Здесь сохранены описания поведения, а не чужая реализация.

| Механизм | Reference-файлы и символы | Подтверждённое поведение и предел доказательства |
|---|---|---|
| Registry и capabilities | com/lazycatsoftware/mediaservices/Services.java: sServers; obf/gs0.java; obf/vq0.java: parser Class fields | Registry связывает сервис с list/article parser и настройками запросов. Доступность entry не доказывает живой endpoint. |
| Поиск и карточка статьи | content/HDREZKA_ListArticles.java: parseSearchList, parseGlobalSearchList, processingList; models/service/OooO00o.java: parseBase | Поиск даёт provider article reference и metadata; статья парсится отдельно. Catalog identity Movia — наша дополнительная защита, не заимствованный идентификатор. |
| Общий article contract | models/service/OooO00o.java: parseContent, parseTorrent, parseSimilar, setCustomHeaders, addTask | Разделены основной контент, torrent и связанные карточки; есть task tracking и HTTP profile. destroy() имеет сомнительную декомпиляцию типов: корректную отмену всех запросов этим методом не считаем доказанной. |
| Дерево | obf/i30, k30, j30; HDREZKA_Article.java: getSerialTranslate, parseMoviesFiles | Folder и file — разные типы; сохраняется контекст родительской ветки. В HDRezka видны translation → season → episode → media leaves. |
| Отложенная загрузка | obf/h30.java: onParse; obf/pl.java: doInBackground, onPostExecute; ActivityExoPlayer.java: OooO0o | Незагруженная папка раскрывается через её parser, результат добавляется к папке. Выбор ветки может запустить загрузчик. |
| Provider voice и decoder tracks | HDREZKA_Article.java: translation folders; obf/p.java: MappedTrackInfo и TrackSelectionView | Название озвучки в provider tree и audio/video/text renderer tracks — разные уровни. Не следует считать номер перевода индексом decoder track. |
| Смена media leaf | ActivityExoPlayer.java: Oooo, OooOooO; obf/p.java: OoooOO0 | Выбирается конкретный leaf; создаётся новый MediaSource. Поиск сходного format/quality не является доказательством корректного studio mapping. |
| Позиция и история | ActivityExoPlayer.java: Oooo0OO, OoooO0O; obf/o30.java: OooO0o0, OooOOo | Чтение и запись позиции связаны с article/file/folder hashes. Такие hash keys не переносим в catalog identity Movia. |
| Переход серии | ActivityExoPlayer.java: OooO0o и sibling folder traversal | При переходе может раскрыться следующая папка и выбирается подходящий leaf. Наш exact S/E contract остаётся обязательным. |
| Reload подписанной ссылки | Scoped read ActivityExoPlayer, p, j30 и article contract | Смена URL/MediaSource установлена; полноценная схема expiry → refresh same logical leaf → resume в LazyMedia этим чтением не доказана. Не приписываем ей Zona reload-логику из старого отчёта. Movia reload рассматривается как наша собственная реализация. |

В HDREZKA_Article.parseContent, ActivityExoPlayer и obf/p есть JADX warnings. Читаемые call sites позволяют установить связи классов, но не означают полную корректность всех декомпилированных методов. Это карта механизмов, а не утверждение, что каждый provider parser reference полностью восстановлен и перенесён.

## Сопоставление с Movia
B = backend/runtime/; A = app/src/main/java/app/movia/android/. Точные номера найденных символов записаны в JSON.

| № | Возможность | Файлы Movia / владельцы | Статус и что осталось |
|---|---|---|---|
| M01 | Provider definition / HTTP profile | B/provider_contract.py: ProviderDefinition, ProviderRequestProfile; B/provider_configuration.py | Реализовано. Backend discovery пока перечисляет flag branches в provider_discovery.py, а не работает через универсальный список adapter objects. Блок 2. |
| M02 | Search → exact article | B/hdrezka_provider_adapter.py: search, resolve_source; B/hdrezka_transport.py | Реализовано; HDRezka live подтверждён ранее. Native search использует exact aliases/title/year/type; ambiguous fail closed. Не обещает доступность любого фильма. |
| M03 | Единственная catalog identity | B/catalog_stream_service.py; B/stream_validation.py; A/domain/playback/DomainPlaybackResolver.kt: resolveStreamsWithBackend | Реализовано и покрыто тестами. Для известного mediaId нет title rebind источников. Прогресс/предпочтения требуют отдельной проверки M16. |
| M04 | Folder / concrete leaf | B/provider_contract.py: VariantFolder, VariantStream, flatten_variant_tree; A/domain/provider/MoviaProviderRegistry.kt | Реализовано. Leaf сохраняет headers, transport, tracks, exact S/E. Flatten на границе выдачи concrete leaves сам по себе допустим; проблема — преждевременное раскрытие веток и старые пути в обход контракта. |
| M05 | Deferred loading выбранной ветки | B/provider_contract.py: DeferredVariantLoader; A/domain/provider/MoviaProviderRegistry.kt: Deferred | Контракт и unit fixtures есть. AST-поиск в runtime не нашёл подклассов DeferredVariantLoader; HDRezka собирает streams до дерева. Android adapter группирует полученные rows обратно в дерево. Полного рабочего lazy branch lifecycle ещё нет. Блок 2. |
| M06 | Сохранить все реальные варианты | B/hdrezka_transport.py: playlist_leaves; B/provider_contract.py; A/domain/provider/MoviaProviderRegistry.kt | Реализовано без лимита 3×3. Есть fixture 600 leaves и Android fixture 12 voices × 8 qualities. Limits nodes/depth/budget — защита ресурсов, не квота меню. |
| M07 | Union providers и изоляция ошибок | B/provider_discovery.py: discover_provider_streams; B/content_filler.py; A/domain/provider/MoviaProviderRegistry.kt: discover | Реализовано. Один provider не останавливает остальные. Backend late results только в 60-секундном кэше; нужен exact persist/publish callback. Блок 4. |
| M08 | Stable logical leaf / mutable locator | B/provider_contract.py: _stream_row; B/hdrezka_provider_adapter.py: stream_key; B/torrent_provider_adapter.py: _leaf_stream_key | Частично. Torrent BTIH не зависит от trackers, но общий ID включает mutable quality; HD key дополнительно row index. Подтверждён диагностикой. Блок 3. |
| M09 | Реальные озвучки / аудиодорожки | B/hdrezka_transport.py: Translator; B/collaps_provider_adapter.py; A/ui/player/ProviderTrackSelection.kt, LogicalAudioTracks.kt | Реализовано для известных provider labels и поддерживаемых decoder tracks; ранее были реальные HDRezka voice и Collaps track smoke. Rutor translation N → decoder ordinal не доказан. Блоки 10–11. |
| M10 | Quality evidence | B/media_content_probe.py; B/hdrezka_provider_adapter.py; A/ui/player/PlaybackChoices.kt | Частично. Native advertised labels не превращаются в высоту без измерения; текущий decoder height используется. Фоновое измерение пока не обновляет каждую уже опубликованную leaf автоматически. Блок 8. |
| M11 | Voice branch / healthy leaf selection | A/domain/playback/StreamRanker.kt; A/ui/player/StreamSettingsSelection.kt: select; PlaybackSession.kt: selectVoice, selectVideoQuality | Частично. Общий ranker учитывает evidence; ручной selector использует firstOrNull и adaptive unknown shortcut. Требуется единая политика выбора среди листьев нужной voice. Блок 6. |
| M12 | Same logical reload → fallback | A/domain/playback/DomainPlaybackResolver.kt: reloadStreamCandidate, matchesReloadIdentity; A/ui/player/PlaybackSession.kt: recoverFromFailure | Реализовано на уровне orchestration. Stable-ID defect, late cache и segment failures ограничивают гарантии. Не объявляем full signed-link recovery доказанным для всех providers. Блок 7. |
| M13 | Headers, UA, retry | A/domain/playback/StreamRequestProfile.kt; A/ui/player/PlaybackSession.kt: DynamicHeaderDataSource.open | Реализовано: профиль применяется к HTTP запросам; один open retry. Read/segment recovery не равен успешному manifest HTTP 200; требуется проверка начала, середины и seek. Блоки 5, 7. |
| M14 | Один Media3 session / реальные переключения | A/ui/player/PlaybackSession.kt: MoviaPlaybackRegistry.obtain, switchToStream, applyCandidateTrackOverrides | Реализовано. Для совместимого URI/profile меняется track override, иначе MediaItem с сохранением позиции. Runtime smoke 6 октября подтверждает отдельные сценарии; вся матрица ещё не доказана. Блок 19. |
| M15 | Exact series / stale callback cancellation | B/hdrezka_episode_identity.py; B/provider_contract.py; A/domain/model/EpisodeNavigation.kt; A/ui/player/PlaybackSession.kt: generation checks | Реализовано; HDRezka exact episodes подтверждены ранее. Требуется переход вперёд/назад/через сезон/во время discovery для разных transports. Блок 9. |
| M16 | История и resume по identity | A/data/library/LibraryRepository.kt: saveProgress(MediaRef); A/ui/MoviaApp.kt: saved progress selection | Частично. Запись по MediaRef есть, но чтение после exact miss допускает progressByTitle и lastProgress.title. У одноимённых фильмов возможен неверный resume. Блок 2/P0, затем 19. |
| M17 | Unknown metadata остаётся unknown | B/provider_contract.py: _stream_row; B/torrent_provider_adapter.py; A/domain/model/StreamLanguage.kt | Нарушение: пустой language принудительно ru. Диагностический Original leaf без языка тоже получает ru. Это не доказанная русская дорожка. Блок 2/P0; нельзя выводить язык только из studio name. |
| M18 | Subtitles и устойчивый track selection | A/ui/player/PlaybackSession.kt: external SubtitleConfiguration; A/ui/player/PlayerScreen.kt: selectSubtitleTrack | Подключено, но ownership разделён: UI напрямую меняет text overrides, session setTrackPreferences обновляет только state. Восстановление выбранного text track после URL/group changes ещё не доказано. Блоки 10, 19. |
| M19 | Torrent file / seek / cancellation | B/torrent_provider_adapter.py; B/streamer.py: _torrserver_exact_episode_file_id, _torrserver_prepare_episode_stream | Реализованы exact file-path selection и отдельные engine-local indexes. Нет нового доказательства полного seek/cancel/voice flow в этом блоке; не создавать четыре voice leaf из P/P2/A без mapping. Блоки 11–12. |
| M20 | Offline variant identity | A/data/download/DownloadPlaybackIdentity.kt, OfflineVariantSelection.kt, AdaptiveOfflineDownloader.kt, OfflineDownloadWorker.kt | Собственные механизмы и тесты есть. Полный download → offline exact voice/quality → reopen without network требует device smoke. Блок 20. |
| M21 | Независимость от reference runtime | A/domain/legacy/LegacyPlaybackResolver.kt; A/domain/provider/MoviaBackendProviderAdapter.kt; app assets | APK/Dex загрузчик и LazyMedia imports не найдены в active main/build inputs; referenceRuntimeLoaded=false. Android backend adapter один, дополнительные providers живут на сервере. Другие Zona transport wrappers ещё требуют сокращения. Блок 22. |
| M22 | Разные provider transports | B/zona_provider_adapter.py → zona_contract.py → zona_legacy_adapters.py; B/collaps_provider_adapter.py; B/filmix_provider_adapter.py; B/octopus_provider_adapter.py; B/zona_mobi_provider_adapter.py | Native HDRezka независим от Zona config/runtime. Наличие других native adapters не равняется full live parity. Zona.mobi отдельный путь, gated OFF. Блоки 13–18. |

## Реестр незавершённого: 14 пунктов
Каждый пункт имеет владельца, способ закрытия и место в принятом плане. «Нужна проверка» не является утверждением о найденной поломке.

| ID | Тип / приоритет | Действие и критерий закрытия | Блоки |
|---|---|---|---|
| G01 | Архитектура / P1 | provider_discovery + provider_contract: adapters registry и рабочий deferred loader; не раскрывать чужие S/E, lazy branch errors/cancellation изолированы. Проверить не только CountingLoader fixture. | 2 |
| G02 | Дефект / P0 | provider_contract._stream_row и HD key: logical ID не меняется при измерении quality, перестановке leaves, обновлении URL/trackers. Representation key берётся из устойчивой provider identity; distinct representations остаются distinct. | 3 |
| G03 | Дефект доставки / P0 | provider_discovery late completion → catalog persistence/publish по неизменяемой exact identity; не ждать второго poll и не записывать результат в уже сменившуюся карточку. | 4 |
| G04 | Playback / P0 | Реальная доступность manifest/segments/start/seek отделена от «URL есть»; voice/quality selector выбирает подходящий healthy leaf и сохраняет все остальные реальные options. | 5–6 |
| G05 | Playback / P0 | Same-leaf signed URL reload и восстановление позиции после expiry/segment error; bounded retries, актуальные headers, отсутствует cross-film/episode fallback. | 7 |
| G06 | Evidence / P1 | Measured dimensions безопасно обновляют inventory каждой representation без смены ID; unknown не считается concrete quality. Не хардкодить наблюдавшийся сдвиг 1080→720. | 8 |
| G07 | Дефект truthfulness / P0 | Удалить implicit ru из общего контрактного слоя; пустой language остаётся unknown, языковые/studio evidence не смешиваются. | 2, 10 |
| G08 | Дефект identity / P0 | При известном MediaRef resume/preferences/offline lookup не перепривязываются по title. Для старых записей нужна доказанная migration отдельно. Проверить два одинаковых названия разных лет и соседние серии. | 2, 19–20 |
| G09 | Mapping / P1 | Rutor translation N связывается с реальным container track либо остаётся metadata одной раздачи. Пример с P/P2/A не считается selectable multi-voice. | 11 |
| G10 | Transport coverage / P1 | Для каждого выбранного provider: собственный protocol → adapter → exact tree → non-zero live parity → flag → smoke → удаление заменённого wrapper. Глухой endpoint остаётся диагностированным unavailable, без fixture source. | 13–18, 22 |
| G11 | Нужна проверка / P1 | Сериал: previous/next/cross-season/cancel discovery; torrent exact file/seek/stop; стабильные audio/video/text identities после stream switch. Subtitles ownership не должен зависеть от живого UI. | 9–12, 19 |
| G12 | Нужна проверка / P1 | Offline сохраняет выбранный реальный variant, язык/трек/эпизод; открывается без сети и с правильным resume. | 20 |
| G13 | Нужна проверка / P1 | Бounded concurrency/queue/CPU/RAM и долгий playback; не только быстрый один title. Не снижать полноту меню искусственным count cap. | 21 |
| G14 | Завершение / release gate | Полные проверки, свежие cohorts, broad actual Android decode, устранение blockers, финальная установка с hash и отчёт о честных unsupported providers. | 23–32 |

## Поправки к дальнейшему порядку
Следующий блок 2 остаётся 30-минутным аудитом/доводкой единого контракта. Начинать следует с точных границ ownership, G07 unknown language и G08 identity-bound resume; затем G01 registry/deferred. Если исправление не помещается, оно переносится как явно незакрытый пункт, не объявляется готовым по таймеру.

Блок 3 обязан менять общий provider_contract identity, а не только строку ключа в HDRezka: чистая диагностика показала drift при одинаковом stream_key уже в общем слое. Блок 4 должен закрывать именно persist/publish поздних результатов; увеличение TTL кэша само по себе не закрывает G03.

Signed reload, healthy selection, quality evidence и stable IDs связаны: после их изменения нужны проверки одним сценарием «выбрать voice → получить высоту → обновить locator → продолжить с той же позиции». Для series тот же сценарий строго в exact S/E.

Исторический строгий показатель ≥3 voices AND ≥3 qualities можно сохранять только как статистику coverage. Он не ограничивает UI, не заменяет полную variant inventory и не является обещанием такого количества у каждого фильма.

## Условия завершения проекта
Блок 32 может закрыть проект только после выполнения следующих условий, либо после явного согласования пользователем оставшегося ограничения:

1. Movia работает после удаления LazyMedia: нет зависимости от его installation/runtime/assets/config и не требуется его запуск.
2. Все поддерживаемые providers проходят единый owned contract; нет активного обхода, выдающего неверные identity, voice, quality или audio index.
3. UI показывает все реальные доступные options, сохраняет связь voice/quality/file/track, unknown остаётся unknown; не создаёт Cartesian combinations, которых provider не дал.
4. Известные catalog identities и exact episodes защищены также в resume, history, offline и callbacks.
5. Реальные switching/reload/seek/recovery/download flows подтверждены на Android, включая failure scenarios и сохранение позиции.
6. Performance budgets не скрывают поздние результаты и не оставляют бесконтрольные workers.
7. Свежие независимые 1000-film cohorts и broad device playback проверены по плану; decoded, unavailable и externally blocked считаются отдельно.
8. Финальный APK собран, установлен с сохранением данных, SHA сверён; cold launch и crash/ANR smoke пройдены. Документация, commit, rollback и список поддерживаемых providers сохранены.

Один timebox или наличие класса не являются завершением проекта. Внешний blocked provider не заменяется фиктивным источником; его статус и решение о поддержке отражаются явно.

## Артефакты и воспроизводимость
- BLOCK01_REFERENCE_MOVIA_EVIDENCE_20261007.json: source hashes, symbol line anchors, чистая диагностика и snapshot рабочего окружения.
- block01_source_audit_20261007.py: собственный read-only анализатор. Он не содержит скопированного reference source; только пути, шаблоны символов и контрактную диагностику.
- Проверка: PYTHONPATH=backend/runtime python -m unittest discover -s backend/tests -p test_provider_contract.py — 11/11 PASS.
- Перед изменением документов device binding integrity: PASS. WARP/DNS, сервисы, catalog.db, app code и установленный APK не изменялись.
