# Movia — native runtime verification build 327 через Shower window

Дата: 4 октября 2026.

## Контур проверки

Проверка выполнена через **Shower private virtual Android display**, а не через физический экран телефона.

- Shower slot: **2**
- virtual displayId: **390**
- пакет: `app.movia.android`
- установленная версия: **versionCode 327**, `versionName 0.0.1`
- в этом блоке APK **не устанавливался**
- Shower session после проверки освобождён

Это обновляет прежнее ограничение: build 327 теперь имеет хотя бы один native runtime playback сценарий через Shower. Это не означает, что build 327 полностью проверен.

## Сценарий

1. Запуск Movia в Shower window.
2. Переход в каталог.
3. Поиск: **Интерстеллар**.
4. Получена карточка:
   - title: Интерстеллар
   - year: **2014**
   - rating: 8.5
5. Открыта карточка.
6. Нажато `Смотреть`.
7. Проверено фактическое состояние плеера.
8. Открыты настройки.
9. Выбрано:
   - озвучка: **Дубляж**
   - качество: **720p**
10. Возврат в плеер и проверка восстановления воспроизведения.

## Startup evidence

Примерно через 1.5 s после `Смотреть` UI показывал:

- `Подготавливаем видео`
- `✓ Источник найден`
- `✓ Подключились`
- `● Готовим видео`

Затем был обнаружен replacement path:

- `Нашли более стабильный источник`
- `Переключаемся…`

После стабилизации:

- control: **Пауза**
- position: **00:25**
- remaining: **−2:48:38**

Наличие `Пауза` и изменение позиции являются более сильным runtime evidence, чем просто наличие URL.

## Доступные варианты в native settings

Для этого фильма Shower accessibility показал:

### Источники
- Rutor
- Collaps
- YTS
- Apibay

Итого видимых: **4**.

### Озвучка
- Auto
- Дубляж
- Д. Есарев
- Омикрон

Итого видимых в текущем viewport: **4**.

### Качество
- Auto
- 4K
- 1080p
- 720p

Итого видимых в текущем viewport: **4**.

Новый clean-room Filmix adapter там отсутствует, что ожидаемо: production discovery на него ещё не переключён.

## Проверка voice + quality switching

В настройках выбрано:

- voice: **Дубляж**
- quality: **720p**

Accessibility `checked` подтвердил выбранные controls:

- source: Rutor
- voice: Дубляж
- quality: 720p
- speed: 1×
- subtitles: Нет

После возврата:

- на позиции **01:50** наблюдалось `Переключаемся…`;
- UI не показывал playback error;
- после завершения switching controls скрылись как при обычном playback;
- после повторного показа controls:
  - control: **Пауза**
  - position: **02:22**
  - remaining: **−2:46:41**

То есть в этом одном native сценарии:

```text
01:50
 -> voice=Дубляж
 -> quality=720p
 -> replacement/switch
 -> 02:22
 -> state=playing
```

Позиция не сброшена в 00:00, приложение не вышло из player и воспроизведение возобновилось.

## Shower runtime evidence

На финальной проверке:

- `accessibilityReady = true`
- `videoBootstrapReady = true`
- `videoFrameCount = 3728`

Последние два значения доказывают активный видеопоток Shower display; сами по себе они не доказывают конкретный Media3 codec/frame decode. Для playback использовались также UI state `Пауза` и изменение media position.

## Что НЕ доказано этим тестом

- Не доказано, что выбранная озвучка действительно является конкретной заявленной студией по аудиопрослушиванию.
- Не проверены все источники.
- Не проверены все voice × quality комбинации.
- Не проверены 500 фильмов.
- Новый Filmix adapter ещё не подключён к production и этим Shower тестом не проверялся.
- Это один native scenario для build 327, а не полная native acceptance.

## Состояние backend

Active parser не изменялся:

- PID: **23603**
- check: **RUNNING**
- hashes `zona_legacy_adapters.py` и `zona_contract.py` без изменений.

Пользовательский Android source worktree не записывался:

- HEAD: `c5d1d3c882e07238c6f2a8fd308e8cec32f85936`
- index SHA-256: `0a6e8e0d5143d26ed257c1686d849ab9b4d92eb244fb4f1b8cbd843c0771ac7a`
- status SHA-256: `065a8153e9a074e097e7d0465c68a6da153d33bef67e7a9c4ee648790729e48f`
- status lines: **114**
