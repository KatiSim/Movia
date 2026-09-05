#!/usr/bin/env python3
"""Integration boundary for the legacy Zona source protocol.

The adapter is intentionally contract-driven: metadata suggestion, video
source references, and exact extractor stream resolution are separate steps.
No title-based URL templates or synthetic direct streams are accepted.
"""

import json
import logging
import sqlite3
import sys
import threading
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any, Dict, List, Optional

import requests

from stream_validation import is_test_stream_url, sanitize_streams
from zona_contract import resolve_zona_for_title, resolve_zona_source_refs

DIR = Path(__file__).resolve().parent
DB_PATH = DIR / "catalog.db"
LOG_DIR = DIR / "logs"
LOG_DIR.mkdir(parents=True, exist_ok=True)
LOG_FILE = LOG_DIR / "balancer_integration.log"

logger = logging.getLogger("balancer_integration")
logger.setLevel(logging.DEBUG)
if not logger.handlers:
    rfh = RotatingFileHandler(
        LOG_FILE, maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8"
    )
    rfh.setFormatter(logging.Formatter("[%(levelname)s] %(message)s"))
    logger.addHandler(rfh)
    sh = logging.StreamHandler(sys.stdout)
    sh.setFormatter(logging.Formatter("[%(levelname)s] %(message)s"))
    logger.addHandler(sh)

_RESOLUTION_DIAGNOSTICS = threading.local()


def _set_resolution_diagnostics(status: str, error_count: int = 0) -> None:
    """Keep provider outcome metadata local to the current worker thread."""
    _RESOLUTION_DIAGNOSTICS.status = str(status or "NO_RESULTS")
    try:
        _RESOLUTION_DIAGNOSTICS.error_count = max(0, int(error_count))
    except (TypeError, ValueError):
        _RESOLUTION_DIAGNOSTICS.error_count = 0


def get_last_resolution_diagnostics() -> Dict[str, Any]:
    """Return the last provider outcome without exposing provider payloads."""
    return {
        "status": getattr(_RESOLUTION_DIAGNOSTICS, "status", "UNKNOWN"),
        "error_count": getattr(_RESOLUTION_DIAGNOSTICS, "error_count", 0),
    }


def normalize_voice_name(voice: Optional[str]) -> str:
    if not voice:
        return "Не указано"
    v = str(voice).strip()
    if not v:
        return "Не указано"
    low = v.lower()
    if "lostfilm" in low or "лостфильм" in low or "lost film" in low:
        return "LostFilm"
    elif "red head sound" in low or "redhead" in low or "rhs" in low:
        return "Red Head Sound"
    elif "hdrezka" in low or "rezka" in low or "хдрезка" in low:
        return "HDRezka"
    elif "пифагор" in low or "pythagor" in low:
        return "Пифагор (Дубляж)"
    elif "newstudio" in low or "ньюстудио" in low:
        return "NewStudio"
    elif "alexfilm" in low or "алексфильм" in low:
        return "AlexFilm"
    elif "tvshows" in low or "твшоус" in low:
        return "TVShows"
    elif "le-vitation" in low or "levitation" in low or "левитейшн" in low:
        return "LE-Vitation"
    elif "сыендук" in low or "syenduk" in low:
        return "Сыендук"
    elif "кравец" in low or "kravec" in low or "kravets" in low:
        return "Кравец"
    elif "2x2" in low or "2х2" in low:
        return "2x2"
    elif "чистый звук" in low or "line audio" in low or "line" in low:
        return "Чистый звук (Line)"
    elif "дубляж" in low or "полное дублирование" in low or "русский дубляж" in low:
        return "Дубляж"
    elif "многоголос" in low or "профессиональн" in low or "мво" in low or "mvo" in low:
        return "Профессиональный (МВО)"
    elif "двухголос" in low or "дво" in low or "dvo" in low:
        return "Двухголосый (ДВО)"
    elif "одноголос" in low or "авторск" in low:
        return "Авторский (Одноголосый)"
    elif "оригинал" in low or "original" in low or "english" in low:
        return "Original (с субтитрами)"
    return v

def _safe_int(value: Any, default: int = 0) -> int:
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return default


def query_zona_api(
    title: str,
    year: Optional[int] = None,
    season: Optional[int] = None,
    episode: Optional[int] = None,
    allow_torrent_fallback: bool = True,
    expected_titles: Optional[List[str]] = None,
    media_type: Optional[str] = None,
    kinopoisk_id: Optional[int] = None,
    zona_sources: Optional[List[Dict[str, Any]]] = None,
    allow_zona_content_lookup: bool = False,
    force_refresh: bool = False,
) -> List[Dict[str, Any]]:
    """Resolve real Zona streams through its metadata/source contract.

    The legacy search shortcut and guessed URL templates are not used.
    A stream is accepted only after the provider returns a source key and the
    exact extractor endpoint returns a real URL; all output is sanitized before
    it can reach cache, DB, or API.
    """
    clean_title = str(title or "").strip()
    if not clean_title:
        _set_resolution_diagnostics("NO_RESULTS", 0)
        return []

    expected = list(dict.fromkeys(
        str(value).strip()
        for value in (expected_titles or [clean_title])
        if str(value).strip()
    ))
    streams: List[Dict[str, Any]] = []
    lookup_status = "NO_RESULTS"
    lookup_error_count = 0

    if zona_sources:
        lookup = resolve_zona_source_refs(
            provider_id=kinopoisk_id,
            sources=zona_sources,
            season=season,
            episode=episode,
            force_refresh=force_refresh,
            canonical_title=clean_title,
            canonical_original_title=next(
                (value for value in expected if value.casefold() != clean_title.casefold()),
                None,
            ),
            canonical_year=year,
            canonical_media_type=media_type,
        )
        streams.extend(lookup.streams)
        logger.info(
            "[Zona contract] source refs status=%s refs=%s streams=%s",
            lookup.status, lookup.source_refs, len(lookup.streams),
        )
        lookup_status = str(lookup.status or "NO_RESULTS")
        lookup_error_count += len(lookup.errors)
    elif allow_zona_content_lookup:
        lookup = resolve_zona_for_title(
            title=clean_title,
            expected_titles=expected,
            year=year,
            media_type=media_type,
            season=season,
            episode=episode,
            force_refresh=force_refresh,
        )
        streams.extend(lookup.streams)
        logger.info(
            "[Zona contract] title lookup status=%s suggestions=%s refs=%s streams=%s errors=%s",
            lookup.status, lookup.suggestions, lookup.source_refs,
            len(lookup.streams), len(lookup.errors),
        )
        lookup_status = str(lookup.status or "NO_RESULTS")
        lookup_error_count += len(lookup.errors)

    # Normalize provider voice labels once at the boundary, while keeping
    # every returned audio/quality variant distinct.
    for stream in streams:
        if stream.get("voice") is not None:
            stream["voice"] = normalize_voice_name(stream.get("voice"))
        elif stream.get("translation") is not None:
            stream["voice"] = normalize_voice_name(stream.get("translation"))

    validated_streams = sanitize_streams(streams, require_source=True)

    # Torrent discovery remains a separate fallback branch. It is used only
    # when no direct contract stream survived validation.
    if not validated_streams and allow_torrent_fallback:
        try:
            from torrent_resolver import resolve_torrents_for_query
            category = "tv_series" if season is not None else "movies"
            torrent_streams = resolve_torrents_for_query(
                title=clean_title,
                year=year or 0,
                category=category,
                season=season,
                episode=episode,
            )
            for candidate in torrent_streams:
                if is_test_stream_url(candidate.get("url")):
                    continue
                fallback = dict(candidate)
                fallback["source"] = str(fallback.get("source") or "torrent_fallback")
                fallback["provider"] = str(fallback.get("provider") or "torrent_fallback")
                fallback["voice"] = normalize_voice_name(
                    fallback.get("voice") or "Не указано"
                )
                fallback["quality"] = fallback.get("quality") or "Не указано"
                fallback["seeders"] = _safe_int(fallback.get("seeders", 100), 100)
                fallback["url"] = str(fallback.get("url") or "").strip()
                if season is not None:
                    fallback.setdefault("season", season)
                if episode is not None:
                    fallback.setdefault("episode", episode)
                if fallback["url"]:
                    streams.append(fallback)
        except Exception as exc:
            logger.debug("[query_zona_api] torrent fallback error: %s", exc)
            lookup_status = "PROVIDER_ERROR"
            lookup_error_count += 1

    final_streams = sanitize_streams(streams, require_source=True)
    if final_streams:
        final_status = "OK"
    else:
        final_status = lookup_status if lookup_status != "OK" else "NO_RESULTS"
    if final_status not in {
        "OK", "NO_RESULTS", "NETWORK_ERROR", "PROVIDER_TIMEOUT",
        "RATE_LIMIT", "INVALID_RESPONSE", "DB_ERROR", "PROVIDER_ERROR",
        "AMBIGUOUS",
    }:
        final_status = "NO_RESULTS"
    _set_resolution_diagnostics(final_status, lookup_error_count)
    return final_streams

def query_open_balancer_stream(
    title: str,
    tmdb_id: int = 0,
    year: int = 2024,
    season: Optional[int] = None,
    episode: Optional[int] = None,
    kinopoisk_id: Optional[int] = None,
    check_archive_remote: bool = False,
    allow_torrent_fallback: bool = True,
    expected_titles: Optional[List[str]] = None,
    media_type: Optional[str] = None,
    zona_sources: Optional[List[Dict[str, Any]]] = None,
    allow_zona_content_lookup: bool = False,
    force_refresh: bool = False,
) -> List[Dict[str, Any]]:
    """Return direct clean-room balancer streams, falling back to Zona/torrents if needed."""
    try:
        from collaps_provider import resolve_collaps
        collaps_streams = resolve_collaps(
            title=title,
            year=year,
            tmdb_id=tmdb_id,
            season=season,
            episode=episode,
            media_type=media_type,
        )
        if collaps_streams:
            logger.info("Collaps resolved %d direct streams for '%s'", len(collaps_streams), title)
            return collaps_streams
    except Exception as exc:
        logger.debug("Collaps balancer error: %s", exc)

    return query_zona_api(
        title=title,
        year=year,
        season=season,
        episode=episode,
        allow_torrent_fallback=allow_torrent_fallback,
        expected_titles=expected_titles,
        media_type=media_type,
        kinopoisk_id=kinopoisk_id,
        zona_sources=zona_sources,
        allow_zona_content_lookup=allow_zona_content_lookup,
        force_refresh=force_refresh,
    )

def resolve_balancer(
    title: str,
    year: int = 2024,
    tmdb_id: int = 0,
    season: Optional[int] = None,
    episode: Optional[int] = None,
    expected_titles: Optional[List[str]] = None,
    media_type: Optional[str] = None,
) -> Optional[Dict[str, Any]]:
    """Resolves best direct balancer stream for content_filler dispatcher."""
    try:
        streams = query_open_balancer_stream(
            title=title,
            tmdb_id=tmdb_id,
            year=year,
            season=season,
            episode=episode,
            allow_torrent_fallback=False,
            expected_titles=expected_titles,
            media_type=media_type,
            allow_zona_content_lookup=True,
        )
        diagnostics = get_last_resolution_diagnostics()
        if not streams:
            logger.warning(
                "[Balancer] Нет доступных потоков: status=%s provider_errors=%s title=%s year=%s",
                diagnostics.get("status"),
                diagnostics.get("error_count"),
                title,
                year,
            )
            return None

        best = streams[0]
        logger.info(f"✅ [Balancer] Найден поток для: {title} | {best.get('voice')} {best.get('quality')} -> {best['url']}")
        return {
            "playback_url": best["url"],
            "voice": best.get("voice", "Не указано"),
            "quality": best.get("quality", "Не указано"),
            "seeders": best.get("seeders", 850),
            "streams": streams,
            "link_verified": 1
        }
    except Exception as e:
        _set_resolution_diagnostics("PROVIDER_ERROR", 1)
        logger.error(f"Error in resolve_balancer for {title}: {e}")
        return None

def batch_update_balancer_streams(limit: int = 5000):
    """Enriches catalog database with direct balancer streams."""
    if not DB_PATH.exists():
        logger.error(f"Database {DB_PATH} not found.")
        return

    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()

    rows = cur.execute("SELECT id, title, tmdb_id, year, category, rating, streams FROM movies LIMIT ?;", (limit,)).fetchall()
    logger.info(f"🔍 Обогащение прямыми потоками балансеров ({len(rows)} записей)...")

    updated = 0
    for r in rows:
        m_id = r["id"]
        title = r["title"] or ""
        tmdb_id = r["tmdb_id"] or 0
        year = int(r["year"] or 2024)
        current_streams = []
        try:
            current_streams = json.loads(r["streams"] or "[]")
        except Exception:
            current_streams = []

        direct_balancer_streams = query_open_balancer_stream(title=title, tmdb_id=tmdb_id, year=year)
        torrent_streams = [s for s in current_streams if s.get("url", "").startswith("magnet:")]
        combined = direct_balancer_streams + torrent_streams
        cur.execute("UPDATE movies SET streams = ? WHERE id = ?;", (json.dumps(combined, ensure_ascii=False), m_id))
        updated += 1

    conn.commit()
    conn.close()
    logger.info(f"✅ Успешно обновлено прямыми потоками {updated} тайтлов.")

def fetch_new_releases(limit: int = 50) -> List[Dict[str, Any]]:
    """Fetches latest releases and updates from public balancer feeds."""
    releases = []
    try:
        url = f"https://kodikapi.com/list?limit={min(limit, 100)}"
        r = requests.get(url, timeout=3.0, headers={"User-Agent": "Mozilla/5.0 Movia/0.8.14"})
        if r.status_code == 200:
            data = r.json()
            for item in data.get("results", []):
                t = item.get("title") or item.get("title_orig")
                y = item.get("year", 2024)
                if t:
                    releases.append({"title": t, "year": int(y), "category": item.get("type", "movies")})
    except Exception as e:
        logger.debug(f"fetch_new_releases error: {e}")
    return releases

if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("usage: balancer_integration.py <title> [year]")
    try:
        requested_year = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    except ValueError:
        requested_year = 0
    result = resolve_balancer(sys.argv[1], requested_year)
    print(json.dumps(result, indent=2, ensure_ascii=False))
