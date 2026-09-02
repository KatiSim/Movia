#!/usr/bin/env python3
"""Authoritative TMDb metadata repair for catalog.db.

Only metadata columns are replaced. ``streams`` is never touched.  Every row is
fetched by its canonical ``(media_type, tmdb_id)`` rather than title search.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from catalog_schema_v2 import bump_revision, ensure_schema, normalize_ru_text
from tmdb_client import TMDbClient

DIR = Path(__file__).resolve().parent
DB_PATH = DIR / "catalog.db"
STATE_PATH = DIR / "metadata_repair_state.json"

_thread_local = threading.local()


def _client() -> TMDbClient:
    client = getattr(_thread_local, "tmdb", None)
    if client is None:
        client = TMDbClient()
        _thread_local.tmdb = client
    return client


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _fetch(row: dict[str, Any]) -> tuple[int, dict[str, Any] | None, str | None]:
    try:
        tmdb_id = int(row["tmdb_id"] or 0)
        media_type = str(row["media_type"] or "").lower()
        if tmdb_id <= 0 or media_type not in {"movie", "tv"}:
            return int(row["id"]), None, "invalid_identity"
        data = _client().get_tv_details(tmdb_id) if media_type == "tv" else _client().get_movie_details(tmdb_id)
        if not data:
            return int(row["id"]), None, "tmdb_not_found"
        if int(data.get("tmdb_id") or 0) != tmdb_id or str(data.get("media_type")) != media_type:
            return int(row["id"]), None, "identity_mismatch"
        return int(row["id"]), data, None
    except Exception as exc:  # pragma: no cover - operational reporting
        return int(row["id"]), None, f"{type(exc).__name__}:{exc}"


def apply_authoritative_metadata(conn: sqlite3.Connection, row_id: int, data: dict[str, Any]) -> None:
    localized = str(data.get("localized_ru_title") or "").strip()
    original = str(data.get("original_title") or "").strip()
    title = str(data.get("title") or original or "Без названия").strip()
    genres = json.dumps(data.get("genres") or [], ensure_ascii=False, separators=(",", ":"))
    cast = json.dumps(data.get("cast") or [], ensure_ascii=False, separators=(",", ":"))
    creators = json.dumps(data.get("creators") or [], ensure_ascii=False, separators=(",", ":"))
    alternatives = json.dumps(data.get("alternative_titles") or [], ensure_ascii=False, separators=(",", ":"))
    season_counts = json.dumps(data.get("season_episode_counts") or [], separators=(",", ":"))
    now = _now()
    conn.execute(
        """
        UPDATE movies SET
            title=?, original_title=?, localized_ru_title=?, alternative_titles=?,
            localization_source=?, localization_updated_at=?,
            normalized_ru_title=?, normalized_original_title=?,
            imdb_id=?, year=?, rating=?, vote_average=?, vote_count=?,
            duration_minutes=?, synopsis=?, poster_url=?, backdrop_url=?,
            genres=?, "cast"=?, director=?, creators=?, country=?, category=?,
            collection_id=?, seasons_count=?, episodes_count=?, season_episode_counts=?,
            metadata_source='tmdb_detail', metadata_updated_at=?, updated_at=?
        WHERE id=? AND media_type=? AND tmdb_id=?
        """,
        (
            title,
            original,
            localized,
            alternatives,
            "tmdb_ru" if localized else "",
            now if localized else "",
            normalize_ru_text(localized),
            normalize_ru_text(original),
            str(data.get("imdb_id") or ""),
            int(data.get("year") or 0),
            float(data.get("rating") or 0.0),
            float(data.get("vote_average") or 0.0),
            int(data.get("vote_count") or 0),
            int(data.get("duration_minutes") or 0),
            str(data.get("synopsis") or ""),
            str(data.get("poster_url") or ""),
            str(data.get("backdrop_url") or data.get("poster_url") or ""),
            genres,
            cast,
            str(data.get("director") or ""),
            creators,
            str(data.get("country") or "Зарубежный"),
            str(data.get("category") or ("tv_series" if data.get("media_type") == "tv" else "movies")),
            int(data.get("collection_id") or 0),
            int(data.get("seasons_count") or 0),
            int(data.get("episodes_count") or 0),
            season_counts,
            now,
            now,
            int(row_id),
            str(data.get("media_type")),
            int(data.get("tmdb_id") or 0),
        ),
    )


def _load_state() -> int:
    try:
        return max(0, int(json.loads(STATE_PATH.read_text()).get("last_id") or 0))
    except Exception:
        return 0


def _save_state(last_id: int, *, repaired: int, failed: int) -> None:
    STATE_PATH.write_text(json.dumps({
        "last_id": int(last_id),
        "repaired": int(repaired),
        "failed": int(failed),
        "updated_at": _now(),
    }, ensure_ascii=False, indent=2))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ids", help="Comma-separated catalog row ids")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--reset-state", action="store_true")
    args = ap.parse_args()

    ensure_schema(DB_PATH)
    if args.reset_state and STATE_PATH.exists():
        STATE_PATH.unlink()

    conn = sqlite3.connect(str(DB_PATH), timeout=30.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=30000")
    if args.ids:
        ids = [int(x) for x in args.ids.split(",") if x.strip().isdigit()]
        if not ids:
            print("No valid ids")
            return 2
        placeholders = ",".join("?" for _ in ids)
        rows = [dict(x) for x in conn.execute(
            f"SELECT id,tmdb_id,media_type FROM movies WHERE id IN ({placeholders}) ORDER BY id", ids
        ).fetchall()]
    else:
        last_id = _load_state() if args.resume else 0
        sql = (
            "SELECT id,tmdb_id,media_type FROM movies "
            "WHERE id>? AND tmdb_id>0 AND media_type IN ('movie','tv') "
            "AND ((metadata_source='tmdb_detail' AND (metadata_updated_at='' "
            "OR julianday(metadata_updated_at) < julianday('now','-30 days'))) "
            "OR (metadata_source='tmdb_not_found' AND (metadata_updated_at='' "
            "OR julianday(metadata_updated_at) < julianday('now','-7 days'))) "
            "OR metadata_source NOT IN ('tmdb_detail','tmdb_not_found')) "
            "ORDER BY id"
        )
        params: list[Any] = [last_id]
        if args.limit and args.limit > 0:
            sql += " LIMIT ?"
            params.append(int(args.limit))
        rows = [dict(x) for x in conn.execute(sql, params).fetchall()]

    print(json.dumps({"selected": len(rows), "workers": max(1, min(args.workers, 12)), "resume_from": _load_state() if args.resume else 0}))
    if not rows:
        if args.resume and STATE_PATH.exists():
            # One complete id sweep is finished. Reset the cursor so the next
            # service pass can revisit rows that have become stale or were
            # inserted with a lower id by a restore/import.
            STATE_PATH.unlink()
            print(json.dumps({"cycle_complete": True, "cursor_reset": True}))
        return 0

    repaired = failed = 0
    last_seen = 0
    errors: dict[str, int] = {}
    workers = max(1, min(int(args.workers), 12))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        future_map = {pool.submit(_fetch, row): row for row in rows}
        for index, future in enumerate(as_completed(future_map), 1):
            row = future_map[future]
            row_id, data, error = future.result()
            last_seen = max(last_seen, int(row_id))
            if data is not None:
                apply_authoritative_metadata(conn, row_id, data)
                repaired += 1
            else:
                failed += 1
                errors[error or "unknown"] = errors.get(error or "unknown", 0) + 1
                if error == "tmdb_not_found":
                    now = _now()
                    conn.execute(
                        "UPDATE movies SET metadata_source='tmdb_not_found', "
                        "metadata_updated_at=?, updated_at=? WHERE id=?",
                        (now, now, int(row_id)),
                    )
            if index % 25 == 0 or index == len(rows):
                bump_revision(conn)
                conn.commit()
                if not args.ids:
                    _save_state(last_seen, repaired=repaired, failed=failed)
                print(json.dumps({
                    "processed": index,
                    "selected": len(rows),
                    "repaired": repaired,
                    "failed": failed,
                    "last_id": last_seen,
                }))

    print(json.dumps({"repaired": repaired, "failed": failed, "errors": errors}, ensure_ascii=False))
    print("integrity=", conn.execute("PRAGMA integrity_check").fetchone()[0])
    conn.close()
    return 0 if repaired > 0 or failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
