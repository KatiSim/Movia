# [ADR-272] Восстановление метаданных карточки и логики главной страницы / Версия (v0.9.2, build 272)
**Дата:** 2026-08-28 04:15

### 1. Проблема (Problem Statement)
После перехода на REST API в карточке фильма на UI пропал текст сюжета (пустой блок), не отображалась страна и блок актёров («В ролях»), а сиквелы и похожее формировались неполноценно.

### 2. Первопричина (Root Cause)
- REST эндпоинт `GET /api/movie/{id}` отдавал вложенную структуру `{ "movie": {...}, "sequels": [...] }`, в то время как парсер клиента ожидал плоский объект.
- В SQLite отсутствовала нормализация названий стран и сиквелов.
- В `DetailsScreen.kt` отсутствовала асинхронная загрузка полной карточки с актёрами при открытии по названию.

### 3. Решение (Solution & Architecture)
- **Унификация REST API**: В `catalog_api.py` реализован возврат гибридного JSON с root-ключами (`description`, `country`, `actors`, `cast`, `sequels_and_prequels`, `similar`).
- **Синхронизация карточки**: В `DetailsScreen.kt` внедрен `produceState` для фоновой догрузки полных метаданных через `DemoCatalogRepository.findFullByTitle()`.
- **Ребалансировка базы**: Скрипт `rebalance_catalog.py` удалил 11 568 низкокачественных тайтлов и выставил мировые квоты (США 42%, Европа 29%, Корея/Турция 12%, Япония/Китай 7%).

### 4. Измененные файлы (Changed Files)
- `projects/media-parser/rebalance_catalog.py`
- `projects/media-parser/catalog_api.py`
- `projects/media-parser/streamer.py`
- `projects/movia/app/src/main/java/app/movia/android/data/catalog/CatalogRepository.kt`
- `projects/movia/app/src/main/java/app/movia/android/ui/details/DetailsScreen.kt`
- `projects/movia/app/src/main/java/app/movia/android/ui/home/HomeScreen.kt`
- `projects/movia/app/build.gradle.kts`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
# Проверка эндпоинта карточки
curl -s http://127.0.0.1:8888/api/movie/12783 | jq '{title, country, description: .description[:60], actors_count: (.actors | length)}'

# Сборка и деплой
./gradlew assembleDebug
cat app/build/outputs/apk/debug/app-debug.apk | rish -c "cat > /data/local/tmp/m272.apk && pm install -r -d /data/local/tmp/m272.apk && rm -f /data/local/tmp/m272.apk"
```
