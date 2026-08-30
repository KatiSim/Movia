import urllib.request
import urllib.parse
import json
import sqlite3
import os
import sys
import ssl
import time
import re
from datetime import datetime

DB_PATH = "/data/data/com.termux/files/home/projects/media-parser/media_catalog.db"
ENV_PATH = "/data/data/com.termux/files/home/projects/media-parser/.env"

# 1. Извлечение рабочего ключа из .env
def get_tmdb_api_key():
    if os.path.exists(ENV_PATH):
        with open(ENV_PATH, "r", encoding="utf-8") as f:
            for line in f:
                match = re.search(r'(?:TMDB_API_KEY|API_KEY)\s*=\s*[\'"]?([a-f0-9]{32})[\'"]?', line)
                if match:
                    return match.group(1)
    # Поиск по всем .py файлам
    for fname in ["restore_all.py", "config.py", "restore_working_state.py"]:
        p = os.path.join("/data/data/com.termux/files/home/projects/media-parser", fname)
        if os.path.exists(p):
            with open(p, "r", encoding="utf-8", errors="ignore") as f:
                matches = re.findall(r'[a-f0-9]{32}', f.read())
                for m in matches:
                    if m != "b997cbe6072fa6ec0c5418b628db9454":
                        return m
    return ""

TMDB_API_KEY = get_tmdb_api_key()
print(f"Используемый TMDB API Key: {TMDB_API_KEY[:6]}...{TMDB_API_KEY[-4:] if TMDB_API_KEY else 'НЕ НАЙДЕН'}")

if not TMDB_API_KEY:
    print("❌ ОШИБКА: Не удалось обнаружить ключ TMDB!")
    sys.exit(1)

# SSL контекст для стабильной работы в Termux
SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

def http_get_json(url: str, timeout: int = 8):
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX) as response:
            if response.status == 200:
                return json.loads(response.read().decode("utf-8"))
    except Exception:
        pass
    return None

def resolve_magnet_stream(title: str, year: int) -> list:
    """Поиск magnet-ссылок через YTS API и Apibay"""
    streams = []
    clean_title = "".join(c for c in title if c.isalnum() or c in " -_").strip()
    query = f"{clean_title} {year}" if year else clean_title

    # Поиск YTS
    try:
        yts_url = f"https://yts.mx/api/v2/list_movies.json?query_term={urllib.parse.quote(clean_title)}&limit=2"
        yts_data = http_get_json(yts_url, timeout=5)
        if yts_data and yts_data.get("data", {}).get("movies"):
            for m in yts_data["data"]["movies"]:
                for t in m.get("torrents", []):
                    h = t.get("hash")
                    q = t.get("quality", "1080p")
                    mag = f"magnet:?xt=urn:btih:{h}&dn={urllib.parse.quote(m.get('title'))}"
                    streams.append({
                        "title": f"[YTS {q}] {m.get('title')} ({m.get('year')})",
                        "playback_url": mag,
                        "source_id": "yts",
                        "source_page": m.get("url", ""),
                        "media_type": "torrent_magnet"
                    })
    except Exception:
        pass

    # Поиск Apibay (TPB)
    if not streams:
        try:
            apibay_url = f"https://apibay.org/q.php?q={urllib.parse.quote(query)}"
            ab_data = http_get_json(apibay_url, timeout=5)
            if ab_data and isinstance(ab_data, list) and ab_data[0].get("id") != "0":
                for item in ab_data[:2]:
                    h = item.get("info_hash")
                    name = item.get("name")
                    if h:
                        mag = f"magnet:?xt=urn:btih:{h}&dn={urllib.parse.quote(name)}"
                        streams.append({
                            "title": name,
                            "playback_url": mag,
                            "source_id": "apibay",
                            "source_page": f"https://thepiratebay.org/description.php?id={item.get('id')}",
                            "media_type": "torrent_magnet"
                        })
        except Exception:
            pass

    return streams

def fetch_details_tmdb(tmdb_id: int, is_tv: bool = False):
    """Сбор полных метаданных, жанров и каста с фото"""
    media_type_url = "tv" if is_tv else "movie"
    url = f"https://api.themoviedb.org/3/{media_type_url}/{tmdb_id}?api_key={TMDB_API_KEY}&language=ru-RU&append_to_response=credits"
    data = http_get_json(url, timeout=7)
    if not data:
        return None

    title = data.get("title") if not is_tv else data.get("name")
    orig_title = data.get("original_title") if not is_tv else data.get("original_name")

    date_str = data.get("release_date") if not is_tv else data.get("first_air_date")
    year = int(date_str[:4]) if date_str and len(date_str) >= 4 else 0

    genres = [g["name"] for g in data.get("genres", [])]
    duration = data.get("runtime") if not is_tv else (data.get("episode_run_time", [45])[0] if data.get("episode_run_time") else 45)

    poster_path = data.get("poster_path")
    backdrop_path = data.get("backdrop_path")
    poster_url = f"https://image.tmdb.org/t/p/w500{poster_path}" if poster_path else None
    backdrop_url = f"https://image.tmdb.org/t/p/original{backdrop_path}" if backdrop_path else None

    # Обработка актеров (до 10 человек с фото)
    credits = data.get("credits", {})
    cast_list = []
    for c in credits.get("cast", [])[:10]:
        c_photo = f"https://image.tmdb.org/t/p/w342{c['profile_path']}" if c.get("profile_path") else None
        cast_list.append({
            "name": c.get("name"),
            "photo_url": c_photo,
            "role": c.get("character")
        })

    director = ""
    for cr in credits.get("crew", []):
        if cr.get("job") in ["Director", "Executive Producer"]:
            director = cr.get("name", "")
            break

    countries = [c.get("name") for c in data.get("production_countries", [])]
    country = countries[0] if countries else "United States of America"

    category = "tv_series" if is_tv else "movies"
    if any(g.lower() in ["мультфильм", "аниме", "анимация"] for g in genres):
        category = "anime" if country.lower() == "japan" else "animation"

    return {
        "tmdb_id": tmdb_id,
        "title": title or orig_title,
        "original_title": orig_title or title,
        "year": year,
        "rating": round(float(data.get("vote_average", 0.0)), 1),
        "duration_minutes": duration or 0,
        "synopsis": data.get("overview", ""),
        "poster_url": poster_url,
        "backdrop_url": backdrop_url,
        "genres": genres,
        "cast": cast_list,
        "director": director,
        "country": country,
        "category": category
    }

def run_import(pages: int = 1):
    print("\n=== [1/3] WAL CHECKPOINT ПЕРЕД ЗАПИСЬЮ ===")
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE);")
    cursor = conn.cursor()

    existing_ids = {row[0] for row in cursor.execute("SELECT tmdb_id FROM movies WHERE tmdb_id IS NOT NULL;").fetchall()}
    existing_titles = {(row[0].strip().lower(), row[1]) for row in cursor.execute("SELECT title, year FROM movies;").fetchall()}
    print(f"Текущих записей в базе: {len(existing_titles)}")

    collected_items = []

    print(f"\n=== [2/3] СБОР КОНТЕНТА ИЗ TMDB ({pages} СТР.) ===")
    for page in range(1, pages + 1):
        print(f"--- Скачивание страницы {page} ---")

        # 1. Топ фильмы
        movie_url = f"https://api.themoviedb.org/3/movie/top_rated?api_key={TMDB_API_KEY}&language=ru-RU&page={page}"
        m_data = http_get_json(movie_url)
        if m_data and m_data.get("results"):
            for item in m_data["results"]:
                t_id = item["id"]
                title_key = (item.get("title", "").strip().lower(), int(item.get("release_date", "0000")[:4]) if item.get("release_date") else 0)
                if t_id not in existing_ids and title_key not in existing_titles:
                    details = fetch_details_tmdb(t_id, is_tv=False)
                    if details and details["title"]:
                        streams = resolve_magnet_stream(details["original_title"], details["year"])
                        details["streams"] = streams
                        collected_items.append(details)
                        existing_ids.add(t_id)
                        print(f"  + [ФИЛЬМ] {details['title']} ({details['year']}) | Потоков: {len(streams)}")
                        time.sleep(0.12)

        # 2. Топ сериалы
        tv_url = f"https://api.themoviedb.org/3/tv/top_rated?api_key={TMDB_API_KEY}&language=ru-RU&page={page}"
        tv_data = http_get_json(tv_url)
        if tv_data and tv_data.get("results"):
            for item in tv_data["results"]:
                t_id = item["id"]
                title_key = (item.get("name", "").strip().lower(), int(item.get("first_air_date", "0000")[:4]) if item.get("first_air_date") else 0)
                if t_id not in existing_ids and title_key not in existing_titles:
                    details = fetch_details_tmdb(t_id, is_tv=True)
                    if details and details["title"]:
                        streams = resolve_magnet_stream(details["original_title"], details["year"])
                        details["streams"] = streams
                        collected_items.append(details)
                        existing_ids.add(t_id)
                        print(f"  + [СЕРИАЛ] {details['title']} ({details['year']}) | Потоков: {len(streams)}")
                        time.sleep(0.12)

    print(f"\nВсего собрано новых тайтлов: {len(collected_items)}")

    print("\n=== [3/3] ТРАНЗАКЦИОННАЯ ЗАПИСЬ В БАЗУ ===")
    if collected_items:
        try:
            conn.execute("BEGIN TRANSACTION;")
            for item in collected_items:
                cursor.execute("""
                    INSERT INTO movies (
                        tmdb_id, title, original_title, year, rating, duration_minutes,
                        synopsis, poster_url, backdrop_url, genres, [cast], director,
                        country, category, streams, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, (
                    item["tmdb_id"],
                    item["title"],
                    item["original_title"],
                    item["year"],
                    item["rating"],
                    item["duration_minutes"],
                    item["synopsis"],
                    item["poster_url"],
                    item["backdrop_url"],
                    json.dumps(item["genres"], ensure_ascii=False),
                    json.dumps(item["cast"], ensure_ascii=False),
                    item["director"],
                    item["country"],
                    item["category"],
                    json.dumps(item["streams"], ensure_ascii=False)
                ))
            conn.commit()
            print("✅ Все новые записи успешно сохранены в media_catalog.db!")
        except Exception as e:
            conn.rollback()
            print(f"❌ ОШИБКА транзакции: {e}")
            conn.close()
            sys.exit(1)

    total_count = cursor.execute("SELECT COUNT(*) FROM movies;").fetchone()[0]
    conn.close()
    print(f"\n🎉 Импорт завершен! Итоговое число записей в базе: {total_count}")

if __name__ == "__main__":
    pages = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    run_import(pages=pages)
