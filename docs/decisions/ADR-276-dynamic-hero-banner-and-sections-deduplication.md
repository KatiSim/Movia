# [ADR-276] Двухрежимный Hero-баннер, ликвидация дубликатов на главной странице и отображение страны на карточках / Версия (v0.9.6, build 276)
**Дата:** 2026-08-28 09:12

### 1. Проблема (Problem Statement)
- Секции «Сейчас популярно», «Для вас», «Сериалы» и главный экран дублировали одни и те же тайтлы («Во все тяжкие», «Рик и Морти», «Побег из Шоушенка»).
- Фильм из Hero-баннера повторялся первым в карусели «Новинки».
- В разделе «Новинки» присутствовали аномальные оценки (9.4+) из-за недостаточного байесовского сглаживания малооцененных картин.
- На карточках контента в каталоге и каруселях не отображалась страна производства (`country`).

### 2. Первопричина (Root Cause)
- В `catalog_api.py` функция `get_home_payload` не вела реестр уже отобранных идентификаторов (`excluded_ids`).
- В `MediaCard.kt` переменная `country` не включалась в `metadataFacts`.
- В `HomeScreen.kt` бейдж Hero-баннера при отсутствии истории имел статичный текст «НОВИНКА» вместо «ГЛАВНЫЙ ХИТ».

### 3. Решение (Solution & Architecture)
- **100% Дедупликация (`catalog_api.py`)**:
  - Внедрен сквозной реестр `excluded_ids = set()`.
  - **Hero Promo Item**: Топ-1 блокбастер (`vote_count >= 3000, rating >= 8.0, backdrop_url IS NOT NULL`). Добавляется в `excluded_ids`.
  - **«Новинки»**: Строго 2025–2026, `id NOT IN (excluded_ids)`, квота 60% США / 40% Мир, `vote_count >= 30`.
  - **«Сейчас популярно»**: Мировые хиты по раздачам и просмотрам (`id NOT IN (excluded_ids)`).
  - **«Для вас»**: Разножанровая выборка шедевров мирового кино (IMDb Top) с ротацией (не более 2 фильмов одного жанра подряд, `id NOT IN (excluded_ids)`).
  - **«Сериалы и Мультсериалы»**: Только сериальный контент (`id NOT IN (excluded_ids)`).
- **Байесовское сглаживание ($m=150, C=6.5$)**:
  Обновлены рейтинги базы `catalog.db`, исключены аномальные 9.4+ у неизвестных картин.
- **Двухрежимный Hero-баннер (`HomeScreen.kt`)**:
  - При наличии активного прогресса (0.02..0.95): режим «Продолжить просмотр», бейдж «ПРОДОЛЖИТЬ», тонкий прогресс-бар.
  - При отсутствии истории: серверный блокбастер, бейдж «ГЛАВНЫЙ ХИТ», кнопка «Смотреть».
- **Страна на карточках (`MediaCard.kt`)**:
  Формат подписи: `"★ {rating} • {year} • {country} • {genre}"` (например, `"★ 8.9 • 2008 • США • Драма"`).

### 4. Измененные файлы (Changed Files)
- `projects/media-parser/catalog_api.py`
- `projects/movia/app/src/main/java/app/movia/android/ui/components/MediaCard.kt`
- `projects/movia/app/src/main/java/app/movia/android/ui/home/HomeScreen.kt`
- `projects/movia/app/build.gradle.kts`
- `projects/movia/docs/decisions/INDEX.md`

### 5. Использованные команды и верификация (Verification)
```bash
# Проверка 100% уникальности всех каруселей и баннера
curl -s http://127.0.0.1:8888/api/home | jq '[.sections[].items[].id] | length == (unique | length)'

# Сборка и деплой через Shizuku rish
./gradlew assembleDebug
cat /sdcard/Download/Movia-Official-v276.apk | rish -c "cat > /data/local/tmp/m276.apk && pm install -r -d /data/local/tmp/m276.apk && rm -f /data/local/tmp/m276.apk && am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"
```
