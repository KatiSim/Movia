# [ADR-289] Рефакторинг Hero-экрана Movia и инкремент версии (v0.10.2, build 307)
**Дата:** 2026-09-21 22:06

### 1. Проблема (Problem Statement)
На главном экране приложения Movia градиент затемнения (scrim) Hero-постера перекрывал верхнюю и центральную часть арта серой дымкой, снижая контрастность и четкость ключевых персонажей и фона.

### 2. Первопричина (Root Cause)
В Composable-функции `ContinueWatchingCard` градиентная маска затемнения начинала затенять постер уже с середины блока мелкими ступенями прозрачности (`0.70f`, `0.78f`, `0.85f`), из-за чего арт персонажей выглядел блеклым и размытым.

### 3. Решение (Solution & Architecture)
1. Скорректированы параметры `Brush.verticalGradient`:
   - Верхние 65% блока постера зафиксированы полностью прозрачными (`0.00f to Color.Transparent`, `0.65f to Color.Transparent`), что полностью освобождает лица и фигуры персонажей от дымки.
   - Переход в цвет фона экрана (`pageBackground`) сосредоточен строго в нижних 35% высоты (`0.82f to pageBackground.copy(alpha = 0.65f)`, `1.00f to pageBackground`).
2. Сохранены все пропорции и читаемость элементов нижнего блока: название, подзаголовок серии, полоса прогресса, кнопка «Продолжить» и кнопка-закладка.
3. Поднята версия приложения: `versionCode: 306 -> 307`, `versionName: "0.10.1" -> "0.10.2"`.

### 4. Измененные файлы (Changed Files)
- `app/src/main/java/app/movia/android/ui/home/HomeScreen.kt`
- `app/build.gradle.kts`
- `docs/decisions/ADR-289-hero-scrim-refactoring-and-v0-10-2.md`
- `docs/decisions/INDEX.md`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
# Сборка APK в offline-режиме
./gradlew :app:assembleDebug --no-daemon --offline

# Сверка контрольной суммы
sha256sum app/build/outputs/apk/debug/app-debug.apk
# af75ddbbbb53edd3aa7b633b6e734c776d4f19c71593a86121263c8fdd25bfb4

# Установка и запуск через Shizuku (rish)
cat app/build/outputs/apk/debug/app-debug.apk | rish -c "cat > /data/local/tmp/app_deploy.apk && pm install -r -d /data/local/tmp/app_deploy.apk && rm -f /data/local/tmp/app_deploy.apk"
rish -c "am start -n app.movia.android/.MainActivity"

# Визуальная верификация на Shower Virtual Display
rish -c "screencap -d 11529215050128903777 -p /data/local/tmp/screen_vd.png"
```
