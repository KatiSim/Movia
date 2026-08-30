
CANONICAL_VOICE_MAP = {
    "кубик в кубе": "Кубик в Кубе",
    "lostfilm": "LostFilm",
    "hdrezka": "HDRezka",
    "red head sound": "Red Head Sound",
    "alexfilm": "AlexFilm",
    "newstudio": "NewStudio",
    "flarrow films": "Flarrow Films",
    "jaskier": "Jaskier",
    "tvshows": "TVShows",
    "кураж-бамбей": "Кураж-Бамбей",
    "le-vitation": "LE-Vitation",
    "дубляж": "Дубляж",
    "профессиональный (мво)": "Профессиональный (МВО)",
    "двухголосый (дво)": "Двухголосый (ДВО)",
    "чистый звук (line)": "Чистый звук (Line)",
    "original (с субтитрами)": "Original (с субтитрами)"
}

def get_canonical_voice(v: str) -> str:
    if not v:
        return "Не указано"
    clean = v.strip().lower()
    return CANONICAL_VOICE_MAP.get(clean, v.strip())
#!/usr/bin/env python3
"""
Automated Multi-Provider Torrent Resolver & Tracker Indexer (Torznab / Apibay / YTS / RuTracker / Nyaa / EZTV)
Queries provider pool in parallel via asyncio, handles failover, ranks by seeds,
filters by quality, and enriches magnet links with live announce trackers (Zona model).
"""

import os
import sys
import json
import sqlite3
import hashlib
import asyncio
import logging
from logging.handlers import RotatingFileHandler
import urllib.parse
import urllib.request
import re
import unicodedata
import xml.etree.ElementTree as ET
from difflib import SequenceMatcher
from pathlib import Path
from typing import List, Dict, Any, Optional
from stream_validation import is_valid_btih, sanitize_streams, stream_variant_key
from catalog_schema_v2 import normalize_ru_text

DIR = Path(__file__).resolve().parent
DB_PATH = DIR / "catalog.db"
CONFIG_PATH = DIR / "config" / "sources.json"
LOG_DIR = DIR / "logs"
LOG_DIR.mkdir(parents=True, exist_ok=True)
LOG_FILE = LOG_DIR / "torrent_resolver.log"

logger = logging.getLogger("torrent_resolver")
logger.setLevel(logging.INFO)
if not logger.handlers:
    rfh = RotatingFileHandler(LOG_FILE, maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8")
    rfh.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s - %(message)s"))
    logger.addHandler(rfh)
    sh = logging.StreamHandler()
    sh.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s - %(message)s"))
    logger.addHandler(sh)

DEFAULT_TRACKERSLIST = [
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.tracker.cl:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "http://tracker.openbittorrent.com:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://tracker.tiny-vps.com:6969/announce",
    "udp://opentracker.i2p.rocks:6969/announce"
]

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

def load_sources_config() -> Dict[str, Any]:
    """Loads sources.json with hot reload support or returns fallback configuration."""
    if CONFIG_PATH.exists():
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception as e:
            logger.warning(f"Failed to read sources.json: {e}")
    return {
        "trackers": [
            {"name": "YTS", "type": "api_json", "endpoint": "https://yts.mx/api/v2/list_movies.json", "enabled": True, "timeout": 3.0},
            {"name": "Apibay", "type": "api_json", "endpoint": "https://apibay.org/q.php", "enabled": True, "timeout": 3.0}
        ],
        "trackerslist": DEFAULT_TRACKERSLIST
    }

def get_live_trackers() -> List[str]:
    cfg = load_sources_config()
    return cfg.get("trackerslist") or DEFAULT_TRACKERSLIST


def _configured_tracker_endpoint(name: str, default: str) -> str:
    """Resolve an enabled endpoint from sources.json without requiring a rebuild."""
    endpoints = _configured_tracker_endpoints(name, [default])
    return endpoints[0] if endpoints else ""


def _configured_tracker_endpoints(name: str, defaults: List[str]) -> List[str]:
    """Return configured enabled mirrors, preserving an explicit source order."""
    try:
        cfg = load_sources_config()
        wanted = str(name or "").strip().casefold()
        for tracker in cfg.get("trackers", []):
            if not isinstance(tracker, dict):
                continue
            if str(tracker.get("name") or "").strip().casefold() == wanted:
                if not tracker.get("enabled", True):
                    return []
                values = tracker.get("mirrors") or [tracker.get("endpoint")]
                return list(dict.fromkeys(
                    str(value).strip()
                    for value in values
                    if str(value or "").strip()
                ))
    except Exception:
        pass
    return list(dict.fromkeys(str(value).strip() for value in defaults if str(value or "").strip()))


def enrich_magnet_with_trackers(magnet: str, trackers: Optional[List[str]] = None) -> str:
    """Enriches magnet URI with live announce trackers (&tr=) for maximum seeder swarm connectivity."""
    if not magnet or not magnet.startswith("magnet:?"):
        return magnet
    tr_list = trackers or get_live_trackers()
    tr_params = "&".join(f"tr={urllib.parse.quote(tr, safe='')}" for tr in tr_list if tr)
    if "tr=" not in magnet and tr_params:
        return f"{magnet}&{tr_params}"
    return magnet

def _normalize_release_text(value: str) -> str:
    # Provider identity checks use the same canonical text contract as catalog
    # indexing: Unicode/case/spacing/dash/punctuation and ё/е are consistent.
    return normalize_ru_text(value)


_RELEASE_METADATA_TOKENS = frozenset({
    "web", "webdl", "web dl", "dl", "webrip", "uhd", "aka", "audio", "bd", "br", "bluray", "brrip", "bdrip", "bdremux",
    "remux", "hdrip", "dvdrip", "hdtv", "tvrip", "rip", "proper", "repack",
    "extended", "unrated", "directors", "cut", "readnfo", "nf", "amzn", "amazon",
    "netflix", "hulu", "disney", "apple", "max", "x264", "x265", "h264", "h265",
    "hevc", "av1", "10bit", "hdr", "dv", "dovi", "aac", "ac3", "ddp", "ddp5",
    "eac3", "dts", "atmos", "mp3", "flac", "rus", "рус", "русский", "english",
    "eng", "original", "sub", "subs", "subtitles", "дубляж", "многоголосый",
    "многоголос", "одноголосый", "авторский", "профессиональный", "lostfilm",
    "hdrezka", "rezka", "newstudio", "rutor", "eztv", "apibay", "yts", "nyaa",
    "zona", "torrent", "mkv", "mp4", "avi", "afg", "megusta", "yify", "rarbg",
    "pahe", "psa", "subsplease", "erai", "raws", "season", "сезон", "episode",
    "серия", "movie", "film", "фильм", "series", "сериал",
})


def _release_token_is_metadata(token: str) -> bool:
    token = str(token or "").casefold()
    if not token:
        return True
    if token in _RELEASE_METADATA_TOKENS:
        return True
    if re.fullmatch(r"\d{3,4}p?", token):
        return True
    if re.fullmatch(r"s\d{1,3}(?:e\d{1,3})?", token):
        return True
    if re.fullmatch(r"e\d{1,3}", token):
        return True
    if token.isdigit() and len(token) <= 4:
        return True
    # One-letter release markers (D/P/L/etc.) are common after a provider
    # quality boundary; they are not semantic title words.
    if len(token) == 1 and token.isalpha():
        return True
    return False


def _strip_leading_release_groups(value: str) -> str:
    # Common indexers prefix a release group in brackets. Remove only leading
    # groups; semantic words elsewhere remain part of identity validation.
    cleaned = str(value or "")
    for _ in range(3):
        updated = re.sub(r"^\s*(?:\[[^\]]{1,100}\]|\([^)]{1,100}\)|\{[^}]{1,100}\})\s*", "", cleaned)
        if updated == cleaned:
            break
        cleaned = updated
    return cleaned


def _release_has_expected_title(release_name: str, expected_title: str) -> bool:
    """Match a complete title within a conventional release-name boundary."""
    cleaned = _strip_leading_release_groups(str(release_name or ""))

    def keep_bracket_metadata(match: re.Match[str]) -> str:
        # Brackets may contain a year/season boundary (e.g. [2003] or [S01]),
        # so preserve their tokens instead of erasing useful identity markers.
        return f" {match.group(1) or match.group(2) or ''} "

    cleaned = re.sub(
        r"\[([^\]]{1,200})\]|\{([^}]{1,200})\}",
        keep_bracket_metadata,
        cleaned,
    )
    # Bilingual provider names commonly use slash/pipe/AKA segments. Restrict
    # slash splitting to spaced separators so titles such as AC/DC remain whole.
    segments = re.split(
        r"\s+/\s+|\s+\|\s+|\s+(?:aka|also\s+known\s+as)\s+|\s+[•·]\s+",
        cleaned,
        flags=re.IGNORECASE,
    )
    expected_tokens = _normalize_release_text(expected_title).split()
    if not expected_tokens:
        return False

    for segment in segments or [cleaned]:
        release_tokens = _normalize_release_text(segment).split()
        if not release_tokens:
            continue
        width = len(expected_tokens)
        for start in range(0, len(release_tokens) - width + 1):
            if release_tokens[start:start + width] != expected_tokens:
                continue
            before = release_tokens[:start]
            after = release_tokens[start + width:]
            # A leading article may be present in one language's release form.
            if not all(
                _release_token_is_metadata(token) or token in {"the", "a", "an"}
                for token in before
            ):
                continue
            if not after:
                return True

            # Once a year/quality/season marker begins, the remaining suffix is
            # provider metadata. Non-metadata words before that boundary are
            # treated as a different title and rejected.
            boundary = next(
                (index for index, token in enumerate(after)
                 if _release_token_is_metadata(token)),
                None,
            )
            if boundary == 0:
                return True
            if boundary is not None and all(
                _release_token_is_metadata(token) for token in after[:boundary]
            ):
                return True
    return False


def _strip_release_noise(value: str) -> str:
    tokens = _normalize_release_text(value).split()
    noise = {
        "1080p", "720p", "2160p", "480p", "1080", "720", "2160", "480", "4k", "uhd",
        "bluray", "bdrip", "bdremux", "webrip", "webdl", "web", "dl", "remux", "hdrip",
        "x264", "x265", "h264", "h265", "hevc", "av1", "hdr", "dv", "dovi", "10bit",
        "aac", "ac3", "ddp", "ddp5", "dts", "atmos", "proper", "repack", "extended",
    }
    return " ".join(t for t in tokens if t not in noise)


def _release_matches_expected(
    release_name: str,
    expected_titles: List[str],
    year: Optional[int],
    season: Optional[int],
    episode: Optional[int],
) -> bool:
    """Conservatively bind a provider release to the requested catalog item.

    A title is accepted only when its complete normalized token sequence is
    surrounded by release metadata. This prevents a shorter title from
    accidentally matching a longer, different work that merely starts with it.
    """

    raw = str(release_name or "")
    if not _normalize_release_text(raw):
        return False

    if year and year > 1900:
        years = [
            int(value)
            for value in re.findall(
                r"(?<!\d)(19\d{2}|20\d{2}|21\d{2})(?!\d)", raw
            )
        ]
        if years and year not in years:
            return False

    low = _normalize_release_text(raw)
    if season is not None:
        season_value = int(season)
        season_patterns = [
            rf"\bs0*{season_value}\b",
            rf"\bseason\s*0*{season_value}\b",
            rf"\bсезон\s*0*{season_value}\b",
        ]
        if episode is not None:
            episode_value = int(episode)
            episode_patterns = [
                rf"\bs0*{season_value}e0*{episode_value}\b",
                rf"\be0*{episode_value}\b",
                rf"\bepisode\s*0*{episode_value}\b",
                rf"\bсерия\s*0*{episode_value}\b",
            ]
            if re.search(r"\bs\d{1,2}e\d{1,3}\b", low) and not any(
                re.search(pattern, low) for pattern in episode_patterns
            ):
                return False
        if re.search(r"\bs\d{1,2}\b", low) and not any(
            re.search(pattern, low) for pattern in season_patterns
        ):
            return False

    for title in expected_titles:
        if title and _release_has_expected_title(raw, title):
            return True
    return False


_RUSSIAN_VOICE_HINTS = (
    "дубляж", "lostfilm", "hdrezka", "red head sound", "кубик", "кураж", "alexfilm",
    "newstudio", "flarrow", "jaskier", "tvshows", "le-vitation", "пифагор", "сыендук", "кравец", "2x2", "2х2",
    "профессиональный", "мво", "двухголосый", "дво", "авторский", "одноголосый",
    "чистый звук",
)


def _stream_rank(stream: Dict[str, Any]) -> tuple:
    voice = str(stream.get("voice") or "").lower()
    source = str(stream.get("source") or "").lower()
    russian_rank = 0 if any(h in voice for h in _RUSSIAN_VOICE_HINTS) else (2 if "original" in voice else 1)
    source_rank = 0 if source == "rutor" else (1 if "zona" in source else (3 if source == "apibay" else 4))
    try:
        seeders = int(stream.get("seeders") or 0)
    except (TypeError, ValueError):
        seeders = 0
    quality = str(stream.get("quality") or "").lower()
    if any(token in quality for token in ("2160", "4k", "uhd")):
        quality_rank = 0
    elif any(token in quality for token in ("1080", "fullhd", "fhd")):
        quality_rank = 1
    elif "720" in quality or quality == "hd":
        quality_rank = 2
    elif "480" in quality or "sd" in quality:
        quality_rank = 3
    elif not quality or quality == "не указано":
        quality_rank = 5
    else:
        quality_rank = 4
    return (
        russian_rank,
        source_rank,
        quality_rank,
        -seeders,
        voice,
        quality,
        str(stream.get("title") or "").strip().casefold(),
        repr(stream_variant_key(stream)),
    )


def classify_voice_and_quality(raw_name: str) -> tuple[str, str]:
    """Smart classification of Russian/Original audio tracks and video resolutions."""
    lower = raw_name.lower()

    # Специфичные студии перевода и озвучки
    if any(k in lower for k in ["red head sound", "rhs", "ред хед", "редхед"]):
        voice = "Red Head Sound"
    elif any(k in lower for k in ["lostfilm", "лостфильм", "лост"]):
        voice = "LostFilm"
    elif any(k in lower for k in ["hdrezka", "rezka", "резка"]):
        voice = "HDRezka"
    elif any(k in lower for k in ["кубик в кубе", "kubik", "кубик"]):
        voice = "Кубик в Кубе"
    elif any(k in lower for k in ["кураж-бамбей", "кураж бамбей", "kuraj"]):
        voice = "Кураж-Бамбей"
    elif any(k in lower for k in ["alexfilm", "алексфильм", "alex film"]):
        voice = "AlexFilm"
    elif any(k in lower for k in ["newstudio", "ньюстудио"]):
        voice = "NewStudio"
    elif any(k in lower for k in ["flarrow films", "flarrow", "флэроу"]):
        voice = "Flarrow Films"
    elif any(k in lower for k in ["jaskier", "яскьер", "джаскьер"]):
        voice = "Jaskier"
    elif any(k in lower for k in ["tvshows", "твшоус"]):
        voice = "TVShows"
    elif any(k in lower for k in ["le-vitation", "levitation", "левитейшн"]):
        voice = "LE-Vitation"
    elif any(k in lower for k in ["пифагор", "pythagor"]):
        voice = "Пифагор (Дубляж)"
    elif any(k in lower for k in ["сыендук", "syenduk", "sыендук"]):
        voice = "Сыендук"
    elif any(k in lower for k in ["кравец", "kravec", "kravets"]):
        voice = "Кравец"
    elif any(k in lower for k in ["2x2", "2х2"]):
        voice = "2x2"
    elif any(k in lower for k in ["чистый звук", "line", "line audio", "звук с ts"]):
        voice = "Чистый звук (Line)"
    elif (
        any(k in lower for k in ["дубляж", "дублированный", "dub", "полное дублирование", "bdrip dub", "web-dl dub"])
        or re.search(r"(?:^|\|)\s*d\s*(?:[,|]|$)", lower)
    ):
        voice = "Дубляж"
    elif any(k in lower for k in ["многоголосый", "профессиональный", "проф.", "мво", "mvo"]):
        voice = "Профессиональный (МВО)"
    elif any(k in lower for k in ["двухголосый", "дво", "dvo"]):
        voice = "Двухголосый (ДВО)"
    elif any(k in lower for k in ["авторский", "одноголосый", "пво", "головин", "сербин", "живов", "пучков", "гоблин", "гаврилов"]):
        voice = "Авторский (Одноголосый)"
    elif any(k in lower for k in ["original", "english", "eng", "оригинал", "субтитры", "sub"]):
        voice = "Original (с субтитрами)"
    else:
        # Do not invent a Russian dub when the release title contains no
        # language/translation marker. Unknown metadata stays unknown.
        voice = "Не указано"

    # Разрешение / Качество
    if any(k in lower for k in ["2160", "4k", "uhd", "ultra hd"]):
        quality = "4K"
    elif any(k in lower for k in ["1080", "1080p", "fullhd", "fhd", "remux", "bdremux"]):
        quality = "1080p"
    elif any(k in lower for k in ["720", "720p", "hdrip"]):
        quality = "720p"
    elif any(k in lower for k in ["480", "480p", "dvd", "dvdrip"]):
        quality = "480p"
    elif any(k in lower for k in ["cam", "camrip", "ts", "telesync"]):
        quality = "TS / CAM"
    else:
        quality = "Не указано"

    return get_canonical_voice(voice), quality

async def fetch_yts_torrents(title: str, year: int = 2024, timeout: float = 2.0) -> List[Dict[str, Any]]:
    """Query configured YTS mirrors concurrently with bounded fan-out."""
    mirrors = _configured_tracker_endpoints(
        "YTS",
        [
            "https://yts.mx/api/v2/list_movies.json",
            "https://yts.lt/api/v2/list_movies.json",
            "https://yts.am/api/v2/list_movies.json",
        ],
    )

    clean_title = title.replace(":", " ").replace("-", " ").strip()
    candidate_terms = [
        f"{title} {year}".strip() if year and year > 1900 else title.strip(),
        title.strip(),
        clean_title
    ]
    unique_terms = list(dict.fromkeys([t for t in candidate_terms if t]))

    bounded_timeout = min(max(float(timeout), 0.5), 5.0)
    loop = asyncio.get_running_loop()

    async def fetch_term(endpoint: str, term: str) -> Optional[Dict[str, Any]]:
        url = f"{endpoint}?query_term={urllib.parse.quote(term)}&limit=10"
        req = urllib.request.Request(url, headers={"User-Agent": "MoviaTorrentResolver/3.0"})

        def _call():
            with urllib.request.urlopen(req, timeout=bounded_timeout) as resp:
                if resp.status == 200:
                    return json.loads(resp.read().decode("utf-8"))
            return None

        try:
            return await loop.run_in_executor(None, _call)
        except Exception as exc:
            logger.debug(f"YTS endpoint {endpoint} error for {term}: {exc}")
            return None

    results = await asyncio.gather(
        *(
            fetch_term(endpoint, term)
            for endpoint in mirrors
            for term in unique_terms[:2]
        ),
        return_exceptions=True,
    )
    streams: List[Dict[str, Any]] = []
    for data in results:
        if not isinstance(data, dict) or data.get("status") != "ok":
            continue
        movies = data.get("data", {}).get("movies", [])
        if not isinstance(movies, list):
            continue
        for movie in movies:
            if not isinstance(movie, dict):
                continue
            movie_title = movie.get("title_long") or movie.get("title") or title
            if not _release_matches_expected(movie_title, [title], year, None, None):
                continue
            torrents = movie.get("torrents", [])
            if not isinstance(torrents, list):
                continue
            for torrent in torrents:
                if not isinstance(torrent, dict):
                    continue
                hash_val = torrent.get("hash")
                raw_q = str(torrent.get("quality", "") or "").strip().lower()
                quality = "4K" if "2160" in raw_q else (
                    "FullHD 1080" if "1080" in raw_q else (
                        "HD 720" if "720" in raw_q else "Не указано"
                    )
                )
                try:
                    seeds = max(0, int(torrent.get("seeds", 100) or 0))
                except (TypeError, ValueError):
                    seeds = 0
                if not hash_val:
                    continue
                raw_magnet = f"magnet:?xt=urn:btih:{hash_val}&dn={urllib.parse.quote(movie_title)}"
                enriched_magnet = enrich_magnet_with_trackers(raw_magnet)
                streams.append({
                    "source": "YTS",
                    "voice": "Original (с субтитрами)",
                    "quality": quality,
                    "seeders": seeds,
                    "url": enriched_magnet,
                    "title": movie_title,
                })
    return streams

async def fetch_eztv_torrents(
    title: str,
    year: int = 2024,
    category: str = "tv_series",
    season: Optional[int] = None,
    episode: Optional[int] = None,
    timeout: float = 4.0,
) -> List[Dict[str, Any]]:
    """Fetch TV releases from the configured EZTV JSON endpoint."""
    if category not in {"tv_series", "series", "dramas_asian", "anime", "limited_series"} and season is None:
        return []

    endpoint = _configured_tracker_endpoint(
        "EZTV", "https://eztvx.to/api/get-torrents"
    )
    params = {
        "keywords": str(title or "").strip(),
        "limit": "100",
        "page": "1",
    }
    if not params["keywords"] or not endpoint:
        return []
    url = f"{endpoint}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "MoviaTorrentResolver/3.0", "Accept": "application/json"},
    )
    loop = asyncio.get_running_loop()

    def _call():
        with urllib.request.urlopen(req, timeout=min(max(float(timeout), 0.5), 5.0)) as resp:
            if resp.status != 200:
                return None
            return json.loads(resp.read().decode("utf-8", errors="ignore"))

    try:
        data = await loop.run_in_executor(None, _call)
    except Exception as exc:
        logger.debug("EZTV endpoint error for %s: %s", title, exc)
        return []

    items = data.get("torrents", []) if isinstance(data, dict) else []
    if not isinstance(items, list):
        return []

    streams: List[Dict[str, Any]] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        release_title = str(item.get("title") or item.get("filename") or "").strip()
        magnet = str(item.get("magnet_url") or "").strip()
        if not release_title or not magnet or is_test_stream_url(magnet):
            continue
        try:
            seeders = max(0, int(item.get("seeds") or 0))
        except (TypeError, ValueError):
            seeders = 0
        if seeders <= 0:
            continue
        try:
            item_season = int(item.get("season") or 0) or None
        except (TypeError, ValueError):
            item_season = None
        try:
            item_episode = int(item.get("episode") or 0) or None
        except (TypeError, ValueError):
            item_episode = None
        if not _release_matches_expected(
            release_title,
            [title],
            year if year > 1900 else None,
            item_season if item_season is not None else season,
            item_episode if item_episode is not None else episode,
        ):
            continue
        streams.append({
            "source": "EZTV",
            "voice": "Original (с субтитрами)",
            "quality": classify_voice_and_quality(release_title)[1],
            "seeders": seeders,
            "url": enrich_magnet_with_trackers(magnet),
            "title": release_title,
            "season": item_season if item_season is not None else season,
            "episode": item_episode if item_episode is not None else episode,
            "provider_item_id": item.get("id") or item.get("imdb_id"),
        })
    return streams


async def fetch_nyaa_torrents(
    title: str,
    year: int = 2024,
    category: str = "anime",
    season: Optional[int] = None,
    episode: Optional[int] = None,
    timeout: float = 4.0,
) -> List[Dict[str, Any]]:
    """Fetch anime/animation releases through Nyaa's RSS interface."""
    if category not in {"anime", "animation"}:
        return []

    endpoint = _configured_tracker_endpoint("Nyaa", "https://nyaa.si/")
    parts = urllib.parse.urlsplit(endpoint)
    params = dict(urllib.parse.parse_qsl(parts.query, keep_blank_values=True))
    params.update({"page": "rss", "f": "0", "c": "1_2", "q": str(title or "").strip()})
    if not params["q"] or not parts.scheme or not parts.netloc:
        return []
    rss_url = urllib.parse.urlunsplit((
        parts.scheme,
        parts.netloc,
        parts.path or "/",
        urllib.parse.urlencode(params),
        "",
    ))

    req = urllib.request.Request(
        rss_url,
        headers={"User-Agent": "MoviaTorrentResolver/3.0", "Accept": "application/rss+xml, application/xml"},
    )
    loop = asyncio.get_running_loop()

    def _call():
        with urllib.request.urlopen(req, timeout=min(max(float(timeout), 0.5), 5.0)) as resp:
            if resp.status != 200:
                return None
            return resp.read()

    try:
        raw_xml = await loop.run_in_executor(None, _call)
        root = ET.fromstring(raw_xml or b"")
    except Exception as exc:
        logger.debug("Nyaa endpoint error for %s: %s", title, exc)
        return []

    def xml_local_text(item: ET.Element, names: set[str]) -> str:
        for child in list(item):
            local_name = str(child.tag).rsplit("}", 1)[-1].casefold()
            if local_name in names and child.text:
                return child.text.strip()
        return ""

    streams: List[Dict[str, Any]] = []
    for item in root.findall(".//item"):
        release_title = xml_local_text(item, {"title"})
        description = xml_local_text(item, {"description"})
        info_hash = xml_local_text(item, {"infohash", "info_hash", "hash"})
        if not info_hash and "magnet:" in description:
            info_hash_match = re.search(
                r"xt=urn:btih:([^&\s<]+)", description, re.IGNORECASE
            )
            info_hash = info_hash_match.group(1) if info_hash_match else ""
        if not release_title or not is_valid_btih(info_hash):
            continue
        if not _release_matches_expected(
            release_title,
            [title],
            year if year > 1900 else None,
            season,
            episode,
        ):
            continue
        try:
            seeders = int(xml_local_text(item, {"seeders", "seed"}) or 0)
        except (TypeError, ValueError):
            seeders = 0
        if seeders <= 0:
            continue
        voice, quality = classify_voice_and_quality(release_title)
        magnet = enrich_magnet_with_trackers(
            f"magnet:?xt=urn:btih:{info_hash}&dn={urllib.parse.quote(release_title)}"
        )
        streams.append({
            "source": "Nyaa",
            "voice": voice,
            "quality": quality,
            "seeders": seeders,
            "url": magnet,
            "title": release_title,
            "season": season,
            "episode": episode,
        })
    return streams


def get_catalog_titles(query_title: str, category: str = "movies", season: Optional[int] = None) -> tuple[str, str, int, str]:
    """Look up localized/original titles from canonical catalog.db."""
    try:
        conn = sqlite3.connect(str(DB_PATH))
        cur = conn.cursor()
        requested_media_type = "tv" if (season is not None or category in {"tv_series", "series", "dramas_asian", "anime", "limited_series"}) else "movie"
        cur.execute(
            "SELECT title, original_title, year, category FROM movies WHERE media_type=? AND (title LIKE ? OR original_title LIKE ?) ORDER BY CASE WHEN title=? THEN 0 ELSE 1 END LIMIT 1;",
            (requested_media_type, f"%{query_title}%", f"%{query_title}%", query_title),
        )
        row = cur.fetchone()
        conn.close()
        if row:
            return row[0] or query_title, row[1] or query_title, int(row[2] or 0), row[3] or "movies"
    except Exception:
        pass
    return query_title, query_title, 0, "movies"

async def fetch_apibay_torrents(
    title: str,
    year: int = 2024,
    category: str = "movies",
    season: Optional[int] = None,
    episode: Optional[int] = None,
    timeout: float = 4.0
) -> List[Dict[str, Any]]:
    """Asynchronously queries Apibay / The Pirate Bay index with mirrors."""
    streams = []
    q_str = title
    if season is not None and episode is not None:
        q_str = f"{title} S{season:02d}E{episode:02d}"
    elif season is not None:
        q_str = f"{title} Season {season}"

    clean_q = urllib.parse.quote(q_str)
    mirrors = ["https://apibay.org/q.php"]

    for endpoint in mirrors:
        try:
            url = f"{endpoint}?q={clean_q}"
            loop = asyncio.get_event_loop()
            req = urllib.request.Request(url, headers={"User-Agent": "MoviaTorrentResolver/3.0"})

            def _call():
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    if resp.status == 200:
                        return json.loads(resp.read().decode("utf-8"))
                return None

            data = await loop.run_in_executor(None, _call)
            if isinstance(data, list) and len(data) > 0 and data[0].get("id") != "0":
                for item in data[:20]:
                    info_hash = item.get("info_hash")
                    name = item.get("name", title)
                    try:
                        seeders = max(0, int(item.get("seeders", 0) or 0))
                    except (TypeError, ValueError):
                        seeders = 0
                    if info_hash and seeders > 0:
                        voice, quality = classify_voice_and_quality(name)
                        raw_magnet = f"magnet:?xt=urn:btih:{info_hash}&dn={urllib.parse.quote(name)}"
                        enriched = enrich_magnet_with_trackers(raw_magnet)
                        streams.append({
                            "source": "Apibay",
                            "voice": voice,
                            "quality": quality,
                            "seeders": seeders,
                            "url": enriched,
                            "title": name,
                        })
                if streams:
                    break
        except Exception as e:
            logger.debug(f"Apibay mirror {endpoint} error for {title}: {e}")
    return streams


async def fetch_rutor_torrents(title: str, year: int = 2024, category: str = "movies", season: Optional[int] = None, episode: Optional[int] = None, timeout: float = 3.5) -> List[Dict[str, Any]]:
    """Fetch all configured Rutor mirrors concurrently and merge results."""
    clean_q = re.sub(r'[:;,!?]', ' ', title).strip()
    mirrors = [
        "https://rutor.info/search/0/0/0/0/",
        "https://rutor.is/search/0/0/0/0/",
        "http://6tor.net/search/0/0/0/0/"
    ]
    tr_list = get_live_trackers()
    tr_params = "&".join(f"tr={urllib.parse.quote(tr, safe='')}" for tr in tr_list if tr)

    bounded_timeout = min(max(float(timeout), 0.5), 5.0)
    loop = asyncio.get_running_loop()

    def parse_html(html: str) -> List[Dict[str, Any]]:
        streams: List[Dict[str, Any]] = []
        if not html or "magnet:" not in html:
            return streams
        rows = re.findall(r'<tr class="(?:gai|tum)".*?</tr>', html, re.DOTALL)
        for row in rows:
            magnet_match = re.search(r'href="(magnet:\?xt=urn:btih:[^"]+)"', row)
            if not magnet_match:
                continue
            raw_magnet = magnet_match.group(1)
            if "tr=" not in raw_magnet and tr_params:
                magnet = f"{raw_magnet}&{tr_params}"
            else:
                magnet = raw_magnet

            title_match = re.search(r'<a href="/torrent/[^>]+>(.*?)</a>', row)
            raw_title = re.sub(r'<[^>]+>', '', title_match.group(1)) if title_match else clean_q

            seed_match = re.search(r'<span class="green">.*?(\d+).*?</span>', row)
            try:
                seeders = max(0, int(seed_match.group(1))) if seed_match else 5
            except (TypeError, ValueError):
                seeders = 0

            if year and str(year) not in raw_title and str(year - 1) not in raw_title and str(year + 1) not in raw_title:
                continue

            voice, quality = classify_voice_and_quality(raw_title)
            encoded_dn = urllib.parse.quote(raw_title, safe="")
            if re.search(r"([?&])dn=[^&]*", magnet, re.IGNORECASE):
                magnet = re.sub(
                    r"([?&])dn=[^&]*",
                    lambda match: f"{match.group(1)}dn={encoded_dn}",
                    magnet,
                    count=1,
                    flags=re.IGNORECASE,
                )
            else:
                magnet += f"&dn={encoded_dn}"

            streams.append({
                "source": "Rutor",
                "voice": voice,
                "quality": quality,
                "seeders": seeders,
                "url": magnet,
                "title": raw_title,
            })
        return streams

    async def fetch_mirror(mirror: str) -> List[Dict[str, Any]]:
        url = f"{mirror}{urllib.parse.quote(clean_q)}"
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            })

            def do_request():
                with urllib.request.urlopen(req, timeout=bounded_timeout) as resp:
                    return resp.read().decode("utf-8", errors="ignore")

            html = await loop.run_in_executor(None, do_request)
            return parse_html(html)
        except Exception as e:
            logger.debug(f"Rutor mirror {mirror} error: {e}")
            return []

    results = await asyncio.gather(
        *(fetch_mirror(mirror) for mirror in mirrors),
        return_exceptions=True,
    )
    streams: List[Dict[str, Any]] = []
    for result in results:
        if isinstance(result, list):
            streams.extend(result)
    return streams

async def async_resolve_torrents(
    title: str,
    year: int = 2024,
    category: str = "movies",
    season: Optional[int] = None,
    episode: Optional[int] = None,
    quality_filter: Optional[str] = None
) -> List[Dict[str, Any]]:
    """Queries real torrent providers in parallel via asyncio.gather with automatic failover and ranking."""
    streams = []
    ru_title, en_title, db_year, db_cat = get_catalog_titles(title, category=category, season=season)
    eff_year = year or db_year
    eff_cat = category or db_cat
    is_series = eff_cat in ["tv_series", "series", "dramas_asian", "anime", "limited_series"] or season is not None

    # Build unique search queries for multi-tracker search strictly by title + year
    raw_queries = [
        f"{ru_title} {eff_year}".strip() if eff_year else ru_title.strip(),
        f"{en_title} {eff_year}".strip() if eff_year else en_title.strip(),
        ru_title.strip(),
        en_title.strip(),
        f"{title} {eff_year}".strip() if eff_year else title.strip(),
    ]
    search_queries = list(dict.fromkeys([q for q in raw_queries if q]))

    tasks = []
    # 0. Rutor queries (Russian studios & dubs: RHS, LostFilm, HDRezka, Dub)
    for q in [ru_title, f"{ru_title} {eff_year}".strip()]:
        if q:
            tasks.append(fetch_rutor_torrents(title=q, year=eff_year, category=eff_cat, season=season, episode=episode))

    # 1. Apibay queries for all title variants
    for q in search_queries[:4]:
        tasks.append(fetch_apibay_torrents(title=q, year=eff_year, category=eff_cat, season=season, episode=episode))

    # 2. YTS queries for movie English variants
    if not is_series:
        for q in [en_title, title] if en_title != title else [title]:
            tasks.append(fetch_yts_torrents(title=q, year=eff_year))

    # 3. Dedicated TV and anime providers. They are queried only for the
    # categories they actually index, keeping provider load bounded.
    if is_series:
        for q in list(dict.fromkeys([ru_title, en_title, title]))[:2]:
            if q:
                tasks.append(fetch_eztv_torrents(
                    title=q,
                    year=eff_year,
                    category=eff_cat,
                    season=season,
                    episode=episode,
                ))
    if eff_cat in {"anime", "animation"}:
        for q in list(dict.fromkeys([ru_title, en_title, title]))[:2]:
            if q:
                tasks.append(fetch_nyaa_torrents(
                    title=q,
                    year=eff_year,
                    category=eff_cat,
                    season=season,
                    episode=episode,
                ))

    results = await asyncio.gather(*tasks, return_exceptions=True)
    for r in results:
        if isinstance(r, list):
            streams.extend(r)

    # Direct streams container (populated without circular recursion)
    direct_streams = []

    # Deduplicate exact variants while retaining the same torrent locator when
    # providers report different voice or quality metadata. Tracker churn is
    # ignored by stream_variant_key.
    unique_torrent_streams = sanitize_streams(streams, require_source=True)

    # Reject provider query leakage and dead torrents. Search indexes may return
    # unrelated items for short/ambiguous titles, so identity is checked against
    # both localized and original catalog titles before ranking.
    expected_titles = list(dict.fromkeys([ru_title, en_title, title]))
    active_torrents = []
    for stream in unique_torrent_streams:
        try:
            seeds = int(stream.get("seeders") or 0)
        except (TypeError, ValueError):
            seeds = 0
        release_title = str(stream.get("title") or "")
        if seeds <= 0:
            continue
        if not release_title or not _release_matches_expected(
            release_title, expected_titles, eff_year, season, episode
        ):
            continue
        active_torrents.append(stream)

    # Russian dubbing/voice-over is the product default; within the same class,
    # prefer the Russian-focused provider and then the healthier swarm.
    active_torrents.sort(key=_stream_rank)

    # Combine: Direct HTTP/HLS streams FIRST, followed by validated P2P torrents.
    all_streams = sanitize_streams(direct_streams + active_torrents, require_source=True)

    # Apply quality filter if requested
    if quality_filter:
        q_clean = quality_filter.lower().replace("p", "")
        filtered = [s for s in all_streams if q_clean in s.get("quality", "").lower().replace("p", "")]
        if filtered:
            all_streams = filtered

    return all_streams

def resolve_torrents_for_query(
    title: str,
    year: int = 2024,
    category: str = "movies",
    season: Optional[int] = None,
    episode: Optional[int] = None,
    quality: Optional[str] = None
) -> List[Dict[str, Any]]:
    """Synchronous entry point for torrent resolution."""
    try:
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            loop = None

        if loop and loop.is_running():
            import concurrent.futures
            with concurrent.futures.ThreadPoolExecutor() as pool:
                return pool.submit(lambda: asyncio.run(async_resolve_torrents(title, year, category, season, episode, quality))).result()
        else:
            return asyncio.run(async_resolve_torrents(title, year, category, season, episode, quality))
    except Exception as e:
        logger.error(f"Error in resolve_torrents_for_query for {title}: {e}")
        return []

def resolve_streams(title: str, year: int = 2024, category: str = "movies", season: Optional[int] = None, episode: Optional[int] = None) -> List[Dict[str, Any]]:
    """Public convenience function to resolve real streams for a title."""
    return resolve_torrents_for_query(title=title, year=year, category=category, season=season, episode=episode)

def resolve_torrent(
    title: str,
    year: int = 2024,
    category: str = "movies",
    season: Optional[int] = None,
    episode: Optional[int] = None,
    quality: Optional[str] = None
) -> Optional[Dict[str, Any]]:
    """Resolves best torrent release for content_filler dispatcher."""
    try:
        streams = resolve_torrents_for_query(title, year, category, season, episode, quality)
        if not streams:
            logger.warning(f"❌ [Torrent] Не найдены раздачи для: {title} ({year})")
            return None

        best = streams[0]
        logger.info(f"✅ [Torrent] Найден поток для: {title} | {best.get('voice')} {best.get('quality')} ({best.get('seeders')} сидов)")
        return {
            "playback_url": best["url"],
            "voice": best.get("voice", "Не указано"),
            "quality": best.get("quality", "Не указано"),
            "seeders": int(best.get("seeders", 100)),
            "streams": streams,
            "link_verified": 1
        }
    except Exception as e:
        logger.error(f"Error in resolve_torrent for {title}: {e}")
        return None

if __name__ == "__main__":
    if len(sys.argv) > 1:
        test_title = sys.argv[1]
        res = resolve_torrents_for_query(test_title, 2024)
        print(f"🔍 Найдено {len(res)} потоков для '{test_title}':")
        for idx, s in enumerate(res[:5], 1):
            print(f" {idx}. [{s.get('source')}] {s.get('voice')} {s.get('quality')} | {s.get('seeders')} seeds -> {s.get('url')[:60]}...")
    else:
        print("Тестирование resolve_torrent('Интерстеллар', 2014):")
        result = resolve_torrent("Интерстеллар", 2014)
        print(json.dumps(result, indent=2, ensure_ascii=False))
