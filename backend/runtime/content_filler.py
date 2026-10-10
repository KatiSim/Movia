#!/usr/bin/env python3
"""Generic catalog stream enrichment with durable progress and retries.

The worker enriches the working catalog.db only with provider-returned,
structurally valid playback candidates. It never fabricates a stream for a
title whose provider lookup did not produce one.
"""
from __future__ import annotations
from contextlib import closing
from movia_paths import DATA_DIR

import argparse
import json
import logging
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeoutError, wait
from collections import Counter
from datetime import datetime, timezone
from logging.handlers import RotatingFileHandler
from pathlib import Path
from threading import BoundedSemaphore
from typing import Any, Dict, List, Optional

DIR = DATA_DIR
sys.path.insert(0, str(DIR))

from database import filter_streams_for_content, get_db, save_content
from stream_validation import sanitize_streams
from variant_coverage import variant_coverage
from torrent_resolver import resolve_torrent
from balancer_integration import (
    get_last_resolution_diagnostics,
    resolve_balancer,
)
from provider_discovery import discover_provider_streams
from streamer import set_cached_streams
from background_network_budget import background_bulk_allowed
from playback_availability_index import (
    DISCOVERY_BACKGROUND, MEDIA_MOVIE, MEDIA_SERIES, PlaybackAvailabilityService,
    signed_url_expiry,
)

LOG_DIR = DIR / "logs"
LOG_DIR.mkdir(parents=True, exist_ok=True)
LOG_FILE = LOG_DIR / "content_filler.log"
STATE_FILE = DIR / "state.json"
STATE_VERSION = 2
STREAM_CLEANUP_VERSION = 4
PROVIDER_ERROR_RETRY_BASE_SECONDS = 2 * 60 * 60
PROVIDER_ERROR_RETRY_MAX_SECONDS = 24 * 60 * 60
NO_SOURCE_RETRY_SECONDS = 7 * 24 * 60 * 60
RECENT_NO_SOURCE_RETRY_SECONDS = 30 * 60
PARTIAL_COVERAGE_RETRY_SECONDS = 30 * 60
COMPLETE_COVERAGE_REFRESH_SECONDS = 24 * 60 * 60
IDENTITY_RETRY_SECONDS = 30 * 24 * 60 * 60
PERSISTENCE_RETRY_SECONDS = 60 * 60
BACKGROUND_DIRECT_PROVIDER_BUDGET_SECONDS = 4.0
# Absolute budget measured from torrent submission, not after direct search.
BACKGROUND_TORRENT_BUDGET_SECONDS = 16.0
MAX_INFLIGHT_BACKGROUND_TORRENTS = 12


def _background_torrent_budget_seconds() -> float:
    try:
        value = float(os.environ.get(
            "MOVIA_BACKGROUND_TORRENT_BUDGET_SECONDS", BACKGROUND_TORRENT_BUDGET_SECONDS
        ))
    except (TypeError, ValueError):
        value = BACKGROUND_TORRENT_BUDGET_SECONDS
    return min(30.0, max(4.0, value))


def _background_direct_provider_budget_seconds() -> float:
    try:
        value = float(os.environ.get("MOVIA_BACKGROUND_DIRECT_PROVIDER_BUDGET_SECONDS", BACKGROUND_DIRECT_PROVIDER_BUDGET_SECONDS))
    except (TypeError, ValueError):
        value = BACKGROUND_DIRECT_PROVIDER_BUDGET_SECONDS
    return min(8.0, max(0.05, value))


# Independent bounded pools: six active movie workers submit one request to
# each pool. A slow balancer must not consume provider registry slots.
_FILL_PROVIDER_EXECUTOR = ThreadPoolExecutor(max_workers=6, thread_name_prefix="movia-fill-provider")
_FILL_BALANCER_EXECUTOR = ThreadPoolExecutor(max_workers=6, thread_name_prefix="movia-fill-balancer")
_FILL_TORRENT_EXECUTOR = ThreadPoolExecutor(max_workers=6, thread_name_prefix="movia-fill-torrent")
# A cancelled network Future may already be running. Cap running + queued work
# so subsequent batches cannot grow an unbounded queue behind slow providers.
_FILL_BACKGROUND_TORRENT_SLOTS = BoundedSemaphore(MAX_INFLIGHT_BACKGROUND_TORRENTS)


def _release_background_torrent_slot(_future):
    _FILL_BACKGROUND_TORRENT_SLOTS.release()


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
DIRECT_REFRESH_MARGIN_SECONDS = 60
DIRECT_REFRESH_MAX_AGE_SECONDS = 5 * 60


def _catalog_streams_need_refresh(row: Any, *, now_epoch: Optional[float] = None) -> bool:
    """Return True when persisted direct HTTP candidates need rediscovery.

    Signed provider URLs are refreshed before their explicit expiry. Unsigned
    direct URLs use the catalog write timestamp as a bounded freshness hint.
    P2P-only rows are not treated as expiring direct candidates here.
    """
    try:
        raw = json.loads(row["streams"] or "[]")
    except (TypeError, ValueError, json.JSONDecodeError):
        raw = []
    clean = sanitize_streams(raw, require_source=True)
    direct = [
        item for item in clean
        if str(item.get("url") or item.get("playback_url") or "").strip().lower().startswith(("http://", "https://"))
    ]
    if not direct:
        return False
    now = time.time() if now_epoch is None else float(now_epoch)
    expiries = [
        expiry for expiry in (signed_url_expiry(item.get("url") or item.get("playback_url")) for item in direct)
        if expiry is not None
    ]
    if expiries:
        return max(expiries) <= now + DIRECT_REFRESH_MARGIN_SECONDS
    updated = row["link_updated_at"] if "link_updated_at" in row.keys() else None
    if updated is None or not str(updated).strip():
        return True
    text = str(updated).strip()
    try:
        stamp = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if stamp.tzinfo is None:
            stamp = stamp.replace(tzinfo=timezone.utc)
        updated_epoch = stamp.timestamp()
    except (TypeError, ValueError):
        return True
    return now - updated_epoch >= DIRECT_REFRESH_MAX_AGE_SECONDS


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
        "provider_timeout_total": 0,
        "retry_after": {},
        "failure_streaks": {},
        "last_pass_completed_at": None,
        "updated_at": _now(),
    }


def _count_valid_catalog_rows() -> int:
    """Count rows containing at least one source-backed valid stream."""
    try:
        with closing(get_db()) as db, db:
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
        with closing(get_db()) as db, db:
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
        "provider_timeout_total",
    ):
        state[key] = _as_int(state.get(key))

    retry_after = state.get("retry_after")
    state["retry_after"] = retry_after if isinstance(retry_after, dict) else {}
    failure_streaks = state.get("failure_streaks")
    state["failure_streaks"] = failure_streaks if isinstance(failure_streaks, dict) else {}

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
def _retry_key(content_id: int) -> str:
    return str(max(0, int(content_id)))


def _retry_due(state: Dict[str, Any], content_id: int, now_epoch: Optional[float] = None) -> bool:
    schedule = state.get("retry_after") if isinstance(state.get("retry_after"), dict) else {}
    try:
        retry_at = float(schedule.get(_retry_key(content_id)) or 0.0)
    except (TypeError, ValueError):
        retry_at = 0.0
    return retry_at <= (time.time() if now_epoch is None else float(now_epoch))


def _record_retry_outcome(
    state: Dict[str, Any],
    content_id: int,
    status: str,
    now_epoch: Optional[float] = None,
) -> None:
    key = _retry_key(content_id)
    now = time.time() if now_epoch is None else float(now_epoch)
    schedule = state.setdefault("retry_after", {})
    streaks = state.setdefault("failure_streaks", {})
    if not isinstance(schedule, dict):
        schedule = state["retry_after"] = {}
    if not isinstance(streaks, dict):
        streaks = state["failure_streaks"] = {}

    if status in {"persisted", "duplicate"}:
        schedule.pop(key, None)
        streaks.pop(key, None)
        return
    if status == "coverage_complete":
        streaks.pop(key, None)
        schedule[key] = int(now + COMPLETE_COVERAGE_REFRESH_SECONDS)
        return
    if status == "partial_coverage":
        streaks.pop(key, None)
        schedule[key] = int(now + PARTIAL_COVERAGE_RETRY_SECONDS)
        return
    if status == "no_source_recent":
        streaks.pop(key, None)
        schedule[key] = int(now + RECENT_NO_SOURCE_RETRY_SECONDS)
        return
    if status in {"provider_error", "provider_timeout"}:
        failures = max(1, _as_int(streaks.get(key), 0) + 1)
        streaks[key] = failures
        delay = min(
            PROVIDER_ERROR_RETRY_MAX_SECONDS,
            PROVIDER_ERROR_RETRY_BASE_SECONDS * (2 ** min(failures - 1, 8)),
        )
    elif status == "provider_deferred":
        streaks.pop(key, None)
        delay = PROVIDER_ERROR_RETRY_BASE_SECONDS
    elif status == "no_source":
        streaks.pop(key, None)
        delay = NO_SOURCE_RETRY_SECONDS
    elif status == "rejected_by_identity":
        streaks.pop(key, None)
        delay = IDENTITY_RETRY_SECONDS
    elif status == "persistence_error":
        delay = PERSISTENCE_RETRY_SECONDS
    else:
        delay = PROVIDER_ERROR_RETRY_BASE_SECONDS
    schedule[key] = int(now + delay)


def _fetch_rows(db: Any, last_id: int, state: Optional[Dict[str, Any]] = None) -> List[Any]:
    # New releases get a reserved budget on every pass; the remaining budget advances old coverage.
    # Never read the whole catalog into RAM just to slice it afterwards.
    columns="id,tmdb_id,media_type,title,original_title,year,category,rating,vote_count,streams,playback_url,link_verified,link_updated_at"
    # Generic TV cards cannot supply an exact episode; the dedicated episode
    # discovery path handles them. Do not spend background movie slots on TV.
    # Only refresh persisted direct HTTP streams by age. Magnet-only rows must
    # not be recycled every 30 minutes: P2P locators have no HTTP expiry.
    direct_hint = "(COALESCE(playback_url,'') LIKE 'http%' OR instr(streams, '\"url\": \"http')>0 OR instr(streams, '\"url\":\"http')>0)"
    needs=("(COALESCE(playback_url,'')='' OR COALESCE(streams,'') IN ('','[]') "
           "OR COALESCE(link_verified,0)=0 OR (COALESCE(link_updated_at,'')<? AND " + direct_hint + "))")
    now_epoch=time.time();cutoff=datetime.fromtimestamp(now_epoch-1800,timezone.utc).isoformat()
    recent_year=datetime.now(timezone.utc).year-1
    recent=db.execute(
        "SELECT "+columns+" FROM movies WHERE media_type='movie' AND COALESCE(metadata_source,'') NOT IN ('tmdb_wrong_media_type','tmdb_wrong_media_type_with_streams') AND year>=? AND "+needs+
        " ORDER BY COALESCE(vote_count,0) DESC, COALESCE(rating,0) DESC, id DESC LIMIT 300",
        (recent_year,cutoff),
    ).fetchall()
    popular_older=db.execute(
        "SELECT "+columns+" FROM movies WHERE media_type='movie' AND COALESCE(metadata_source,'') NOT IN ('tmdb_wrong_media_type','tmdb_wrong_media_type_with_streams') AND COALESCE(year,0)<? AND "+needs+
        " ORDER BY COALESCE(vote_count,0) DESC, COALESCE(rating,0) DESC, id DESC LIMIT 300",
        (recent_year,cutoff),
    ).fetchall()
    cursor=_as_int((state or {}).get("cloud_backfill_id"))
    older=db.execute(
        "SELECT "+columns+" FROM movies WHERE media_type='movie' AND COALESCE(metadata_source,'') NOT IN ('tmdb_wrong_media_type','tmdb_wrong_media_type_with_streams') AND id>? AND COALESCE(year,0)<? AND "+needs+
        " ORDER BY id ASC LIMIT 300",
        (cursor,recent_year,cutoff),
    ).fetchall()
    if not older and state is not None:
        state["cloud_backfill_id"]=0
    def coverage_priority(row):
        try:
            raw = json.loads(row["streams"] or "[]")
        except (TypeError, ValueError, json.JSONDecodeError):
            raw = []
        coverage = variant_coverage(raw, media_type=str(row["media_type"] or "movie"))
        if coverage.requires_episode_identity:
            return 2
        # Variant counts are audit statistics, never a reason to stop discovery.
        return coverage.streams
    def due(rows):
        eligible=[]
        for row in rows:
            if state and not _retry_due(state,_as_int(row["id"]),now_epoch):
                continue
            has_saved = str(row["streams"] or "") not in ("", "[]", "null")
            has_primary = bool(str(row["playback_url"] or "").strip())
            if has_saved and has_primary and _as_int(row["link_verified"]) == 1:
                if not _catalog_streams_need_refresh(row, now_epoch=now_epoch):
                    continue
            eligible.append(row)
        return sorted(eligible, key=coverage_priority)
    selected=[];seen=set()
    def take(rows,limit):
        for row in due(rows):
            key=_as_int(row["id"])
            if key in seen: continue
            selected.append(row);seen.add(key)
            if sum(1 for _ in selected) >= limit: break
    # Reserve one third of every pass for each cohort: recent demand, proven
    # popular catalog, and deterministic long-tail progress.
    take(recent,10)
    target=20
    for row in due(popular_older):
        key=_as_int(row["id"])
        if key in seen: continue
        selected.append(row);seen.add(key)
        if len(selected)>=target: break
    for row in due(older):
        key=_as_int(row["id"])
        if key in seen: continue
        selected.append(row);seen.add(key)
        if len(selected)>=30: break
    return selected


def _valid_persisted_row(content_id: int) -> bool:
    try:
        with closing(get_db()) as db, db:
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


def _persisted_variant_coverage(content_id: int):
    try:
        with closing(get_db()) as db, db:
            row = db.execute(
                "SELECT streams, media_type FROM movies WHERE id = ?",
                (content_id,),
            ).fetchone()
        if not row:
            return None
        try:
            raw = json.loads(row["streams"] or "[]")
        except (TypeError, ValueError, json.JSONDecodeError):
            raw = []
        return variant_coverage(raw, media_type=str(row["media_type"] or "movie"))
    except Exception:
        return None


def _candidate_streams(found_stream: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
    if not isinstance(found_stream, dict):
        return []
    raw_streams = found_stream.get("streams")
    if not isinstance(raw_streams, list):
        return []
    return sanitize_streams(raw_streams, require_source=True)


def _rewrite_torrent_rows_for_persistence(
    streams: List[Dict[str, Any]],
    row: Any,
) -> List[Dict[str, Any]]:
    if os.environ.get("MOVIA_ENABLE_TORRENT_PROVIDER_CONTRACT", "0") != "1":
        return streams
    magnet_rows = [item for item in streams if str(item.get("url") or "").startswith("magnet:?")]
    if not magnet_rows:
        return streams
    direct_rows = [item for item in streams if not str(item.get("url") or "").startswith("magnet:?")]
    try:
        from provider_contract import ProviderRequest
        from torrent_provider_adapter import rewrite_torrent_rows_as_variant_tree
        media_type = str(row["media_type"] or "movie").strip().casefold()
        request = ProviderRequest(
            media_id=str(row["id"]),
            title=str(row["title"] or "").strip(),
            year=_as_int(row["year"]) or None,
            media_type="tv" if media_type in {"tv", "series", "serial", "tv_series", "limited_series"} else "movie",
        )
        rewritten = rewrite_torrent_rows_as_variant_tree(magnet_rows, request)
        logger.info(
            "Torrent VariantTree persistence rewrite ID=%s input=%s leaves=%s",
            row["id"], len(magnet_rows), len(rewritten),
        )
        return sanitize_streams(direct_rows + rewritten, require_source=True)
    except Exception as exc:
        logger.warning(
            "Torrent VariantTree persistence rewrite failed ID=%s error=%s",
            row["id"], type(exc).__name__,
        )
        return streams


def _resolve_balancer_with_diagnostics(**request):
    """Read thread-local balancer diagnostics on the SAME worker as resolver."""
    streams = resolve_balancer(**request)
    diagnostics = get_last_resolution_diagnostics()
    return streams, dict(diagnostics)


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
    row_provider_timeouts = 0
    row_provider_deferred = 0
    torrent_status = "NOT_REQUESTED"
    provider_status = "NOT_ATTEMPTED"
    balancer_status = "NOT_ATTEMPTED"
    persisted_ok = False

    cloud_mode = os.environ.get("MOVIA_CLOUD_MODE", "0") == "1"
    background_bulk = (
        os.environ.get("MOVIA_BACKGROUND_BULK", "0") == "1"
        or cloud_mode
    )
    allow_background_torrent = (
        not cloud_mode
        and os.environ.get("MOVIA_BACKGROUND_TORRENT_LOOKUP", "0") == "1"
    )
    should_resolve_torrent = not background_bulk or allow_background_torrent

    provider_future = _FILL_PROVIDER_EXECUTOR.submit(
        discover_provider_streams,
        title=search_title,
        original_title=original_title,
        year=year,
        media_id=str(content_id),
        media_type=str(row["media_type"] or category),
    )
    balancer_future = _FILL_BALANCER_EXECUTOR.submit(
        _resolve_balancer_with_diagnostics,
        title=search_title,
        year=year,
        tmdb_id=tmdb_id,
        expected_titles=list(dict.fromkeys(
            value for value in (search_title, title, original_title) if value
        )),
        media_type=category,
    )
    torrent_future = None
    torrent_started_at = time.monotonic()
    if should_resolve_torrent:
        acquired = not background_bulk or _FILL_BACKGROUND_TORRENT_SLOTS.acquire(blocking=False)
        if not acquired:
            torrent_status = "CAPACITY_DEFERRED"
            row_provider_deferred += 1
        else:
            try:
                torrent_future = _FILL_TORRENT_EXECUTOR.submit(
                    resolve_torrent,
                    title=search_title,
                    year=year,
                    category=category,
                )
                torrent_status = "STARTED"
                if background_bulk:
                    torrent_future.add_done_callback(_release_background_torrent_slot)
            except Exception:
                if background_bulk:
                    _FILL_BACKGROUND_TORRENT_SLOTS.release()
                torrent_status = "SUBMIT_ERROR"
                row_provider_errors += 1

    resolved_streams: List[Dict[str, Any]] = []
    direct_futures = {provider_future, balancer_future}
    done, pending = wait(
        direct_futures,
        timeout=_background_direct_provider_budget_seconds(),
    )

    if provider_future in done:
        try:
            provider_outcome = provider_future.result()
            provider_status = str(provider_outcome.status or "UNKNOWN")
            resolved_streams.extend(provider_outcome.streams)
            row_provider_errors += _as_int(provider_outcome.error_count)
            if provider_status == "PROVIDER_TIMEOUT":
                row_provider_timeouts += 1
            elif provider_status == "PROVIDER_COOLDOWN":
                row_provider_deferred += 1
            elif provider_status == "PROVIDER_ERROR" and not provider_outcome.error_count:
                row_provider_errors += 1
        except Exception as exc:
            provider_status = "PROVIDER_ERROR"
            row_provider_errors += 1
            logger.debug("Provider registry error for %s: %s", title, exc)
    else:
        provider_status = "BUDGET_TIMEOUT"
        row_provider_timeouts += 1
        provider_future.cancel()
        logger.debug("Provider registry budget exceeded for %s", title)

    if balancer_future in done:
        try:
            balancer_result, diagnostics = balancer_future.result()
            balancer_status = str(diagnostics.get("status") or "UNKNOWN")
            diagnostics_errors = _as_int(diagnostics.get("error_count"))
            row_provider_errors += diagnostics_errors
            if balancer_status == "PROVIDER_TIMEOUT":
                row_provider_timeouts += 1
            elif balancer_status == "PROVIDER_COOLDOWN":
                row_provider_deferred += 1
            elif balancer_status in {"PROVIDER_ERROR", "NETWORK_ERROR", "RATE_LIMIT", "INVALID_RESPONSE", "DB_ERROR"} and not diagnostics_errors:
                row_provider_errors += 1
            resolved_streams.extend(_candidate_streams(balancer_result))
        except Exception as exc:
            balancer_status = "PROVIDER_ERROR"
            row_provider_errors += 1
            logger.debug("Balancer error for %s: %s", title, exc)
    else:
        balancer_status = "BUDGET_TIMEOUT"
        row_provider_timeouts += 1
        balancer_future.cancel()
        logger.debug("Balancer budget exceeded for %s", title)

    # Pending direct-provider work must never block the additive torrent path.
    # Running calls may finish in the shared executor, but their late result is
    # intentionally ignored for this background row and can be discovered on a
    # later retry cycle.
    for future in pending:
        future.cancel()

    # The P2P budget includes concurrent direct discovery. In background mode
    # a slow torrent cannot block the durable cursor indefinitely. Cancelling a
    # running Future does not abort its network calls; the capacity semaphore
    # prevents orphaned requests from creating unbounded executor queues.
    if torrent_future is not None:
        try:
            remaining = max(0.0, _background_torrent_budget_seconds() - (
                time.monotonic() - torrent_started_at
            )) if background_bulk else None
            torrent_result = torrent_future.result(timeout=remaining)
            torrent_candidates = _candidate_streams(torrent_result)
            torrent_status = "OK" if torrent_candidates else "NO_RESULTS"
            resolved_streams.extend(torrent_candidates)
        except FutureTimeoutError:
            torrent_status = "BUDGET_TIMEOUT"
            row_provider_timeouts += 1
            torrent_future.cancel()
        except Exception as exc:
            torrent_status = "PROVIDER_ERROR"
            row_provider_errors += 1
            logger.debug("Torrent error for %s: %s", title, exc)

    resolved_streams = sanitize_streams(resolved_streams, require_source=True)
    resolved_streams = _rewrite_torrent_rows_for_persistence(resolved_streams, row)
    if resolved_streams:
        primary = resolved_streams[0]
        found_stream = {
            "playback_url": primary.get("url", ""),
            "voice": primary.get("voice", "Не указано"),
            "quality": primary.get("quality", "Не указано"),
            "seeders": primary.get("seeders", 0),
            "streams": resolved_streams,
        }
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
            # Feed newly persisted direct candidates into Source Truth immediately.
            # This removes the old need for a later full catalog backfill pass.
            try:
                media_kind = MEDIA_SERIES if str(row["media_type"] or "").lower() == "tv" else MEDIA_MOVIE
                PlaybackAvailabilityService(
                    DIR / "stream_cache" / "playback_availability_v1.db"
                ).record_discovery(
                    content_id,
                    clean_streams,
                    kind=media_kind,
                    discovery_method=DISCOVERY_BACKGROUND,
                )
            except Exception as exc:
                logger.debug("Source Truth ingestion failed for ID=%s: %s", content_id, exc)
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

    coverage = _persisted_variant_coverage(content_id) if persisted_ok else None
    if persisted_ok:
        # 3x3 is a diagnostic target, not an exhausted-provider inventory.
        # Keep normal freshness scheduling for 1, 3 or 30 voices alike.
        status = "duplicate" if duplicate_candidate else "persisted"
    elif clean_streams:
        status = "persistence_error"
    elif found_stream:
        status = "rejected_by_identity"
    elif row_provider_errors:
        status = "provider_error"
    elif row_provider_timeouts:
        status = "provider_timeout"
    elif row_provider_deferred:
        status = "provider_deferred"
    elif year >= datetime.now(timezone.utc).year - 1:
        status = "no_source_recent"
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
        "provider_timeouts": row_provider_timeouts,
        "provider_deferred": row_provider_deferred,
        "provider_status": provider_status,
        "balancer_status": balancer_status,
        "torrent_status": torrent_status,
        "coverage_complete": bool(coverage.complete) if coverage is not None else False,
        "coverage_voices": int(coverage.voices) if coverage is not None else 0,
        "coverage_qualities": int(coverage.qualities) if coverage is not None else 0,
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
    cloud_mode = os.environ.get("MOVIA_CLOUD_MODE", "0") == "1"
    background_guard = (
        os.environ.get("MOVIA_BACKGROUND_BULK", "0") == "1"
        and not cloud_mode
    )
    if background_guard:
        decision = background_bulk_allowed()
        if not decision.allowed:
            logger.info("⏸️ Background enrichment blocked: %s", decision.reason)
            return {"processed": 0, "blocked": True, "reason": decision.reason}

    with closing(get_db()) as db, db:
        rows = _fetch_rows(db, last_id, state)
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
    provider_timeouts = 0
    status_counts = Counter()
    provider_status_counts = Counter()
    balancer_status_counts = Counter()
    torrent_status_counts = Counter()
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
            if background_guard:
                decision = background_bulk_allowed()
                if not decision.allowed:
                    logger.info(
                        "⏸️ Background enrichment paused before batch %s: %s",
                        batch_start // batch_size + 1,
                        decision.reason,
                    )
                    break
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
                if _as_int(row["year"]) < datetime.now(timezone.utc).year-1:
                    state["cloud_backfill_id"] = content_id
                state["processed_total"] = _as_int(state.get("processed_total")) + 1
                state["attempted_total"] = _as_int(state.get("attempted_total")) + 1
                resolver_hits += int(bool(result.get("resolver_hit")))
                persisted += int(bool(result.get("persisted_ok")))
                no_source += int(bool(result.get("no_source")))
                invalid_results += int(bool(result.get("invalid_result")))
                persist_failures += int(bool(result.get("persist_failure")))
                provider_errors += _as_int(result.get("provider_errors"))
                provider_timeouts += _as_int(result.get("provider_timeouts"))
                provider_status_counts[str(result.get("provider_status") or "UNKNOWN")] += 1
                balancer_status_counts[str(result.get("balancer_status") or "UNKNOWN")] += 1
                torrent_status_counts[str(result.get("torrent_status") or "UNKNOWN")] += 1
                status = str(result.get("status") or "provider_error")
                status_counts[status] += 1
                _record_retry_outcome(state, content_id, status)
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
                state["provider_timeout_total"] = _as_int(
                    state.get("provider_timeout_total")
                ) + _as_int(result.get("provider_timeouts"))

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
    logger.info("Provider diagnostics: registry=%s balancer=%s torrent=%s timeouts=%s",
                dict(provider_status_counts), dict(balancer_status_counts),
                dict(torrent_status_counts), provider_timeouts)
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
        "provider_status_counts": dict(provider_status_counts),
        "balancer_status_counts": dict(balancer_status_counts),
        "torrent_status_counts": dict(torrent_status_counts),
        "provider_timeouts": provider_timeouts,
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
