# [ADR-288] Cloud-first hybrid backend для Movia
**Дата:** 2026-09-08
**Статус:** Accepted for implementation

## 1. Контекст

После Blocks 0–5 локальный playback стабилизирован, но массовый background enrichment оказался главным источником лишнего мобильного трафика и постоянной нагрузки Termux. Финальная защита фонового трафика уже действует: unmetered Wi-Fi работает без byte-cap, mobile ограничен safety-порогом 4.2 GiB/месяц, bulk torrent lookup отключён, для отрицательных результатов действует retry backoff.

Фактический runtime на телефоне на момент решения:

- backend tree: ~961 MiB;
- `catalog.db`: ~758 MiB;
- строк `movies`: 70 367;
- `streamer.py`: ~40 MiB RSS;
- idle CPU: около 1%;
- backend health: HTTP 200 на `127.0.0.1:8888`.

Вывод: HTTP/API часть дешёвая. Основная нагрузка — provider polling, metadata enrichment, catalog refresh и torrent fan-out. Поэтому переносить нужно control plane, а не видеотрафик.

## 2. Решение

Принять **cloud-first hybrid** архитектуру.

```text
                 CLOUD CONTROL PLANE
        catalog / metadata / delta updates
      direct-stream discovery + URL cache
        provider reliability / health state
              scheduled enrichment
                      |
                      v
                  Movia API
                      |
                      v
                 Android Movia
                 /           \
                /             \
     direct HLS/CDN         local P2P fallback
 provider/CDN -> Android    strictly on-demand
```

### Обязательные правила

1. Облако **не проксирует HLS/video payload** в штатном direct-playback пути.
2. Android получает metadata, direct stream URL/manifest, реальные quality/audio данные и provider health.
3. Media идёт напрямую `provider/CDN -> Android`.
4. Bulk metadata/stream enrichment после cutover выполняется в облаке, а не на телефоне.
5. Torrent/P2P на первом этапе остаётся локальным строго on-demand fallback. Перенос torrent streaming на VPS запрещён как базовая архитектура, потому что это превращает сервер в медиапрокси и создаёт большой outbound traffic.
6. Signed stream URL хранится только до explicit expiry; expired URL не выдаётся клиенту.
7. Provider credentials/secrets остаются только server-side и не встраиваются в APK.

## 3. Выбор hosting model

### 3.1 VPS — выбран для первого production backend

Причины:

- SQLite можно перенести без немедленной смены СУБД;
- фоновые worker-процессы и scheduler работают без serverless lifecycle-ограничений;
- предсказуемый диск;
- простой перенос существующего Python runtime;
- низкая стоимость при текущем объёме;
- легко ограничить CPU/network и отключить P2P media path.

Стартовый sizing:

- 1–2 shared vCPU;
- 2–4 GiB RAM;
- 40+ GiB SSD/NVMe;
- EU region;
- отдельный persistent volume не обязателен, если системный диск >=40 GiB и backup вынесен отдельно.

### 3.2 Container hosting — не выбран как основной сейчас

Render и аналогичные платформы упрощают deploy, health checks и cron, но для Movia сейчас дают худшее соотношение цена/контроль. На Render 1 CPU / 2 GB стоит $25/месяц, persistent disk — $0.25/GB/месяц; на Hobby включено 5 GB outbound, затем $0.15/GB.

Это рабочий вариант для staging, но production backend с SQLite + постоянными workers дешевле и проще разместить на VPS.

### 3.3 Serverless — не выбран для текущей stateful версии

Cloud Run подходит для stateless API и jobs и имеет бесплатный месячный tier, но текущая архитектура использует mutable SQLite и фоновые процессы. Для корректного serverless-размещения пришлось бы одновременно вводить отдельную managed DB/object storage и перестраивать scheduler/worker lifecycle.

Это увеличивает число изменений в одном релизе без доказанной необходимости. Serverless можно рассматривать после отделения catalog storage от локального SQLite файла.

### 3.4 Hybrid phone/cloud — выбран как переходная и целевая playback-схема

Cloud принимает всю фоновую и discovery-нагрузку. Телефон сохраняет только пользовательский playback и временный on-demand P2P fallback. Такой вариант минимизирует риск регрессии direct-vs-P2P, не требует проксировать видео через облако и позволяет постепенно убрать постоянный Termux runtime.

## 4. Стоимость: snapshot на 2026-09-08

Цены ниже — ориентиры официальных pricing pages на дату ADR; перед фактическим provisioning их нужно проверить ещё раз.

| Вариант | Ресурсы / модель | Базовая цена | Комментарий |
|---|---|---:|---|
| Hetzner CX23 | 2 vCPU, 4 GB, 40 GB NVMe, EU | €5.49/mo excl. VAT + €0.50 IPv4 | 20 TB EU traffic; cost-optimized capacity на текущей странице может быть временно недоступна |
| Hetzner CAX11 | 2 ARM vCPU, 4 GB, 40 GB NVMe, EU | €5.99/mo excl. VAT + €0.50 IPv4 | backend уже исполняется на arm64 Termux, поэтому ARM технически естественный кандидат, но image/dependencies нужно проверить |
| DigitalOcean Basic | 1 vCPU, 2 GB, 50 GB SSD | $12/mo | 2 TB transfer; хороший fallback, если Hetzner capacity недоступна |
| Render | 1 CPU, 2 GB | $25/mo + disk | проще deploy, но дороже; Hobby bandwidth 5 GB included, затем $0.15/GB |
| Cloud Run | request-based | usage-based | compute может быть очень дешёвым при малой нагрузке, но stateful DB/jobs потребуют отдельной архитектуры и отдельной стоимости |

Предпочтение: **Hetzner EU small VPS при наличии capacity; DigitalOcean 2 GB как простой fallback**. Не использовать сервер для проксирования video bytes.

## 5. Storage strategy

На первом cloud этапе сохранить SQLite как SSOT:

- один canonical `catalog.db`;
- WAL только локально на VPS;
- API readers + ограниченный single-writer/scheduled writer policy;
- регулярный SQLite-consistent backup на независимое storage;
- runtime DB, WAL, backups и secrets не входят в Git.

Переход на PostgreSQL выполнять только при одном из условий:

- появляются несколько независимых writers;
- требуется horizontal scaling API;
- SQLite lock contention становится измеримой проблемой;
- требуется HA/failover, который нельзя обеспечить single-node моделью.

## 6. Background processing после cutover

В облако переносятся:

- metadata delta updater;
- direct-stream enrichment для ограниченной priority выборки;
- provider reliability state;
- stream URL cache;
- catalog change-feed/delta processing;
- scheduled maintenance/backoff retries.

Не переносится в bulk:

- torrent media download;
- full catalog torrent scanning;
- HLS proxy/cache;
- full-media offline storage.

Приоритет discovery:

```text
user opens/plays title
        |
        v
cloud cache valid? -- yes --> return direct candidate
        |
        no
        v
bounded resolver -> cache result -> return candidate
        |
        no direct source
        v
local on-demand P2P fallback (temporary)
```

Background enrichment используется только для небольшой priority очереди: recently opened, popular, upcoming continue-watching и свежие catalog changes. Полное циклическое пересканирование каталога запрещается.

## 7. Migration plan

### Phase A — deployable cloud backend

1. Добавить reproducible container/service definition для backend API и workers.
2. Перенести копию `catalog.db` в staging VPS.
3. Запустить API, metadata updater, direct resolver/cache и provider reliability state.
4. Отключить cloud torrent media path.
5. Настроить health, logs, bounded worker concurrency и backups.

### Phase B — parity gate

Перед переключением Android cloud endpoint должны пройти:

- catalog counts/schema parity;
- metadata sample parity;
- direct resolver control titles;
- RU/UK quality/audio contract;
- signed URL expiry tests;
- provider circuit/recovery tests;
- startup budget <=10 s на контрольной выборке.

### Phase C — Android cloud-first

1. Android вызывает cloud API для catalog/metadata/direct streams.
2. Direct media URL передаётся Media3 напрямую.
3. При cloud `NO_SOURCE` разрешён локальный on-demand P2P fallback.
4. Старый phone background enrichment выключается feature flag'ом, но rollback остаётся возможным до acceptance gate.

### Phase D — release gate

До окончательного удаления phone background runtime:

- 100 случайных фильмов: 100% acceptance;
- отдельная большая series sample: 100% acceptance;
- no HLS proxy through VPS;
- mobile background provider polling = 0;
- phone idle Termux enrichment workers = 0;
- cloud health/recovery/backup проверены.

## 8. Что не делаем сейчас

- не мигрируем SQLite в PostgreSQL одновременно с первым cloud cutover;
- не запускаем Kubernetes;
- не используем VPS как CDN/video proxy;
- не переносим torrent download/enrichment в постоянный облачный background;
- не удаляем локальный P2P fallback до cloud acceptance.

## 9. Источники pricing snapshot

Проверено 2026-09-08 по официальным страницам:

- Hetzner Docs — Price Adjustment 15 June 2026;
- Hetzner Cloud — Cost-Optimized / server plan specifications;
- Hetzner Docs — Primary IPv4 pricing;
- DigitalOcean — Droplet Pricing;
- Render — Pricing / Compute / Persistent Disks;
- Google Cloud — Cloud Run pricing.

## 10. Итог

Для текущего Movia выбран **малый EU VPS + cloud-first hybrid playback**. Это минимальный архитектурный шаг, который полностью убирает массовый catalog/provider polling с телефона, сохраняет прямой CDN playback и не создаёт новый огромный cloud outbound bill.
