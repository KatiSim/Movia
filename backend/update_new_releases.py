#!/usr/bin/env python3
"""
Movia New Releases Monitor & Catalog Updater
Polls public release APIs and enriches media_catalog.db.
"""

import sys
import time
import sqlite3
from pathlib import Path
from balancer_integration import fetch_new_releases, is_test_stream_url

DIR = Path(__file__).resolve().parent
DB_PATH = DIR / "media_catalog.db"

def update_catalog():
    if not DB_PATH.exists():
        print(f"Database {DB_PATH} not found.")
        return

    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    try:
        new_items = fetch_new_releases(limit=50)
        added = 0
        for item in new_items:
            title = item.get("title", "").strip()
            year = int(item.get("year", 2024))
            category = item.get("category", "movies")
            if not title:
                continue

            cursor.execute("SELECT id FROM movies WHERE title = ? AND year = ? LIMIT 1;", (title, year))
            if not cursor.fetchone():
                cursor.execute(
                    "INSERT INTO movies (title, original_title, year, category, rating, is_featured, streams) VALUES (?, ?, ?, ?, ?, 0, '[]');",
                    (title, title, year, category, 7.0)
                )
                added += 1
        conn.commit()
        if added > 0:
            print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] Added {added} new releases to catalog.")
        else:
            print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] Catalog is up to date (no new entries).")
    except Exception as e:
        print(f"Catalog update failed: {e}")
    finally:
        conn.close()

if __name__ == "__main__":
    print("Running on-demand catalog new releases check...")
    update_catalog()
