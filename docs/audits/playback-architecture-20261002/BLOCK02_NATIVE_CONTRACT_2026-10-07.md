# Movia — блок 2: единый контракт провайдеров

Дата: 7 октября 2026. Начало 11:43:56 UTC; результат зафиксирован 2026-10-07T12:08:07.689507+00:00.

## Выполнено

- Шесть native provider handlers зарегистрированы в едином `PROVIDER_REGISTRY`; flags выводятся из него. Общая граница запроса, результата и ошибок сохраняет union результатов. Ошибка одного провайдера не скрывает другие.
- HDRezka подключён через настоящий `DeferredVariantLoader`: создание article/tree не выполняет HTTP; загрузка происходит при обходе выбранного дерева, результат memoized. Запрос другого фильма/года/серии не может повторно использовать loader; другая серия отсекается до HTTP.
- В общем backend leaf и Android неизвестный язык сохраняется как `und`. Название студии и Original не назначают ru/en. Явный язык сохраняется.
- Resume в UI, agent и списке эпизодов привязан к `MediaRef` и точной S/E. Убрана повторная привязка по названию после cache miss.
- Чтение, сохранение и backfill прогресса не назначают catalog ID по display title. Legacy прогресс сохранён, но до подтверждения identity не применяется к известной карточке.
- Нет фиксированного числа озвучек/качеств. Contract regression сохраняет 600 leaves без усечения.

## Проверка

- Backend: 418/418 PASS; 49 focused provider tests PASS. Существующие SQLite ResourceWarning остаются предупреждениями.
- Android: 220 unit tests; failures=0, errors=0, skipped=0. APK assembled; instrumentation Kotlin compiled. Instrumentation tests на устройстве не запускались.
- Live HDRezka: точный «Во все тяжкие» (2008) S01E02, 100 leaves, 9 названий озвучек, native IDs 100/100, allExactEpisode=True. Качества 480p, Не указано; language und. Эти discovery-варианты не объявлены все проверенными декодером.
- Live путь через общий registry: status=OK, leaves=100, nativeIds=100, exactEpisode=True, elapsed=8.87s при QA budget=12s. Production budget не менялся.
- Установлен build 335 / 0.0.1, данные сохранены; cold headless launch; uiAttached=false; FATAL/ANR не обнаружены в проверенном PID logcat.
- Built SHA256 = installed base.apk SHA256: `70c027a1dfebd3be95a02ddd916acfcd19163f62a7c38a5290538ef6b1d64de5`.
- Decoder smoke: passed=True, mediaItemId=159, voice=Оригинал (+субтитры), height=480, durationMs=2884903, frames=13. Cleanup=True.
- Backend deployed; parser/enricher running; health HTTP 200 на 8888. Flags и WARP/DNS не менялись. Массовая миграция БД не выполнялась.
- 20 ранее изменённых/новых пользовательских путей сохранены; UI staged только собственными resume hunks. Установленный APK также содержит уже имевшиеся пользовательские изменения рабочего дерева.

## Что осталось

- Это контрактная граница и deferred загрузка article для HDRezka. Полное асинхронное раскрытие каждой voice/quality папки на Android, остальные transports и удаление старых обходов ещё не завершены.
- Ранее автоматически привязанные persisted progress rows требуют отдельного доказательного аудита: этот блок не переатрибутирует существующие IDs вслепую. Identity preferences/history/downloads — последующие блоки 19–20.
- G02 stable source identity при изменении измеренного качества — следующий блок 3; late publish — блок 4. Этот блок их не исправляет.
- Общий проект и release gates 23–32 ещё не закрыты. Fixed 3×3 не является критерием завершения.

## Откат

Backend: вернуть три файла из `block02-live-backup`, затем последовательно перезапустить parser/enricher и проверить health. Android: установить предыдущий проверенный APK `/sdcard/Download/Movia_QA_335/app.apk` (SHA256 135d2fdb68b0d477418139904b6a426c5cc88555d21a26dd9e8fe9b7772ccbae) через pm install -r -d с сохранением данных. Ветка сохраняет предыдущий checkpoint 11903b96e61b5d1fcd4e06bb667b6ea42ea3d290. Текущий APK идентифицируется SHA выше.
