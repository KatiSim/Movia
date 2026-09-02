#!/usr/bin/env python3
"""Incremental TMDb metadata sync for Movia's canonical catalog.db.

This module only discovers metadata. Playback availability remains a separate
on-demand resolver concern, so catalog refresh cannot inject playable URLs.

The streamer owns the background worker. A sync is deliberately conservative:
it has a five-minute minimum interval, deduplicates by ``(media_type, tmdb_id)``,
and keeps already-enriched TV season data when a list feed only contains a
summary.
"""
from __future__ import annotations

import json
import re
import sqlite3
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

from catalog_localization import meta_localized_title, parse_alternative_titles
from tmdb_client import tmdb
from metadata_quality import bayesian_rating

DIR = Path(__file__).resolve().parent
DB_PATH = DIR / "catalog.db"

MIN_SYNC_INTERVAL_SECONDS = 300
DEFAULT_SYNC_INTERVAL_SECONDS = MIN_SYNC_INTERVAL_SECONDS
DEFAULT_SYNC_PAGES = 2

_SYNC_LOCK = threading.Lock()
_DB_WRITE_LOCK = threading.Lock()
_STATUS_LOCK = threading.Lock()
_WORKER_STATE_LOCK = threading.Lock()
_WORKER_STARTED = False
_WORKER_THREAD: Optional[threading.Thread] = None

_STATUS: Dict[str, Any] = {
    "running": False,
    "last_started_at": None,
    "last_finished_at": None,
    "last_success_at": None,
    "last_error": None,
    "last_feed_errors": [],
    "last_cache_error": None,
    "last_run_ok": None,
    "last_duration_seconds": None,
    "last_seen": 0,
    "last_inserted": 0,
    "last_updated": 0,
    "consecutive_failures": 0,
    "sync_interval_seconds": DEFAULT_SYNC_INTERVAL_SECONDS,
}

# These are the authorized TMDb discovery feeds used by the existing catalog
# architecture. Keep the list centralized so coverage is auditable.
FEEDS: Tuple[Tuple[str, str, Dict[str, str]], ...] = (
    ("movie", "/trending/movie/day", {}),
    ("tv", "/trending/tv/day", {}),
    ("movie", "/movie/now_playing", {}),
    ("tv", "/tv/on_the_air", {}),
    ("movie", "/movie/popular", {}),
    ("tv", "/tv/popular", {}),
    (
        "movie",
        "/discover/movie",
        {"sort_by": "primary_release_date.desc", "include_adult": "false"},
    ),
    (
        "tv",
        "/discover/tv",
        {"sort_by": "first_air_date.desc", "include_adult": "false"},
    ),
)

COUNTRY_NAMES = {
    "US": "США", "CA": "Канада", "GB": "Великобритания", "RU": "Россия",
    "FR": "Франция", "DE": "Германия", "IT": "Италия", "ES": "Испания",
    "JP": "Япония", "KR": "Южная Корея", "TR": "Турция", "CN": "Китай",
    "AU": "Австралия", "IN": "Индия", "BR": "Бразилия", "MX": "Мексика",
}


def _now() -> str:
    """Return an unambiguous UTC timestamp for status and audit output."""
    return datetime.now(timezone.utc).isoformat()


def _status_update(**values: Any) -> None:
    with _STATUS_LOCK:
        _STATUS.update(values)


def _timestamp_age_seconds(value: Any) -> Optional[float]:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return max(0.0, time.time() - parsed.timestamp())
    except (TypeError, ValueError, OverflowError):
        return None


def status() -> Dict[str, Any]:
    """Return a JSON-safe snapshot of the in-memory sync state.

    ``last_success_at`` means the last complete feed pass. A partial pass may
    still insert useful metadata, but freshness is measured from the last
    complete pass so operators can see that partial work did not reset it.
    """
    with _STATUS_LOCK:
        snapshot = dict(_STATUS)
    snapshot["last_feed_errors"] = list(snapshot.get("last_feed_errors") or [])

    with _WORKER_STATE_LOCK:
        worker = _WORKER_THREAD
        worker_started = _WORKER_STARTED
    snapshot["worker_started"] = worker_started
    snapshot["worker_alive"] = bool(worker and worker.is_alive())

    success_age = _timestamp_age_seconds(snapshot.get("last_success_at"))
    finished_age = _timestamp_age_seconds(snapshot.get("last_finished_at"))
    interval = max(
        MIN_SYNC_INTERVAL_SECONDS,
        _safe_int(snapshot.get("sync_interval_seconds"), DEFAULT_SYNC_INTERVAL_SECONDS),
    )
    snapshot["last_success_age_seconds"] = success_age
    snapshot["last_finished_age_seconds"] = finished_age
    snapshot["sync_overdue"] = success_age is None or success_age > interval
    snapshot["overdue"] = snapshot["sync_overdue"]
    snapshot["sync_interval_seconds"] = interval
    return snapshot


def _safe_int(value: Any, default: int = 0) -> int:
    try:
        if isinstance(value, bool):
            return int(value)
        return int(value)
    except (TypeError, ValueError, OverflowError):
        return default


def _positive_int(value: Any, default: int = 0) -> int:
    parsed = _safe_int(value, default)
    return parsed if parsed > 0 else default


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        parsed = float(value)
        return parsed if parsed == parsed else default
    except (TypeError, ValueError, OverflowError):
        return default


def _release_year(value: Any) -> int:
    """Extract a year without substituting the worker's current year."""
    if value is None:
        return 0
    match = re.match(r"^(\d{4})", str(value).strip())
    if not match:
        return 0
    year = _safe_int(match.group(1))
    return year if 1 <= year <= 9999 else 0


def _image_url(path: Any, size: str) -> str:
    raw = str(path or "").strip()
    if not raw:
        return ""
    if raw.startswith("http://") or raw.startswith("https://"):
        return raw
    return f"https://image.tmdb.org/t/p/{size}{raw}"


def _category(media_type: str, genres: Iterable[str], countries: Iterable[str]) -> str:
    low = {str(g).lower() for g in genres if g}
    countries_u = {str(c).upper() for c in countries if c}
    animation = any("мульт" in g or "animation" in g for g in low)
    documentary = any("документ" in g or "documentary" in g for g in low)
    if animation and "JP" in countries_u:
        return "anime"
    if animation:
        return "animation"
    if documentary:
        return "documentaries"
    return "tv_series" if media_type == "tv" else "movies"


def _summary_to_meta(item: Dict[str, Any], media_type: str) -> Dict[str, Any]:
    media_type = str(media_type or "movie").lower()
    title = item.get("name") if media_type == "tv" else item.get("title")
    original = item.get("original_name") if media_type == "tv" else item.get("original_title")
    date = item.get("first_air_date") if media_type == "tv" else item.get("release_date")
    year = _release_year(date)

    # TMDb list responses normally contain genre_ids. Search responses can
    # occasionally already contain names, so retain those when available.
    genre_map = {
        16: "мультфильм", 18: "драма", 28: "боевик", 35: "комедия",
        53: "триллер", 27: "ужасы", 99: "документальный", 10751: "семейный",
        878: "фантастика", 14: "фэнтези", 12: "приключения", 80: "криминал",
        9648: "детектив", 10749: "мелодрама", 36: "история", 10752: "военный",
        37: "вестерн", 10402: "музыка", 10759: "боевик", 10765: "фантастика",
    }
    genre_names: List[str] = []
    for raw_gid in item.get("genre_ids") or []:
        gid = _safe_int(raw_gid)
        name = genre_map.get(gid)
        if name and name not in genre_names:
            genre_names.append(name)
    raw_genres = item.get("genres")
    if not genre_names and isinstance(raw_genres, list):
        for raw_genre in raw_genres:
            name = raw_genre.get("name") if isinstance(raw_genre, dict) else raw_genre
            if name and str(name) not in genre_names:
                genre_names.append(str(name))

    raw_countries = item.get("origin_country") or []
    countries = [str(country).upper() for country in raw_countries if country] if isinstance(raw_countries, list) else []
    country = COUNTRY_NAMES.get(countries[0], "Зарубежный") if countries else "Зарубежный"

    episode_runtime = item.get("episode_run_time")
    if isinstance(episode_runtime, list):
        duration = _positive_int(episode_runtime[0] if episode_runtime else 0)
    else:
        duration = _positive_int(episode_runtime)
    if not duration:
        duration = _positive_int(item.get("runtime"))
    if not duration:
        duration = 45 if media_type == "tv" else 90

    return {
        "tmdb_id": _positive_int(item.get("id")),
        "media_type": media_type,
        "title": str(title or original or "Без названия"),
        "localized_ru_title": meta_localized_title({
            "title": title,
            "original_title": original,
        }) or "",
        "localization_source": "tmdb_ru" if meta_localized_title({
            "title": title,
            "original_title": original,
        }) else "",
        "alternative_titles": [],
        "original_title": str(original or ""),
        # Unknown dates remain unknown. Do not fabricate the current year.
        "year": year,
        "rating": bayesian_rating(_safe_float(item.get("vote_average")), _positive_int(item.get("vote_count"))),
        "vote_count": _positive_int(item.get("vote_count")),
        "vote_average": _safe_float(item.get("vote_average")),
        "duration_minutes": duration,
        "synopsis": str(item.get("overview") or ""),
        "poster_url": _image_url(item.get("poster_path"), "w500"),
        "backdrop_url": _image_url(item.get("backdrop_path"), "original"),
        "genres": genre_names,
        "cast": [],
        "director": "",
        "country": country,
        "category": _category(media_type, genre_names, countries),
        # List feeds do not reliably carry TV structure. Details enrichment
        # owns exact counts; existing non-zero counts are preserved by _upsert.
        "seasons_count": _positive_int(item.get("number_of_seasons")),
        "episodes_count": _positive_int(item.get("number_of_episodes")),
    }


def _upsert(conn: sqlite3.Connection, meta: Dict[str, Any]) -> bool:
    """Insert or refresh one canonical row; return True only for an insert."""
    tmdb_id = _positive_int(meta.get("tmdb_id"))
    if not tmdb_id:
        return False
    media_type = str(meta.get("media_type") or "movie").lower()
    existed = conn.execute(
        "SELECT 1 FROM movies WHERE media_type=? AND tmdb_id=?",
        (media_type, tmdb_id),
    ).fetchone() is not None
    conn.execute(
        """
        INSERT INTO movies (
            tmdb_id,media_type,title,original_title,year,rating,duration_minutes,synopsis,
            poster_url,backdrop_url,genres,cast,director,country,category,streams,
            vote_count,vote_average,seasons_count,episodes_count
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, '[]', ?,?,?,?)
        ON CONFLICT(media_type,tmdb_id) DO UPDATE SET
            title=CASE WHEN excluded.title!='Без названия' THEN excluded.title ELSE movies.title END,
            original_title=CASE WHEN excluded.original_title!='' THEN excluded.original_title ELSE movies.original_title END,
            -- A populated year may have been enriched from a detail record;
            -- never replace that trusted value during a list-feed refresh.
            year=CASE WHEN movies.year>0 THEN movies.year ELSE excluded.year END,
            rating=CASE WHEN excluded.rating>0 THEN excluded.rating ELSE movies.rating END,
            vote_count=CASE WHEN excluded.vote_count>0 THEN excluded.vote_count ELSE movies.vote_count END,
            vote_average=CASE WHEN excluded.vote_average>0 THEN excluded.vote_average ELSE movies.vote_average END,
            duration_minutes=CASE WHEN movies.duration_minutes>0 THEN movies.duration_minutes ELSE excluded.duration_minutes END,
            synopsis=CASE WHEN excluded.synopsis!='' THEN excluded.synopsis ELSE movies.synopsis END,
            poster_url=CASE WHEN excluded.poster_url!='' THEN excluded.poster_url ELSE movies.poster_url END,
            backdrop_url=CASE WHEN excluded.backdrop_url!='' THEN excluded.backdrop_url ELSE movies.backdrop_url END,
            genres=CASE
                WHEN movies.metadata_source='tmdb_detail' THEN movies.genres
                WHEN excluded.genres!='[]' THEN excluded.genres ELSE movies.genres END,
            country=CASE
                WHEN movies.metadata_source='tmdb_detail' THEN movies.country
                WHEN excluded.country!='Зарубежный' THEN excluded.country ELSE movies.country END,
            category=CASE
                WHEN movies.metadata_source='tmdb_detail' THEN movies.category
                WHEN excluded.genres!='[]' THEN excluded.category
                WHEN movies.media_type='tv' THEN 'tv_series'
                ELSE movies.category
            END,
            seasons_count=CASE WHEN movies.seasons_count>0 THEN movies.seasons_count ELSE excluded.seasons_count END,
            episodes_count=CASE WHEN movies.episodes_count>0 THEN movies.episodes_count ELSE excluded.episodes_count END
        """,
        (
            tmdb_id, media_type, meta.get("title") or "Без названия",
            meta.get("original_title", ""), _safe_int(meta.get("year")),
            _safe_float(meta.get("rating")), _positive_int(meta.get("duration_minutes")),
            meta.get("synopsis", ""), meta.get("poster_url", ""), meta.get("backdrop_url", ""),
            json.dumps(meta.get("genres") or [], ensure_ascii=False),
            json.dumps(meta.get("cast") or [], ensure_ascii=False), meta.get("director", ""),
            meta.get("country", "Зарубежный"), meta.get("category", "movies"),
            _positive_int(meta.get("vote_count")), _safe_float(meta.get("vote_average")),
            _positive_int(meta.get("seasons_count")), _positive_int(meta.get("episodes_count")),
        ),
    )
    return not existed


def _feed_label(endpoint: str, page: int) -> str:
    return f"{endpoint}?page={page}"


def _format_errors(errors: Iterable[str]) -> Optional[str]:
    values = [str(error) for error in errors if error]
    if not values:
        return None
    return "; ".join(values[:20])


def _normalise_pages(pages: Any) -> int:
    try:
        return max(1, int(pages))
    except (TypeError, ValueError, OverflowError):
        return DEFAULT_SYNC_PAGES


def _tmdb_get(endpoint: str, params: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Fetch a discovery page with the sync-only bounded retry policy.

    The compatibility fallback keeps lightweight test doubles and older local
    clients usable; those callers receive their normal single request.
    """
    try:
        return tmdb._get(endpoint, params, max_retries=1)
    except TypeError as exc:
        try:
            return tmdb._get(endpoint, params)
        except TypeError:
            raise exc


def sync_once(pages: int = DEFAULT_SYNC_PAGES) -> Dict[str, Any]:
    """Run one bounded metadata pass.

    A page-level failure does not discard successful feeds. It is reported as
    partial, and therefore does not advance ``last_success_at``.
    """
    if not _SYNC_LOCK.acquire(blocking=False):
        return {**status(), "skipped": "already_running"}

    started_at = _now()
    started_mono = time.monotonic()
    _status_update(running=True, last_started_at=started_at)
    seen: Dict[Tuple[str, int], Dict[str, Any]] = {}
    feed_errors: List[str] = []
    inserted = 0
    updated = 0
    db_committed = False

    try:
        page_count = _normalise_pages(pages)
        for media_type, endpoint, extra in FEEDS:
            for page in range(1, page_count + 1):
                label = _feed_label(endpoint, page)
                try:
                    data = _tmdb_get(endpoint, {"page": page, **extra}) or None
                except Exception as exc:
                    feed_errors.append(f"{label}: {type(exc).__name__}: {exc}")
                    continue
                if not isinstance(data, dict):
                    feed_errors.append(f"{label}: no response")
                    continue
                results = data.get("results")
                if results is None:
                    feed_errors.append(f"{label}: missing results")
                    continue
                if not isinstance(results, list):
                    feed_errors.append(f"{label}: invalid results")
                    continue
                for item in results:
                    if not isinstance(item, dict):
                        continue
                    tid = _positive_int(item.get("id"))
                    if tid:
                        # The feed media type is authoritative even when a list
                        # item has an absent or inconsistent media_type field.
                        seen[(media_type, tid)] = item

        with _DB_WRITE_LOCK:
            with sqlite3.connect(str(DB_PATH), timeout=20.0) as conn:
                conn.execute("PRAGMA busy_timeout=20000")
                for (media_type, _), item in seen.items():
                    try:
                        meta = _summary_to_meta(item, media_type)
                        if _upsert(conn, meta):
                            inserted += 1
                        else:
                            updated += 1
                    except Exception as exc:
                        feed_errors.append(
                            f"{media_type}:{item.get('id', '?')}: {type(exc).__name__}: {exc}"
                        )
                conn.commit()
                db_committed = True

        cache_error: Optional[str] = None
        try:
            import catalog_api
            invalidate_home_cache = getattr(catalog_api, "invalidate_home_cache", None)
            if callable(invalidate_home_cache):
                invalidate_home_cache()
        except Exception as exc:
            cache_error = f"{type(exc).__name__}: {exc}"

        all_errors = list(feed_errors)
        if cache_error:
            all_errors.append(f"cache invalidation: {cache_error}")
        finished_at = _now()
        run_ok = not all_errors
        with _STATUS_LOCK:
            consecutive = int(_STATUS.get("consecutive_failures") or 0)
            updates: Dict[str, Any] = {
                "running": False,
                "last_finished_at": finished_at,
                "last_error": _format_errors(all_errors),
                "last_feed_errors": all_errors,
                "last_cache_error": cache_error,
                "last_run_ok": run_ok,
                "last_duration_seconds": round(time.monotonic() - started_mono, 3),
                "last_seen": len(seen),
                "last_inserted": inserted,
                "last_updated": updated,
                "consecutive_failures": 0 if run_ok else consecutive + 1,
            }
            if run_ok:
                updates["last_success_at"] = finished_at
            _STATUS.update(updates)
        return status()
    except Exception as exc:
        if not db_committed:
            inserted = 0
            updated = 0
        finished_at = _now()
        errors = list(feed_errors)
        errors.append(f"sync: {type(exc).__name__}: {exc}")
        with _STATUS_LOCK:
            consecutive = int(_STATUS.get("consecutive_failures") or 0)
            _STATUS.update(
                running=False,
                last_finished_at=finished_at,
                last_error=_format_errors(errors),
                last_feed_errors=errors,
                last_cache_error=None,
                last_run_ok=False,
                last_duration_seconds=round(time.monotonic() - started_mono, 3),
                last_seen=len(seen),
                last_inserted=inserted,
                last_updated=updated,
                consecutive_failures=consecutive + 1,
            )
        return status()
    finally:
        _SYNC_LOCK.release()


def _normalise_interval(interval_seconds: Any) -> int:
    try:
        requested = int(interval_seconds)
    except (TypeError, ValueError, OverflowError):
        requested = DEFAULT_SYNC_INTERVAL_SECONDS
    return max(MIN_SYNC_INTERVAL_SECONDS, requested)


def _record_worker_error(exc: BaseException) -> None:
    now = _now()
    _status_update(
        running=False,
        last_finished_at=now,
        last_error=f"worker: {type(exc).__name__}: {exc}",
        last_feed_errors=[f"worker: {type(exc).__name__}: {exc}"],
        last_run_ok=False,
    )


def start_background_sync(interval_seconds: int = DEFAULT_SYNC_INTERVAL_SECONDS) -> None:
    """Start the single daemon-owned worker; its first pass runs immediately."""
    global _WORKER_STARTED, _WORKER_THREAD
    interval = _normalise_interval(interval_seconds)
    with _WORKER_STATE_LOCK:
        if _WORKER_STARTED:
            return
        _WORKER_STARTED = True
    _status_update(sync_interval_seconds=interval)

    def worker() -> None:
        while True:
            try:
                result = sync_once(pages=DEFAULT_SYNC_PAGES)
                print(
                    "[CATALOG-SYNC] seen={last_seen} inserted={last_inserted} "
                    "updated={last_updated} success={last_run_ok} error={last_error}".format(
                        last_seen=result.get("last_seen", 0),
                        last_inserted=result.get("last_inserted", 0),
                        last_updated=result.get("last_updated", 0),
                        last_run_ok=result.get("last_run_ok"),
                        last_error=result.get("last_error"),
                    ),
                    flush=True,
                )
            except BaseException as exc:
                # sync_once is defensive itself; this guard keeps an unexpected
                # logging or worker error from silently killing future passes.
                _record_worker_error(exc)
                print(f"[CATALOG-SYNC] worker error: {type(exc).__name__}: {exc}", flush=True)
            try:
                time.sleep(interval)
            except BaseException as exc:
                _record_worker_error(exc)

    thread = threading.Thread(target=worker, name="movia-catalog-sync", daemon=True)
    try:
        thread.start()
    except Exception as exc:
        with _WORKER_STATE_LOCK:
            _WORKER_STARTED = False
            _WORKER_THREAD = None
        _record_worker_error(exc)
        raise
    with _WORKER_STATE_LOCK:
        _WORKER_THREAD = thread


def discover_query(query: str, limit: int = 20) -> Dict[str, Any]:
    """Immediately import movie/TV metadata for a user search miss."""
    q = (query or "").strip()
    if not q:
        return {"query": q, "seen": 0, "inserted": 0, "updated": 0}

    try:
        data = _tmdb_get("/search/multi", {"query": q, "include_adult": "false", "page": 1})
    except Exception as exc:
        return {
            "query": q, "seen": 0, "inserted": 0, "updated": 0,
            "error": f"{type(exc).__name__}: {exc}",
        }
    if not isinstance(data, dict):
        return {"query": q, "seen": 0, "inserted": 0, "updated": 0, "error": "no response"}

    unique: Dict[Tuple[str, int], Dict[str, Any]] = {}
    for item in data.get("results") or []:
        if not isinstance(item, dict):
            continue
        media_type = str(item.get("media_type") or "").lower()
        tid = _positive_int(item.get("id"))
        if media_type in {"movie", "tv"} and tid:
            unique[(media_type, tid)] = item
    try:
        requested_limit = max(1, _safe_int(limit, 20))
    except Exception:
        requested_limit = 20
    candidates = list(unique.values())[:requested_limit]
    inserted = 0
    updated = 0
    errors: List[str] = []
    try:
        with _DB_WRITE_LOCK:
            with _catalog_connect(DB_PATH) as conn:
                conn.execute("PRAGMA busy_timeout=20000")
                for item in candidates:
                    try:
                        media_type = str(item.get("media_type")).lower()
                        if _upsert(conn, _summary_to_meta(item, media_type)):
                            inserted += 1
                        else:
                            updated += 1
                    except Exception as exc:
                        errors.append(f"{item.get('id', '?')}: {type(exc).__name__}: {exc}")
                conn.commit()
    except Exception as exc:
        return {
            "query": q, "seen": len(candidates), "inserted": 0, "updated": 0,
            "error": f"{type(exc).__name__}: {exc}",
        }

    if candidates:
        try:
            import catalog_api
            catalog_api.invalidate_home_cache()
        except Exception as exc:
            errors.append(f"cache invalidation: {type(exc).__name__}: {exc}")
    result: Dict[str, Any] = {
        "query": q, "seen": len(candidates), "inserted": inserted, "updated": updated,
    }
    if errors:
        result["error"] = _format_errors(errors)
    return result


# --- Durable normalization + continuous/deep discovery v2 ---
from catalog_schema_v2 import (
    bump_revision as _catalog_bump_revision,
    connect_catalog as _catalog_connect,
    ensure_schema as _ensure_catalog_schema_v2,
    get_cursor as _get_discovery_cursor,
    set_cursor as _set_discovery_cursor,
    set_meta as _set_catalog_meta,
    normalize_ru_text as _normalize_ru_title,
    upsert_trigram_index as _upsert_trigram_index,
)

try:
    _ensure_catalog_schema_v2(DB_PATH)
except Exception as _catalog_schema_error:
    print(f"[CATALOG-SYNC] schema bootstrap warning: {_catalog_schema_error}", flush=True)

_LEGACY_UPSERT = _upsert


def _upsert(conn: sqlite3.Connection, meta: Dict[str, Any]) -> bool:
    """Preserve legacy merge semantics and update the canonical search index."""
    inserted = _LEGACY_UPSERT(conn, meta)
    tmdb_id = _positive_int(meta.get("tmdb_id"))
    media_type = str(meta.get("media_type") or "movie").lower()
    row = conn.execute(
        "SELECT id,title,original_title,normalized_ru_title,"
        "normalized_original_title,localized_ru_title,alternative_titles,"
        "localization_source,localization_updated_at,updated_at FROM movies "
        "WHERE media_type=? AND tmdb_id=?",
        (media_type, tmdb_id),
    ).fetchone()
    if row:
        localized = meta_localized_title(meta) or str(row["localized_ru_title"] or "").strip()
        alternative_titles = parse_alternative_titles(
            meta.get("alternative_titles") or row["alternative_titles"]
        )
        if localized:
            conn.execute(
                "UPDATE movies SET localized_ru_title=?, localization_source=?, "
                "localization_updated_at=? WHERE id=?",
                (
                    localized,
                    str(meta.get("localization_source") or "tmdb_ru"),
                    _now(),
                    int(row["id"]),
                ),
            )
        if alternative_titles:
            conn.execute(
                "UPDATE movies SET alternative_titles=? WHERE id=?",
                (json.dumps(alternative_titles, ensure_ascii=False, separators=(",", ":")), int(row["id"])),
            )
        title_norm = _normalize_ru_title(localized)
        original_norm = _normalize_ru_title(row["original_title"])
        conn.execute(
            "UPDATE movies SET normalized_ru_title=?,"
            "normalized_original_title=?,updated_at=? WHERE id=?",
            (title_norm, original_norm, _now(), int(row["id"])),
        )
        _upsert_trigram_index(
            conn, int(row["id"]), title_norm, original_norm
        )
    return inserted


def _feed_key(media_type: str, endpoint: str) -> str:
    return f"{str(media_type).lower()}:{endpoint}"


def _read_discovery_page(media_type: str, endpoint: str, page: int, extra):
    params = {"page": int(page), **dict(extra or {})}
    return _tmdb_get(endpoint, params)


def sync_once(pages: int = DEFAULT_SYNC_PAGES) -> Dict[str, Any]:
    """Run one fast-feed pass plus one persistent deep-discovery page."""
    if not _SYNC_LOCK.acquire(blocking=False):
        return {**status(), "skipped": "already_running"}

    started_at = _now()
    started_mono = time.monotonic()
    _status_update(running=True, last_started_at=started_at)
    feed_errors: List[str] = []
    seen: Dict[Tuple[str, int], Dict[str, Any]] = {}
    inserted = 0
    updated = 0
    deep_descriptor = None
    deep_page = 0
    deep_next_page = 0
    deep_total_pages = 0
    try:
        fast_pages = max(1, min(int(pages), 2))
        for media_type, endpoint, extra in FEEDS:
            for page in range(1, fast_pages + 1):
                label = _feed_label(endpoint, page)
                try:
                    data = _read_discovery_page(media_type, endpoint, page, extra)
                except Exception as exc:
                    feed_errors.append(
                        f"{label}: {type(exc).__name__}: {exc}"
                    )
                    continue
                if not isinstance(data, dict) or not isinstance(data.get("results"), list):
                    feed_errors.append(f"{label}: invalid response")
                    continue
                for item in data["results"]:
                    if isinstance(item, dict):
                        tid = _positive_int(item.get("id"))
                        if tid:
                            seen[(media_type, tid)] = item

        with _catalog_connect(DB_PATH) as conn:
            deep_index_row = conn.execute(
                "SELECT value FROM catalog_meta WHERE key='deep_feed_index'"
            ).fetchone()
            deep_index = _safe_int(
                deep_index_row[0] if deep_index_row else 0,
                0,
            )
            deep_index %= len(FEEDS)
            deep_descriptor = FEEDS[deep_index]
            deep_media_type, deep_endpoint, deep_extra = deep_descriptor
            deep_page = _get_discovery_cursor(
                conn, _feed_key(deep_media_type, deep_endpoint)
            )
            try:
                deep_data = _read_discovery_page(
                    deep_media_type, deep_endpoint, deep_page, deep_extra
                )
            except Exception as exc:
                deep_data = None
                feed_errors.append(
                    f"{_feed_label(deep_endpoint, deep_page)}: "
                    f"{type(exc).__name__}: {exc}"
                )
            if isinstance(deep_data, dict) and isinstance(deep_data.get("results"), list):
                for item in deep_data["results"]:
                    if isinstance(item, dict):
                        tid = _positive_int(item.get("id"))
                        if tid:
                            seen[(deep_media_type, tid)] = item
                deep_total_pages = _positive_int(deep_data.get("total_pages"))
                deep_next_page = deep_page + 1
                if deep_total_pages and deep_next_page > deep_total_pages:
                    deep_next_page = 1
            else:
                if deep_data is not None:
                    feed_errors.append(
                        f"{_feed_label(deep_endpoint, deep_page)}: invalid response"
                    )
                deep_next_page = deep_page

            for (media_type, _), item in seen.items():
                try:
                    meta = _summary_to_meta(item, media_type)
                    if _upsert(conn, meta):
                        inserted += 1
                    else:
                        updated += 1
                except Exception as exc:
                    feed_errors.append(
                        f"{media_type}:{item.get('id', '?')}: "
                        f"{type(exc).__name__}: {exc}"
                    )
            _set_discovery_cursor(
                conn,
                _feed_key(deep_media_type, deep_endpoint),
                deep_media_type,
                deep_endpoint,
                deep_next_page,
                deep_total_pages,
            )
            _set_catalog_meta(conn, "deep_feed_index", (deep_index + 1) % len(FEEDS))
            if seen:
                _catalog_bump_revision(conn)
            conn.commit()

        try:
            import catalog_api
            invalidate_home_cache = getattr(catalog_api, "invalidate_home_cache", None)
            if callable(invalidate_home_cache):
                invalidate_home_cache()
        except Exception as exc:
            feed_errors.append(
                f"cache invalidation: {type(exc).__name__}: {exc}"
            )

        finished_at = _now()
        run_ok = not feed_errors
        with _STATUS_LOCK:
            consecutive = int(_STATUS.get("consecutive_failures") or 0)
            _STATUS.update(
                running=False,
                last_finished_at=finished_at,
                last_error=_format_errors(feed_errors),
                last_feed_errors=list(feed_errors),
                last_cache_error=None,
                last_run_ok=run_ok,
                last_duration_seconds=round(time.monotonic() - started_mono, 3),
                last_seen=len(seen),
                last_inserted=inserted,
                last_updated=updated,
                consecutive_failures=0 if run_ok else consecutive + 1,
            )
            if run_ok:
                _STATUS["last_success_at"] = finished_at
        return status()
    except Exception as exc:
        finished_at = _now()
        errors = list(feed_errors)
        errors.append(f"sync: {type(exc).__name__}: {exc}")
        with _STATUS_LOCK:
            consecutive = int(_STATUS.get("consecutive_failures") or 0)
            _STATUS.update(
                running=False,
                last_finished_at=finished_at,
                last_error=_format_errors(errors),
                last_feed_errors=errors,
                last_cache_error=None,
                last_run_ok=False,
                last_duration_seconds=round(time.monotonic() - started_mono, 3),
                last_seen=len(seen),
                last_inserted=inserted,
                last_updated=updated,
                consecutive_failures=consecutive + 1,
            )
        return status()
    finally:
        _SYNC_LOCK.release()


def start_background_sync(interval_seconds: int = DEFAULT_SYNC_INTERVAL_SECONDS) -> None:
    """Start one durable-in-process worker; fast sync and deep cursor share a DB."""
    global _WORKER_STARTED, _WORKER_THREAD
    interval = _normalise_interval(interval_seconds)
    with _WORKER_STATE_LOCK:
        if _WORKER_STARTED:
            return
        _WORKER_STARTED = True
    _status_update(sync_interval_seconds=interval)
    wake = threading.Event()

    def worker() -> None:
        while True:
            try:
                result = sync_once(pages=1)
                print(
                    "[CATALOG-SYNC] seen={last_seen} inserted={last_inserted} "
                    "updated={last_updated} success={last_run_ok} error={last_error}".format(
                        last_seen=result.get("last_seen", 0),
                        last_inserted=result.get("last_inserted", 0),
                        last_updated=result.get("last_updated", 0),
                        last_run_ok=result.get("last_run_ok"),
                        last_error=result.get("last_error"),
                    ),
                    flush=True,
                )
            except BaseException as exc:
                _record_worker_error(exc)
                print(
                    f"[CATALOG-SYNC] worker error: {type(exc).__name__}: {exc}",
                    flush=True,
                )
            wake.wait(interval)
            wake.clear()

    thread = threading.Thread(
        target=worker, name="movia-catalog-sync-v2", daemon=True
    )
    try:
        thread.start()
    except Exception as exc:
        with _WORKER_STATE_LOCK:
            _WORKER_STARTED = False
            _WORKER_THREAD = None
        _record_worker_error(exc)
        raise
    with _WORKER_STATE_LOCK:
        _WORKER_THREAD = thread
