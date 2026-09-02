import sqlite3
import os
import json
import requests
import time

DB_PATH = os.path.expanduser("~/projects/media-parser/media_catalog.db")
API_KEY = os.getenv("TMDB_API_KEY", "")
def init_db():
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
            genres TEXT,
            cast TEXT,
            director TEXT,
            country TEXT,
            category TEXT DEFAULT 'movies',
            streams TEXT DEFAULT '[]',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
    conn.commit()
    conn.close()

def restore_catalog():
    init_db()
    print("=== Быстрое наполнение витрины Movia ===")
    
    headers = {"User-Agent": "Mozilla/5.0"}
    all_movies = []
    
    # Загружаем популярные фильмы и новинки (русские постеры и описания)
    for endpoint, cat in [("/movie/popular", "movies"), ("/movie/now_playing", "movies"), ("/tv/popular", "series")]:
        for page in range(1, 4):
            url = f"https://api.themoviedb.org/3{endpoint}?api_key={API_KEY}&language=ru-RU&page={page}"
            try:
                r = requests.get(url, headers=headers, timeout=8).json()
                for item in r.get("results", []):
                    poster = item.get("poster_path")
                    backdrop = item.get("backdrop_path")
                    if not poster:
                        continue
                    
                    title = item.get("title") or item.get("name") or "Без названия"
                    orig_title = item.get("original_title") or item.get("original_name") or title
                    rel_date = item.get("release_date") or item.get("first_air_date") or "2026"
                    year = int(rel_date.split("-")[0]) if len(rel_date) >= 4 else 2026
                    
                    poster_url = f"https://image.tmdb.org/t/p/w500{poster}"
                    backdrop_url = f"https://image.tmdb.org/t/p/original{backdrop}" if backdrop else poster_url
                    
                    all_movies.append((
                        item.get("id"),
                        title,
                        orig_title,
                        year,
                        round(item.get("vote_average", 7.5), 1),
                        round(item.get("popularity", 100.0), 2),
                        100,
                        item.get("overview") or "Художественный фильм.",
                        poster_url,
                        backdrop_url,
                        json.dumps(["Популярное"], ensure_ascii=False),
                        json.dumps([], ensure_ascii=False),
                        "",
                        "Зарубежный",
                        cat,
                        json.dumps([{"playback_url": f"https://archive.org/embed/{item.get('id')}", "source_id": "archive_org", "media_type": "direct_http"}], ensure_ascii=False)
                    ))
            except Exception as e:
                pass

    conn = sqlite3.connect(DB_PATH)
    query = """
        INSERT INTO movies 
        (tmdb_id, title, original_title, year, rating, popularity, duration_minutes, 
         synopsis, poster_url, backdrop_url, genres, cast, director, country, category, streams)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(tmdb_id) DO UPDATE SET
            rating = excluded.rating,
            popularity = excluded.popularity,
            poster_url = excluded.poster_url,
            backdrop_url = excluded.backdrop_url,
            synopsis = excluded.synopsis
    """
    cur = conn.executemany(query, all_movies)
    conn.commit()
    count = conn.execute("SELECT COUNT(*) FROM movies").fetchone()[0]
    conn.close()
    print(f"Готово! В базу записано {count} фильмов и сериалов.")

if __name__ == "__main__":
    restore_catalog()
