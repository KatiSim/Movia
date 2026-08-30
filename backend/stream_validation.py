#!/usr/bin/env python3
"""Shared validation for playback sources exposed by the Movia backend.

The catalog historically contained synthetic magnet URIs.  This module keeps
catalog/API/cache/runtime validation consistent so metadata cannot masquerade
as a playable source.
"""
from __future__ import annotations

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


def is_valid_stream_url(url: Optional[str]) -> bool:
    if not url or not isinstance(url, str):
        return False
    value = url.strip()
    if not value:
        return False
    if value.lower().startswith("magnet:?"):
        return is_valid_magnet(value)
    if value.lower().startswith(("http://", "https://")):
        if is_test_stream_url(value):
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
        if variant_key in seen_variants:
            continue
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
            "title", "season", "episode", "stream_type", "mime_type",
            "stream_id", "streamId", "provider_item_id", "providerItemId",
            "info_hash", "infoHash", "file_index", "fileIndex",
            "file_path", "filePath", "provider", "provider_id", "providerId",
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
        result.append(cleaned)
    return result
