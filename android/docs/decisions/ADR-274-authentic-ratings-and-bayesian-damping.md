# [ADR-274] Интеграция честных рейтингов TMDB и байесовское сглаживание / Версия (v0.9.4, build 274)
**Дата:** 2026-08-28 08:35

### 1. Проблема (Problem Statement)
- В каталоге присутствовали синтетические оценки 9.0–10.0, выставленные при начальной генерации базы тайтлам без подтвержденных оценок.
- Проблема малого числа голосов (Low Vote Count): неизвестные фильмы с 1–2 голосами получали балл 10.0, искажая топы и секции «Новинки».
- В UI отсутствовало количество проголосовавших пользователей, а тайтлы без оценок отображали искусственные звёзды.

### 2. Первопричина (Root Cause)
- В `catalog.db` отсутствовали столбцы `vote_count` и `vote_average`.
- В формуле сортировки `SCORE_SQL` не учитывалось количество голосов пользователей.
- В UI не было разделения между `rating == 0.0` (нет оценок) и валидным рейтингом со счетчиком голосов.

### 3. Решение (Solution & Architecture)
- **Байесовское взвешенное сглаживание ($m=30, C=6.5$)**:
  $$WR = \frac{v}{v + 30} \cdot R + \frac{30}{v + 30} \cdot 6.5$$
  Низкорейтинговые/малоголосые тайтлы сглаживаются к среднемировому баллу 6.5, исключая выбросы. Тайтлы без голосов получают честный `rating = 0.0`.
- **Зачистка базы (`clean_fake_ratings.py`)**:
  Обнулены неподтвержденные 10.0 и синхронизированы реальные `vote_count` и `vote_average` из TMDB API.
- **Рейтинговая сортировка `SCORE_SQL`**:
  Введена весовая шкала по количеству голосов (>10k, >1k, >100, >30).
- **Отображение в UI (`DetailsScreen.kt`)**:
  - При наличии рейтинга: `★ 7.8 (12.4k) • 2026 • США • Фильм • 121 мин`.
  - При отсутствии оценок: плашка `—` без ложных звёзд.
- **Модель данных**: В `MediaContent.kt` и `CatalogRepository.kt` добавлено поле `voteCount`.

### 4. Измененные файлы (Changed Files)
- `projects/media-parser/clean_fake_ratings.py`
- `projects/media-parser/enrich_catalog_metadata.py`
- `projects/media-parser/catalog_api.py`
- `projects/movia/app/src/main/java/app/movia/android/domain/model/MediaContent.kt`
- `projects/movia/app/src/main/java/app/movia/android/data/catalog/CatalogRepository.kt`
- `projects/movia/app/src/main/java/app/movia/android/ui/details/DetailsScreen.kt`
- `projects/movia/app/build.gradle.kts`
- `projects/movia/docs/decisions/INDEX.md`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
# Проверка топ-10 тайтлов по реальному рейтингу и голосам
python3 -c "import sqlite3; conn = sqlite3.connect('catalog.db'); print(conn.execute('SELECT title, rating, vote_count FROM movies WHERE rating > 0 ORDER BY rating DESC, vote_count DESC LIMIT 10').fetchall())"

# Проверка API карточки с vote_count
curl -s http://127.0.0.1:8888/api/movie/159 | jq '{title, rating, vote_count, country}'

# Сборка и деплой
./gradlew assembleDebug
cat app/build/outputs/apk/debug/app-debug.apk | rish -c "cat > /data/local/tmp/m274.apk && pm install -r -d /data/local/tmp/m274.apk && rm -f /data/local/tmp/m274.apk"
```
