# Movia: час с 06:50:53 UTC, 8 октября 2026

Системные изменения: MediaSource сохраняет собственный immutable request profile и offline factory, включая позднее создание HLS/DASH DataSource. Позиция передаётся атомарно при установке MediaSource. Startup watchdog требует фактического первого кадра; READY недостаточно. Decoder startup timeout классифицирован как временный. Фактический кадр отменяет pending recovery; поздний ответ защищён подготовкой и frame epoch, устаревший coroutine не очищает новый recovery job. Backend получает время исходной ошибки. Manual quality/Auto принимает ID подготовленного источника, сохраняя остальные поля запроса.

SourceLoadEvidence содержит максимум 32 безопасных события OPEN/ERROR/CLOSE, phase по suffix, bytes, elapsed и HTTP code/class. URL, headers и exception text не публикуются. OPEN/bytes не доказывают decoder playback. Сравнение URI в Player.Listener не доказывает происхождение запоздалого renderer callback; строгая event-time/media-period attribution и same-URI/audio сценарии требуют отдельной проверки.

319 Android unit и 561 backend прошли. Instrumentation compiled, не executed. APK 0.0.1 /335 установлена без очистки данных. SHA ecc032ab6347e69819854fdbc88775a3aa0246db1ca1b9941ca5fcdb21d756b6. Live native feedback: False; exact switching matrix: False; direct download/offline/seek: True. Все неудачные сценарии сохранены. Отдельная проверка manual Auto/существующего качества после реального fallback — selection-after-fallback-proof.json; она не доказывает переход на другое разрешение.

Накопленный журнал: 277/500 карточек разных APK. Gate: False. Текущая APK отдельно: 40 карточек. Новый observation budget 45 секунд; timeout не доказывает постоянную недоступность или исчерпание полного recovery window. Прежние ошибки остаются до повторной проверки.

На двух сериях HDRezka search вернул HTTP500. Это не доказывает недоступность article/player и не объясняет автоматически все discovery errors. В API двух карточек reloadSupported не сопровождается сохранённой article reference/reload_data; force-refresh идёт через поиск. Прямое обновление по доказанной known article reference требует отдельного изучения и сохранения reference при публикации; URL по hash не угадываем. Новую тысячу IDs в этом часе не создавали.

SourceTruth сохраняет один request profile на physical URI: migration для нескольких профилей остаётся. Также остаются 500-card audit, транспортная матрица дорожек/качества/серий/offline, gates/references, legacy wrapper parity/removal, CPU/RAM/queues/startup <=5s и финальная проверка проекта. Подробные 32 статуса — plan-32-status.json. Проект не завершён.

## Откат

Checkpoint 3442 /b192f5664ff758a1f5f2c1cc6e9b90f94d6542b8. APK: ~/.cache/movia-architecture-20261002/hour-0850-rollback-3442.apk, SHA 7592ab9e479a6dcd8f15a7f6eea8f521ef6f118b6c9c1febfe0aae9ddad23ddb. pm install -r -d сохраняет данные; проверить SHA. Backend в этом часе не изменён.

20 несвязанных inputs сохранены без изменений и не включены в commit. Архив hour-0850-rebuild-inputs.zip, SHA d9586edc7e8f543fb58ca5658e6cbfe56a0b01d07e2f02d3d8daa2188d633891; он нужен для повторной сборки, идентичность байтов не обещается. WARP не изменён, physical UI не использован.
