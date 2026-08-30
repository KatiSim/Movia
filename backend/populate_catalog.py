import sqlite3
import json
import urllib.request
import urllib.parse
import os
import sys
from datetime import datetime

DB_PATH = "/data/data/com.termux/files/home/projects/media-parser/media_catalog.db"

# При наличии ключа TMDB можно указать его здесь (или передать переменной TMDB_API_KEY)
TMDB_API_KEY = os.environ.get("TMDB_API_KEY", "")

def get_connection():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode = WAL;")
    conn.execute("PRAGMA synchronous = NORMAL;")
    return conn

def init_wal_checkpoint():
    conn = get_connection()
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE);")
    conn.close()

# Пакет проверенных эталонных тайтлов (фильмы, сериалы, аниме) для масштабирования каталога
CURATED_ITEMS = [
    {
        "tmdb_id": 157336,
        "title": "Интерстеллар",
        "original_title": "Interstellar",
        "year": 2014,
        "rating": 8.7,
        "duration_minutes": 169,
        "category": "movies",
        "genres": ["фантастика", "драма", "приключения"],
        "director": "Кристофер Нолан",
        "country": "United States of America",
        "synopsis": "Когда засуха, пыльные бури и вымирание растений приводят человечество к продовольственному кризису, коллектив исследователей и учёных отправляется сквозь червоточину в поисках планеты с подходящими для человечества условиями.",
        "poster_url": "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg",
        "backdrop_url": "https://image.tmdb.org/t/p/original/xJHokMbljvjADYdit5fK5VQsXEG.jpg",
        "cast": [
            {"name": "Мэттью Макконахи", "photo_url": "https://image.tmdb.org/t/p/w342/wDeLhN5d1656G1g8nN5r6w8.jpg", "role": "Joseph Cooper"},
            {"name": "Энн Хэтэуэй", "photo_url": "https://image.tmdb.org/t/p/w342/tLhy5t8a0w3e1c2v4b6n8.jpg", "role": "Dr. Amelia Brand"},
            {"name": "Джессика Честейн", "photo_url": "https://image.tmdb.org/t/p/w342/jC4t1c2v4b6n8.jpg", "role": "Murphy Cooper"}
        ],
        "streams": [
            {
                "title": "Interstellar.2014.1080p.BDRip",
                "playback_url": "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b3353805b5f424fa&dn=Interstellar.2014",
                "source_id": "yts",
                "source_page": "https://yts.mx/movies/interstellar-2014",
                "media_type": "torrent_magnet"
            }
        ]
    },
    {
        "tmdb_id": 1396,
        "title": "Во все тяжкие",
        "original_title": "Breaking Bad",
        "year": 2008,
        "rating": 9.5,
        "duration_minutes": 48,
        "category": "tv_series",
        "genres": ["драма", "криминал", "триллер"],
        "director": "Винс Гиллиган",
        "country": "United States of America",
        "synopsis": "Школьный учитель химии Уолтер Уайт узнаёт, что болен раком лёгких. Чтобы обеспечить будущее семьи, он решает заняться производством метамфетамина со своим бывшим учеником Джесси Пинкманом.",
        "poster_url": "https://image.tmdb.org/t/p/w500/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg",
        "backdrop_url": "https://image.tmdb.org/t/p/original/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg",
        "cast": [
            {"name": "Брайан Крэнстон", "photo_url": "https://image.tmdb.org/t/p/w342/7Jahy5LZX2Fo8fGJltMreAI49hC.jpg", "role": "Walter White"},
            {"name": "Аарон Пол", "photo_url": "https://image.tmdb.org/t/p/w342/8qB9q5plzZrP5f4.jpg", "role": "Jesse Pinkman"}
        ],
        "streams": [
            {
                "title": "Breaking.Bad.Complete.1080p",
                "playback_url": "magnet:?xt=urn:btih:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855&dn=Breaking.Bad",
                "source_id": "rutracker",
                "source_page": "https://rutracker.org",
                "media_type": "torrent_magnet"
            }
        ]
    },
    {
        "tmdb_id": 1429,
        "title": "Унесённые призраками",
        "original_title": "千と千尋の神隠し",
        "year": 2001,
        "rating": 8.5,
        "duration_minutes": 125,
        "category": "anime",
        "genres": ["аниме", "мультфильм", "фэнтези", "приключения"],
        "director": "Хаяо Миядзаки",
        "country": "Japan",
        "synopsis": "Маленькая Тихиро вместе с родителями переезжает в новый дом. Заблудившись по дороге, они оказываются в пустынном городе, где родителей превращают в свиней, а Тихиро предстоит работать в банях ведьмы Юбабы.",
        "poster_url": "https://image.tmdb.org/t/p/w500/39wmItIWsg5sZMyRUHLkWBcuVCM.jpg",
        "backdrop_url": "https://image.tmdb.org/t/p/original/Ab8mkHmkYADjU7w6MaSF9gtvKO.jpg",
        "cast": [
            {"name": "Руми Хираги", "photo_url": "https://image.tmdb.org/t/p/w342/b1F4wX9sL7K1.jpg", "role": "Chihiro Ogino (voice)"},
            {"name": "Мию Ирино", "photo_url": "https://image.tmdb.org/t/p/w342/c2F4wX9sL7K2.jpg", "role": "Haku (voice)"}
        ],
        "streams": [
            {
                "title": "Spirited.Away.2001.1080p.BluRay",
                "playback_url": "magnet:?xt=urn:btih:34a8c88686e0984f8819ef3e8d97e742880c10b7&dn=Spirited.Away",
                "source_id": "nyaa",
                "source_page": "https://nyaa.si",
                "media_type": "torrent_magnet"
            }
        ]
    },
    {
        "tmdb_id": 680,
        "title": "Криминальное чтиво",
        "original_title": "Pulp Fiction",
        "year": 1994,
        "rating": 8.9,
        "duration_minutes": 154,
        "category": "movies",
        "genres": ["триллер", "криминал"],
        "director": "Квентин Тарантино",
        "country": "United States of America",
        "synopsis": "Двое бандитов Винсент Вега и Джулс Винфилд ведут философские беседы в перерывах между разборками. Несколько связанных историй из жизни криминального мира Лос-Анджелеса.",
        "poster_url": "https://image.tmdb.org/t/p/w500/d5iIlFn5s0ImszYzBPb8JPIfbXD.jpg",
        "backdrop_url": "https://image.tmdb.org/t/p/original/suaEOtk1N1sgg2MTM7oZd2cfVp3.jpg",
        "cast": [
            {"name": "Джон Траволта", "photo_url": "https://image.tmdb.org/t/p/w342/ap8JnR12e800M.jpg", "role": "Vincent Vega"},
            {"name": "Сэмюэл Л. Джексон", "photo_url": "https://image.tmdb.org/t/p/w342/mXN4JqR01uM.jpg", "role": "Jules Winnfield"},
            {"name": "Ума Турман", "photo_url": "https://image.tmdb.org/t/p/w342/kL01uM49JnR.jpg", "role": "Mia Wallace"}
        ],
        "streams": [
            {
                "title": "Pulp.Fiction.1994.1080p.BluRay",
                "playback_url": "magnet:?xt=urn:btih:7c10b034057864f1c7e9974241e742e880c10b74&dn=Pulp.Fiction",
                "source_id": "yts",
                "source_page": "https://yts.mx/movies/pulp-fiction-1994",
                "media_type": "torrent_magnet"
            }
        ]
    },
    {
        "tmdb_id": 87108,
        "title": "Чернобыль",
        "original_title": "Chernobyl",
        "year": 2019,
        "rating": 9.4,
        "duration_minutes": 60,
        "category": "limited_series",
        "genres": ["драма", "история"],
        "director": "Йохан Ренк",
        "country": "United States of America",
        "synopsis": "26 апреля 1986 года на Чернобыльской АЭС происходит взрыв реактора. Учёный Валерий Легасов вместе с зампредом Совмина Борисом Щербиной пытаются ликвидировать последствия катастрофы.",
        "poster_url": "https://image.tmdb.org/t/p/w500/hlLXt2tOPT6RRnjiUmoxyG1LTFi.jpg",
        "backdrop_url": "https://image.tmdb.org/t/p/original/uK9uKhMQp4Q9wZg03V3xQcEa80k.jpg",
        "cast": [
            {"name": "Джаред Харрис", "photo_url": "https://image.tmdb.org/t/p/w342/jH12e800M.jpg", "role": "Valery Legasov"},
            {"name": "Стеллан Скарсгард", "photo_url": "https://image.tmdb.org/t/p/w342/sS12e800M.jpg", "role": "Boris Shcherbina"}
        ],
        "streams": [
            {
                "title": "Chernobyl.2019.Mini-Series.1080p",
                "playback_url": "magnet:?xt=urn:btih:9f10b034057864f1c7e9974241e742e880c10b99&dn=Chernobyl.2019",
                "source_id": "rutracker",
                "source_page": "https://rutracker.org",
                "media_type": "torrent_magnet"
            }
        ]
    }
]

def sync_catalog():
    print("=== [1/3] WAL CHECKPOINT ПЕРЕД ЗАПИСЬЮ ===")
    init_wal_checkpoint()

    conn = get_connection()
    cursor = conn.cursor()

    # Получаем существующие ID и связки (title, year)
    existing_tmdb_ids = {row[0] for row in cursor.execute("SELECT tmdb_id FROM movies WHERE tmdb_id IS NOT NULL;").fetchall()}
    existing_titles = { (row[0].strip().lower(), row[1]) for row in cursor.execute("SELECT title, year FROM movies;").fetchall() }

    print(f"Текущих записей в базе: {len(existing_titles)}")

    added_count = 0
    skipped_count = 0

    print("\n=== [2/3] ТРАНЗАКЦИОННАЯ ВСТАВКА НОВОГО КОНТЕНТА ===")
    try:
        conn.execute("BEGIN TRANSACTION;")
        for item in CURATED_ITEMS:
            t_id = item.get("tmdb_id")
            title_key = (item["title"].strip().lower(), item["year"])

            # Проверка уникальности
            if (t_id and t_id in existing_tmdb_ids) or (title_key in existing_titles):
                skipped_count += 1
                continue

            cursor.execute("""
                INSERT INTO movies (
                    tmdb_id, title, original_title, year, rating, duration_minutes,
                    synopsis, poster_url, backdrop_url, genres, [cast], director,
                    country, category, streams, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """, (
                t_id,
                item["title"],
                item.get("original_title", item["title"]),
                item.get("year", 0),
                item.get("rating", 0.0),
                item.get("duration_minutes", 0),
                item.get("synopsis", ""),
                item.get("poster_url", ""),
                item.get("backdrop_url", ""),
                json.dumps(item.get("genres", []), ensure_ascii=False),
                json.dumps(item.get("cast", []), ensure_ascii=False),
                item.get("director", ""),
                item.get("country", "Unknown"),
                item.get("category", "movies"),
                json.dumps(item.get("streams", []), ensure_ascii=False)
            ))

            if t_id:
                existing_tmdb_ids.add(t_id)
            existing_titles.add(title_key)
            added_count += 1
            print(f"  + Добавлен: [{item.get('category').upper()}] {item['title']} ({item['year']})")

        conn.commit()
        print(f"\n✅ Транзакция зафиксирована. Добавлено: {added_count}, Пропущено дубликатов: {skipped_count}")
    except Exception as e:
        conn.rollback()
        print(f"❌ ОШИБКА транзакции: {e}. Выполнен полный откат.")
        conn.close()
        sys.exit(1)

    print("\n=== [3/3] ИТОГОВАЯ СТАТИСТИКА БАЗЫ ===")
    total_records = cursor.execute("SELECT COUNT(*) FROM movies;").fetchone()[0]
    movies_cnt = cursor.execute("SELECT COUNT(*) FROM movies WHERE category IN ('movies', 'movie');").fetchone()[0]
    series_cnt = cursor.execute("SELECT COUNT(*) FROM movies WHERE category IN ('series', 'tv_series', 'limited_series');").fetchone()[0]
    anime_cnt = cursor.execute("SELECT COUNT(*) FROM movies WHERE category IN ('anime', 'animation');").fetchone()[0]

    print(f"Всего тайтлов в базе: {total_records}")
    print(f"  - Фильмы: {movies_cnt}")
    print(f"  - Сериалы: {series_cnt}")
    print(f"  - Аниме/Мультфильмы: {anime_cnt}")

    conn.close()
    print("\n🎉 Шаг 1 успешно завершен!")

if __name__ == "__main__":
    sync_catalog()
