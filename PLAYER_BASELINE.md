# Movia Player — Authoritative Baseline / Recovery Contract

**Статус:** CURRENT APPROVED SPEC
**Эталон:** Movia `0.3.14`, `versionCode=116`
**Дата фиксации:** 2026-08-20  
**Основной файл реализации:** `app/src/main/java/app/movia/android/ui/player/PlayerScreen.kt`  
**Playback session:** `PlaybackSession` / один `session.player` для всех player-layout/routes.

> Этот файл — инструкция восстановления текущего согласованного плеера. При случайном UI-регрессе, конфликте merge или последующей правке сначала сверяться с этим baseline. Не менять перечисленные инварианты без нового явного решения пользователя. Если пользователь позже сознательно меняет один из пунктов, обновить этот файл вместе с кодом.

---

## 1. Главный архитектурный инвариант

Не создавать параллельный/второй player.

```text
PlaybackSession
      │
      ▼
  PlayerScreen
   ├─ Portrait
   ├─ Landscape / fullscreen
   ├─ Настройки воспроизведения
   │   ├─ Аудиодорожка
   │   ├─ Субтитры
   │   ├─ Скорость
   │   └─ Качество
   └─ Выбор сезона и серий
```

Все режимы используют один `session.player`.

При переходах между экранами/ориентациями не должны сбрасываться:

- playback position;
- Play/Pause;
- выбранная аудиодорожка;
- субтитры;
- скорость;
- качество;
- выбранный/текущий media item;
- текущая playback-session.

`MainActivity` должна продолжать обрабатывать orientation/config changes без создания второго ExoPlayer.

---

## 2. Общая визуальная концепция плеера


Видео — главный визуальный слой.

Поверх него остаются только необходимые controls:

```text
( ← )                      ( PiP ) ( ⚙ )

                  ( ▶ / Ⅱ )

[===================●--------------------]
00:01                                 −09:54
                              (≡►)   (⛶/collapse)
```

Иерархия действий:

```text
ВЕРХ:
←      navigation/back
PiP    внешний режим окна
⚙      настройки; всегда крайняя справа

ЦЕНТР:
▶/Ⅱ    главное playback-действие

НИЗ:
timeline
время
≡►     выбор воспроизводимого контента
⛶      режим отображения видео
```

Не возвращать без отдельного запроса:

- название фильма поверх видео;
- отдельные большие непрозрачные панели;
- старый прозрачный settings bottom-sheet;
- `Вписать / Заполнить экран` в player settings;
- PiP обратно в нижний action-row;
- три нижние action-кнопки;
- дублирующие fullscreen-кнопки;
- отдельный download action на экране сезонов/эпизодов.

---

## 3. Системная цветовая система

Player обязан использовать Movia/system tokens, а не новые произвольные chromatic literals.

Ключевые токены:

```text
MoviaBrandAmber       = #D4AF37
MoviaBorderSubtle     = #2A2F3D
MoviaGlowLuminescence = RGB #F2CF5F с token alpha
scheme.background
scheme.surface
scheme.surfaceContainer
scheme.onSurface
scheme.onBackground
```

Selected/current values в settings — золотые `MoviaBrandAmber`.

---

## 4. Верхняя навигация плеера


### Back

- слева сверху;
- стандартная `Icons.AutoMirrored.Outlined.ArrowBack`;
- НЕ `×`;
- отдельная круглая кнопка;
- размер внешней кнопки: **52dp**;
- иконка: **24dp**;
- фон: `scheme.surfaceContainer.copy(alpha = 0.62f)`;
- border: `1dp MoviaBorderSubtle`;
- icon tint: `scheme.onSurface.copy(alpha = 0.94f)`.

### Правый верхний блок — STRICT ORDER

```text
( PiP )   ( ⚙ )
             ↑
        крайняя справа
```

PiP:

- `Icons.Outlined.PictureInPictureAlt`;
- исходный размер glyph сохранён: **22dp**;
- внешний glass-action контейнер: **52dp**;
- фон/border/tint те же, что у `PlayerGlassAction`;
- вызывает существующий `enterPictureInPicture()` flow.

Settings:

- `Icons.Outlined.Settings`;
- контейнер **52dp**, glyph **24dp**;
- всегда **последняя/крайняя правая** кнопка;
- позиция Settings не должна прыгать из-за PiP.

Между PiP и Settings:

```text
8dp
```

Back не объединять с правым action block.

### Insets

Portrait:

```text
WindowInsets.safeDrawing: Top + Horizontal
padding(horizontal = 16dp, vertical = 16dp)
```

Landscape:

```text
WindowInsets.displayCutout: Horizontal
WindowInsets.safeDrawing: Top
padding(horizontal = 24dp, vertical = 16dp)
```

Нельзя размещать Back/PiP/Settings под cutout/system unsafe area.

---

## 5. Центральный Play / Pause — APPROVED SIZE

Размер был увеличен ровно на **15%** и должен сохраняться:

```text
PLAYER_CENTER_CONTROL_SIZE = 57.5dp
PLAYER_CENTER_ICON_SIZE    = 28.75dp
```

Позиция:

- `Alignment.Center` относительно video/player area;
- не сдвигать из-за появления нижней панели.

Внешний вид:

```text
shape  = CircleShape
border = 1dp MoviaBrandAmber alpha 0.48
```

Radial background:

```text
0.00 -> MoviaGlowLuminescence alpha 0.20
0.38 -> scheme.surfaceContainer alpha 0.62
0.72 -> scheme.surfaceContainer alpha 0.44
1.00 -> scheme.surface alpha 0.28
```

Play/Pause icon:

```text
tint = scheme.onSurface alpha 0.96
```

Buffering spinner:

```text
size        = 27.6dp
strokeWidth = 2.25dp
color       = scheme.onSurface
track alpha = 0.14
```

---

## 6. Player dimming / overlay

Approved overlay behind controls:

```text
full-screen dimmer: scheme.background alpha 0.20
```

Top soft fade:

```text
height = 22% player height
start = scheme.background alpha 0.34
end   = transparent
```

Не возвращать тяжёлые отдельные top/bottom bands без необходимости.

---

## 7. Нижняя панель — FINAL APPROVED LAYOUT

Нижний controls-блок остаётся **без общей рамки и без общей цветной подложки**. Это прозрачный overlay поверх видео.

```text
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━●
00:01                                      −09:54

                               (≡►)   (⛶/collapse)
```

Порядок сверху вниз — STRICT:

1. timeline;
2. непосредственно под ним строка времени;
3. `8dp` свободного пространства;
4. справа `≡►` и `Fullscreen/FullscreenExit`.

PiP снизу отсутствует и остаётся сверху слева от Settings.

### Portrait

Approved horizontal behavior не менялся:

```text
outerHorizontal = 0dp
```

Внутренний inset видимой линии в Portrait:

```text
timelineInset = 5dp
```

Итоговый фактический отступ начала/конца видимой линии от Portrait safe player-area:

```text
0dp outer + 5dp timeline = 5dp TOTAL
```

То есть **весь пользовательский горизонтальный отступ линии — ровно 5dp слева и 5dp справа**. Системный `safeDrawing` inset сохраняется отдельно и не считается частью пользовательского 5dp отступа.

Timeline использует доступную ширину Portrait player area.

### Landscape — CRITICAL VIDEO-BOUND RULE

В Landscape lower controls **не привязываются к физическим краям дисплея**.

Их горизонтальная область ограничивается фактическим FIT-прямоугольником видео, рассчитанным по `VideoSize` и тем же root `BoxWithConstraints`, внутри которого находится `PlayerView`.

```text
Player root / physical display
┌────────────────────────────────────────────────────┐
│      FIT video rectangle / movie window            │
│      ┌──────────────────────────────────────┐      │
│      │ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │      │
│      │ 00:01                         −09:54 │      │
│      │                         ≡►     ⛶      │      │
│      └──────────────────────────────────────┘      │
└────────────────────────────────────────────────────┘
```

Landscape outer horizontal inset:

```text
maxOf(30dp, landscapeVideoHorizontalInset + 18dp)
```

То есть:

- минимум сохраняется safe margin `30dp`;
- если `RESIZE_MODE_FIT` создаёт боковые поля, controls сдвигаются внутрь на ширину этих полей;
- дополнительно сохраняется `18dp` внутреннего отступа уже внутри видеопрямоугольника.

Insets также сохраняются:

```text
displayCutout Horizontal
navigationBarsIgnoringVisibility Horizontal + Bottom
```

Не возвращать привязку timeline к полной физической ширине Landscape-экрана.

---

## 8. Timeline geometry

Timeline container:

```text
fillMaxWidth()
height = 22dp
```

Visible timeline line и time row используют один общий endpoint anchor, но значение зависит от orientation:

```text
Portrait:  timelineInset = 5dp
Landscape: timelineInset = 9dp

trackStart = timelineInset
trackEnd   = width - timelineInset
stroke     = 3dp
cap        = Round
```

### Video FIT aspect source

Использовать актуальный Media3 `VideoSize`:

```text
displayAspect = width × pixelWidthHeightRatio / height
```

`unappliedRotationDegrees` не использовать: в Media3 1.9.3 поле deprecated, а актуальный `VideoSize(width, height, pixelWidthHeightRatio)` создаётся с уже display-oriented geometry.

Aspect ratio обновляется через:

```text
Player.Listener.onVideoSizeChanged(videoSize)
```

### Landscape FIT bounds

Корень `PlayerScreen` — `BoxWithConstraints(fillMaxSize())`, то есть те же constraints, в которых расположен `PlayerView(fillMaxSize())`.

Для Landscape:

```text
fittedVideoWidth = min(maxWidth, maxHeight × videoAspectRatio)
landscapeVideoHorizontalInset = max(0dp, (maxWidth - fittedVideoWidth) / 2)
```

Далее `PlayerTimeline` получает этот inset и применяет:

```text
outerHorizontal = maxOf(30dp, landscapeVideoHorizontalInset + 18dp)
```

Это делает timeline адаптивным для:

- 16:9 видео на ультрашироком дисплее;
- 4:3 видео;
- 21:9/2.39:1 видео;
- источников с non-square pixel ratio.

Если видео само заполняет ширину Landscape viewport, `landscapeVideoHorizontalInset = 0dp` и остаётся обычный safe padding `30dp`.

Colors:

```text
remaining/base = MoviaBorderSubtle
buffered       = scheme.onSurface alpha 0.34
played         = MoviaBrandAmber
```

Thumb:

```text
normal    = 10dp diameter
scrubbing = 14dp diameter
animation = 120ms
thumb color = scheme.onSurface
scrub glow  = MoviaGlowLuminescence alpha 0.14
```

Scrubbing semantics/seek/preview сохраняются.

---

## 9. Время — CRITICAL ALIGNMENT LOCK

Время находится **сразу под timeline** и визуально образует с ним один информационный блок.

Approved implementation:

```text
Row = fillMaxWidth()
padding horizontal = timelineInset (9dp)
Arrangement.SpaceBetween
```

То есть:

```text
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━●
↑                                              ↑
00:01                                      −09:54
```

Инварианты:

- current time начинается строго под левым видимым концом timeline;
- remaining time заканчивается строго под правым видимым концом timeline;
- оба края задаются тем же orientation-aware `timelineInset`, что и линия: **5dp Portrait / 9dp Landscape**;
- time row не имеет собственного дополнительного правого/левого offset;
- timeline container уменьшен до `22dp`, чтобы цифры были визуально ближе к линии;
- remaining time отображается со знаком `−`;
- current text: `scheme.onSurface alpha 0.92`, `labelMedium`, Medium;
- remaining text: `scheme.onSurface alpha 0.78`, `labelMedium`, Medium.

Между time row и нижними action-кнопками approved spacer:

```text
8dp
```

---

## 10. Нижние action-кнопки — НЕ МЕНЯТЬ ГЕОМЕТРИЮ


В нижнем action-row теперь максимум **две** кнопки.

Каждая:

```text
size       = 48dp
icon size  = 22dp
shape      = CircleShape
background = scheme.surfaceContainer alpha 0.62
border     = 1dp MoviaBorderSubtle
icon tint  = scheme.onSurface alpha 0.94
```

Spacing:

```text
8dp
```

Alignment:

```text
Arrangement.spacedBy(8dp, Alignment.End)
```

### Порядок

Для series:

```text
(≡►)  (fullscreen action)
```

Для movie/non-series:

```text
(fullscreen action)
```

### 1 — Season/Episodes

```text
Icons.AutoMirrored.Outlined.PlaylistPlay
```

Открывает `PlayerEpisodeSelectionScreen`.

### 2 — Fullscreen

Portrait:

```text
Icons.Outlined.Fullscreen
```

Действие: enter Landscape/fullscreen.

Landscape:

```text
Icons.Outlined.FullscreenExit
```

Действие: return Portrait.

Иконка показывает предстоящее действие.

### PiP

PiP больше **не является нижним action**. Его approved position — верхний правый блок **слева от Settings**.

---

## 11. Fullscreen / orientation Back contract

Fullscreen меняет layout/orientation текущего плеера, а не открывает второй player.

Portrait -> Fullscreen:

```text
requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE
```

Landscape -> Portrait:

```text
requestedOrientation = SCREEN_ORIENTATION_PORTRAIT
```

При полном выходе из player:

```text
requestedOrientation = SCREEN_ORIENTATION_UNSPECIFIED
```

### System/UI Back hierarchy

Порядок обработки Back:

```text
1. settings picker -> main Playback Settings
2. main Playback Settings -> player
3. Episode Selection -> player
4. Landscape fullscreen -> Portrait player
5. Portrait player -> previous app screen
```

Не перепрыгивать уровни.

---

## 12. Настройки воспроизведения — FULLSCREEN OPAQUE ROUTE

Кнопка `⚙` НЕ должна открывать `ModalBottomSheet`.

Approved flow:

```text
PLAYER
  │ tap ⚙
  ▼
Настройки воспроизведения
```

Экран:

- полностью непрозрачный;
- `.background(scheme.background)`;
- `WindowInsets.safeDrawing`;
- video behind НЕ просвечивает;
- `zIndex(30f)` в Player flow;
- same playback session remains alive.

### Верх

Back:

```text
48dp touch target
ArrowBack 24dp
```

Title:

```text
Настройки воспроизведения
26sp
lineHeight 32sp
Bold
scheme.onBackground
```

### Порядок sections — STRICT

```text
АУДИО И СУБТИТРЫ
  Аудиодорожка
  Субтитры

ВОСПРОИЗВЕДЕНИЕ
  Скорость

ВИДЕО
  Качество
```

Не добавлять обратно `Вписать / Заполнить экран`.

Player viewport resize mode в текущем approved state остаётся FIT:

```text
AspectRatioFrameLayout.RESIZE_MODE_FIT
```

---

## 13. Settings row styling

Main navigation row:

```text
min height = 72dp
shape      = RoundedCornerShape(14dp)
background = scheme.surfaceContainer
border     = 1dp MoviaBorderSubtle
padding    = 16dp horizontal / 12dp vertical
row gap    = 12dp
```

Title:

```text
16sp / 22sp
Medium
scheme.onSurface
```

Current selected value:

```text
14sp / 20sp
Medium
MoviaBrandAmber
maxLines = 1
```

Chevron:

```text
KeyboardArrowRight
24dp
scheme.onSurface alpha 0.72
```

Section label:

```text
13sp / 18sp
SemiBold
letterSpacing 0.7sp
scheme.onSurface alpha 0.68
```

---

## 14. Nested settings picker screens

Picker enum / supported screens:

```text
AUDIO
SUBTITLES
SPEED
QUALITY
```

Titles:

```text
Аудиодорожка
Субтитры
Скорость
Качество
```

Option row:

```text
min height = 58dp
shape      = RoundedCornerShape(14dp)
normal bg  = scheme.surfaceContainer
normal border = MoviaBorderSubtle
selected bg = MoviaBrandAmber alpha 0.14
selected border = MoviaBrandAmber
selected text = MoviaBrandAmber
selected mark = ✓ gold
```

После выбора параметра возвращаться в основной экран `Настройки воспроизведения`.

Не закрывать весь settings chain.

---

## 15. Swipe-down = one Back step

Единый helper:

```text
playerSwipeDownBack(...)
```

Он вызывает **тот же `onBack` callback**, что и кнопка `←`.

Intentional gesture constraints:

```text
start zone: top 112dp
minimum downward distance: 96dp
vertical dominance: totalY > abs(totalX) * 1.35
```

Если touch начался ниже 112dp — gesture не считается swipe-back.

Это защищает обычную вертикальную прокрутку settings/episode list от случайного Back.

### One-step rule

```text
Скорость
  ↓
Настройки воспроизведения
  ↓
Player
```

То же для Audio/Subtitles/Quality.

На main Playback Settings:

```text
swipe ↓ == ← == Player
```

---

## 16. Выбор сезона и серий

Это отдельный opaque screen внутри Player flow.

### Верх

- `←` слева;
- без повторного большого заголовка;
- Back возвращает в player;
- screen background = `scheme.background`;
- safe drawing insets.

### Season chips

Horizontal row с `Сезон 1`, `Сезон 2`, ...

### Gestures

Vertical back:

```text
swipe ↓ == ← == Player
```

Horizontal season switching:

```text
swipe ← -> next season
swipe → -> previous season
```

Intentional horizontal constraints:

```text
minimum distance = 88dp
horizontal dominance = abs(totalX) > abs(totalY) * 1.35
```

Horizontal swipe меняет только `episodesScreenSeason`/displayed season.

Если пользователь просто посмотрел другой сезон и сделал swipe-down/Back:

- playback НЕ перезапускается;
- текущий media НЕ меняется;
- position НЕ сбрасывается.

При смене displayed season episode list scroll resets to top only:

```text
episodeScrollState.scrollTo(0)
```

### Episode selection

Tap episode:

```text
select episode
-> selector closes
-> selected episode starts immediately
```

Не добавлять confirmation dialog.

Download action на этом selector screen отсутствует.

---

## 17. Auto-hide controls

Сохранять существующее поведение:

```text
auto-hide delay = 3500ms
```

Auto-hide работает только когда:

- controls visible;
- playback active;
- no playback error;
- settings closed;
- episode selector closed;
- not scrubbing.

Скрываются согласованно:

```text
←
⚙
Play/Pause
lower controls
```

Видео остаётся.

Tap/interaction снова показывает controls.

Не менять timer без нового отдельного решения.

---

## 18. Playback settings state preservation

Settings меняют текущий `player` напрямую через существующие Media3 APIs.

Сохранять:

- audio selection через `trackSelectionParameters`;
- video quality через `trackSelectionParameters`;
- subtitle enabled/override через `TRACK_TYPE_TEXT`;
- speed через `PlaybackParameters(speed)`.

Открытие/закрытие settings НЕ должно делать `prepare()` нового player и НЕ должно reset media position.

---

## 19. PiP invariants


PiP использует существующий `buildMoviaPictureInPictureParams(...)` и текущий player.

Approved UI position:

```text
верх справа: ( PiP ) ( ⚙ )
```

- icon: `Icons.Outlined.PictureInPictureAlt`;
- glyph size: **22dp**;
- PiP расположен слева от Settings;
- Settings всегда остаётся крайней справа;
- в нижней панели PiP отсутствует.

При входе в PiP закрываются overlays:

```text
controlsVisible = false
settingsOpen = false
settingsPicker = null
episodesScreenOpen = false
```

Не создавать отдельную playback session для PiP.

---

## 20. Что считать регрессией

Любое из следующего — regression относительно этого baseline:

- Back снова стал `×`;
- Settings перестала быть крайней правой верхней action-кнопкой;
- PiP снова оказался в нижней панели;
- PiP стоит справа от Settings;
- settings снова BottomSheet/translucent;
- `Вписать / Заполнить экран` вернулись в player settings;
- Play/Pause меньше/больше `57.5dp / 28.75dp` без нового решения;
- вокруг timeline/time/actions снова появилась общая рамка, капсула или цветная подложка;
- timeline container снова стал `38dp` без нового решения;
- Landscape timeline снова растягивается по физической ширине дисплея независимо от FIT-видео;
- Landscape использует фиксированный device-specific margin вместо расчёта из `VideoSize` и root constraints;
- потерян `onVideoSizeChanged` и aspect ratio перестал обновляться при смене media/track;
- Portrait outer padding перестал быть `0dp` без нового решения;
- Portrait timelineInset перестал быть `5dp` без нового решения;
- Portrait итоговый пользовательский отступ линии перестал быть ровно `5dp` от safe player-area без нового решения;
- timeline и time row используют разные endpoint offsets;
- правый remaining time не заканчивается под правым концом timeline;
- remaining time потеряло знак `−`;
- нижние кнопки получили другую геометрию без запроса;
- Portrait и Landscape показывают одну и ту же fullscreen icon;
- swipe-down закрывает больше одного navigation level;
- vertical scrolling случайно закрывает settings/episode screen;
- horizontal season swipe запускает media;
- settings/fullscreen создают новый player или сбрасывают position;
- controls auto-hide timer изменён без запроса;
- cutout/system insets потеряны.

---

## 21. Recovery checklist после merge/regression

1. Сверить `PlayerScreen.kt` с этим документом.
2. Проверить version/source state и фактический diff.
3. Проверить центральный control `57.5dp / 28.75dp`.
4. Проверить верх: `Back | PiP Settings`, Settings крайняя справа.
5. Проверить отсутствие общей Surface/рамки/цветной подложки вокруг lower controls.
6. Проверить порядок `timeline → time → 8dp spacer → actions`.
7. Проверить timeline `fillMaxWidth()`, container height `22dp`.
8. Проверить orientation-aware `timelineInset`: `5dp Portrait / 9dp Landscape`, единый для draw/gesture/time row.
9. Проверить `RESIZE_MODE_FIT`.
10. Проверить state `videoAspectRatio = moviaVideoAspectRatio(player.videoSize)`.
11. Проверить `onVideoSizeChanged(videoSize)`.
12. Проверить aspect formula `width × pixelWidthHeightRatio / height` без deprecated rotation API.
13. Проверить root `BoxWithConstraints(fillMaxSize())` и FIT width formula `min(maxWidth, maxHeight × aspect)`.
14. Проверить Landscape outer inset `maxOf(30dp, landscapeVideoHorizontalInset + 18dp)`.
15. Проверить Portrait: `outerHorizontal = 0dp`, `timelineInset = 5dp`, итоговый пользовательский отступ видимой линии = ровно `5dp` от safe player-area.
16. Проверить lower actions: PlaylistPlay + Fullscreen/Exit, `48dp`, icon `22dp`, gap `8dp`.
17. Проверить opaque Playback Settings и nested Back hierarchy.
18. Проверить swipe-down thresholds `112dp / 96dp / 1.35x`.
19. Проверить season horizontal thresholds `88dp / 1.35x`.
20. Проверить one `session.player`.
21. Выполнить `git diff --check` и релевантный compile/test/lint/assemble gate.
22. Не заявлять runtime-проверку, если приложение физически не запускалось.

---

## 22. Последний подтверждённый build baseline

```text
Movia 0.3.14
versionCode 116
APK: /storage/emulated/0/Download/Movia-0.3.14.apk
full gate marker: MOVIA_0_3_14_ALL_GATES_PASS
```

Последний полный gate включал:

```text
compileDebugKotlin
testDebugUnitTest
assembleDebug
lintDebug
assembleRelease
assembleDebugAndroidTest
static portrait-total-5dp contract
git diff --check
```

Установка `0.3.14 / 116` подтверждена через `dumpsys package app.viora.android`.

Контрольные SHA-256 на момент фиксации baseline:

```text
PlayerScreen.kt
3a516449d9e3c37af5be7d208e3f67dbe2be937182c43796f585403543e8ca2b

Movia-0.3.14.apk
86309938e5eff74ed38797d6dbae45c475ee190cd1ddb65eccc224cfc966f28f
```

---

## 23. Rule for future work

**Перед любой следующей правкой PlayerScreen:**

- прочитать этот файл;
- менять только явно запрошенный пользователем контракт;
- не «улучшать заодно» остальные approved player-параметры;
- после изменения сверить untouched invariants;
- если новый дизайн сознательно заменяет один из approved пунктов — обновить этот baseline в той же работе.
