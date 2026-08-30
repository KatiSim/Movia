#!/usr/bin/env python3
"""
Movia Content & Stream Auto-Updater
Daily background daemon and cron worker that scans catalog titles,
resolves fresh torrent / CDN streams, updates media_catalog.db,
and warms streams_cache.db.
"""

import os
import sys
import time
import json
import sqlite3
import argparse
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path

DIR = Path(__file__).resolve().parent
DB_PATH = DIR / "media_catalog.db"
CACHE_DB_PATH = DIR / "stream_cache" / "streams_cache.db"
LOG_DIR = DIR / "logs"
LOG_DIR.mkdir(parents=True, exist_ok=True)
LOG_FILE = LOG_DIR / "updater.log"

logger = logging.getLogger("content_updater")
logger.setLevel(logging.INFO)
if not logger.handlers:
    rfh = RotatingFileHandler(LOG_FILE, maxBytes=10 * 1024 * 1024, backupCount=3, encoding="utf-8")
    rfh.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s - %(message)s"))
    logger.addHandler(rfh)
    sh = logging.StreamHandler()
    sh.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s - %(message)s"))
    logger.addHandler(sh)

try:
    from torrent_resolver import resolve_torrents_for_query, enrich_magnet_with_trackers
except ImportError:
    sys.path.insert(0, str(DIR))
    from torrent_resolver import resolve_torrents_for_query, enrich_magnet_with_trackers

def warm_cache_db(cache_key: str, streams: list, ttl_hours: int = 72):
    try:
        CACHE_DB_PATH.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(str(CACHE_DB_PATH))
        cur = conn.cursor()
        cur.execute("CREATE TABLE IF NOT EXISTS streams_cache (cache_key TEXT PRIMARY KEY, streams_json TEXT NOT NULL, expires_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);")
        now = int(time.time())
        expires_at = now + (ttl_hours * 3600)
        cur.execute(
            "INSERT OR REPLACE INTO streams_cache (cache_key, streams_json, expires_at, updated_at) VALUES (?, ?, ?, ?);",
            (cache_key, json.dumps(streams, ensure_ascii=False), expires_at, now)
        )
        conn.commit()
        conn.close()
    except Exception as e:
        logger.warning(f"Cache warm error for {cache_key}: {e}")

def update_catalog_batch(limit: int = 200, priority_recent: bool = True):
    if not DB_PATH.exists():
        logger.error(f"Catalog DB not found at {DB_PATH}")
        return

    conn = sqlite3.connect(str(DB_PATH))
    cur = conn.cursor()

    order_clause = "ORDER BY year DESC, rating DESC" if priority_recent else "ORDER BY rating DESC"
    cur.execute(f"""
        SELECT id, title, year, category, rating, tmdb_id
        FROM movies
        WHERE (playback_url IS NULL OR playback_url = '' OR seeders < 50)
          AND title IS NOT NULL AND title != ''
        {order_clause}
        LIMIT ?;
    """, (limit,))

    rows = cur.fetchall()
    total = len(rows)
    logger.info(f"🔄 Запуск обновления потоков для {total} тайтлов (priority_recent={priority_recent})...")

    updated_count = 0
    start_time = time.time()

    for idx, (m_id, title, year, category, rating, tmdb_id) in enumerate(rows, 1):
        try:
            effective_year = year or 2024
            streams = resolve_torrents_for_query(title=title, year=effective_year, category=category or "movies")
            if streams:
                best = streams[0]
                streams_json = json.dumps(streams, ensure_ascii=False)
                cur.execute("""
                    UPDATE movies
                    SET playback_url = ?,
                        streams = ?,
                        voice = ?,
                        quality = ?,
                        seeders = ?,
                        link_verified = 1,
                        link_updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?;
                """, (
                    best["url"],
                    streams_json,
                    best.get("voice", "Дубляж"),
                    best.get("quality", "1080p"),
                    int(best.get("seeders", 100)),
                    m_id
                ))
                conn.commit()
                cache_key = f"{title.strip().lower()}_{effective_year}_{category}_sNone_eNone"
                warm_cache_db(cache_key, streams)
                updated_count += 1
                logger.info(f"[{idx}/{total}] ✅ Обновлен: '{title}' ({effective_year}) -> {best.get('voice')} {best.get('quality')} ({len(streams)} потоков)")
            else:
                logger.debug(f"[{idx}/{total}] ⚠️ Нет новых потоков для: '{title}' ({effective_year})")
        except Exception as e:
            logger.error(f"[{idx}/{total}] ❌ Ошибка для '{title}': {e}")

    conn.close()
    elapsed = time.time() - start_time
    logger.info(f"🎉 Завершено обновление: обновлено {updated_count}/{total} тайтлов за {elapsed:.1f} сек.")

def main():
    parser = argparse.ArgumentParser(description="Movia Stream Auto-Updater")
    parser.add_argument("--limit", type=int, default=200, help="Количество тайтлов для обновления за один прогон")
    parser.add_argument("--priority-recent", action="store_true", default=True, help="Приоритет новинкам последних лет")
    args = parser.parse_args()

    update_catalog_batch(limit=args.limit, priority_recent=args.priority_recent)

if __name__ == "__main__":
    main()
