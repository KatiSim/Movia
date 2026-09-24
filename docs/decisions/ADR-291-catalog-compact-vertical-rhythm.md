# [ADR-291] Movia Catalog: Compact Vertical Rhythm (v0.0.1, build 307)
**Дата:** 2026-09-22 16:30

### 1. Проблема (Problem Statement)
Верхняя управляющая зона экрана «Каталог» занимала избыточную вертикальную высоту из-за скрытых внутренних паддингов и раздутых интерактивных контейнеров, сдвигая контентную сетку постеров вниз.

### 2. Первопричина (Root Cause)
1. В `LazyVerticalGrid` базовый шаг сетки задан через `Arrangement.spacedBy(16.dp)`.
2. Элемент сортировки `catalog-sort` содержал внутренний `Box(modifier = Modifier.heightIn(min = 48.dp))` вокруг однострочного текста высотой 20dp, что добавляло 14dp верхнего и 14dp нижнего пустого пространства, раздувая визуальные интервалы Chips → Sort до 32dp и Sort → Posters до 32dp.
3. В Header Row присутствовал отступ `bottom = 4.dp`, дававший в сумме с шагом сетки 20dp вместо требуемых 24dp.

### 3. Решение (Solution & Architecture)
1. Header Row: отступ изменен на `.padding(top = 4.dp, bottom = 8.dp)`, что с учетом шага сетки 16dp дает ровно **24dp** до строки поиска.
2. Search → Chips: сохранено значение по умолчанию `0.dp` дополнительных отступов, что дает ровно **16dp** шага сетки.
3. Chips → Sort: для Sort Row задан отступ `.padding(top = 4.dp)`, что вместе с шагом сетки 16dp формирует ровно **20dp**.
4. Sort → Posters: убран лишний `heightIn(min = 48.dp)` и нижний паддинг, высота контейнера теперь равна высоте контента (20dp). От нижней границы сортировки до верхнего края постеров интервал составляет ровно **16dp** (шаг сетки).
5. Дополнительные Spacer между блоками исключены.

### 4. Измененные файлы (Changed Files)
- `app/src/main/java/app/movia/android/ui/catalog/CatalogScreen.kt`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
./gradlew compileDebugKotlin --offline
./gradlew :app:assembleDebug --no-daemon --offline
cat app-debug.apk | rish -c "cat > /data/local/tmp/app_deploy.apk && pm install -r -d /data/local/tmp/app_deploy.apk && rm -f /data/local/tmp/app_deploy.apk && am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"
rish -c "screencap -p /sdcard/catalog_fullscreen.png"
```
