#!/usr/bin/env python3
"""Verified public-media source adapter for Internet Archive.

This adapter is intentionally conservative: it returns only exact title/year
matches with explicit public-domain or Creative Commons rights and a real
video file that responds over HTTP. It never turns an Archive item page or a
guessed filename into a playable stream.
"""
from __future__ import annotations

import asyncio
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, Iterable, List, Optional

ARCHIVE_SEARCH_URL = "https://archive.org/advancedsearch.php"
ARCHIVE_METADATA_URL = "https://archive.org/metadata"
USER_AGENT = "MoviaArchiveSource/1.0"

_VIDEO_EXTENSIONS = frozenset(
    {".mp4", ".m4v", ".webm", ".ogv", ".ogg", ".m3u8"}
)
_LICENSE_MARKERS = (
    "creativecommons.org",
    "creativecommons",
    "public domain",
    "publicdomain",
    "public-domain",
    "pd mark",
)


def _text(value: Any) -> str:
    if isinstance(value, (list, tuple, set)):
        return " ".join(_text(item) for item in value)
    if isinstance(value, dict):
        return " ".join(f"{key} {_text(item)}" for key, item in value.items())
    return str(value or "").strip()


def _year(value: Any) -> Optional[int]:
    try:
        parsed = int(str(value or "").strip()[:4])
    except (TypeError, ValueError):
        return None
    return parsed if 1900 <= parsed <= 2100 else None


def _explicit_rights(values: Iterable[Any]) -> bool:
    text = " ".join(_text(value) for value in values).casefold()
    return bool(text) and any(marker in text for marker in _LICENSE_MARKERS)


def _escape_solr_phrase(value: str) -> str:
    clean = re.sub(r"\s+", " ", str(value or "").strip())
    clean = re.sub(r'([\\+\-!(){}\[\]^"~*?:/])', r"\\\1", clean)
    return f'"{clean}"'


def _search_url(title: str) -> str:
    query = f"title:{_escape_solr_phrase(title)} AND mediatype:movies"
    params = [
        ("q", query),
        ("rows", "8"),
        ("output", "json"),
        ("fl[]", "identifier"),
        ("fl[]", "title"),
        ("fl[]", "year"),
        ("fl[]", "licenseurl"),
        ("fl[]", "rights"),
        ("fl[]", "collection"),
    ]
    return f"{ARCHIVE_SEARCH_URL}?{urllib.parse.urlencode(params)}"


def _metadata_url(identifier: str) -> str:
    return f"{ARCHIVE_METADATA_URL}/{urllib.parse.quote(identifier, safe='')}"


def _http_json(url: str, timeout: float) -> Optional[Dict[str, Any]]:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status != 200:
                return None
            decoded = response.read().decode("utf-8", errors="replace")
            value = json.loads(decoded)
            return value if isinstance(value, dict) else None
    except (OSError, ValueError, urllib.error.URLError, urllib.error.HTTPError):
        return None


def _download_url(identifier: str, name: str) -> str:
    encoded_name = "/".join(
        urllib.parse.quote(part, safe="") for part in str(name).split("/")
    )
    return (
        f"https://archive.org/download/"
        f"{urllib.parse.quote(identifier, safe='')}/{encoded_name}"
    )


def _probe_video_url(url: str, timeout: float) -> bool:
    extension = re.search(r"(\.[a-z0-9]{2,5})(?:\?|$)", url.casefold())
    extension_ok = bool(extension and extension.group(1) in _VIDEO_EXTENSIONS)
    headers = {"User-Agent": USER_AGENT, "Accept": "*/*"}
    try:
        request = urllib.request.Request(url, headers=headers, method="HEAD")
        with urllib.request.urlopen(request, timeout=timeout) as response:
            content_type = str(response.headers.get("Content-Type") or "").casefold()
            length = str(response.headers.get("Content-Length") or "").strip()
            return (
                response.status in (200, 206)
                and (extension_ok or "video/" in content_type
                     or "mpegurl" in content_type or "octet-stream" in content_type)
                and (not length or length != "0")
            )
    except urllib.error.HTTPError as exc:
        if exc.code not in (403, 405, 501):
            return False
    except (OSError, urllib.error.URLError):
        return False

    # Some archive mirrors do not implement HEAD. A two-byte range request
    # verifies reachability without downloading media.
    try:
        range_headers = dict(headers)
        range_headers["Range"] = "bytes=0-1"
        request = urllib.request.Request(url, headers=range_headers)
        with urllib.request.urlopen(request, timeout=timeout) as response:
            content_type = str(response.headers.get("Content-Type") or "").casefold()
            return (
                response.status in (200, 206)
                and (extension_ok or "video/" in content_type
                     or "mpegurl" in content_type or "octet-stream" in content_type)
            )
    except (OSError, urllib.error.URLError, urllib.error.HTTPError):
        return False


def _quality(name: str, file_data: Dict[str, Any]) -> str:
    text = f"{name} {_text(file_data.get('format'))}".casefold()
    if any(token in text for token in ("2160", "4k", "uhd")):
        return "4K"
    if any(token in text for token in ("1080", "full hd", "fullhd")):
        return "1080p"
    if "720" in text:
        return "720p"
    return "Не указано"


def _file_size(file_data: Dict[str, Any]) -> int:
    try:
        return max(0, int(file_data.get("size") or 0))
    except (TypeError, ValueError, OverflowError):
        return 0


def _video_file(files: Any) -> Optional[Dict[str, Any]]:
    if not isinstance(files, list):
        return None
    candidates: List[Dict[str, Any]] = []
    for item in files:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or "").strip()
        if not name or name.startswith("_"):
            continue
        suffix = "." + name.rsplit(".", 1)[-1].casefold() if "." in name else ""
        fmt = _text(item.get("format")).casefold()
        if suffix not in _VIDEO_EXTENSIONS and not any(
            token in fmt for token in ("mpeg4", "h.264", "h264", "video", "webm", "ogg")
        ):
            continue
        if _file_size(item) <= 0:
            continue
        if any(token in name.casefold() for token in (".torrent", ".jpg", ".jpeg", ".png")):
            continue
        candidates.append(item)
    if not candidates:
        return None

    def sort_key(item: Dict[str, Any]) -> tuple:
        name = str(item.get("name") or "")
        text = f"{name} {_text(item.get('format'))}".casefold()
        quality_rank = (
            0 if any(token in text for token in ("2160", "4k", "uhd"))
            else 1 if any(token in text for token in ("1080", "full hd", "fullhd"))
            else 2 if "720" in text
            else 3
        )
        format_rank = 0 if name.casefold().endswith((".mp4", ".m4v")) else 1
        return (quality_rank, format_rank, -_file_size(item), name.casefold())

    return sorted(candidates, key=sort_key)[0]


def _match_title(
    release_title: str,
    expected_titles: List[str],
    year: Optional[int],
) -> bool:
    # Import at call time to avoid a module cycle with torrent_resolver.
    try:
        from torrent_resolver import _release_matches_expected
        return any(
            _release_matches_expected(
                release_title,
                [candidate],
                year,
                None,
                None,
            )
            for candidate in expected_titles
            if candidate
        )
    except Exception:
        return False


async def fetch_archive_streams(
    title: str,
    year: int = 0,
    expected_titles: Optional[List[str]] = None,
    timeout: float = 3.5,
) -> List[Dict[str, Any]]:
    """Resolve verified direct files for an exact public/CC movie match."""
    expected = list(
        dict.fromkeys(
            str(value).strip()
            for value in (expected_titles or [title])
            if str(value or "").strip()
        )
    )
    if not expected:
        return []

    terms = list(dict.fromkeys(expected + [str(title or "").strip()]))[:3]
    bounded_timeout = min(max(float(timeout), 0.8), 5.0)
    loop = asyncio.get_running_loop()

    async def search(term: str) -> Optional[Dict[str, Any]]:
        if not term:
            return None
        return await loop.run_in_executor(
            None, _http_json, _search_url(term), bounded_timeout
        )

    search_results = await asyncio.gather(
        *(search(term) for term in terms),
        return_exceptions=True,
    )
    documents: Dict[str, Dict[str, Any]] = {}
    for result in search_results:
        if not isinstance(result, dict):
            continue
        for document in (result.get("response") or {}).get("docs", []) or []:
            if not isinstance(document, dict):
                continue
            identifier = str(document.get("identifier") or "").strip()
            doc_title = str(document.get("title") or "").strip()
            if (
                not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{1,200}", identifier)
                or not doc_title
                or not _match_title(doc_title, expected, _year(year))
            ):
                continue
            doc_year = _year(document.get("year"))
            if _year(year) and doc_year and doc_year != _year(year):
                continue
            documents.setdefault(identifier, document)

    if not documents:
        return []

    semaphore = asyncio.Semaphore(3)

    async def resolve_document(
        identifier: str,
        document: Dict[str, Any],
    ) -> Optional[Dict[str, Any]]:
        async with semaphore:
            metadata = await loop.run_in_executor(
                None, _http_json, _metadata_url(identifier), bounded_timeout
            )
            if not isinstance(metadata, dict):
                return None
            metadata_fields = metadata.get("metadata") or {}
            if not isinstance(metadata_fields, dict):
                metadata_fields = {}
            rights_values = [
                document.get("licenseurl"),
                document.get("rights"),
                metadata_fields.get("licenseurl"),
                metadata_fields.get("license"),
                metadata_fields.get("rights"),
            ]
            if not _explicit_rights(rights_values):
                return None

            resolved_title = str(
                metadata_fields.get("title")
                or document.get("title")
                or identifier
            ).strip()
            resolved_year = _year(
                metadata_fields.get("year")
                or document.get("year")
                or year
            )
            if not _match_title(resolved_title, expected, _year(year)):
                return None
            if _year(year) and resolved_year and resolved_year != _year(year):
                return None

            file_data = _video_file(metadata.get("files"))
            if not file_data:
                return None
            file_name = str(file_data.get("name") or "").strip()
            url = _download_url(identifier, file_name)
            if not await loop.run_in_executor(
                None, _probe_video_url, url, min(bounded_timeout, 3.0)
            ):
                return None
            return {
                "source": "Internet Archive",
                "provider": "archive.org",
                "provider_item_id": identifier,
                "voice": "Original",
                "quality": _quality(file_name, file_data),
                "seeders": 0,
                "url": url,
                "title": resolved_title,
                "stream_type": "direct_http",
                "mime_type": str(file_data.get("format") or "").strip(),
                "year": resolved_year or 0,
            }

    resolved = await asyncio.gather(
        *(resolve_document(identifier, document)
          for identifier, document in list(documents.items())[:8]),
        return_exceptions=True,
    )
    streams: List[Dict[str, Any]] = []
    seen = set()
    for item in resolved:
        if not isinstance(item, dict):
            continue
        key = str(item.get("url") or "")
        if key and key not in seen:
            seen.add(key)
            streams.append(item)
    return streams[:8]


def search_archive_streams_sync(
    title: str,
    year: Optional[int] = None,
    timeout: float = 3.5,
) -> List[Dict[str, Any]]:
    """Synchronous compatibility adapter for the legacy search engine."""
    try:
        streams = asyncio.run(
            fetch_archive_streams(
                title=title,
                year=int(year or 0),
                expected_titles=[title],
                timeout=timeout,
            )
        )
    except Exception:
        return []
    result: List[Dict[str, Any]] = []
    for stream in streams:
        result.append(
            {
                "title": stream.get("title") or title,
                "original_title": title,
                "year": stream.get("year") or year,
                "playback_url": stream.get("url"),
                "source_id": "archive_org",
                "source": stream.get("source"),
                "provider_item_id": stream.get("provider_item_id"),
                "source_page": (
                    "https://archive.org/details/"
                    + str(stream.get("provider_item_id") or "")
                ),
                "media_type": "direct_http",
                "voice": stream.get("voice"),
                "quality": stream.get("quality"),
            }
        )
    return result
