# [ADR-288] Стабилизация движка воспроизведения Media3 (v1.0.0-rc1, build 301)
**Дата:** 2026-09-06 10:00

### 1. Проблема (Problem Statement)
- При сбоях источника или нестабильном соединении плеер мог зависать в состоянии бесконечной загрузки (`STATE_BUFFERING`).
- Отсутствовало явное пользовательское состояние с сообщением `"Произошла ошибка: повторите"`.
- Чрезмерные размеры буфера `DefaultLoadControl` (300с - 360с) приводили к высокому расходу ОЗУ и риску Android LMK/OOM в Termux.
- Длительное время инициализации потока (startup latency) из-за завышенного `bufferForPlaybackMs` (2.5с) и сетевых таймаутов (15с).

### 2. Первопричина (Root Cause)
1. В `PlaybackSession.kt` существовал только первоначальный `startupWatchdogJob`. При возникновении затяжного буферирования/сталла в середине воспроизведения (`STATE_BUFFERING` при `playWhenReady == true`) watchdog не взводился, приводя к бесконечному спиннеру.
2. `DomainPlaybackResolver.resolveStreams()` не был ограничен асинхронным таймаутом с обработчиком сбоя.
3. При исчерпании доступных источников сессия плеера устанавливала длинное сообщение вместо стандартизированного `"Произошла ошибка: повторите"`.
4. `DefaultLoadControl` выделял до 6 минут буфера, замедляя холодный старт и провоцируя вытеснение процесса системой.

### 3. Решение (Solution & Architecture)
1. **Двухуровневый Watchdog & Stall Protection:**
   - Добавлен `stallWatchdogJob` в `Player.Listener`: при переходе в `STATE_BUFFERING` во время активного воспроизведения запускается таймер на 10 секунд (`STALL_WATCHDOG_MS = 10_000L`). При превышении порога инициируется `handleCandidateFailure("BUFFERING_TIMEOUT", ...)`.
   - `STARTUP_WATCHDOG_MS` сокращен с 15с до 10с.
   - `RELOAD_TIMEOUT_MS` оптимизирован до 10с.
   - Вызов `DomainPlaybackResolver.resolveStreams()` обернут в `withTimeoutOrNull(RESOLVER_TIMEOUT_MS = 12_000L)`.
2. **Стандартизированное состояние ошибки:**
   - В `failPlayback(reason)` статус сессии переводится в `PlaybackStatus.IDLE`, `switchState = PlaybackSwitchState.FAILED`, а `statusMessage` устанавливается в `"Произошла ошибка: повторите"`.
   - Добавлен публичный метод `PlaybackSession.retry()` и действие агента `player.retry`.
3. **Мобильная оптимизация буферизации (Low-Latency & Memory Protection):**
   - В `DefaultLoadControl.Builder()`:
     - `minBufferMs = 15_000` (15с вместо 300с)
     - `maxBufferMs = 45_000` (45с вместо 360с, экономия до 200MB ОЗУ)
     - `bufferForPlaybackMs = 1_000` (1с вместо 2.5с для мгновенного первого кадра)
     - `bufferForPlaybackAfterRebufferMs = 2_000` (2с вместо 5с)
     - Включен `setBackBuffer(10_000, true)` для быстрого перемотки назад на 10с без повторной загрузки чанков.
   - Сетевые таймауты `DynamicHeaderDataSource`: `connectTimeoutMs = 5_000`, `readTimeoutMs = 8_000`.
   - `BUFFERING_TIMEOUT` классифицирован как `StreamFailureClass.NETWORK` для автоматической попытки реконнекта/перезагрузки до выбывания потока.
4. **Гарантии неизменности:**
   - Не затронуты UI-слой, каталог и серверный бэкенд.

### 4. Измененные файлы (Changed Files)
- `app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt`
- `app/src/main/java/app/movia/android/domain/playback/StreamFailurePolicy.kt`
- `app/src/main/java/app/movia/android/agent/AgentControlRuntime.kt`
- `app/src/test/java/app/movia/android/domain/playback/StreamFailurePolicyTest.kt`
- `app/src/test/java/app/movia/android/ui/player/Media3PlaybackStabilizationTest.kt`
- `acceptance/100_playback_stability.py`
- `docs/decisions/ADR-288-media3-playback-engine-stabilization.md`
- `docs/decisions/INDEX.md`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
# Проверка компиляции
./gradlew compileDebugKotlin

# Запуск юнит-тестов (86 тестов, включая 100 циклов стабильности)
./gradlew testDebugUnitTest

# Сборка и бэкап APK
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk app-backup.apk

# Автоматический приемочный стресс-тест на 100 запусков потока
./acceptance/100_playback_stability.py --iterations 100

# Регрессионный сьют приемочных ворот
./movia_acceptance
```
