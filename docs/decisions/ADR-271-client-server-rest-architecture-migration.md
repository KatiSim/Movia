# [ADR-271] Переход на клиент-серверную архитектуру (Zona Clone 64MB) / Версия (v0.9.1, build 271)
**Дата:** 2026-08-28 02:00

### 1. Проблема (Problem Statement)
Приложение Movia весило 472 МБ (APK 96 МБ + данные 376 МБ) из-за встроенной локальной базы данных на 60 000 тайтлов и отсутствия автоматической очистки кэша торрентов. Zona весит всего ~64 МБ, подгружая каталог по требованию через серверный API.

### 2. Первопричина (Root Cause)
- Встроенный файл `catalog.db` был упакован напрямую в assets APK.
- Room/SQLite дублировал базу в памяти приложения при первом запуске.
- Кэш торрент-стримера `torrent_cache/` разрастался без лимитов и автоочистки.

### 3. Решение (Solution & Architecture)
- **Удаление локальной базы из APK**: База данных перенесена на серверный уровень (`catalog_api.py` / `streamer.py`).
- **Легковесный HTTP-клиент**: `HttpCatalogRepository.kt` заменил тяжелый Room-слой, опрашивая локальный бэкенд `http://127.0.0.1:8888/api/`.
- **Автоматическая ротация кэша**: В `catalog_api.py` реализована очистка кэша торрентов при превышении порога 200 МБ.
- **Оптимизация APK**: Размер приложения уменьшен с 96 МБ до **23 МБ**.

### 4. Измененные файлы (Changed Files)
- `projects/media-parser/catalog_api.py`
- `projects/media-parser/streamer.py`
- `projects/movia/app/src/main/java/app/movia/android/data/catalog/CatalogRepository.kt`
- `projects/movia/app/src/main/java/app/movia/android/ui/MoviaApp.kt`
- `projects/movia/app/build.gradle.kts`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
# Проверка размера APK
ls -lh /sdcard/Download/Movia-Official-v271.apk

# Проверка API каталога
curl -s http://127.0.0.1:8888/api/home | jq .

# Сборка и деплой через Shizuku
./gradlew assembleDebug
cat app/build/outputs/apk/debug/app-debug.apk | rish -c "cat > /data/local/tmp/m271.apk && pm install -r -d /data/local/tmp/m271.apk && rm -f /data/local/tmp/m271.apk"
```
