#!/usr/bin/env python3
"""Safe local adapters ported from the legacy Zona provider registry.

The old Zona APK resolves providers in-process.  It does not expose a
generic /getStreams/ service, so Movia dispatches source records to this
module instead of inventing a network route.

Only deterministic, non-secret behavior is included here.  Provider-specific
captcha bypasses, embedded keys, cookies, and opaque credentials are not
copied from the APK.  Unsupported registry entries fail explicitly until a
clean adapter is ported.
"""
from __future__ import annotations

import base64
import json
import re
import threading
import time
from html import unescape
from urllib.parse import quote, urljoin, urlparse
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple

from stream_validation import is_valid_stream_url
from zona_playback_architecture import (
    LegacyStreamBuilder,
    VideoSourceRef,
    ZONA_SOURCE_REGISTRY,
    extractor_label,
    source_capabilities,
)


# Extractor IDs and labels recovered from the old APK's source registry.
# Keeping the complete registry makes unsupported providers observable and
# prevents them from being mistaken for a missing or malformed source ref.
LEGACY_EXTRACTOR_REGISTRY: Dict[int, str] = {
    1: "mobilink",
    2: "hdrezka",
    3: "filmix",
    5: "bazon",
    6: "videocdn",
    7: "kinomania",
    8: "alloha",
    9: "awmzone",
    10: "bazon-czx",
    11: "ustore",
    12: "lordfilms",
    13: "kholobok",
    14: "kinoteatr",
    15: "kodik",
    16: "ru",
    17: "kinovod",
    19: "cdnmovies",
    20: "cdnmovies",
    21: "ivi-movie",
    22: "ivi-movie",
    23: "krasview",
    24: "zagonka",
    25: "zetflix",
    26: "kinoplay",
    27: "playep",
    28: "voidboost",
    29: "thefilm",
    30: "anwap",
    31: "vk",
    32: "takedwn",
    33: "cdnvideohub",
    34: "hdvb",
    35: "fancdn",
    36: "filmru",
    37: "videoframe2",
    38: "cloud-mail",
    39: "sooplive",
    40: "videoseed",
    41: "turbo",
    42: "rutube",
    43: "plvideo",
    44: "lomont",
    45: "veoveo",
    46: "ok",
    47: "flixcdn",
    48: "kinovibe",
    49: "link",
    50: "kinoton",
    51: "kinobadi",
    52: "fanserials",
    53: "videodb",
}

# Ported IDs are resolved in-process from the APK contracts. Types 2, 3, 6,
# and 8 additionally consume their live remote provider configuration rather
# than copying stale hosts or credentials into Movia. Type 49 is the
# deterministic LinkData adapter where download_link_key is directly playable.
PORTED_EXTRACTOR_IDS = frozenset({1, 2, 3, 6, 7, 8, 9, 14, 33, 35, 36, 39, 42, 43, 45, 46, 49, 51})

LEGACY_ADAPTER_BLOCKERS: Dict[int, str] = {
    5: "RECAPTCHA_RSA_AES_TRANSFORMER_REQUIRED",
    10: "BAZON_SHARED_BROWSER_CONTRACT_NOT_PORTED",
}

# Every registry entry is classified.  The reference APK contains provider
# classes for these IDs, but a class name alone is not an authorized,
# reproducible playback contract.  Keep the distinction explicit so an
# unported provider cannot fall through to a guessed endpoint or URL.
DOCUMENTED_BLOCKER_DETAILS: Dict[int, str] = {
    5: "RECAPTCHA_RSA_AES_TRANSFORMER_REQUIRED",
    10: "BAZON_SHARED_BROWSER_CONTRACT_NOT_PORTED",
    11: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    12: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    13: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    15: "PROVIDER_SESSION_CONTRACT_UNAVAILABLE",
    16: "PROVIDER_AUTH_CONTRACT_UNAVAILABLE",
    17: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    19: "SHARED_PROVIDER_CONTRACT_UNAVAILABLE",
    20: "SHARED_PROVIDER_CONTRACT_UNAVAILABLE",
    21: "PROTECTED_PROVIDER_CONTRACT_UNAVAILABLE",
    22: "PROTECTED_PROVIDER_CONTRACT_UNAVAILABLE",
    23: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    24: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    25: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    26: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    27: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    28: "PROVIDER_SESSION_CONTRACT_UNAVAILABLE",
    29: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    30: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    31: "ACCOUNT_OR_AUTH_CONTRACT_UNAVAILABLE",
    32: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    34: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    37: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    38: "ACCOUNT_OR_SESSION_CONTRACT_UNAVAILABLE",
    40: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    41: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    44: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    47: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    48: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    50: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
    52: "ACCOUNT_OR_SESSION_CONTRACT_UNAVAILABLE",
    53: "NO_SAFE_ADAPTER_CONTRACT_OR_FIXTURE",
}

EXTRACTOR_STATUS: Dict[int, str] = {
    extractor_id: (
        "IMPLEMENTED" if extractor_id in PORTED_EXTRACTOR_IDS
        else "DOCUMENTED_BLOCKER"
    )
    for extractor_id in LEGACY_EXTRACTOR_REGISTRY
}


def extractor_status(extractor_id: Optional[int]) -> str:
    """Return the finite status vocabulary for one registry entry."""
    if extractor_id not in EXTRACTOR_STATUS:
        return "UNREGISTERED"
    return EXTRACTOR_STATUS[int(extractor_id)]


def extractor_registry_status() -> List[Dict[str, Any]]:
    """Expose a secret-free, deterministic registry audit for tests/reports."""
    return [
        {
            "id": extractor_id,
            "name": name,
            "provider_label": ZONA_SOURCE_REGISTRY.get(extractor_id, name),
            "status": EXTRACTOR_STATUS[extractor_id],
            "blocker": DOCUMENTED_BLOCKER_DETAILS.get(extractor_id),
            "capabilities": source_capabilities(extractor_id),
        }
        for extractor_id, name in sorted(LEGACY_EXTRACTOR_REGISTRY.items())
    ]
KINOTEATR_BASE_URL = "https://www.kino-teatr.ru"
PLVIDEO_API_BASE_URL = "https://api.g1.plvideo.ru/v1/videos"
PLVIDEO_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/137.0.0.0 Safari/537.36"
)
VEOVEO_BASE_URL = "https://api.rstprgapipt.com"
VEOVEO_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/138.0.0.0 Safari/537.36"
)
RUTUBE_API_BASE_URL = "https://rutube.ru/api/play/options"
RUTUBE_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36"
)
SOOPLIVE_BASE_URL = "https://api.m.sooplive.co.kr"
SOOPLIVE_API_PATH = "/station/video/a/view"
SOOPLIVE_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36"
)

FANCDN_BASE_URL = "https://fancdn.net"
FANCDN_CONFIG_PATH = "/static/ext35.txt"
FANCDN_DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36"
)

CDNVIDEOHUB_BASE_URL = "https://plapi.cdnvideohub.com"
CDNVIDEOHUB_PLAYLIST_PATH = "/api/v1/player/sv/playlist"
CDNVIDEOHUB_FALLBACK_PATH = "/api/v1/player/sv/video/fallback"
CDNVIDEOHUB_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/138.0.0.0 Safari/537.36"
)
CDNVIDEOHUB_PROFILES = (
    ("mpeg4kUrl", "2160p"),
    ("mpeg2kUrl", "1440p"),
    ("mpegFullHdUrl", "1080p"),
    ("mpegHighUrl", "720p"),
    ("mpegMediumUrl", "480p"),
    ("mpegLowUrl", "360p"),
    ("mpegLowestUrl", "240p"),
    ("mpegTinyUrl", "144p"),
)

HDREZKA_BASE_URL = "https://hdrezka.ag"
HDREZKA_CONFIG_PATH = "/static/ext2.txt"
HDREZKA_AJAX_PATH = "/ajax/get_cdn_series/?t="
HDREZKA_CONFIG_MIRRORS = (
    "https://vsr01.zonasearch.com",
    "https://vsw01.zonasearch.com",
)
HDREZKA_DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/138.0.0.0 Safari/537.36"
)
HDREZKA_DECODER_SEPARATOR = "//_//"
HDREZKA_DECODER_MARKERS = (
    "$$#!!@#!@##",
    "^^^!@##!!##",
    "####^!!##!@@",
    "@@@@@!##!^^^",
    "$$!!@$$@^!@#$$@",
)

FILMIX_BASE_URLS = (
    "https://filmix.ac",
    "http://filmixapp.cyou",
)
FILMIX_CONFIG_PATH = "/static/ext3.txt"
FILMIX_PLAYER_PATH = "/api/movies/player-data?t="
FILMIX_DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/138.0.0.0 Safari/537.36"
)

VIDEOCDN_CONFIG_MIRRORS = (
    "https://vsr01.zonasearch.com",
    "https://vsw01.zonasearch.com",
)
VIDEOCDN_CONFIG_PATH = "/static/ext6.txt"
VIDEOCDN_DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/140.0.0.0 Safari/537.36"
)
_VIDEOCDN_CONFIG_LOCK = threading.RLock()
_VIDEOCDN_CONFIG_CACHE: Dict[str, Any] = {}
_VIDEOCDN_CONFIG_EXPIRES_AT = 0.0
_VIDEOCDN_CONFIG_TTL_SECONDS = 300.0

ALLOHA_CONFIG_MIRRORS = (
    "https://vsr01.zonasearch.com",
    "https://vsw01.zonasearch.com",
)
ALLOHA_CONFIG_PATH = "/static/ext8.txt"
ALLOHA_ENDPOINT_MAP_PATH = "/static/ext0.txt"
ALLOHA_DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/148.0.0.0 Safari/537.36"
)
_ALLOHA_CONFIG_LOCK = threading.RLock()
_ALLOHA_CONFIG_CACHE: Dict[str, Any] = {}
_ALLOHA_CONFIG_EXPIRES_AT = 0.0
_ALLOHA_ENDPOINT_CACHE: List[Tuple[str, str, str]] = []
_ALLOHA_ENDPOINT_EXPIRES_AT = 0.0
_ALLOHA_CONFIG_TTL_SECONDS = 300.0

AWMZONE_CONFIG_MIRRORS = (
    "https://vsr01.zonasearch.com",
    "https://vsw01.zonasearch.com",
)
AWMZONE_CONFIG_PATH = "/static/ext9.txt"
AWMZONE_ENDPOINT_MAP_PATH = "/static/ext0.txt"
AWMZONE_DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/149.0.0.0 Safari/537.36"
)
_AWMZONE_CONFIG_LOCK = threading.RLock()
_AWMZONE_CONFIG_CACHE: Dict[str, Any] = {}
_AWMZONE_CONFIG_EXPIRES_AT = 0.0
_AWMZONE_ENDPOINT_CACHE: List[str] = []
_AWMZONE_ENDPOINT_EXPIRES_AT = 0.0
_AWMZONE_CONFIG_TTL_SECONDS = 300.0
_HDREZKA_CONFIG_LOCK = threading.RLock()
_HDREZKA_CONFIG_CACHE: Dict[str, Any] = {}
_HDREZKA_CONFIG_EXPIRES_AT = 0.0
_HDREZKA_CONFIG_TTL_SECONDS = 300.0

KINOMANIA_BASE_URL = "https://www.kinomania.ru"
FILMRU_BASE_URL = "https://www.film.ru"
OK_BASE_URL = "https://ok.ru"
KINOBADI_BASE_URL = "https://vip.kinobadi.im"

LINK_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/145.0.0.0 Safari/537.36"
)

JsonFetcher = Callable[
    [str, Sequence[Tuple[str, str]]],
    Tuple[Optional[Any], Optional[str]],
]
TextFetcher = Callable[
    [str, Dict[str, str]],
    Tuple[Optional[str], Optional[str]],
]
PostFormFetcher = Callable[
    [str, Dict[str, str], Dict[str, str]],
    Tuple[Optional[str], Optional[str]],
]


def extractor_name(extractor_id: Optional[int]) -> str:
    if extractor_id is None:
        return "unknown"
    return LEGACY_EXTRACTOR_REGISTRY.get(int(extractor_id), f"extractor-{int(extractor_id)}")


def _first(raw: Dict[str, Any], keys: Tuple[str, ...]) -> Any:
    for key in keys:
        value = raw.get(key)
        if value is not None and str(value).strip():
            return value
    return None


def _extractor_id(source: Dict[str, Any]) -> Optional[int]:
    value = _first(source, (
        "extractor", "extractorId", "extractor_id",
        "videoSourceTypeId", "video_source_type_id",
        "sourceTypeId", "source_type_id", "typeId", "type_id",
    ))
    try:
        parsed = int(value)
        return parsed if parsed > 0 else None
    except (TypeError, ValueError):
        return None


def _source_key(source: Dict[str, Any]) -> Optional[str]:
    value = _first(source, (
        "downloadLinkKey", "download_link_key",
        "downloadKey", "download_key",
        "sourceKey", "source_key", "key",
    ))
    return str(value).strip() if value is not None else None


def _merged_source(source: Dict[str, Any]) -> Dict[str, Any]:
    merged = dict(source)
    info = source.get("info")
    if isinstance(info, str):
        try:
            info = json.loads(info)
        except (TypeError, ValueError):
            info = None
    if isinstance(info, dict):
        merged.update({key: value for key, value in info.items() if key not in merged})
    return merged


def _stream_metadata(
    source: Dict[str, Any],
    extractor: int,
    url: str,
    *,
    voice: str,
    language: str,
    quality: str,
    user_agent: str,
) -> Dict[str, Any]:
    source_id = _first(source, (
        "id", "sourceId", "source_id",
        "videoSourceId", "video_source_id",
    ))
    source_ref = VideoSourceRef.from_mapping(source)
    # Compatibility source records can omit or alias the numeric type. The
    # registry dispatch is authoritative at this point, so bind that exact ID.
    source_ref = VideoSourceRef(
        id=source_ref.id,
        video_source_type_id=int(extractor),
        video_content_type_id=source_ref.video_content_type_id,
        kinopoisk_id=source_ref.kinopoisk_id,
        download_link_key=source_ref.download_link_key,
        episode_key=source_ref.episode_key,
        info=source_ref.info,
    )
    provider_label = str(_first(source, (
        "name", "title", "sourceName", "source_name",
    )) or extractor_label(extractor))
    headers = {"User-Agent": user_agent} if user_agent else {}
    stream = LegacyStreamBuilder(
        video_source=source_ref,
        url=url,
        translation=voice or "Не указано",
        language=language or "",
        quality=quality or "MEDIUM",
        resolution=quality or "",
        headers=headers,
        provider_label=provider_label,
    ).to_stream_dict()
    # Preserve an opaque compatibility source ID when test/import data does not
    # obey the real long-valued serializer. Runtime APK data remains numeric.
    if source_id is not None:
        stream["provider_item_id"] = source_id
        stream["source_id"] = source_id
    content_type_id = _first(source, (
        "videoContentTypeId", "video_content_type_id",
        "contentTypeId", "content_type_id",
    ))
    if content_type_id is not None:
        stream["content_type_id"] = content_type_id
    return stream


def _hdrezka_safe_headers(value: Any) -> Dict[str, str]:
    result: Dict[str, str] = {}
    if not isinstance(value, dict):
        return result
    for raw_name, raw_value in value.items():
        name = str(raw_name or "").strip()
        text = str(raw_value or "").strip()
        if (
            not name
            or not text
            or len(name) > 128
            or len(text) > 2048
            or "\r" in name
            or "\n" in name
            or "\r" in text
            or "\n" in text
        ):
            continue
        result[name] = text
    return result


def _hdrezka_decode_inner_json(value: Any) -> Any:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        raw = bytearray(base64.b64decode(value.strip(), validate=False))
        for index in range(len(raw)):
            raw[index] ^= 59
        return json.loads(bytes(raw).decode("utf-8"))
    except Exception:
        return None


def _hdrezka_decode_config(text: str) -> Optional[Dict[str, Any]]:
    if not isinstance(text, str) or not text.strip():
        return None
    payload: Any = None
    raw_text = text.strip()
    try:
        payload = json.loads(base64.b64decode(raw_text, validate=False).decode("utf-8"))
    except Exception:
        try:
            payload = json.loads(raw_text)
        except Exception:
            return None
    if not isinstance(payload, dict):
        return None

    result: Dict[str, Any] = dict(payload)
    header_overrides = _hdrezka_decode_inner_json(payload.get("hoah"))
    host_pool = _hdrezka_decode_inner_json(payload.get("hl"))
    result["_headers"] = _hdrezka_safe_headers(header_overrides)
    result["_hosts"] = [
        str(item).strip()
        for item in (host_pool if isinstance(host_pool, list) else [])
        if str(item or "").strip() and "\r" not in str(item) and "\n" not in str(item)
    ][:32]
    account = payload.get("a")
    if not isinstance(account, dict):
        # Current deployments may version this optional block as ``a_``.
        account = payload.get("a_")
    result["_account"] = account if isinstance(account, dict) else {}
    return result


def _hdrezka_get_config(
    fetch_text: Optional[TextFetcher],
    request_user_agent: str,
) -> Dict[str, Any]:
    global _HDREZKA_CONFIG_CACHE, _HDREZKA_CONFIG_EXPIRES_AT
    now = time.monotonic()
    with _HDREZKA_CONFIG_LOCK:
        if _HDREZKA_CONFIG_CACHE and now < _HDREZKA_CONFIG_EXPIRES_AT:
            return dict(_HDREZKA_CONFIG_CACHE)
    if fetch_text is None:
        return {}

    headers = {"User-Agent": request_user_agent or HDREZKA_DEFAULT_USER_AGENT}
    for mirror in HDREZKA_CONFIG_MIRRORS:
        try:
            text, error = fetch_text(f"{mirror}{HDREZKA_CONFIG_PATH}", headers)
        except Exception:
            continue
        if error or not isinstance(text, str):
            continue
        decoded = _hdrezka_decode_config(text)
        if decoded is None:
            continue
        with _HDREZKA_CONFIG_LOCK:
            _HDREZKA_CONFIG_CACHE = dict(decoded)
            _HDREZKA_CONFIG_EXPIRES_AT = time.monotonic() + _HDREZKA_CONFIG_TTL_SECONDS
        return decoded
    return {}


def _hdrezka_base_url(value: Any) -> Optional[str]:
    candidate = str(value or "").strip().rstrip("/")
    if not candidate:
        return None
    parsed = urlparse(candidate)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return None
    if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        return None
    return candidate


def _hdrezka_source_path(source: Dict[str, Any]) -> Optional[str]:
    value = _source_key(source)
    if not value:
        return None
    candidate = str(value).strip()
    if candidate.startswith(("http://", "https://")):
        parsed = urlparse(candidate)
        candidate = parsed.path
    if any(char in candidate for char in "\r\n?#\\") or len(candidate) > 768:
        return None
    candidate = candidate.strip("/")
    if candidate.endswith(".html"):
        candidate = candidate[:-5]
    if not candidate or any(part in {".", ".."} for part in candidate.split("/")):
        return None
    return candidate


def _hdrezka_attr(tag: str, name: str) -> str:
    match = re.search(
        rf"\b{re.escape(name)}\s*=\s*([\"'])(.*?)\1",
        tag,
        re.IGNORECASE | re.DOTALL,
    )
    return unescape(match.group(2).strip()) if match else ""


def _hdrezka_clean_voice(value: Any) -> str:
    text = re.sub(r"<[^>]+>", " ", str(value or ""))
    return re.sub(r"\s+", " ", unescape(text)).strip()


def _hdrezka_translators(
    page_text: str,
    source_path: str,
    source: Dict[str, Any],
) -> List[Dict[str, str]]:
    basename = source_path.rsplit("/", 1)[-1]
    page_id = basename.split(".", 1)[0]
    merged = _merged_source(source)
    result: List[Dict[str, str]] = []
    seen = set()

    tag_pattern = re.compile(
        r"<(?:li|a)\b[^>]*\bdata-translator_id\s*=\s*([\"'])\d+\1[^>]*>.*?</(?:li|a)>",
        re.IGNORECASE | re.DOTALL,
    )
    for match in tag_pattern.finditer(page_text):
        tag = match.group(0)
        translator_id = _hdrezka_attr(tag, "data-translator_id")
        if not translator_id.isdigit() or translator_id in seen:
            continue
        seen.add(translator_id)
        voice = _hdrezka_attr(tag, "title") or _hdrezka_clean_voice(tag)
        item = {
            "id": _hdrezka_attr(tag, "data-id") or page_id,
            "translator_id": translator_id,
            "voice": voice or str(_first(merged, ("tran", "translation", "voice")) or "Не указано"),
        }
        for attr, field in (
            ("data-camrip", "is_camrip"),
            ("data-ads", "is_ads"),
            ("data-director", "is_director"),
        ):
            attr_value = _hdrezka_attr(tag, attr)
            if attr_value:
                item[field] = attr_value
        result.append(item)

    if not result:
        init_match = re.search(
            r"sof\.tv\.initCDN(?:Ser|Mov)iesEvents\(\s*(\d+)\s*,\s*(\d+)\s*,",
            page_text,
            re.IGNORECASE,
        )
        if init_match:
            effective_id = page_id if page_id.isdigit() else init_match.group(1)
            result.append({
                "id": effective_id,
                "translator_id": init_match.group(2),
                "voice": str(_first(merged, ("tran", "translation", "voice")) or "Не указано"),
            })

    if not result:
        translator = _first(merged, (
            "translator_id", "translatorId", "translator", "tid",
        ))
        if translator is not None and str(translator).strip().isdigit():
            item = {
                "id": str(_first(merged, ("content_id", "contentId", "hdrezka_id", "id")) or page_id),
                "translator_id": str(translator).strip(),
                "voice": str(_first(merged, ("tran", "translation", "voice")) or "Не указано"),
            }
            for field, aliases in (
                ("is_camrip", ("is_camrip", "camrip")),
                ("is_ads", ("is_ads", "ads")),
                ("is_director", ("is_director", "director")),
            ):
                raw_value = _first(merged, aliases)
                if raw_value is not None:
                    item[field] = str(raw_value)
            result.append(item)
    return result


def _hdrezka_static_decode(value: str) -> Optional[str]:
    text = unescape(str(value or "").strip()).replace("\\/", "/")
    if not text.startswith("#"):
        return text
    if len(text) < 3:
        return None
    encoded = text[2:]
    for marker in reversed(HDREZKA_DECODER_MARKERS):
        token = HDREZKA_DECODER_SEPARATOR + base64.b64encode(marker.encode("utf-8")).decode("ascii")
        encoded = encoded.replace(token, "")
    try:
        padded = encoded + "=" * ((4 - len(encoded) % 4) % 4)
        return base64.b64decode(padded, validate=False).decode("utf-8")
    except Exception:
        return None


def _hdrezka_quality(label: Any, mapping: Any = None) -> str:
    text = _hdrezka_clean_voice(label)
    if isinstance(mapping, dict):
        mapped = mapping.get(text)
        if mapped is not None and str(mapped).strip():
            text = str(mapped).strip()
    match = re.search(r"(?<!\d)(2160|1440|1080|720|576|480|360|240|144)(?:\s*[pP]?)\b", text)
    if match:
        return f"{match.group(1)}p"
    aliases = {"4k": "2160p", "uhd": "2160p", "fhd": "1080p", "fullhd": "1080p", "hd": "720p"}
    return aliases.get(text.casefold(), text or "Не указано")


def _hdrezka_subtitles(payload: Dict[str, Any]) -> List[Dict[str, str]]:
    raw = payload.get("subtitle")
    labels = payload.get("subtitle_lns")
    label_map = labels if isinstance(labels, dict) else {}
    parts: List[Any]
    if isinstance(raw, list):
        parts = raw
    elif isinstance(raw, str):
        parts = [part for part in raw.split(",") if part.strip()]
    else:
        parts = []
    result: List[Dict[str, str]] = []
    for part in parts:
        if isinstance(part, dict):
            url = str(_first(part, ("url", "file", "src")) or "").strip()
            language = str(_first(part, ("language", "lang", "title", "label")) or "").strip()
        else:
            text = str(part).strip().replace("\\/", "/")
            match = re.match(r"\[([^\]]+)\](.+)$", text)
            if match:
                code = match.group(1).strip()
                language = str(label_map.get(code) or code)
                url = match.group(2).strip()
            else:
                language = ""
                url = text
        if is_valid_stream_url(url):
            item = {"url": url}
            if language:
                item["language"] = language
            result.append(item)
    return result


def _hdrezka_stream_candidates(raw_value: Any, quality_mapping: Any) -> Tuple[List[Tuple[str, str]], bool]:
    dynamic_decoder_required = False
    candidates: List[Tuple[str, str]] = []

    def add(url: Any, quality: Any = "") -> None:
        clean_url = unescape(str(url or "").strip()).replace("\\/", "/")
        if clean_url.startswith("//"):
            clean_url = "https:" + clean_url
        if is_valid_stream_url(clean_url):
            candidates.append((clean_url, _hdrezka_quality(quality, quality_mapping)))

    if isinstance(raw_value, dict):
        for quality, item in raw_value.items():
            if isinstance(item, dict):
                add(_first(item, ("url", "file", "src")), quality)
            else:
                add(item, quality)
        return candidates, False
    if isinstance(raw_value, list):
        for item in raw_value:
            if isinstance(item, dict):
                add(_first(item, ("url", "file", "src")), _first(item, ("quality", "label", "title")))
            else:
                nested, needs_dynamic = _hdrezka_stream_candidates(item, quality_mapping)
                candidates.extend(nested)
                dynamic_decoder_required = dynamic_decoder_required or needs_dynamic
        return candidates, dynamic_decoder_required

    text = str(raw_value or "").strip()
    if not text:
        return [], False
    if text.startswith("#"):
        decoded = _hdrezka_static_decode(text)
        if decoded is None:
            return [], True
        text = decoded
    text = unescape(text).replace("\\/", "/")

    # Legacy format: [1080p]https://...,[720p]https://... and occasional
    # alternatives separated by " or ". Do not synthesize URLs from labels.
    occupied = set()
    for match in re.finditer(
        r"\[([^\]]+)\]\s*((?:https?:)?//[^,\s]+)",
        text,
        re.IGNORECASE,
    ):
        add(match.group(2), match.group(1))
        occupied.add(match.group(2))
    for match in re.finditer(r"https?://[^,\s]+", text, re.IGNORECASE):
        url = match.group(0).rstrip("'\";)")
        if not any(url == existing for existing in occupied):
            add(url, "")
    return candidates, dynamic_decoder_required


def _hdrezka_parse_response(
    response_text: str,
    source: Dict[str, Any],
    *,
    voice: str,
    user_agent: str,
    page_url: str,
    origin: str,
    season: Optional[int],
    episode: Optional[int],
    quality_mapping: Any,
) -> Tuple[List[Dict[str, Any]], bool]:
    try:
        payload = json.loads(response_text)
    except (TypeError, ValueError):
        return [], False
    if not isinstance(payload, dict):
        return [], False
    raw_value = payload.get("url")
    if raw_value in (None, "", [], {}):
        raw_value = payload.get("streams")
    candidates, dynamic_required = _hdrezka_stream_candidates(raw_value, quality_mapping)
    subtitles = _hdrezka_subtitles(payload)
    streams: List[Dict[str, Any]] = []
    seen = set()
    for stream_url, quality in candidates:
        if stream_url in seen:
            continue
        seen.add(stream_url)
        stream = _stream_metadata(
            source,
            2,
            stream_url,
            voice=voice,
            language="ru",
            quality=quality,
            user_agent=user_agent,
        )
        stream["headers"] = {
            "User-Agent": user_agent,
            "Referer": page_url,
            "Origin": origin,
        }
        if voice:
            stream["translation"] = voice
        if season is not None:
            stream["season"] = int(season)
        if episode is not None:
            stream["episode"] = int(episode)
        if subtitles:
            stream["subtitles"] = list(subtitles)
        streams.append(stream)
    return streams, dynamic_required


def _resolve_hdrezka(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
    fetch_post_form_text: Optional[PostFormFetcher],
    request_user_agent: str,
    season: Optional[int],
    episode: Optional[int],
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    source_path = _hdrezka_source_path(source)
    if not source_path:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_text is None or fetch_post_form_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    config = _hdrezka_get_config(fetch_text, request_user_agent)
    account = config.get("_account") if isinstance(config.get("_account"), dict) else {}
    configured_base = _hdrezka_base_url(account.get("d"))
    base_candidates: List[str] = []
    for candidate in (configured_base, HDREZKA_BASE_URL):
        if candidate and candidate not in base_candidates:
            base_candidates.append(candidate)

    user_agent = str(config.get("u") or request_user_agent or HDREZKA_DEFAULT_USER_AGENT).strip()
    dynamic_headers = _hdrezka_safe_headers(config.get("_headers"))
    account_headers = _hdrezka_safe_headers(account.get("h"))
    hosts = config.get("_hosts") if isinstance(config.get("_hosts"), list) else []
    if hosts and "Host" not in dynamic_headers:
        dynamic_headers["Host"] = str(hosts[0])

    page_text = None
    page_url = None
    selected_base = None
    page_errors: List[str] = []
    encoded_path = quote(source_path, safe="/%:@-._~")
    for base in base_candidates:
        candidate_url = f"{base}/{encoded_path}.html"
        headers = {"User-Agent": user_agent}
        headers.update(dynamic_headers)
        try:
            text, error = fetch_text(candidate_url, headers)
        except Exception as exc:
            page_errors.append(type(exc).__name__)
            continue
        if error or not isinstance(text, str) or not text.strip():
            page_errors.append(str(error or "EMPTY_PAGE")[:120])
            continue
        page_text = text
        page_url = candidate_url
        selected_base = base
        break
    if page_text is None or page_url is None or selected_base is None:
        return [], "hdrezka:PAGE_REQUEST_FAILED" + (":" + ";".join(page_errors[:3]) if page_errors else "")

    restricted = re.search(
        r'<span class="b-player__restricted__block_message">([^<>]+)',
        page_text,
        re.IGNORECASE,
    )
    if restricted:
        return [], "hdrezka:RESTRICTED"
    if re.search(r"<title>Sign In</title>", page_text, re.IGNORECASE):
        return [], "hdrezka:AUTH_REQUIRED"

    merged = _merged_source(source)
    disabled_raw = merged.get("disabled_tids")
    disabled = {
        str(item)
        for item in (disabled_raw if isinstance(disabled_raw, (list, tuple, set)) else [])
    }
    translators = [
        item for item in _hdrezka_translators(page_text, source_path, source)
        if str(item.get("translator_id") or "") not in disabled
    ]

    # Some pages embed the CDN player payload directly and need no AJAX call.
    embedded = re.search(r'(\{"id":"cdnplayer".*?\})\);', page_text, re.IGNORECASE | re.DOTALL)
    if embedded:
        voice = str(_first(merged, ("tran", "translation", "voice")) or "Не указано")
        direct_streams, dynamic_required = _hdrezka_parse_response(
            embedded.group(1),
            source,
            voice=voice,
            user_agent=user_agent,
            page_url=page_url,
            origin=selected_base,
            season=season,
            episode=episode,
            quality_mapping=config.get("q"),
        )
        if direct_streams:
            return direct_streams, None
        if dynamic_required:
            return [], "hdrezka:DYNAMIC_DECODER_REQUIRED"

    if not translators:
        return [], "hdrezka:NO_TRANSLATORS"

    all_streams: List[Dict[str, Any]] = []
    dynamic_decoder_required = False
    provider_errors: List[str] = []
    for translator in translators:
        form: Dict[str, str] = {
            "action": "get_stream" if season is not None and episode is not None else "get_movie",
            "id": str(translator.get("id") or ""),
            "translator_id": str(translator.get("translator_id") or ""),
        }
        if season is not None and episode is not None:
            form["season"] = str(int(season))
            form["episode"] = str(int(episode))
        for field in ("is_camrip", "is_ads", "is_director"):
            if translator.get(field) not in (None, ""):
                form[field] = str(translator[field])

        headers = {
            "User-Agent": user_agent,
            "Origin": selected_base,
            "Referer": page_url,
        }
        headers.update(dynamic_headers)
        headers.update(account_headers)
        endpoint = f"{selected_base}{HDREZKA_AJAX_PATH}{int(time.time() * 1000)}"
        response_text: Optional[str] = None
        last_error: Optional[str] = None
        for attempt in range(3):
            try:
                response_text, last_error = fetch_post_form_text(endpoint, headers, form)
            except Exception as exc:
                response_text = None
                last_error = f"{type(exc).__name__}:{str(exc)[:100]}"
            if response_text and not last_error:
                break
            if attempt < 2:
                time.sleep(0.1 * (2 ** attempt))
        if not response_text or last_error:
            provider_errors.append(str(last_error or "EMPTY_RESPONSE")[:120])
            continue

        parsed_streams, needs_dynamic = _hdrezka_parse_response(
            response_text,
            source,
            voice=str(translator.get("voice") or "Не указано"),
            user_agent=user_agent,
            page_url=page_url,
            origin=selected_base,
            season=season,
            episode=episode,
            quality_mapping=config.get("q"),
        )
        dynamic_decoder_required = dynamic_decoder_required or needs_dynamic
        all_streams.extend(parsed_streams)

    if all_streams:
        return all_streams, None
    if dynamic_decoder_required:
        return [], "hdrezka:DYNAMIC_DECODER_REQUIRED"
    if provider_errors:
        return [], "hdrezka:PROVIDER_ERROR:" + ";".join(provider_errors[:3])
    return [], "hdrezka:NO_PLAYABLE_URL"


def _resolve_mobilink(
    source: Dict[str, Any],
    *,
    fetch_json: Optional[JsonFetcher],
    client_time: Optional[str],
    request_user_agent: str,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    key = _source_key(source)
    if not key:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_json is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    payload, error = fetch_json(
        "getMobiVideo",
        (("id", key), ("client_time", str(client_time or ""))),
    )
    if error:
        return [], f"mobilink:{error}"
    if not isinstance(payload, dict):
        return [], "mobilink:INVALID_RESPONSE"

    nested = payload.get("data")
    data = nested if isinstance(nested, dict) else payload
    url = _first(data, ("lqUrl", "lq_url", "url"))
    if not url:
        url = _first(payload, ("lqUrl", "lq_url", "url"))
    url = str(url or "").strip()
    if not is_valid_stream_url(url):
        return [], "mobilink:NO_PLAYABLE_URL"

    stream = _stream_metadata(
        source,
        1,
        url,
        voice="Русский язык",
        language="ru",
        quality="LQ",
        user_agent=request_user_agent,
    )
    stream["translation"] = "Русский язык"
    stream["resolution"] = "LQ"
    return [stream], None




def _plvideo_quality(value: Any) -> str:
    try:
        numeric = int(str(value))
    except (TypeError, ValueError):
        return "Не указано"
    thresholds = (2160, 1440, 1080, 720, 480, 360, 240, 144)
    for index in range(1, len(thresholds)):
        if numeric > thresholds[index]:
            return f"{thresholds[index - 1]}p"
    return f"{thresholds[-1]}p"


def _resolve_plvideo(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    key = _source_key(source)
    if not key:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    endpoint = f"{PLVIDEO_API_BASE_URL}/{quote(key, safe='')}?aud=16"
    headers = {"User-Agent": PLVIDEO_USER_AGENT}
    try:
        api_text, error = fetch_text(endpoint, headers)
    except Exception as exc:
        return [], f"plvideo:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"plvideo:{error}"
    if not isinstance(api_text, str) or not api_text.strip():
        return [], "plvideo:EMPTY_RESPONSE"
    try:
        payload = json.loads(api_text)
    except (TypeError, ValueError):
        return [], "plvideo:INVALID_JSON"
    if not isinstance(payload, dict):
        return [], "plvideo:INVALID_RESPONSE"

    item = payload.get("item")
    profiles = item.get("profiles") if isinstance(item, dict) else None
    if not isinstance(profiles, dict):
        return [], "plvideo:NO_PROFILES"

    subtitles: List[Dict[str, Any]] = []
    captions = profiles.get("captions")
    if isinstance(captions, list):
        for caption in captions:
            if not isinstance(caption, dict) or caption.get("is_autogenerated"):
                continue
            caption_url = _first(caption, ("file", "url"))
            if not caption_url or not is_valid_stream_url(str(caption_url).strip()):
                continue
            subtitle: Dict[str, Any] = {
                "url": str(caption_url).strip(),
            }
            title = _first(caption, ("langTitle", "title", "language"))
            code = _first(caption, ("code", "lang"))
            if title:
                subtitle["language"] = str(title)
            if code:
                subtitle["code"] = str(code)
            subtitles.append(subtitle)

    balancer = payload.get("video_balancer")
    if not isinstance(balancer, dict):
        return [], "plvideo:NO_VIDEO_BALANCER"
    playlist_url = _first(balancer, ("m3u8", "url"))
    if not playlist_url:
        return [], "plvideo:NO_M3U8"
    playlist_url = str(playlist_url).strip()
    if not is_valid_stream_url(playlist_url):
        return [], "plvideo:INVALID_M3U8"

    try:
        playlist_text, error = fetch_text(playlist_url, headers)
    except Exception as exc:
        return [], f"plvideo:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"plvideo:{error}"
    if not isinstance(playlist_text, str) or not playlist_text.strip():
        return [], "plvideo:EMPTY_PLAYLIST"

    streams: List[Dict[str, Any]] = []
    for match in re.finditer(r"https:.*?i=\d+x(\d+)_\d+", playlist_text):
        stream_url = match.group(0).strip()
        if not is_valid_stream_url(stream_url):
            continue
        quality = _plvideo_quality(match.group(1))
        stream = _stream_metadata(
            source,
            43,
            stream_url,
            voice="",
            language="ru",
            quality=quality,
            user_agent=PLVIDEO_USER_AGENT,
        )
        stream["resolution"] = quality
        if subtitles:
            stream["subtitles"] = list(subtitles)
        streams.append(stream)

    if not streams:
        return [], "plvideo:NO_PLAYABLE_URL"
    return streams, None

def _veoveo_quality(value: Any) -> str:
    label = str(value or "").strip()
    if not label:
        return "Не указано"
    match = re.search(
        r"(?<!\d)(2160|1440|1080|720|480|360|240|144)(?:\s*[pP]?)\b",
        label,
    )
    if match:
        return f"{match.group(1)}p"
    aliases = {
        "uhd": "2160p",
        "4k": "2160p",
        "full hd": "1080p",
        "fhd": "1080p",
        "hd": "720p",
        "sd": "480p",
    }
    return aliases.get(label.casefold(), label)


def _resolve_veoveo(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
    season: Optional[int],
    episode: Optional[int],
    request_user_agent: str,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    key = _source_key(source)
    if not key:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"
    if len(key) > 256 or any(char in key for char in "\r\n?#"):
        return [], "SOURCE_REF_INVALID"

    endpoint = (
        f"{VEOVEO_BASE_URL}/balancer-api/proxy/playlists/"
        f"catalog-api/episodes?content-id={quote(key, safe='')}"
    )
    headers = {
        "User-Agent": request_user_agent or VEOVEO_USER_AGENT,
    }
    try:
        response_text, error = fetch_text(endpoint, headers)
    except Exception as exc:
        return [], f"veoveo:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"veoveo:{error}"
    if not isinstance(response_text, str) or not response_text.strip():
        return [], "veoveo:EMPTY_RESPONSE"
    try:
        payload = json.loads(response_text)
    except (TypeError, ValueError):
        return [], "veoveo:INVALID_JSON"
    if not isinstance(payload, list):
        return [], "veoveo:INVALID_RESPONSE"

    variants: List[Dict[str, Any]] = []
    for group in payload:
        if not isinstance(group, dict):
            continue
        if season is not None and episode is not None:
            try:
                group_episode = int(group.get("order"))
            except (TypeError, ValueError):
                continue
            season_data = group.get("season")
            if not isinstance(season_data, dict):
                continue
            try:
                group_season = int(season_data.get("order"))
            except (TypeError, ValueError):
                continue
            if group_season != int(season) or group_episode != int(episode):
                continue
        group_variants = group.get("episodeVariants")
        if isinstance(group_variants, list):
            variants.extend(
                variant
                for variant in group_variants
                if isinstance(variant, dict)
            )

    streams: List[Dict[str, Any]] = []
    seen_urls = set()
    for variant in variants:
        title = _first(variant, ("title", "name", "translation"))
        raw_url = _first(variant, ("filepath", "file", "url"))
        stream_url = str(raw_url or "").strip()
        if (
            not stream_url
            or stream_url in seen_urls
            or not is_valid_stream_url(stream_url)
        ):
            continue
        seen_urls.add(stream_url)
        quality = _veoveo_quality(title)
        stream = _stream_metadata(
            source,
            45,
            stream_url,
            voice=str(title or ""),
            language="ru",
            quality=quality,
            user_agent=headers["User-Agent"],
        )
        if title:
            stream["translation"] = str(title)
        stream["resolution"] = quality
        streams.append(stream)

    if not streams:
        return [], "veoveo:NO_PLAYABLE_URL"
    return streams, None


def _rutube_video_key(source: Dict[str, Any]) -> Optional[str]:
    value = _source_key(source)
    if not value:
        return None
    candidate = value.strip()
    if candidate.startswith(("http://", "https://")):
        parsed = urlparse(candidate)
        host = (parsed.hostname or "").lower()
        if host not in {"rutube.ru", "www.rutube.ru"}:
            return None
        segments = [segment for segment in parsed.path.split("/") if segment]
        candidate = segments[-1] if segments else ""
    else:
        candidate = candidate.strip("/")
        if "/" in candidate:
            candidate = candidate.rsplit("/", 1)[-1]
    if not re.fullmatch(r"[A-Za-z0-9_-]+", candidate):
        return None
    return candidate


def _resolve_rutube(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    video_key = _rutube_video_key(source)
    if not video_key:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    endpoint = (
        f"{RUTUBE_API_BASE_URL}/{quote(video_key, safe='')}/"
        "?no_404=true&referer=&pver=v2&client=wdp&mq=all&ac_client=web"
    )
    headers = {"User-Agent": RUTUBE_USER_AGENT}
    try:
        options_text, error = fetch_text(endpoint, headers)
    except Exception as exc:
        return [], f"rutube:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"rutube:{error}"
    if not isinstance(options_text, str) or not options_text.strip():
        return [], "rutube:EMPTY_RESPONSE"
    try:
        payload = json.loads(options_text)
    except (TypeError, ValueError):
        return [], "rutube:INVALID_JSON"
    if not isinstance(payload, dict):
        return [], "rutube:INVALID_RESPONSE"

    subtitles: List[Dict[str, Any]] = []
    captions = payload.get("captions")
    if isinstance(captions, list):
        for caption in captions:
            if not isinstance(caption, dict) or caption.get("is_autogenerated"):
                continue
            caption_url = _first(caption, ("file", "url"))
            if not caption_url or not is_valid_stream_url(str(caption_url).strip()):
                continue
            subtitle: Dict[str, Any] = {"url": str(caption_url).strip()}
            title = _first(caption, ("langTitle", "title", "language"))
            code = _first(caption, ("code", "lang"))
            if title:
                subtitle["language"] = str(title)
            if code:
                subtitle["code"] = str(code)
            subtitles.append(subtitle)

    balancer = payload.get("video_balancer")
    if not isinstance(balancer, dict):
        return [], "rutube:NO_VIDEO_BALANCER"
    playlist_url = _first(balancer, ("m3u8", "url"))
    if not playlist_url:
        return [], "rutube:NO_M3U8"
    playlist_url = str(playlist_url).strip()
    if not is_valid_stream_url(playlist_url):
        return [], "rutube:INVALID_M3U8"

    try:
        playlist_text, error = fetch_text(playlist_url, headers)
    except Exception as exc:
        return [], f"rutube:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"rutube:{error}"
    if not isinstance(playlist_text, str) or not playlist_text.strip():
        return [], "rutube:EMPTY_PLAYLIST"

    streams: List[Dict[str, Any]] = []
    seen_urls = set()
    for match in re.finditer(r"https:.*?i=\d+x(\d+)_\d+", playlist_text):
        stream_url = match.group(0).strip()
        if stream_url in seen_urls or not is_valid_stream_url(stream_url):
            continue
        seen_urls.add(stream_url)
        quality = _plvideo_quality(match.group(1))
        stream = _stream_metadata(
            source,
            42,
            stream_url,
            voice="",
            language="",
            quality=quality,
            user_agent=RUTUBE_USER_AGENT,
        )
        stream["resolution"] = quality
        if subtitles:
            stream["subtitles"] = list(subtitles)
        streams.append(stream)

    if not streams:
        return [], "rutube:NO_PLAYABLE_URL"
    return streams, None



def _kinomania_page_url(source: Dict[str, Any]) -> Optional[str]:
    key = _source_key(source)
    if not key:
        return None
    clean_key = key.strip().strip("/")
    if (
        not clean_key
        or len(clean_key) > 256
        or any(char in clean_key for char in "\r\n?#")
        or not re.fullmatch(
            r"[A-Za-z0-9][A-Za-z0-9._~-]*(?:/[A-Za-z0-9][A-Za-z0-9._~-]*)?",
            clean_key,
        )
    ):
        return None
    if re.fullmatch(r"\d+", clean_key):
        return f"{KINOMANIA_BASE_URL}/film/{clean_key}/trailers"
    return f"{KINOMANIA_BASE_URL}/{clean_key}/videos"


def _resolve_kinomania(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
    request_user_agent: str,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    page_url = _kinomania_page_url(source)
    if not page_url:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    user_agent = request_user_agent or "Zona"
    try:
        page, error = fetch_text(page_url, {"User-Agent": user_agent})
    except Exception as exc:
        return [], f"kinomania:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"kinomania:{error}"
    if not isinstance(page, str) or not page.strip():
        return [], "kinomania:EMPTY_RESPONSE"

    streams: List[Dict[str, Any]] = []
    seen_urls = set()

    def add_stream(raw_url: Any, title: Any) -> None:
        stream_url = urljoin(page_url, unescape(str(raw_url or "").strip()))
        if (
            not stream_url
            or stream_url in seen_urls
            or not is_valid_stream_url(stream_url)
        ):
            return
        seen_urls.add(stream_url)
        clean_title = unescape(str(title or "").strip())
        language = (
            "ru"
            if re.search(r"\(рус\.\)|русск|russian", clean_title, re.IGNORECASE)
            else ""
        )
        stream = _stream_metadata(
            source,
            7,
            stream_url,
            voice=clean_title,
            language=language,
            quality="LQ",
            user_agent=user_agent,
        )
        if clean_title:
            stream["translation"] = clean_title
        stream["resolution"] = "LQ"
        streams.append(stream)

    primary_match = re.search(
        r"<video-js\b[^>]*\bid\s*=\s*['\"]([^'\"]+)['\"][\s\S]*?"
        r"<source\b[^>]*\bsrc\s*=\s*['\"]([^'\"]+)['\"]",
        page,
        re.IGNORECASE,
    )
    if primary_match:
        player_id = primary_match.group(1)
        title_match = re.search(
            r"const\s+playerId\s*=\s*['\"]"
            + re.escape(player_id)
            + r"['\"].*?\btitle\s*:\s*['\"]([^'\"]+)['\"]",
            page,
            re.IGNORECASE | re.DOTALL,
        )
        add_stream(
            primary_match.group(2),
            title_match.group(1) if title_match else "",
        )

    for modal_match in re.finditer(
        r"modalVideo\s*\(\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)",
        page,
        re.IGNORECASE,
    ):
        add_stream(modal_match.group(1), modal_match.group(2))

    if not streams:
        return [], "kinomania:NO_PLAYABLE_URL"
    return streams, None




def _filmru_page_url(source: Dict[str, Any]) -> Optional[str]:
    key = _source_key(source)
    if not key:
        return None
    clean_key = key.strip().strip("/")
    # The legacy adapter builds /node/{key}/trailers directly. Film.ru's
    # trailer embedURL is numeric as well, so reject path/query injection.
    if not re.fullmatch(r"\d{1,12}", clean_key):
        return None
    return f"{FILMRU_BASE_URL}/node/{clean_key}/trailers"


def _filmru_language(description: str) -> str:
    compact = re.sub(r"\s+", "", description.casefold())
    if re.search(r"рус|russian", compact):
        return "ru"
    if re.search(r"англ|english", compact):
        return "en"
    if re.search(r"фран|french", compact):
        return "fr"
    if re.search(r"исп|spanish", compact):
        return "es"
    if re.search(r"нем|german", compact):
        return "de"
    return ""


def _resolve_filmru(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
    request_user_agent: str,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    page_url = _filmru_page_url(source)
    if not page_url:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    user_agent = request_user_agent or "Zona"
    try:
        page, error = fetch_text(page_url, {"User-Agent": user_agent})
    except Exception as exc:
        return [], f"filmru:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"filmru:{error}"
    if not isinstance(page, str) or not page.strip():
        return [], "filmru:EMPTY_RESPONSE"

    streams: List[Dict[str, Any]] = []
    seen_urls = set()

    def add_stream(raw_url: Any, description: Any) -> None:
        stream_url = urljoin(page_url, unescape(str(raw_url or "").strip()))
        if (
            not stream_url
            or stream_url in seen_urls
            or not is_valid_stream_url(stream_url)
        ):
            return
        seen_urls.add(stream_url)
        clean_description = unescape(str(description or "").strip())
        language = _filmru_language(clean_description)
        stream = _stream_metadata(
            source,
            36,
            stream_url,
            voice=clean_description,
            language=language,
            quality="LQ",
            user_agent=user_agent,
        )
        if clean_description:
            stream["translation"] = clean_description
        stream["resolution"] = "LQ"
        stream["trailer"] = True
        streams.append(stream)

    # Legacy pattern: a video-js block with an HTML source element.
    for block_match in re.finditer(
        r"<video-js\b[^>]*>(.*?)(?=\s+ads:)",
        page,
        re.IGNORECASE | re.DOTALL,
    ):
        block = block_match.group(1)
        source_match = re.search(
            r"\bsrc\s*=\s*['\"]([^'\"]+)['\"][\s\S]*?"
            r"\btype\s*=\s*['\"]video",
            block,
            re.IGNORECASE,
        )
        description_match = re.search(
            r"\bdescription\s*=\s*['\"]([^'\"]+)",
            block,
            re.IGNORECASE,
        )
        if source_match:
            add_stream(
                source_match.group(1),
                description_match.group(1) if description_match else "",
            )

    # Legacy fallback: trailerplayer JavaScript options.
    for block_match in re.finditer(
        r"['\"]trailerplayer['\"]\s*,(.*?)(?=\s+ads:)",
        page,
        re.IGNORECASE | re.DOTALL,
    ):
        block = block_match.group(1)
        source_match = re.search(
            r"\bsrc\s*:\s*['\"]([^'\"]+)['\"][\s\S]*?"
            r"\btype\s*:\s*['\"]video",
            block,
            re.IGNORECASE,
        )
        description_match = re.search(
            r"\bdescription\s*:\s*['\"]([^'\"]+)",
            block,
            re.IGNORECASE,
        )
        if source_match:
            add_stream(
                source_match.group(1),
                description_match.group(1) if description_match else "",
            )

    if not streams:
        return [], "filmru:NO_PLAYABLE_URL"
    return streams, None



def _ok_video_page_url(source: Dict[str, Any]) -> Optional[str]:
    key = _source_key(source)
    if not key:
        return None
    clean_key = key.strip().strip("/")
    if clean_key.startswith(("http://", "https://")):
        parsed = urlparse(clean_key)
        if (
            parsed.scheme not in {"http", "https"}
            or (parsed.hostname or "").lower() not in {"ok.ru", "www.ok.ru"}
        ):
            return None
        segments = [segment for segment in parsed.path.split("/") if segment]
        if len(segments) >= 2 and segments[-2].casefold() == "video":
            clean_key = segments[-1]
        elif segments:
            clean_key = segments[-1]
        else:
            return None
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,256}", clean_key):
        return None
    return f"{OK_BASE_URL}/video/{quote(clean_key, safe='')}"


def _ok_quality(value: Any) -> str:
    label = str(value or "").strip().casefold()
    aliases = {
        "mobile": "144p",
        "lowest": "240p",
        "low": "360p",
        "sd": "480p",
        "hd": "720p",
        "full": "1080p",
    }
    if label in aliases:
        return aliases[label]
    match = re.fullmatch(r"(2160|1440|1080|720|480|360|240|144)\s*[pP]?", label)
    return f"{match.group(1)}p" if match else (label or "Не указано")


def _resolve_ok(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
    request_user_agent: str,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    page_url = _ok_video_page_url(source)
    if not page_url:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    user_agent = request_user_agent or "Zona"
    try:
        page, error = fetch_text(page_url, {"User-Agent": user_agent})
    except Exception as exc:
        return [], f"ok:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"ok:{error}"
    if not isinstance(page, str) or not page.strip():
        return [], "ok:EMPTY_RESPONSE"

    options_payload: Optional[Dict[str, Any]] = None
    for options_match in re.finditer(
        r"\bdata-options\s*=\s*(['\"])(.*?)\1",
        page,
        re.IGNORECASE | re.DOTALL,
    ):
        candidate = unescape(options_match.group(2)).strip()
        try:
            parsed = json.loads(candidate)
        except (TypeError, ValueError):
            continue
        if isinstance(parsed, dict):
            options_payload = parsed
            break
    if options_payload is None:
        return [], "ok:NO_DATA_OPTIONS"

    flashvars = options_payload.get("flashvars")
    metadata = flashvars.get("metadata") if isinstance(flashvars, dict) else None
    videos = metadata.get("videos") if isinstance(metadata, dict) else None
    if not isinstance(videos, list):
        return [], "ok:NO_VIDEO_PROFILES"

    streams: List[Dict[str, Any]] = []
    seen_urls = set()
    for video in videos:
        if not isinstance(video, dict):
            continue
        stream_url = str(_first(video, ("url", "file", "src")) or "").strip()
        if (
            not stream_url
            or stream_url in seen_urls
            or not is_valid_stream_url(stream_url)
        ):
            continue
        seen_urls.add(stream_url)
        quality = _ok_quality(_first(video, ("name", "quality", "profile")))
        stream = _stream_metadata(
            source,
            46,
            stream_url,
            voice="",
            language="",
            quality=quality,
            user_agent=user_agent,
        )
        stream["resolution"] = quality
        stream["downloadUrl"] = stream_url
        stream["downloadFormat"] = "M3U8"
        streams.append(stream)

    if not streams:
        return [], "ok:NO_PLAYABLE_URL"
    return streams, None



def _kinobadi_page_url(
    source: Dict[str, Any],
    season: Optional[int],
    episode: Optional[int],
) -> Optional[str]:
    key = _source_key(source)
    if not key:
        return None
    clean_key = key.strip().strip("/")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,256}", clean_key):
        return None
    page_url = (
        f"{KINOBADI_BASE_URL}/player_index.php"
        f"?id={quote(clean_key, safe='')}"
    )
    if season is not None and episode is not None:
        try:
            season_number = int(season)
            episode_number = int(episode)
        except (TypeError, ValueError):
            return None
        if season_number < 0 or episode_number < 0:
            return None
        page_url += f"&season={season_number}&episode={episode_number}"
    return page_url


def _resolve_kinobadi(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
    season: Optional[int],
    episode: Optional[int],
    request_user_agent: str,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    page_url = _kinobadi_page_url(source, season, episode)
    if not page_url:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    user_agent = request_user_agent or "Zona"
    try:
        page, error = fetch_text(page_url, {"User-Agent": user_agent})
    except Exception as exc:
        return [], f"kinobadi:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"kinobadi:{error}"
    if not isinstance(page, str) or not page.strip():
        return [], "kinobadi:EMPTY_RESPONSE"

    translator = ""
    translator_match = re.search(
        r"<select\b[^>]*\bid\s*=\s*['\"]translator-name['\"][^>]*>"
        r"(.*?)</select>",
        page,
        re.IGNORECASE | re.DOTALL,
    )
    if translator_match:
        selected = re.search(
            r"<option\b[^>]*\bselected\s*=\s*['\"]?selected['\"]?[^>]*>"
            r"(.*?)</option>",
            translator_match.group(1),
            re.IGNORECASE | re.DOTALL,
        )
        if selected is None:
            selected = re.search(
                r"<option\b[^>]*>(.*?)</option>",
                translator_match.group(1),
                re.IGNORECASE | re.DOTALL,
            )
        if selected:
            translator = unescape(re.sub(r"<[^>]+>", "", selected.group(1))).strip()

    streams: List[Dict[str, Any]] = []
    seen_urls = set()
    file_matches = re.finditer(
        r"\bfile\s*:\s*['\"]([^'\"]+)['\"]",
        page,
        re.IGNORECASE,
    )
    for file_match in file_matches:
        profile_list = file_match.group(1)
        for profile_match in re.finditer(
            r"\[(\d+)\]([^,\"]+)",
            profile_list,
        ):
            quality = f"{profile_match.group(1)}p"
            raw_url = unescape(profile_match.group(2).strip())
            stream_url = urljoin(page_url, raw_url)
            if (
                not stream_url
                or stream_url in seen_urls
                or not is_valid_stream_url(stream_url)
            ):
                continue
            seen_urls.add(stream_url)
            language = _filmru_language(translator)
            stream = _stream_metadata(
                source,
                51,
                stream_url,
                voice=translator,
                language=language,
                quality=quality,
                user_agent=user_agent,
            )
            if translator:
                stream["translation"] = translator
            stream["resolution"] = quality
            streams.append(stream)

    if not streams:
        return [], "kinobadi:NO_PLAYABLE_URL"
    return streams, None





def _cdnvideohub_publishers(source: Dict[str, Any]) -> List[int]:
    merged = _merged_source(source)
    configured = _first(merged, (
        "pub", "publisher", "publisherId", "publisher_id",
    ))
    try:
        publisher = int(configured)
    except (TypeError, ValueError):
        publisher = 0
    if 1 <= publisher <= 30:
        return [publisher]

    key = _source_key(source) or ""
    seed = sum((index + 1) * ord(char) for index, char in enumerate(key))
    candidates: List[int] = []
    for offset in range(5):
        candidate = ((seed + offset) % 30) + 1
        if candidate not in candidates:
            candidates.append(candidate)
    return candidates


def _cdnvideohub_label(item: Dict[str, Any]) -> str:
    studio = str(_first(item, (
        "voiceStudio", "voice_studio", "voice", "translation",
    )) or "").strip()
    source_name = str(_first(item, (
        "source", "sourceName", "source_name",
    )) or "").strip()
    if studio and source_name:
        return f"{studio} ({source_name})"
    return studio or source_name


def _cdnvideohub_stream(
    source: Dict[str, Any],
    *,
    url: str,
    quality: str,
    voice: str,
    user_agent: str,
) -> Dict[str, Any]:
    stream = _stream_metadata(
        source,
        33,
        url,
        voice=voice,
        language=_filmru_language(voice),
        quality=quality,
        user_agent=user_agent,
    )
    if voice:
        stream["translation"] = voice
    stream["resolution"] = quality
    return stream


def _resolve_cdnvideohub(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    key = _source_key(source)
    if not key:
        return [], "SOURCE_REF_INCOMPLETE"
    if len(key) > 256 or any(char in key for char in "\r\n?#"):
        return [], "SOURCE_REF_INVALID"
    if fetch_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    headers = {"User-Agent": CDNVIDEOHUB_USER_AGENT}
    last_error = "cdnvideohub:NO_ITEMS"
    for publisher in _cdnvideohub_publishers(source):
        endpoint = (
            f"{CDNVIDEOHUB_BASE_URL}{CDNVIDEOHUB_PLAYLIST_PATH}"
            f"?pub={publisher}&id={quote(key, safe='')}&aggr=kp"
        )
        try:
            response_text, error = fetch_text(endpoint, headers)
        except Exception as exc:
            last_error = f"cdnvideohub:{type(exc).__name__}:{str(exc)[:120]}"
            continue
        if error:
            last_error = f"cdnvideohub:{error}"
            continue
        if not isinstance(response_text, str) or not response_text.strip():
            last_error = "cdnvideohub:EMPTY_RESPONSE"
            continue
        try:
            payload = json.loads(response_text)
        except (TypeError, ValueError):
            last_error = "cdnvideohub:INVALID_JSON"
            continue
        if not isinstance(payload, dict):
            last_error = "cdnvideohub:INVALID_RESPONSE"
            continue
        items = payload.get("items")
        if not isinstance(items, list):
            last_error = "cdnvideohub:NO_ITEMS"
            continue

        streams: List[Dict[str, Any]] = []
        seen_urls = set()
        for item in items:
            if not isinstance(item, dict):
                continue
            item_key = _first(item, ("vkId", "vk_id", "id"))
            item_key = str(item_key or "").strip()
            if not item_key or len(item_key) > 256 or any(
                char in item_key for char in "\r\n?#"
            ):
                continue

            fallback_url = (
                f"{CDNVIDEOHUB_BASE_URL}{CDNVIDEOHUB_FALLBACK_PATH}"
                f"{item_key if item_key.startswith('/') else '/' + item_key}"
            )
            try:
                fallback_text, fallback_error = fetch_text(
                    fallback_url,
                    headers,
                )
            except Exception:
                continue
            if fallback_error:
                continue
            if not isinstance(fallback_text, str) or not fallback_text.strip():
                continue
            try:
                fallback_payload = json.loads(fallback_text)
            except (TypeError, ValueError):
                continue
            if not isinstance(fallback_payload, dict):
                continue
            provider_data = fallback_payload.get("source")
            if not isinstance(provider_data, dict):
                provider_data = fallback_payload

            label = _cdnvideohub_label(item)
            hls_url = str(_first(provider_data, (
                "hlsUrl", "hls_url",
            )) or "").strip()
            if hls_url:
                playlist_url = urljoin(fallback_url, hls_url)
                if not is_valid_stream_url(playlist_url):
                    continue
                try:
                    playlist_text, playlist_error = fetch_text(
                        playlist_url,
                        headers,
                    )
                except Exception:
                    continue
                if playlist_error or not isinstance(playlist_text, str):
                    continue
                for match in re.finditer(
                    r"#EXT-X-STREAM-INF:.*?,RESOLUTION=\d+x(\d+)\s+(\S+)",
                    playlist_text,
                    re.IGNORECASE | re.DOTALL,
                ):
                    stream_url = urljoin(playlist_url, match.group(2).strip())
                    if (
                        stream_url in seen_urls
                        or not is_valid_stream_url(stream_url)
                    ):
                        continue
                    quality = f"{match.group(1)}p"
                    streams.append(_cdnvideohub_stream(
                        source,
                        url=stream_url,
                        quality=quality,
                        voice=label,
                        user_agent=headers["User-Agent"],
                    ))
                    seen_urls.add(stream_url)
                continue

            for profile_key, quality in CDNVIDEOHUB_PROFILES:
                raw_url = _first(provider_data, (profile_key,))
                stream_url = urljoin(
                    fallback_url,
                    str(raw_url or "").strip(),
                )
                if (
                    not raw_url
                    or stream_url in seen_urls
                    or not is_valid_stream_url(stream_url)
                ):
                    continue
                streams.append(_cdnvideohub_stream(
                    source,
                    url=stream_url,
                    quality=quality,
                    voice=label,
                    user_agent=headers["User-Agent"],
                ))
                seen_urls.add(stream_url)

        if streams:
            return streams, None
        last_error = "cdnvideohub:NO_PLAYABLE_URL"

    return [], last_error


def _sooplive_quality(value: Any) -> str:
    label = str(value or "").strip()
    if not label:
        return "Не указано"
    match = re.search(
        r"(?<!\d)(2160|1440|1080|720|480|360|240|144)(?:\s*[pP]?)\b",
        label,
    )
    if match:
        return f"{match.group(1)}p"
    return label


def _resolve_sooplive(
    source: Dict[str, Any],
    *,
    fetch_post_text: Optional[TextFetcher],
    request_user_agent: str,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    key = _source_key(source)
    if not key:
        return [], "SOURCE_REF_INCOMPLETE"
    if len(key) > 256 or any(char in key for char in "\r\n"):
        return [], "SOURCE_REF_INVALID"
    if fetch_post_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    endpoint = f"{SOOPLIVE_BASE_URL}{SOOPLIVE_API_PATH}"
    headers = {
        "User-Agent": request_user_agent or SOOPLIVE_USER_AGENT,
        "nTitleNo": key,
        "nApiLevel": "10",
        "nPlaylistIdx": "0",
    }
    try:
        response_text, error = fetch_post_text(endpoint, headers)
    except Exception as exc:
        return [], f"sooplive:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"sooplive:{error}"
    if not isinstance(response_text, str) or not response_text.strip():
        return [], "sooplive:EMPTY_RESPONSE"
    try:
        payload = json.loads(response_text)
    except (TypeError, ValueError):
        return [], "sooplive:INVALID_JSON"
    if not isinstance(payload, dict):
        return [], "sooplive:INVALID_RESPONSE"

    data = payload.get("data")
    if not isinstance(data, dict):
        return [], "sooplive:NO_DATA"
    files = data.get("files")
    if not isinstance(files, list):
        return [], "sooplive:NO_FILES"

    merged = _merged_source(source)
    translation_value = _first(merged, (
        "tran", "translation", "voice", "audio",
    ))
    language_value = _first(merged, ("lang", "language"))
    translation = str(translation_value or "").strip()
    language = str(language_value or "ru").strip() or "ru"

    streams: List[Dict[str, Any]] = []
    seen_urls = set()
    for group in files:
        if not isinstance(group, dict):
            continue
        quality_info = group.get("quantity_info")
        if not isinstance(quality_info, list):
            quality_info = group.get("quality_info")
        if not isinstance(quality_info, list):
            continue
        for item in quality_info:
            if not isinstance(item, dict):
                continue
            raw_url = _first(item, ("file", "url"))
            stream_url = unescape(str(raw_url or "").strip())
            if (
                not stream_url
                or stream_url in seen_urls
                or not is_valid_stream_url(stream_url)
            ):
                continue
            resolution = _first(item, ("resolution", "res", "quality"))
            quality = _sooplive_quality(resolution)
            seen_urls.add(stream_url)
            stream = _stream_metadata(
                source,
                39,
                stream_url,
                voice=translation,
                language=language,
                quality=quality,
                user_agent=headers["User-Agent"],
            )
            if translation:
                stream["translation"] = translation
            stream["resolution"] = quality
            streams.append(stream)

    if not streams:
        return [], "sooplive:NO_PLAYABLE_URL"
    return streams, None


def _fancdn_subtitles(value: Any, base_url: str) -> List[Dict[str, str]]:
    if isinstance(value, list):
        parts = value
    else:
        parts = str(value or "").split(",")
    subtitles: List[Dict[str, str]] = []
    for part in parts:
        if isinstance(part, dict):
            label = str(_first(part, ("title", "label", "language")) or "").strip()
            raw_url = _first(part, ("url", "file"))
        else:
            text = str(part or "").strip()
            label_match = re.match(r"\[([^\]]+)\](.*)$", text)
            if label_match:
                label = label_match.group(1).strip()
                raw_url = label_match.group(2).strip()
            else:
                label = ""
                raw_url = text
        subtitle_url = urljoin(base_url, str(raw_url or "").strip())
        if not is_valid_stream_url(subtitle_url):
            continue
        subtitle: Dict[str, str] = {"url": subtitle_url}
        if label:
            subtitle["language"] = label
        subtitles.append(subtitle)
    return subtitles


def _resolve_fancdn(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
    request_user_agent: str,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    key = _source_key(source)
    if not key:
        return [], "SOURCE_REF_INCOMPLETE"
    if len(key) > 256 or any(char in key for char in "\r\n?#"):
        return [], "SOURCE_REF_INVALID"
    if fetch_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    config_url = f"{FANCDN_BASE_URL}{FANCDN_CONFIG_PATH}"
    try:
        config_text, error = fetch_text(
            config_url,
            {"User-Agent": request_user_agent or FANCDN_DEFAULT_USER_AGENT},
        )
    except Exception as exc:
        return [], f"fancdn:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"fancdn:{error}"
    if not isinstance(config_text, str) or not config_text.strip():
        return [], "fancdn:EMPTY_CONFIG"
    try:
        config = json.loads(config_text)
    except (TypeError, ValueError):
        return [], "fancdn:INVALID_CONFIG"
    if not isinstance(config, dict):
        return [], "fancdn:INVALID_CONFIG"

    configured_user_agent = str(
        _first(config, ("u", "userAgent", "user_agent"))
        or request_user_agent
        or FANCDN_DEFAULT_USER_AGENT
    ).strip()
    referer = str(_first(config, ("r", "referer")) or "").strip()
    template = str(_first(config, ("t", "template", "path")) or "").strip()
    if not template:
        return [], "fancdn:NO_TEMPLATE"

    endpoint = template.replace("{movieId}", quote(key, safe=""))
    if endpoint.startswith(("http://", "https://")):
        endpoint_url = endpoint
    else:
        endpoint_url = (
            f"{FANCDN_BASE_URL.rstrip('/')}/"
            f"{endpoint.lstrip('/')}"
        )
    if not is_valid_stream_url(endpoint_url):
        return [], "fancdn:INVALID_TEMPLATE"

    try:
        playlist_page, error = fetch_text(
            endpoint_url,
            {"User-Agent": configured_user_agent, **(
                {"Referer": referer} if referer else {}
            )},
        )
    except Exception as exc:
        return [], f"fancdn:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"fancdn:{error}"
    if not isinstance(playlist_page, str) or not playlist_page.strip():
        return [], "fancdn:EMPTY_PLAYLIST_PAGE"

    playlist_match = re.search(
        r"(?:var|let)\s+playlist\s*=\s*(\[.*?\]);",
        playlist_page,
        re.IGNORECASE | re.DOTALL,
    )
    if not playlist_match:
        return [], "fancdn:NO_PLAYLIST"
    try:
        playlist = json.loads(playlist_match.group(1))
    except (TypeError, ValueError):
        return [], "fancdn:INVALID_PLAYLIST"
    if not isinstance(playlist, list):
        return [], "fancdn:INVALID_PLAYLIST"

    streams: List[Dict[str, Any]] = []
    seen_urls = set()
    for item in playlist:
        if not isinstance(item, dict):
            continue
        title = str(_first(item, ("title", "name", "translation")) or "").strip()
        file_value = _first(item, ("file", "url", "src"))
        file_url = urljoin(endpoint_url, str(file_value or "").strip())
        if not is_valid_stream_url(file_url):
            continue
        headers = {"User-Agent": configured_user_agent}
        if referer:
            headers["Referer"] = referer
        try:
            player_page, error = fetch_text(file_url, headers)
        except Exception:
            continue
        if error or not isinstance(player_page, str):
            continue
        subtitles = _fancdn_subtitles(item.get("subtitles"), file_url)
        for match in re.finditer(
            r"\./(\d+)\.mp4:hls:.*?\.m3u8",
            player_page,
            re.IGNORECASE | re.DOTALL,
        ):
            stream_url = urljoin(file_url, match.group(0))
            if (
                stream_url in seen_urls
                or not is_valid_stream_url(stream_url)
            ):
                continue
            quality = _sooplive_quality(match.group(1))
            stream = _stream_metadata(
                source,
                35,
                stream_url,
                voice=title,
                language=_filmru_language(title),
                quality=quality,
                user_agent=configured_user_agent,
            )
            if title:
                stream["translation"] = title
            stream["resolution"] = quality
            if subtitles:
                stream["subtitles"] = list(subtitles)
            seen_urls.add(stream_url)
            streams.append(stream)

    if not streams:
        return [], "fancdn:NO_PLAYABLE_URL"
    return streams, None

def _videocdn_decode_xor59(value: Any) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    try:
        decoded = base64.b64decode(text, validate=True)
        result = bytes((byte ^ 59) for byte in decoded).decode("utf-8")
        if result:
            return result
    except Exception:
        pass
    return text


def _videocdn_parse_config_payload(text: str) -> Optional[Dict[str, Any]]:
    raw = str(text or "").strip()
    if not raw:
        return None
    candidates = [raw]
    try:
        candidates.append(base64.b64decode(raw, validate=True).decode("utf-8"))
    except Exception:
        pass
    for candidate in candidates:
        try:
            payload = json.loads(candidate)
        except (TypeError, ValueError):
            continue
        if isinstance(payload, dict):
            return payload
    return None


def _videocdn_config(
    fetch_text: Optional[TextFetcher],
) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    global _VIDEOCDN_CONFIG_EXPIRES_AT, _VIDEOCDN_CONFIG_CACHE
    if fetch_text is None:
        return None, "ADAPTER_REQUEST_UNAVAILABLE"
    now = time.monotonic()
    with _VIDEOCDN_CONFIG_LOCK:
        if _VIDEOCDN_CONFIG_CACHE and now < _VIDEOCDN_CONFIG_EXPIRES_AT:
            return dict(_VIDEOCDN_CONFIG_CACHE), None

    errors: List[str] = []
    for mirror in VIDEOCDN_CONFIG_MIRRORS:
        url = mirror.rstrip("/") + VIDEOCDN_CONFIG_PATH
        try:
            text, error = fetch_text(
                url,
                {
                    "User-Agent": VIDEOCDN_DEFAULT_USER_AGENT,
                    "Accept": "text/plain,*/*;q=0.8",
                    "Cache-Control": "no-cache",
                },
            )
        except Exception as exc:
            errors.append(type(exc).__name__)
            continue
        if error:
            errors.append(str(error)[:120])
            continue
        payload = _videocdn_parse_config_payload(str(text or ""))
        if not payload:
            errors.append("INVALID_CONFIG")
            continue
        with _VIDEOCDN_CONFIG_LOCK:
            _VIDEOCDN_CONFIG_CACHE = dict(payload)
            _VIDEOCDN_CONFIG_EXPIRES_AT = time.monotonic() + _VIDEOCDN_CONFIG_TTL_SECONDS
        return dict(payload), None
    return None, "videocdn:CONFIG_UNAVAILABLE:" + ";".join(errors[:4])


def _videocdn_source_parts(source: Dict[str, Any]) -> Optional[Tuple[str, str]]:
    key = str(_source_key(source) or "").strip()
    if not key:
        return None
    if key.startswith(("http://", "https://")):
        key = urlparse(key).path
    parts = [unescape(part).strip() for part in key.split("/") if part.strip()]
    if len(parts) < 2:
        return None
    content_type, content_id = parts[0], parts[1]
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", content_type):
        return None
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,160}", content_id):
        return None
    return content_type, content_id


def _videocdn_hls_variants(master_url: str, playlist: str) -> List[Tuple[str, str]]:
    variants: List[Tuple[str, str]] = []
    seen = set()
    lines = [line.strip() for line in str(playlist or "").splitlines() if line.strip()]

    def add(raw_url: str, quality: str) -> None:
        url = urljoin(master_url, unescape(str(raw_url or "").strip()))
        if not is_valid_stream_url(url):
            return
        key = (url, quality)
        if key in seen:
            return
        seen.add(key)
        variants.append((url, quality or "Не указано"))

    for index, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF") and index + 1 < len(lines):
            match = re.search(r"RESOLUTION=\d+x(\d+)", line, re.IGNORECASE)
            quality = f"{match.group(1)}p" if match else _filmix_quality(lines[index + 1])
            add(lines[index + 1], quality)
            continue
        match = re.match(r"^\./(\d{3,4})/.*?\.m3u8(?:\?.*)?$", line, re.IGNORECASE)
        if match:
            add(line, f"{match.group(1)}p")
    return variants


def _resolve_videocdn_l(
    source: Dict[str, Any],
    config: Dict[str, Any],
    *,
    fetch_text: TextFetcher,
    request_user_agent: str,
    season: Optional[int],
    episode: Optional[int],
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    parts = _videocdn_source_parts(source)
    if not parts:
        return [], "SOURCE_REF_INCOMPLETE"
    content_type, content_id = parts
    template = _videocdn_decode_xor59(config.get("t2"))
    if not template.startswith(("http://", "https://")):
        return [], "videocdn:INVALID_LUMEX_TEMPLATE"
    endpoint = (
        template.replace("{contentType}", quote(content_type, safe=""))
        .replace("{contentId}", quote(content_id, safe=""))
    )
    if "{" in endpoint or "}" in endpoint:
        return [], "videocdn:UNRESOLVED_TEMPLATE"

    user_agent = str(config.get("ul") or request_user_agent or VIDEOCDN_DEFAULT_USER_AGENT).strip()
    headers = {
        "User-Agent": user_agent,
        "Accept": "application/json,text/plain;q=0.9,*/*;q=0.8",
        "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
    }
    try:
        body, error = fetch_text(endpoint, headers)
    except Exception as exc:
        return [], f"videocdn:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"videocdn:{error}"
    try:
        payload = json.loads(str(body or ""))
    except (TypeError, ValueError):
        return [], "videocdn:INVALID_LUMEX_JSON"
    if not isinstance(payload, dict):
        return [], "videocdn:INVALID_LUMEX_RESPONSE"
    raw_url = payload.get("url")
    if raw_url is None and isinstance(payload.get("data"), dict):
        raw_url = payload["data"].get("url")
    master_url = urljoin(endpoint, str(raw_url or "").strip())
    if not is_valid_stream_url(master_url):
        return [], "videocdn:NO_PLAYLIST_URL"

    playlist_headers = {"User-Agent": user_agent, "Referer": endpoint}
    try:
        playlist, playlist_error = fetch_text(master_url, playlist_headers)
    except Exception as exc:
        return [], f"videocdn:{type(exc).__name__}:{str(exc)[:120]}"
    if playlist_error:
        return [], f"videocdn:{playlist_error}"
    variants = _videocdn_hls_variants(master_url, str(playlist or ""))
    if not variants and master_url.lower().split("?", 1)[0].endswith(".m3u8"):
        variants = [(master_url, "Не указано")]

    merged = _merged_source(source)
    voice = str(_first(merged, ("tran", "translation", "voice", "audio")) or "Не указано")
    language = str(_first(merged, ("lang", "language")) or "")
    result: List[Dict[str, Any]] = []
    for stream_url, quality in variants:
        stream = _stream_metadata(
            source,
            6,
            stream_url,
            voice=voice,
            language=language,
            quality=quality,
            user_agent=user_agent,
        )
        stream["headers"] = {"User-Agent": user_agent, "Referer": endpoint}
        if season is not None:
            stream["season"] = int(season)
        if episode is not None:
            stream["episode"] = int(episode)
        result.append(stream)
    return (result, None) if result else ([], "videocdn:NO_PLAYABLE_URL")


def _resolve_videocdn(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
    request_user_agent: str,
    season: Optional[int],
    episode: Optional[int],
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    if _videocdn_source_parts(source) is None:
        return [], "SOURCE_REF_INCOMPLETE"
    config, error = _videocdn_config(fetch_text)
    if error or not config:
        return [], error or "videocdn:CONFIG_UNAVAILABLE"
    engines = config.get("ll")
    if isinstance(engines, str):
        engines = [item.strip() for item in engines.split(",") if item.strip()]
    if not isinstance(engines, list):
        engines = []
    errors: List[str] = []
    for engine in [str(item).strip() for item in engines]:
        # Current ext6 config uses 's' first and legacy 'l' as a fallback.
        # The old 's' endpoint is host/CSRF stateful and is skipped when it is
        # unavailable; this mirrors the APK's provider fall-through behavior.
        if engine == "l":
            streams, engine_error = _resolve_videocdn_l(
                source,
                config,
                fetch_text=fetch_text,
                request_user_agent=request_user_agent,
                season=season,
                episode=episode,
            )
            if streams:
                return streams, None
            if engine_error:
                errors.append(engine_error)
        elif engine:
            errors.append(f"engine_{engine}:UNAVAILABLE")
    if "l" not in engines:
        return [], "videocdn:ACTIVE_ENGINE_UNSUPPORTED:" + ",".join(str(item) for item in engines)
    return [], "videocdn:NO_PLAYABLE_URL:" + ";".join(errors[:4])


def _awmzone_decode_xor59(value: Any) -> str:
    return _videocdn_decode_xor59(value)


def _awmzone_fetch_config_payload(
    path: str,
    fetch_text: Optional[TextFetcher],
) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    if fetch_text is None:
        return None, "ADAPTER_REQUEST_UNAVAILABLE"
    errors: List[str] = []
    for mirror in AWMZONE_CONFIG_MIRRORS:
        try:
            text, error = fetch_text(
                mirror.rstrip("/") + path,
                {
                    "User-Agent": AWMZONE_DEFAULT_USER_AGENT,
                    "Accept": "text/plain,*/*;q=0.8",
                    "Cache-Control": "no-cache",
                },
            )
        except Exception as exc:
            errors.append(type(exc).__name__)
            continue
        if error:
            errors.append(str(error)[:120])
            continue
        payload = _videocdn_parse_config_payload(str(text or ""))
        if payload:
            return payload, None
        errors.append("INVALID_CONFIG")
    return None, "awmzone:CONFIG_UNAVAILABLE:" + ";".join(errors[:4])


def _awmzone_config(fetch_text: Optional[TextFetcher]) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    global _AWMZONE_CONFIG_CACHE, _AWMZONE_CONFIG_EXPIRES_AT
    now = time.monotonic()
    with _AWMZONE_CONFIG_LOCK:
        if _AWMZONE_CONFIG_CACHE and now < _AWMZONE_CONFIG_EXPIRES_AT:
            return dict(_AWMZONE_CONFIG_CACHE), None
    payload, error = _awmzone_fetch_config_payload(AWMZONE_CONFIG_PATH, fetch_text)
    if error or not payload:
        return None, error or "awmzone:CONFIG_UNAVAILABLE"
    with _AWMZONE_CONFIG_LOCK:
        _AWMZONE_CONFIG_CACHE = dict(payload)
        _AWMZONE_CONFIG_EXPIRES_AT = time.monotonic() + _AWMZONE_CONFIG_TTL_SECONDS
    return dict(payload), None


def _awmzone_runtime_bases(fetch_text: Optional[TextFetcher]) -> Tuple[List[str], Optional[str]]:
    global _AWMZONE_ENDPOINT_CACHE, _AWMZONE_ENDPOINT_EXPIRES_AT
    now = time.monotonic()
    with _AWMZONE_CONFIG_LOCK:
        if _AWMZONE_ENDPOINT_CACHE and now < _AWMZONE_ENDPOINT_EXPIRES_AT:
            return list(_AWMZONE_ENDPOINT_CACHE), None
    payload, error = _awmzone_fetch_config_payload(AWMZONE_ENDPOINT_MAP_PATH, fetch_text)
    if error or not payload:
        return [], error or "awmzone:ENDPOINT_MAP_UNAVAILABLE"
    raw_entries = payload.get("9")
    if not isinstance(raw_entries, list):
        return [], "awmzone:ENDPOINT_MAP_MISSING_TYPE9"
    result: List[str] = []
    seen = set()
    for raw in raw_entries:
        value = str(raw or "").strip().rstrip("/")
        parsed = urlparse(value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            continue
        normalized = f"{parsed.scheme}://{parsed.netloc}" + (parsed.path.rstrip("/") if parsed.path not in {"", "/"} else "")
        if normalized not in seen:
            seen.add(normalized)
            result.append(normalized)
    if not result:
        return [], "awmzone:NO_RUNTIME_ENDPOINTS"
    with _AWMZONE_CONFIG_LOCK:
        _AWMZONE_ENDPOINT_CACHE = list(result)
        _AWMZONE_ENDPOINT_EXPIRES_AT = time.monotonic() + _AWMZONE_CONFIG_TTL_SECONDS
    return result, None


def _awmzone_source_key(source: Dict[str, Any]) -> Optional[str]:
    value = str(_source_key(source) or "").strip()
    if not value or value.startswith(("http://", "https://")):
        return None
    if len(value) > 512 or not re.fullmatch(r"[A-Za-z0-9._~-]+", value):
        return None
    return value


def _awmzone_headers(config: Dict[str, Any], *, referer: str = "") -> Dict[str, str]:
    raw = config.get("h")
    headers = _hdrezka_safe_headers(raw if isinstance(raw, dict) else {})
    user_agent = str(config.get("u") or AWMZONE_DEFAULT_USER_AGENT).strip()
    headers["User-Agent"] = user_agent
    if referer:
        parsed = urlparse(referer)
        root = f"{parsed.scheme}://{parsed.netloc}/" if parsed.scheme and parsed.netloc else referer
        headers["Referer"] = root
    return headers


def _awmzone_pattern_matches(pattern_text: str, text: str) -> Optional[re.Match[str]]:
    raw = str(pattern_text or "")
    if not raw:
        return None
    candidates: List[str] = []
    current = raw
    for _ in range(4):
        if current not in candidates:
            candidates.append(current)
        if "\\\\" not in current:
            break
        current = current.replace("\\\\", "\\")
    for candidate in candidates:
        try:
            match = re.search(candidate, text, re.IGNORECASE | re.DOTALL)
        except re.error:
            continue
        if match:
            return match
    return None


def _awmzone_player_payload(
    page: str,
    config: Dict[str, Any],
    *,
    season: Optional[int],
    episode: Optional[int],
) -> Tuple[Optional[str], Optional[str]]:
    pattern_text = _awmzone_decode_xor59(config.get("dp"))
    match = _awmzone_pattern_matches(pattern_text, str(page or ""))
    if not match:
        return None, "PLAYERJS_PAYLOAD_NOT_FOUND"
    try:
        decoded = base64.b64decode(unescape(match.group(1)), validate=False).decode("utf-8")
        payload = json.loads(decoded)
    except Exception:
        return None, "INVALID_PLAYERJS_JSON"
    if not isinstance(payload, dict):
        return None, "INVALID_PLAYERJS_PAYLOAD"
    raw_file = payload.get("file")
    if isinstance(raw_file, str):
        return raw_file, None
    if not isinstance(raw_file, list) or season is None or episode is None:
        return None, "PLAYERJS_FILE_MISSING"
    target = f"S{int(season)}E{int(episode)}"

    def search(value: Any) -> Optional[str]:
        if isinstance(value, dict):
            if str(value.get("id") or "") == target and isinstance(value.get("file"), str):
                return str(value["file"])
            folder = value.get("folder")
            if isinstance(folder, list):
                found = search(folder)
                if found:
                    return found
            for child in value.values():
                if child is folder:
                    continue
                found = search(child)
                if found:
                    return found
        elif isinstance(value, list):
            for child in value:
                found = search(child)
                if found:
                    return found
        return None

    selected = search(raw_file)
    return (selected, None) if selected else (None, "EPISODE_FILE_NOT_FOUND")


def _awmzone_decode_playerjs_file(raw_file: str, config: Dict[str, Any]) -> Tuple[Optional[str], Optional[str]]:
    value = str(raw_file or "").strip()
    if not value:
        return None, "EMPTY_PLAYERJS_FILE"
    if value.startswith("#"):
        if len(value) < 3:
            return None, "INVALID_PLAYERJS_FILE"
        value = value[2:]
    decoded_cfg = _awmzone_decode_xor59(config.get("pjs"))
    try:
        pjs = json.loads(decoded_cfg)
    except (TypeError, ValueError):
        return None, "INVALID_PLAYERJS_CONFIG"
    if not isinstance(pjs, dict):
        return None, "INVALID_PLAYERJS_CONFIG"
    marker_prefix = str(pjs.get("fs") or "")
    keys = pjs.get("bk")
    if not isinstance(keys, list):
        return None, "INVALID_PLAYERJS_KEYS"
    for raw_key in reversed(keys):
        key = str(raw_key or "")
        if not key:
            continue
        marker = marker_prefix + base64.b64encode(key.encode("utf-8")).decode("ascii")
        value = value.replace(marker, "")
    try:
        return base64.b64decode(value, validate=False).decode("utf-8"), None
    except Exception:
        # Some current mirrors can already expose the decoded {label}URL form.
        if re.search(r"\{[^}]+\}\s*https?://", value):
            return value, None
        return None, "PLAYERJS_DECODE_FAILED"


def _awmzone_tracks(decoded_file: str) -> List[Tuple[str, str]]:
    result: List[Tuple[str, str]] = []
    seen = set()
    text = unescape(str(decoded_file or "")).replace("\\/", "/")
    for match in re.finditer(r"\{(.*?)\}([^;]+)", text):
        label = re.sub(r"\s+", " ", match.group(1)).strip() or "Не указано"
        raw_url = match.group(2).strip().strip('"\'')
        if not is_valid_stream_url(raw_url):
            continue
        key = (label, raw_url)
        if key not in seen:
            seen.add(key)
            result.append(key)
    return result


def _awmzone_playlist_variants(track_url: str, playlist: str) -> List[Tuple[str, str]]:
    result: List[Tuple[str, str]] = []
    seen = set()
    body = unescape(str(playlist or "")).replace("\\/", "/")

    def add(url: str, quality: str) -> None:
        resolved = urljoin(track_url, str(url or "").strip())
        if not is_valid_stream_url(resolved):
            return
        key = (resolved, quality)
        if key not in seen:
            seen.add(key)
            result.append(key)

    for line in body.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = re.search(r"(\d{3,4})\.mp4(?:\?|$)", stripped, re.IGNORECASE)
        if match:
            add(stripped, f"{match.group(1)}p")
    for url, quality in _videocdn_hls_variants(track_url, body):
        add(url, quality)
    return result


def _resolve_awmzone(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
    season: Optional[int],
    episode: Optional[int],
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    source_key = _awmzone_source_key(source)
    if not source_key:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"
    config, error = _awmzone_config(fetch_text)
    if error or not config:
        return [], error or "awmzone:CONFIG_UNAVAILABLE"
    bases, error = _awmzone_runtime_bases(fetch_text)
    if error or not bases:
        return [], error or "awmzone:NO_RUNTIME_ENDPOINTS"
    template = _awmzone_decode_xor59(config.get("k"))
    if "{key}" not in template:
        return [], "awmzone:INVALID_EMBED_TEMPLATE"
    episode_pair = _alloha_parse_episode_key(_alloha_episode_key(source))
    target_season = int(season) if season is not None else (episode_pair[0] if episode_pair else None)
    target_episode = int(episode) if episode is not None else (episode_pair[1] if episode_pair else None)
    merged = _merged_source(source)
    language = str(_first(merged, ("lang", "language")) or "")
    user_agent = str(config.get("u") or AWMZONE_DEFAULT_USER_AGENT).strip()
    errors: List[str] = []

    for base in bases:
        page_url = base.rstrip("/") + template.replace("{key}", quote(source_key, safe=""))
        if "{" in page_url or "}" in page_url:
            errors.append("UNRESOLVED_TEMPLATE")
            continue
        try:
            page, page_error = fetch_text(page_url, _awmzone_headers(config))
        except Exception as exc:
            errors.append(f"PAGE_{type(exc).__name__}")
            continue
        if page_error or not isinstance(page, str):
            errors.append("PAGE:" + str(page_error)[:90])
            continue
        raw_file, file_error = _awmzone_player_payload(
            page, config, season=target_season, episode=target_episode,
        )
        if file_error or raw_file is None:
            errors.append("PLAYER:" + str(file_error)[:90])
            continue
        decoded_file, decode_error = _awmzone_decode_playerjs_file(raw_file, config)
        if decode_error or decoded_file is None:
            errors.append("DECODE:" + str(decode_error)[:90])
            continue
        tracks = _awmzone_tracks(decoded_file)
        if not tracks:
            errors.append("NO_TRACKS")
            continue
        streams: List[Dict[str, Any]] = []
        headers = _awmzone_headers(config, referer=page_url)
        for voice, track_url in tracks:
            try:
                playlist, playlist_error = fetch_text(track_url, headers)
            except Exception as exc:
                playlist, playlist_error = None, type(exc).__name__
            variants: List[Tuple[str, str]] = []
            if not playlist_error and isinstance(playlist, str):
                variants = _awmzone_playlist_variants(track_url, playlist)
            if not variants and track_url.lower().split("?", 1)[0].endswith((".m3u8", ".mp4")):
                variants = [(track_url, _filmix_quality(track_url))]
            if playlist_error and not variants:
                errors.append("PLAYLIST:" + str(playlist_error)[:70])
            for stream_url, quality in variants:
                stream = _stream_metadata(
                    source, 9, stream_url,
                    voice=voice, language=language,
                    quality=quality or "Не указано", user_agent=user_agent,
                )
                stream["headers"] = dict(headers)
                if target_season is not None:
                    stream["season"] = target_season
                if target_episode is not None:
                    stream["episode"] = target_episode
                streams.append(stream)
        if streams:
            return streams, None
    if errors:
        return [], "awmzone:NO_PLAYABLE_URL:" + ";".join(errors[:5])
    return [], "awmzone:NO_PLAYABLE_URL"


def _alloha_decode_config_value(value: Any) -> Any:
    if not isinstance(value, str):
        return value
    text = value.strip()
    if not text:
        return text
    try:
        decoded = base64.b64decode(text, validate=True)
        candidate = bytes((byte ^ 59) for byte in decoded).decode("utf-8")
        if candidate:
            return candidate
    except Exception:
        pass
    return value


def _alloha_fetch_json_config(
    path: str,
    fetch_text: Optional[TextFetcher],
) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    if fetch_text is None:
        return None, "ADAPTER_REQUEST_UNAVAILABLE"
    errors: List[str] = []
    for mirror in ALLOHA_CONFIG_MIRRORS:
        try:
            text, error = fetch_text(
                mirror.rstrip("/") + path,
                {"User-Agent": ALLOHA_DEFAULT_USER_AGENT, "Accept": "text/plain,*/*;q=0.8"},
            )
        except Exception as exc:
            errors.append(type(exc).__name__)
            continue
        if error:
            errors.append(str(error)[:120])
            continue
        payload = _videocdn_parse_config_payload(str(text or ""))
        if payload:
            return payload, None
        errors.append("INVALID_CONFIG")
    return None, "alloha:CONFIG_UNAVAILABLE:" + ";".join(errors[:4])


def _alloha_config(fetch_text: Optional[TextFetcher]) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    global _ALLOHA_CONFIG_EXPIRES_AT, _ALLOHA_CONFIG_CACHE
    now = time.monotonic()
    with _ALLOHA_CONFIG_LOCK:
        if _ALLOHA_CONFIG_CACHE and now < _ALLOHA_CONFIG_EXPIRES_AT:
            return dict(_ALLOHA_CONFIG_CACHE), None
    payload, error = _alloha_fetch_json_config(ALLOHA_CONFIG_PATH, fetch_text)
    if error or not payload:
        return None, error or "alloha:CONFIG_UNAVAILABLE"
    with _ALLOHA_CONFIG_LOCK:
        _ALLOHA_CONFIG_CACHE = dict(payload)
        _ALLOHA_CONFIG_EXPIRES_AT = time.monotonic() + _ALLOHA_CONFIG_TTL_SECONDS
    return dict(payload), None


def _alloha_runtime_endpoints(
    fetch_text: Optional[TextFetcher],
) -> Tuple[List[Tuple[str, str, str]], Optional[str]]:
    global _ALLOHA_ENDPOINT_CACHE, _ALLOHA_ENDPOINT_EXPIRES_AT
    now = time.monotonic()
    with _ALLOHA_CONFIG_LOCK:
        if _ALLOHA_ENDPOINT_CACHE and now < _ALLOHA_ENDPOINT_EXPIRES_AT:
            return list(_ALLOHA_ENDPOINT_CACHE), None
    payload, error = _alloha_fetch_json_config(ALLOHA_ENDPOINT_MAP_PATH, fetch_text)
    if error or not payload:
        return [], error or "alloha:ENDPOINT_MAP_UNAVAILABLE"
    raw_entries = payload.get("8")
    if not isinstance(raw_entries, list):
        return [], "alloha:ENDPOINT_MAP_MISSING_TYPE8"
    endpoints: List[Tuple[str, str, str]] = []
    for raw in raw_entries:
        text = str(raw or "").strip()
        if ";" not in text:
            continue
        referer, backend = text.split(";", 1)
        referer = referer.strip()
        backend = backend.strip()
        if "?token=" not in backend:
            continue
        provider_base, runtime_token = backend.split("?token=", 1)
        provider_base = provider_base.rstrip("/")
        runtime_token = runtime_token.split("&", 1)[0].strip()
        if not referer.startswith(("http://", "https://")):
            continue
        if not provider_base.startswith(("http://", "https://")):
            continue
        if not runtime_token or len(runtime_token) > 512:
            continue
        endpoints.append((referer, provider_base, runtime_token))
    if not endpoints:
        return [], "alloha:NO_RUNTIME_ENDPOINTS"
    with _ALLOHA_CONFIG_LOCK:
        _ALLOHA_ENDPOINT_CACHE = list(endpoints)
        _ALLOHA_ENDPOINT_EXPIRES_AT = time.monotonic() + _ALLOHA_CONFIG_TTL_SECONDS
    return endpoints, None


def _alloha_download_key(source: Dict[str, Any]) -> Optional[str]:
    key = str(_source_key(source) or "").strip()
    if not key or key.startswith(("http://", "https://")):
        return None
    if len(key) > 512 or not re.fullmatch(r"[A-Za-z0-9._~-]+", key):
        return None
    return key


def _alloha_episode_key(source: Dict[str, Any]) -> str:
    merged = _merged_source(source)
    return str(_first(merged, ("episode_key", "episodeKey")) or "").strip()


def _alloha_parse_episode_key(value: str) -> Optional[Tuple[int, int]]:
    match = re.fullmatch(r"S(\d+)E(\d+)", str(value or ""), re.IGNORECASE)
    if not match:
        return None
    season, episode = int(match.group(1)), int(match.group(2))
    return (season, episode) if season > 0 and episode >= 0 else None


def _alloha_headers_from_config(config: Dict[str, Any], field: str) -> Dict[str, str]:
    decoded = _alloha_decode_config_value(config.get(field))
    if not isinstance(decoded, str) or not decoded.strip():
        return {}
    try:
        value = json.loads(decoded)
    except (TypeError, ValueError):
        return {}
    if not isinstance(value, dict):
        return {}
    return {str(k): str(v) for k, v in value.items() if isinstance(k, str) and v is not None}


def _alloha_page_url(provider_base: str, runtime_token: str, download_key: str, translation: str = "") -> str:
    url = (
        provider_base.rstrip("/")
        + "/?token=" + quote(runtime_token, safe="")
        + "&token_movie=" + quote(download_key, safe="")
    )
    if translation:
        url += "&translation=" + quote(str(translation), safe="")
    return url


def _alloha_fetch_page(
    page_url: str,
    referer: str,
    provider_base: str,
    config: Dict[str, Any],
    fetch_text: TextFetcher,
) -> Tuple[Optional[str], Optional[str]]:
    user_agent = str(config.get("u") or ALLOHA_DEFAULT_USER_AGENT)
    headers = _alloha_headers_from_config(config, "ih")
    headers.update({
        "User-Agent": user_agent,
        "Referer": referer,
        "Host": urlparse(provider_base).netloc,
    })
    try:
        text, error = fetch_text(page_url, headers)
    except Exception as exc:
        return None, f"{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return None, str(error)
    if not isinstance(text, str) or not text.strip():
        return None, "EMPTY_PAGE"
    return text, None


def _alloha_js_string(value: str) -> str:
    text = str(value or "")
    try:
        # JSON.parse('...') payloads primarily use JSON-compatible backslash escapes.
        escaped = text.replace('\\\'', "'").replace('"', '\\"')
        return json.loads('"' + escaped + '"')
    except Exception:
        return text.replace("\\/", "/").replace("\\'", "'").replace('\\"', '"')


def _alloha_page_model(page_url: str, html: str) -> Dict[str, Any]:
    text = unescape(str(html or ""))
    scripts = re.findall(r"<script[^>]+src=[\"']([^\"']+)", text, re.IGNORECASE)
    playerjs = ""
    for raw in scripts:
        candidate = urljoin(page_url, raw)
        if re.search(r"player(?:\.min)?", candidate, re.IGNORECASE):
            playerjs = candidate
            break
    file_match = re.search(r"[\"']file[\"']\s*:\s*[\"']([^\"']+)", text, re.IGNORECASE)
    subtitle_match = re.search(r"[\"']subtitle[\"']\s*:\s*[\"']([^\"']*)", text, re.IGNORECASE)
    id_match = re.search(r"var\s+id\s*?=\s*?(\d+)\s*;", text, re.IGNORECASE)
    translations: List[Dict[str, Any]] = []
    pattern = re.compile(
        r'<(?:button|a)\s+data-translation-m="(\d+)"\s+data-id-file="(\d+)"[^>]+class="([^"]+)">([^<]+)',
        re.IGNORECASE,
    )
    for match in pattern.finditer(text):
        translations.append({
            "translation": match.group(1),
            "id_file": match.group(2),
            "label": re.sub(r"\s+", " ", unescape(match.group(4))).strip(),
            "active": "active" in match.group(3).casefold(),
        })
    file_list = None
    serial_match = re.search(r"const\s+fileList\s*=\s*JSON\.parse\('(.+?)'\);\s*</script>", text, re.IGNORECASE | re.DOTALL)
    if serial_match:
        try:
            file_list = json.loads(_alloha_js_string(serial_match.group(1)))
        except (TypeError, ValueError):
            file_list = None
    return {
        "page_url": page_url,
        "html": text,
        "playerjs_url": playerjs,
        "file": file_match.group(1) if file_match else "",
        "subtitle": subtitle_match.group(1) if subtitle_match else "",
        "id": id_match.group(1) if id_match else "",
        "translations": translations,
        "file_list": file_list,
    }


def _alloha_extract_assignment_context(text: str, runtime_token: str) -> Dict[str, Any]:
    assignments: Dict[str, str] = {}
    for match in re.finditer(r"(?:var|;)\s+(\w+)\s*?=\s*?[\"']([^\"']+)", str(text or "")):
        assignments[match.group(1)] = match.group(2)
    suffix = ""
    dotted = ""
    short_hex = ""
    for value in assignments.values():
        if value == runtime_token or len(value) <= 10 or not re.fullmatch(r"[-0-9A-Za-z._]+", value):
            continue
        if len(value) < 20 and re.fullmatch(r"[0-9a-f]+", value):
            short_hex = short_hex or value
            continue
        if "." not in value:
            suffix = suffix or value
        elif value.count(".") == 1:
            dotted = dotted or value
    return {"assignments": assignments, "suffix": suffix, "dotted": dotted, "short_hex": short_hex}


def _alloha_vars_contract(text: str) -> Dict[str, str]:
    parts = [part.strip() for part in str(text or "").split(",")]
    result = {"path": "", "referer_param": "", "token_var": "", "id_var": "", "other_var": ""}
    if parts:
        result["path"] = parts[0]
    if len(parts) > 1:
        result["referer_param"] = parts[1]
    for raw in parts[2:]:
        if "=" not in raw:
            continue
        label, name = raw.split("=", 1)
        label, name = label.strip(), name.strip()
        if label == "token":
            result["token_var"] = name
        elif label == "id":
            result["id_var"] = name
        elif name:
            result["other_var"] = name
    return result


def _alloha_password(
    model: Dict[str, Any],
    config: Dict[str, Any],
    runtime_token: str,
    provider_base: str,
    fetch_post_form_text: PostFormFetcher,
    extra_script: str = "",
) -> Tuple[Optional[str], Optional[str]]:
    playerjs_url = str(model.get("playerjs_url") or "")
    if not playerjs_url:
        return None, "NO_PLAYERJS_URL"
    user_agent = str(config.get("u") or ALLOHA_DEFAULT_USER_AGENT)
    helper_errors: List[str] = []
    vars_text = None
    for mirror in ALLOHA_CONFIG_MIRRORS:
        try:
            response, error = fetch_post_form_text(
                mirror.rstrip("/") + "/getAlloha",
                {"User-Agent": user_agent},
                {
                    "client_time": str(int(time.time() * 1000)),
                    "playerjsUrl": playerjs_url,
                    "path": "vars",
                },
            )
        except Exception as exc:
            helper_errors.append(type(exc).__name__)
            continue
        if error:
            helper_errors.append(str(error)[:100])
            continue
        if isinstance(response, str) and response.strip():
            vars_text = response.strip()
            break
    if not vars_text:
        return None, "VARS_HELPER_FAILED:" + ";".join(helper_errors[:3])
    contract = _alloha_vars_contract(vars_text)
    context = _alloha_extract_assignment_context(str(model.get("html") or "") + "\n" + str(extra_script or ""), runtime_token)
    assignments = context["assignments"]
    path = contract.get("path") or ""
    suffix = context.get("suffix") or ""
    if not path or not suffix:
        return None, "DECRYPT_CONTEXT_INCOMPLETE"
    form: Dict[str, str] = {}
    token_var = contract.get("token_var") or ""
    id_var = contract.get("id_var") or ""
    other_var = contract.get("other_var") or ""
    if token_var:
        form[token_var] = runtime_token
    if id_var:
        form[id_var] = str(model.get("id") or "")
    if other_var:
        form[other_var] = context.get("dotted") or assignments.get(other_var, "")
    base_page = str(model.get("page_url") or "").split("?", 1)[0]
    endpoint = base_page + path.lstrip("/") + suffix
    headers = {
        "User-Agent": user_agent,
        "Referer": str(model.get("page_url") or ""),
        "Origin": provider_base,
    }
    try:
        response, error = fetch_post_form_text(endpoint, headers, form)
    except Exception as exc:
        return None, f"PASSWORD_{type(exc).__name__}:{str(exc)[:100]}"
    if error:
        return None, f"PASSWORD_{error}"
    password = str(response or "").strip()
    return (password, None) if password else (None, "EMPTY_PASSWORD")


def _alloha_decrypt_payload(payload: str, password: str) -> Tuple[Optional[str], Optional[str]]:
    text = str(payload or "").strip()
    if not text.startswith("#"):
        return text, None
    # Legacy Mb.g.e() always drops the two-character encrypted marker before splitting by "##".
    if len(text) < 3:
        return None, "INVALID_ENCRYPTED_PAYLOAD"
    encoded = text[2:]
    parts = encoded.split("##")
    if len(parts) != 3:
        return None, "INVALID_ENCRYPTED_PAYLOAD"
    try:
        ciphertext = base64.b64decode(parts[0])
        iv = bytes.fromhex(parts[1])
        salt = bytes.fromhex(parts[2])
        if len(iv) != 16:
            return None, "INVALID_AES_IV"
        import hashlib
        digest_material = password.encode("utf-8") + salt
        key = b""
        previous = b""
        while len(key) < 32:
            previous = hashlib.md5(previous + digest_material).digest()
            key += previous
        key = key[:32]
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        decryptor = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
        padded = decryptor.update(ciphertext) + decryptor.finalize()
        if not padded:
            return None, "EMPTY_AES_PAYLOAD"
        pad = padded[-1]
        if pad < 1 or pad > 16 or padded[-pad:] != bytes([pad]) * pad:
            return None, "INVALID_AES_PADDING"
        return padded[:-pad].decode("utf-8"), None
    except Exception as exc:
        return None, f"DECRYPT_{type(exc).__name__}"


def _alloha_file_candidates(raw: Any) -> List[Tuple[str, str]]:
    text = unescape(str(raw or "").strip()).replace("\\/", "/")
    result: List[Tuple[str, str]] = []
    seen = set()
    for comma_part in text.split(","):
        for part in re.split(r"\s+or\s+", comma_part, flags=re.IGNORECASE):
            label = "Не указано"
            body = part.strip().strip('"\'')
            match = re.match(r"^\{([^}]+)\}(.*)$", body)
            if match:
                label, body = match.group(1).strip(), match.group(2).strip()
            match2 = re.match(r"^\[([^]]+)\](.*)$", body)
            if match2:
                label, body = match2.group(1).strip(), match2.group(2).strip()
            urls = [body] if is_valid_stream_url(body) else re.findall(r"https?://[^\s,\"'<>]+", body)
            for url in urls:
                url = url.rstrip(")]};")
                if not is_valid_stream_url(url):
                    continue
                quality = _filmix_quality(label if label != "Не указано" else url)
                key = (url, quality)
                if key not in seen:
                    seen.add(key); result.append(key)
    return result


def _alloha_streams_from_file(
    source: Dict[str, Any],
    raw_file: str,
    *,
    voice: str,
    page_url: str,
    user_agent: str,
    fetch_text: TextFetcher,
    season: Optional[int],
    episode: Optional[int],
) -> Tuple[List[Dict[str, Any]], List[str]]:
    streams: List[Dict[str, Any]] = []
    errors: List[str] = []
    merged = _merged_source(source)
    language = str(_first(merged, ("lang", "language")) or "")
    for url, hinted_quality in _alloha_file_candidates(raw_file):
        variants = [(url, hinted_quality)]
        if urlparse(url).path.casefold().endswith(".m3u8"):
            try:
                playlist, error = fetch_text(url, {"User-Agent": user_agent, "Referer": page_url})
            except Exception as exc:
                playlist, error = None, type(exc).__name__
            if not error and isinstance(playlist, str):
                parsed = _videocdn_hls_variants(url, playlist)
                if parsed:
                    variants = parsed
            elif error:
                errors.append(str(error)[:100])
        for stream_url, quality in variants:
            stream = _stream_metadata(
                source, 8, stream_url,
                voice=voice or "Не указано", language=language,
                quality=quality or hinted_quality, user_agent=user_agent,
            )
            stream["headers"] = {"User-Agent": user_agent, "Referer": page_url}
            if season is not None: stream["season"] = int(season)
            if episode is not None: stream["episode"] = int(episode)
            streams.append(stream)
    return streams, errors


def _alloha_player_ajax(
    model: Dict[str, Any],
    translation: Optional[Dict[str, Any]],
    *,
    config: Dict[str, Any],
    provider_base: str,
    runtime_token: str,
    fetch_post_form_text: PostFormFetcher,
) -> Tuple[Optional[str], str, str, Optional[str]]:
    user_agent = str(config.get("u") or ALLOHA_DEFAULT_USER_AGENT)
    page_url = str(model.get("page_url") or "")
    endpoint = page_url.split("?", 1)[0]
    id_file = str((translation or {}).get("id_file") or model.get("id") or "")
    if not id_file:
        return None, "", "", "NO_ID_FILE"
    headers = _alloha_headers_from_config(config, "ph")
    headers.update({
        "User-Agent": user_agent,
        "Origin": provider_base,
        "Referer": page_url,
        "X-Requested-With": "XMLHttpRequest",
        "Accept": "*/*",
    })
    form = {"player_ajax": "1", "id_file": id_file, "token": runtime_token, "av1": "true"}
    try:
        response, error = fetch_post_form_text(endpoint, headers, form)
    except Exception as exc:
        return None, "", "", f"{type(exc).__name__}:{str(exc)[:100]}"
    if error:
        return None, "", "", str(error)
    try:
        payload = json.loads(str(response or ""))
    except (TypeError, ValueError):
        return None, "", "", "INVALID_AJAX_JSON"
    if not isinstance(payload, dict):
        return None, "", "", "INVALID_AJAX_RESPONSE"
    raw_file = str(payload.get("url") or "").strip()
    subtitle = str(payload.get("subtitle") or "").strip()
    tokenq = str(payload.get("tokenq") or "")
    if not raw_file:
        return None, subtitle, tokenq, "NO_AJAX_URL"
    return raw_file, subtitle, tokenq, None


def _alloha_series_direct_candidates(file_list: Any, season: int, episode: int) -> List[Tuple[str, str, str]]:
    node = file_list
    if isinstance(node, dict): node = node.get(str(season), node.get(season))
    if isinstance(node, dict): node = node.get(str(episode), node.get(episode))
    result: List[Tuple[str, str, str]] = []
    seen = set()
    def walk(value: Any, label: str = "") -> None:
        if isinstance(value, dict):
            voice = str(value.get("translation") or value.get("name") or value.get("title") or label or "Не указано")
            for key in ("file", "url", "src"):
                if key in value:
                    for url, quality in _alloha_file_candidates(value.get(key)):
                        item=(url,quality,voice)
                        if item not in seen: seen.add(item); result.append(item)
            for key, child in value.items():
                if key not in {"file","url","src"}: walk(child, str(key) if not label else label)
        elif isinstance(value, list):
            for child in value: walk(child,label)
        elif isinstance(value, str):
            for url,quality in _alloha_file_candidates(value):
                item=(url,quality,label or "Не указано")
                if item not in seen: seen.add(item); result.append(item)
    if node is not None: walk(node)
    return result


def _resolve_alloha(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
    fetch_post_form_text: Optional[PostFormFetcher],
    season: Optional[int],
    episode: Optional[int],
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    download_key = _alloha_download_key(source)
    if not download_key:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_text is None or fetch_post_form_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"
    config, error = _alloha_config(fetch_text)
    if error or not config: return [], error or "alloha:CONFIG_UNAVAILABLE"
    endpoints, error = _alloha_runtime_endpoints(fetch_text)
    if error or not endpoints: return [], error or "alloha:NO_RUNTIME_ENDPOINTS"
    episode_pair = _alloha_parse_episode_key(_alloha_episode_key(source))
    target_season = int(season) if season is not None else (episode_pair[0] if episode_pair else None)
    target_episode = int(episode) if episode is not None else (episode_pair[1] if episode_pair else None)
    user_agent = str(config.get("u") or ALLOHA_DEFAULT_USER_AGENT)
    errors: List[str] = []
    for referer, provider_base, runtime_token in endpoints:
        page_url = _alloha_page_url(provider_base, runtime_token, download_key)
        page, page_error = _alloha_fetch_page(page_url, referer, provider_base, config, fetch_text)
        if page_error or page is None:
            errors.append("PAGE:" + str(page_error)[:90]); continue
        model = _alloha_page_model(page_url, page)

        if target_season is not None and target_episode is not None and model.get("file_list") is not None:
            direct = _alloha_series_direct_candidates(model.get("file_list"), target_season, target_episode)
            collected: List[Dict[str, Any]] = []
            for raw_url, quality, voice in direct:
                sub, _ = _alloha_streams_from_file(
                    source, f"{{{quality}}}{raw_url}", voice=voice,
                    page_url=page_url, user_agent=user_agent, fetch_text=fetch_text,
                    season=target_season, episode=target_episode,
                )
                collected.extend(sub)
            if collected: return collected, None

        if model.get("playerjs_url"):
            translations = list(model.get("translations") or [])
            translations.sort(key=lambda item: (not bool(item.get("active")), str(item.get("translation") or "")))
            if not translations:
                translations = [None]
            collected: List[Dict[str, Any]] = []
            for translation in translations:
                raw_file, _subtitle, tokenq, ajax_error = _alloha_player_ajax(
                    model, translation, config=config, provider_base=provider_base,
                    runtime_token=runtime_token, fetch_post_form_text=fetch_post_form_text,
                )
                if ajax_error or not raw_file:
                    errors.append("AJAX:" + str(ajax_error)[:90]); continue
                decoded_file = raw_file
                if not _alloha_file_candidates(decoded_file) and decoded_file.startswith("#"):
                    password, password_error = _alloha_password(
                        model, config, runtime_token, provider_base,
                        fetch_post_form_text, extra_script=tokenq,
                    )
                    if password_error or not password:
                        errors.append("PASSWORD:" + str(password_error)[:80]); continue
                    decoded_file, decrypt_error = _alloha_decrypt_payload(decoded_file, password)
                    if decrypt_error or decoded_file is None:
                        errors.append("DECRYPT:" + str(decrypt_error)[:80]); continue
                voice = str((translation or {}).get("label") or _first(_merged_source(source), ("tran","translation","voice")) or "Не указано")
                sub, sub_errors = _alloha_streams_from_file(
                    source, decoded_file, voice=voice, page_url=page_url,
                    user_agent=user_agent, fetch_text=fetch_text,
                    season=target_season, episode=target_episode,
                )
                errors.extend("HLS:" + item for item in sub_errors[:2])
                collected.extend(sub)
            if collected: return collected, None
        elif model.get("file"):
            raw_file = str(model.get("file") or "")
            if raw_file.startswith("#"):
                password, password_error = _alloha_password(model, config, runtime_token, provider_base, fetch_post_form_text)
                if password_error or not password:
                    errors.append("PASSWORD:" + str(password_error)[:80]); continue
                raw_file, decrypt_error = _alloha_decrypt_payload(raw_file, password)
                if decrypt_error or raw_file is None:
                    errors.append("DECRYPT:" + str(decrypt_error)[:80]); continue
            sub, sub_errors = _alloha_streams_from_file(
                source, raw_file, voice="Не указано", page_url=page_url,
                user_agent=user_agent, fetch_text=fetch_text,
                season=target_season, episode=target_episode,
            )
            errors.extend("HLS:" + item for item in sub_errors[:2])
            if sub: return sub, None
    if errors:
        return [], "alloha:NO_PLAYABLE_URL:" + ";".join(errors[:5])
    return [], "alloha:NO_PLAYABLE_URL"


def _filmix_post_id(source: Dict[str, Any]) -> Optional[str]:
    merged = _merged_source(source)
    explicit = _first(merged, (
        "post_id", "postId", "filmix_id", "filmixId", "content_id", "contentId",
    ))
    candidates = [explicit, _source_key(source)]
    for raw in candidates:
        text = str(raw or "").strip()
        if not text:
            continue
        if text.startswith(("http://", "https://")):
            parsed = urlparse(text)
            match = re.search(r"/play/(\d+)(?:[-/]|$)", parsed.path, re.IGNORECASE)
        else:
            match = re.match(r"^(\d+)(?:-|$)", text)
        if match:
            return match.group(1)
    return None


def _filmix_base_candidates(source: Dict[str, Any]) -> List[str]:
    merged = _merged_source(source)
    values = [
        _first(merged, ("filmix_base_url", "filmixBaseUrl", "base_url", "baseUrl", "site_url", "siteUrl", "host")),
        *FILMIX_BASE_URLS,
    ]
    result: List[str] = []
    for raw in values:
        candidate = str(raw or "").strip().rstrip("/")
        if not candidate:
            continue
        parsed = urlparse(candidate)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            continue
        if parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
            continue
        if candidate not in result:
            result.append(candidate)
    return result


def _filmix_quality(value: Any) -> str:
    text = str(value or "").strip()
    match = re.search(r"(?<!\d)(2160|1440|1080|720|576|480|360|240|144)(?:p)?\b", text, re.IGNORECASE)
    return f"{match.group(1)}p" if match else "Не указано"


def _filmix_direct_urls(value: Any) -> List[Tuple[str, str]]:
    """Return only URLs actually present in Filmix response data.

    The legacy APK has a JavaScript/packed-link decoder for some historical
    values. Movia deliberately does not reconstruct an URL when the current
    response is opaque: such values are reported as unsupported instead.
    """
    result: List[Tuple[str, str]] = []
    seen = set()

    def add(raw_url: Any, label: Any = "") -> None:
        url = unescape(str(raw_url or "").strip()).replace("\\/", "/")
        if url.startswith("//"):
            url = "https:" + url
        if not is_valid_stream_url(url):
            return
        key = (url, str(label or ""))
        if key in seen:
            return
        seen.add(key)
        result.append((url, _filmix_quality(label or url)))

    def walk(raw: Any, label: Any = "") -> None:
        if isinstance(raw, dict):
            direct = _first(raw, ("url", "file", "src", "link", "links"))
            if direct is not None and not isinstance(direct, (dict, list, tuple)):
                add(direct, _first(raw, ("quality", "resolution", "label", "name")) or label)
            for key, item in raw.items():
                if key in {"url", "file", "src", "link"}:
                    continue
                walk(item, key if not label else label)
            return
        if isinstance(raw, (list, tuple)):
            for item in raw:
                walk(item, label)
            return
        text = unescape(str(raw or "").strip()).replace("\\/", "/")
        if not text:
            return
        if is_valid_stream_url(text):
            add(text, label)
            return
        for match in re.finditer(r"(?:https?:)?//[^\s,\"'<>]+", text, re.IGNORECASE):
            add(match.group(0).rstrip(")]};"), label)

    walk(value)
    return result


def _filmix_player_payload(text: str) -> Tuple[Dict[str, Any], Optional[str]]:
    try:
        payload = json.loads(text)
    except (TypeError, ValueError):
        return {}, "INVALID_JSON"
    if not isinstance(payload, dict):
        return {}, "INVALID_RESPONSE"
    message = payload.get("message")
    if not isinstance(message, dict):
        return {}, "NO_MESSAGE"
    translations = message.get("translations")
    if not isinstance(translations, dict):
        return {}, "NO_TRANSLATIONS"
    video = translations.get("video")
    if not isinstance(video, dict):
        return {}, "NO_VIDEO_MAP"
    return video, None


def _resolve_filmix(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
    fetch_post_form_text: Optional[PostFormFetcher],
    request_user_agent: str,
    season: Optional[int],
    episode: Optional[int],
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    post_id = _filmix_post_id(source)
    if not post_id:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_text is None or fetch_post_form_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    user_agent = request_user_agent or FILMIX_DEFAULT_USER_AGENT
    page_text = ""
    page_url = ""
    base_url = ""
    page_errors: List[str] = []
    for base in _filmix_base_candidates(source):
        candidate = f"{base}/play/{post_id}"
        headers = {"User-Agent": user_agent, "Accept": "text/html,*/*;q=0.8"}
        try:
            text, error = fetch_text(candidate, headers)
        except Exception as exc:
            page_errors.append(type(exc).__name__)
            continue
        if error or not isinstance(text, str) or not text.strip():
            page_errors.append(str(error or "EMPTY_PAGE")[:120])
            continue
        page_text = text
        page_url = candidate
        base_url = base
        break
    if not page_text:
        suffix = ":" + ";".join(page_errors[:3]) if page_errors else ""
        return [], "filmix:PAGE_REQUEST_FAILED" + suffix

    headers = {
        "User-Agent": user_agent,
        "Referer": page_url,
        "Origin": base_url,
        "Accept": "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With": "XMLHttpRequest",
        "Cache-Control": "no-cache",
        "Pragma": "no-cache",
    }
    endpoint = f"{base_url}{FILMIX_PLAYER_PATH}{int(time.time() * 1000)}"
    form = {"post_id": post_id, "showfull": "true"}
    try:
        response, error = fetch_post_form_text(endpoint, headers, form)
    except Exception as exc:
        return [], f"filmix:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"filmix:{error}"
    if not isinstance(response, str) or not response.strip():
        return [], "filmix:EMPTY_RESPONSE"

    video_map, parse_error = _filmix_player_payload(response)
    if parse_error:
        return [], f"filmix:{parse_error}"

    merged = _merged_source(source)
    fallback_voice = str(_first(merged, ("tran", "translation", "voice", "audio")) or "Не указано")
    streams: List[Dict[str, Any]] = []
    opaque_entries = 0
    for raw_voice, raw_value in video_map.items():
        voice = re.sub(r"\s+", " ", unescape(str(raw_voice or "")).strip()) or fallback_voice
        candidates = _filmix_direct_urls(raw_value)
        if not candidates and raw_value not in (None, "", [], {}):
            opaque_entries += 1
            continue
        for stream_url, quality in candidates:
            # In the legacy APK *.txt is an intermediate packed-link document,
            # not the final media URL. Never persist it as playback media.
            if urlparse(stream_url).path.casefold().endswith(".txt"):
                opaque_entries += 1
                continue
            stream = _stream_metadata(
                source,
                3,
                stream_url,
                voice=voice,
                language=str(_first(merged, ("lang", "language")) or ""),
                quality=quality,
                user_agent=user_agent,
            )
            stream["headers"] = {"User-Agent": user_agent, "Referer": page_url, "Origin": base_url}
            stream["translation"] = voice
            if season is not None:
                stream["season"] = int(season)
            if episode is not None:
                stream["episode"] = int(episode)
            streams.append(stream)

    if streams:
        return streams, None
    if opaque_entries:
        return [], "filmix:OPAQUE_LINK_DECODER_REQUIRED"
    return [], "filmix:NO_PLAYABLE_URL"


def _kinoteatr_page_url(source: Dict[str, Any]) -> Optional[str]:
    key = _source_key(source)
    if not key:
        return None
    key = key.strip()
    if key.startswith(("http://", "https://")):
        parsed = urlparse(key)
        if parsed.scheme in {"http", "https"} and parsed.netloc:
            return key
        return None

    merged = _merged_source(source)
    configured_base = _first(merged, (
        "kinoteatrBaseUrl", "kinoteatr_base_url",
        "baseUrl", "base_url", "siteUrl", "site_url",
    ))
    base = str(configured_base or KINOTEATR_BASE_URL).strip().rstrip("/")
    parsed_base = urlparse(base)
    if parsed_base.scheme not in {"http", "https"} or not parsed_base.netloc:
        base = KINOTEATR_BASE_URL

    clean_key = key.strip("/")
    if not clean_key:
        return None
    path = clean_key if clean_key.startswith("video/") else f"video/{clean_key}"
    return f"{base}/{path}/"


def _resolve_kinoteatr(
    source: Dict[str, Any],
    *,
    fetch_text: Optional[TextFetcher],
    request_user_agent: str,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    page_url = _kinoteatr_page_url(source)
    if not page_url:
        return [], "SOURCE_REF_INCOMPLETE"
    if fetch_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"

    try:
        page, error = fetch_text(
            page_url,
            {"User-Agent": request_user_agent or "Zona"},
        )
    except Exception as exc:
        return [], f"kinoteatr:{type(exc).__name__}:{str(exc)[:120]}"
    if error:
        return [], f"kinoteatr:{error}"
    if not isinstance(page, str) or not page.strip():
        return [], "kinoteatr:EMPTY_RESPONSE"

    category_match = re.search(
        r"<div\s+class=['\"]category_header_nolink['\"][^>]*>\s*"
        r'<h3[^>]*>([^<]+)',
        page,
        re.IGNORECASE,
    )
    translation = unescape(category_match.group(1).strip()) if category_match else ""

    title_match = re.search(
        r"<h1[^>]*itemprop=['\"]name['\"][^>]*>([^<]+)",
        page,
        re.IGNORECASE,
    )
    title = unescape(title_match.group(1).strip()) if title_match else ""
    language = ""
    if title:
        if re.search(r"русск|russian", title, re.IGNORECASE):
            language = "ru"
        elif re.search(r"англ|english", title, re.IGNORECASE):
            language = "en"

    source_matches = re.findall(
        r"data-video-src\s*?=\s*?['\"]([^'\"]+)",
        page,
        re.IGNORECASE,
    )
    if not source_matches:
        return [], "kinoteatr:NO_PLAYABLE_URL"

    parsed_page = urlparse(page_url)
    base_url = (
        f"{parsed_page.scheme}://{parsed_page.netloc}"
        if parsed_page.scheme and parsed_page.netloc
        else KINOTEATR_BASE_URL
    )
    streams: List[Dict[str, Any]] = []
    for raw_url in source_matches:
        stream_url = urljoin(base_url + "/", unescape(str(raw_url).strip()))
        if not is_valid_stream_url(stream_url):
            continue
        stream = _stream_metadata(
            source,
            14,
            stream_url,
            voice=str(translation or language or "Не указано"),
            language=language,
            quality="LQ",
            user_agent=request_user_agent or "Zona",
        )
        if translation:
            stream["translation"] = translation
        stream["resolution"] = "LQ"
        streams.append(stream)

    if not streams:
        return [], "kinoteatr:NO_PLAYABLE_URL"
    return streams, None

def resolve_local_source(
    source: Dict[str, Any],
    *,
    fetch_json: Optional[JsonFetcher] = None,
    fetch_text: Optional[TextFetcher] = None,
    fetch_post_text: Optional[TextFetcher] = None,
    fetch_post_form_text: Optional[PostFormFetcher] = None,
    client_time: Optional[str] = None,
    request_user_agent: str = "",
    season: Optional[int] = None,
    episode: Optional[int] = None,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    """Resolve a source record using a ported in-process adapter.

    Returning an explicit ADAPTER_NOT_PORTED result is intentional: the
    legacy APK has no generic stream endpoint that can be queried instead.
    """
    extractor = _extractor_id(source)
    if extractor is None:
        return [], "EXTRACTOR_UNKNOWN"
    name = extractor_name(extractor)
    blocker = LEGACY_ADAPTER_BLOCKERS.get(extractor)
    if blocker:
        return [], f"ADAPTER_BLOCKED:{name}:{blocker}"
    if extractor not in PORTED_EXTRACTOR_IDS:
        return [], f"ADAPTER_NOT_PORTED:{name}"

    if extractor == 1:
        return _resolve_mobilink(
            source,
            fetch_json=fetch_json,
            client_time=client_time,
            request_user_agent=request_user_agent or "Zona",
        )
    if extractor == 2:
        return _resolve_hdrezka(
            source,
            fetch_text=fetch_text,
            fetch_post_form_text=fetch_post_form_text,
            request_user_agent=request_user_agent or HDREZKA_DEFAULT_USER_AGENT,
            season=season,
            episode=episode,
        )
    if extractor == 3:
        return _resolve_filmix(
            source,
            fetch_text=fetch_text,
            fetch_post_form_text=fetch_post_form_text,
            request_user_agent=request_user_agent or FILMIX_DEFAULT_USER_AGENT,
            season=season,
            episode=episode,
        )
    if extractor == 6:
        return _resolve_videocdn(
            source,
            fetch_text=fetch_text,
            request_user_agent=request_user_agent or VIDEOCDN_DEFAULT_USER_AGENT,
            season=season,
            episode=episode,
        )
    if extractor == 8:
        return _resolve_alloha(
            source,
            fetch_text=fetch_text,
            fetch_post_form_text=fetch_post_form_text,
            season=season,
            episode=episode,
        )
    if extractor == 9:
        return _resolve_awmzone(
            source,
            fetch_text=fetch_text,
            season=season,
            episode=episode,
        )
    if extractor == 7:
        return _resolve_kinomania(
            source,
            fetch_text=fetch_text,
            request_user_agent=request_user_agent or "Zona",
        )
    if extractor == 33:
        return _resolve_cdnvideohub(source, fetch_text=fetch_text)
    if extractor == 35:
        return _resolve_fancdn(
            source,
            fetch_text=fetch_text,
            request_user_agent=request_user_agent or FANCDN_DEFAULT_USER_AGENT,
        )
    if extractor == 36:
        return _resolve_filmru(
            source,
            fetch_text=fetch_text,
            request_user_agent=request_user_agent or "Zona",
        )
    if extractor == 46:
        return _resolve_ok(
            source,
            fetch_text=fetch_text,
            request_user_agent=request_user_agent or "Zona",
        )
    if extractor == 51:
        return _resolve_kinobadi(
            source,
            fetch_text=fetch_text,
            season=season,
            episode=episode,
            request_user_agent=request_user_agent or "Zona",
        )
    if extractor == 14:
        return _resolve_kinoteatr(
            source,
            fetch_text=fetch_text,
            request_user_agent=request_user_agent or "Zona",
        )
    if extractor == 39:
        return _resolve_sooplive(
            source,
            fetch_post_text=fetch_post_text,
            request_user_agent=request_user_agent or SOOPLIVE_USER_AGENT,
        )
    if extractor == 42:
        return _resolve_rutube(source, fetch_text=fetch_text)
    if extractor == 45:
        return _resolve_veoveo(
            source,
            fetch_text=fetch_text,
            season=season,
            episode=episode,
            request_user_agent=request_user_agent or VEOVEO_USER_AGENT,
        )
    if extractor == 43:
        return _resolve_plvideo(source, fetch_text=fetch_text)

    url = _source_key(source)
    if not url or not is_valid_stream_url(url):
        return [], "LINK_INVALID_URL"

    merged = _merged_source(source)
    translation = _first(merged, (
        "tran", "translation", "voice", "audio",
    ))
    language = _first(merged, ("lang", "language"))
    resolution = _first(merged, (
        "res", "resolution", "quality",
        "videoResolution", "video_resolution",
    ))
    stream = _stream_metadata(
        source,
        extractor,
        url,
        voice=str(translation or language or "Не указано"),
        language=str(language or ""),
        quality=str(resolution or "Не указано"),
        user_agent=LINK_USER_AGENT,
    )
    if translation is not None:
        stream["translation"] = str(translation)
    if resolution is not None:
        stream["resolution"] = str(resolution)
    return [stream], None
