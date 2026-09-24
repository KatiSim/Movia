# [ADR-273] Отображение страны, карусель актёров и квотирование «Новинок» / Версия (v0.9.3, build 273)
**Дата:** 2026-08-28 08:05

### 1. Проблема (Problem Statement)
- В шапке карточки фильма отсутствовала страна производства.
- Блок «В ролях» оставался пустым при открытии тайтлов по русскоязычным именам.
- В секцию «Новинки» попадали старые тайтлы прошлых лет без жесткой квоты на блокбастеры США.

### 2. Первопричина (Root Cause)
- В `streamer.py` в роуте `/api/movie/{id}` путь не декодировался через `urllib.parse.unquote`, из-за чего закодированные URL-символы (`%D0%91%D1%80%D0%B0%D1%82`) не находили фильм в базе.
- В `DetailsMetadataLine` поле `country` опускалось при пустых значениях без стандартизированного шаблона.
- В `catalog_api.py` выборка «Новинок» не имела строгого фильтра по 2026 году и фиксированной квоты 60% США.

### 3. Решение (Solution & Architecture)
- **URL-декодирование**: Добавлен `urllib.parse.unquote()` в `streamer.py`.
- **Стандартизация метаданных**: В `DetailsScreen.kt` зафиксирован строгий формат `★ {rating} • {year} • {country} • {type} • {duration}`.
- **Редизайн `ActorCard`**: Круглая аватарка `72.dp`, двустрочное центрированное имя и роль.
- **Жесткое квотирование «Новинок»**: В `catalog_api.py` реализована выборка строго за 2026 год с рейтингом $\ge 6.0$: ровно 60% (9 тайтлов) — США, 40% (6 тайтлов) — мировые хиты.
- **Массив `sections`**: В `GET /api/home` добавлен стандартный массив секций.

### 4. Измененные файлы (Changed Files)
- `projects/media-parser/streamer.py`
- `projects/media-parser/catalog_api.py`
- `projects/movia/app/src/main/java/app/movia/android/ui/details/DetailsScreen.kt`
- `projects/movia/app/src/main/java/app/movia/android/data/catalog/CatalogRepository.kt`
- `projects/movia/app/build.gradle.kts`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
# Проверка квоты Новинок (60% США, 100% 2026 год)
curl -s http://127.0.0.1:8888/api/home | jq '.sections[] | select(.id=="new_releases") | {total: (.items | length), usa_count: ([.items[] | select(.country=="США")] | length), years: [.items[].year] | unique}'

# Проверка фильма с кастом и страной
curl -s http://127.0.0.1:8888/api/movie/12783 | jq '{title, country, rating, actors_count: (.actors | length)}'

# Сборка и деплой
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/Movia-Official-v273.apk
cat /sdcard/Download/Movia-Official-v273.apk | rish -c "cat > /data/local/tmp/m273.apk && pm install -r -d /data/local/tmp/m273.apk && rm -f /data/local/tmp/m273.apk && am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"
```
