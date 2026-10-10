#!/usr/bin/env python3
"""Authoritative TMDb metadata repair for catalog.db.

Only metadata columns are replaced. ``streams`` is never touched.  Every row is
fetched by its canonical ``(media_type, tmdb_id)`` rather than title search.
"""
from __future__ import annotations
from movia_paths import DATA_DIR

import argparse
import json
import os
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from catalog_schema_v2 import bump_revision, ensure_schema, normalize_ru_text
from tmdb_client import TMDbClient
from background_network_budget import background_bulk_allowed

DIR = DATA_DIR
DB_PATH = DIR / "catalog.db"
STATE_PATH = DIR / "metadata_repair_state.json"
TYPE_AUDIT_STATE_PATH = DIR / "metadata_type_repair_state.json"

_thread_local = threading.local()


def _client() -> TMDbClient:
    client = getattr(_thread_local, "tmdb", None)
    if client is None:
        client = TMDbClient()
        _thread_local.tmdb = client
    return client


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _identity_text(value: Any) -> str:
    return normalize_ru_text(value).replace(" ", "")


def _detail_matches_row(row: dict[str, Any], data: dict[str, Any]) -> bool:
    current = {_identity_text(row.get("title")), _identity_text(row.get("original_title"))}
    actual = {_identity_text(data.get("title")), _identity_text(data.get("original_title"))}
    current.discard(""); actual.discard("")
    if not current.intersection(actual):
        return False
    current_year = int(row.get("year") or 0)
    actual_year = int(data.get("year") or 0)
    return not (current_year and actual_year and abs(current_year - actual_year) > 1)


def _fetch(row: dict[str, Any]) -> tuple[int, dict[str, Any] | None, str | None]:
    try:
        tmdb_id = int(row["tmdb_id"] or 0)
        media_type = str(row["media_type"] or "").lower()
        if tmdb_id <= 0 or media_type not in {"movie", "tv"}:
            return int(row["id"]), None, "invalid_identity"
        client = _client()
        data = client.get_tv_details(tmdb_id) if media_type == "tv" else client.get_movie_details(tmdb_id)
        if data:
            if int(data.get("tmdb_id") or 0) != tmdb_id or str(data.get("media_type")) != media_type:
                return int(row["id"]), None, "identity_mismatch"
            if _detail_matches_row(row, data):
                return int(row["id"]), data, None
            # A numeric TMDb id may exist in both namespaces. If the current
            # namespace resolves to a different work, continue to the opposite
            # namespace instead of overwriting the catalog with that collision.

        # TMDb movie and TV ids live in separate namespaces. Legacy imports can
        # therefore carry the right numeric id under the wrong media_type. Only
        # accept an opposite-namespace repair when title/original-title and year
        # match exactly enough to prove the same work.
        opposite = client.get_movie_details(tmdb_id) if media_type == "tv" else client.get_tv_details(tmdb_id)
        if opposite and _detail_matches_row(row, opposite):
            return int(row["id"]), opposite, "wrong_media_type"
        return int(row["id"]), None, "identity_mismatch" if data else "tmdb_not_found"
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

    if 'age_rating' in data:
        conn.execute("UPDATE movies SET age_rating=?, age_rating_source=?, age_rating_jurisdiction=? WHERE id=? AND media_type=? AND tmdb_id=?", (data.get('age_rating'),data.get('age_rating_source'),data.get('age_rating_jurisdiction'),int(row_id),str(data.get('media_type')),int(data.get('tmdb_id') or 0)))


def _record_redirect(conn: sqlite3.Connection, old_id: int, new_id: int) -> None:
    conn.execute(
        "INSERT INTO catalog_meta(key,value) VALUES (?,?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (f"catalog_redirect:{int(old_id)}", str(int(new_id))),
    )


def apply_media_type_correction(
    conn: sqlite3.Connection, row: dict[str, Any], data: dict[str, Any]
) -> int | None:
    old_id = int(row["id"])
    old_type = str(row.get("media_type") or "").lower()
    new_type = str(data.get("media_type") or "").lower()
    tmdb_id = int(row.get("tmdb_id") or 0)
    if old_type not in {"movie", "tv"} or new_type not in {"movie", "tv"} or old_type == new_type:
        return None
    try:
        streams = json.loads(row.get("streams") or "[]")
    except (TypeError, ValueError):
        streams = []
    if isinstance(streams, list) and streams:
        # Never silently rebind an existing playback identity.
        return None

    existing = conn.execute(
        "SELECT id FROM movies WHERE media_type=? AND tmdb_id=? AND id!=?",
        (new_type, tmdb_id, old_id),
    ).fetchone()
    now = _now()
    if existing:
        canonical_id = int(existing[0])
        apply_authoritative_metadata(conn, canonical_id, data)
        _record_redirect(conn, old_id, canonical_id)
        conn.execute(
            "UPDATE movies SET localized_ru_title='', normalized_ru_title='', "
            "metadata_source='tmdb_wrong_media_type', metadata_updated_at=?, updated_at=? WHERE id=?",
            (now, now, old_id),
        )
        return canonical_id

    conn.execute("UPDATE movies SET media_type=? WHERE id=?", (new_type, old_id))
    apply_authoritative_metadata(conn, old_id, data)
    return old_id


def _load_state(path: Path = STATE_PATH) -> int:
    try:
        return max(0, int(json.loads(path.read_text()).get("last_id") or 0))
    except Exception:
        return 0


def _save_state(
    last_id: int, *, repaired: int, failed: int, media_type_corrected: int = 0,
    path: Path = STATE_PATH,
) -> None:
    path.write_text(json.dumps({
        "last_id": int(last_id),
        "repaired": int(repaired),
        "failed": int(failed),
        "media_type_corrected": int(media_type_corrected),
        "updated_at": _now(),
    }, ensure_ascii=False, indent=2))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ids", help="Comma-separated catalog row ids")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--reset-state", action="store_true")
    ap.add_argument(
        "--audit-media-type", action="store_true",
        help="Resumable exact movie/tv namespace audit over streamless catalog rows",
    )
    args = ap.parse_args()

    if os.environ.get("MOVIA_BACKGROUND_BULK", "0") == "1":
        decision = background_bulk_allowed()
        if not decision.allowed:
            print(json.dumps({"blocked": True, "reason": decision.reason}, ensure_ascii=False))
            return 0

    ensure_schema(DB_PATH)
    state_path = TYPE_AUDIT_STATE_PATH if args.audit_media_type else STATE_PATH
    if args.reset_state and state_path.exists():
        state_path.unlink()

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
            f"SELECT id,tmdb_id,media_type,title,original_title,year,streams FROM movies WHERE id IN ({placeholders}) ORDER BY id", ids
        ).fetchall()]
    else:
        last_id = _load_state(state_path) if args.resume else 0
        if args.audit_media_type:
            # Audit every streamless identity eventually, independent of normal
            # metadata freshness. Existing stream-bearing rows are deliberately
            # excluded because automatic type rebinding must never move playback.
            sql = (
                "SELECT id,tmdb_id,media_type,title,original_title,year,streams FROM movies "
                "WHERE id>? AND tmdb_id>0 AND media_type IN ('movie','tv') "
                "AND (streams IS NULL OR streams='' OR streams='[]') "
                "AND metadata_source NOT IN ('tmdb_wrong_media_type','tmdb_wrong_media_type_with_streams') "
                "ORDER BY id"
            )
        else:
            sql = (
                "SELECT id,tmdb_id,media_type,title,original_title,year,streams FROM movies "
                "WHERE id>? AND tmdb_id>0 AND media_type IN ('movie','tv') "
                "AND ((metadata_source='tmdb_detail' AND (metadata_updated_at='' "
                "OR julianday(metadata_updated_at) < julianday('now','-30 days'))) "
                "OR (metadata_source='tmdb_not_found' AND (metadata_updated_at='' "
                "OR julianday(metadata_updated_at) < julianday('now','-7 days'))) "
                "OR metadata_source NOT IN ('tmdb_detail','tmdb_not_found','tmdb_wrong_media_type','tmdb_wrong_media_type_with_streams')) "
                "ORDER BY id"
            )
        params: list[Any] = [last_id]
        if args.limit and args.limit > 0:
            sql += " LIMIT ?"
            params.append(int(args.limit))
        rows = [dict(x) for x in conn.execute(sql, params).fetchall()]

    print(json.dumps({
        "selected": len(rows), "workers": max(1, min(args.workers, 12)),
        "resume_from": _load_state(state_path) if args.resume else 0,
        "audit_media_type": bool(args.audit_media_type),
    }))
    if not rows:
        if args.resume and state_path.exists():
            # One complete id sweep is finished. Reset the cursor so the next
            # service pass can revisit rows that have become stale or were
            # inserted with a lower id by a restore/import.
            state_path.unlink()
            print(json.dumps({"cycle_complete": True, "cursor_reset": True, "audit_media_type": bool(args.audit_media_type)}))
        return 0

    repaired = failed = media_type_corrected = 0
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
                if error == "wrong_media_type":
                    canonical_id = apply_media_type_correction(conn, row, data)
                    if canonical_id is not None:
                        repaired += 1
                        media_type_corrected += 1
                    else:
                        failed += 1
                        errors["wrong_media_type_with_streams"] = errors.get("wrong_media_type_with_streams", 0) + 1
                        now = _now()
                        conn.execute(
                            "UPDATE movies SET metadata_source='tmdb_wrong_media_type_with_streams', "
                            "metadata_updated_at=?, updated_at=? WHERE id=?",
                            (now, now, int(row_id)),
                        )
                else:
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
                    _save_state(last_seen, repaired=repaired, failed=failed, media_type_corrected=media_type_corrected, path=state_path)
                print(json.dumps({
                    "processed": index,
                    "selected": len(rows),
                    "repaired": repaired,
                    "failed": failed,
                    "media_type_corrected": media_type_corrected,
                    "last_id": last_seen,
                }))

    print(json.dumps({"repaired": repaired, "failed": failed, "media_type_corrected": media_type_corrected, "errors": errors}, ensure_ascii=False))
    print("integrity=", conn.execute("PRAGMA integrity_check").fetchone()[0])
    conn.close()
    return 0 if repaired > 0 or failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
