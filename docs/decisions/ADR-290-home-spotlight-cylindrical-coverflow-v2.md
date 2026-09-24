# [ADR-290] Home Spotlight: Cylindrical 3D Coverflow Upgrade V2 (v0.10.2, build 307)
**Дата:** 2026-09-22 13:45

### 1. Проблема (Problem Statement)
Предыдущая реализация карусели Home Spotlight представляла собой базовый наклонный пейджер (tilted pager) с линейной интерполяцией углов (±18°), линейным масштабированием (0.88) и полупрозрачностью самих изображений постеров (`alpha = 0.68`). В результате боковые постеры выглядели плоскими, а их прозрачность приводила к просвечиванию фонового слоя, создавая эффект растворения карточек вместо ощущения глубины сцены.

### 2. Первопричина (Root Cause)
Трансформации рассчитывались независимо друг от друга без единой вогнутой цилиндрической математической модели. Затемнение карточек производилось через альфа-канал постера (`graphicsLayer.alpha`), что нарушало физику освещения. Также отсутствовала дополнительная X-компрессия для компенсации движения карточек по виртуальной цилиндрической дуге.

### 3. Решение (Solution & Architecture)
Реализован Cylindrical 3D Coverflow V2 на базе Jetpack Compose:
- **Единая непрерывная координата:** все трансформации рассчитываются от непрерывного `p = (pageIndex - currentPage) - offsetFraction`, $d = |p|$, $t = \text{clamp}(d, 0, 1)$, $t_2 = \text{clamp}(d - 1, 0, 1)$.
- **RotationY (вогнутый цилиндр):** боковые карточки развернуты внутрь к центру по формуле $\text{rotationY} = -\text{sign}(p) \times 30^\circ \times t$ (Center: 0°, Adjacent: ±30°, Distant: до ±36°).
- **Scale:** Center = 1.00, Adjacent = 0.84 ($1.00 - 0.16 \times t$), Distant = до 0.76.
- **Horizontal Arc Compression:** $\text{arcCorrectionX} = -\text{sign}(p) \times 24\text{dp} \times \sin(t \times \pi / 2)$ для компенсации дуги по оси X (втягивание карточки на 24dp внутрь сцены).
- **Opaque Image & Dark Overlay:** альфа-канал самого постера зафиксирован на 1.00; затемнение реализовано внутренним черным оверлеем с кривой $\text{smoothstep}(t)$ (Center = 0.00, Adjacent = 0.28, Distant = до 0.42).
- **Z-Index:** непрерывный $\text{zIndex} = (2f - d).\text{coerceAtLeast}(0f)$ (Active = 2.0, Half = 1.5, Adjacent = 1.0, Distant = 0.0).
- **Reduce Motion:** при отключенных анимациях в системе сохраняется плоский режим (`rotationY = 0°`, `arcCorrectionX = 0dp`, `scale` до 0.94, `overlay` до 0.20).

### 4. Измененные файлы (Changed Files)
- `app/src/main/java/app/movia/android/ui/home/HomeScreen.kt`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
./gradlew compileDebugKotlin --offline
./gradlew :app:assembleDebug --no-daemon --offline
cat app/build/outputs/apk/debug/app-debug.apk | rish -c "cat > /data/local/tmp/app_deploy.apk && pm install -r -d /data/local/tmp/app_deploy.apk && rm -f /data/local/tmp/app_deploy.apk"
rish -c "am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"
rish -c "logcat -d | grep -E 'FATAL|AndroidRuntime|Spotlight' | tail -n 30"
```
