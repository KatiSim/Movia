# [ADR-283] Восстановление главного баннера и экрана деталей (v0.9.23, build 293)
**Дата:** 2026-09-03 02:30

### 1. Проблема (Problem Statement)
1. На главном экране в верхнем баннере отображался некорректный бейдж «ГЛАВНЫЙ ХИТ» вместо «ПРОДОЛЖИТЬ ПРОСМОТР». Центральная кнопка Play не запускала воспроизведение контента (была некликабельна).
2. На экране деталей фильма верхний Hero-постер начинался с отступом от статус-бара (был визуально обрезан), а круглые кнопки навигации накладывались на системную область и вырез фронтальной камеры.
3. В нижней части экрана деталей пропали блоки «В ролях», «Режиссёр» и «Похожее» из-за тяжелых промежуточных таблиц съемочной группы и технических деталей.

### 2. Первопричина (Root Cause)
1. В `HomeScreen.kt` переменная `badgeLabel` вычислялась с дефолтом `"ГЛАВНЫЙ ХИТ"`, а при пустом или неразрешенном `heroContent` вызов `onPlay` внутри `Surface.clickable` молча игнорировался без фоллбека.
2. В `DetailsScreen.kt` контейнер `LazyColumn` имел жесткий верхний паддинг `.padding(top = 64.dp)`, отрезавший Edge-to-Edge рендеринг постера под статус-бар. Кнопки `TopAppBar` позиционировались со смещенным инсетом.
3. В `DetailsScreen.kt` блоки участников и похожих были заблокированы вызовом `CrewDetailsSection` / `TechnicalDetailsSection`, выводившими плоскую таблицу вместо канонических каруселей актеров и похожих карточек.

### 3. Решение (Solution & Architecture)
1. В `HomeScreen.kt`:
   - Зафиксирован бейдж `val badgeLabel = "ПРОДОЛЖИТЬ ПРОСМОТР"`.
   - Внедрен гарантированный резолв контента `val resolvedContent = heroContent ?: fallbackItem`.
   - На центральную кнопку Play назначен прямой `clickable` с ripple-эффектом amber, вызывающий `onPlay(targetContent, targetTitle)`. Клик по телу карточки сохранен за `onOpenDetails`.
2. В `DetailsScreen.kt`:
   - Удален `.padding(top = 64.dp)` у `LazyColumn`, постер уходит под прозрачный StatusBar от y = 0.
   - Навигационная плашка зафиксирована поверх баннера с модификатором `Modifier.statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)`, исключая пересечение с вырезом камеры.
   - Восстановлена секция «В ролях» с `titleMedium`, отступом `top = 20.dp`, кругами актеров 72x72 dp, жирным белым шрифтом имени (13sp) и серым именем персонажа (11sp).
   - Восстановлена лаконичная секция «Режиссёр» (`titleMedium`, цвет `#E1E2EC`, 14sp).
   - Подтверждена карусель «Похожее» с карточками формата 2:3, скруглением 12.dp и двухстрочными метаданными.

### 4. Измененные файлы (Changed Files)
- `app/src/main/java/app/movia/android/ui/home/HomeScreen.kt`
- `app/src/main/java/app/movia/android/ui/details/DetailsScreen.kt`
- `docs/decisions/ADR-283-hero-banner-and-details-ui-restoration.md`
- `docs/decisions/INDEX.md`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
# Проверка компиляции Kotlin
./gradlew compileDebugKotlin

# Прогон юнит-тестов
./gradlew testDebugUnitTest

# Сборка исполняемого APK
./gradlew assembleDebug

# Установка через Shizuku (Zero-Click Deploy)
TMP_APK="/data/local/tmp/app_deploy.apk"
rish -c "cat 'app/build/outputs/apk/debug/app-debug.apk' > '$TMP_APK' && pm install -r -d '$TMP_APK' && rm -f '$TMP_APK' && am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"
```
