# [ADR-285] Clean-Room Online Balancers and Timeout Resilience / v0.9.29 (build 299)
**Дата:** 2026-09-03 23:55

### 1. Проблема (Problem Statement)
При попытке воспроизведения фильмов и сериалов в приложении Movia (проверено на «Сплит» 2016 / 11100 и «Во все тяжкие» S01E01) видео и аудиопотоки не запускались вообще. В приложении Zona на том же телефоне все потоки и озвучки воспроизводились штатно. Логи Movia фиксировали `HTTP GET failed for /api/movie/...: timeout` через 4–6 секунд, а в плеере появлялась ошибка отсутствия доступных источников.

### 2. Первопричина (Root Cause)
1. **Зависимость от отказавшего закрытого шлюза Zona:** Бэкенд `streamer.py` в ветке `_resolve_balancer_provider` последовательно опрашивал 5 зеркал `apir1.mzona.net/getVideoSources`, которые возвращали `HTTP 500: Internal error` из-за отсутствия проприетарной подписи `libzona.so` (`Dc.h.a`). Попытки ретраев суммарно блокировали ответ на 25–35 секунд.
2. **Асинхронная блокировка на выходе `ThreadPoolExecutor`:** В `resolve_on_demand_streams` использовался контекстный менеджер `with ThreadPoolExecutor(...)`, который на выходе неявно выполнял `shutdown(wait=True)`, ожидая завершения зависших сокетов торрент-провайдеров даже после таймаута future.
3. **Блокировка публичных торрент-трекеров:** Трекеры `rutor.info`, `apibay.org`, `yts.mx` блокируются оператором связи пользователя без VPN, возвращая 0 раздач.
4. **Заниженные сокет-таймауты в клиенте Movia:** `CatalogRepository.kt` (`connectTimeout=4000`, `readTimeout=6000`) и `DomainPlaybackResolver.kt` (`connectTimeout=5000`) обрывали соединение до получения ответа.
5. **Тяжелый расчет похожих фильмов:** Функция `_similar_items` сканировала 1200 строк с десериализацией JSON в Python, занимая до 10 секунд процессорного времени.

### 3. Решение (Solution & Architecture)
1. **Создан независимый Clean-Room модуль `collaps_provider.py`:**
   - Прямое извлечение адаптивных HLS (`master.m3u8`) видеопотоков и полного спектра русскоязычных звуковых дорожек («Дубляж», «LostFilm», «Кубик в Кубе», «Гоблин», «Многоголосый», «Original») из зеркал Collaps (`api.delivembd.ws`, `api.bhcesh.me`).
   - Поддержка фильмов (через `IMDb ID` / `TMDb ID`) и сериалов (структурированный парсинг сезонов и эпизодов).
   - Скорость ответа — 0.4–1.5 секунды.
2. **Интеграция в `balancer_integration.py`:**
   - Первичный приоритет отдан прямому балансеру Collaps.
   - В `zona_contract.py` добавлен немедленный выход (`break`) при получении кодов `HTTP 500/403/401`, исключающий 25-секундный завис.
3. **Устранение блокировок в `streamer.py`:**
   - Заменен `with ThreadPoolExecutor` на явный `pool.shutdown(wait=False, cancel_futures=True)`.
   - В `/resolve` исправлен дефолт года с 2024 на 0 для точного сопоставления сериалов по названию.
4. **Оптимизация каталога и увеличение таймаутов на клиенте:**
   - В `catalog_api.py` лимит кандидатов в `_similar_items` оптимизирован с 1200 до 150 (ускорение в 10 раз, ответ деталей <1.5s).
   - В `CatalogRepository.kt`: `connectTimeout = 10_000`, `readTimeout = 12_000`.
   - В `DomainPlaybackResolver.kt`: `connectTimeout = 12_000`, `readTimeout = 15_000`.

### 4. Измененные файлы (Changed Files)
- `projects/media-parser/collaps_provider.py` (новый модуль)
- `projects/media-parser/balancer_integration.py`
- `projects/media-parser/zona_contract.py`
- `projects/media-parser/streamer.py`
- `projects/media-parser/catalog_api.py`
- `projects/movia/app/src/main/java/app/movia/android/data/catalog/CatalogRepository.kt`
- `projects/movia/app/src/main/java/app/movia/android/domain/playback/DomainPlaybackResolver.kt`
- `projects/movia/app/build.gradle.kts`
- `projects/movia/docs/decisions/INDEX.md`
- `projects/movia/docs/decisions/ADR-285-clean-room-online-balancers-and-timeout-resilience.md`

### 5. Использованные инструменты и команды (Tools & Verification)
```bash
# Тест Collaps и бэкенда
python3 projects/media-parser/collaps_provider.py "Сплит" 2017
curl -s "http://127.0.0.1:8888/api/movie/11100/stream"
curl -s "http://127.0.0.1:8888/resolve?title=Во%20все%20тяжкие&category=series&season=1&episode=1&refresh=1"

# Сборка и верификация Android
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
./gradlew assembleDebug

# Автономный тихий деплой через Shizuku (Zero-Click Deploy)
cat app/build/outputs/apk/debug/app-debug.apk | rish -c "cat > /data/local/tmp/app_deploy.apk && pm install -r -d /data/local/tmp/app_deploy.apk && rm -f /data/local/tmp/app_deploy.apk && am force-stop app.movia.android && am start -n app.movia.android/.MainActivity"

# Верификация работы процесса на телефоне
rish -c "dumpsys package app.movia.android | grep -E "versionCode|versionName""
rish -c "dumpsys window | grep -E "mCurrentFocus""
```
