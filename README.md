# Movia

Movia — Android-клиент каталога и воспроизведения медиаконтента на Jetpack Compose и Media3.

Этот репозиторий является канонической историей проекта. Текущий фактический статус не приравнивается к «stable» или «production-ready»: незавершённые функции и непроверенные компоненты явно помечаются в [PROJECT_STATE.md](PROJECT_STATE.md).

## Current version

Версия и versionCode извлекаются автоматически из `android/app/build.gradle.kts` скриптом `scripts/create-baseline.sh` и записываются в [CURRENT_BASELINE.json](CURRENT_BASELINE.json).

Этот checkpoint отражает проверенный source baseline репозитория. Наличие установленного APK, backend, catalog.db и Movia Agent на телефоне подтверждается отдельно; отсутствующие компоненты не считаются PASS.

## Architecture

- **Android** — полный Gradle/Compose/Media3 source под `android/`.
- **Backend** — ожидаемый media-parser под `backend/`; фактический путь фиксируется manifest-файлом.
- **Catalog** — Room schema и миграции под `database/`; большая runtime-база не хранится в Git.
- **Playback** — Media3 player и playback baseline в `android/app/src/main/java/app/movia/android/ui/player/`.
- **Agent/MCP** — контракты и service definitions под `agent/`; незнайденные на checkpoint компоненты отмечены явно.

## Build

~~~bash
./android/gradlew -p android testDebugUnitTest
./android/gradlew -p android lintDebug
./android/gradlew -p android assembleDebug
~~~

Сборка не означает, что внешний playback/backend полностью проверен.

## Install

Установка APK выполняется без очистки данных:

~~~bash
scripts/install.sh path/to/Movia.apk
~~~

Скрипт требует доступный `rish`/Shizuku и использует reinstall с сохранением данных. Старые APK не помещаются в Git.

## Services

Список реально найденных сервисов и отсутствующих Movia-сервисов находится в [PROJECT_STATE.md](PROJECT_STATE.md). Общая инфраструктура Termux/Chipupa не является автоматически backend Movia.

## Verification

~~~bash
scripts/verify-project.sh
scripts/restore-check.sh
scripts/health-check.sh
~~~

Каждая проверка возвращает `PASS` или `FAIL` с причиной. Текущий checkpoint не скрывает отсутствующий backend/APK/DB.

## Baseline and backup

~~~bash
scripts/create-baseline.sh
scripts/create-baseline.sh --db /absolute/path/catalog.db
~~~

Без `--db` база не копируется. APK и DB snapshot предназначены для GitHub Release/backup artifacts, а не для обычной Git history.

## Restore

Полная инструкция восстановления находится в [RESTORE.md](RESTORE.md).
