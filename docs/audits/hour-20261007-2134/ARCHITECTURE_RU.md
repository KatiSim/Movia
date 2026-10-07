# Movia: системные изменения часа 21:34 UTC, 7 октября 2026

Основа — checkpoint 3441 /1238a6d72b4e7b364ac05688492e70fb4d798435. Номера каталога используются только в живых проверках. В runtime нет исключений для отдельных фильмов.

| Общая причина | Исправление | Где реализовано |
|---|---|---|
| Кешированный native leaf без sourceId не отправлял ошибку | Authenticated native-variant-failure принимает точные catalog ID, S/E, logical stream ID и два fingerprints; URL от клиента не принимается | PlaybackSession, native_variant_feedback |
| Поздний ответ первого кадра не возвращал sourceId в Android | sourceId прикрепляется только к совпавшему запросу, поколению и подготовке; повторно публикуются options | NativeVariantFeedback, PlaybackSession |
| Ошибка или кадр могли пересечься с обновлением профиля | Проверка профиля, insert и запись native success/failure выполняются в SQLite write transaction; обновлённый профиль не получает старые результаты | PlaybackAvailabilityService |
| Старый кадр мог стереть более новую ошибку | Новый native кадр передаёт observedAt; сравниваются lastSuccessAt/lastFailureAt, observationApplied показывает применение | native_variant_feedback, playback_availability_index |
| После восстановления успешный кадр нового источника мог быть потерян | DecoderFeedbackGate относится к каждой подготовке, включая повтор того же URL; задержка считается от этой подготовки | DecoderFeedbackGate, PlaybackSession |
| Registry превращал outcome в простой список | ProviderDiscoveryOutcome сохраняет status, error_count и ссылки на незавершённые задачи; ResolvedStreams сохраняет совместимость со списком и несёт trace | provider_discovery, discovery_outcome, streamer |
| Поздняя ошибка превращалась в отсутствие вариантов | Очередь читает поздние исходы без transport I/O; PENDING, ERROR и завершённое отсутствие вариантов различаются | DiscoveryTrace, DiscoveryQueue, CatalogStreamService |
| Число автоматических повторов росло с inventory | Список вариантов сохраняется целиком; новый автоматический prepare допускается не более шести раз и только в 60-секундном окне | PlaybackRecoveryBudget, PlaybackSession |
| Late enrichment снова запускал исчерпанный путь | Исчерпанный путь сохраняет новые варианты для ручного выбора; автоматический switch наследует бюджет | acceptDiscoveredCandidates, switchToStream |

## Контракт feedback

Media3 отправляет только наблюдение фактического запуска или ошибки и точную область выбранного leaf. Backend сам загружает карточку и применяет общий content/episode filter и identity binding. Единственный совпавший вариант допускается к записи; другой фильм, серия, logical ID, URL или request profile отвергается.

Новая ошибка без sourceId создаёт DISCOVERED source и фиксирует реальную неудачу. Она не создаёт VERIFIED, измеренное качество, аудиодорожки или lastSuccessAt. NETWORK получает COOLDOWN, NON_NETWORK — FAILED. Пользовательская отмена/stop не считается ошибкой источника.

Успех и ошибка сравнивают timestamps одного физического источника. sourceId-only старые feedback endpoints сохранены для совместимости. Старый APK без observedAt использует время получения; точный порядок событий подтверждается для APK этого часа.

Fingerprint существующего формата включает URL отдельно и headers, userAgent, transport, audio/video indexes, file index/path, DRM license URL в canonical JSON. DRM scheme в этот формат ещё не входит; полноценная DRM-матрица и версия fingerprint остаются обязательной работой перед таким адаптером. Нельзя объявлять этот формат доказательством проверки всех DRM-путей.

## Discovery и доступность

Catalog GET читает inventory и ставит точный ключ в ограниченную очередь. Provider outcome проходит registry → resolver → worker → очередь. Незавершённые outer/inner futures сохраняются в trace. Поздний результат проверяется и публикуется существующим identity-bound callback; пустой исход несёт статус и не создаёт synthetic leaf.

Ожидание имеет отдельный PENDING. Завершённая ошибка/отказ executor — ERROR. Истечение 30-секундного окна trace — DISCOVERY_TIMEOUT, не доказательство внешней недоступности. Готовые варианты остаются READY даже при ERROR другого discovery; discoveryStatus и providerErrorCount показывают частичную ошибку отдельно.

Trace держит только фиксированные статусы/счётчики после завершения futures и не публикует URL, headers или exception messages. Очередь по-прежнему имеет 2 workers, 64 pending keys и 128 remembered jobs. Внутренний provider executor на 24 workers существовал ранее; его собственная очередь и общий resource audit ещё не закрыты.

Некоторые нижележащие старые transport functions всё ещё могут скрывать причину отказа. Это исправление устраняет подтверждённую потерю outcome на нативной границе и future exception wrappers; оно не доказывает, что каждый внешний отказ полностью классифицирован.

## Бюджет восстановления

Шесть автоматических подготовок и окно 60 секунд ограничивают новые попытки одного эпизода отказов. Уже выполняющаяся операция завершается по собственному timeout; это не обещание окончания playback строго за 60 секунд и не подтверждение цели старта ≤5 секунд.

Успешный первый кадр и явное действие пользователя сбрасывают бюджет. Late discovery, рост числа вариантов и автоматический switch бюджет не увеличивают. Inventory не ограничивается шестью leafs. Специальных правил по названию или ID нет.

## Проверки и оставшиеся ворота

Актуальные количества тестов, installed SHA и результаты живых сценариев записываются в hour-summary.json после всех проверок. Instrumentation компилируется, но не объявляется выполненным. Разные итерации APK не смешиваются.

Android 500 journal продолжается с прежнего состояния. Старые NO_NATIVE_TERMINAL повторяются под новым discovery contract. DISCOVERY_ERROR отделён от harness/request errors. Для реальных playback attempts дополнительно сохраняются fingerprints и Source Truth до/после; source URLs и capability не публикуются.

Остаются полный 500-прогон, точные live voice/audio/quality/subtitle/episode/offline матрицы каждого транспорта, актуальные доступные references/gates провайдеров, контролируемые CPU/RAM/start измерения и удаление заменённых legacy wrappers после проверки соответствия. Две прежние 1000-ID выборки остаются inventory/enrichment аудитами; их negative classifications требуют осторожной интерпретации после изменения discovery. Новая тысяча в этом часе не создавалась.


## Оставшееся ограничение хранения доказательств

Уникальный ключ SourceTruth сейчас относится к физическому URI (media_key, provider, locator_hash), а запись хранит один request_profile_hash. Несколько законных представлений одного URI с разными audio/video/file indexes могут конкурировать за эту запись или отклоняться строгой проверкой профиля. Каталог сохраняет варианты, но хранение доказательств для всей такой матрицы ещё не завершено. Нужны отдельные записи по профилям и связь с logical variant, отличающая параллельное представление от обновления профиля. Миграция этого часа не выполнялась; полноценная матрица дорожек и torrent-файлов остаётся открытой.


## Граница recovery budget

Бюджет ограничивает recovery reload/fallback и автоматические переключения, наследующие этот бюджет: максимум шесть новых допусков в 60-секундном окне после первой попытки восстановления. Первоначальная пользовательская подготовка сюда не входит. Существующий startup discovery handover имеет отдельный одноразовый guard и сейчас не расходует этот бюджет. Поэтому число шесть не заявляется как общий предел абсолютно всех подготовок приложения. Уже начатая операция завершается по собственному timeout; 60 секунд не являются строгим сроком окончания всего восстановления.
