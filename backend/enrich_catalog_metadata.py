#!/usr/bin/env python3
"""
Movia Catalog TMDB Metadata & Poster Enrichment Engine.
Asynchronously enriches catalog.db movies with:
- High-res posters (w500)
- Backdrop images (original)
- Russian synopses
- Country of origin
- Cast & crew with character names and avatars
- Director
"""

import os
import sys
import json
import sqlite3
import asyncio
import argparse
import re
from typing import Dict, Any, List, Optional
from pathlib import Path
import httpx
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent
DB_PATH = BASE_DIR / "catalog.db"
ENV_PATH = BASE_DIR / ".env"

load_dotenv(ENV_PATH)
TMDB_API_KEY = os.getenv("TMDB_API_KEY", "6edd31b8201cbd29c437df73fcd3345d")
TMDB_ACCESS_TOKEN = os.getenv("TMDB_ACCESS_TOKEN", "")

ISO_COUNTRY_MAP = {
    "US": "США", "GB": "Великобритания", "FR": "Франция", "DE": "Германия",
    "IT": "Италия", "ES": "Испания", "RU": "Россия", "SU": "СССР",
    "KR": "Южная Корея", "JP": "Япония", "CN": "Китай", "HK": "Гонконг",
    "TW": "Тайвань", "IN": "Индия", "TR": "Турция", "CA": "Канада",
    "AU": "Австралия", "NZ": "Новая Зеландия", "BR": "Бразилия",
    "MX": "Мексика", "AR": "Аргентина", "SE": "Швеция", "DK": "Дания",
    "NO": "Норвегия", "FI": "Финляндия", "NL": "Нидерланды", "BE": "Бельгия",
    "PL": "Польша", "IE": "Ирландия", "AT": "Австрия", "CH": "Швейцария",
    "TH": "Таиланд", "ID": "Индонезия", "ZA": "ЮАР", "CZ": "Чехия",
    "HU": "Венгрия", "RO": "Румыния", "UA": "Украина", "BY": "Беларусь",
    "KZ": "Казахстан", "IL": "Израиль", "GR": "Греция", "IS": "Исландия"
}

CLEAN_TITLE_REGEX = re.compile(r'\s*\([^)]*\)|\s*\[[^\]]*\]|\s*\|\s*.*|[:/].*|\s*-\s*сезон.*', re.IGNORECASE)

def clean_title(title: str) -> str:
    cleaned = CLEAN_TITLE_REGEX.sub('', title).strip()
    return cleaned if len(cleaned) >= 2 else title.strip()

def map_country(country_data: List[Dict[str, Any]], origin_country: Optional[List[str]] = None) -> Optional[str]:
    if country_data:
        for c in country_data:
            iso = c.get("iso_3166_1", "").upper()
            if iso in ISO_COUNTRY_MAP:
                return ISO_COUNTRY_MAP[iso]
        first = country_data[0].get("name")
        if first:
            return first

    if origin_country:
        for iso in origin_country:
            iso_upper = iso.upper()
            if iso_upper in ISO_COUNTRY_MAP:
                return ISO_COUNTRY_MAP[iso_upper]

    return None

class TMDBEnricher:
    def __init__(self, concurrency: int = 25, delay: float = 0.03):
        self.semaphore = asyncio.Semaphore(concurrency)
        self.delay = delay
        self.headers = {
            "User-Agent": "MoviaParser/0.9.3 (Android/Termux; ru-RU)",
            "Accept": "application/json"
        }
        if TMDB_ACCESS_TOKEN and len(TMDB_ACCESS_TOKEN) > 50:
            self.headers["Authorization"] = f"Bearer {TMDB_ACCESS_TOKEN}"

    async def fetch_json(self, client: httpx.AsyncClient, url: str, params: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        async with self.semaphore:
            if self.delay > 0:
                await asyncio.sleep(self.delay)
            params["api_key"] = TMDB_API_KEY
            for attempt in range(3):
                try:
                    resp = await client.get(url, params=params, headers=self.headers, timeout=12.0)
                    if resp.status_code == 200:
                        return resp.json()
                    elif resp.status_code == 429:
                        await asyncio.sleep(1.5 * (attempt + 1))
                    elif resp.status_code == 404:
                        return None
                except Exception:
                    await asyncio.sleep(0.5 * (attempt + 1))
            return None

    async def enrich_item(self, client: httpx.AsyncClient, row: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        row_id = row["id"]
        tmdb_id = row.get("tmdb_id")
        title = row.get("title", "").strip()
        original_title = row.get("original_title") or ""
        year = row.get("year")
        category = (row.get("category") or "").lower()
        is_tv = category in ["series", "tv_series", "limited_series"] or "сериал" in title.lower()

        tmdb_data = None
        details_type = "tv" if is_tv else "movie"

        # 1. Direct fetch if tmdb_id exists
        if tmdb_id:
            details_url = f"https://api.themoviedb.org/3/{details_type}/{tmdb_id}"
            tmdb_data = await self.fetch_json(client, details_url, {"language": "ru-RU", "append_to_response": "credits"})
            if not tmdb_data and is_tv:
                details_url = f"https://api.themoviedb.org/3/movie/{tmdb_id}"
                tmdb_data = await self.fetch_json(client, details_url, {"language": "ru-RU", "append_to_response": "credits"})
                if tmdb_data:
                    details_type = "movie"

        # 2. Search if no data yet
        if not tmdb_data:
            search_query = clean_title(title)
            search_type = "tv" if is_tv else "movie"
            search_url = f"https://api.themoviedb.org/3/search/{search_type}"

            search_params = {"query": search_query, "language": "ru-RU"}
            if year and year > 1900:
                if search_type == "movie":
                    search_params["year"] = year
                else:
                    search_params["first_air_date_year"] = year

            search_res = await self.fetch_json(client, search_url, search_params)
            results = search_res.get("results", []) if search_res else []

            # Fallback 1: Search without year constraint
            if not results and (search_params.get("year") or search_params.get("first_air_date_year")):
                search_params.pop("year", None)
                search_params.pop("first_air_date_year", None)
                search_res = await self.fetch_json(client, search_url, search_params)
                results = search_res.get("results", []) if search_res else []

            # Fallback 2: Search with original title
            if not results and original_title and len(original_title) > 2:
                search_params["query"] = clean_title(original_title)
                search_res = await self.fetch_json(client, search_url, search_params)
                results = search_res.get("results", []) if search_res else []

            # Fallback 3: Try opposite type (movie vs tv)
            if not results:
                alt_type = "movie" if is_tv else "tv"
                alt_url = f"https://api.themoviedb.org/3/search/{alt_type}"
                search_params["query"] = search_query
                search_res = await self.fetch_json(client, alt_url, search_params)
                results = search_res.get("results", []) if search_res else []
                if results:
                    details_type = alt_type

            if results:
                matched_id = results[0].get("id")
                if matched_id:
                    details_url = f"https://api.themoviedb.org/3/{details_type}/{matched_id}"
                    tmdb_data = await self.fetch_json(client, details_url, {"language": "ru-RU", "append_to_response": "credits"})

        if not tmdb_data:
            return None

        # Parse extracted data
        found_tmdb_id = tmdb_data.get("id")
        poster_path = tmdb_data.get("poster_path")
        backdrop_path = tmdb_data.get("backdrop_path")
        overview = (tmdb_data.get("overview") or "").strip()

        poster_url = f"https://image.tmdb.org/t/p/w500{poster_path}" if poster_path else None
        backdrop_url = f"https://image.tmdb.org/t/p/original{backdrop_path}" if backdrop_path else poster_url

        prod_countries = tmdb_data.get("production_countries", [])
        orig_countries = tmdb_data.get("origin_country", [])
        country = map_country(prod_countries, orig_countries)

        raw_vote_avg = float(tmdb_data.get("vote_average", 0.0))
        raw_vote_cnt = int(tmdb_data.get("vote_count", 0))
        auth_rating = round(raw_vote_avg, 1)

        # Durations & Seasons
        runtime = int(tmdb_data.get("runtime") or (tmdb_data.get("episode_run_time") or [0])[0] or 0)
        seasons_count = int(tmdb_data.get("number_of_seasons") or 0)
        episodes_count = int(tmdb_data.get("number_of_episodes") or 0)

        belongs_col = tmdb_data.get("belongs_to_collection")
        collection_id = int(belongs_col.get("id") or 0) if isinstance(belongs_col, dict) else None

        credits = tmdb_data.get("credits", {})
        cast_raw = credits.get("cast", [])
        cast_list = []
        for person in cast_raw[:12]:
            name = person.get("name")
            if not name:
                continue
            profile_path = person.get("profile_path")
            cast_list.append({
                "name": name,
                "role": person.get("character") or None,
                "photo_url": f"https://image.tmdb.org/t/p/w342{profile_path}" if profile_path else None,
                "photoUrl": f"https://image.tmdb.org/t/p/w342{profile_path}" if profile_path else None,
            })

        director = None
        for crew in credits.get("crew", []):
            if crew.get("job") == "Director":
                director = crew.get("name")
                break

        return {
            "id": row_id,
            "tmdb_id": found_tmdb_id,
            "poster_url": poster_url,
            "backdrop_url": backdrop_url,
            "synopsis": overview if len(overview) > 10 else None,
            "country": country,
            "cast": json.dumps(cast_list, ensure_ascii=False) if cast_list else None,
            "director": director,
            "vote_average": raw_vote_avg,
            "vote_count": raw_vote_cnt,
            "rating": auth_rating,
            "duration_minutes": runtime if runtime > 0 else None,
            "seasons_count": seasons_count if seasons_count > 0 else None,
            "episodes_count": episodes_count if episodes_count > 0 else None,
            "collection_id": collection_id
        }

def save_batch_updates(conn: sqlite3.Connection, updates: List[Dict[str, Any]]):
    if not updates:
        return
    cur = conn.cursor()
    for u in updates:
        try:
            sql = """
                UPDATE movies SET
                    tmdb_id = COALESCE(?, tmdb_id),
                    poster_url = COALESCE(?, poster_url),
                    backdrop_url = COALESCE(?, backdrop_url),
                    synopsis = COALESCE(?, synopsis),
                    country = COALESCE(?, country),
                    "cast" = COALESCE(?, "cast"),
                    director = COALESCE(?, director),
                    vote_average = ?,
                    vote_count = ?,
                    rating = ?,
                    duration_minutes = COALESCE(?, duration_minutes),
                    seasons_count = COALESCE(?, seasons_count),
                    episodes_count = COALESCE(?, episodes_count),
                    collection_id = COALESCE(?, collection_id)
                WHERE id = ?;
            """
            cur.execute(sql, (
                u["tmdb_id"],
                u["poster_url"],
                u["backdrop_url"],
                u["synopsis"],
                u["country"],
                u["cast"],
                u["director"],
                u.get("vote_average", 0.0),
                u.get("vote_count", 0),
                u.get("rating", 0.0),
                u.get("duration_minutes"),
                u.get("seasons_count"),
                u.get("episodes_count"),
                u.get("collection_id"),
                u["id"]
            ))
        except sqlite3.IntegrityError:
            sql_fallback = """
                UPDATE movies SET
                    poster_url = COALESCE(?, poster_url),
                    backdrop_url = COALESCE(?, backdrop_url),
                    synopsis = COALESCE(?, synopsis),
                    country = COALESCE(?, country),
                    "cast" = COALESCE(?, "cast"),
                    director = COALESCE(?, director),
                    vote_average = ?,
                    vote_count = ?,
                    rating = ?,
                    duration_minutes = COALESCE(?, duration_minutes),
                    seasons_count = COALESCE(?, seasons_count),
                    episodes_count = COALESCE(?, episodes_count),
                    collection_id = COALESCE(?, collection_id)
                WHERE id = ?;
            """
            cur.execute(sql_fallback, (
                u["poster_url"],
                u["backdrop_url"],
                u["synopsis"],
                u["country"],
                u["cast"],
                u["director"],
                u.get("vote_average", 0.0),
                u.get("vote_count", 0),
                u.get("rating", 0.0),
                u.get("duration_minutes"),
                u.get("seasons_count"),
                u.get("episodes_count"),
                u.get("collection_id"),
                u["id"]
            ))
    conn.commit()

async def run_enrichment(limit: Optional[int] = None, priority_only: bool = False, batch_size: int = 50):
    if not DB_PATH.exists():
        print(f"Error: Database {DB_PATH} not found!")
        sys.exit(1)

    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()

    if priority_only:
        print("🎯 Selecting Queue 1: Top-500 Popular and 2025-2026 New Releases...")
        cur.execute("""
            SELECT id, tmdb_id, title, original_title, year, category, rating, seeders
            FROM movies
            WHERE (year >= 2025 OR rating >= 7.5)
            ORDER BY (rating * 1.5 + seeders / 1000.0) DESC
            LIMIT 500;
        """)
    else:
        limit_sql = f"LIMIT {limit}" if limit else ""
        print(f"📦 Selecting titles to enrich ({limit_sql or 'Full Catalog'})...")
        cur.execute(f"""
            SELECT id, tmdb_id, title, original_title, year, category, rating, seeders
            FROM movies
            ORDER BY (rating * 1.5 + seeders / 1000.0) DESC
            {limit_sql};
        """)

    rows = [dict(r) for r in cur.fetchall()]
    total_count = len(rows)
    print(f"⚡ Total items queued for TMDB enrichment: {total_count}")

    if total_count == 0:
        print("No items to enrich.")
        conn.close()
        return

    enricher = TMDBEnricher(concurrency=25, delay=0.03)
    success_count = 0
    fail_count = 0

    async with httpx.AsyncClient(timeout=15.0) as client:
        for i in range(0, total_count, batch_size):
            chunk = rows[i : i + batch_size]
            tasks = [enricher.enrich_item(client, row) for row in chunk]
            results = await asyncio.gather(*tasks, return_exceptions=True)

            valid_updates = []
            for res in results:
                if isinstance(res, dict) and res:
                    valid_updates.append(res)
                    success_count += 1
                else:
                    fail_count += 1

            save_batch_updates(conn, valid_updates)

            processed = min(i + batch_size, total_count)
            pct = (processed / total_count) * 100
            print(f"[{processed}/{total_count}] ({pct:.1f}%) | Enriched: {success_count} | Skipped/Missing: {fail_count}")

    conn.close()
    print(f"\n✨ Enrichment finished! Successfully updated: {success_count}/{total_count}")

def main():
    parser = argparse.ArgumentParser(description="Movia TMDB Catalog Metadata & Poster Enricher")
    parser.add_argument("--priority-only", action="store_true", help="Run Queue 1 (Top 500)")
    parser.add_argument("--limit", type=int, default=None, help="Limit number of items to process")
    parser.add_argument("--batch-size", type=int, default=50, help="Batch size for concurrent processing")
    args = parser.parse_args()

    asyncio.run(run_enrichment(limit=args.limit, priority_only=args.priority_only, batch_size=args.batch_size))

if __name__ == "__main__":
    main()
