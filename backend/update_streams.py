#!/usr/bin/env python3
"""
Movia Streams Synchronizer & Zona API Batch Populator
Fills the 'streams' JSON column in catalog.db & media_catalog.db
Usage:
  python3 update_streams.py "Человек-паук: Нет пути домой" 2021
  python3 update_streams.py --top 500
"""

import sys
import json
import sqlite3
import time
from pathlib import Path
from typing import List, Dict, Any, Optional

DIR = Path(__file__).resolve().parent
CATALOG_DB = DIR / "catalog.db"
MEDIA_CATALOG_DB = DIR / "media_catalog.db"

from balancer_integration import query_zona_api

def sync_streams_for_movie(title: str, year: Optional[int] = None, media_id: Optional[str] = None) -> List[Dict[str, Any]]:
    print(f"🔍 [Zona Sync] Fetching streams for: '{title}' ({year or 'any'})...")
    streams = query_zona_api(title=title, year=year)
    if not streams:
        print(f"⚠️ [Zona Sync] No streams found for: '{title}'")
        return []

    print(f"✅ [Zona Sync] Found {len(streams)} streams for: '{title}'")
    for s in streams:
        print(f"   • [{s.get('source')}] {s.get('voice')} | {s.get('quality')} | {s.get('seeders')} seeds -> {s.get('url')[:60]}...")

    streams_json = json.dumps(streams, ensure_ascii=False)
    top_stream = streams[0]
    top_url = top_stream.get("url", "")
    top_voice = top_stream.get("voice", "Дубляж")
    top_quality = top_stream.get("quality", "1080p")
    top_seeds = top_stream.get("seeders", 100)

    for db_path in [CATALOG_DB, MEDIA_CATALOG_DB]:
        if not db_path.exists():
            continue
        try:
            conn = sqlite3.connect(str(db_path))
            cur = conn.cursor()
            if media_id:
                cur.execute("""
                    UPDATE movies
                    SET streams = ?, playback_url = ?, voice = ?, quality = ?, seeders = ?, link_verified = 1
                    WHERE id = ? OR tmdb_id = ?
                """, (streams_json, top_url, top_voice, top_quality, top_seeds, media_id, int(media_id) if media_id.isdigit() else -1))
            else:
                cur.execute("""
                    UPDATE movies
                    SET streams = ?, playback_url = ?, voice = ?, quality = ?, seeders = ?, link_verified = 1
                    WHERE (title = ? OR original_title = ?) AND (year = ? OR ? IS NULL)
                """, (streams_json, top_url, top_voice, top_quality, top_seeds, title, title, year, year))
            conn.commit()
            conn.close()
            print(f"💾 [Zona Sync] Saved to {db_path.name} (updated {cur.rowcount} rows)")
        except Exception as e:
            print(f"❌ [Zona Sync] Error updating {db_path.name}: {e}")

    return streams

def batch_sync_top_movies(limit: int = 500):
    print(f"🚀 [Zona Sync] Starting batch synchronization for top {limit} movies...")
    if not CATALOG_DB.exists():
        print(f"❌ {CATALOG_DB} does not exist!")
        return

    conn = sqlite3.connect(str(CATALOG_DB))
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()
    cur.execute("""
        SELECT id, title, year, category
        FROM movies
        WHERE streams IS NULL OR streams = '[]' OR streams = ''
        ORDER BY rating DESC, vote_count DESC
        LIMIT ?
    """, (limit,))
    rows = [dict(r) for r in cur.fetchall()]
    conn.close()

    print(f"📋 Found {len(rows)} titles needing stream synchronization.")
    for idx, r in enumerate(rows, 1):
        m_id = r["id"]
        t = r["title"]
        y = r.get("year")
        print(f"\n[{idx}/{len(rows)}] Processing '{t}' ({y})...")
        sync_streams_for_movie(title=t, year=y, media_id=m_id)
        time.sleep(0.3)

    print("\n🏁 [Zona Sync] Batch synchronization complete!")

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--top":
        lim = int(sys.argv[2]) if len(sys.argv) > 2 else 500
        batch_sync_top_movies(limit=lim)
    elif len(sys.argv) > 1:
        req_title = sys.argv[1]
        req_year = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else None
        sync_streams_for_movie(title=req_title, year=req_year)
    else:
        print("Usage: python3 update_streams.py <title> [year]")
        print("       python3 update_streams.py --top [limit]")
