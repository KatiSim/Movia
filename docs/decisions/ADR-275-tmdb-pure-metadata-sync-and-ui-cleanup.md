# [ADR-275] 100% Синхронизация чистых метаданных TMDB и удаление счетчика голосов из UI / Версия (v0.9.5, build 275)
**Дата:** 2026-08-28 08:52

### 1. Проблема (Problem Statement)
- Отображение количества голосов в скобках (например, `(18.4k)`, `(837)`) перегружало мобильный интерфейс и строку метаданных.
- Требовалось гарантировать 100% чистоту метаданных (постеры, фоны, синопсис, каст с аватарами, режиссеры, сезоны, серии) исключительно из официального TMDB API без каких-либо синтетических генераций.

### 2. Первопричина (Root Cause)
- В `catalog.db` отсутствовали столбцы `seasons_count`, `episodes_count`, `collection_id`.
- В `DetailsMetadataLine` форматировалась строка с `voteCount` в скобках.

### 3. Решение (Solution & Architecture)
- **Очистка строки метаданных в `DetailsScreen.kt`**:
  - Фильм: `"★ 7.7 • 2008 • США • Фильм • 121 мин"`
  - Сериал: `"★ 8.9 • 2008 • США • 5 сезонов (62 серии) • 48 мин/серия"`
  - Тайтлы без оценок: `"—"` без ложных звёзд.
- **Расширение схемы базы данных (`catalog.db`)**:
  Добавлены столбцы: `seasons_count INTEGER`, `episodes_count INTEGER`, `collection_id INTEGER`.
- **Строгий парсинг TMDB API (`enrich_catalog_metadata.py`)**:
  - `rating`: `round(vote_average, 1)`.
  - `seasons_count`: `number_of_seasons`, `episodes_count`: `number_of_episodes`.
  - `duration_minutes`: точный хронометраж `runtime` / `episode_run_time`.
  - `cast`: топ-10 актёров с официальными аватарами (`https://image.tmdb.org/t/p/w342/...`).
  - Синопсис, постеры и фон строго из TMDB (без искусственных шаблонов).
- **Обновление REST API (`catalog_api.py`)**:
  Эндпоинт `GET /api/movie/{id}` возвращает структурированный JSON с полями сезонов, серий, длительности и режиссёра.

### 4. Измененные файлы (Changed Files)
- `projects/media-parser/enrich_catalog_metadata.py`
- `projects/media-parser/catalog_api.py`
- `projects/movia/app/src/main/java/app/movia/android/domain/model/MediaContent.kt`
- `projects/movia/app/src/main/java/app/movia/android/data/catalog/CatalogRepository.kt`
- `projects/movia/app/src/main/java/app/movia/android/ui/details/DetailsScreen.kt`
- `projects/movia/app/build.gradle.kts`
- `projects/movia/docs/decisions/INDEX.md`

### 5. Использованные команды и верификация (Verification)
```bash
# Проверка JSON ответа API
curl -s http://127.0.0.1:8888/api/movie/159 | jq '{title, rating, country, seasons_count, episodes_count, director, actors_count: (.actors | length)}'

# Сборка и деплой
./gradlew assembleDebug
cat /sdcard/Download/Movia-Official-v275.apk | rish -c "cat > /data/local/tmp/m275.apk && pm install -r -d /data/local/tmp/m275.apk && rm -f /data/local/tmp/m275.apk"
```
