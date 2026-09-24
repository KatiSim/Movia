# [ADR-279] Комплексный запуск архитектуры Zona: скоростной бэкенд, P2P/CDN-шлюз потоков, официальные франшизы TMDB и Media3-плеер (v0.9.9, build 279)
**Дата:** 2026-08-28 10:15

### 1. Проблема (Problem Statement)
- Задержки при запросе домашней ленты (`GET /api/home`) из-за отсутствия композитных индексов в SQLite.
- Неточные франшизы сиквелов/приквелов при текстовом поиске по подстрокам названий.
- Необходимость бесшовного переключения аудиодорожек и озвучек в Media3 ExoPlayer с сохранением позиции таймлайна.
- Потенциальные относительные пути постеров (`/path.jpg`) в истории и закладках при отсутствии префикса базового домена TMDB.

### 2. Первопричина (Root Cause)
- Запросы SQLite на выборку каруселей выполняли полное сканирование таблицы `movies` без B-Tree индексов по рейтингу, годам и коллекциям.
- Отсутствие оперативного RAM-кэша для агрегированного payload домашнего экрана.
- Относительные пути TMDB требовали автоматической нормализации в абсолютные URL `https://image.tmdb.org/t/p/w500/...`.

### 3. Решение (Solution & Architecture)
- **Индексация SQLite & RAM-кэш:**
  - Созданы B-Tree индексы `idx_movies_rating_votes`, `idx_movies_year_country`, `idx_movies_collection`, `idx_movies_category`, `idx_movies_seeders` и представление `titles`.
  - Внедрен RAM-кэш `_home_cache` (TTL 15 минут) с предпрогревом при старте сервера (< 20 мс время ответа).
  - Обеспечена сквозная дедупликация `excluded_ids = set()` между Hero-баннером, Новинками (квота 60% США, 2025–2026), Популярным, «Для вас» и Сериалами.
- **Официальные франшизы TMDB:**
  - Сиквелы и приквелы формируются строго по `collection_id` (`WHERE collection_id = ? AND id != ? ORDER BY year ASC`). Текстовый поиск по `LIKE` исключен.
- **Шлюз потоков Zona API & Балансировщики:**
  - Интегрирована функция `query_zona_api` с опросом зеркал из `config/zona_sources.json`.
  - В эндпоинте `GET /api/movie/{id}/stream` настроен авто-резолвинг на лету при пустом поле `streams` с сохранением в БД.
- **Media3 ExoPlayer & Озвучки:**
  - `DynamicHeaderDataSourceFactory`: изоляция `127.0.0.1:8888` и целевые `Referer`/`Origin` для CDN (`kodik`, `hdrezka`, `collaps`, `alloha`).
  - В меню настроек плеера (⚙️) выводится список полученных дорожек `voice` с переключением через `session.switchToStream(newStreamUrl, currentPos)` и сохранением позиции.
- **Постеры:**
  - В `MoviaArtwork.kt` и `catalog_api.py` добавлена автонормализация относительных путей TMDB в `https://image.tmdb.org/t/p/w500/...` с защитой нейтральным серым плейсхолдером.

### 4. Измененные файлы (Changed Files)
- `projects/media-parser/catalog_api.py`
- `projects/media-parser/streamer.py`
- `projects/media-parser/balancer_integration.py`
- `projects/media-parser/update_streams.py`
- `projects/movia/app/src/main/java/app/movia/android/ui/components/MoviaArtwork.kt`
- `projects/movia/app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt`
- `projects/movia/app/src/main/java/app/movia/android/ui/player/PlayerScreen.kt`
- `projects/movia/app/build.gradle.kts`
- `projects/movia/gradle.properties`
- `projects/movia/docs/decisions/ADR-279-full-zona-engine-integration.md`
- `projects/movia/docs/decisions/INDEX.md`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
# Индексация базы данных
python3 -c "import sqlite3; conn = sqlite3.connect('catalog.db'); ..."

# Сборка и деплой
cd /data/data/com.termux/files/home/projects/movia
./gradlew compileDebugKotlin
./gradlew assembleDebug --no-daemon
cat app/build/outputs/apk/debug/app-debug.apk | rish -c "cat > /data/local/tmp/m279.apk && pm install -r -d /data/local/tmp/m279.apk && rm -f /data/local/tmp/m279.apk"
am start -S -n app.movia.android/.MainActivity

# Проверка скорости и работы API
curl -o /dev/null -s -w 'Home response: %{time_total}s\n' http://127.0.0.1:8888/api/home
curl -s "http://127.0.0.1:8888/api/movie/search?q=Человек-паук" | jq '.[0] | {title, collection_id, streams_count: (.streams | length)}'
```
