# [ADR-292] Movia Home: Ambient Glow V3 (v0.0.1, build 307)
**Дата:** 2026-09-22 16:25

### 1. Проблема (Problem Statement)
Фоновое свечение активной карточки Spotlight (Ambient Glow) было избыточно растянуто по вертикали, вторичный оттенок терялся на фоне первичного, а в верхней части экрана (в зоне status bar / заголовка Movia) наблюдался артефакт в виде горизонтального шва и прямоугольного блока виньетки.

### 2. Первопричина (Root Cause)
1. В `MoviaAmbientStrength.HOME` вторичная альфа была занижена до `0.25f` при первичной `0.42f`, что делало вторичный цвет тусклым. Квантование насыщенности и яркости в `extractAmbientPalette` срезало пиковые значения (max value 0.58).
2. Центры свечения были зафиксированы относительно размеров экрана, а не активной карточки.
3. Круговые радиальные градиенты со статическим радиусом растягивались далеко за пределы карточки по вертикали.
4. В `HomeSpotlightSection` (`HomeScreen.kt`) присутствовал внутренний Box виньетки высотой 50dp с отступом `offset(y = vignetteStartY)`, который резко начинался с альфы 0.55 на отметке status bar ($y = 41.3\text{dp}$), разрезая фоновое свечение горизонтальной полосой.

### 3. Решение (Solution & Architecture)
1. **Яркость и баланс (+20%):**
   - Установлены `primaryAlpha = 0.52f` и `secondaryAlpha = 0.50f`, уравняв визуальную силу обоих слоев.
   - Повышены пороги насыщенности (`0.40..0.85`) и яркости (`0.42..0.78`) в `extractAmbientPalette` и `derivedSecondary`.
2. **Диагональная композиция (Bottom-Left → Top-Right):**
   - Color A (нижний левый сектор): центр строго на 30% ширины и 68% высоты активного постера.
   - Color B (верхний правый сектор): центр строго на 70% ширины и 32% высоты активного постера.
   - Выборка цветов в `extractAmbientPalette` приоритизирует нижне-левый и верхне-правый секторы постера с разнесением по цветовому кругу (`hueDistance >= 2..3`).
3. **Компактная эллиптическая форма и плавный falloff:**
   - Применено эллиптическое масштабирование `scale(scaleX = 1.15f, scaleY = 0.80f)` вокруг центров слоев.
   - Градиент оформлен через 6-точечный cosine/smoothstep falloff (0%, 25%, 50%, 72%, 88%, 100%), полностью растворяющийся в `BackgroundPrimary` (`#080B11`) выше секции «Для вас сегодня» и ниже status bar.
4. **Устранение верхнего шва/артефакта:**
   - Удален обособленный блок виньетки в `HomeScreen.kt`. Верхняя зона бесшовно переходит в чистый фон.
5. **Сохранность контрактов:**
   - Сохранены 3D Coverflow, размеры карточек, анимация crossfade 320ms ease-out, кэширование по `mediaId` и обновление только после `settledPage`.

### 4. Измененные файлы (Changed Files)
- `app/src/main/java/app/movia/android/ui/components/MoviaAmbient.kt`
- `app/src/main/java/app/movia/android/ui/home/HomeScreen.kt`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
./gradlew compileDebugKotlin --offline
./gradlew :app:assembleDebug --no-daemon --offline
cat app-debug.apk | rish -c "cat > /data/local/tmp/app_deploy.apk && pm install -r -d /data/local/tmp/app_deploy.apk && rm -f /data/local/tmp/app_deploy.apk && am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"
rish -c "screencap -p /sdcard/home_ambient_v3.png"
```
