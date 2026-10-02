#!/usr/bin/env python3
"""Clean-room direct balancer adapter for Collaps.

Extracts genuine adaptive HLS master playlists and audio tracks (Дубляж,
LostFilm, Кубик в Кубе, Goblin, etc.) without relying on closed proprietary
signatures or failing gateways.
"""

from __future__ import annotations
from contextlib import closing
from movia_paths import DATA_DIR

import json
import logging
import re
import sqlite3
import threading
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger("collaps_provider")

DIR = DATA_DIR
_COLLAPS_DIAGNOSTICS = threading.local()


def _set_collaps_diagnostics(status: str, error_count: int = 0) -> None:
    _COLLAPS_DIAGNOSTICS.status = str(status or "NO_RESULTS")
    try:
        _COLLAPS_DIAGNOSTICS.error_count = max(0, int(error_count))
    except (TypeError, ValueError):
        _COLLAPS_DIAGNOSTICS.error_count = 0


def get_last_collaps_diagnostics() -> Dict[str, Any]:
    return {
        "status": getattr(_COLLAPS_DIAGNOSTICS, "status", "UNKNOWN"),
        "error_count": getattr(_COLLAPS_DIAGNOSTICS, "error_count", 0),
    }

MIRRORS = [
    "https://api.delivembd.ws",
    "https://api.bhcesh.me",
    "https://api.apicollaps.cc",
]

DEFAULT_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
}

VOICE_MAP = {
    "рус. дублированный": "Дубляж",
    "дублированный": "Дубляж",
    "дубляж": "Дубляж",
    "кубик в кубе": "Кубик в Кубе",
    "lostfilm": "LostFilm",
    "лостфильм": "LostFilm",
    "дмитрий \"goblin\" пучков": "Гоблин (Пучков)",
    "goblin": "Гоблин (Пучков)",
    "рус. люб. многоголосый": "Многоголосый",
    "многоголосый": "Многоголосый",
    "eng.original": "Original (English)",
    "original": "Original",
}


def normalize_collaps_voice(raw_voice: str) -> str:
    cleaned = str(raw_voice or "").strip()
    low = cleaned.casefold()
    for pattern, normalized in VOICE_MAP.items():
        if pattern in low:
            return normalized
    return cleaned if cleaned else "Не указано"


def get_imdb_id_from_db(title: str, year: int = 0, tmdb_id: int = 0) -> Optional[str]:
    db_file = DIR / "catalog.db"
    if not db_file.exists():
        return None
    try:
        with closing(sqlite3.connect(str(db_file), timeout=2.0)) as conn, conn:
            c = conn.cursor()
            if tmdb_id and tmdb_id > 0:
                c.execute(
                    "SELECT imdb_id FROM movies WHERE tmdb_id = ? AND imdb_id IS NOT NULL AND length(imdb_id) > 3 LIMIT 1;",
                    (tmdb_id,),
                )
                row = c.fetchone()
                if row and row[0]:
                    return str(row[0]).strip()

            if title:
                c.execute(
                    "SELECT imdb_id FROM movies WHERE title = ? AND imdb_id IS NOT NULL AND length(imdb_id) > 3 LIMIT 1;",
                    (title.strip(),),
                )
                row = c.fetchone()
                if row and row[0]:
                    return str(row[0]).strip()

                if year and year > 0:
                    c.execute(
                        "SELECT imdb_id FROM movies WHERE (title LIKE ? OR original_title LIKE ?) AND year BETWEEN ? AND ? AND imdb_id IS NOT NULL AND length(imdb_id) > 3 LIMIT 1;",
                        (f"%{title.strip()}%", f"%{title.strip()}%", year - 1, year + 1),
                    )
                    row = c.fetchone()
                    if row and row[0]:
                        return str(row[0]).strip()
    except Exception as exc:
        logger.debug("DB lookup error for imdb_id: %s", exc)
    return None


def fetch_imdb_id_from_tmdb(title: str, year: int = 0, tmdb_id: int = 0, is_tv: bool = False) -> Optional[str]:
    try:
        from tmdb_client import TMDbClient
        client = TMDbClient()
        if not tmdb_id and title:
            search_type = "/search/tv" if is_tv else "/search/movie"
            params: Dict[str, Any] = {"query": title}
            if year and year > 0:
                if not is_tv:
                    params["year"] = year
                else:
                    params["first_air_date_year"] = year
            res = client._get(search_type, params)
            results = (res or {}).get("results", [])
            if results and isinstance(results[0], dict):
                tmdb_id = int(results[0].get("id") or 0)

        if tmdb_id and tmdb_id > 0:
            details_type = f"/tv/{tmdb_id}/external_ids" if is_tv else f"/movie/{tmdb_id}/external_ids"
            ext = client._get(details_type)
            if ext and ext.get("imdb_id"):
                return str(ext["imdb_id"]).strip()
    except Exception as exc:
        logger.debug("TMDB external_ids error: %s", exc)
    return None


def _audio_display_labels(audio_names: List[Any]) -> List[str]:
    """Keep provider labels losslessly; identical labels need ordinal choices."""
    raw = [str(name or "").strip() or "Не указано" for name in audio_names]
    counts: Dict[str, int] = {}
    for label in raw:
        counts[label.casefold()] = counts.get(label.casefold(), 0) + 1
    return [
        f"{label} · дорожка {index + 1}" if counts[label.casefold()] > 1 else label
        for index, label in enumerate(raw)
    ]


def _embedded_json(html: str, key: str):
    match = re.search(r"(?<![\w])(?:[\"']?" + re.escape(key) + r"[\"']?)\s*:\s*", html)
    if not match:
        return None
    try:
        value, _ = json.JSONDecoder().raw_decode(html[match.end():])
        return value
    except (ValueError, TypeError):
        return None


def _positive_number(value):
    if isinstance(value, bool):
        return None
    text = str(value or "").strip()
    return int(text) if text.isdecimal() and int(text) > 0 else None


def _collaps_rows(payload, mirror, imdb_id, season=None, episode=None):
    if not isinstance(payload, dict):
        return []
    url = payload.get("hls")
    if not isinstance(url, str):
        return []
    try:
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            return []
    except ValueError:
        return []
    audio = payload.get("audio")
    names = audio.get("names") if isinstance(audio, dict) else None
    names = names if isinstance(names, list) and names else None
    if names is not None:
        names = [name.strip() if isinstance(name, str) else "" for name in names]
    labels = _audio_display_labels(names) if names is not None else ["Не указано"]
    subtitles = payload.get("cc")
    subtitles = subtitles if isinstance(subtitles, list) else []
    rows = []
    for index, label in enumerate(labels):
        scope = f"_s{season}e{episode}" if season is not None else ""
        ordinal = f"_a{index}" if names is not None else "_auto"
        stream_id = f"collaps_{imdb_id}{scope}{ordinal}_{urllib.parse.quote(label, safe='')}"
        row = {"stream_id": stream_id, "streamId": stream_id, "source": "Collaps",
               "provider": "collaps", "source_type_id": 9, "voice": label,
               "quality": "Auto", "url": url.strip(), "transport": "hls",
               "headers": {"User-Agent": DEFAULT_HEADERS["User-Agent"], "Referer": f"{mirror}/"},
               "subtitles": subtitles}
        if names is not None:
            row.update(audio_track_index=index, source_voice_label=names[index])
        if season is not None:
            row.update(season=season, episode=episode)
        rows.append(row)
    return rows


def parse_collaps_page(
    html: str,
    mirror: str,
    imdb_id: str,
    season: Optional[int] = None,
    episode: Optional[int] = None,
) -> List[Dict[str, Any]]:
    # JSONDecoder respects brackets/quotes inside studio and subtitle names.
    series_marker = re.search(r"(?<![\w])(?:[\"']?seasons[\"']?)\s*:", html)
    if series_marker:
        wanted_season, wanted_episode = _positive_number(season), _positive_number(episode)
        if wanted_season is None or wanted_episode is None:
            return []
        seasons = _embedded_json(html, "seasons")
        if not isinstance(seasons, list):
            return []
        for item in seasons:
            if not isinstance(item, dict) or _positive_number(item.get("season")) != wanted_season:
                continue
            episodes = item.get("episodes")
            if not isinstance(episodes, list):
                continue
            for item_episode in episodes:
                if isinstance(item_episode, dict) and _positive_number(item_episode.get("episode")) == wanted_episode:
                    return _collaps_rows(item_episode, mirror, imdb_id, wanted_season, wanted_episode)
        # Never relabel an unrelated episode or nested movie URL as the requested one.
        return []
    if season is not None or episode is not None:
        return []
    match = re.search(r"(?<![\w])(?:[\"']?hls[\"']?)\s*:\s*[\"'](https?://[^\"']+)[\"']", html)
    if not match:
        return []
    return _collaps_rows({"hls": match.group(1), "audio": _embedded_json(html, "audio"),
                          "cc": _embedded_json(html, "cc")}, mirror, imdb_id)


def resolve_collaps(
    title: str,
    year: int = 0,
    tmdb_id: int = 0,
    imdb_id: Optional[str] = None,
    season: Optional[int] = None,
    episode: Optional[int] = None,
    media_type: Optional[str] = None,
) -> List[Dict[str, Any]]:
    """Resolves genuine playable HLS streams with audio tracks from Collaps."""
    effective_imdb = str(imdb_id or "").strip()
    is_tv = season is not None or str(media_type).lower() in {"tv", "series"}

    if not effective_imdb:
        effective_imdb = get_imdb_id_from_db(title=title, year=year, tmdb_id=tmdb_id) or ""

    if not effective_imdb:
        effective_imdb = fetch_imdb_id_from_tmdb(title=title, year=year, tmdb_id=tmdb_id, is_tv=is_tv) or ""

    if not effective_imdb:
        _set_collaps_diagnostics("NO_RESULTS", 0)
        return []

    error_count = 0
    for mirror in MIRRORS:
        url = f"{mirror}/embed/imdb/{effective_imdb}"
        try:
            req = urllib.request.Request(url, headers=DEFAULT_HEADERS)
            with urllib.request.urlopen(req, timeout=3.5) as resp:
                if resp.status == 200:
                    html = resp.read().decode("utf-8", errors="replace")
                    streams = parse_collaps_page(
                        html=html,
                        mirror=mirror,
                        imdb_id=effective_imdb,
                        season=season,
                        episode=episode,
                    )
                    if streams:
                        _set_collaps_diagnostics("OK", error_count)
                        return streams
        except Exception as exc:
            error_count += 1
            logger.debug("Collaps mirror %s error: %s", mirror, exc)
            continue

    _set_collaps_diagnostics(
        "PROVIDER_ERROR" if error_count >= len(MIRRORS) else "NO_RESULTS",
        error_count,
    )
    return []


if __name__ == "__main__":
    import sys
    test_title = sys.argv[1] if len(sys.argv) > 1 else "Сплит"
    test_year = int(sys.argv[2]) if len(sys.argv) > 2 else 2017
    print(f"Testing collaps for '{test_title}' ({test_year})...")
    res = resolve_collaps(title=test_title, year=test_year)
    print(f"Found {len(res)} streams:")
    for s in res:
        print(f"  {s.get("voice")} | {s.get("quality")} | {s.get("url")[:60]}")

