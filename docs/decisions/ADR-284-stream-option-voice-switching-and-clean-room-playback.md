# [ADR-284] Архитектурное исправление выбора озвучек через StreamOption и Clean-Room изоляция (v0.9.28, build 298)
**Дата:** 2026-09-03 22:58

### 1. Проблема (Problem Statement)
1. В плеере `PlayerScreen` при выборе озвучки (LostFilm, Кубик в Кубе, Дубляж) не происходило реальное переключение видеопотока, либо звук полностью пропадал/зависал.
2. Варианты озвучек и качеств в меню настроек плеера брались из `DemoCatalogRepository.findByTitle(...).streams`, игнорируя динамически разрешенные потоки `session.streamOptions`. При пустом каталоге список принудительно схлопывался в единственную заглушку «Дубляж».
3. Название студии озвучки (например, «LostFilm» или «Кубик в Кубе») ошибочно передавалось в Media3 ExoPlayer как `label` внутренней аудиодорожки контейнера (`C.TRACK_TYPE_AUDIO`), которой в медиафайле не существовало.

### 2. Первопричина (Root Cause)
1. Концептуальная подмена понятий: студийный `voice` в пиратских онлайн-балансерах и P2P-потоках является самостоятельным `StreamOption` (отдельным URL с собственными заголовками и метаданными), а не встроенной внутренней дорожкой в одном HLS/MP4 контейнере.
2. `PlayerScreen` не был подписан на StateFlow `session.streamOptions`, читая статическую копию из каталога.
3. Попытка эмуляции закрытого шлюза Zona `/getVideoSources` на бэкенде давала `HTTP 500: Internal error` из-за закрытой нативной проверки времени/куки, требуя чистой архитектурной изоляции и работы через независимые прямые контракты источников.

### 3. Решение (Solution & Architecture)
1. В `PlayerScreen.kt`:
   - Подписали компонент на реактивный поток `session.streamOptions`.
   - Выбор озвучки и качества теперь осуществляется строго среди цельных объектов `StreamOption`.
   - Переключение потока выполняется через `session.switchToStream(matchedStream, currentPosition)`, сохраняя все оригинальные сетевые заголовки (`Referer`, `Origin`), `User-Agent`, `source identity` и текущую позицию таймлайна.
   - Названия студий озвучки исключены из передачи в Media3 track-selection overrides.
   - Устранена некорректная инициализация `audioTrackId` именами студий («LostFilm» и др.).
2. Создан выделенный компонент выбора потоков `StreamSettingsSelection.kt` и покрыт модульными тестами.
3. Сохранен строгий инвариант плеера:
   - `ExoPlayer.Builder = 1`
   - `MediaSession.Builder = 1`
   - UI и дизайн не модифицировались.
4. Версия приложения повышена:
   - `versionCode = 298`
   - `versionName = "0.9.28"`
5. Выполнено резервное копирование и Zero-Click Deploy:
   - Снята копия установленной версии 0.9.27/297 (`app-installed-0.9.27-code297-backup.apk`).
   - Сохранена копия артефакта сборки 0.9.28/298 (`app-backup.apk`).
   - Произведена тихая установка на устройство через Shizuku (`rish`) с верификацией запуска `MainActivity`.

### 4. Измененные файлы (Changed Files)
- `app/build.gradle.kts`
- `app/src/main/java/app/movia/android/ui/player/PlayerScreen.kt`
- `app/src/main/java/app/movia/android/ui/player/StreamSettingsSelection.kt`
- `app/src/test/java/app/movia/android/ui/player/StreamSettingsSelectionTest.kt`
- `app-backup.apk` (Release artifact copy v0.9.28 b298)
- `app-installed-0.9.27-code297-backup.apk` (Pre-install backup v0.9.27 b297)
- `docs/decisions/ADR-284-stream-option-voice-switching-and-clean-room-playback.md`
- `docs/decisions/INDEX.md`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
# Полный gate сборки и тестирования (PASS: 3m 33s, 56 tasks)
./gradlew testDebugUnitTest compileDebugKotlin compileDebugAndroidTestKotlin assembleDebug --no-daemon

# Проверка параметров сгенерированного артефакта (SHA-256: fe770c4aa2ec26e3f3f5c8d6b9525b22b1142fc846ca945c88ab4af3ee22921a)
aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep -E "package: name=|versionCode=|versionName="

# Резервное копирование установленной версии 0.9.27/297 из системы
rish -c "cat /data/app/.../base.apk" > app-installed-0.9.27-code297-backup.apk

# Автономный тихий деплой через Shizuku (rish)
APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
TMP_APK="/data/local/tmp/app_deploy.apk"
cat "$APK_SRC" | rish -c "cat > '$TMP_APK' && pm install -r -d '$TMP_APK' && rm -f '$TMP_APK' && am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"

# Верификация установленной версии и активного фокуса
rish -c "dumpsys package app.movia.android | grep -E 'versionCode|versionName'"
rish -c "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
```
