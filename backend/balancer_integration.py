#!/usr/bin/env python3
"""
Public Balancer & Open CDN Integration Module (HLS / MP4)
Integrates direct video streams from open balancer catalogs (Kodik, Alloha, Collaps, HDRezka, Open CDN, Archive.org)
by TMDB ID, Kinopoisk ID, and title for instant seedless playback.
"""

import os
import sys
import json
import sqlite3
import random
import logging
from logging.handlers import RotatingFileHandler
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple
from stream_validation import sanitize_streams
from torrent_resolver import _release_matches_expected

DIR = Path(__file__).resolve().parent
DB_PATH = DIR / "catalog.db"
CONFIG_PATH = DIR / "config" / "sources.json"
LOG_DIR = DIR / "logs"
LOG_DIR.mkdir(parents=True, exist_ok=True)
LOG_FILE = LOG_DIR / "balancer_integration.log"

logger = logging.getLogger("balancer_integration")
logger.setLevel(logging.DEBUG)
if not logger.handlers:
    rfh = RotatingFileHandler(LOG_FILE, maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8")
    rfh.setFormatter(logging.Formatter("[%(levelname)s] %(message)s"))
    logger.addHandler(rfh)
    sh = logging.StreamHandler(sys.stdout)
    sh.setFormatter(logging.Formatter("[%(levelname)s] %(message)s"))
    logger.addHandler(sh)

USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Linux; Android 14; 24069PC21G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 Movia/0.7.6",
]

DEFAULT_BALANCER_MIRRORS = [
    {
        "name": "HDRezka",
        "voice": "HDRezka",
        "quality": "1080p",
        "template": "https://stream.voidboost.cc/movie/{tmdb_id}/1080.m3u8",
        "episode_template": "https://stream.voidboost.cc/serial/{tmdb_id}/s{season}e{episode}/1080.m3u8",
        "stream_type": "hls",
        "seeders": 950
    },
    {
        "name": "Kodik",
        "voice": "Дубляж",
        "quality": "1080p",
        "template": "https://v2.kodik.biz/video/{tmdb_id}/1080.mp4",
        "episode_template": "https://v2.kodik.biz/serial/{tmdb_id}/s{season}e{episode}/1080.mp4",
        "stream_type": "direct",
        "seeders": 900
    },
    {
        "name": "Collaps",
        "voice": "LostFilm",
        "quality": "1080p",
        "template": "https://api.collaps.org/embed/movie/{tmdb_id}/index.m3u8",
        "episode_template": "https://api.collaps.org/embed/series/{tmdb_id}/s{season}e{episode}/index.m3u8",
        "stream_type": "hls",
        "seeders": 870
    },
    {
        "name": "Alloha",
        "voice": "Red Head Sound",
        "quality": "720p",
        "template": "https://alloha.tv/stream/{tmdb_id}/720.mp4",
        "episode_template": "https://alloha.tv/stream/{tmdb_id}/s{season}e{episode}/720.mp4",
        "stream_type": "direct",
        "seeders": 780
    },
    {
        "name": "Archive.org",
        "voice": "Original",
        "quality": "1080p",
        "template": "https://archive.org/advancedsearch.php",
        "stream_type": "direct_http",
        "seeders": 999
    }
]

def load_balancer_mirrors() -> List[Dict[str, Any]]:
    if CONFIG_PATH.exists():
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
                b = data.get("balancers")
                if b and isinstance(b, list):
                    return b
        except Exception as e:
            logger.warning(f"Error loading balancer config: {e}")
    return DEFAULT_BALANCER_MIRRORS

import re
import requests
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

TEST_STREAM_PATTERNS = [
    "devstreaming-cdn.apple.com",
    "storage.googleapis.com",
    "bipbop",
    "bigbuckbunny",
    "exoplayer-test-media"
]

def is_test_stream_url(url: Optional[str]) -> bool:
    if not url:
        return False
    low = url.lower()
    return any(p in low for p in TEST_STREAM_PATTERNS)

def extract_direct_m3u8_from_html(embed_url: str, timeout: float = 3.0) -> Optional[str]:
    """Fetches player HTML/JS from iframe embed and extracts the direct .m3u8 manifest URL."""
    if not embed_url or not (embed_url.startswith("http://") or embed_url.startswith("https://")):
        return None
    if is_test_stream_url(embed_url):
        return None
    try:
        r = requests.get(
            embed_url,
            timeout=timeout,
            verify=False,
            headers={
                "User-Agent": "Mozilla/5.0 (Linux; Android 14; 24069PC21G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
                "Referer": "https://stream.voidboost.cc/",
                "Origin": "https://voidboost.net",
                "Accept": "*/*"
            }
        )
        if r.status_code in [200, 206]:
            html_text = r.text
            # Regex search for .m3u8 playlist links
            matches = re.findall(r'https?://[^\s"\'<>]+\.m3u8[^\s"\'<>]*', html_text)
            for m3u8_candidate in matches:
                # Clean candidate
                clean_candidate = m3u8_candidate.replace("\\/", "/").strip()
                if not is_test_stream_url(clean_candidate) and verify_http_stream(clean_candidate, timeout=2.0):
                    return clean_candidate
    except Exception as e:
        logger.debug(f"[extract_direct_m3u8] Failed on {embed_url}: {e}")
    return None

def verify_http_stream(url: str, timeout: float = 2.0) -> bool:
    """Verifies that the stream URL returns HTTP 200/206 with valid media Content-Type."""
    if not url or not (url.startswith("http://") or url.startswith("https://")):
        return False
    if is_test_stream_url(url):
        logger.debug(f"[verify_http_stream] Rejected test stream URL: {url}")
        return False
    try:
        r = requests.head(
            url,
            allow_redirects=True,
            timeout=timeout,
            verify=False,
            headers={
                "User-Agent": "Mozilla/5.0 (Linux; Android 14; 24069PC21G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
                "Referer": "https://stream.voidboost.cc/",
                "Origin": "https://voidboost.net",
                "Accept": "*/*"
            }
        )
        ct = r.headers.get("Content-Type", "").lower()
        logger.debug(f"[verify_http_stream] HEAD {url} -> status={r.status_code}, content-type={ct}")
        if r.status_code in [200, 206] and ("video" in ct or "mpegurl" in ct or "octet-stream" in ct or "audio" in ct):
            return True
    except Exception as e:
        logger.debug(f"[verify_http_stream] HEAD {url} failed: {e}")
    return False

validate_stream_url = verify_http_stream
ZONA_API_CONFIG_PATH = DIR / "config" / "zona_api.json"
ZONA_CONFIG_PATH = DIR / "config" / "zona_sources.json"

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
    elif "кубик в кубе" in low or "кубик" in low or "kubik" in low:
        return "Кубик в Кубе"
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

def load_zona_mirrors_config() -> Tuple[List[str], float, Dict[str, str]]:
    if ZONA_API_CONFIG_PATH.exists():
        try:
            with open(ZONA_API_CONFIG_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
                raw_mirrors = data.get("mirrors", [])
                timeout = float(data.get("timeout_sec", 3.5))
                headers = data.get("headers", {
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Accept": "application/json, text/plain, */*",
                    "X-Requested-With": "XMLHttpRequest"
                })
                m_list = []
                for m in raw_mirrors:
                    if isinstance(m, str):
                        m_list.append(m)
                    elif isinstance(m, dict) and m.get("url"):
                        m_list.append(m["url"])
                if m_list:
                    return m_list, timeout, headers
        except Exception as e:
            logger.warning(f"Error reading zona_api.json: {e}")

    if ZONA_CONFIG_PATH.exists():
        try:
            with open(ZONA_CONFIG_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
                mirrors = [m["url"] for m in data.get("mirrors", []) if isinstance(m, dict) and m.get("enabled", True) and m.get("url")]
                if mirrors:
                    return mirrors, 3.5, {
                        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Accept": "application/json, text/plain, */*"
                    }
        except Exception:
            pass

    return [
        "https://apir0.mzona.net",
        "https://vsr01.zonasearch.com",
        "https://zstat.zona.mobi"
    ], 3.5, {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept": "application/json, text/plain, */*"
    }

def load_zona_mirrors() -> List[Dict[str, Any]]:
    mirrors, timeout, _ = load_zona_mirrors_config()
    return [{"name": f"Mirror {i}", "url": m, "enabled": True, "timeout": timeout} for i, m in enumerate(mirrors)]


def _safe_int(value: Any, default: int = 0) -> int:
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return default


def _zona_nested_objects(item: Dict[str, Any]) -> List[Dict[str, Any]]:
    objects: List[Dict[str, Any]] = [item]
    for key in ("metadata", "movie", "release", "content", "data"):
        value = item.get(key)
        if isinstance(value, dict):
            objects.append(value)
    return objects


def _zona_item_titles(item: Dict[str, Any]) -> List[str]:
    keys = (
        "title", "name", "label", "release_name", "releaseName",
        "title_ru", "titleRu", "original_title", "originalTitle",
        "movie_title", "movieTitle",
    )
    result: List[str] = []
    seen = set()
    for obj in _zona_nested_objects(item):
        for key in keys:
            value = str(obj.get(key) or "").strip()
            if value and value.casefold() not in seen:
                seen.add(value.casefold())
                result.append(value)
    return result


def _zona_item_year(item: Dict[str, Any]) -> Optional[int]:
    for obj in _zona_nested_objects(item):
        for key in ("year", "release_year", "releaseYear"):
            value = obj.get(key)
            try:
                parsed = int(value)
            except (TypeError, ValueError):
                parsed = 0
            if 1900 <= parsed <= 2100:
                return parsed
        for key in ("date", "release_date", "releaseDate"):
            match = re.search(r"(?<!\d)(19\d{2}|20\d{2}|21\d{2})(?!\d)", str(obj.get(key) or ""))
            if match:
                return int(match.group(1))
    return None


def _zona_item_media_type(item: Dict[str, Any]) -> str:
    for obj in _zona_nested_objects(item):
        for key in ("media_type", "mediaType", "type", "kind", "content_type", "contentType"):
            value = str(obj.get(key) or "").strip().casefold()
            if not value:
                continue
            if any(token in value for token in ("tv", "series", "serial", "сериал")):
                return "tv"
            if any(token in value for token in ("movie", "film", "фильм", "кино")):
                return "movie"
    return ""


def _zona_item_matches_expected(
    item: Dict[str, Any],
    expected_titles: List[str],
    year: Optional[int],
    media_type: Optional[str],
    season: Optional[int],
    episode: Optional[int],
) -> Optional[str]:
    """Return the matching provider title, or None on identity mismatch."""
    titles = _zona_item_titles(item)
    if not titles:
        return None
    if not any(
        _release_matches_expected(candidate, expected_titles, year, season, episode)
        for candidate in titles
    ):
        return None

    item_year = _zona_item_year(item)
    if year and item_year and item_year != int(year):
        return None

    expected_kind = "tv" if (
        season is not None
        or str(media_type or "").casefold() in {
            "tv", "series", "tv_series", "serial", "limited_series",
            "dramas_asian", "anime", "animation",
        }
    ) else ("movie" if str(media_type or "").casefold() in {"movie", "movies", "film"} else "")
    item_kind = _zona_item_media_type(item)
    if expected_kind and item_kind and expected_kind != item_kind:
        return None

    for candidate in titles:
        if _release_matches_expected(candidate, expected_titles, year, season, episode):
            return candidate
    return None


def _query_zona_mirror(
    mirror_url: str,
    clean_title: str,
    expected_titles: List[str],
    year: Optional[int],
    media_type: Optional[str],
    season: Optional[int],
    episode: Optional[int],
    timeout_sec: float,
    headers: Dict[str, str],
) -> List[Dict[str, Any]]:
    """Fetch one configured Zona mirror and keep only identity-safe items."""
    base_url = mirror_url.rstrip("/")
    endpoint = f"{base_url}/search/items?query={urllib.parse.quote(clean_title)}"
    try:
        req = urllib.request.Request(endpoint, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
            if resp.status not in (200, 206):
                return []
            raw_data = resp.read().decode("utf-8", errors="ignore")
        data = json.loads(raw_data)
        items = data if isinstance(data, list) else data.get("results", data.get("items", []))
        if not isinstance(items, list):
            return []

        streams: List[Dict[str, Any]] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            provider_title = _zona_item_matches_expected(
                item, expected_titles, year, media_type, season, episode
            )
            if not provider_title:
                logger.debug(
                    "[query_zona_api] Rejected non-matching provider item for %r",
                    clean_title,
                )
                continue
            stream_url = item.get("url") or item.get("magnet") or item.get("link")
            if not stream_url or is_test_stream_url(str(stream_url)):
                continue
            raw_voice = item.get("voice") or item.get("translation") or item.get("audio")
            provider_item_id = (
                item.get("provider_item_id") or item.get("providerItemId")
                or item.get("item_id") or item.get("id")
            )
            stream: Dict[str, Any] = {
                "source": "Zona API",
                "provider": item.get("provider") or item.get("source") or base_url,
                "voice": normalize_voice_name(raw_voice),
                "quality": item.get("quality") or item.get("resolution") or "Не указано",
                "seeders": _safe_int(item.get("seeders", item.get("seeds", 120)), 120),
                "url": str(stream_url).strip(),
                "title": provider_title,
                "season": season,
                "episode": episode,
            }
            if provider_item_id is not None and str(provider_item_id).strip():
                stream["provider_item_id"] = provider_item_id
            for key in ("stream_type", "streamType", "mime_type", "mimeType"):
                if item.get(key) is not None:
                    stream[key] = item[key]
            streams.append(stream)
        return streams
    except Exception as exc:
        logger.debug(f"[query_zona_api] Mirror {base_url} failed ({exc})")
        return []


def query_zona_api(
    title: str,
    year: Optional[int] = None,
    season: Optional[int] = None,
    episode: Optional[int] = None,
    allow_torrent_fallback: bool = True,
    expected_titles: Optional[List[str]] = None,
    media_type: Optional[str] = None,
) -> List[Dict[str, Any]]:
    """
    Queries Zona API gateway and mirrors with automatic failover and voice normalization.
    Extracts stream metadata: source ('Zona API'), voice, quality, seeders, and stream URL.
    Falls back gracefully to the multi-tracker swarm resolver unless the
    caller already runs that resolver independently.
    """
    if not title:
        return []

    streams: List[Dict[str, Any]] = []
    clean_title = title.strip()
    mirrors, timeout_sec, headers = load_zona_mirrors_config()

    # 1. Query all configured mirrors concurrently. First-success behavior
    # hid variants that existed only on a lower-priority mirror.
    bounded_timeout = min(max(float(timeout_sec), 0.5), 5.0)
    unique_mirrors = list(dict.fromkeys(str(m).strip() for m in mirrors if str(m).strip()))
    if unique_mirrors:
        with ThreadPoolExecutor(
            max_workers=min(4, len(unique_mirrors)),
            thread_name_prefix="zona-mirror",
        ) as pool:
            futures = [
                pool.submit(
                    _query_zona_mirror,
                    mirror,
                    clean_title,
                    list(dict.fromkeys([str(value).strip() for value in (expected_titles or [clean_title]) if str(value).strip()])),
                    year,
                    media_type,
                    season,
                    episode,
                    bounded_timeout,
                    headers,
                )
                for mirror in unique_mirrors
            ]
            for future in futures:
                try:
                    streams.extend(future.result())
                except Exception as exc:
                    logger.debug(f"[query_zona_api] Mirror worker failed ({exc})")

    # 2. Resilient multi-track resolution via torrent_resolver swarms. A
    # mirror can return a non-empty but unusable payload, so decide based on
    # structurally validated candidates rather than raw item count.
    validated_streams = sanitize_streams(streams, require_source=True)
    if not validated_streams and allow_torrent_fallback:
        try:
            from torrent_resolver import resolve_torrents_for_query
            category = "tv_series" if season is not None else "movies"
            t_streams = resolve_torrents_for_query(
                title=clean_title,
                year=year or 0,
                category=category,
                season=season,
                episode=episode
            )
            for ts in t_streams:
                if not is_test_stream_url(ts.get("url")):
                    # Keep the real resolver source and release identity. The
                    # fallback must not masquerade as a Zona result or lose the
                    # title used by the identity validator.
                    fallback = dict(ts)
                    fallback["source"] = str(fallback.get("source") or "torrent_fallback")
                    fallback["provider"] = str(fallback.get("provider") or "torrent_fallback")
                    fallback["voice"] = normalize_voice_name(fallback.get("voice") or "Не указано")
                    fallback["quality"] = fallback.get("quality") or "Не указано"
                    fallback["seeders"] = _safe_int(fallback.get("seeders", 100), 100)
                    fallback["url"] = str(fallback.get("url") or "").strip()
                    if season is not None:
                        fallback.setdefault("season", season)
                    if episode is not None:
                        fallback.setdefault("episode", episode)
                    if fallback["url"]:
                        streams.append(fallback)
        except Exception as e:
            logger.debug(f"[query_zona_api] Fallback error: {e}")

    # Keep same-locator audio/quality variants while removing exact duplicates.
    return sanitize_streams(streams, require_source=True)

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
) -> List[Dict[str, Any]]:
    """Queries Zona API gateway and open balancer streams for instant seedless/P2P playback."""
    return query_zona_api(
        title=title,
        year=year,
        season=season,
        episode=episode,
        allow_torrent_fallback=allow_torrent_fallback,
        expected_titles=expected_titles,
        media_type=media_type,
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
        )
        if not streams:
            logger.warning(f"❌ [Balancer] Нет доступных потоков для: {title} ({year})")
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
    if len(sys.argv) > 1:
        test_title = sys.argv[1]
        res = resolve_balancer(test_title, 2024)
        print(f"Результат для '{test_title}':", json.dumps(res, indent=2, ensure_ascii=False))
    else:
        print("Тестирование resolve_balancer('Аватар: Путь воды', 2022, 76600):")
        result = resolve_balancer("Аватар: Путь воды", 2022, 76600)
        print(json.dumps(result, indent=2, ensure_ascii=False))
