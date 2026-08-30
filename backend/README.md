# Movia media-parser — безопасный локальный контур

Сервис предоставляет Movia метаданные из TMDb и воспроизводимые прямые медиаресурсы из явно разрешённого каталога.

## Что поддерживается

- Flask API на 127.0.0.1:5001;
- SQLite catalog.db;
- TMDb-поиск на русском языке;
- карточки с названием, постером, описанием, годом, рейтингом, жанрами и актёрами;
- прямые HTTP/HTTPS-ссылки на MP4, WebM, HLS (.m3u8) и DASH (.mpd);
- фильтрация технических страниц и неполных записей;
- пагинация и фильтры каталога;
- локальный манифест для контролируемого пополнения каталога.

Поиск по произвольным торрент-индексам, получение magnet-ссылок и BitTorrent-стриминг в этой сборке отключены. Воспроизведение разрешается только по явно предоставленной прямой медиассылке.

## Запуск

~~~bash
cd ~/projects/media-parser
python -m pip install -r requirements.txt
nohup python -u server.py >> server.log 2>&1 &
~~~

Проверка:

~~~bash
curl http://127.0.0.1:5001/health
curl http://127.0.0.1:5001/diagnostics
curl 'http://127.0.0.1:5001/catalog?limit=20'
~~~

Остановка:

~~~bash
pkill -f 'python.*server.py'
~~~

## API

- GET /health
- GET /diagnostics
- GET /catalog?limit=100&offset=0
- GET /catalog?genre=драма&year=2020
- GET /catalog?playable_only=1
- GET /content/<id>
- GET /content/<id>/playback
- GET /stream/<id>
- POST /search
- POST /parse

Пример поиска:

~~~bash
curl -X POST http://127.0.0.1:5001/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"Inception","year":2010,"media_type":"movie","limit":5}'
~~~

## Авторизованный манифест

Файл authorized_catalog.json обрабатывается скриптом catalog_sync.py:

~~~json
[
  {
    "query": "Название материала",
    "year": 2020,
    "media_type": "movie",
    "playback_url": "https://authorized.example/video.mp4",
    "source_id": "AUTHORIZED_OPEN_DATA",
    "source_page": "https://authorized.example/item",
    "license_name": "Public Domain или CC",
    "license_url": "https://authorized.example/license"
  }
]
~~~

Синхронизация:

~~~bash
python catalog_sync.py
~~~

Скрипт не удаляет существующие записи. Неполные строки и ссылки на страницы просмотра пропускаются.

## Movia Android

Клиент использует:

    http://127.0.0.1:5001/

Он принимает envelope каталога {"items": [...], "pagination": {...}} и совместимый ответ /content/<id>/playback.

В Android-манифесте уже разрешён локальный cleartext-доступ для 127.0.0.1 и localhost.

## Диагностика

Текущие счётчики доступны в /diagnostics:

- catalog.raw_count — все строки БД;
- catalog.displayable_count — записи, пригодные для карточек;
- catalog.playable_count — записи с прямой медиассылкой;
- rules — только историческая статистика правил, не активный механизм сканирования источников.

Существующие catalog.db, sources.txt и папка backups не очищаются автоматически.
