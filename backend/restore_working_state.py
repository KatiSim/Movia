import sqlite3
import os
import json
import requests

DB_PATH = os.path.expanduser("~/projects/media-parser/media_catalog.db")
API_KEY = "6edd31b8201cbd29c437df73fcd3345d"

def init_db():
    if os.path.exists(DB_PATH):
        os.remove(DB_PATH)
    conn = sqlite3.connect(DB_PATH)
    conn.execute("PRAGMA journal_mode = WAL;")
    conn.execute("""
        CREATE TABLE IF NOT EXISTS movies (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            tmdb_id INTEGER UNIQUE,
            title TEXT NOT NULL,
            original_title TEXT,
            year INTEGER,
            rating REAL DEFAULT 0.0,
            popularity REAL DEFAULT 0.0,
            duration_minutes INTEGER DEFAULT 0,
            synopsis TEXT,
            poster_url TEXT,
            backdrop_url TEXT,
            genres TEXT DEFAULT '[]',
            cast TEXT DEFAULT '[]',
            director TEXT DEFAULT '',
            country TEXT DEFAULT 'Зарубежный',
            category TEXT DEFAULT 'movies',
            streams TEXT DEFAULT '[]',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
    conn.commit()
    conn.close()

def restore():
    print("=== Восстановление стабильного состояния (80 фильмов) ===")
    init_db()

    genres_map = {
        28: "Боевик", 12: "Приключения", 16: "Мультфильм", 35: "Комедия",
        80: "Криминал", 99: "Документальный", 18: "Драма", 10751: "Семейный",
        14: "Фэнтези", 36: "История", 27: "Ужасы", 10402: "Музыка",
        9648: "Детектив", 10749: "Мелодрама", 878: "Фантастика", 53: "Триллер"
    }

    records = []
    # Загружаем 4 страницы топа популярных фильмов (80 карточек)
    for page in range(1, 5):
        url = f"https://api.themoviedb.org/3/movie/popular?api_key={API_KEY}&language=ru-RU&page={page}"
        try:
            res = requests.get(url, timeout=8).json()
            for m in res.get("results", []):
                p_path = m.get("poster_path")
                b_path = m.get("backdrop_path")
                if not p_path:
                    continue

                title = m.get("title") or m.get("original_title") or "Без названия"
                orig_title = m.get("original_title") or title
                rel_date = m.get("release_date") or "2026"
                year = int(rel_date.split("-")[0]) if len(rel_date) >= 4 else 2026

                poster_url = f"https://image.tmdb.org/t/p/w500{p_path}"
                backdrop_url = f"https://image.tmdb.org/t/p/original{b_path}" if b_path else poster_url

                g_names = [genres_map.get(gid) for gid in m.get("genre_ids", []) if gid in genres_map]
                streams = [{
                    "media_type": "direct_http",
                    "playback_url": f"https://archive.org/download/movie_{m.get('id')}/video.mp4",
                    "source_id": "archive_org",
                    "title": f"[Direct] {title} ({year})",
                    "year": year
                }]

                records.append((
                    m.get("id"),
                    title,
                    orig_title,
                    year,
                    round(m.get("vote_average", 0.0), 1),
                    round(m.get("popularity", 0.0), 2),
                    110,
                    m.get("overview") or "Художественный фильм.",
                    poster_url,
                    backdrop_url,
                    json.dumps(g_names, ensure_ascii=False),
                    json.dumps([], ensure_ascii=False),
                    "",
                    "Зарубежный",
                    "movies",
                    json.dumps(streams, ensure_ascii=False)
                ))
        except Exception:
            pass

    conn = sqlite3.connect(DB_PATH)
    query = """
        INSERT INTO movies
        (tmdb_id, title, original_title, year, rating, popularity, duration_minutes,
         synopsis, poster_url, backdrop_url, genres, cast, director, country, category, streams)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """
    conn.executemany(query, records)
    conn.commit()
    count = conn.execute("SELECT COUNT(*) FROM movies").fetchone()[0]
    conn.close()
    print(f"Готово! В базу записано {count} карточек.")

if __name__ == "__main__":
    restore()
