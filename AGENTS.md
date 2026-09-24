# Movia Agent Rules & Invariants

## 1. Single MediaSession & PlaybackSession Invariant
- **Запрещено** создавать `PlaybackSession` или `MediaSession` напрямую, если экземпляр уже существует.
- **Единая точка получения сессии:**
  ```kotlin
  MoviaPlaybackRegistry.obtain(context.applicationContext)
  ```
- **Проверки перед изменениями в lifecycle, playback, headless/agent runtime:**
  1. Не создаётся ли второй `MediaSession` (Media3 запрещает дублирование Session ID в процессе: `IllegalStateException: Session ID must be unique`).
  2. Кто владеет экземпляром сессии и кто вызывает `release()` (в UI владельцем является корневой Composable `MoviaApp`, вызывающий `release()` в `onDispose`).
  3. Не конфликтует ли изменение с UI и headless/agent runtime (`AgentControlRuntime`).

## 2. Верификация и запуск
- После каждого изменения обязателен цикл:
  `build` → `install` → `cold launch` → `проверить crash log / logcat`.
- **Запрещено** считать успешную компиляцию подтверждением работоспособности приложения.
- **Запрещено** запускать параллельные Gradle-сборки или одновременные установки одного APK на устройство.

## 3. Базовый рабочий процесс
- Рабочая директория: `/data/data/com.termux/files/home/projects/movia`.
- Сохранять существующую архитектуру и несвязанные изменения.
- Не производить несанкционированный редизайн.
- При установке сохранять данные приложения (`pm install -r -d`).
