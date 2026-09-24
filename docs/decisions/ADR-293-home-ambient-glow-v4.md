# [ADR-293] Movia Home: Ambient Glow V4 (v0.0.1, build 307)
**Дата:** 2026-09-22 16:38

### 1. Проблема (Problem Statement)
В Ambient Glow V3 фоновое свечение за карточкой Spotlight на Главной странице воспринималось недостаточно выраженным по яркости и охвату при сохранении строгой локальности вокруг карточки и отсутствии full-screen заливки.

### 2. Первопричина (Root Cause)
1. Значения альфы в `MoviaAmbientStrength.HOME` (`primaryAlpha = 0.52f`, `secondaryAlpha = 0.50f`) создавали деликатное, но приглушенное свечение.
2. Базовый радиус эллипса `baseRadius = 150.dp` давал эффективные границы ≈ 345×240dp, чего было недостаточно для плавного, объемного ореола вокруг крупного постера Spotlight (188×282dp).

### 3. Решение (Solution & Architecture)
1. **Увеличение яркости (+40%):**
   - `primaryAlpha`: `0.52f` → `0.73f` (+40%, +0.21)
   - `secondaryAlpha`: `0.50f` → `0.70f` (+40%, +0.20)
   - Сохранено строгое визуальное равноправие двух цветовых источников.
2. **Увеличение размера (+40%):**
   - `baseRadius`: `150.dp` → `210.dp` (+40%, +60dp)
   - Сохранена эллиптическая форма: `scaleX = 1.15f`, `scaleY = 0.80f`
   - Итоговые границы свечения: с ≈ 345×240dp до ≈ 483×336dp (+138×96dp).
3. **Сохраненные инварианты:**
   - Координаты центров: Primary = `30%W / 68%H`, Secondary = `70%W / 32%H` относительно постера.
   - Диагональный вектор свечения (Bottom-Left → Top-Right).
   - 6-точечный smooth falloff с мягким растворением в `#080B11`.
   - Отсутствие верхнего шва и артефактов в зоне status bar.
   - Палитра, кэш (96 слотов), crossfade 320ms, 3D Coverflow, размеры карточек, `versionCode = 307`, `versionName = "0.0.1"`.

### 4. Измененные файлы (Changed Files)
- `app/src/main/java/app/movia/android/ui/components/MoviaAmbient.kt`
- `docs/decisions/ADR-293-home-ambient-glow-v4.md`
- `docs/decisions/INDEX.md`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
./gradlew compileDebugKotlin --offline
./gradlew :app:assembleDebug --no-daemon --offline
cat app/build/outputs/apk/debug/app-debug.apk | rish -c "cat > /data/local/tmp/app_deploy.apk && pm install -r -d /data/local/tmp/app_deploy.apk && rm -f /data/local/tmp/app_deploy.apk && am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"
rish -c "screencap -p /sdcard/check.png"
rish -c "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
```
