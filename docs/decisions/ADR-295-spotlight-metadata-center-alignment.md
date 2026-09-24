# [ADR-295] Movia Home: Spotlight Metadata Row Center Alignment (v0.0.1, build 307)
**Дата:** 2026-09-22 18:01

### 1. Проблема (Problem Statement)
В секции Spotlight на Главном экране строка метаданных фильма (`★ 8,3 · Комедия · 2022`) отображалась с левым выравниванием (`TextAlign.Start`), в то время как заголовок фильма, центральный постер карусели и блок кнопок CTA отцентрированы по горизонтальной оси экрана. Это создавало асимметричный визуальный сдвиг метаданных влево относительно центральной композиции.

### 2. Первопричина (Root Cause)
1. В `HomeScreen.kt` компонент `MediaMetadataRow` вызывался с `Modifier.fillMaxWidth()`, но без указания выравнивания текста.
2. В `MediaMetadataText.kt` компонент `MediaMetadataRow` жестко рендерил Compose `Text(...)` без параметра `textAlign`, что приводило к дефолтному выравниванию `TextAlign.Start`.

### 3. Решение (Solution & Architecture)
1. **Параметризация `MediaMetadataRow` ([`MediaMetadataText.kt`](file:///data/data/com.termux/files/home/projects/movia/app/src/main/java/app/movia/android/ui/components/MediaMetadataText.kt)):**
   - Добавлен параметр `textAlign: TextAlign = TextAlign.Start`.
   - Значение передается в базовый `Text(..., textAlign = textAlign)`.
   - Дефолтное значение сохраняет совместимость со всеми существующими вызовами в Каталоге, карточках списков и деталях (`TextAlign.Start`).
2. **Центрирование в Spotlight ([`HomeScreen.kt`](file:///data/data/com.termux/files/home/projects/movia/app/src/main/java/app/movia/android/ui/home/HomeScreen.kt)):**
   - В вызов `MediaMetadataRow` для Spotlight передан аргумент `textAlign = TextAlign.Center`.
   - Строка метаданных теперь строго центрирована по той же вертикальной оси, что постер, заголовок и CTA.
3. **Безопасность отсутствующих полей:**
   - Алгоритм формирования `parts` через `listOfNotNull` и `forEachIndexed` гарантирует отсутствие висячих разделителей « · » при отсутствии рейтинга, жанра или года, при этом центрирование сохраняется.
4. **Сохраненные инварианты:**
   - Порядок метаданных: ★ рейтинг · жанр/тип · год.
   - Цвет рейтинга `#FFB73A` (`MoviaBrandAmber`).
   - Размеры шрифтов (13sp/17sp), отступы, геометрия Spotlight и размеры постеров.
   - Внешний вид других карточек и экранов.

### 4. Измененные файлы (Changed Files)
- `app/src/main/java/app/movia/android/ui/components/MediaMetadataText.kt`
- `app/src/main/java/app/movia/android/ui/home/HomeScreen.kt`
- `docs/decisions/ADR-295-spotlight-metadata-center-alignment.md`
- `docs/decisions/INDEX.md`
