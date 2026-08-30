# Movia (Android) 🎬

Movia — медиаплеер и каталог на Jetpack Compose Material 3 с локальной медиатекой, поиском, фильтрами и Media3 ExoPlayer.

## Каноническая сборка 0.3.70

- Пакет: `app.viora.android`, versionCode `176`.
- Каталог: 157 материалов — 120 фильмов и 37 сериалов.
- Источник проекта: `/data/data/com.termux/files/home/projects/viora`.
- Канонический полный снимок: `/data/data/com.termux/files/home/MoviaApp/Movia/Каноническая версия/0.3.70/Полный проект-0.3.70`.
- Независимая копия: `/data/data/com.termux/files/home/.movia-backups/0.3.70-canonical-20260824`.
- APK: `Movia-0.3.70-cast-photos.apk`.
- Парсер и генератор каталога: `media-parser-0.3.70/` рядом с APK и снимком проекта.

## Изменение этой сборки

- `MediaContent.cast` переведён на `List<Person>`.
- В базе и сгенерированном Kotlin-каталоге сохранены имя, роль и `photoUrl` актёров.
- Экран деталей загружает фотографии актёров с безопасным буквенным fallback, если URL недоступен.
- Существующие данные пользователя, навигация, плеер и каталог сохранены.

Перед каждой новой генерацией каталога сначала обновляйте базу, затем запускайте `generate_catalog_repository.py`, `verify_canonical.py` и `./gradlew :app:assembleDebug`.
