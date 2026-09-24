# [ADR-287] Интеграция движков Media3 HLS и DASH для воспроизведения онлайн-потоков и аудиодорожек / Версия (v0.9.30, build 300)
**Дата:** 2026-09-04 09:30

### 1. Проблема (Problem Statement)
1. На реальном смартфоне Xiaomi (HyperOS / Android 14) в приложении Zona видеопотоки и все аудиодорожки запускаются стабильно, тогда как в Movia воспроизведение не стартовало ни для одного фильма или сериала.
2. В системных логах `logcat` при попытке запуска любого кандидата воспроизведения (например, Collaps HLS `master.m3u8`) фиксировался сбой:
   ```text
   MoviaPlayer: Candidate preparation failed: id=collaps_... type=IllegalStateException
   ```
3. Пользователь подтвердил отсутствие воспроизведения и потребовал устранить сбой в Movia без изменения дизайна интерфейса.

### 2. Первопричина (Root Cause)
1. **Отсутствие модуля HLS в зависимостях Movia:**
   - Онлайн-балансеры (Collaps) отдают адаптивные стримы по стандарту HLS (`.m3u8`).
   - Библиотека Google AndroidX Jetpack Media3 является модульной: артефакт `androidx.media3:media3-exoplayer` содержит только парсеры прогрессивных медиаконтейнеров (MP4, MKV).
   - Поддержка протоколов HLS (`.m3u8`) и DASH (`.mpd`) вынесена в специализированные библиотеки `media3-exoplayer-hls` и `media3-exoplayer-dash`.
   - В `app/build.gradle.kts` эти зависимости отсутствовали. Фабрика `DefaultMediaSourceFactory` через рефлексию запрашивала `androidx.media3.exoplayer.hls.HlsMediaSource$Factory`, получала `ClassNotFoundException` и оборачивала его в `IllegalStateException`.
   - Байткод-аудит предыдущего APK подтвердил: класс `HlsMediaSource` физически отсутствовал во всех `.dex` файлах.
2. **Потенциальная уязвимость потока инициализации:**
   - Конструктор `ExoPlayer.Builder(appContext)` не фиксировал явный Looper, что создавало риск рассинхронизации потоков при фоновом обращении через Registry.
3. **Неинформативное логирование:**
   - В блоке перехвата ошибок `prepareCandidate` логировался только `throwable::class.java.simpleName`, скрывая реальную причину (`ClassNotFoundException: androidx.media3.exoplayer.hls.HlsMediaSource$Factory`).

### 3. Решение (Solution & Architecture)
1. **Подключение библиотек потокового воспроизведения (`app/build.gradle.kts`):**
   - Добавлены официальные зависимости AndroidX:
     ```kotlin
     implementation("androidx.media3:media3-exoplayer-hls:1.9.3")
     implementation("androidx.media3:media3-exoplayer-dash:1.9.3")
     ```
   - Версия сборки повышена до `versionCode = 300`, `versionName = "0.9.30"`.
2. **Гарантия потокобезопасности и наблюдаемости (`PlaybackSession.kt`):**
   - В билдер `ExoPlayer.Builder` добавлен `.setLooper(Looper.getMainLooper())`.
   - Логирование в `prepareCandidate` расширено для вывода сообщения исключения и полного stack trace.
3. **Сохранение каноничного дизайна:**
   - Все файлы UI (`HomeScreen.kt`, `DetailsScreen.kt`, `CatalogScreen.kt`, `LibraryScreen.kt`, `SearchScreen.kt`) остались абсолютно нетронутыми.
4. **Автономный Zero-Click Deploy:**
   - Сборка нового APK, создание резервной копии `app-backup.apk`.
   - Тихая фоновая установка через ADB/Shizuku без диалоговых окон.

### 4. Измененные файлы (Changed Files)
- `projects/movia/app/build.gradle.kts`
- `projects/movia/app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt`
- `projects/movia/docs/decisions/INDEX.md`
- `projects/movia/docs/decisions/ADR-287-media3-hls-dash-engine-integration.md`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
# Компиляция и тесты
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
./gradlew assembleDebug

# Проверка включения класса HlsMediaSource в DEX
python3 -c "import zipfile; z = zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk'); print(any(b'Landroidx/media3/exoplayer/hls/HlsMediaSource;' in z.read(n) for n in z.namelist() if n.endswith('.dex')))"

# Автономный деплой
adb -s 127.0.0.1:44905 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s 127.0.0.1:44905 shell "am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"
```
