# [ADR-282] Надежная доменная архитектура воспроизведения Movia (v0.9.12, build 282)
**Дата:** 2026-08-30 11:10

### 1. Проблема (Problem Statement)
Ранее логика воспроизведения в приложении опиралась на прямые строковые URL-адреса и разрозненные обработчики переключения. При сбоях провайдеров, протухании токенов сессий, сетевых таймаутах или отсутствии доступных источников пользователь сталкивался с бесконечным буферизационным экраном, черным экраном либо внезапными падениями без возможности повтора. Механизм выбора стримов не учитывал различия между прямыми CDN-потоками и P2P-раздачами, что вызывало длительные задержки старта.

### 2. Первопричина (Root Cause)
1. Отсутствие строгой доменной модели запроса на воспроизведение (`PlaybackRequest`) и нормализованного представления стрим-кандидатов (`StreamCandidate`).
2. Недостаточная дедупликация и нормализация озвучек/качеств на клиенте перед отправкой в плеер.
3. Отсутствие разделения приоритетов между быстрыми прямыми CDN потоками и P2P/торрент-потоками.
4. Отсутствие явного состояния `NO_SOURCE` и обработки ошибок с диалогом восстановления («Повторить» / «Вернуться назад») в интерфейсе `PlayerScreen.kt`.

### 3. Решение (Solution & Architecture)
1. **Domain Playback Layer (`app.movia.android.domain.playback`)**:
   - `PlaybackRequest`: Каноническое представление целевого медиа (ID, название, тип, сезон, эпизод, канонический ключ).
   - `StreamCandidate`: Строго типизированный дескриптор потока с метаданными (провайдер, URL, озвучка, качество, сиды, кастомные заголовки, субтитры).
   - `StreamDeduplicator`: Нормализация озвучек и устранение дубликатов по хэшам и ссылкам.
   - `StreamRanker`: Детерминированный ранжировщик стримов, приоритизирующий прямые CDN-потоки над торрентами и исключающий сбойные стримы (`failedStreamIds`).
   - `DomainPlaybackResolver`: Параллельное разрешение источников с контролем таймаутов и поддержкой горячего перезапроса (`reloadStreamCandidate`).
2. **Интеграция в плеер и UI**:
   - `PlaybackSession.kt`: Обновлена сортировка потоков (`isDirectStream`), внедрен трекинг поколений (`switchGeneration`), безопасные переключения зеркал и обработка отката.
   - `PlayerScreen.kt`: Добавлен информативный диалог при `NO_SOURCE` и ошибках воспроизведения с кнопками «Повторить» и «Вернуться назад».
3. **Backend & Acceptance Tests**:
   - Исправлен тестовый набор схемы в `projects/media-parser/test_catalog_sync.py` (19/19 тестов пройдены).
   - Разработан и пройден автотест случайного покрытия `acceptance/08_playback_coverage_random.py` (20 фильмов + 20 сериалов, 0 крашей / 0 сбоев плеера).
   - Все авторитетные гейты `01_headless_cold` — `07_final_acceptance` пройдены (24/24 PASS).

### 4. Измененные файлы (Changed Files)
- `app/src/main/java/app/movia/android/domain/playback/PlaybackRequest.kt`
- `app/src/main/java/app/movia/android/domain/playback/StreamCandidate.kt`
- `app/src/main/java/app/movia/android/domain/playback/StreamDeduplicator.kt`
- `app/src/main/java/app/movia/android/domain/playback/StreamRanker.kt`
- `app/src/main/java/app/movia/android/domain/playback/DomainPlaybackResolver.kt`
- `app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt`
- `app/src/main/java/app/movia/android/ui/player/PlayerScreen.kt`
- `app/src/test/java/app/movia/android/domain/playback/DomainPlaybackResolverTest.kt`
- `projects/media-parser/test_catalog_sync.py`
- `acceptance/08_playback_coverage_random.py`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
# Проверка бэкенд тестов
python3 -m unittest test_playback_variants.py test_streamer_torrent_gid.py test_catalog_sync.py

# Сборка и прогон unit-тестов Android
./gradlew :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug :app:compileDebugAndroidTestKotlin

# Zero-Click Deploy через Shizuku (rish)
APK_PATH="/data/data/com.termux/files/home/projects/movia/app/build/outputs/apk/debug/app-debug.apk"
TMP_APK="/data/local/tmp/app_deploy.apk"
cat "$APK_PATH" | rish -c "cat > '$TMP_APK' && pm install -r -d '$TMP_APK' && rm -f '$TMP_APK' && am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"

# Авторитетные приемочные тесты
bash acceptance/01_headless_cold.sh && python3 acceptance/02_smoke.py && python3 acceptance/03_benchmark.py && python3 acceptance/04_operation.py && python3 acceptance/05_breaking_bad.py && bash acceptance/06_mcp_inventory.sh && python3 acceptance/07_final_acceptance.py

# Рандомизированный coverage тест на 40 тайтлах
python3 acceptance/08_playback_coverage_random.py
```
