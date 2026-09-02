#!/usr/bin/env python3
"""Generic catalog stream enrichment with durable progress and retries.

The worker enriches the working catalog.db only with provider-returned,
structurally valid playback candidates. It never fabricates a stream for a
title whose provider lookup did not produce one.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
from datetime import datetime, timezone
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any, Dict, List, Optional

DIR = Path("/data/data/com.termux/files/home/projects/media-parser")
sys.path.insert(0, str(DIR))

from database import filter_streams_for_content, get_db, save_content
from stream_validation import sanitize_streams
from torrent_resolver import resolve_torrent
from balancer_integration import (
    get_last_resolution_diagnostics,
    resolve_balancer,
)
from streamer import set_cached_streams

LOG_DIR = DIR / "logs"
LOG_DIR.mkdir(parents=True, exist_ok=True)
LOG_FILE = LOG_DIR / "content_filler.log"
STATE_FILE = DIR / "state.json"
STATE_VERSION = 2
STREAM_CLEANUP_VERSION = 3

logger = logging.getLogger("content_filler")
logger.setLevel(logging.INFO)
if not logger.handlers:
    rfh = RotatingFileHandler(
        LOG_FILE,
        maxBytes=512 * 1024,
        backupCount=2,
        encoding="utf-8",
    )
    rfh.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s - %(message)s"))
    logger.addHandler(rfh)
    sh = logging.StreamHandler()
    sh.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s - %(message)s"))
    logger.addHandler(sh)

UNRESOLVED_SQL = """
    COALESCE(playback_url, '') = ''
    OR COALESCE(streams, '') = ''
    OR streams = '[]'
    OR COALESCE(link_verified, 0) = 0
"""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _as_int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError, OverflowError):
        return default


def _new_state() -> Dict[str, Any]:
    return {
        "state_version": STATE_VERSION,
        "last_id": 0,
        "pass_number": 0,
        "processed_total": 0,
        "attempted_total": 0,
        "success_total": 0,
        "persisted_total": 0,
        "resolver_hit_total": 0,
        "no_source_total": 0,
        "invalid_result_total": 0,
        "persist_failure_total": 0,
        "provider_error_total": 0,
        "last_pass_completed_at": None,
        "updated_at": _now(),
    }


def _count_valid_catalog_rows() -> int:
    """Count rows containing at least one source-backed valid stream."""
    try:
        with get_db() as db:
            rows = db.execute("SELECT streams FROM movies").fetchall()
        count = 0
        for row in rows:
            try:
                raw = json.loads(row["streams"] or "[]")
            except (TypeError, ValueError, json.JSONDecodeError):
                raw = []
            if sanitize_streams(raw, require_source=True):
                count += 1
        return count
    except Exception as exc:
        logger.warning("Unable to calculate the verified baseline: %s", exc)
        return 0



def _repair_catalog_streams() -> Dict[str, int]:
    """Remove legacy invalid stream payloads without touching metadata."""
    stats = {"rows": 0, "valid_rows": 0, "normalized": 0, "cleared": 0}
    updates: List[Any] = []
    try:
        with get_db() as db:
            rows = db.execute(
                """
                SELECT id, streams, playback_url, link_verified,
                       title, original_title, year, media_type, category
                FROM movies
                """
            ).fetchall()
            for row in rows:
                stats["rows"] += 1
                raw_text = row["streams"]
                try:
                    raw_items = json.loads(raw_text or "[]")
                except (TypeError, ValueError, json.JSONDecodeError):
                    raw_items = []
                clean = filter_streams_for_content(raw_items, dict(row))
                if clean:
                    stats["valid_rows"] += 1
                    canonical_json = json.dumps(clean, ensure_ascii=False)
                    if (
                        raw_text != canonical_json
                        or str(row["playback_url"] or "") != clean[0]["url"]
                        or _as_int(row["link_verified"]) != 1
                    ):
                        updates.append(
                            (clean[0]["url"], canonical_json, 1, _as_int(row["id"]))
                        )
                        stats["normalized"] += 1
                elif (
                    raw_text not in (None, "", "[]")
                    or str(row["playback_url"] or "")
                    or _as_int(row["link_verified"]) != 0
                ):
                    # Keep the content metadata but remove only the invalid
                    # playback payload so the retry queue can see this row.
                    updates.append(("", "[]", 0, _as_int(row["id"])))
                    stats["cleared"] += 1
            if updates:
                db.executemany(
                    """
                    UPDATE movies
                    SET playback_url = ?, streams = ?, link_verified = ?
                    WHERE id = ?
                    """,
                    updates,
                )
                db.commit()
    except Exception as exc:
        logger.warning("Catalog stream cleanup failed: %s", exc)
    return stats

def save_state(state: Dict[str, Any]) -> None:
    """Atomically persist a JSON checkpoint so a killed worker can resume."""
    payload = dict(state)
    payload["state_version"] = STATE_VERSION
    payload["updated_at"] = _now()
    temporary = STATE_FILE.with_name(STATE_FILE.name + ".tmp")
    try:
        with open(temporary, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
        os.replace(temporary, STATE_FILE)
    except Exception as exc:
        logger.warning("Error saving state.json: %s", exc)
        try:
            temporary.unlink()
        except OSError:
            pass


def load_state() -> Dict[str, Any]:
    state: Dict[str, Any] = {}
    if STATE_FILE.exists():
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as handle:
                raw = json.load(handle)
            if isinstance(raw, dict):
                state = raw
        except Exception as exc:
            logger.warning("Error loading state.json: %s", exc)

    version = _as_int(state.get("state_version"), 1)
    if version < STATE_VERSION:
        old_last_id = _as_int(state.get("last_id"))
        old_processed = _as_int(state.get("processed_total"))
        old_resolver_hits = _as_int(state.get("success_total"))
        migrated = _new_state()
        migrated["last_id"] = old_last_id
        migrated["processed_total"] = old_processed
        migrated["attempted_total"] = old_processed
        migrated["resolver_hit_total"] = old_resolver_hits
        migrated["success_total"] = _count_valid_catalog_rows()
        migrated["persisted_total"] = migrated["success_total"]
        migrated["metrics_reset_at"] = _now()
        migrated["legacy_state_version"] = version
        state = migrated
    else:
        defaults = _new_state()
        for key, value in defaults.items():
            state.setdefault(key, value)

    state["state_version"] = STATE_VERSION
    state["last_id"] = _as_int(state.get("last_id"))
    for key in (
        "pass_number",
        "processed_total",
        "attempted_total",
        "success_total",
        "persisted_total",
        "resolver_hit_total",
        "no_source_total",
        "invalid_result_total",
        "persist_failure_total",
        "provider_error_total",
    ):
        state[key] = _as_int(state.get(key))

    # Run once for each cleanup contract revision. This removes legacy or
    # identity-mismatched payloads and re-queues their rows without rebuilding
    # the database.
    if _as_int(state.get("stream_cleanup_version")) != STREAM_CLEANUP_VERSION:
        cleanup = _repair_catalog_streams()
        state["stream_cleanup_version"] = STREAM_CLEANUP_VERSION
        state["stream_cleanup"] = cleanup
        state["cleanup_at"] = _now()
        state["success_total"] = _count_valid_catalog_rows()
        state["persisted_total"] = state["success_total"]
        save_state(state)
    return state
def _fetch_rows(db: Any, last_id: int) -> List[Any]:
    query = f"""
        SELECT id, tmdb_id, media_type, title, original_title, year, category, rating, streams, link_verified
        FROM movies
        WHERE ({UNRESOLVED_SQL})
    """
    params: List[Any] = []
    if last_id > 0:
        query += " AND id > ?"
        params.append(last_id)
    query += " ORDER BY id ASC"
    return db.execute(query, tuple(params)).fetchall()


def _valid_persisted_row(content_id: int) -> bool:
    try:
        with get_db() as db:
            row = db.execute(
                "SELECT streams, link_verified FROM movies WHERE id = ?",
                (content_id,),
            ).fetchone()
        if not row or _as_int(row["link_verified"]) != 1:
            return False
        try:
            raw = json.loads(row["streams"] or "[]")
        except (TypeError, ValueError, json.JSONDecodeError):
            raw = []
        return bool(sanitize_streams(raw, require_source=True))
    except Exception:
        return False


def _candidate_streams(found_stream: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
    if not isinstance(found_stream, dict):
        return []
    raw_streams = found_stream.get("streams")
    if not isinstance(raw_streams, list):
        return []
    return sanitize_streams(raw_streams, require_source=True)


def _process_row(row: Any, index: int, total: int) -> Dict[str, Any]:
    """Resolve and persist one row; the caller commits the durable cursor."""
    content_id = _as_int(row["id"])
    tmdb_id = _as_int(row["tmdb_id"])
    title = str(row["title"] or "Без названия")
    original_title = str(row["original_title"] or title)
    year = _as_int(row["year"]) or 0
    category = str(row["category"] or "movies")
    search_title = (
        title
        if any("\u0400" <= char <= "\u04FF" for char in title)
        else original_title
    )

    logger.debug(
        "[%s/%s] Обработка: %s (%s) [ID=%s]",
        index,
        total,
        title,
        year,
        content_id,
    )

    found_stream: Optional[Dict[str, Any]] = None
    row_provider_errors = 0
    persisted_ok = False

    try:
        balancer_result = resolve_balancer(
            title=search_title,
            year=year,
            tmdb_id=tmdb_id,
            expected_titles=list(dict.fromkeys(
                value for value in (search_title, title, original_title)
                if value
            )),
            media_type=category,
        )
        diagnostics = get_last_resolution_diagnostics()
        row_provider_errors += _as_int(diagnostics.get("error_count"))
        if isinstance(balancer_result, dict) and balancer_result.get("playback_url"):
            found_stream = balancer_result
    except Exception as exc:
        row_provider_errors += 1
        logger.debug("Balancer error for %s: %s", title, exc)

    if not found_stream:
        try:
            torrent_result = resolve_torrent(
                title=search_title,
                year=year,
                category=category,
            )
            if isinstance(torrent_result, dict) and torrent_result.get("playback_url"):
                found_stream = torrent_result
        except Exception as exc:
            row_provider_errors += 1
            logger.debug("Torrent error for %s: %s", title, exc)

    resolved_streams = _candidate_streams(found_stream)
    # The fetched row includes media_type, so this pre-filter is identical to
    # the persistence-boundary identity check in database.save_content().
    clean_streams = filter_streams_for_content(resolved_streams, dict(row))
    duplicate_candidate = False
    if clean_streams:
        try:
            existing_raw = json.loads(row["streams"] or "[]")
        except (TypeError, ValueError, json.JSONDecodeError):
            existing_raw = []
        existing_clean = sanitize_streams(existing_raw, require_source=True)
        from stream_validation import stream_variant_key
        existing_keys = {stream_variant_key(item) for item in existing_clean}
        duplicate_candidate = bool(existing_keys) and all(
            stream_variant_key(item) in existing_keys for item in clean_streams
        )
    if clean_streams:
        payload = {
            "id": content_id,
            "voice": found_stream.get("voice", "Не указано"),
            "quality": found_stream.get("quality", "Не указано"),
            "seeders": _as_int(found_stream.get("seeders"), 0),
            "streams": clean_streams,
            "link_verified": 1,
            "replace_direct_variants": True,
        }
        try:
            saved = bool(save_content(payload))
        except Exception as exc:
            saved = False
            logger.warning("Persistence error for ID=%s: %s", content_id, exc)

        if saved and _valid_persisted_row(content_id):
            persisted_ok = True
            try:
                set_cached_streams(
                    cache_key=f"{title.strip().lower()}_{year}_{category.strip().lower()}",
                    streams=clean_streams,
                    ttl_hours=48,
                )
            except Exception:
                pass
        elif clean_streams:
            logger.warning(
                "⚠️ Источник найден, но не подтверждён после сохранения: ID=%s",
                content_id,
            )

    if persisted_ok:
        status = "duplicate" if duplicate_candidate else "persisted"
    elif clean_streams:
        status = "persistence_error"
    elif found_stream:
        status = "rejected_by_identity"
    elif row_provider_errors:
        status = "provider_error"
    else:
        status = "no_source"

    return {
        "content_id": content_id,
        "status": status,
        "resolver_hit": bool(found_stream),
        "persisted_ok": persisted_ok,
        "no_source": status == "no_source",
        "invalid_result": status == "rejected_by_identity",
        "persist_failure": status == "persistence_error",
        "provider_errors": row_provider_errors,
    }


def fill_content(
    limit: Optional[int] = None,
    resume: bool = False,
    force_all: bool = False,
) -> Dict[str, Any]:
    logger.info(
        "=== Запуск контент-конвейера Movia "
        "(limit=%s, resume=%s, all=%s) ===",
        limit,
        resume,
        force_all,
    )
    state = load_state()
    last_id = state["last_id"] if resume else 0

    with get_db() as db:
        rows = _fetch_rows(db, last_id)
        if limit and not force_all:
            rows = rows[: int(limit)]

    # A cursor is a throughput checkpoint, not a permanent exclusion list.
    # Once the high-water pass is complete, reset it so failed/temporary rows
    # below the cursor are retried during the next service cycle.
    if not rows:
        if last_id > 0:
            state["last_id"] = 0
            state["pass_number"] = _as_int(state.get("pass_number")) + 1
            state["last_pass_completed_at"] = _now()
            save_state(state)
            logger.info(
                "✅ Cursor pass completed; reset checkpoint for a full retry pass."
            )
            return {"processed": 0, "pass_completed": True}
        state["last_pass_completed_at"] = _now()
        save_state(state)
        logger.info("✅ Нет тайтлов, требующих наполнения.")
        return {"processed": 0, "pass_completed": True}

    total = len(rows)
    logger.info("📦 Найдено тайтлов для текущего прохода: %s", total)

    processed = 0
    persisted = 0
    resolver_hits = 0
    no_source = 0
    invalid_results = 0
    persist_failures = 0
    provider_errors = 0
    status_counts = Counter()
    started = time.monotonic()

    configured_workers = _as_int(
        os.environ.get("MOVIA_ENRICH_WORKERS"), 4
    )
    worker_count = min(max(1, configured_workers), 6)
    batch_size = worker_count * 2
    logger.info(
        "⚙️ Ограниченный параллельный проход: workers=%s, batch_size=%s",
        worker_count,
        batch_size,
    )

    with ThreadPoolExecutor(
        max_workers=worker_count,
        thread_name_prefix="movia-enrich",
    ) as executor:
        for batch_start in range(0, len(rows), batch_size):
            batch = rows[batch_start:batch_start + batch_size]
            futures = [
                executor.submit(_process_row, row, batch_start + offset + 1, total)
                for offset, row in enumerate(batch)
            ]

            # Consume results in catalog-id order. Workers may finish out of
            # order, but the durable cursor never skips an unfinished row.
            for row, future in zip(batch, futures):
                content_id = _as_int(row["id"])
                try:
                    result = future.result()
                except Exception as exc:
                    logger.exception("Unexpected worker failure for ID=%s", content_id)
                    result = {
                        "content_id": content_id,
                        "status": "provider_error",
                        "resolver_hit": False,
                        "persisted_ok": False,
                        "no_source": False,
                        "invalid_result": False,
                        "persist_failure": False,
                        "provider_errors": 1,
                    }

                processed += 1
                state["last_id"] = content_id
                state["processed_total"] = _as_int(state.get("processed_total")) + 1
                state["attempted_total"] = _as_int(state.get("attempted_total")) + 1
                resolver_hits += int(bool(result.get("resolver_hit")))
                persisted += int(bool(result.get("persisted_ok")))
                no_source += int(bool(result.get("no_source")))
                invalid_results += int(bool(result.get("invalid_result")))
                persist_failures += int(bool(result.get("persist_failure")))
                provider_errors += _as_int(result.get("provider_errors"))
                status = str(result.get("status") or "provider_error")
                status_counts[status] += 1
                status_totals = state.setdefault("status_totals", {})
                status_totals[status] = _as_int(status_totals.get(status)) + 1

                state["resolver_hit_total"] = _as_int(state.get("resolver_hit_total")) + int(
                    bool(result.get("resolver_hit"))
                )
                state["success_total"] = _as_int(state.get("success_total")) + int(
                    bool(result.get("persisted_ok"))
                )
                state["persisted_total"] = _as_int(state.get("persisted_total")) + int(
                    bool(result.get("persisted_ok"))
                )
                state["no_source_total"] = _as_int(state.get("no_source_total")) + int(
                    bool(result.get("no_source"))
                )
                state["invalid_result_total"] = _as_int(
                    state.get("invalid_result_total")
                ) + int(bool(result.get("invalid_result")))
                state["persist_failure_total"] = _as_int(
                    state.get("persist_failure_total")
                ) + int(bool(result.get("persist_failure")))
                state["provider_error_total"] = _as_int(
                    state.get("provider_error_total")
                ) + _as_int(result.get("provider_errors"))

                if processed % 10 == 0 or processed == total:
                    save_state(state)

    # The local counters above are for this invocation; cumulative values live
    # in state.json. Keep the completion log unambiguous.
    elapsed = time.monotonic() - started
    logger.info(
        "🎉 Завершён проход: persisted=%s/%s, resolver_hits=%s, "
        "no_source=%s, invalid=%s, persist_failures=%s, provider_errors=%s, "
        "elapsed=%.2fs",
        persisted,
        processed,
        resolver_hits,
        no_source,
        invalid_results,
        persist_failures,
        provider_errors,
        elapsed,
    )
    save_state(state)
    return {
        "processed": processed,
        "persisted": persisted,
        "resolver_hits": resolver_hits,
        "no_source": no_source,
        "invalid_results": invalid_results,
        "persist_failures": persist_failures,
        "provider_errors": provider_errors,
        "status_counts": dict(status_counts),
        "pass_completed": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Movia catalog stream enricher")
    parser.add_argument("--limit", type=int, help="Количество тайтлов")
    parser.add_argument("--all", action="store_true", help="Обработать все unresolved titles")
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Продолжить с checkpoint; failed rows retry on the next pass",
    )
    args = parser.parse_args()
    fill_content(
        limit=args.limit,
        resume=args.resume,
        force_all=args.all,
    )


if __name__ == "__main__":
    main()
