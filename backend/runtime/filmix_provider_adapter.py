#!/usr/bin/env python3
"""Independent Filmix adapter for Movia's clean-room provider contract.

Architecture is derived from the verified LazyMedia Deluxe 3.466 Filmix path:
search/list -> article -> translation -> season -> episode -> concrete files.
Provider-specific public constants were decoded from the pinned 3.466 APK.
No cookies, credentials, tokens or signing material are embedded here.
"""
from __future__ import annotations

import json
import re
import time
from html import unescape
from typing import Any, Callable, Dict, Optional, Tuple
from urllib.parse import quote, urlparse

from provider_contract import (
    ProviderArticle,
    ProviderDefinition,
    ProviderRequest,
    ProviderRequestProfile,
    ProviderSearchResult,
    VariantFolder,
    VariantStream,
)
from stream_validation import is_valid_stream_url


TextFetcher = Callable[[str, Dict[str, str]], Tuple[Optional[str], Optional[str]]]
PostFormFetcher = Callable[
    [str, Dict[str, str], Dict[str, str]],
    Tuple[Optional[str], Optional[str]],
]


FILMIX_BASE_URLS = (
    "https://filmix.ac",
    "http://filmixapp.cyou",
)
FILMIX_PLAYER_PATH = "/api/movies/player-data?t="
FILMIX_DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/138.0.0.0 Safari/537.36"
)

# LazyMedia 3.466 qi.OooOOOo search constants, decoded from md/nd/do0.
FILMIX_SEARCH_ENDPOINT = "http://5.61.56.18/partner_api/list"
FILMIX_SEARCH_EXCLUDED_CATEGORY = "s87"


FILMIX_PROVIDER = ProviderDefinition(
    provider_id="lazy:filmix",
    name="Filmix",
    family="lazy",
    enabled=True,
    source_type_id=3,
    request_profile=ProviderRequestProfile(
        user_agent=FILMIX_DEFAULT_USER_AGENT,
        base_urls=FILMIX_BASE_URLS,
        properties={
            "architecture": "lazy-3.466",
            "search_endpoint": FILMIX_SEARCH_ENDPOINT,
        },
    ),
    capabilities=frozenset({
        "movie", "series", "search", "voice", "quality", "mirror-fallback",
    }),
)


def _first(raw: Dict[str, Any], keys: tuple[str, ...]) -> Any:
    for key in keys:
        value = raw.get(key)
        if value is not None and str(value).strip():
            return value
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
    info: Any = source.get("info")
    if isinstance(info, str):
        try:
            info = json.loads(info)
        except (TypeError, ValueError):
            info = None
    if isinstance(info, dict):
        merged.update({key: value for key, value in info.items() if key not in merged})
    return merged


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


def _filmix_base_candidates(source: Dict[str, Any]) -> list[str]:
    merged = _merged_source(source)
    values = [
        _first(merged, (
            "filmix_base_url", "filmixBaseUrl", "base_url", "baseUrl",
            "site_url", "siteUrl", "host",
        )),
        *FILMIX_BASE_URLS,
    ]
    result: list[str] = []
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
    match = re.search(
        r"(?<!\d)(2160|1440|1080|720|576|480|360|240|144)(?:p)?\b",
        text,
        re.IGNORECASE,
    )
    return f"{match.group(1)}p" if match else "Не указано"


def _filmix_direct_urls(value: Any) -> list[tuple[str, str]]:
    """Extract only concrete URLs present in the provider response."""

    result: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()

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
                add(
                    direct,
                    _first(raw, ("quality", "resolution", "label", "name")) or label,
                )
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
        for match in re.finditer(r'(?:https?:)?//[^\s,"\'<>]+', text, re.IGNORECASE):
            add(match.group(0).rstrip(")]};"), label)

    walk(value)
    return result


def _filmix_player_payload(text: str) -> tuple[Dict[str, Any], Optional[str]]:
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


def _filmix_search_results(text: str) -> tuple[list[dict[str, Any]], Optional[str]]:
    """Parse the exact LazyMedia 3.466 partner_api/list result shape."""

    try:
        payload = json.loads(text)
    except (TypeError, ValueError):
        return [], "INVALID_JSON"
    if not isinstance(payload, dict):
        return [], "INVALID_RESPONSE"
    items = payload.get("items")
    if not isinstance(items, list):
        return [], "NO_ITEMS"

    result: list[dict[str, Any]] = []
    for raw in items[:200]:
        if not isinstance(raw, dict):
            continue
        if str(raw.get("category") or "").strip() == FILMIX_SEARCH_EXCLUDED_CATEGORY:
            continue
        item_id = str(raw.get("id") or "").strip()
        title = str(raw.get("title") or "").strip()
        if not item_id or not title:
            continue
        try:
            year = int(raw.get("year")) if str(raw.get("year") or "").strip() else None
        except (TypeError, ValueError):
            year = None
        result.append({
            "id": item_id,
            "title": title,
            "year": year,
            "poster": str(raw.get("poster") or "").strip(),
            "rating_imdb": str(raw.get("ratingImdb") or "").strip(),
            "quality": str(raw.get("quality") or "").strip(),
        })
    return result, None


def _clean_voice(value: Any) -> str:
    return re.sub(r"\s+", " ", unescape(str(value or "")).strip()) or "Не указано"


class FilmixProviderAdapter:
    definition = FILMIX_PROVIDER

    def search(
        self,
        title: str,
        *,
        fetch_text: Optional[TextFetcher],
        page: int = 1,
        request_user_agent: str = "",
    ) -> tuple[list[ProviderSearchResult], Optional[str]]:
        query = str(title or "").strip()
        if not query:
            return [], "SEARCH_QUERY_REQUIRED"
        if fetch_text is None:
            return [], "ADAPTER_REQUEST_UNAVAILABLE"
        if isinstance(page, bool) or not isinstance(page, int) or page <= 0 or page > 1000:
            return [], "SEARCH_PAGE_INVALID"

        # LazyMedia uses Android Uri.encode, equivalent here to UTF-8 percent
        # encoding with spaces encoded as %20.
        url = (
            f"{FILMIX_SEARCH_ENDPOINT}"
            f"?page={page}&sort=date&search={quote(query, safe='')}"
        )
        headers = {
            "User-Agent": request_user_agent or FILMIX_DEFAULT_USER_AGENT,
            "Accept": "application/json,*/*;q=0.8",
        }
        try:
            body, error = fetch_text(url, headers)
        except Exception as exc:
            return [], f"filmix-search:{type(exc).__name__}:{str(exc)[:120]}"
        if error:
            return [], f"filmix-search:{error}"
        if not isinstance(body, str) or not body.strip():
            return [], "filmix-search:EMPTY_RESPONSE"

        rows, parse_error = _filmix_search_results(body)
        if parse_error:
            return [], f"filmix-search:{parse_error}"

        return [
            ProviderSearchResult(
                provider=self.definition,
                item_id=row["id"],
                title=row["title"],
                year=row["year"],
                article_ref=row["id"],
                content_ref=row["id"],
            )
            for row in rows
        ], None

    def resolve_source(
        self,
        source: Dict[str, Any],
        request: ProviderRequest,
        *,
        fetch_text: Optional[TextFetcher],
        fetch_post_form_text: Optional[PostFormFetcher],
        request_user_agent: str = "",
    ) -> tuple[Optional[VariantFolder], Optional[ProviderArticle], Optional[str]]:
        post_id = _filmix_post_id(source)
        if not post_id:
            return None, None, "SOURCE_REF_INCOMPLETE"
        if fetch_text is None or fetch_post_form_text is None:
            return None, None, "ADAPTER_REQUEST_UNAVAILABLE"

        user_agent = request_user_agent or FILMIX_DEFAULT_USER_AGENT
        page_text = ""
        page_url = ""
        base_url = ""
        page_errors: list[str] = []
        for base in _filmix_base_candidates(source):
            candidate = f"{base}/play/{post_id}"
            headers = {
                "User-Agent": user_agent,
                "Accept": "text/html,*/*;q=0.8",
            }
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
            return None, None, "filmix:PAGE_REQUEST_FAILED" + suffix

        request_headers = {
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
            response, error = fetch_post_form_text(endpoint, request_headers, form)
        except Exception as exc:
            return None, None, f"filmix:{type(exc).__name__}:{str(exc)[:120]}"
        if error:
            return None, None, f"filmix:{error}"
        if not isinstance(response, str) or not response.strip():
            return None, None, "filmix:EMPTY_RESPONSE"

        video_map, parse_error = _filmix_player_payload(response)
        if parse_error:
            return None, None, f"filmix:{parse_error}"

        article = ProviderArticle(
            provider=self.definition,
            item_id=str(post_id),
            title=request.title,
            year=request.year,
            article_ref=page_url,
            content_ref=str(post_id),
        )

        playback_headers = {
            "User-Agent": user_agent,
            "Referer": page_url,
            "Origin": base_url,
        }

        voice_nodes: list[VariantFolder] = []
        opaque_entries = 0
        for raw_voice, raw_value in video_map.items():
            voice = _clean_voice(raw_voice)
            candidates = _filmix_direct_urls(raw_value)
            if not candidates and raw_value not in (None, "", [], {}):
                opaque_entries += 1
                continue

            leaves: list[VariantStream] = []
            per_dimension_counts: dict[tuple[str, str], int] = {}
            for stream_url, quality in candidates:
                if urlparse(stream_url).path.casefold().endswith(".txt"):
                    opaque_entries += 1
                    continue
                dimension = (voice, quality)
                ordinal = per_dimension_counts.get(dimension, 0)
                per_dimension_counts[dimension] = ordinal + 1
                stream_key = f"voice:{voice}|quality:{quality}|index:{ordinal}"
                leaves.append(
                    VariantStream(
                        url=stream_url,
                        stream_key=stream_key,
                        voice=voice,
                        quality=quality,
                        season=request.season,
                        episode=request.episode,
                        headers=playback_headers,
                        user_agent=user_agent,
                    )
                )

            if leaves:
                voice_nodes.append(
                    VariantFolder(
                        voice=voice,
                        season=request.season,
                        episode=request.episode,
                        children=tuple(leaves),
                    )
                )

        if not voice_nodes:
            if opaque_entries:
                return None, None, "filmix:OPAQUE_LINK_DECODER_REQUIRED"
            return None, None, "filmix:NO_PLAYABLE_URL"

        if request.is_series_request:
            episode_folder = VariantFolder(
                season=request.season,
                episode=request.episode,
                children=tuple(voice_nodes),
            )
            season_folder = VariantFolder(
                season=request.season,
                children=(episode_folder,),
            )
            root = VariantFolder(children=(season_folder,))
        else:
            root = VariantFolder(children=tuple(voice_nodes))

        return root, article, None
