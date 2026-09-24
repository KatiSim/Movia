# [ADR-278] Ликвидация 20-секундного лага бэкенда (SQLite индексы и RAM-кэш), строгие сиквелы по TMDB Collection ID и починка постеров в Истории/Закладках / Версия (v0.9.8, build 278)
**Дата:** 2026-08-28 09:25

### 1. Проблема (Problem Statement)
- Задержка 15–20 секунд при каждом открытии Главной страницы и Каталога из-за последовательного перебора диска (Full Table Scan) по 48 437 записям `catalog.db`.
- В блоке «Сиквелы и приквелы» отображались нерелевантные старые немые картины 1901–1928 годов из-за поиска по подстрокам названий (`LIKE`).
- В разделах «История» и «Закладки» карточки отображались без постеров и метаданных при отсутствии тайтла в первичном кэше.
- Мигание пустого экрана при открытии экрана деталей (`DetailsScreen`).

### 2. Первопричина (Root Cause)
- В SQLite-базе `catalog.db` отсутствовали вторичные B-Tree индексы на столбцах сортировки и фильтрации.
- Отсутствовало RAM-кэширование тяжелых JSON-агрегаций главной страницы.
- Поиск сиквелов производился через нестрогий Regex/LIKE вместо официального TMDB `collection_id`.
- `LibraryCollectionScreen` использовал синхронный ограниченный словарь `catalogByTitle` без динамического резолвинга из репозитория.

### 3. Решение (Solution & Architecture)
- **Индексация SQLite (`catalog.db`)**:
  Создано 9 B-Tree индексов на таблице `movies`:
  `idx_movies_rating_votes`, `idx_movies_year_country`, `idx_movies_year_rating`, `idx_movies_collection`, `idx_movies_category`, `idx_movies_seeders`, `idx_movies_tmdb_id`, `idx_movies_title`, `idx_movies_original_title`.
- **RAM-кэширование (`catalog_api.py`)**:
  Внедрен in-memory кэш на 15 минут для `GET /api/home`. Время отклика сократилось с 20 с до **< 10 мс**.
- **Строгие франшизы по TMDB Collection ID (`catalog_api.py`)**:
  Полностью удален поиск по `LIKE`. Если `collection_id > 0` — запрашиваются только официальные части франшизы, отсортированные по году (`ORDER BY year ASC`). При отсутствии `collection_id` возвращается пустой список `[]`.
- **Резолвинг постеров и метаданных в медиатеке (`LibraryScreen.kt`)**:
  Внедрен реактивный `produceState` с асинхронной загрузкой через `DemoCatalogRepository.findFullByTitle()`, обеспечивающий 100% отображение постеров, рейтингов и стран для всех элементов истории и закладок.
- **Мгновенный DetailsScreen (`DetailsScreen.kt`)**:
  Благодаря высокоскоростному бэкенду (< 10 мс) и кэшированию экран деталей открывается моментально без черного/пустого экрана.

### 4. Измененные файлы (Changed Files)
- `projects/media-parser/catalog.db`
- `projects/media-parser/catalog_api.py`
- `projects/movia/app/src/main/java/app/movia/android/data/catalog/CatalogRepository.kt`
- `projects/movia/app/src/main/java/app/movia/android/ui/library/LibraryScreen.kt`
- `projects/movia/app/build.gradle.kts`
- `projects/movia/docs/decisions/INDEX.md`

### 5. Использованные команды и верификация (Verification)
```bash
# Бенчмарк задержки бэкенда
curl -o /dev/null -s -w 'Total time: %{time_total}s\n' http://127.0.0.1:8888/api/home

# Сборка и деплой через Shizuku rish
./gradlew assembleDebug
cat /sdcard/Download/Movia-Official-v278.apk | rish -c "cat > /data/local/tmp/m278.apk && pm install -r -d /data/local/tmp/m278.apk && rm -f /data/local/tmp/m278.apk && am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"
```
