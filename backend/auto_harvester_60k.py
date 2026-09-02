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
from concurrent.futures import ThreadPoolExecutor, as_completed

DB_PATH = "/data/data/com.termux/files/home/projects/media-parser/catalog.db"
ENV_PATH = "/data/data/com.termux/files/home/projects/media-parser/.env"
STATE_PATH = "/data/data/com.termux/files/home/projects/media-parser/harvester_state.json"
TARGET_TOTAL = 60000

# 1. Получение API ключа
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
    if not text:
        return False
    return len(re.findall(r'[а-яА-ЯёЁ]', str(text))) >= 2

def http_get_json(url: str, timeout: int = 7, retries: int = 2):
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
    )
    for _ in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX) as response:
                if response.status == 200:
                    return json.loads(response.read().decode("utf-8"))
        except Exception:
            time.sleep(0.3)
    return None

def resolve_magnet_stream(title: str, year: int) -> list:
    streams = []
    clean_title = "".join(c for c in title if c.isalnum() or c in " -_").strip()
    
    # 1. YTS
    try:
        yts_url = f"https://yts.mx/api/v2/list_movies.json?query_term={urllib.parse.quote(clean_title)}&limit=1"
        yts_data = http_get_json(yts_url, timeout=3, retries=1)
        if yts_data and yts_data.get("data", {}).get("movies"):
            m = yts_data["data"]["movies"][0]
            for t in m.get("torrents", [])[:1]:
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
    return streams

def fetch_details_tmdb(t_id: int, is_tv: bool):
    media_type_url = "tv" if is_tv else "movie"
    url = f"https://api.themoviedb.org/3/{media_type_url}/{t_id}?api_key={TMDB_API_KEY}&language=ru-RU&append_to_response=credits"
    data = http_get_json(url, timeout=6)
    if not data:
        return None

    title = data.get("title") if not is_tv else data.get("name")
    orig_title = data.get("original_title") if not is_tv else data.get("original_name")
    synopsis = data.get("overview", "")

    if not has_cyrillic(title):
        return None

    date_str = data.get("release_date") if not is_tv else data.get("first_air_date")
    year = int(date_str[:4]) if date_str and len(date_str) >= 4 else 0

    genres = [g["name"] for g in data.get("genres", [])]
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
    for c in credits.get("cast", [])[:8]:
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

GENRE_TARGETS = [
    # 1. Основные и расширенные жанровые категории
    {"name": "Action", "movie_id": 28, "tv_id": 10759, "params": {}},
    {"name": "Adventure", "movie_id": 12, "tv_id": 10759, "params": {}},
    {"name": "Comedy", "movie_id": 35, "tv_id": 35, "params": {}},
    {"name": "Drama", "movie_id": 18, "tv_id": 18, "params": {}},
    {"name": "Animation", "movie_id": 16, "tv_id": 16, "params": {}},
    {"name": "Sci-Fi & Fantasy", "movie_id": 878, "tv_id": 10765, "params": {}},
    {"name": "Fantasy", "movie_id": 14, "tv_id": 10765, "params": {}},
    {"name": "Crime", "movie_id": 80, "tv_id": 80, "params": {}},
    {"name": "Mystery & Detective", "movie_id": 9648, "tv_id": 9648, "params": {}},
    {"name": "Thriller", "movie_id": 53, "tv_id": 9648, "params": {}},
    {"name": "Family & Kids", "movie_id": 10751, "tv_id": 10762, "params": {}},
    {"name": "Romance & Melodrama", "movie_id": 10749, "tv_id": 10766, "params": {}},
    {"name": "Horror", "movie_id": 27, "tv_id": 10765, "params": {}},
    {"name": "History & Biography", "movie_id": 36, "tv_id": 18, "params": {}},
    {"name": "War & Military", "movie_id": 10752, "tv_id": 10768, "params": {}},
    {"name": "Western", "movie_id": 37, "tv_id": 37, "params": {}},
    {"name": "Music & Musical", "movie_id": 10402, "tv_id": 18, "params": {}},
    {"name": "Documentary", "movie_id": 99, "tv_id": 99, "params": {}},
    # 2. Региональные направления с популярным дубляжом и локализацией
    {"name": "Anime (Japanese Animation)", "movie_id": 16, "tv_id": 16, "params": {"with_original_language": "ja"}},
    {"name": "K-Drama (Korean Series & Movies)", "movie_id": None, "tv_id": None, "params": {"with_original_language": "ko"}},
    {"name": "Turkish Series & Cinema (Dizi)", "movie_id": None, "tv_id": None, "params": {"with_original_language": "tr"}},
    {"name": "Russian Cinema & TV", "movie_id": None, "tv_id": None, "params": {"with_original_language": "ru"}},
    {"name": "European Cinema (FR/IT/ES/DE/GB)", "movie_id": None, "tv_id": None, "params": {"with_origin_country": "FR|IT|ES|DE|GB"}},
    {"name": "Chinese Cinema & Donghua", "movie_id": None, "tv_id": None, "params": {"with_original_language": "zh"}},
]

SORT_STRATEGIES = [
    {"id": "pop_desc", "sort_by": "popularity.desc", "vote_count_gte": 5, "for_tv": True, "for_movie": True},
    {"id": "vote_desc", "sort_by": "vote_average.desc", "vote_count_gte": 10, "for_tv": True, "for_movie": True},
    {"id": "votes_cnt_desc", "sort_by": "vote_count.desc", "vote_count_gte": 10, "for_tv": True, "for_movie": True},
    {"id": "rev_desc", "sort_by": "revenue.desc", "vote_count_gte": 5, "for_tv": False, "for_movie": True},
    {"id": "recent_movie", "sort_by": "primary_release_date.desc", "vote_count_gte": 3, "for_tv": False, "for_movie": True},
    {"id": "recent_tv", "sort_by": "first_air_date.desc", "vote_count_gte": 3, "for_tv": True, "for_movie": False},
]

def load_state():
    if os.path.exists(STATE_PATH):
        try:
            with open(STATE_PATH, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {"mode": "genre_deep_scan", "genre_index": 0, "sort_index": 0, "is_tv": False, "current_page": 1}

def save_state(year, page, is_tv):
    with open(STATE_PATH, "w", encoding="utf-8") as f:
        json.dump({"mode": "year_scan", "current_year": year, "current_page": page, "is_tv": is_tv, "updated_at": datetime.now().isoformat()}, f, indent=2)

def save_genre_state(genre_idx, genre_name, sort_idx, sort_by, is_tv, page):
    with open(STATE_PATH, "w", encoding="utf-8") as f:
        json.dump({
            "mode": "genre_deep_scan",
            "genre_index": genre_idx,
            "genre_name": genre_name,
            "sort_index": sort_idx,
            "sort_by": sort_by,
            "is_tv": is_tv,
            "current_page": page,
            "updated_at": datetime.now().isoformat()
        }, f, indent=2)

def run_genre_deep_scan(conn, cursor, existing_ids, existing_titles, current_count, state):
    print("=" * 60)
    print("   РЕЖИМ: ГЛУБОКОЕ ЖАНРОВОЕ СКАНИРОВАНИЕ (GENRE DEEP SCAN)")
    print("=" * 60)

    start_g_idx = state.get("genre_index", 0)
    start_s_idx = state.get("sort_index", 0)
    start_is_tv = state.get("is_tv", False)
    start_page = state.get("current_page", 1)

    for g_idx in range(start_g_idx, len(GENRE_TARGETS)):
        genre_spec = GENRE_TARGETS[g_idx]
        g_name = genre_spec["name"]

        s_start = start_s_idx if g_idx == start_g_idx else 0
        for s_idx in range(s_start, len(SORT_STRATEGIES)):
            sort_spec = SORT_STRATEGIES[s_idx]
            sort_by = sort_spec["sort_by"]
            vote_gte = sort_spec["vote_count_gte"]

            types_to_scan = []
            if sort_spec.get("for_movie", True):
                types_to_scan.append(False)
            if sort_spec.get("for_tv", True):
                types_to_scan.append(True)

            tv_start = start_is_tv if (g_idx == start_g_idx and s_idx == start_s_idx) else None

            for is_tv in types_to_scan:
                if tv_start is not None and is_tv != tv_start:
                    continue
                tv_start = None

                genre_id = genre_spec.get("tv_id") if is_tv else genre_spec.get("movie_id")
                if not genre_id and not genre_spec.get("params"):
                    continue

                media_type_url = "tv" if is_tv else "movie"
                p_start = start_page if (g_idx == start_g_idx and s_idx == start_s_idx and is_tv == start_is_tv) else 1
                max_pages = 100

                print(f"\n🎯 [Срез] Жанр: {g_name} ({g_idx+1}/{len(GENRE_TARGETS)}) | Тип: {'TV' if is_tv else 'Movie'} | Сортировка: {sort_by} | Стр: {p_start}..{max_pages}")

                for page in range(p_start, max_pages + 1):
                    if current_count >= TARGET_TOTAL:
                        print(f"\n🎉 ЦЕЛЕВОЙ ОБЪЕМ {TARGET_TOTAL} ДОСТИГНУТ!")
                        return current_count

                    query_params = {
                        "api_key": TMDB_API_KEY,
                        "language": "ru-RU",
                        "sort_by": sort_by,
                        "page": page,
                        "vote_count.gte": vote_gte,
                    }
                    if genre_id:
                        query_params["with_genres"] = genre_id
                    if "params" in genre_spec:
                        query_params.update(genre_spec["params"])

                    discover_url = f"https://api.themoviedb.org/3/discover/{media_type_url}?" + urllib.parse.urlencode(query_params)
                    data = http_get_json(discover_url)
                    if not data or not data.get("results"):
                        break

                    items_to_fetch = []
                    for item in data["results"]:
                        t_id = item["id"]
                        t_name = item.get("title") if not is_tv else item.get("name")
                        if not t_name:
                            continue
                        date_str = item.get("release_date") if not is_tv else item.get("first_air_date")
                        year = int(date_str[:4]) if date_str and len(date_str) >= 4 else 0
                        title_key = (t_name.strip().lower(), year)

                        if t_id not in existing_ids and title_key not in existing_titles and has_cyrillic(t_name):
                            items_to_fetch.append(t_id)
                            existing_ids.add(t_id)
                            existing_titles.add(title_key)

                    if items_to_fetch:
                        collected = []
                        with ThreadPoolExecutor(max_workers=6) as executor:
                            futures = [executor.submit(fetch_details_tmdb, t_id, is_tv) for t_id in items_to_fetch]
                            for f in as_completed(futures):
                                res = f.result()
                                if res:
                                    collected.append(res)

                        if collected:
                            try:
                                conn.execute("BEGIN TRANSACTION;")
                                for item in collected:
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
                                current_count += len(collected)
                                print(f"[{datetime.now().strftime('%H:%M:%S')}] Жанр: {g_name} | {'TV' if is_tv else 'MOV'} | {sort_by[:12]} | стр {page}/{max_pages} | +{len(collected)} тайтлов | Всего в базе: {current_count}", flush=True)
                            except Exception as e:
                                conn.rollback()
                                print(f"⚠️ Ошибка транзакции: {e}", flush=True)

                    save_genre_state(g_idx, g_name, s_idx, sort_by, is_tv, page)
                    time.sleep(0.2)

                start_page = 1

    return current_count

def main():
    print("="*60)
    print("      АВТОМАТИЧЕСКИЙ ХАРВЕСТЕР КАТАЛОГА (ЦЕЛЬ: 60 000+)")
    print("="*60)

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE);")
    cursor = conn.cursor()

    existing_ids = {row[0] for row in cursor.execute("SELECT tmdb_id FROM movies WHERE tmdb_id IS NOT NULL;").fetchall()}
    existing_titles = {(row[0].strip().lower(), row[1]) for row in cursor.execute("SELECT title, year FROM movies;").fetchall()}
    
    current_count = len(existing_titles)
    print(f"Стартовое количество записей в базе: {current_count}")

    if current_count >= TARGET_TOTAL:
        print(f"🎉 ЦЕЛЕВОЙ ОБЪЕМ {TARGET_TOTAL} УЖЕ ДОСТИГНУТ!")
        conn.close()
        return

    state = load_state()
    mode = state.get("mode", "genre_deep_scan")

    if mode == "genre_deep_scan":
        current_count = run_genre_deep_scan(conn, cursor, existing_ids, existing_titles, current_count, state)
    else:
        start_year = state.get("current_year", 2026)
        start_page = state.get("current_page", 1)
        is_tv_mode = state.get("is_tv", False)

        print(f"Возобновление с: Год {start_year}, Страница {start_page}, Режим {'TV' if is_tv_mode else 'MOVIE'}")

        for year in range(start_year, 1950, -1):
            for is_tv in [False, True]:
                if year == start_year and is_tv != is_tv_mode:
                    continue

                media_type_url = "tv" if is_tv else "movie"
                year_param = "first_air_date_year" if is_tv else "primary_release_year"
                
                page_start = start_page if year == start_year else 1
                max_pages_for_year = 40

                for page in range(page_start, max_pages_for_year + 1):
                    if current_count >= TARGET_TOTAL:
                        print(f"🎉 ЦЕЛЕВОЙ ОБЪЕМ {TARGET_TOTAL} ДОСТИГНУТ!")
                        conn.close()
                        return

                    discover_url = (
                        f"https://api.themoviedb.org/3/discover/{media_type_url}?"
                        f"api_key={TMDB_API_KEY}&language=ru-RU&sort_by=popularity.desc&"
                        f"{year_param}={year}&page={page}&vote_count.gte=5"
                    )

                    data = http_get_json(discover_url)
                    if not data or not data.get("results"):
                        break

                    items_to_fetch = []
                    for item in data["results"]:
                        t_id = item["id"]
                        t_name = item.get("title") if not is_tv else item.get("name")
                        if not t_name:
                            continue
                        title_key = (t_name.strip().lower(), year)
                        if t_id not in existing_ids and title_key not in existing_titles and has_cyrillic(t_name):
                            items_to_fetch.append(t_id)
                            existing_ids.add(t_id)
                            existing_titles.add(title_key)

                    if items_to_fetch:
                        collected = []
                        with ThreadPoolExecutor(max_workers=6) as executor:
                            futures = [executor.submit(fetch_details_tmdb, t_id, is_tv) for t_id in items_to_fetch]
                            for f in as_completed(futures):
                                res = f.result()
                                if res:
                                    collected.append(res)

                        if collected:
                            try:
                                conn.execute("BEGIN TRANSACTION;")
                                for item in collected:
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
                                current_count += len(collected)
                                print(f"[{datetime.now().strftime('%H:%M:%S')}] Год: {year} | {'TV' if is_tv else 'MOV'} стр {page} | +{len(collected)} тайтлов | Всего в базе: {current_count}")
                            except Exception as e:
                                conn.rollback()
                                print(f"⚠️ Ошибка транзакции: {e}")

                    save_state(year, page, is_tv)
                    time.sleep(0.2)

                start_page = 1

        if current_count < TARGET_TOTAL:
            state = {"mode": "genre_deep_scan", "genre_index": 0, "sort_index": 0, "is_tv": False, "current_page": 1}
            current_count = run_genre_deep_scan(conn, cursor, existing_ids, existing_titles, current_count, state)

    conn.close()
    print(f"Завершено сканирование. Итоговый объем базы: {current_count}")

if __name__ == "__main__":
    main()
