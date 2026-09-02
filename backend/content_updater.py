#!/usr/bin/env python3
"""Movia stream updater for the canonical catalog.db only.

This worker never writes media_catalog.db. Provider results pass the shared
structural validator and release-identity filter, then use database.save_content
for additive/idempotent persistence. The persistent stream cache is warmed only
after the catalog write is confirmed.
"""
from __future__ import annotations

import argparse
import json
import logging
import sqlite3
import sys
import time
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any, Dict, List

DIR = Path(__file__).resolve().parent
CATALOG_DB = DIR / "catalog.db"
LOG_DIR = DIR / "logs"
LOG_DIR.mkdir(parents=True, exist_ok=True)
LOG_FILE = LOG_DIR / "updater.log"

sys.path.insert(0, str(DIR))
from database import filter_streams_for_content, save_content  # noqa: E402
from stream_validation import sanitize_streams, stream_variant_key  # noqa: E402
from streamer import set_cached_streams  # noqa: E402
from torrent_resolver import resolve_torrents_for_query  # noqa: E402

logger = logging.getLogger("content_updater")
logger.setLevel(logging.INFO)
if not logger.handlers:
    rfh = RotatingFileHandler(LOG_FILE, maxBytes=10 * 1024 * 1024, backupCount=3, encoding="utf-8")
    rfh.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s - %(message)s"))
    logger.addHandler(rfh)
    sh = logging.StreamHandler()
    sh.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s - %(message)s"))
    logger.addHandler(sh)


def _load_rows(limit: int, priority_recent: bool) -> List[Dict[str, Any]]:
    if not CATALOG_DB.exists() or CATALOG_DB.stat().st_size == 0:
        raise RuntimeError(f"canonical catalog missing: {CATALOG_DB}")
    order_clause = "ORDER BY year DESC, rating DESC, id ASC" if priority_recent else "ORDER BY rating DESC, id ASC"
    conn = sqlite3.connect(str(CATALOG_DB), timeout=30.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=30000")
    try:
        rows = conn.execute(
            f"""
            SELECT id, tmdb_id, media_type, title, original_title, year, category,
                   rating, streams, playback_url, link_verified
            FROM movies
            WHERE (
                COALESCE(playback_url, '') = ''
                OR COALESCE(streams, '') IN ('', '[]')
                OR COALESCE(link_verified, 0) = 0
            )
              AND COALESCE(title, '') != ''
            {order_clause}
            LIMIT ?
            """,
            (int(limit),),
        ).fetchall()
        return [dict(row) for row in rows]
    finally:
        conn.close()


def _existing_keys(row: Dict[str, Any]) -> set:
    try:
        raw = json.loads(row.get("streams") or "[]")
    except Exception:
        raw = []
    return {stream_variant_key(item) for item in sanitize_streams(raw, require_source=True)}


def update_catalog_batch(limit: int = 200, priority_recent: bool = True) -> Dict[str, int]:
    rows = _load_rows(limit, priority_recent)
    stats = {
        "processed": 0,
        "persisted": 0,
        "duplicate": 0,
        "rejected_by_identity": 0,
        "no_source": 0,
        "provider_error": 0,
        "persistence_error": 0,
    }
    logger.info("Starting canonical catalog update for %s cards", len(rows))

    for idx, row in enumerate(rows, 1):
        stats["processed"] += 1
        card_id = int(row["id"])
        title = str(row.get("title") or "").strip()
        original_title = str(row.get("original_title") or "").strip()
        year = int(row.get("year") or 0)
        category = str(row.get("category") or "movies")
        try:
            resolved = resolve_torrents_for_query(
                title=title,
                year=year,
                category=category,
            )
        except Exception as exc:
            stats["provider_error"] += 1
            logger.warning("[%s/%s] provider_error ID=%s %s", idx, len(rows), card_id, type(exc).__name__)
            continue

        clean = sanitize_streams(resolved, require_source=True)
        if not clean:
            stats["no_source"] += 1
            continue
        accepted = filter_streams_for_content(clean, row)
        if not accepted:
            stats["rejected_by_identity"] += 1
            logger.info("[%s/%s] rejected_by_identity ID=%s", idx, len(rows), card_id)
            continue

        before = _existing_keys(row)
        incoming = {stream_variant_key(item) for item in accepted}
        if incoming and incoming.issubset(before) and int(row.get("link_verified") or 0) == 1:
            stats["duplicate"] += 1
            continue

        payload = {
            "id": card_id,
            "streams": accepted,
            "voice": accepted[0].get("voice", "Не указано"),
            "quality": accepted[0].get("quality", "Не указано"),
            "seeders": int(accepted[0].get("seeders") or 0),
            "link_verified": 1,
        }
        try:
            saved = bool(save_content(payload))
        except Exception as exc:
            saved = False
            logger.warning("[%s/%s] persistence_error ID=%s %s", idx, len(rows), card_id, type(exc).__name__)
        if not saved:
            stats["persistence_error"] += 1
            continue

        stats["persisted"] += 1
        cache_key = f"{title.casefold()}_{year}_{category.casefold()}"
        try:
            set_cached_streams(cache_key=cache_key, streams=accepted, ttl_hours=48)
        except Exception as exc:
            logger.debug("cache warm failed ID=%s: %s", card_id, type(exc).__name__)
        logger.info("[%s/%s] persisted ID=%s title=%r variants=%s", idx, len(rows), card_id, title, len(accepted))

    logger.info("Updater finished: %s", stats)
    return stats


def main() -> int:
    parser = argparse.ArgumentParser(description="Movia canonical stream updater")
    parser.add_argument("--limit", type=int, default=200)
    parser.add_argument("--priority-recent", action="store_true", default=True)
    args = parser.parse_args()
    if args.limit <= 0:
        parser.error("--limit must be > 0")
    print(json.dumps(update_catalog_batch(args.limit, args.priority_recent), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
