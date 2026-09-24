# [ADR-294] Movia Home: Ambient Glow Dynamic Bounds (v0.0.1, build 307)
**Дата:** 2026-09-22 16:48

### 1. Проблема (Problem Statement)
Фоновое свечение (Ambient Glow) Spotlight на Главном экране ранее рисовалось на весь контейнер либо распространялось ниже Spotlight, попадая в область контентного блока «Для вас сегодня» («For You Today») и создавая визуальный шум за постерами ленты рекомендаций. Требовалось строго ограничить свечение сверху от physical y=0 (включая зону status bar) и снизу ровно за 5dp до начала блока «Для вас сегодня».

### 2. Решение (Solution & Architecture)
1. **Динамический расчет координат (Dynamic Measurement):**
   - В `HomeScreen.kt` на корневой `Box` добавлен замер `rootTopInWindowPx` через `onGloballyPositioned`.
   - На элемент заголовка секции «Для вас сегодня» добавлен замер `forYouTopInWindowPx` через `onGloballyPositioned`.
   - Вычислено относительное положение блока:
     `forYouRelativeTopDp = with(density) { (forYouTopInWindowPx - rootTopInWindowPx).toDp() }`
   - Нижняя граница ambient-области задана как:
     `ambientBottomDp = (forYouRelativeTopDp - 5.dp).coerceAtLeast(0.dp)`
2. **Изоляция слоя подсветки (Scoped Glow Container):**
   - Ambient Glow вынесен в отдельный слой фона `Box(modifier = Modifier.fillMaxWidth().height(ambientBottomDp).clipToBounds().moviaAmbient(...))`
   - Физический верх находится на $y = 0$ (под status bar), нижняя граница строго заканчивается на `forYouTop - 5dp`.
   - Блок «Для вас сегодня» расположен ниже границы ambient-слоя и не попадает в зону свечения.
3. **Бесшовное растворение (Seamless Soft Dissolve):**
   - В `MoviaAmbient.kt` в `drawBehind` добавлен вертикальный линейный градиент плавного растворения (`Color.Transparent` -> `MoviaBackgroundPrimary #080B11`) на нижних 48dp высоты свечения.
   - Это гарантирует полное отсутствие видимой горизонтальной линии среза свечения.
4. **Сохранение дизайна и инвариантов:**
   - Положение Spotlight, карточек, CTA, текста, карусели 3D Coverflow и навигации осталось неизменным.

### 3. Измерения и верификация (Measurements on Device)
- **CURRENT:** ~904 dp (полная высота экрана `rootTop = 0.0` до низа viewport, свечение распространялось под блок «Для вас сегодня»)
- **TARGET:** `ambientBottom = forYouTop - 5dp`
- **ACTUAL:** 
  - `rootTop = 0.0 px` ($y = 0$, свечение начинается с самого верха под status bar)
  - `forYouTopInWindow = 1584.0 px` (`forYouTop = 528.0 dp`)
  - `ambientBottom = 523.0 dp` (динамически вычислено `forYouTop - 5.dp`)
  - Зазор между нижней границей свечения и блоком «Для вас сегодня» составляет строго **5.0 dp**.
- **DELTA:** -381 dp (область свечения локализована сверху от $y=0$ до $y=523\text{ dp}$, нижняя граница поднята на 381 dp, исключая блок «Для вас сегодня»).

### 4. Измененные файлы (Changed Files)
- `app/src/main/java/app/movia/android/ui/home/HomeScreen.kt`
- `app/src/main/java/app/movia/android/ui/components/MoviaAmbient.kt`
- `docs/decisions/ADR-294-home-ambient-glow-dynamic-bounds.md`
- `docs/decisions/INDEX.md`
