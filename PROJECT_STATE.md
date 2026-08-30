# Movia — PROJECT_STATE

Дата и значения baseline генерируются скриптом `scripts/create-baseline.sh`. Этот файл описывает только подтверждённое состояние checkpoint; отсутствие данных обозначается `NOT_FOUND` или `NOT_VERIFIED`.

## CURRENT VERSION

- Source versionName: см. `CURRENT_BASELINE.json`, извлекается из `android/app/build.gradle.kts`.
- Source versionCode: см. `CURRENT_BASELINE.json`, извлекается автоматически.
- Installed version: `NOT_FOUND` на проверенном Termux/Android endpoint.
- Package name: извлекается автоматически; ожидаемый namespace source — `app.movia.android`.

## ANDROID

- Status: source baseline присутствует под `android/`.
- Gradle project: присутствует.
- Последняя проверка `testDebugUnitTest`: `FAIL` — Android SDK location not found (`ANDROID_HOME`/`sdk.dir` не задан).
- Recovered phone artifact: `android/recovered-jadx` — ссылка на `~/movia_src`; это декомпилированный Java-вывод, не заменяющий buildable Kotlin source.

## BACKEND

- Status: `NOT_FOUND` в проверенных разрешённых корнях.
- Current file: не установлен.
- Restore: предоставить текущий media-parser source и его точный путь; не создавать новый backend по памяти.

## CATALOG

- Row count: `NOT_FOUND`; catalog.db на телефоне не найдена.
- Schema version: определяется автоматически из `database/schema`.
- Current SSOT: versioned Room schema/source в репозитории; runtime catalog.db отсутствует в checkpoint.
- Декомпилированный catalog-файл хранится вне Git и только описывается manifest-файлом.

## PLAYBACK

Подтверждено наличием source-компонентов: Media3 PlayerScreen, PlaybackSession, PiP/UI и тестовые заготовки.

Известные ранее зарегистрированные проблемы не считаются исправленными без воспроизведения:

- рассинхронизация выбранного качества/озвучки и фактически активного stream;
- timeout при наличии найденного stream;
- артефакты/разрушение изображения после переключения stream;
- отсутствие доказанного полного playback acceptance на текущем checkpoint.

## SEARCH

- Source search implementation: присутствует в Android source.
- Live backend/discovery search: `NOT_VERIFIED`, backend отсутствует.

## AGENT

- Status: `NOT_FOUND` на проверенном телефоне.
- Native Movia MCP tools: `NOT_FOUND`.
- Контракты восстановления находятся под `agent/` и не являются рабочим runtime.

## SERVICES

- Общие Termux/Chipupa services работают отдельно.
- Movia backend/agent service definitions на checkpoint не найдены.
- Запуск неизвестных сервисов не выполняется автоматически.

## KNOWN ISSUES

1. Backend media-parser отсутствует в доступных корнях.
2. catalog.db и проверенный snapshot отсутствуют.
3. Installed Movia APK отсутствует.
4. Movia Agent/MCP runtime отсутствует.
5. Playback identity/fallback issues зарегистрированы исторически и не имеют PASS на этом checkpoint.
6. Репозиторий содержит source baseline, но не доказывает восстановление всей runtime-системы.
7. На телефоне отсутствует настроенный Android SDK, поэтому локальная Gradle-проверка APK не прошла.

## LAST VERIFIED BASELINE

- Git source commit и дата: см. `CURRENT_BASELINE.json`.
- Remote pre-sync backup: branch `legacy-before-current-sync`.
- Tag/release создаются только после diff/test/secret-scan verification.
