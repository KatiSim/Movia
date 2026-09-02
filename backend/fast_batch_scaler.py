import urllib.request
import urllib.parse
import json
import sqlite3
import os
import sys
import ssl
import time
import re
from concurrent.futures import ThreadPoolExecutor, as_completed

DB_PATH = "/data/data/com.termux/files/home/projects/media-parser/catalog.db"
ENV_PATH = "/data/data/com.termux/files/home/projects/media-parser/.env"

def get_tmdb_api_key():
    if os.path.exists(ENV_PATH):
        with open(ENV_PATH, "r", encoding="utf-8") as f:
            for line in f:
                match = re.search(r'(?:TMDB_API_KEY|API_KEY)\s*=\s*[\'"]?([a-f0-9]{32})[\'"]?', line)
                if match:
                    return match.group(1)
    return "6edd31b8c2c19a589e7e72183e20345d"

TMDB_API_KEY = get_tmdb_api_key()
SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

def has_cyrillic(text: str) -> bool:
    """Проверка наличия русских букв (кириллицы)"""
    if not text:
        return False
    return len(re.findall(r'[а-яА-ЯёЁ]', str(text))) >= 2

def http_get_json(url: str, timeout: int = 7):
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
    streams = []
    clean_title = "".join(c for c in title if c.isalnum() or c in " -_").strip()
    query = f"{clean_title} {year}" if year else clean_title
    
    # YTS API
    try:
        yts_url = f"https://yts.mx/api/v2/list_movies.json?query_term={urllib.parse.quote(clean_title)}&limit=2"
        yts_data = http_get_json(yts_url, timeout=4)
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

    # Apibay (The Pirate Bay)
    if not streams:
        try:
            apibay_url = f"https://apibay.org/q.php?q={urllib.parse.quote(query)}"
            ab_data = http_get_json(apibay_url, timeout=4)
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

def fetch_single_item(item_summary: dict, is_tv: bool):
    t_id = item_summary["id"]
    media_type_url = "tv" if is_tv else "movie"
    url = f"https://api.themoviedb.org/3/{media_type_url}/{t_id}?api_key={TMDB_API_KEY}&language=ru-RU&append_to_response=credits"
    data = http_get_json(url, timeout=7)
    if not data:
        return None

    title = data.get("title") if not is_tv else data.get("name")
    orig_title = data.get("original_title") if not is_tv else data.get("original_name")
    synopsis = data.get("overview", "")

    # СТРОГИЙ ФИЛЬТР: Название ОБЯЗАНО содержать русские буквы
    if not has_cyrillic(title):
        return None

    date_str = data.get("release_date") if not is_tv else data.get("first_air_date")
    year = int(date_str[:4]) if date_str and len(date_str) >= 4 else 0

    genres = [g["name"] for g in data.get("genres", [])]
    
    # Фильтруем телепередачи, новости, мыльные оперы без сюжета
    ignored_genres = ["Новости", "Ток-шоу", "News", "Talk"]
    if any(ig.lower() in [g.lower() for g in genres] for ig in ignored_genres):
        return None

    duration = data.get("runtime") if not is_tv else (data.get("episode_run_time", [45])[0] if data.get("episode_run_time") else 45)

    poster_path = data.get("poster_path")
    backdrop_path = data.get("backdrop_path")
    poster_url = f"https://image.tmdb.org/t/p/w500{poster_path}" if poster_path else None
    backdrop_url = f"https://image.tmdb.org/t/p/original{backdrop_path}" if backdrop_path else None

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

    streams = resolve_magnet_stream(orig_title or title, year)

    return {
        "tmdb_id": t_id,
        "title": title,
        "original_title": orig_title or title,
        "year": year,
        "rating": round(float(data.get("vote_average", 0.0)), 1),
        "duration_minutes": duration or 0,
        "synopsis": synopsis,
        "poster_url": poster_url,
        "backdrop_url": backdrop_url,
        "genres": genres,
        "cast": cast_list,
        "director": director,
        "country": country,
        "category": category,
        "streams": streams
    }

def run_scale(pages: int = 5):
    print("=== [1/3] ПРОВЕРКА ТЕКУЩЕГО СОСТОЯНИЯ БАЗЫ ===")
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE);")
    cursor = conn.cursor()

    existing_ids = {row[0] for row in cursor.execute("SELECT tmdb_id FROM movies WHERE tmdb_id IS NOT NULL;").fetchall()}
    existing_titles = {(row[0].strip().lower(), row[1]) for row in cursor.execute("SELECT title, year FROM movies;").fetchall()}
    print(f"Текущее количество тайтлов в базе: {len(existing_titles)}")

    targets_to_fetch = []

    print(f"\n=== [2/3] СКАНИРОВАНИЕ ТОП-РЕЙТИНГОВ TMDB (СТРАНИЦ: {pages}) ===")
    for p in range(1, pages + 1):
        # 1. Топ и популярные фильмы
        for endpoint in ["top_rated", "popular"]:
            m_data = http_get_json(f"https://api.themoviedb.org/3/movie/{endpoint}?api_key={TMDB_API_KEY}&language=ru-RU&page={p}")
            if m_data and m_data.get("results"):
                for item in m_data["results"]:
                    t_id = item["id"]
                    t_name = item.get("title", "")
                    title_key = (t_name.strip().lower(), int(item.get("release_date", "0000")[:4]) if item.get("release_date") else 0)
                    if t_id not in existing_ids and title_key not in existing_titles and has_cyrillic(t_name):
                        targets_to_fetch.append((item, False))
                        existing_ids.add(t_id)
                        existing_titles.add(title_key)

        # 2. Топ и популярные сериалы
        for endpoint in ["top_rated", "popular"]:
            tv_data = http_get_json(f"https://api.themoviedb.org/3/tv/{endpoint}?api_key={TMDB_API_KEY}&language=ru-RU&page={p}")
            if tv_data and tv_data.get("results"):
                for item in tv_data["results"]:
                    t_id = item["id"]
                    t_name = item.get("name", "")
                    title_key = (t_name.strip().lower(), int(item.get("first_air_date", "0000")[:4]) if item.get("first_air_date") else 0)
                    if t_id not in existing_ids and title_key not in existing_titles and has_cyrillic(t_name):
                        targets_to_fetch.append((item, True))
                        existing_ids.add(t_id)
                        existing_titles.add(title_key)

    print(f"Найдено русскоязычных кандидатов для загрузки: {len(targets_to_fetch)}")

    collected_records = []
    print("\nМногопоточная загрузка (только русский контент)...")
    
    with ThreadPoolExecutor(max_workers=6) as executor:
        futures = [executor.submit(fetch_single_item, item, is_tv) for item, is_tv in targets_to_fetch]
        for f in as_completed(futures):
            res = f.result()
            if res:
                collected_records.append(res)
                print(f"  + [RU] [{res['category'].upper()}] {res['title']} ({res['year']}) | Потоков: {len(res['streams'])}")

    print(f"\nУспешно подготовлено к сохранению: {len(collected_records)}")

    print("\n=== [3/3] ПАКЕТНАЯ ЗАПИСЬ В БАЗУ ДАННЫХ ===")
    if collected_records:
        try:
            conn.execute("BEGIN TRANSACTION;")
            for item in collected_records:
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
            print("✅ Все русские тайтлы успешно добавлены в media_catalog.db!")
        except Exception as e:
            conn.rollback()
            print(f"❌ ОШИБКА транзакции: {e}")
            conn.close()
            sys.exit(1)

    total_count = cursor.execute("SELECT COUNT(*) FROM movies;").fetchone()[0]
    movie_count = cursor.execute("SELECT COUNT(*) FROM movies WHERE category IN ('movies', 'movie');").fetchone()[0]
    series_count = cursor.execute("SELECT COUNT(*) FROM movies WHERE category IN ('series', 'tv_series', 'limited_series');").fetchone()[0]
    anime_count = cursor.execute("SELECT COUNT(*) FROM movies WHERE category IN ('anime', 'animation');").fetchone()[0]
    conn.close()

    print("\n" + "="*50)
    print(f"ИТОГОВОЕ СОСТОЯНИЕ РУССКОГО КАТАЛОГА: {total_count} ТАЙТЛОВ")
    print(f"  • Фильмы: {movie_count}")
    print(f"  • Сериалы: {series_count}")
    print(f"  • Аниме / Мультфильмы: {anime_count}")
    print("="*50)

if __name__ == "__main__":
    pages = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    run_scale(pages=pages)
