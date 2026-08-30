# MOVIA — главный паттерн работы Android-агента

Статус: обязательный регламент проекта. Применять перед каждой задачей, изменением версии и установкой APK.

## Главный принцип

Новая версия только добавляет изменения. Рабочую старую версию, её APK и пользовательские данные нельзя удалять, очищать или молча заменять до успешной проверки новой версии.

Архив версий:
`/data/data/com.termux/files/home/MoviaApp/Movia/Версии Movia/`

Правила архива:

- папка создаётся один раз и ведётся append-only;
- каждая версия хранится в отдельной подпапке: `Версии Movia/<versionName>/`;
- существующие APK и подпапки не перезаписывать и не удалять;
- после успешной сборки APK сразу копировать в архив;
- рядом сохранять SHA-256 и краткую запись: версия, package name, commit, задача;
- до установки новой версии должна существовать точка отката предыдущей версии.

## Этап 0 — принять задачу

Не писать код сразу. Определить экран, компонент, архитектуру, текущую версию и ожидаемое поведение.

## Этап 1 — проверить проект

Через @Jarvis:

- проверить окружение и доступ;
- найти актуальную копию проекта;
- проверить `app/`, `build.gradle.kts`, `settings.gradle.kts`, `gradlew`;
- не удалять неизвестные файлы и незакоммиченные изменения.

Основной проект: `~/projects/viora`.
Собираемая Git-копия: `~/MoviaApp/Movia`.
При конфликте копий сначала сравнить versionName, commit, package, статус Git и результат сборки.

## Этап 2 — найти компонент

Использовать поиск по исходникам, а не менять случайный файл:

- `grep`/поиск по имени компонента;
- открыть найденный файл;
- определить существующую архитектуру и слой состояния.

## Этап 3 — проверить связанные файлы

Проверять связанные:

- UI и навигацию;
- тему, цвета и токены;
- состояние/ViewModel;
- ресурсы и иконки;
- тесты и зависимости.

## Этап 4 — зафиксировать baseline

До изменения зафиксировать:

- текущую версию;
- package name/applicationId;
- commit и Git status;
- поведение старого интерфейса;
- APK и SHA-256 предыдущей версии.

## Этап 5 — минимальное изменение

Менять минимальное число файлов и переиспользовать существующие компоненты, тему и токены. Не дублировать старый компонент поверх нового без необходимости.

## Этап 6 — проверка регрессий

Проверить старые функции, связанные с изменением:

- старые вкладки и навигацию;
- существующие кнопки и состояния;
- поведение при повороте, возврате и повторном открытии;
- отсутствие потери данных.

## Этап 7 — сборка

После изменения запускать:

`./gradlew :app:assembleDebug`

При ошибке сначала исправлять ошибку сборки и не устанавливать непроверенный APK.

## Этап 8 — архив APK

После успешной сборки:

`Версии Movia/<versionName>/Movia-<versionName>-<feature>.apk`

Сохранять SHA-256 и техническую запись. Архив не очищать.

## Этап 9 — безопасная установка

Перед установкой:

1. проверить package name и versionCode/versionName APK;
2. проверить установленный старый пакет, путь и версию;
3. проверить подпись, если package name совпадает;
4. проверить Shizuku/rish: допустим `uid=2000(shell)` или root;
5. проверить, что старая точка отката сохранена.

Правила:

- если package name новый — устанавливать рядом, не затрагивая старый пакет;
- если package name тот же и подпись совпадает — только `pm install -r --user 0`;
- никогда не использовать uninstall, `pm clear`, downgrade или замену старой APK без разрешения;
- при несовпадении подписи или сомнении остановиться;
- после установки не запускать приложение автоматически.

## Этап 10 — проверка телефона

Проверить:

- результат установки;
- наличие нового package через `pm path`;
- наличие старого package;
- сохранность старых данных;
- отсутствие автоматического запуска.

После установки остановиться и ждать пользовательские скриншоты для визуальной проверки. Не делать скриншоты самостоятельно без отдельной команды.

## Этап 11 — фиксация

После успешной проверки:

- сохранить APK и SHA-256;
- сохранить commit/checkpoint;
- записать результат и ограничения;
- не удалять старую версию из `Версии Movia`.

Текущая особенность: установленный старый пакет — `app.viora.android`; APK scrim-сборки — `app.vioraa.android`. Это разные package name, поэтому их нельзя считать одной версией без отдельного решения.


## Исправление инцидента 2026-08-21: сохранение данных при обновлении

- Официальный Android package Movia: `app.viora.android`.
- `app.vioraa.android` — ошибочная боковая сборка 0.3.22 с отдельным хранилищем; не считать её обновлением официального приложения.
- Перед установкой новой версии обязательно сверять `applicationId`, versionCode/versionName и подпись APK с установленным `app.viora.android`.
- Обновление выполнять только через `cmd package install -r`/`pm install -r`; запрещены uninstall, clear и downgrade.
- Каталог `DemoCatalogRepository` не оставлять пустым: перед сборкой проверять количество записей каталога.
- Старый APK сохранять в `Версии Movia/<version>/` до установки новой версии.

## Canonical baseline 2026-08-24

The only source of truth for new Movia builds is:
  /data/data/com.termux/files/home/projects/viora

Current canonical baseline:
- package: app.viora.android
- versionName: 0.3.70
- versionCode: 176
- catalog: 157 entries (120 movies, 37 series)
- canonical APK and complete project snapshot:
  /data/data/com.termux/files/home/MoviaApp/Movia/Каноническая версия/0.3.70/
- independent duplicate:
  /data/data/com.termux/files/home/.movia-backups/0.3.70-canonical-20260824/
- parser/generator snapshot: media-parser-0.3.70/ inside both release folders

Previous verified baseline:
- versionName: 0.3.69
- versionCode: 175
- paths:
  /data/data/com.termux/files/home/MoviaApp/Movia/Каноническая версия/0.3.69/
  /data/data/com.termux/files/home/.movia-backups/0.3.69-canonical-20260824/

The source tree /data/data/com.termux/files/home/MoviaApp/Movia is an archive and must not be used as a build source. Historical 0.3.22–0.3.68 artifacts are preserved for reference, but the canonical rollback target is 0.3.70.

Before every catalog generation and build, run:
  python3 /data/data/com.termux/files/home/projects/media-parser/update_cast_photos.py
  python3 /data/data/com.termux/files/home/projects/media-parser/generate_catalog_repository.py
  python3 /data/data/com.termux/files/home/projects/viora/verify_canonical.py /data/data/com.termux/files/home/projects/viora

A build is invalid if the package is not app.viora.android, versionCode is below 176, the catalog has fewer than 157 entries, poster/backdrop fields do not cover every entry, the generated cast is not List<Person>, or actor photoUrl fields are absent. Never install an APK from an old version folder by path alone; install only the freshly verified APK from the source-of-truth build output.
