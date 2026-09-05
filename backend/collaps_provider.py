#!/usr/bin/env python3
"""Clean-room direct balancer adapter for Collaps.

Extracts genuine adaptive HLS master playlists and audio tracks (Дубляж,
LostFilm, Кубик в Кубе, Goblin, etc.) without relying on closed proprietary
signatures or failing gateways.
"""

from __future__ import annotations

import json
import logging
import re
import sqlite3
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger("collaps_provider")

DIR = Path(__file__).resolve().parent

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
    return cleaned if cleaned else "Дубляж"


def get_imdb_id_from_db(title: str, year: int = 0, tmdb_id: int = 0) -> Optional[str]:
    db_file = DIR / "catalog.db"
    if not db_file.exists():
        return None
    try:
        with sqlite3.connect(str(db_file), timeout=2.0) as conn:
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


def parse_collaps_page(
    html: str,
    mirror: str,
    imdb_id: str,
    season: Optional[int] = None,
    episode: Optional[int] = None,
) -> List[Dict[str, Any]]:
    streams: List[Dict[str, Any]] = []

    # Case 1: TV Series
    idx = html.find("seasons:[")
    if idx != -1:
        start = idx + 8
        depth = 0
        end = start
        for i, ch in enumerate(html[start:], start):
            if ch == "[":
                depth += 1
            elif ch == "]":
                depth -= 1
                if depth == 0:
                    end = i + 1
                    break
        raw_json = html[start:end]
        try:
            seasons_data = json.loads(raw_json)
            target_season = season if (season is not None and season > 0) else 1
            target_episode = episode if (episode is not None and episode > 0) else 1

            matched_ep: Optional[Dict[str, Any]] = None
            for s in seasons_data:
                if s.get("season") == target_season:
                    for ep in s.get("episodes", []):
                        if str(ep.get("episode")) == str(target_episode):
                            matched_ep = ep
                            break
                    if matched_ep:
                        break

            if matched_ep:
                hls_url = matched_ep.get("hls")
                if hls_url and str(hls_url).startswith("http"):
                    audio_names = (matched_ep.get("audio") or {}).get("names") or ["Дубляж"]
                    subtitles = matched_ep.get("cc") or []
                    for audio_index, name in enumerate(audio_names):
                        norm_voice = normalize_collaps_voice(name)
                        stream_id = f"collaps_{imdb_id}_s{target_season}e{target_episode}_{urllib.parse.quote(norm_voice, safe="")}"
                        streams.append({
                            "stream_id": stream_id,
                            "streamId": stream_id,
                            "source": "Collaps",
                            "provider": "collaps",
                            "source_type_id": 9,
                            "voice": norm_voice,
                            "quality": "1080p",
                            "audio_track_index": audio_index,
                            "url": str(hls_url).strip(),
                            "transport": "hls",
                            "headers": {
                                "User-Agent": DEFAULT_HEADERS["User-Agent"],
                                "Referer": f"{mirror}/",
                            },
                            "season": target_season,
                            "episode": target_episode,
                            "subtitles": subtitles,
                        })
                    return streams
        except Exception as exc:
            logger.debug("Collaps seasons parse error: %s", exc)

    # Case 2: Movie
    m_hls = re.search(r"hls:\s*[\"\'](https?://[^\"\']+)[\"\']", html)
    if not m_hls:
        return []

    hls_url = m_hls.group(1).strip()
    audio_names = ["Дубляж"]
    m_audio = re.search(r"audio:\s*({[^}]+})", html)
    if m_audio:
        try:
            adata = json.loads(m_audio.group(1))
            names = adata.get("names", [])
            if names:
                audio_names = names
        except Exception:
            pass

    subtitles = []
    m_cc = re.search(r"cc:\s*(\[[^\]]*\])", html)
    if m_cc:
        try:
            subtitles = json.loads(m_cc.group(1))
        except Exception:
            pass

    for audio_index, name in enumerate(audio_names):
        norm_voice = normalize_collaps_voice(name)
        stream_id = f"collaps_{imdb_id}_{urllib.parse.quote(norm_voice, safe="")}"
        streams.append({
            "stream_id": stream_id,
            "streamId": stream_id,
            "source": "Collaps",
            "provider": "collaps",
            "source_type_id": 9,
            "voice": norm_voice,
            "quality": "1080p",
            "audio_track_index": audio_index,
            "url": hls_url,
            "transport": "hls",
            "headers": {
                "User-Agent": DEFAULT_HEADERS["User-Agent"],
                "Referer": f"{mirror}/",
            },
            "subtitles": subtitles,
        })

    return streams


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
        return []

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
                        return streams
        except Exception as exc:
            logger.debug("Collaps mirror %s error: %s", mirror, exc)
            continue

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
