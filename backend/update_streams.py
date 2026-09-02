#!/usr/bin/env python3
"""Manual Movia/Zona stream sync against canonical catalog.db only.

Safety properties:
- canonical catalog.db is the only persistent catalog;
- cards are selected by local Movia id, or by a unique exact title/year lookup;
- provider output passes shared validation and release matching;
- persistence uses additive/idempotent database.save_content;
- existing streams are never wholesale replaced.
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
import unicodedata
from pathlib import Path
from typing import Any, Dict, List, Optional

DIR = Path(__file__).resolve().parent
CATALOG_DB = DIR / "catalog.db"
sys.path.insert(0, str(DIR))

from balancer_integration import query_zona_api  # noqa: E402
from database import filter_streams_for_content, save_content  # noqa: E402
from stream_validation import sanitize_streams, stream_variant_key  # noqa: E402


def normalize_title(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).casefold().replace("ё", "е")
    text = re.sub(r"[\u2010-\u2015−–—-]+", " ", text)
    text = re.sub(r"[^\w\s]+", " ", text, flags=re.UNICODE).replace("_", " ")
    return re.sub(r"\s+", " ", text).strip()


def _connect() -> sqlite3.Connection:
    if not CATALOG_DB.exists() or CATALOG_DB.stat().st_size == 0:
        raise RuntimeError(f"canonical catalog missing: {CATALOG_DB}")
    conn = sqlite3.connect(str(CATALOG_DB), timeout=30.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=30000")
    return conn


def get_card_by_id(card_id: int) -> Optional[Dict[str, Any]]:
    with _connect() as conn:
        row = conn.execute(
            """
            SELECT id, tmdb_id, media_type, title, original_title, year, category,
                   streams, playback_url, link_verified, voice, quality, seeders
            FROM movies WHERE id = ?
            """,
            (int(card_id),),
        ).fetchone()
    return dict(row) if row else None


def resolve_unique_card(title: str, year: Optional[int]) -> Dict[str, Any]:
    wanted = normalize_title(title)
    with _connect() as conn:
        rows = [dict(row) for row in conn.execute(
            """
            SELECT id, tmdb_id, media_type, title, original_title, year, category,
                   streams, playback_url, link_verified, voice, quality, seeders
            FROM movies
            WHERE (? IS NULL OR year = ?)
            """,
            (year, year),
        )]
    matches = [
        row for row in rows
        if wanted in {normalize_title(row.get("title")), normalize_title(row.get("original_title"))}
    ]
    if len(matches) != 1:
        ids = [row["id"] for row in matches[:20]]
        raise RuntimeError(f"title lookup is not unique: matches={len(matches)} ids={ids}")
    return matches[0]


def _existing_keys(card: Dict[str, Any]) -> set:
    try:
        raw = json.loads(card.get("streams") or "[]")
    except Exception:
        raw = []
    return {stream_variant_key(item) for item in sanitize_streams(raw, require_source=True)}


def sync_streams_for_card(card: Dict[str, Any]) -> Dict[str, Any]:
    title = str(card.get("title") or "").strip()
    original_title = str(card.get("original_title") or "").strip()
    year = int(card.get("year") or 0)
    category = str(card.get("category") or "movies")
    expected_titles = [value for value in (title, original_title) if value]

    resolved = query_zona_api(
        title=title,
        year=year,
        expected_titles=expected_titles,
        media_type=str(card.get("media_type") or category),
        allow_zona_content_lookup=True,
        allow_torrent_fallback=True,
    )
    clean = sanitize_streams(resolved, require_source=True)
    if not clean:
        return {"status": "no_source", "card_id": int(card["id"]), "found": 0, "accepted": 0, "added": 0}

    accepted = filter_streams_for_content(clean, card)
    if not accepted:
        return {
            "status": "rejected_by_identity",
            "card_id": int(card["id"]),
            "found": len(clean),
            "accepted": 0,
            "added": 0,
        }

    before = _existing_keys(card)
    additions = [item for item in accepted if stream_variant_key(item) not in before]
    if not additions and int(card.get("link_verified") or 0) == 1:
        return {
            "status": "duplicate",
            "card_id": int(card["id"]),
            "found": len(clean),
            "accepted": len(accepted),
            "added": 0,
        }

    payload = {
        "id": int(card["id"]),
        "streams": accepted,
        "voice": accepted[0].get("voice", "Не указано"),
        "quality": accepted[0].get("quality", "Не указано"),
        "seeders": int(accepted[0].get("seeders") or 0),
        "link_verified": 1,
    }
    try:
        saved = bool(save_content(payload))
    except Exception as exc:
        return {
            "status": "persistence_error",
            "card_id": int(card["id"]),
            "found": len(clean),
            "accepted": len(accepted),
            "added": 0,
            "error": type(exc).__name__,
        }
    if not saved:
        return {
            "status": "persistence_error",
            "card_id": int(card["id"]),
            "found": len(clean),
            "accepted": len(accepted),
            "added": 0,
        }

    return {
        "status": "persisted",
        "card_id": int(card["id"]),
        "title": title,
        "year": year,
        "found": len(clean),
        "accepted": len(accepted),
        "added": len(additions),
    }


def batch_sync(limit: int) -> List[Dict[str, Any]]:
    with _connect() as conn:
        rows = [dict(row) for row in conn.execute(
            """
            SELECT id, tmdb_id, media_type, title, original_title, year, category,
                   streams, playback_url, link_verified, voice, quality, seeders
            FROM movies
            WHERE COALESCE(streams, '') IN ('', '[]') OR COALESCE(link_verified, 0) = 0
            ORDER BY rating DESC, vote_count DESC, id ASC
            LIMIT ?
            """,
            (int(limit),),
        )]
    return [sync_streams_for_card(row) for row in rows]


def main() -> int:
    parser = argparse.ArgumentParser(description="Safe Movia stream sync")
    selector = parser.add_mutually_exclusive_group(required=True)
    selector.add_argument("--id", type=int, help="local Movia card id")
    selector.add_argument("--title", help="exact title/original_title; must resolve uniquely")
    selector.add_argument("--top", type=int, help="batch unresolved cards")
    parser.add_argument("--year", type=int)
    args = parser.parse_args()

    if args.id is not None:
        card = get_card_by_id(args.id)
        if not card:
            raise SystemExit(f"Movia card id={args.id} not found")
        result: Any = sync_streams_for_card(card)
    elif args.title is not None:
        result = sync_streams_for_card(resolve_unique_card(args.title, args.year))
    else:
        if args.top <= 0:
            parser.error("--top must be > 0")
        result = batch_sync(args.top)

    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
