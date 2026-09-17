# Архитектурный аудит и техническая карта проекта Movia (Релиз 1.0.0 Readiness)

> **Статус документа:** Официальный архитектурный аудит  
> **Проект:** Movia Android Client + Backend Streaming Gateway  
> **Текущая версия кодовой базы:** `v0.9.32` (build `302`)  
> **Целевая версия:** `v1.0.0` (Production Stabilization)  
> **Дата формирования:** 05.09.2026  
> **Роль составителя:** Senior Mobile Architect  

---

## 0. Введение и цели аудита

Цель настоящего документа — зафиксировать полную техническую карту текущего состояния программного комплекса **Movia**, включающего нативное Android-приложение (Jetpack Compose / Media3) и локальный бэкенд-шлюз потокового вещания (Python / TorrServer / CDN Balancers). 

Документ служит фундаментом для стабилизации кодовой базы перед выпуском версии **1.0.0**, выявляет скрытые технические долги, критические точки отказа (SPOF), описывает жизненный цикл воспроизведения, матрицу зависимостей и пошаговый план перехода к production-ready состоянию.

В соответствии с регламентом аудита:
- Исходный код системы на этапе аудита **не изменялся**;
- Проведен анализ статического анализатора, компилятора Kotlin/Java, Media3 HLS/DASH пайплайна, Unit-тестов (82 теста Android + 63 теста Python) и сквозных сценариев приемки (Movia Agent / Jarvis MCP).

---

## 1. Текущая архитектура системы

Программный комплекс Movia спроектирован по гибридной архитектуре: ультралегкий нативный мобильный клиент (размер APK ~23 МБ) взаимодействует с локальным изолированным бэкенд-демоном (`127.0.0.1:8888`), обеспечивающим сокрытие сложности взаимодействия с гетерогенными источниками медиапотоков (P2P/BitTorrent, CDN-балансеры, агрегаторы).

```mermaid
graph TD
    subgraph "Android Application (app.movia.android)"
        UI[UI Layer: Jetpack Compose + Material 3]
        PlayerUI[PlayerScreen + MiniPlayerBar + Controls]
        MoviaArtwork[MoviaArtworkLoader: In-Memory LRU + Disk Cache]
        
        subgraph "Domain Layer"
            DPR[DomainPlaybackResolver: Dual-Stage Identity / Title]
            SR[StreamRanker: Weight Matrix & Health]
            SDD[StreamDeduplicator & FailureClassifier]
            PS[PlaybackSession: Media3 ExoPlayer Engine]
        end
        
        subgraph "Data Layer"
            CR[CatalogRepository: HTTP Client + LruCache 500]
            RE[RecommendationEngine: Local Scoring]
            RoomDB[(Room Database: User Library & Progress)]
            PrefRepo[MoviaPreferencesRepository: DataStore]
            WM[DownloadScheduler: WorkManager]
        end

        subgraph "Control Plane (Agent / MCP)"
            AgentSvc[AgentControlService: HTTP 127.0.0.1:8899]
            AgentBoot[AgentBootstrapReceiver: Wake-Only]
            AgentRuntime[AgentControlRuntime: 28+ Headless Actions]
        end
    end

    subgraph "Backend Streaming Gateway (projects/media-parser)"
        Streamer[streamer.py: Port 8888 Daemon]
        CatAPI[catalog_api.py: Catalog & Home Feed]
        DB[(catalog.db: SQLite 60k Titles)]
        
        subgraph "Provider Adapters Pool"
            Collaps[collaps_provider.py: Direct HLS master.m3u8]
            Rezka[rezka_provider.py: HDRezka CDN Scraper]
            Zona[zona_contract.py: Fast Failure Gateway]
            TorrentRes[torrent_resolver.py: Torznab / Apibay / Trackers]
        end
        
        TorrServer[TorrServer / aria2c JSON-RPC :6800 Gateway]
    end

    UI --> CR
    UI --> RoomDB
    UI --> MoviaArtwork
    PlayerUI --> PS
    PS --> DPR
    DPR -->|HTTP GET /api/movie/{id}/stream| Streamer
    DPR -->|HTTP GET /resolve?title=...| Streamer
    CR -->|HTTP GET /api/home, /api/catalog| Streamer
    Streamer --> CatAPI
    CatAPI --> DB
    Streamer --> Collaps
    Streamer --> Rezka
    Streamer --> Zona
    Streamer --> TorrentRes
    TorrentRes --> TorrServer
    TorrServer -->|HTTP Video Stream| PS
    Collaps -->|HLS Stream .m3u8| PS
    AgentSvc --> AgentRuntime
    AgentRuntime --> PS
    AgentRuntime --> CR
```

### 1.1 Android Layers (Слои Android-приложения)

#### 1.1.1 UI Layer (Jetpack Compose / Material 3)
*   **Архитектурный паттерн:** State-driven Single-Activity (`MainActivity.kt`). Отказ от сторонних библиотек навигации в пользу каноничного реактивного состояния `rememberSaveable` в `MoviaApp.kt`:
    *   `selectedIndex` (0: Главная, 1: Каталог, 2: Моё);
    *   `detailsStack: List<String>` (LIFO-стек открытых карточек фильмов с сохранением истории перехода);
    *   `settingsRoute: String?` (маршрутизация настроек);
    *   `fullPlayerOpen: Boolean` (полноэкранный режим плеера).
*   **Компоненты экранов:**
    *   `HomeScreen.kt`: Динамический Hero-баннер, горизонтальные ленты («Популярное», «Новинки», «Сериалы», «Для вас»), подкачка через `produceState`.
    *   `CatalogScreen.kt`: Адаптивная сетка постеров, чипы жанров, фильтрация по годам и рейтингам, сохранение позиции скролла через `CatalogRetentionState`.
    *   `DetailsScreen.kt`: Edge-to-Edge верстка (ADR-283), плавающие кнопки запуска, горизонтальные списки актеров и режиссеров, выбор сезонов и серий через `ModalBottomSheet` (высота 88%, запрет перехвата системного Back).
    *   `PlayerScreen.kt`: Профессиональный видеоинтерфейс с двойным тапом для перемотки (+/-10с), жестами регулировки громкости и яркости, выбором звуковых дорожек, качества и субтитров, PiP-режимом (`PictureInPictureSupport.kt`).
    *   `MiniPlayerBar.kt`: Плавающий плеер, жестко спозиционированный над `NavigationBar`.
*   **Подсистема графики и изображений:**
    *   `MoviaArtwork.kt`: Полностью автономная подсистема (без Coil/Glide). Включает:
        *   Двухуровневый кэш: оперативная память `LruCache` (расчет размера в килобайтах) + дисковый кэш `context.cacheDir/movia_artwork` с именованием по SHA-256;
        *   Масштабирование декодирования: постерам назначается `inSampleSize = 2` для экономии памяти;
        *   Дедупликация параллельных запросов: `ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>` гарантирует ровно 1 HTTP-запрос при одновременной отрисовке одинаковых постеров в разных каруселях.

#### 1.1.2 Domain Layer (Модели и бизнес-логика)
*   **Модели предметной области:**
    *   `MediaContent`: Универсальная карточка контента (фильм, сериал, ТВ).
    *   `StreamOption` & `StreamCandidate`: 40+ атрибутов стрима (прямой URL, логический идентификатор `logicalSourceId`, хеш торрента `infoHash`, HTTP-заголовки `headers`, дорожки звука `voice`, качество `quality`, сиды `seeders`, транспорт `transport`).
    *   `PlaybackState`: Модель состояния проигрывателя со строгим перечислением `PlaybackStatus` (`IDLE`, `PREPARING`, `PLAYING`, `PAUSED`, `BUFFERING`, `FAILED`, `ENDED`).
*   **Оркестрация стримов:**
    *   `DomainPlaybackResolver.kt`: Инкапсулирует двухэтапное обнаружение источников на бэкенде. Фильтрует невоспроизводимые URL, проверяет белый список заголовков HTTP (`accept`, `referer`, `origin`, `user-agent`), парсит встроенные и внешние субтитры.
    *   `StreamRanker.kt`: Скоринг кандидатов по матрице:
        Score = W_voice + W_quality + W_health + W_transport - W_failures.
        Прямые HLS-потоки балансеров получают базовый приоритет перед P2P-раздачами для мгновенного старта.
    *   `StreamDeduplicator.kt`: Устранение дубликатов по нормализованному ключу `(url, voice, quality, season, episode)`.
    *   `StreamFailurePolicy.kt`: Классификатор сбоев (`NETWORK_TIMEOUT`, `HTTP_FORBIDDEN`, `CONTAINER_PARSE_ERROR`, `SOURCE_EXHAUSTED`).

#### 1.1.3 Data Layer (Хранилища и сетевой клиент)
*   **Каталог:**
    *   `CatalogRepository.kt` (`DemoCatalogRepository`): Клиент REST API бэкенда (`127.0.0.1:8888`). Кэширует до 500 сущностей в `LruCache`, выполняет фоновый прогрев лент (`fetchHomeAsync`), нормализует кириллицу через `CanonicalTextNormalizer`.
    *   `RecommendationEngine.kt`: Вычисляет релевантные рекомендации в оперативной памяти устройства по коэффициентам Жаккара для жанров и режиссеров.
*   **База данных Room:**
    *   `MoviaDatabase.kt` (v2.8.4): Хранит пользовательские данные (Избранное, История, Прогресс воспроизведения, Очередь загрузок). Локальный тяжелый каталог был вынесен на бэкенд в ADR-271.
*   **Настройки и загрузки:**
    *   `MoviaPreferencesRepository.kt`: Jetpack DataStore Preferences.
    *   `OfflineDownloadWorker.kt`: AndroidX WorkManager для надежной фоновой загрузки файлов.

#### 1.1.4 Control Plane & Agent Integration (`app.movia.android.agent`)
*   **Loopback API:** Встроенный легковесный HTTP-сервер на `http://127.0.0.1:8899/agent/v1`.
*   **Безопасность:** Доступ разрешен только с `127.0.0.1`. Авторизация по Bearer-токену (64 hex-символа), сохраняемому в защищенной директории приложения `files/agent/movia-agent.token`. В броадкастах и интентах токены **не передаются**.
*   **Возможности:** 28+ действий (`media.play`, `player.pause`, `player.seek`, `player.selectVoice`, `player.selectQuality`, `catalog.query`, `library.snapshot`).
*   **Интеграция с Jarvis/MCP:** Адаптер `termux-mcp/src/movia-tools.ts` напрямую проксирует команды агента в локальный API Movia без необходимости использования ADB или Shizuku в стандартном контуре.

---

### 1.2 Media3 Playback Pipeline (Конвейер воспроизведения)

Пайплайн воспроизведения Movia основан на Google AndroidX Media3 (версия 1.9.3) и спроектирован с соблюдением принципа строгого владения ресурсами:

```
[Пользователь / Агент]
          │  start(mediaId, title, season, episode, sourceUri, preferredVoice)
          ▼
[PlaybackSession.kt] ───► CoroutineScope (SupervisorJob + Dispatchers.Main.immediate)
          │
          ├── 1. Сброс состояния: watchdogJob, failureClass, recoveryBudget = 1
          ├── 2. Запрос стримов: DomainPlaybackResolver.resolveCandidates()
          ├── 3. Ранжирование: StreamRanker.rank() (Collaps HLS > P2P > Fallback)
          │
          ▼
[Подготовка потока: prepareCandidate()]
          │
          ├── Применение RequestProfile: DynamicHeaderDataSource (Referer, Origin, UA)
          ├── Построение MediaItem: URI + safeMimeType (application/x-mpegURL) + Subtitles
          ├── Привязка к ExoPlayer (MainLooper)
          │
          ▼
[Media3 Engine Pipeline]
   ├── Exoplayer.setMediaItem(mediaItem, startPositionMs)
   ├── DefaultMediaSourceFactory
   │      ├── HlsMediaSource.Factory (androidx.media3:media3-exoplayer-hls)
   │      ├── DashMediaSource.Factory (androidx.media3:media3-exoplayer-dash)
   │      └── ProgressiveMediaSource.Factory (DefaultExtractorsFactory)
   ├── DefaultLoadControl (minBuffer: 300s, maxBuffer: 360s, playbackBuffer: 2.5s)
   │
   ▼
[События ExoPlayer Listener: onPlaybackStateChanged]
   ├── STATE_BUFFERING ──► Показ вращающегося Movia Spinner
   ├── STATE_READY ─────► Старт воспроизведения (playWhenReady = true), скрытие лоадера
   ├── STATE_ENDED ─────► Сохранение 100% прогресса, автопереход к следующей серии
   └── Player.Listener.onPlayerError ──► StreamFailureClassifier ──► Auto-Fallback
```

*   **Архитектурное исправление озвучек (ADR-284):** Поскольку внешние балансеры и торренты поставляют разные переводы в виде **отдельных видеофайлов**, выбор озвучки в Movia переключает весь объект `StreamOption` через `session.switchToStream(matchedStream, currentPosition)`, бесшовно сохраняя секунду воспроизведения.
*   **HLS/DASH интеграция (ADR-287):** Подключение модулей `media3-exoplayer-hls` и `media3-exoplayer-dash` устранило критический сбой `ClassNotFoundException` при старте HLS-потоков Collaps.
*   **Инициализация плеера:** Строго на `Looper.getMainLooper()` с привязкой к единственному `MediaSessionCompat` для системных медиа-нотификаций и PiP.

---

### 1.3 Stream Resolving Flow (Конвейер обнаружения и шлюзования потоков)

Процесс разрешения потока инициируется при клике на просмотр или вызове `media.play`:

```
1. Movia Android (DomainPlaybackResolver)
   │
   ├── Запрос А: GET http://127.0.0.1:8888/api/movie/{id}/stream?season=S&episode=E&refresh=0
   │
   └── Запрос Б (fallback): GET http://127.0.0.1:8888/resolve?title=T&category=C&year=Y...
             │
             ▼
2. Backend Daemon (streamer.py :8888)
   │
   ├── Нормализация заголовка: normalize_ru_text(title)
   ├── Сопоставление с локальной БД catalog.db:
   │      Нахождение: tmdb_id, imdb_id, canonical_title, canonical_year
   │
   ├── Проверка двухуровневого кэша: Memory Cache + stream_cache/ (TTL 5 мин / 48 ч)
   │
   └── Параллельный запуск провайдеров: ThreadPoolExecutor(max_workers=2)
          │
          ├── Ветка 1: _resolve_balancer_provider (timeout 4.0s)
          │      ├── collaps_provider.py:
          │      │     Запрос к зеркалам delivembd.ws / bhcesh.me по imdb_id
          │      │     Извлечение master.m3u8 и карты аудио (Дубляж, LostFilm, Goblin...)
          │      ├── rezka_provider.py (резервный скрейпинг CDN)
          │      └── zona_contract.py (быстрый выход при HTTP 500/403)
          │
          └── Ветка 2: _resolve_torrent_provider (timeout 0.5s - 3.5s)
                 ├── torrent_resolver.py: Torznab, Apibay, RuTracker, Nyaa
                 ├── Обогащение трекерами (OpenTrackr, OpenBitTorrent)
                 └── Генерация P2P Gateway ссылки:
                       http://127.0.0.1:8888/stream?info_hash=...&file_index=...
                       (Шлюзование через aria2c / TorrServer)
```

---

### 1.4 Metadata Flow (Потоки метаданных)

1.  **Первичное наполнение:** Метаданные хранятся в `projects/media-parser/catalog.db` (~60 000 фильмов и сериалов).
2.  **Синхронизация с TMDb:** Скрипты `tmdb_client.py` и `live_catalog_sync.py` обновляют рейтинги, постеры, синопсисы и списки актеров/съемочной группы.
3.  **Формирование витрины (`/api/home`):** Бэкенд агрегирует секции «Популярное», «Новинки», «Сериалы», «Для вас» и Hero-баннеры, исключая дубликаты между секциями.
4.  **Клиентское потребление:** `DemoCatalogRepository` забирает JSON, кэширует в оперативной памяти и предоставляет готовые `MediaContent` компонентам Jetpack Compose.

---

## 2. Найденные проблемы и архитектурные риски

### 2.1 Bugs (Дефекты реализации)

| ID | Компонент | Описание проблемы | Влияние / Риск |
| :--- | :--- | :--- | :--- |
| **BUG-01** | `SearchScreen.kt:106` | Синхронный вызов `DemoCatalogRepository.getPopular(8)` внутри `remember { ... }` на UI-потоке. | При холодном старте или сброшенном кэше выполняется блокирующий `httpGet()` с таймаутом до 12с. Риск `NetworkOnMainThreadException` или ANR-диалога. |
| **BUG-02** | `MoviaArtworkLoader` | При HTTP-ошибках CDN (429, 503, 504) `download()` возвращает `null`, не кэшируя статус ошибки. | На каждом кадре рекомпозиции списка Compose повторно запрашивает битые ссылки, создавая шторм сетевых запросов и фризы скролла. |
| **BUG-03** | `PlayerScreen.kt:917, 1139, 1186` | Предупреждения компилятора Kotlin: `Condition is always 'true'`. | Наличие мертвого кода и неработающих проверок условий видимости элементов управления. |
| **BUG-04** | `AgentOperationStore.kt` | Состояния операций хранятся исключительно в памяти процесса (`ConcurrentHashMap`). | При выгрузке приложения системой Android (LMK/OOM) фоновые агентские операции теряют трекинг, приводя к зависанию поллинга в Jarvis. |

### 2.2 Технический долг (Technical Debt)

| ID | Компонент | Описание долга | Рекомендация к 1.0.0 |
| :--- | :--- | :--- | :--- |
| **DEBT-01** | `app/build.gradle.kts` | Использование устаревшего `kapt` для Room: `kapt("androidx.room:room-compiler:2.8.4")`. Компилятор падает в fallback до Kotlin 1.9. | Перевести генератор Room на KSP (`com.google.devtools.ksp`), ускорить инкрементальную сборку на 40–50%. |
| **DEBT-02** | Репозиторий Movia | Наличие 15+ мусорных файлов бэкапов (`.bak-*`, `.orig`, `.compile_settings.log`). | Полная зачистка репозитория перед релизом 1.0.0, добавление шаблонов в `.gitignore`. |
| **DEBT-03** | `CatalogRepository.kt` | Именование продакшн-репозитория как `DemoCatalogRepository` (1054 строки боевого кода). | Переименовать в `HttpCatalogRepository` для исключения архитектурной путаницы. |
| **DEBT-04** | `MoviaArtwork.kt` | Собственная реализация загрузчика изображений на `HttpURLConnection` вместо Coil 3. | Отсутствует интеграция с Compose Lifecycle (загрузка не отменяется при быстром скролле), нет поддержки векторных форматов и прогрессивного WebP. |
| **DEBT-05** | Python Backend | Предупреждения `ResourceWarning: unclosed database in <sqlite3.Connection>`. | Внедрить строгий контекстный менеджер `with sqlite3.connect(...)` во всех провайдерах и тестах. |

### 2.3 Deprecated Code (Устаревший код)

*   **`ZonaMediaProbe.kt:7, 73`**: Использование устаревшего в Media3 класса `MetadataRetriever`. Требуется замена на актуальный API Media3 интроспекции треков.
*   **`zona_legacy_adapters.py` (143 КБ)**: Огромный пласт мертвого кода реверс-инжиниринга Zona Desktop/Mobile, эндпоинты которого возвращают HTTP 500/403. Подлежит удалению или изоляции.
*   **Легаси-миграция Room в `MoviaContent`**: Код проверки `needsRoomLibraryMigration()` и физического удаления `catalog.db` в `CatalogInitProvider.kt` актуален только для перехода с версии 0.7/0.8 и избыточен для релиза 1.0.0.

### 2.4 Потенциальные точки отказа (Single Points of Failure)

1.  **SPOF локального бэкенда (`streamer.py:8888`):** Movia является тонким клиентом. Если бэкенд убит системой Android (LMK) или не запущен через Termux Boot, приложение превращается в «белый лист». В приложении отсутствует встроенный watchdog или перезапуск бэкенда.
2.  **Монополия балансера Collaps:** Онлайн-просмотр HLS почти на 100% зависит от стабильности зеркал Collaps. Смена шифрования или защита Cloudflare обрушит все онлайн-потоки приложения.
3.  **Экстремальные размеры буфера Media3:** `minBufferMs = 300_000` (5 мин) и `maxBufferMs = 360_000` (6 мин). На тяжелых потоках (4K / REMUX) буферизация 6 минут в RAM может потребовать до 600–800 МБ памяти, провоцируя Android LMK на мгновенное уничтожение процесса Movia.
4.  **Блокировки публичных трекеров:** При отсутствии VPN-соединения операторы связи блокируют `rutor`/`apibay`, лишая P2P-ветку резервных раздач.

---

## 3. Анализ зависимостей

### 3.1 Критически важные модули (Must Keep)

| Модуль / Компонент | Назначение | Обоснование критичности |
| :--- | :--- | :--- |
| `androidx.media3:media3-exoplayer:1.9.3` | Ядро воспроизведения | Базовый движок проигрывателя видео и аудио. |
| `androidx.media3:media3-exoplayer-hls:1.9.3` | HLS-модуль | Воспроизведение `.m3u8` онлайн-балансеров (ADR-287). Без него онлайн-видео не работает. |
| `androidx.media3:media3-exoplayer-dash:1.9.3` | DASH-модуль | Воспроизведение адаптивных потоков `.mpd`. |
| `androidx.media3:media3-session:1.9.3` | Медиа-сессия | Системные уведомления, управление с гарнитуры, интеграция с PiP. |
| `androidx.compose.material3:material3` | Дизайн-система | Вся верстка интерфейса, анимации, темы, модальные окна. |
| `androidx.room:room-runtime / ktx:2.8.4` | Локальное хранилище | Избранное, История, Прогресс, Пользовательская библиотека. |
| `androidx.datastore:datastore-preferences:1.2.1` | Настройки | Неблокирующее реактивное хранилище конфигураций. |
| `projects/media-parser/collaps_provider.py` | Онлайн-провайдер | Главный действующий источник адаптивных потоков и дубляжей. |
| `projects/media-parser/streamer.py` | Шлюз и диспетчер | Маршрутизация запросов, P2P-мост, кэширование метаданных. |
| `app.movia.android.agent` + `termux-mcp` | Контур автоматизации | Автономное тестирование, Jarvis-интеграция, диагностика. |

### 3.2 Избыточные модули и компоненты под удаление (Prune / Remove)

1.  **Плагин `kapt`**: Удалить в пользу KSP.
2.  **`zona_legacy_adapters.py` (143 КБ)**: Полностью вычистить нефункционирующие парсеры старых версий Zona.
3.  **Все файлы `.bak-*` и `.orig`**: Удалить из дерева исходников приложения и бэкенда.
4.  **Устаревшие вспомогательные скрипты**:
    *   `clean_catalog_russian_only.py`, `step1_find_existing_key.py`, `content_filler.py.orig`, `catalog_api.py.orig`, `database.py.orig`, `streamer.py.orig`.
5.  **Легаси-код удаления старой БД в `CatalogInitProvider.kt`**: Удалить логику проверки файла 376МБ `catalog.db`.

---

## 4. План стабилизации перед релизом 1.0.0 (Roadmap)

### Матрица зависимостей задач

```
[Этап 1: Очистка и сборка]
   ├── TASK-1.1: Удаление .bak, .orig и устаревших скриптов
   ├── TASK-1.2: Миграция Room с Kapt на KSP
   └── TASK-1.3: Рефакторинг DemoCatalogRepository -> HttpCatalogRepository
             │
             ▼
[Этап 2: Отказоустойчивость воспроизведения и памяти]
   ├── TASK-2.1: Оптимизация буферов LoadControl (300с -> 90с/120с)
   ├── TASK-2.2: Изоляция вызовов каталога в CoroutineScope / IO (защита от ANR)
   ├── TASK-2.3: Кэширование ошибок в MoviaArtworkLoader
   └── TASK-2.4: Замена deprecated MetadataRetriever в ZonaMediaProbe
             │
             ▼
[Этап 3: Надежность бэкенда и резервирование провайдеров]
   ├── TASK-3.1: Watchdog/Healthcheck демона :8888 из Android-приложения
   ├── TASK-3.2: Добавление второго резервного онлайн-балансера (HDRezka/Alloha)
   └── TASK-3.3: Закрытие соединений SQLite в тестах и воркерах
             │
             ▼
[Этап 4: Финализация релиза 1.0.0]
   ├── TASK-4.1: Прогон полного цикла приемочных тестов (01-07 Acceptance)
   ├── TASK-4.2: Оформление ADR-300: Финальная архитектура Movia 1.0.0
   └── TASK-4.3: Сборка релизного APK (v1.0.0, build 310) и Zero-Click Deploy
```

### Порядок выполнения работ

#### Фаза 1: Инженерная гигиена и сборка (Срок: 1 день)
1.  **Очистка репозитория:** Удалить все файлы `.bak` и `.orig`, добавить правила исключения в `.gitignore`.
2.  **Миграция на KSP:** Подключить плагин KSP, заменить `kapt("androidx.room:room-compiler:2.8.4")` на `ksp(...)`, устранить предупреждения компилятора Kotlin.
3.  **Приведение нейминга:** Переименовать `DemoCatalogRepository` в `HttpCatalogRepository`.

#### Фаза 2: Отказоустойчивость UI и воспроизведения (Срок: 2 дня)
1.  **Настройка буферизации Media3:** Снизить `minBufferMs` до 60 000 мс (1 минута) и `maxBufferMs` до 120 000 мс (2 минуты). Это предотвратит падения по LMK/OOM на мобильных устройствах при просмотре длинных видео высокой четкости.
2.  **Асинхронность в UI:** Обернуть вызовы `HttpCatalogRepository.getPopular()` в `SearchScreen.kt` в `LaunchedEffect(Unit)` с записью в `MutableState` для исключения блокировки UI-потока.
3.  **Отказоустойчивость картинок:** В `MoviaArtworkLoader` добавить кэширование пустых/ошибочных результатов на 60 секунд для предотвращения шторма запросов к упавшим CDN.

#### Фаза 3: Надежность бэкенд-шлюза (Срок: 2 дня)
1.  **Самовосстановление связи:** Добавить в `DomainPlaybackResolver` перехват `ConnectException` с отправкой broadcast-сигнала для перезапуска локального сервиса потоков.
2.  **Резервный провайдер:** Зафиксировать интеграцию резервного балансера (Rezka зеркала) на случай недоступности серверов Collaps.
3.  **Ревизия SQLite:** Закрыть утечки соединений в `test_collaps_provider.py` и `streamer.py`.

#### Фаза 4: Верификация и выпуск релиза 1.0.0 (Срок: 1 день)
1.  Прогон модульных тестов: `./gradlew testDebugUnitTest` (цель: 82/82 PASS).
2.  Прогон сквозной приемки: `bash acceptance/01_headless_cold.sh` — `python3 acceptance/07_final_acceptance.py` (цель: 24/24 PASS).
3.  Публикация архитектурного решения `ADR-300-release-1.0.0-architecture-stabilization.md`.
4.  Сборка релизного артефакта `v1.0.0` и автономная установка через Shizuku.

---

## 5. Заключение

Проект **Movia** находится в высокой степени готовности к релизу **1.0.0**. Архитектура разделения обязанностей между компактным Compose-клиентом и локальным стриминговым шлюзом полностью оправдала себя:
- Приложение сохраняет минимальный размер дистрибутива (~23 МБ);
- Реализована полноценная поддержка HLS-стриминга с динамическим переключением множества вариантов русской озвучки и качества;
- Контур управления через агентский API (`127.0.0.1:8899`) и Jarvis MCP обеспечивает 100% покрытие приемочными тестами без участия пользователя.

Выполнение предложенного плана стабилизации позволит полностью исключить риски OOM/LMK, устранить устаревший код и гарантировать наивысшую стабильность пользовательского опыта в финальной версии 1.0.0.
