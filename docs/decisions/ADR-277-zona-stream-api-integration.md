# [ADR-277] Внедрение шлюза потоков Zona API, динамический резолвинг озвучек и интеграция с ExoPlayer / Версия (v0.9.7, build 277)
**Дата:** 2026-08-28 10:25

### 1. Проблема (Problem Statement)
- Карточки каталога требовали актуализации живых потоков с множеством вариантов озвучки/дубляжа (Дубляж, LostFilm, Red Head Sound, HDRezka, Кубик в кубе, Original).
- При запросе фильма с пустой колонкой `streams` плеер должен на лету получать варианты источников и кэшировать их в базу.
- Необходимо обеспечить моментальное переключение аудиодорожек в интерфейсе плеера (шестерёнка ⚙️) с сохранением позиции воспроизведения (`seekTo`).

### 2. Первопричина (Root Cause)
- В `balancer_integration.py` отсутствовала реализация `query_zona_api`, читающая конфигурацию зеркал `config/zona_sources.json`.
- Отсутствовал скрипт синхронизации `update_streams.py` для точечного и пакетного (`--top 500`) наполнения базы данных.
- Эндпоинт `/api/movie/{id}/stream` не выполнял он-деманд резолвинг и запись в SQLite при пустом списке `streams`.

### 3. Решение (Solution & Architecture)
- **Конфигурация зеркал (`config/zona_sources.json`)**:
  - Зафиксированы зеркала Zona API (`apir0.mzona.net`, `vsr01.zonasearch.com`, `zstat.zona.mobi`) с таймаутом 4.0с и fallback-логикой.
- **Шлюз потоков (`balancer_integration.py`)**:
  - Реализована функция `query_zona_api(title, year, season, episode)`, извлекающая метаданные потоков: `source: 'Zona API'`, `voice`, `quality`, `seeders`, `url`.
  - Встроена в начало цепочки `query_open_balancer_stream`.
- **Синхронизатор и пакетный наполнитель (`update_streams.py`)**:
  - Создан CLI-инструмент для обновления потоков по отдельным тайтлам (`python3 update_streams.py "<title>" <year>`) и пачками (`--top 500`).
  - Сохраняет структурированный JSON-массив потоков в `catalog.db` и `media_catalog.db`.
- **Он-деманд резолвер (`streamer.py`)**:
  - Добавлены алиасы маршрутов `/api/movie/search` и `/api/search`.
  - В `/api/movie/{id}/stream` добавлен авто-резолвинг через Zona API с мгновенным кэшированием в SQLite.
- **Бесшовное переключение в ExoPlayer (`PlaybackSession.kt` & `PlayerScreen.kt`)**:
  - При выборе аудиодорожки или качества в меню настроек вызывается `switchToStream(url, currentPositionMs)`, переинициализируется `MediaItem` и вызывается `player.seekTo(savedPos)`.

### 4. Измененные файлы (Changed Files)
- `projects/media-parser/config/zona_sources.json`
- `projects/media-parser/balancer_integration.py`
- `projects/media-parser/update_streams.py`
- `projects/media-parser/streamer.py`
- `projects/media-parser/torrent_resolver.py`
- `projects/movia/app/build.gradle.kts`
- `projects/movia/docs/decisions/ADR-277-zona-stream-api-integration.md`
- `projects/movia/docs/decisions/INDEX.md`

### 5. Использованные команды и верификация (Verification)
```bash
# Проверка обновления потоков для тестового тайтла
python3 ~/projects/media-parser/update_streams.py "Человек-паук: Нет пути домой" 2021

# Проверка API поиска и отдачи потоков
curl -s "http://127.0.0.1:8888/api/movie/search?q=Человек-паук" | jq .
curl -s "http://127.0.0.1:8888/api/movie/226/stream" | jq .

# Сборка и тихий Zero-Click деплой через Shizuku rish
./gradlew assembleDebug
cat app/build/outputs/apk/debug/app-debug.apk > /sdcard/Download/Movia-Official-v277.apk
cat /sdcard/Download/Movia-Official-v277.apk | rish -c "cat > /data/local/tmp/m277.apk && pm install -r -d /data/local/tmp/m277.apk && rm -f /data/local/tmp/m277.apk && am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"
```
