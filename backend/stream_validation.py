#!/usr/bin/env python3
"""Shared validation for playback sources exposed by the Movia backend.

The catalog historically contained synthetic magnet URIs.  This module keeps
catalog/API/cache/runtime validation consistent so metadata cannot masquerade
as a playable source.
"""
from __future__ import annotations

import hashlib
import math
import re
from typing import Any, Dict, Iterable, List, Optional
from urllib.parse import parse_qsl, urlparse

_TEST_STREAM_PATTERNS = (
    "devstreaming-cdn.apple.com",
    "storage.googleapis.com",
    "bipbop",
    "bigbuckbunny",
    "exoplayer-test-media",
)

_BTih_RE = re.compile(r"(?:^|[?&])xt=urn:btih:([^&\s]+)", re.IGNORECASE)
_HEX40_RE = re.compile(r"^[0-9a-fA-F]{40}$")
_BASE32_RE = re.compile(r"^[A-Z2-7]{32}$", re.IGNORECASE)


def extract_btih(url: Optional[str]) -> Optional[str]:
    if not url or not isinstance(url, str):
        return None
    match = _BTih_RE.search(url.strip())
    return match.group(1).strip() if match else None


def is_valid_btih(value: Optional[str]) -> bool:
    if not value:
        return False
    value = value.strip()
    return bool(_HEX40_RE.fullmatch(value) or _BASE32_RE.fullmatch(value))


def is_valid_magnet(url: Optional[str]) -> bool:
    if not url or not isinstance(url, str):
        return False
    value = url.strip()
    if not value.lower().startswith("magnet:?"):
        return False
    return is_valid_btih(extract_btih(value))


def is_test_stream_url(url: Optional[str]) -> bool:
    if not url:
        return False
    low = str(url).lower()
    return any(pattern in low for pattern in _TEST_STREAM_PATTERNS)


def is_placeholder_stream_url(url: Optional[str]) -> bool:
    """Reject the known Zona invalid-key placeholder, never a playable stream."""
    if not url or not isinstance(url, str):
        return False
    try:
        parsed = urlparse(url.strip())
        host = (parsed.hostname or "").casefold()
        path = (parsed.path or "").rstrip("/").casefold()
        return host == "dlcache4.vibio.tv" and path == "/direct/out60.mp4"
    except Exception:
        return False


def is_valid_stream_url(url: Optional[str]) -> bool:
    if not url or not isinstance(url, str):
        return False
    value = url.strip()
    if not value:
        return False
    if value.lower().startswith("magnet:?"):
        return is_valid_magnet(value)
    if value.lower().startswith(("http://", "https://")):
        if is_test_stream_url(value) or is_placeholder_stream_url(value):
            return False
        try:
            parsed = urlparse(value)
            return parsed.scheme in ("http", "https") and bool(parsed.hostname)
        except Exception:
            return False
    return False


def _first_text(raw: Dict[str, Any], *keys: str) -> str:
    """Return the first non-empty field without treating numeric zero as empty."""
    for key in keys:
        value = raw.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def _variant_text(value: Any) -> str:
    """Normalize text only for identity comparisons, never for API output."""
    return re.sub(r"\s+", " ", str(value or "").strip()).casefold()


def canonical_stream_locator(url: str) -> str:
    """Return the stable locator used for stream identity and deduplication.

    Magnet tracker parameters and display names are mutable metadata. The
    BTIH plus optional file-selection parameters identifies the content, while
    HTTP URLs remain exact because signed query parameters may identify a
    different playable object.
    """
    value = str(url or "").strip()
    if not value.lower().startswith("magnet:?"):
        return value

    btih = extract_btih(value)
    if not btih:
        return value

    try:
        selections = []
        for key, item_value in parse_qsl(urlparse(value).query, keep_blank_values=True):
            if key.lower() in {"so", "fl"}:
                selections.append((key.lower(), item_value.strip()))
        selection_text = "&".join(
            f"{key}={item_value}" for key, item_value in sorted(selections)
        )
    except Exception:
        selection_text = ""

    base = f"magnet:btih:{btih.strip().lower()}"
    return f"{base}|{selection_text}" if selection_text else base


def stream_variant_key(raw: Dict[str, Any], url: Optional[str] = None) -> tuple:
    """Identify equivalent records while retaining voice/quality variants."""
    clean_url = str(url or raw.get("url") or raw.get("playback_url") or "").strip()
    return (
        canonical_stream_locator(clean_url),
        _variant_text(_first_text(raw, "voice", "translation") or "Не указано"),
        _variant_text(_first_text(raw, "quality") or "Не указано"),
        _variant_text(_first_text(raw, "season")),
        _variant_text(_first_text(raw, "episode")),
        _variant_text(_first_text(raw, "file_index", "fileIndex")),
        _variant_text(_first_text(raw, "file_path", "filePath")),
    )


def stable_stream_id(raw: Dict[str, Any], url: Optional[str] = None) -> str:
    """Return a deterministic public ID for one normalized stream variant."""
    key = stream_variant_key(raw, url)
    identity = "\x1f".join(str(part) for part in key)
    return "stream:" + hashlib.sha256(identity.encode("utf-8")).hexdigest()[:24]




# StreamInfo-compatible, non-secret metadata retained across provider, cache,
# catalog, and API boundaries.  Headers are deliberately allowlisted so a
# provider cannot leak cookies, bearer tokens, API keys, or other credentials.
_SAFE_HEADER_NAMES = frozenset({
    "accept",
    "accept-language",
    "cache-control",
    "content-type",
    "if-modified-since",
    "if-none-match",
    "origin",
    "range",
    "referer",
    "user-agent",
    "x-requested-with",
    "sec-fetch-dest",
    "sec-fetch-mode",
    "sec-fetch-site",
})

def _safe_http_headers(value: Any) -> Dict[str, str]:
    if not isinstance(value, dict):
        return {}
    result: Dict[str, str] = {}
    for raw_name, raw_value in value.items():
        name = str(raw_name or "").strip()
        if name.casefold() not in _SAFE_HEADER_NAMES:
            continue
        text = str(raw_value or "").strip()
        if not text or "\r" in text or "\n" in text or len(text) > 2048:
            continue
        result[name] = text
    return result



def _safe_download_url(value: Any) -> str:
    url = str(value or "").strip()
    if not url or len(url) > 4096 or not is_valid_stream_url(url):
        return ""
    return url


def _safe_skip_intervals(value: Any) -> List[Dict[str, int]]:
    if not isinstance(value, (list, tuple)):
        return []
    result: List[Dict[str, int]] = []
    for raw in list(value)[:32]:
        if isinstance(raw, dict):
            start_raw = raw.get("start", raw.get("start_ms", raw.get("startMs", raw.get("from"))))
            end_raw = raw.get("end", raw.get("end_ms", raw.get("endMs", raw.get("to"))))
        elif isinstance(raw, (list, tuple)) and len(raw) >= 2:
            start_raw, end_raw = raw[0], raw[1]
        else:
            continue
        try:
            start = float(start_raw)
            end = float(end_raw)
        except (TypeError, ValueError, OverflowError):
            continue
        if (
            not math.isfinite(start)
            or not math.isfinite(end)
            or start < 0
            or end < start
            or end > 86_400_000
        ):
            continue
        result.append({"start": int(start), "end": int(end)})
    return result


_RELOAD_SECRET_KEYS = frozenset({
    "token", "access_token", "authorization", "auth", "cookie", "password",
    "secret", "signature", "sig", "key", "private_key", "privatekey",
})


def _is_secret_key(value: Any) -> bool:
    normalized = re.sub(r"[^a-z0-9]", "", str(value or "").casefold())
    return normalized in {
        "token", "accesstoken", "refreshtoken", "authorization", "auth",
        "cookie", "password", "secret", "signature", "sig", "key",
        "privatekey", "apikey", "xapikey",
    } or any(marker in normalized for marker in (
        "accesstoken", "authorization", "privatekey", "apikey",
    ))


def _safe_reload_data(value: Any, *, depth: int = 0) -> Any:
    """Keep reload *shape* without persisting credentials or opaque secrets.

    Providers may return a small refresh descriptor (method, endpoint, static
    parameters). A bearer token, cookie, signature, or private key is
    session-only material and must not cross the catalog/cache boundary.
    """
    if depth > 3:
        return None
    if isinstance(value, dict):
        result: Dict[str, Any] = {}
        for raw_key, raw_value in list(value.items())[:32]:
            key = str(raw_key or "").strip()
            if not key or key.casefold().replace("-", "_") in _RELOAD_SECRET_KEYS or _is_secret_key(key):
                continue
            clean_value = _safe_reload_data(raw_value, depth=depth + 1)
            if clean_value not in (None, "", [], {}):
                result[key[:80]] = clean_value
        return result
    if isinstance(value, (list, tuple)):
        return [item for item in (_safe_reload_data(item, depth=depth + 1) for item in list(value)[:32])
                if item not in (None, "", [], {})]
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) and math.isfinite(float(value)):
        return value
    if isinstance(value, str):
        clean = value.strip()
        if not clean or len(clean) > 512 or any(ord(char) < 32 for char in clean):
            return None
        if re.search(r"(?i)(?:token|authorization|cookie|password|secret|signature|sig|apikey|key)\s*[=:]", clean):
            return None
        return clean
    return None


def bind_stream_identity(
    streams: Any,
    *,
    catalog_media_id: Any,
    title: Any,
    original_title: Any = None,
    year: Any = None,
    media_type: Any = None,
    season: Optional[int] = None,
    episode: Optional[int] = None,
) -> List[Dict[str, Any]]:
    """Attach the canonical catalog identity to already-sanitized streams.

    This is deliberately an additive annotation. It does not turn a stream
    into a source: URL validation and source validation still happen in
    ``sanitize_streams``. The annotation lets the Android boundary reject a
    candidate that came from a different card even when its URL is valid.
    """
    clean = sanitize_streams(streams, require_source=True)
    media_id = str(catalog_media_id or "").strip()
    canonical_title = str(title or "").strip()
    canonical_original = str(original_title or "").strip()
    try:
        canonical_year = int(year) if year is not None else 0
    except (TypeError, ValueError, OverflowError):
        canonical_year = 0
    canonical_type = str(media_type or "").strip().casefold()
    result: List[Dict[str, Any]] = []
    for raw in clean:
        item = dict(raw)
        if media_id:
            item["catalog_media_id"] = media_id
        if canonical_title:
            item["canonical_title"] = canonical_title
        if canonical_original:
            item["canonical_original_title"] = canonical_original
        if canonical_year > 0:
            item["canonical_year"] = canonical_year
        if canonical_type:
            item["canonical_media_type"] = canonical_type
        if season is not None:
            item["season"] = int(season)
        if episode is not None:
            item["episode"] = int(episode)
        result.append(item)
    return result


def _safe_subtitle_list(value: Any) -> List[Dict[str, Any]]:
    if not isinstance(value, list):
        return []
    result: List[Dict[str, Any]] = []
    for raw in value[:16]:
        if isinstance(raw, str):
            url = raw.strip()
            if (
                url.lower().startswith(("http://", "https://"))
                and is_valid_stream_url(url)
            ):
                result.append({"url": url})
            continue
        if not isinstance(raw, dict):
            continue
        item: Dict[str, Any] = {}
        for key in ("language", "lang", "label", "name", "mime_type", "mimeType", "codec"):
            value_text = str(raw.get(key) or "").strip()
            if value_text and len(value_text) <= 200 and "\r" not in value_text and "\n" not in value_text:
                item[key] = value_text
        raw_url = raw.get("url") or raw.get("uri") or raw.get("src")
        url = str(raw_url or "").strip()
        if (
            url
            and url.lower().startswith(("http://", "https://"))
            and is_valid_stream_url(url)
        ):
            item["url"] = url
        if item:
            result.append(item)
    return result


def _safe_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    return str(value or "").strip().casefold() in {"1", "true", "yes", "on"}


def _safe_int(value: Any) -> Optional[int]:
    try:
        parsed = int(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return max(0, parsed)


def sanitize_streams(
    streams: Any,
    *,
    require_source: bool = True,
) -> List[Dict[str, Any]]:
    """Return deduplicated playback candidates that pass structural checks.

    ``require_source`` is intentionally true for persisted catalog/cache data:
    historical synthetic entries have no source marker. Runtime provider
    results also carry a source and therefore pass the same rule.
    """
    if not isinstance(streams, list):
        return []

    result: List[Dict[str, Any]] = []
    seen_variants = set()
    result_indexes: Dict[tuple, int] = {}
    for raw in streams:
        if not isinstance(raw, dict):
            continue
        url = str(raw.get("url") or raw.get("playback_url") or "").strip()
        source = str(raw.get("source") or raw.get("source_id") or "").strip()
        if require_source and not source:
            continue
        if not is_valid_stream_url(url):
            continue

        variant_key = stream_variant_key(raw, url)
        duplicate_index = result_indexes.get(variant_key)
        if duplicate_index is None:
            seen_variants.add(variant_key)
        try:
            seeders = max(0, int(raw.get("seeders") or raw.get("seeds") or 0))
        except (TypeError, ValueError):
            seeders = 0

        cleaned: Dict[str, Any] = {
            "source": source or "unknown",
            "voice": str(raw.get("voice") or raw.get("translation") or "Не указано").strip() or "Не указано",
            "quality": str(raw.get("quality") or "Не указано").strip() or "Не указано",
            "seeders": seeders,
            "url": url,
        }
        for key in (
            "title", "season", "episode", "stream_type", "streamType",
            "mime_type", "mimeType", "source_id", "sourceId",
            "stream_id", "streamId", "provider_item_id", "providerItemId",
            "info_hash", "infoHash", "file_index", "fileIndex",
            "file_path", "filePath", "provider", "provider_id", "providerId",
            "catalog_media_id", "catalogMediaId", "canonical_title",
            "canonicalTitle", "canonical_original_title", "canonicalOriginalTitle",
            "canonical_year", "canonicalYear", "canonical_media_type",
            "canonicalMediaType", "transport",
        ):
            if key in raw and raw.get(key) is not None:
                cleaned[key] = raw.get(key)

        # Standard DRM metadata may cross the API boundary, but key material,
        # offline key-set IDs and embedded secrets are intentionally never copied.
        drm_scheme = str(raw.get("drm_scheme") or raw.get("drmScheme") or "").strip().lower()
        license_url = str(raw.get("license_url") or raw.get("drm_license_url") or raw.get("drmLicenseUrl") or "").strip()
        if drm_scheme in {"widevine", "com.widevine.alpha", "playready", "com.microsoft.playready", "clearkey", "org.w3.clearkey"}:
            cleaned["drm_scheme"] = drm_scheme
            if license_url.startswith("https://") or license_url.startswith("http://127.0.0.1:") or license_url.startswith("http://localhost:"):
                cleaned["license_url"] = license_url
        # Preserve the provider's useful StreamInfo dimensions while
        # dropping credential-bearing or opaque control fields.
        for canonical, aliases in (
            ("language", ("language", "lang")),
            ("source_type_id", ("source_type_id", "video_source_type_id", "videoSourceTypeId")),
            ("content_type_id", ("content_type_id", "video_content_type_id", "videoContentTypeId")),
            ("resolution", ("resolution", "video_resolution", "videoResolution")),
            ("codec", ("codec", "video_codec", "videoCodec")),
            ("subtitle_list", ("subtitle_list", "subtitleList", "subtitles")),
            ("is_use_internal_subtitles", ("is_use_internal_subtitles", "isUseInternalSubtitles")),
            ("unavailable_quality", ("unavailable_quality", "unavailableQuality")),
            ("is_trailer", ("is_trailer", "isTrailer")),
            ("video_track_index", ("video_track_index", "videoTrackIndex")),
            ("audio_track_index", ("audio_track_index", "audioTrackIndex")),
            ("advertisement", ("advertisement", "ad")),
            ("duration", ("duration",)),
            ("size", ("size",)),
            ("user_agent", ("user_agent", "userAgent")),
            ("headers", ("headers", "http_headers", "httpHeaders")),
            ("download_url", ("download_url", "downloadUrl")),
            ("download_headers", ("download_headers", "downloadHeaders")),
            ("skip_intervals", ("skip_intervals", "skipIntervals")),
            ("reload_data", ("reload_data", "reloadData")),
            ("reload_supported", ("reload_supported", "reloadSupported")),
            ("transport_metadata", ("transport_metadata", "transportMetadata")),
        ):
            value = None
            for alias in aliases:
                if alias in raw and raw.get(alias) is not None:
                    value = raw.get(alias)
                    break
            if value is None:
                continue
            if canonical == "subtitle_list":
                value = _safe_subtitle_list(value)
            elif canonical == "headers":
                value = _safe_http_headers(value)
            elif canonical == "download_url":
                value = _safe_download_url(value)
            elif canonical == "download_headers":
                value = _safe_http_headers(value)
            elif canonical == "skip_intervals":
                value = _safe_skip_intervals(value)
            elif canonical == "reload_data":
                value = _safe_reload_data(value)
            elif canonical in {"is_use_internal_subtitles", "is_trailer"}:
                value = _safe_bool(value)
            elif canonical in {"advertisement", "transport_metadata"}:
                value = _safe_reload_data(value)
            elif canonical == "reload_supported":
                value = _safe_bool(value)
            elif canonical in {
                "source_type_id", "content_type_id", "video_track_index",
                "audio_track_index", "duration", "size",
            }:
                value = _safe_int(value)
            elif canonical == "unavailable_quality":
                if isinstance(value, (bool, int, float)):
                    value = _safe_bool(value)
                elif isinstance(value, (list, tuple, set)):
                    value = [
                        str(item).strip()[:200]
                        for item in value
                        if str(item or "").strip()
                    ][:32]
                else:
                    value = str(value or "").strip()[:200]
            else:
                value = str(value or "").strip()[:512]
                if any(ord(char) < 32 for char in value):
                    value = ""
            if value not in (None, "", [], {}):
                cleaned[canonical] = value

        if duplicate_index is not None:
            # The same logical stream can arrive from several mirrors or
            # extractor callbacks. Retain the first identity, but fill only
            # missing metadata from later responses so headers, subtitles,
            # reload descriptors and provider IDs are not lost.
            existing = result[duplicate_index]
            for key, incoming_value in cleaned.items():
                if key not in existing or existing.get(key) in (None, "", [], {}):
                    if incoming_value not in (None, "", [], {}):
                        existing[key] = incoming_value
            continue

        existing_stream_id = _first_text(cleaned, "stream_id", "streamId")
        if existing_stream_id:
            cleaned["stream_id"] = existing_stream_id
            cleaned.pop("streamId", None)
        else:
            cleaned["stream_id"] = stable_stream_id(cleaned, url)

        result_indexes[variant_key] = len(result)
        result.append(cleaned)
    return result
