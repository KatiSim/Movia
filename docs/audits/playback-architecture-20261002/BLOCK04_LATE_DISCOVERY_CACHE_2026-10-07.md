# Movia — блок 4: поздняя публикация и чтение кеша

2026-10-07, работа начата около 13:40 UTC / 15:40 Europe/Madrid. Устройство — только Jarvis 3 Екатерины; UI не присоединён, display 0 не использован. Версия сохранена 335 / 0.0.1, данные не очищались, WARP/DNS не изменялись.

## Результат

Исправлен путь, на котором непустой кеш завершал Android discovery при ответе refreshing=true. MoviaBackendProviderAdapter теперь публикует подтверждённые варианты через собственный MoviaProviderRegistry сразу, затем продолжает наблюдать тот же exact identity job в прежнем ограниченном discovery budget. Только первый запрос инициирует force refresh; дальнейшие polling reads не создают новый job. Найденные позже озвучки и качества объединяются с inventory без фиксированного количества. Signed locator того же проверенного источника получает предпочтение перед устаревшим. Полные identity/track/file проверки сохраняются.

Registry принимает промежуточные варианты каждого provider, проверяет identity перед публикацией и сохраняет уже подтверждённую часть при provider timeout. Диагностика такого provider остаётся TIMEOUT. Отмена coroutine останавливает polling, переход на другую карточку защищён существующим correlationId/generation. Старые одноразовые resolver вызовы без progress callback сохраняют быстрый возврат готового кеша.

Обнаружен и устранён дорогой backend read-path для сериалов: карточка нормализовала потоки всех эпизодов перед фильтрацией одной серии. Добавлен get_movie_playback_card_scoped и подключён к CatalogStreamService, включая повторное чтение и discovery persistence boundary. До нормализации остаются только explicit положительные integer S/E нужного эпизода; generic packs не превращаются в серию. Затем действуют прежние полные identity guards. Обычный get_movie_playback_card остаётся совместимым. Повреждённые контейнеры streams возвращают пустой список.

## Проверено

- Android: 232/232 unit tests PASS, включая пять новых regressions: cache → late voice/URL, terminal failure retention, cancellation, progressive registry publication и сохранение partial rows при timeout. APK и AndroidTest Kotlin собраны; instrumentation не запускалась.
- Backend: 433/433 PASS. Четыре новых теста используют SQLite-карточку с 600 эпизодами и проверяют фильтрацию перед нормализацией, отклонение чужого catalog ID, отсутствие invented episode, malformed container и неизвестный ID.
- Профиль старого card read: 1 238 normalize_lampa_result вызовов, около 11 секунд под profiler. Это диагностический замер с overhead, а не независимый latency benchmark.
- Live exact S01E02 endpoint до развёртывания наблюдался за 2.72 / 8.09 / 6.82 секунды при работающей сборке. После scoped read: 1.498 / 0.785 / 0.828 секунды, 146 streams, все строго S01E02. Условия нагрузки различались; процент улучшения не заявляется. Каталог мог пополняться background enrichment.
- Два backend файла развёрнуты; parser/enricher RUNNING, health 200. catalog_api.py развёрнут точечной заменой playback функции, потому что live имеет другие изменения относительно repo; все остальные live различия сохранены. Бэкапы в block04-live-backup.
- APK установлен и cold headless launch успешен, FATAL/ANR в проверенных PID логах нет. SHA256 сборки и установленного base.apk: e0455426b64e9971028dbd8d44bc2c70332f7740b0e93f096041c8d5a8abb25c.
- Все 20 пользовательских изменённых/новых путей сохранили SHA256. В кодовом checkpoint только восемь собственных файлов. APK включает прежние пользовательские UI изменения worktree.

## Незакрытый playback gate — не считается PASS

- Exact v2 torrent, Интерстеллар catalog 158, запрошен provider-item:v2:9ede95bbfc62adc6b860c65e: за 100 секунд нет frames/длительности, RECOVERING, mediaItemId остаётся 158. Выбор и декодирование v2 не подтверждены.
- Direct series smoke, Во все тяжкие catalog 159 S01E02, Original (+subs), запрос 720p: за 50 секунд нет frames, RECOVERING. В предыдущем блоке этот кейс декодировался в реальных 240p; результат этого блока не подменяется прежним PASS.
- Контроль на APK блока 3 при текущем backend/сети также не получил frames за 50 секунд. Это показывает, что сбой не специфичен только новому Android APK, но не исключает влияние backend и не устанавливает окончательную причину.
- По завершении сравнения текущий APK блока 4 возвращён, SHA и cold launch проверены заново. Каждый smoke остановлен, probe отключён, история/resume/preferences не записывались.
- Отдельный запрос нового HDRezka exact discovery получил SSLError: certificate verify failed, self-signed certificate. Повтор с доверенным Termux CA bundle тоже не прошёл. TLS verification не отключалась; новые HDRezka rows этим тестом не сохранялись.
- Первая попытка diagnostics во время torrent smoke получила HTTP 400; дальнейшие diagnostics reads были доступны. Причина этого ответа ещё не установлена.

Реализация блока и unit/live read проверки готовы, но блок нельзя объявить полностью закрытым по playback gate. Главная следующая работа: блок 5 — диагностика доступности manifest/segment и startup/recovery path direct/P2P, проверка серверных locators/cache после restart, затем доказанный v2 выбор и decoding на Android. Также остаётся живое доказательство появления новых voices во время одной playback-сессии; unit-тесты этого поведения уже проходят. Дальнейшие release gates и ≥500 реальных decoder проверок не отменяются.

## Откат

Android предыдущего блока: /sdcard/Download/Movia_QA_335/block03-app.apk, SHA256 3ac131090b3bab40971209b0ed21c823e3babde5d0bae9040211a0311e2b600b, pm install -r -d без очистки данных. Backend: восстановить два файла из block04-live-backup, перезапустить movia-media-parser и movia-stream-enricher, проверить health. База не мигрировалась массово; feature flags не изменялись.
