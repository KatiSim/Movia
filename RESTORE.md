# Movia — RESTORE

Эта инструкция рассчитана на нового агента. Она не зависит от памяти предыдущего агента и не разрешает восстанавливать отсутствующие компоненты по догадкам.

## 1. Clone repository

~~~bash
git clone https://github.com/KatiSim/Movia.git Movia
cd Movia
git checkout main
~~~

Перед работой проверить remote, branch и baseline:

~~~bash
git remote -v
git status --short --branch
cat CURRENT_BASELINE.json
~~~

## 2. Required Termux packages

Установить только необходимые пакеты:

~~~bash
pkg update
pkg install git curl jq openssl python node
~~~

Для Android build нужны Java 17, Android SDK/Build Tools и Gradle Wrapper. Не копировать `.gradle/` или build outputs из другого проекта.

## 3. Android/Gradle

~~~bash
java -version
./android/gradlew -p android --version
./android/gradlew -p android assembleDebug
~~~

Если Gradle/SDK не настроены, установить их отдельно и сохранить только инструкции/config.example; реальные credentials/keystore не помещать в Git.

## 4. Backend

Ожидаемый backend — media-parser. Сначала открыть:

~~~bash
cat backend/STATUS.md
cat reference/CURRENT_PHONE_ASSETS.json
~~~

На текущем checkpoint backend source не найден. Восстановление возможно только из проверенного source snapshot или из release backup artifact с совпадающим checksum. Не создавать backend из памяти.

## 5. Catalog

Schema находится в `database/schema` и ссылается на Android Room schemas. Mutable `catalog.db` в Git не хранится.

~~~bash
scripts/restore-check.sh
scripts/create-baseline.sh --db /absolute/path/catalog.db
~~~

Если есть release DB snapshot, проверить SHA256SUMS и только потом развернуть его в согласованный runtime path. Не заменять пользовательские данные без отдельной проверки.

## 6. Configure services

Прочитать `agent/STATUS.md` и backend status. Скопировать только проверенные service definitions в локальную runit/service directory. Не запускать неизвестные сервисы и не использовать общий Chipupa service как Movia backend.

## 7. Configure secrets

~~~bash
cat SECRETS_SETUP.md
cp .env.example .env
~~~

Реальные token/cookies/API keys/credentials вводятся только в локальное secret storage. Они не должны попадать в Git diff, logs, manifests или release assets.

## 8. Build APK

~~~bash
./android/gradlew -p android testDebugUnitTest
./android/gradlew -p android lintDebug
./android/gradlew -p android assembleDebug
~~~

Перед публикацией записать versionName/versionCode автоматически:

~~~bash
scripts/create-baseline.sh --apk android/app/build/outputs/apk/debug/app-debug.apk
~~~

## 9. Install APK

~~~bash
scripts/install.sh android/app/build/outputs/apk/debug/app-debug.apk
~~~

Установка выполняется с сохранением данных; `pm uninstall`, очистка данных и force install не являются частью restore.

## 10. Provision agent token

Положить токен в локальное secret storage согласно `SECRETS_SETUP.md`. В репозитории хранится только `.env.example` и описание переменной.

## 11. Start services

Запускать только после успешных backend/agent health checks:

~~~bash
scripts/health-check.sh
~~~

## 12. Verify health

~~~bash
scripts/verify-project.sh
scripts/restore-check.sh
~~~

Ожидается явный `PASS`. `NOT_FOUND`, `NOT_VERIFIED` и timeout означают FAIL.

## 13. Verify MCP

Проверить наличие актуального Movia Agent API и native MCP tools по контракту под `agent/`. Не считать общие Termux MCP tools Movia-native tools.

## 14. Verify installed version

Сверить package, versionName, versionCode и SHA256 APK с `CURRENT_BASELINE.json`. Если установленный пакет отсутствует или версия отличается, baseline не считается восстановленным.
