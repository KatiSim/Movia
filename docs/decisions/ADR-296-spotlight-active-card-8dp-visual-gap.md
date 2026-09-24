# [ADR-296] Movia Home: Spotlight Active Card 8dp Visual Gap (v0.0.1, build 307)
**Дата:** 2026-09-22 19:42

### 1. Проблема (Problem Statement)
В 3D Spotlight/Coverflow на Главном экране активная центральная карточка (размер 172.96×259.44dp) имела избыточный визуальный зазор (37.625dp) до левой и правой соседних 3D-карточек в settled-состоянии. Требовалось увеличить активную центральную карточку пропорционально с сохранением соотношения сторон 2:3 так, чтобы фактический визуальный зазор (с учётом scale 0.75, rotationY ±30°, translationX 24dp и перспективы) до левой и правой карточек составлял ровно 8.0 dp.

### 2. Первопричина (Root Cause)
1. Базовый размер постера `activePosterWidth = minOf(172.96.dp, screenWidth * 0.42504f)` задавал компактную карточку 173×259.5dp.
2. В `HorizontalPager` шаг слотов (stride) составляет `pageSlotWidth + pageSpacing` ($173\text{dp} + 16\text{dp} = 189\text{dp}$).
3. При шаге слотов 189dp левый край правой соседней карточки проецируется на $x = 327.625\text{dp}$, а правый край центральной карточки находился на $x = 290.0\text{dp}$, создавая зазор $327.625 - 290.0 = 37.625\text{dp}$.

### 3. Решение (Solution & Architecture)
1. **Пропорциональное увеличение активной карточки:**
   - $\text{activePosterWidth} = 207.16\text{dp}$ (`minOf(207.16.dp, screenWidth * 0.5094f)`).
   - $\text{activePosterHeight} = \text{activePosterWidth} \times 1.5f = 310.74\text{dp}$ (строгое сохранение соотношения сторон 2:3).
   - Прирост ширины составляет $+34.20\text{dp}$ (+19.8%), высоты $+51.30\text{dp}$ (+19.8%).
2. **Геометрия пейджера и центрирование:**
   - Шаг слотов зафиксирован на каноничном значении $\text{stride} = 189\text{dp}$ (`pageSlotWidth = 173.dp`, `pageSpacing = 16.dp`).
   - Карточки внутри слотов отцентрированы с помощью `wrapContentWidth(Alignment.CenterHorizontally, unbounded = true).requiredWidth(activePosterWidth).requiredHeight(activePosterHeight)`.
   - Центральная карточка остаётся строго отцентрирована по вертикальной оси экрана: центр в $x = 203.33\text{dp}$, левый край $299.75\text{px}$ ($99.92\text{dp}$), правый край $920.25\text{px}$ ($306.75\text{dp}$).
3. **Расчёт и верификация визуального зазора 8dp:**
   - Правая соседняя карточка ($p = +1$): левый край проецируется на $x = 944.25\text{px}$ ($314.75\text{dp}$).
   - Зазор справа: $944.25\text{px} - 920.25\text{px} = 24.00\text{px} = \mathbf{8.00\text{ dp}}$.
   - Левая соседняя карточка ($p = -1$): правый край проецируется на $x = 275.75\text{px}$ ($91.92\text{dp}$).
   - Зазор слева: $299.75\text{px} - 275.75\text{px} = 24.00\text{px} = \mathbf{8.00\text{ dp}}$.
4. **Сохранение динамики свайпа и инвариантов:**
   - Входящая карточка плавно вырастает до нового 100% размера (207.16×310.74dp).
   - Уходящая карточка плавно сжимается до 75% (155.37×233.05dp).
   - Аспекты 3D, zIndex, rotationY, overlay, snap, CTA, Ambient Glow и ADR-294 полностью сохранены.

### 4. Измененные файлы (Changed Files)
- `app/src/main/java/app/movia/android/ui/home/HomeScreen.kt`
- `docs/decisions/ADR-296-spotlight-active-card-8dp-visual-gap.md`
- `docs/decisions/INDEX.md`
