#!/usr/bin/env python3
"""
Clean Fake Ratings and Apply Bayesian Damping Engine.
- Sets rating = 0.0 for titles without confirmed votes or with synthetic 10.0 / 9.x values.
- Asynchronously syncs real TMDB vote_average and vote_count for top titles.
- Applies Bayesian weighted rating formula:
  WR = (v / (v + m)) * R + (m / (v + m)) * C
  where m=30 (min_votes) and C=6.5 (global_mean).
"""

import os
import sys
import json
import sqlite3
import asyncio
from pathlib import Path
import httpx
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent
DB_PATH = BASE_DIR / "catalog.db"
ENV_PATH = BASE_DIR / ".env"

load_dotenv(ENV_PATH)
TMDB_API_KEY = os.getenv("TMDB_API_KEY", "6edd31b8201cbd29c437df73fcd3345d")
TMDB_ACCESS_TOKEN = os.getenv("TMDB_ACCESS_TOKEN", "")

def calculate_authentic_rating(vote_avg: float, vote_cnt: int, min_votes: int = 30, global_mean: float = 6.5) -> float:
    if not vote_cnt or vote_cnt == 0:
        return 0.0
    if vote_cnt < min_votes:
        return round((vote_cnt / (vote_cnt + min_votes)) * vote_avg + (min_votes / (vote_cnt + min_votes)) * global_mean, 1)
    return round(vote_avg, 1)

async def sync_tmdb_ratings(batch_size: int = 50, limit: int = 2000):
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()

    # Reset synthetic unverified ratings (e.g. 10.0 with 0 votes or missing vote_count)
    print("🧹 Cleaning synthetic 10.0 and unverified ratings in catalog.db...")
    cur.execute("""
        UPDATE movies
        SET rating = 0.0
        WHERE (rating >= 9.8 AND (vote_count IS NULL OR vote_count <= 2))
           OR (rating > 0.0 AND vote_count IS NULL);
    """)
    conn.commit()
    print(f"Cleaned unverified rows: {cur.rowcount}")

    # Select titles needing rating & vote_count sync
    cur.execute("""
        SELECT id, tmdb_id, title, original_title, year, category, rating, vote_count, vote_average
        FROM movies
        WHERE tmdb_id IS NOT NULL AND (vote_count IS NULL OR vote_count = 0 OR rating = 0.0 OR rating >= 9.0)
        ORDER BY (rating * 1.5 + seeders / 1000.0) DESC
        LIMIT ?;
    """, (limit,))

    rows = [dict(r) for r in cur.fetchall()]
    total_count = len(rows)
    print(f"🎯 Syncing real TMDB ratings for {total_count} prioritized titles...")

    if total_count == 0:
        conn.close()
        return

    headers = {
        "User-Agent": "MoviaRatingCleaner/0.9.4 (Android/Termux; ru-RU)",
        "Accept": "application/json"
    }
    if TMDB_ACCESS_TOKEN:
        headers["Authorization"] = f"Bearer {TMDB_ACCESS_TOKEN}"

    semaphore = asyncio.Semaphore(25)

    async def fetch_rating(client: httpx.AsyncClient, row: dict) -> dict:
        tmdb_id = row["tmdb_id"]
        category = (row.get("category") or "").lower()
        is_tv = category in ["series", "tv_series", "limited_series"]
        endpoint = "tv" if is_tv else "movie"
        url = f"https://api.themoviedb.org/3/{endpoint}/{tmdb_id}"

        async with semaphore:
            for attempt in range(3):
                try:
                    resp = await client.get(url, params={"api_key": TMDB_API_KEY, "language": "ru-RU"}, headers=headers, timeout=10.0)
                    if resp.status_code == 200:
                        data = resp.json()
                        v_avg = float(data.get("vote_average", 0.0))
                        v_cnt = int(data.get("vote_count", 0))
                        auth_rating = calculate_authentic_rating(v_avg, v_cnt)
                        return {
                            "id": row["id"],
                            "vote_average": v_avg,
                            "vote_count": v_cnt,
                            "rating": auth_rating
                        }
                    elif resp.status_code == 404 and not is_tv:
                        # Try TV endpoint
                        alt_url = f"https://api.themoviedb.org/3/tv/{tmdb_id}"
                        alt_resp = await client.get(alt_url, params={"api_key": TMDB_API_KEY, "language": "ru-RU"}, headers=headers, timeout=10.0)
                        if alt_resp.status_code == 200:
                            data = alt_resp.json()
                            v_avg = float(data.get("vote_average", 0.0))
                            v_cnt = int(data.get("vote_count", 0))
                            auth_rating = calculate_authentic_rating(v_avg, v_cnt)
                            return {
                                "id": row["id"],
                                "vote_average": v_avg,
                                "vote_count": v_cnt,
                                "rating": auth_rating
                            }
                except Exception:
                    await asyncio.sleep(0.5)
        return {
            "id": row["id"],
            "vote_average": 0.0,
            "vote_count": 0,
            "rating": 0.0
        }

    async with httpx.AsyncClient(timeout=12.0) as client:
        for i in range(0, total_count, batch_size):
            chunk = rows[i : i + batch_size]
            tasks = [fetch_rating(client, r) for r in chunk]
            results = await asyncio.gather(*tasks)

            for res in results:
                cur.execute("""
                    UPDATE movies
                    SET rating = ?, vote_count = ?, vote_average = ?
                    WHERE id = ?;
                """, (res["rating"], res["vote_count"], res["vote_average"], res["id"]))
            conn.commit()

            processed = min(i + batch_size, total_count)
            print(f"[{processed}/{total_count}] ({processed/total_count*100:.1f}%) ratings updated.")

    conn.close()
    print("✨ Ratings cleanup and synchronization complete!")

if __name__ == "__main__":
    asyncio.run(sync_tmdb_ratings(batch_size=50, limit=2500))
