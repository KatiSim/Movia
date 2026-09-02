#!/usr/bin/env python3
"""Contract-compatible client for the legacy Zona metadata/stream protocol.

This adapter follows the old app's public flow:
suggestions -> video sources -> local provider adapter.  The APK resolves
providers in-process; Movia never guesses URLs or calls a fabricated
/getStreams/ endpoint.
"""
from __future__ import annotations

import gzip
import json
from concurrent.futures import ThreadPoolExecutor, as_completed
import logging
import re
import os
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

from catalog_schema_v2 import normalize_ru_text
from stream_validation import bind_stream_identity, sanitize_streams
from zona_legacy_adapters import resolve_local_source

DIR = Path(__file__).resolve().parent
CONFIG_PATH = DIR / "config" / "zona_api.json"
_LOG = logging.getLogger("zona_contract")

DEFAULT_API_MIRRORS = (
    "https://apir1.mzona.net",
    "https://apiw1.mzona.net",
    "https://apir0.mzona.net",
    "http://apir0.mzona.net",
    "https://apiw0.mzona.net",
)
DEFAULT_STREAM_MIRRORS = (
    "https://vsr01.zonasearch.com",
    "https://vsw01.zonasearch.com",
)
DEFAULT_TIME_MIRRORS = (
    "https://apir1.mzona.net",
    "https://apiw1.mzona.net",
    "https://apir0.mzona.net",
    "http://apir0.mzona.net",
)
# The legacy Zona client sends this complete provider registry with every
# GetVideoSources request. It is a catalog-wide source capability list, not a
# title alias or a title-specific exception.
DEFAULT_MOVIE_SOURCE_TYPES = (
    "1", "2", "3", "5", "6", "7", "8", "9", "10", "12", "13", "14",
    "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25",
    "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36",
    "37", "38", "39", "40", "41", "42", "43", "44", "45", "46", "47",
    "48", "49", "50", "51", "52", "53",
)
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
DEFAULT_TIMEOUT = 3.5
_TIME_LOCK = threading.Lock()
_TIME_OFFSET_MS = 0
_TIME_EXPIRES_AT = 0.0
_MIRROR_LOCK = threading.Lock()
_MIRROR_FAILURES: Dict[Tuple[str, str], int] = {}
_MIRROR_OPEN_UNTIL: Dict[Tuple[str, str], float] = {}
_PROTECTED_PATHS = frozenset({
    "/getMetadata",
    "/getVideoSources",
    "/getMovieIds",
    "/getSerialIds",
})
_COOKIE_LOCK = threading.RLock()
_COOKIE_CACHE: Dict[Tuple[int, str, int, int], str] = {}
_COOKIE_CACHE_MAX_ENTRIES = 8
_MILLIS_IN_DAY = 86_400_000
_ADLER_MOD = 65_521

# Zona's stable playback pipeline caches the logical source list separately
# from resolved stream URLs. The cache is process-local, bounded, and never
# persisted because source keys are provider credentials-like material.
_SOURCE_CACHE_LOCK = threading.RLock()
_SOURCE_CACHE: Dict[Tuple[str, str, str], Tuple[float, List[Dict[str, Any]]]] = {}
_SOURCE_CACHE_TTL_SECONDS = 60.0 * 60.0
_SOURCE_CACHE_MAX_ENTRIES = 256

@dataclass
class ZonaLookup:
    status: str
    streams: List[Dict[str, Any]] = field(default_factory=list)
    suggestions: int = 0
    source_refs: int = 0
    errors: List[str] = field(default_factory=list)

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

_OPENER = urllib.request.build_opener(_NoRedirect)

def _config() -> Dict[str, Any]:
    try:
        with CONFIG_PATH.open("r", encoding="utf-8") as handle:
            value = json.load(handle)
            return value if isinstance(value, dict) else {}
    except Exception as exc:
        _LOG.debug("zona config unavailable: %s", exc)
        return {}

def _clean_mirrors(values: Any) -> List[str]:
    if not isinstance(values, (list, tuple)):
        return []
    result: List[str] = []
    for value in values:
        if isinstance(value, dict):
            value = value.get("url")
        value = str(value or "").strip().rstrip("/")
        parsed = urllib.parse.urlparse(value)
        if parsed.scheme in {"http", "https"} and parsed.netloc and value not in result:
            result.append(value)
    return result

def _mirrors(kind: str) -> List[str]:
    data = _config()
    configured = _clean_mirrors(data.get(kind))
    if configured:
        return configured
    legacy = _clean_mirrors(data.get("mirrors"))
    if kind == "stream_mirrors":
        stream = [value for value in legacy if "zonasearch" in urllib.parse.urlparse(value).netloc]
        return stream or list(DEFAULT_STREAM_MIRRORS)
    if kind == "time_mirrors":
        api = [value for value in legacy if "zonasearch" not in urllib.parse.urlparse(value).netloc]
        return api or list(DEFAULT_TIME_MIRRORS)
    api = [value for value in legacy if "zonasearch" not in urllib.parse.urlparse(value).netloc]
    return api or list(DEFAULT_API_MIRRORS)


def _configured_movie_source_types() -> List[str]:
    values = _config().get("movie_source_types", DEFAULT_MOVIE_SOURCE_TYPES)
    if isinstance(values, str):
        values = [part.strip() for part in values.split(",")]
    if not isinstance(values, (list, tuple)):
        values = DEFAULT_MOVIE_SOURCE_TYPES
    result: List[str] = []
    for value in values:
        if isinstance(value, bool):
            continue
        text = str(value).strip()
        if text and text not in result:
            result.append(text)
    return result[:64] or list(DEFAULT_MOVIE_SOURCE_TYPES)


def _timeout() -> float:
    try:
        return min(max(float(_config().get("timeout_sec", DEFAULT_TIMEOUT)), 0.5), 8.0)
    except (TypeError, ValueError):
        return DEFAULT_TIMEOUT

def _device_property(name: str, fallback: str) -> str:
    try:
        result = subprocess.run(
            ["getprop", name],
            check=False,
            capture_output=True,
            text=True,
            timeout=0.5,
        )
        value = result.stdout.strip()
        if value:
            return value
    except Exception:
        pass
    return fallback

def zona_user_agent() -> str:
    version = str(_config().get("client_version") or "3.0.68").strip()
    manufacturer = urllib.parse.quote(_device_property("ro.product.manufacturer", "Android"), safe="")
    model = urllib.parse.quote(_device_property("ro.product.model", "Termux"), safe="")
    release = _device_property("ro.build.version.release", "unknown")
    return f"Zona/{version} ({manufacturer}/{model}/Android {release})"

def _effective_time_ms() -> int:
    with _TIME_LOCK:
        offset = _TIME_OFFSET_MS if time.monotonic() < _TIME_EXPIRES_AT else 0
    return int(time.time() * 1000) + int(offset)


def _protected_path(path: str) -> bool:
    return "/" + str(path or "").strip("/") in _PROTECTED_PATHS


def _query_client_time_ms(query: Optional[Sequence[Tuple[str, str]]]) -> Optional[int]:
    for key, value in query or ():
        if str(key) != "client_time":
            continue
        try:
            return int(str(value).split(".", 1)[0])
        except (TypeError, ValueError):
            return None
    return None


def _legacy_apk_candidates() -> Iterable[Path]:
    configured = os.environ.get("ZONA_APK_PATH") or _config().get("legacy_apk_path")
    if configured:
        yield Path(str(configured)).expanduser()
    # Keep discovery scoped to the local Movia/Zona project area. This also
    # survives creation of a newer zona-reference-* checkpoint.
    for candidate in sorted(
        DIR.parent.glob("zona-reference*/apk/*.apk"),
        key=lambda item: str(item),
        reverse=True,
    ):
        yield candidate


def _legacy_apk_identity() -> Optional[Tuple[Path, int, int]]:
    seen: set[str] = set()
    for candidate in _legacy_apk_candidates():
        try:
            resolved = candidate.resolve()
            key = str(resolved)
            if key in seen:
                continue
            seen.add(key)
            stat = resolved.stat()
            if stat.st_size > 0:
                return resolved, int(stat.st_mtime_ns), int(stat.st_size)
        except (OSError, RuntimeError):
            continue
    return None


def _zona_cookie(client_time_ms: int) -> str:
    """Reproduce Zona's process-local daily APK/time cookie.

    The legacy client reads the APK through a temporary cache path, computes
    the same byte-wise Adler-style pair, then embeds the server-time seconds
    into a random 64-bit value. The random component is intentionally not
    persisted; only the resulting header is kept in memory for the day.
    """
    identity = _legacy_apk_identity()
    if identity is None:
        return ""
    apk_path, mtime_ns, file_size = identity
    day = int(client_time_ms) // _MILLIS_IN_DAY
    cache_key = (day, str(apk_path), mtime_ns, file_size)
    with _COOKIE_LOCK:
        cached = _COOKIE_CACHE.get(cache_key)
        if cached is not None:
            return cached

    sum_a = 0
    sum_b = 1
    try:
        with apk_path.open("rb") as handle:
            while True:
                chunk = handle.read(2048)
                if not chunk:
                    break
                for raw_byte in chunk:
                    signed_byte = raw_byte if raw_byte < 128 else raw_byte - 256
                    # Java/Kotlin legacy code applies the byte modulus (256)
                    # before adding the running Adler-style accumulator.
                    sum_b = (((signed_byte + day) % 256) + sum_b) % _ADLER_MOD
                    sum_a = (sum_a + sum_b) % _ADLER_MOD
    except OSError:
        return ""

    checksum = (sum_a << 16) + sum_b
    seconds = int(client_time_ms) // 1000
    random_value = secrets.randbits(64)
    # JADX's output reflects the old Kotlin Int shift semantics here: the
    # shift count is reduced modulo 32 even though the result is OR'ed into a
    # Long. Keeping this detail is required for protocol compatibility.
    for bit_index in range(32):
        # The decompiled Kotlin expression uses an Int shift, then promotes
        # that signed Int to Long for the bit operation. In particular,
        # Int.MIN_VALUE at shift 31 sign-extends into the Long. Reproduce
        # those JVM semantics instead of treating every mask as unsigned.
        shift = ((bit_index * 2) + 1) & 31
        int_mask = (1 << shift) & 0xFFFFFFFF
        if int_mask & 0x80000000:
            mask = (int_mask | ~0xFFFFFFFF) & ((1 << 64) - 1)
        else:
            mask = int_mask
        if (seconds >> bit_index) & 1:
            random_value = (random_value | mask) & ((1 << 64) - 1)
        else:
            random_value = random_value & (~mask & ((1 << 64) - 1))
    random_value &= (1 << 64) - 1
    first = random_value & ~0xFF
    second = (checksum ^ random_value) & ((1 << 64) - 1)
    cookie = first.to_bytes(8, "big").hex() + second.to_bytes(8, "big").hex()
    with _COOKIE_LOCK:
        _COOKIE_CACHE[cache_key] = cookie
        while len(_COOKIE_CACHE) > _COOKIE_CACHE_MAX_ENTRIES:
            _COOKIE_CACHE.pop(next(iter(_COOKIE_CACHE)))
    return cookie

def _mirror_key(base: str, path: str) -> Tuple[str, str]:
    # A route can be unavailable while another route on the same mirror is
    # healthy (for example the old suggestions endpoint versus /search).
    return str(base), str(path or "/").strip("/") or "/"

def _mirror_available(base: str, path: str) -> bool:
    key = _mirror_key(base, path)
    with _MIRROR_LOCK:
        return _MIRROR_OPEN_UNTIL.get(key, 0.0) <= time.monotonic()

def _mirror_success(base: str, path: str) -> None:
    key = _mirror_key(base, path)
    with _MIRROR_LOCK:
        _MIRROR_FAILURES.pop(key, None)
        _MIRROR_OPEN_UNTIL.pop(key, None)

def _mirror_failure(base: str, path: str) -> None:
    key = _mirror_key(base, path)
    with _MIRROR_LOCK:
        failures = _MIRROR_FAILURES.get(key, 0) + 1
        _MIRROR_FAILURES[key] = failures
        cooldown = min(900.0, 60.0 * (2 ** min(failures - 1, 3)))
        _MIRROR_OPEN_UNTIL[key] = time.monotonic() + cooldown

def _request(
    base: str,
    path: str,
    query: Optional[Sequence[Tuple[str, str]]] = None,
) -> Tuple[Optional[bytes], Dict[str, str], Optional[str]]:
    if not _mirror_available(base, path):
        return None, {}, "CIRCUIT_OPEN"
    url = base.rstrip("/") + "/" + path.lstrip("/")
    if query:
        url += "?" + urllib.parse.urlencode(list(query), doseq=True)
    request_headers = {
        "User-Agent": zona_user_agent(),
        "Accept": "application/json, text/plain, */*",
        "Content-Type": "application/json",
        "Accept-Encoding": "gzip",
        "X-Requested-With": "XMLHttpRequest",
    }
    if _protected_path(path):
        client_time_ms = _query_client_time_ms(query) or _effective_time_ms()
        cookie = _zona_cookie(client_time_ms)
        if cookie:
            request_headers["Cookie"] = f"s={cookie}"
    request = urllib.request.Request(
        url,
        headers=request_headers,
    )
    try:
        with _OPENER.open(request, timeout=_timeout()) as response:
            status = int(getattr(response, "status", 200))
            if status not in (200, 206):
                _mirror_failure(base, path)
                return None, {}, f"HTTP_ERROR:{status}"
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                _mirror_failure(base, path)
                return None, {}, "RESPONSE_TOO_LARGE"
            headers = {str(key).lower(): str(value) for key, value in response.headers.items()}
            if "gzip" in headers.get("content-encoding", "").lower():
                raw = gzip.decompress(raw)
                if len(raw) > MAX_RESPONSE_BYTES:
                    _mirror_failure(base, path)
                    return None, {}, "DECOMPRESSED_RESPONSE_TOO_LARGE"
            _mirror_success(base, path)
            return raw, headers, None
    except urllib.error.HTTPError as exc:
        _mirror_failure(base, path)
        return None, {}, f"HTTP_ERROR:{int(exc.code)}"
    except Exception as exc:
        _mirror_failure(base, path)
        return None, {}, f"{type(exc).__name__}:{str(exc)[:120]}"

def _json_items(raw: Optional[bytes]) -> Optional[List[Dict[str, Any]]]:
    if raw is None:
        return None
    try:
        value: Any = json.loads(raw.decode("utf-8", errors="replace"))
    except Exception:
        return None
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    if not isinstance(value, dict):
        return None
    for key in ("data", "results", "items", "values", "suggestions", "sources", "streams"):
        nested = value.get(key)
        if isinstance(nested, list):
            return [item for item in nested if isinstance(item, dict)]
        if isinstance(nested, dict):
            return [nested]
    return [value]

def _params_query(params: Dict[str, Any]) -> List[Tuple[str, str]]:
    """Serialize endpoints that accept top-level legacy query fields.

    Structured values remain compact JSON so this helper is generic for
    suggestions, metadata search, and future compatible endpoints. The
    protected GetVideoSources DTO uses _serialized_params_query below.
    """
    pairs: List[Tuple[str, str]] = []
    for key, value in params.items():
        if value is None:
            continue
        if isinstance(value, (dict, list, tuple)):
            encoded = json.dumps(
                value,
                ensure_ascii=False,
                separators=(",", ":"),
            )
        else:
            encoded = str(value)
        pairs.append((str(key), encoded))
    return pairs

def _first(item: Dict[str, Any], keys: Iterable[str]) -> Any:
    for key in keys:
        value = item.get(key)
        if value is not None and str(value).strip():
            return value
    return None

def _nested_item(item: Dict[str, Any]) -> Dict[str, Any]:
    merged = dict(item)
    for key in ("info", "metadata", "movie", "content", "source", "data"):
        value = item.get(key)
        if isinstance(value, str) and key == "info":
            try:
                value = json.loads(value)
            except Exception:
                value = None
        if isinstance(value, dict):
            for nested_key, nested_value in value.items():
                merged.setdefault(nested_key, nested_value)
    return merged

def _localized_values(value: Any) -> List[str]:
    """Extract localized text fields without assuming one provider payload shape."""
    if isinstance(value, str):
        text = value.strip()
        return [text] if text else []
    if not isinstance(value, dict):
        return []

    aliases = {
        "ru", "ru-ru", "russian",
        "original",
        "en", "en-us", "english",
        "uk", "uk-ua", "ukrainian",
        "value", "text",
    }
    values: List[str] = []
    for key, raw in value.items():
        normalized_key = str(key).strip().casefold().replace("_", "-")
        if normalized_key not in aliases:
            continue
        text = str(raw or "").strip()
        if text and text not in values:
            values.append(text)
    return values


def _title_fields(item: Dict[str, Any]) -> List[str]:
    value = _nested_item(item)
    titles: List[str] = []
    for key in (
        "name", "title", "titleRu", "title_ru", "nameRu", "name_ru",
        "localizedTitle", "localized_title", "movieTitle", "movie_title",
        "originalTitle", "original_title", "originalName", "original_name",
        "nameOriginal", "name_original", "label",
    ):
        for text in _localized_values(value.get(key)):
            if text not in titles:
                titles.append(text)
    return titles

def _same_title(candidate: str, expected: Sequence[str]) -> bool:
    candidate_norm = normalize_ru_text(candidate)
    if not candidate_norm:
        return False
    for value in expected:
        expected_norm = normalize_ru_text(value)
        if not expected_norm:
            continue
        # A title-only prefix is not an identity match: “Гравити Фолз” and
        # “Гравити Фолз: …” may be different catalogue works. Aliases must be
        # supplied explicitly by the same canonical card.
        if candidate_norm == expected_norm:
            return True
    return False

def _item_year(item: Dict[str, Any]) -> Optional[int]:
    value = _nested_item(item)
    for key in ("year", "releaseYear", "release_year"):
        try:
            year = int(value.get(key))
        except (TypeError, ValueError):
            year = 0
        if 1900 <= year <= 2100:
            return year
    return None

def _item_kind(item: Dict[str, Any]) -> str:
    value = _nested_item(item)

    # The legacy Zona payload uses a boolean serial field instead of a
    # mediaType string. Prefer that provider classification when present and
    # fall back to the broader aliases used by other metadata providers.
    serial = value.get("serial")
    if isinstance(serial, bool):
        return "tv" if serial else "movie"
    if isinstance(serial, (int, float)) and not isinstance(serial, bool):
        if serial in (0, 1):
            return "tv" if int(serial) == 1 else "movie"
    if isinstance(serial, str):
        serial_flag = serial.strip().casefold()
        if serial_flag in {"true", "1", "yes", "serial", "series", "tv", "сериал"}:
            return "tv"
        if serial_flag in {"false", "0", "no", "movie", "film", "фильм"}:
            return "movie"

    kind = str(_first(value, ("mediaType", "media_type", "contentType", "content_type", "type", "kind")) or "").casefold()
    if any(token in kind for token in ("tv", "series", "serial", "сериал")):
        return "tv"
    if any(token in kind for token in ("movie", "film", "фильм", "кино")):
        return "movie"
    return ""

def _expected_kind(media_type: Optional[str], season: Optional[int]) -> str:
    raw = str(media_type or "").casefold()
    if season is not None or raw in {"tv", "series", "tv_series", "serial", "limited_series", "animation", "anime"}:
        return "tv"
    if raw in {"movie", "movies", "film"}:
        return "movie"
    return ""

def _matching_title(
    item: Dict[str, Any],
    expected_titles: Sequence[str],
    year: Optional[int],
    media_type: Optional[str],
    season: Optional[int],
) -> Optional[str]:
    titles = _title_fields(item)
    if not any(_same_title(candidate, expected_titles) for candidate in titles):
        return None
    item_year = _item_year(item)
    if year:
        if item_year is None or int(year) != item_year:
            return None
    expected_kind = _expected_kind(media_type, season)
    item_kind = _item_kind(item)
    if expected_kind and (not item_kind or expected_kind != item_kind):
        return None
    for candidate in titles:
        if _same_title(candidate, expected_titles):
            return candidate
    return None

def _provider_id(item: Dict[str, Any]) -> Any:
    return _first(item, (
        "kinopoiskId", "kinopoisk_id", "kinopoiskID", "kpId", "kp_id",
        "id", "providerId", "provider_id",
    ))

def _legacy_search_queries(title: str) -> List[str]:
    """Return a bounded set of generic queries for the legacy /search API.

    A full title is attempted first. If the provider only supports prefix
    matching, one short prefix of the first normalized token is attempted as
    a bounded fallback. The result is always filtered by the canonical title
    matcher before it can become a discovery candidate.
    """
    clean = " ".join(str(title or "").strip().split())
    if not clean:
        return []

    queries = [clean]
    normalized = normalize_ru_text(clean)
    first_token = normalized.split(" ", 1)[0] if normalized else ""
    if len(first_token) >= 3:
        prefix = first_token[:min(8, max(4, len(first_token)))]
        if prefix and prefix != normalized:
            queries.append(prefix)
    return queries


def _fetch_legacy_search(
    query: str,
    *,
    limit: int = 200,
    offset: int = 0,
) -> Tuple[List[Dict[str, Any]], List[str], bool]:
    """Read legacy Zona metadata search with its real top-level pagination.

    Returns (items, errors, valid_response). valid_response is kept separate
    so an unavailable optional suggestions endpoint does not turn a valid
    empty metadata response into a provider error.
    """
    params = {
        "query": str(query),
        "limit": max(1, min(int(limit), 200)),
        "offset": max(0, int(offset)),
        "hideUnavailable": "true",
    }
    errors: List[str] = []
    for base in _mirrors("api_mirrors"):
        raw, _, error = _request(base, "search", _params_query(params))
        if error:
            errors.append(f"{base}:{error}")
            continue
        items = _json_items(raw)
        if items is None:
            errors.append(f"{base}:INVALID_RESPONSE")
            continue
        return items, [], True
    return [], errors, False


def _fetch_suggestions(
    title: str,
    expected_titles: Sequence[str],
    year: Optional[int],
    media_type: Optional[str],
    season: Optional[int],
) -> Tuple[List[Dict[str, Any]], List[str]]:
    params = {"query": title, "timeoutMs": int(_timeout() * 1000)}
    matches: List[Dict[str, Any]] = []
    suggestion_errors: List[str] = []

    def collect(items: Sequence[Dict[str, Any]]) -> None:
        for item in items:
            if _matching_title(item, expected_titles, year, media_type, season):
                provider_id = _provider_id(item)
                if provider_id is not None:
                    copy = dict(item)
                    copy["_provider_id"] = provider_id
                    if not any(str(existing.get("_provider_id")) == str(provider_id) for existing in matches):
                        matches.append(copy)

    for base in _mirrors("api_mirrors"):
        raw, _, error = _request(base, "getMovieOrSerialSuggests", _serialized_params_query(params))
        if error:
            suggestion_errors.append(f"{base}:{error}")
            continue
        items = _json_items(raw)
        if items is None:
            suggestion_errors.append(f"{base}:INVALID_RESPONSE")
            continue
        collect(items)
        if matches:
            # Mirrors are ordered failover endpoints. Once a valid matching
            # suggestion set is available, do not duplicate the same request
            # against every healthy mirror.
            break

    if matches:
        return matches, suggestion_errors

    # Some deployed legacy mirrors expose /search but not the old suggestions
    # route. Use that generic metadata route only for on-demand discovery.
    legacy_valid = False
    legacy_errors: List[str] = []
    for search_query in _legacy_search_queries(title):
        items, errors, valid = _fetch_legacy_search(search_query)
        legacy_errors.extend(errors)
        legacy_valid = legacy_valid or valid
        collect(items)
        if matches:
            break

    if legacy_valid:
        # A valid /search response makes getMovieOrSerialSuggests availability
        # non-fatal; an empty but valid response is a genuine NO_RESULTS case.
        return matches, []
    return matches, suggestion_errors + legacy_errors

def _source_cache_key(
    provider_id: Any,
    episode_key: Optional[str],
    request_params: Optional[Dict[str, Any]] = None,
) -> Tuple[str, str, str]:
    variant = ""
    if request_params:
        variant = json.dumps(
            request_params,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    return (
        str(provider_id or "").strip(),
        str(episode_key or "").strip(),
        variant,
    )


def _source_cache_limit() -> int:
    try:
        value = _config().get("source_cache_max_entries", _SOURCE_CACHE_MAX_ENTRIES)
        return min(max(int(value), 16), 2048)
    except (TypeError, ValueError):
        return _SOURCE_CACHE_MAX_ENTRIES


def _get_cached_video_sources(
    provider_id: Any,
    episode_key: Optional[str],
    *,
    request_params: Optional[Dict[str, Any]] = None,
    force_refresh: bool = False,
) -> Optional[List[Dict[str, Any]]]:
    key = _source_cache_key(provider_id, episode_key, request_params)
    with _SOURCE_CACHE_LOCK:
        if force_refresh:
            _SOURCE_CACHE.pop(key, None)
            return None
        entry = _SOURCE_CACHE.get(key)
        if not entry:
            return None
        expires_at, sources = entry
        if expires_at <= time.monotonic():
            _SOURCE_CACHE.pop(key, None)
            return None
        return [dict(source) for source in sources]


def _put_cached_video_sources(
    provider_id: Any,
    episode_key: Optional[str],
    sources: Sequence[Dict[str, Any]],
    *,
    request_params: Optional[Dict[str, Any]] = None,
) -> None:
    clean_sources = [dict(source) for source in sources if isinstance(source, dict)]
    if not clean_sources:
        return
    key = _source_cache_key(provider_id, episode_key, request_params)
    with _SOURCE_CACHE_LOCK:
        if key not in _SOURCE_CACHE and len(_SOURCE_CACHE) >= _source_cache_limit():
            oldest_key = min(_SOURCE_CACHE, key=lambda item: _SOURCE_CACHE[item][0])
            _SOURCE_CACHE.pop(oldest_key, None)
        _SOURCE_CACHE[key] = (
            time.monotonic() + _SOURCE_CACHE_TTL_SECONDS,
            clean_sources,
        )


def _normalized_video_sources_params(
    provider_id: Any,
    episode_key: Optional[str],
    *,
    movie_source_types: Optional[Sequence[Any]] = None,
    trailer: Optional[bool] = None,
    user_info: Optional[Dict[str, Any]] = None,
    installer_package: Optional[str] = None,
) -> Dict[str, Any]:
    """Build the legacy GetVideoSourcesParams JSON contract.

    The old Kotlin serializer requires a numeric kinopoiskId and episodeKey.
    Optional values are emitted only when meaningful, matching its
    encodeDefaults=false behavior. No cookies, source keys, or signatures are
    part of this DTO.
    """
    try:
        kinopoisk_id = int(str(provider_id).strip())
    except (TypeError, ValueError):
        raise ValueError("kinopoiskId must be numeric")
    params: Dict[str, Any] = {
        "kinopoiskId": kinopoisk_id,
        "episodeKey": str(episode_key or ""),
    }
    if isinstance(movie_source_types, str):
        source_types: Sequence[Any] = [movie_source_types]
    elif isinstance(movie_source_types, (list, tuple)):
        source_types = movie_source_types
    else:
        source_types = ()
    clean_source_types = [
        str(value).strip()
        for value in source_types
        if isinstance(value, (str, int)) and not isinstance(value, bool) and str(value).strip()
    ][:64]
    if clean_source_types:
        params["movieSourceTypes"] = clean_source_types
    if trailer is not None:
        if isinstance(trailer, bool):
            params["trailer"] = trailer
        elif isinstance(trailer, (int, str)):
            value = str(trailer).strip().lower()
            if value in {"true", "1"}:
                params["trailer"] = True
            elif value in {"false", "0"}:
                params["trailer"] = False
    if isinstance(user_info, dict):
        try:
            user_id = int(str(user_info.get("userId", 0)).strip())
        except (TypeError, ValueError):
            user_id = 0
        premium_value = user_info.get("isPremium", False)
        if isinstance(premium_value, bool):
            is_premium = premium_value
        else:
            is_premium = str(premium_value).strip().lower() in {"true", "1", "yes"}
        if user_id != 0 or is_premium:
            params["userInfo"] = {
                "userId": user_id,
                "isPremium": is_premium,
            }
    if installer_package:
        clean_package = str(installer_package).strip()
        if clean_package:
            params["installerPackage"] = clean_package[:128]
    return params


def _serialized_params_query(params: Dict[str, Any]) -> List[Tuple[str, str]]:
    """Encode the old client's single params JSON query envelope."""
    return [(
        "params",
        json.dumps(params, ensure_ascii=False, separators=(",", ":")),
    )]


def _fetch_video_sources(
    provider_id: Any,
    episode_key: Optional[str],
    *,
    movie_source_types: Optional[Sequence[Any]] = None,
    trailer: Optional[bool] = None,
    user_info: Optional[Dict[str, Any]] = None,
    installer_package: Optional[str] = None,
    force_refresh: bool = False,
) -> Tuple[List[Dict[str, Any]], List[str]]:
    effective_source_types = movie_source_types or _configured_movie_source_types()
    try:
        params = _normalized_video_sources_params(
            provider_id,
            episode_key,
            movie_source_types=effective_source_types,
            trailer=trailer,
            user_info=user_info,
            installer_package=installer_package,
        )
    except ValueError as exc:
        return [], [f"INVALID_PARAMS:{exc}"]
    cached = _get_cached_video_sources(
        provider_id,
        episode_key,
        request_params=params,
        force_refresh=force_refresh,
    )
    if cached is not None:
        return cached, []

    # The legacy protected endpoint expects client_time to be based on the
    # provider clock. Refresh the cached offset once before constructing the
    # request; _client_time itself remains a pure formatter.
    _refresh_server_time()
    # The legacy Kotlin client sends the DTO as one params JSON query field.
    # Its interceptor adds synchronized client_time to this protected endpoint.
    query: List[Tuple[str, str]] = _serialized_params_query(params)
    query.append(("client_time", _client_time()))
    sources: List[Dict[str, Any]] = []
    errors: List[str] = []
    for base in _mirrors("api_mirrors"):
        raw, _, error = _request(base, "getVideoSources", query)
        if error:
            errors.append(f"{base}:{error}")
            continue
        items = _json_items(raw)
        if items is None:
            errors.append(f"{base}:INVALID_RESPONSE")
            continue
        for item in items:
            source = _nested_item(item)
            if source not in sources:
                sources.append(source)
        if sources:
            # Keep the first valid source response; later mirrors are
            # failover paths, not an additional source catalog.
            break
    if sources:
        _put_cached_video_sources(
            provider_id,
            episode_key,
            sources,
            request_params=params,
        )
    return sources, errors


def _source_key(source: Dict[str, Any]) -> Optional[str]:
    value = _first(source, (
        "downloadLinkKey", "download_link_key", "downloadKey", "download_key",
        "sourceKey", "source_key", "key",
    ))
    return str(value).strip() if value is not None else None

def _extractor_id(source: Dict[str, Any]) -> Optional[int]:
    value = _first(source, (
        "extractor", "extractorId", "extractor_id", "videoSourceTypeId",
        "video_source_type_id", "sourceTypeId", "source_type_id", "typeId",
        "type_id",
    ))
    try:
        parsed = int(value)
        return parsed if parsed > 0 else None
    except (TypeError, ValueError):
        return None

def _episode_key_for_request(
    season: Optional[int],
    episode: Optional[int],
    episode_key: Optional[str],
) -> Optional[str]:
    clean = str(episode_key or "").strip()
    if clean:
        return clean
    try:
        if season is None or episode is None:
            return None
        season_number = int(season)
        episode_number = int(episode)
    except (TypeError, ValueError):
        return None
    if season_number <= 0 or episode_number <= 0:
        return None
    return f"S{season_number:02d}E{episode_number:02d}"





def _fetch_provider_json(
    path: str,
    query: Sequence[Tuple[str, str]],
) -> Tuple[Optional[Any], Optional[str]]:
    """Fetch a provider adapter response through the configured API mirrors."""
    errors: List[str] = []
    for base in _mirrors("api_mirrors"):
        raw, _, error = _request(base, path, query)
        if error:
            errors.append(f"{base}:{error}")
            continue
        if raw is None:
            errors.append(f"{base}:EMPTY_RESPONSE")
            continue
        try:
            return json.loads(raw.decode("utf-8", errors="replace")), None
        except (TypeError, ValueError):
            errors.append(f"{base}:INVALID_JSON")
    return None, ";".join(errors[:4]) or "PROVIDER_REQUEST_FAILED"



def _fetch_provider_text(
    url: str,
    headers: Optional[Dict[str, str]] = None,
) -> Tuple[Optional[str], Optional[str]]:
    parsed = urllib.parse.urlparse(str(url or "").strip())
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        return None, "INVALID_URL"

    request_headers = {
        "User-Agent": str((headers or {}).get("User-Agent") or zona_user_agent()),
        "Accept": "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8",
        "Accept-Encoding": "gzip",
    }
    for name, value in (headers or {}).items():
        if str(name).lower() == "user-agent":
            continue
        clean_name = str(name).strip()
        clean_value = str(value).strip()
        if (
            clean_name and clean_value
            and len(clean_name) <= 128 and len(clean_value) <= 4096
            and "\r" not in clean_name and "\n" not in clean_name
            and "\r" not in clean_value and "\n" not in clean_value
        ):
            request_headers[clean_name] = clean_value
    request = urllib.request.Request(str(url), headers=request_headers)
    try:
        with _OPENER.open(request, timeout=_timeout()) as response:
            status = int(getattr(response, "status", 200))
            if status not in (200, 206):
                return None, f"HTTP_ERROR:{status}"
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                return None, "RESPONSE_TOO_LARGE"
            response_headers = {
                str(key).lower(): str(value)
                for key, value in response.headers.items()
            }
            if "gzip" in response_headers.get("content-encoding", "").lower():
                raw = gzip.decompress(raw)
                if len(raw) > MAX_RESPONSE_BYTES:
                    return None, "DECOMPRESSED_RESPONSE_TOO_LARGE"
            charset = "utf-8"
            match = re.search(
                r"charset=([A-Za-z0-9._-]+)",
                response_headers.get("content-type", ""),
                re.IGNORECASE,
            )
            if match:
                charset = match.group(1)
            return raw.decode(charset, errors="replace"), None
    except urllib.error.HTTPError as exc:
        return None, f"HTTP_ERROR:{int(exc.code)}"
    except Exception as exc:
        return None, f"{type(exc).__name__}:{str(exc)[:120]}"

def _fetch_provider_post_text(
    url: str,
    headers: Optional[Dict[str, str]] = None,
) -> Tuple[Optional[str], Optional[str]]:
    parsed = urllib.parse.urlparse(str(url or "").strip())
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        return None, "INVALID_URL"

    request_headers = {
        "User-Agent": str((headers or {}).get("User-Agent") or zona_user_agent()),
        "Accept": "application/json,text/plain;q=0.9,*/*;q=0.8",
        "Accept-Encoding": "gzip",
    }
    for name, value in (headers or {}).items():
        if str(name).lower() == "user-agent":
            continue
        request_headers[str(name)] = str(value)
    request = urllib.request.Request(
        str(url),
        headers=request_headers,
        method="POST",
    )
    try:
        with _OPENER.open(request, timeout=_timeout()) as response:
            status = int(getattr(response, "status", 200))
            if status not in (200, 206):
                return None, f"HTTP_ERROR:{status}"
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                return None, "RESPONSE_TOO_LARGE"
            response_headers = {
                str(key).lower(): str(value)
                for key, value in response.headers.items()
            }
            if "gzip" in response_headers.get("content-encoding", "").lower():
                raw = gzip.decompress(raw)
                if len(raw) > MAX_RESPONSE_BYTES:
                    return None, "DECOMPRESSED_RESPONSE_TOO_LARGE"
            charset = "utf-8"
            match = re.search(
                r"charset=([A-Za-z0-9._-]+)",
                response_headers.get("content-type", ""),
                re.IGNORECASE,
            )
            if match:
                charset = match.group(1)
            return raw.decode(charset, errors="replace"), None
    except urllib.error.HTTPError as exc:
        return None, f"HTTP_ERROR:{int(exc.code)}"
    except Exception as exc:
        return None, f"{type(exc).__name__}:{str(exc)[:120]}"

def _fetch_provider_post_form_text(
    url: str,
    headers: Optional[Dict[str, str]] = None,
    form: Optional[Dict[str, str]] = None,
) -> Tuple[Optional[str], Optional[str]]:
    parsed = urllib.parse.urlparse(str(url or "").strip())
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        return None, "INVALID_URL"

    fields: Dict[str, str] = {}
    for name, value in (form or {}).items():
        clean_name = str(name or "").strip()
        clean_value = str(value or "")
        if (
            clean_name and len(clean_name) <= 128 and len(clean_value) <= 4096
            and "\r" not in clean_name and "\n" not in clean_name
        ):
            fields[clean_name] = clean_value
    if len(fields) > 64:
        return None, "FORM_TOO_LARGE"
    body = urllib.parse.urlencode(fields).encode("utf-8")
    if len(body) > 64 * 1024:
        return None, "FORM_TOO_LARGE"

    request_headers = {
        "User-Agent": str((headers or {}).get("User-Agent") or zona_user_agent()),
        "Accept": "application/json,text/plain;q=0.9,*/*;q=0.8",
        "Accept-Encoding": "gzip",
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
    }
    for name, value in (headers or {}).items():
        if str(name).lower() == "user-agent":
            continue
        clean_name = str(name).strip()
        clean_value = str(value).strip()
        if (
            clean_name and clean_value
            and len(clean_name) <= 128 and len(clean_value) <= 4096
            and "\r" not in clean_name and "\n" not in clean_name
            and "\r" not in clean_value and "\n" not in clean_value
        ):
            request_headers[clean_name] = clean_value

    request = urllib.request.Request(
        str(url),
        data=body,
        headers=request_headers,
        method="POST",
    )
    try:
        with _OPENER.open(request, timeout=_timeout()) as response:
            status = int(getattr(response, "status", 200))
            if status not in (200, 206):
                return None, f"HTTP_ERROR:{status}"
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                return None, "RESPONSE_TOO_LARGE"
            response_headers = {
                str(key).lower(): str(value)
                for key, value in response.headers.items()
            }
            if "gzip" in response_headers.get("content-encoding", "").lower():
                raw = gzip.decompress(raw)
                if len(raw) > MAX_RESPONSE_BYTES:
                    return None, "DECOMPRESSED_RESPONSE_TOO_LARGE"
            charset = "utf-8"
            match = re.search(
                r"charset=([A-Za-z0-9._-]+)",
                response_headers.get("content-type", ""),
                re.IGNORECASE,
            )
            if match:
                charset = match.group(1)
            return raw.decode(charset, errors="replace"), None
    except urllib.error.HTTPError as exc:
        return None, f"HTTP_ERROR:{int(exc.code)}"
    except Exception as exc:
        return None, f"{type(exc).__name__}:{str(exc)[:120]}"


def _fetch_streams_for_source(
    source: Dict[str, Any],
    season: Optional[int],
    episode: Optional[int],
    episode_key: Optional[str],
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    key = _source_key(source)
    extractor = _extractor_id(source)
    if not key or extractor is None:
        return [], "SOURCE_REF_INCOMPLETE"

    # The old APK dispatches this source type to a local provider class. There
    # is no generic stream mirror route; unsupported adapters must be explicit.
    streams, error = resolve_local_source(
        source,
        fetch_json=_fetch_provider_json,
        fetch_text=_fetch_provider_text,
        fetch_post_text=_fetch_provider_post_text,
        fetch_post_form_text=_fetch_provider_post_form_text,
        client_time=_client_time(),
        request_user_agent=zona_user_agent(),
        season=season,
        episode=episode,
    )
    if streams:
        return streams, None
    return [], error or "ADAPTER_NO_STREAMS"


def _client_time() -> str:
    # Callers synchronize the cached offset at the protected-operation
    # boundary. Keep this helper pure so it can be reused by request builders
    # and deterministic tests without hidden network I/O.
    return f"{_effective_time_ms()}.083"

def _server_time_offset_ms() -> int:
    global _TIME_OFFSET_MS, _TIME_EXPIRES_AT
    with _TIME_LOCK:
        if time.monotonic() < _TIME_EXPIRES_AT:
            return _TIME_OFFSET_MS
    for base in _mirrors("time_mirrors"):
        raw, headers, error = _request(base, "getTime")
        if error:
            continue
        date_header = headers.get("date")
        if not date_header:
            continue
        try:
            server_seconds = parsedate_to_datetime(date_header).timestamp()
            offset = int(server_seconds * 1000 - time.time() * 1000)
        except Exception:
            continue
        with _TIME_LOCK:
            _TIME_OFFSET_MS = offset
            _TIME_EXPIRES_AT = time.monotonic() + 300.0
        return offset
    with _TIME_LOCK:
        _TIME_EXPIRES_AT = time.monotonic() + 30.0
    return 0

def _refresh_server_time() -> None:
    _server_time_offset_ms()

def _max_extractor_workers() -> int:
    configured = os.environ.get("ZONA_MAX_EXTRACTORS")
    if configured is None:
        configured = _config().get("max_extractors", 6)
    try:
        return min(max(int(configured), 1), 12)
    except (TypeError, ValueError):
        return 6


def resolve_zona_source_refs(
    provider_id: Any,
    sources: Sequence[Dict[str, Any]],
    season: Optional[int] = None,
    episode: Optional[int] = None,
    episode_key: Optional[str] = None,
    *,
    force_refresh: bool = False,
    catalog_media_id: Any = None,
    canonical_title: Optional[str] = None,
    canonical_original_title: Optional[str] = None,
    canonical_year: Optional[int] = None,
    canonical_media_type: Optional[str] = None,
) -> ZonaLookup:
    _refresh_server_time()
    errors: List[str] = []
    valid_refs = 0
    work: List[Tuple[int, Dict[str, Any], Optional[str]]] = []
    for index, raw_source in enumerate(sources):
        if not isinstance(raw_source, dict):
            continue
        source = _nested_item(raw_source)
        if not _source_key(source) or _extractor_id(source) is None:
            continue
        valid_refs += 1
        effective_episode_key = _episode_key_for_request(
            season,
            episode,
            episode_key or _first(source, ("episodeKey", "episode_key")),
        )
        work.append((index, source, effective_episode_key))

    # Zona fans out independent extractors and aggregates their answers. A
    # bounded pool keeps provider load predictable and isolates one failure
    # from the other variants; sorting by source order keeps API results stable.
    collected: Dict[int, Tuple[List[Dict[str, Any]], Optional[str]]] = {}
    if work:
        with ThreadPoolExecutor(
            max_workers=min(_max_extractor_workers(), len(work)),
            thread_name_prefix="zona-extractor",
        ) as pool:
            futures = {
                pool.submit(
                    _fetch_streams_for_source,
                    source,
                    season,
                    episode,
                    effective_episode_key,
                ): index
                for index, source, effective_episode_key in work
            }
            for future in as_completed(futures):
                index = futures[future]
                try:
                    collected[index] = future.result()
                except Exception as exc:
                    collected[index] = (
                        [],
                        f"EXTRACTOR_ERROR:{type(exc).__name__}:{str(exc)[:120]}",
                    )

    all_streams: List[Dict[str, Any]] = []
    for index in sorted(collected):
        streams, error = collected[index]
        all_streams.extend(streams)
        if error:
            errors.append(error)

    streams = sanitize_streams(all_streams, require_source=True)
    # Preserve the provider content identity and the exact VideoSource
    # identity alongside the concrete locator. This is metadata, not a
    # substitute for the source key: every URL still came from the extractor
    # contract above and is validated before it crosses the boundary.
    if streams and provider_id is not None:
        provider_content_id = str(provider_id).strip()
        if provider_content_id:
            for stream in streams:
                stream.setdefault("provider_id", provider_content_id)
                stream.setdefault("provider_content_id", provider_content_id)
    if streams and canonical_title:
        streams = bind_stream_identity(
            streams,
            catalog_media_id=catalog_media_id,
            title=canonical_title,
            original_title=canonical_original_title,
            year=canonical_year,
            media_type=canonical_media_type,
            season=season,
            episode=episode,
        )
    if streams:
        return ZonaLookup("OK", streams, source_refs=valid_refs, errors=errors[:12])
    if valid_refs == 0:
        return ZonaLookup("NO_RESULTS", [], source_refs=0, errors=errors[:12])
    return ZonaLookup(
        "PROVIDER_ERROR" if errors else "NO_RESULTS",
        [],
        source_refs=valid_refs,
        errors=errors[:12],
    )


def resolve_zona_for_title(
    title: str,
    expected_titles: Optional[Sequence[str]] = None,
    year: Optional[int] = None,
    media_type: Optional[str] = None,
    season: Optional[int] = None,
    episode: Optional[int] = None,
    episode_key: Optional[str] = None,
    *,
    force_refresh: bool = False,
) -> ZonaLookup:
    clean_title = str(title or "").strip()
    expected = [str(value).strip() for value in (expected_titles or [clean_title]) if str(value).strip()]
    if not clean_title or not expected:
        return ZonaLookup("NO_RESULTS")
    effective_episode_key = _episode_key_for_request(season, episode, episode_key)
    suggestions, suggestion_errors = _fetch_suggestions(
        clean_title, expected, year, media_type, season
    )
    if not suggestions:
        return ZonaLookup(
            "PROVIDER_ERROR" if suggestion_errors else "NO_RESULTS",
            errors=suggestion_errors[:12],
        )
    # A title/year/type lookup is a proof step, not a provider preference. If
    # more than one canonical provider item survives exact matching, refusing
    # the request is safer than selecting the first result.
    if len(suggestions) != 1:
        return ZonaLookup(
            "AMBIGUOUS",
            suggestions=len(suggestions),
            errors=(suggestion_errors + ["EXACT_PROVIDER_ID_AMBIGUOUS"])[:12],
        )

    all_streams: List[Dict[str, Any]] = []
    errors = list(suggestion_errors)
    source_refs = 0
    for suggestion in suggestions:
        provider_id = suggestion.get("_provider_id")
        source_types = suggestion.get("movieSourceTypes", suggestion.get("movie_source_types"))
        sources, source_errors = _fetch_video_sources(
            provider_id,
            effective_episode_key,
            movie_source_types=source_types if isinstance(source_types, (str, list, tuple)) else None,
            trailer=False,
            force_refresh=force_refresh,
        )
        errors.extend(source_errors)
        source_refs += sum(
            1 for source in sources
            if _source_key(_nested_item(source)) and _extractor_id(_nested_item(source)) is not None
        )
        lookup = resolve_zona_source_refs(
            provider_id,
            sources,
            season=season,
            episode=episode,
            episode_key=effective_episode_key,
            force_refresh=force_refresh,
            canonical_title=clean_title,
            canonical_original_title=next(
                (value for value in expected if value.casefold() != clean_title.casefold()),
                None,
            ),
            canonical_year=year,
            canonical_media_type=media_type,
        )
        all_streams.extend(lookup.streams)
        errors.extend(lookup.errors)
    streams = sanitize_streams(all_streams, require_source=True)
    if streams:
        return ZonaLookup("OK", streams, suggestions=len(suggestions), source_refs=source_refs, errors=errors[:12])
    return ZonaLookup(
        "PROVIDER_ERROR" if errors else "NO_RESULTS",
        [],
        suggestions=len(suggestions),
        source_refs=source_refs,
        errors=errors[:12],
    )

def contract_probe() -> Dict[str, Any]:
    """Read-only diagnostics; no stream key or provider response is returned."""
    offset = _server_time_offset_ms()
    value = _client_time()
    return {
        "user_agent": zona_user_agent(),
        "api_mirrors": _mirrors("api_mirrors"),
        "stream_mirrors": _mirrors("stream_mirrors"),
        "time_offset_ms": offset,
        "client_time_format_valid": value.rsplit(".", 1)[-1] == "083" and value.split(".", 1)[0].isdigit(),
    }
