"""Catalog identity checks without database initialization or schema writes."""
import re
from typing import Any, Dict, List
from urllib.parse import parse_qs, unquote, urlparse
from catalog_localization import russian_alternative_titles
from catalog_schema_v2 import normalize_ru_text
from stream_validation import sanitize_streams

def _stream_identity_title(raw: Dict[str, Any]) -> str:
    """Get provider release identity from explicit title or magnet display name."""
    title = str(raw.get("title") or "").strip()
    if title:
        return title
    url = str(raw.get("url") or raw.get("playback_url") or "").strip()
    if not url.lower().startswith("magnet:?"):
        return ""
    try:
        values = parse_qs(urlparse(url).query, keep_blank_values=True).get("dn") or []
        return unquote(str(values[0])).strip() if values else ""
    except Exception:
        return ""

def filter_streams_for_content(
    streams: Any,
    content: Dict[str, Any],
) -> List[Dict[str, Any]]:
    """Keep structurally valid streams whose release identity matches the card."""
    cleaned = sanitize_streams(streams, require_source=True)
    expected_titles = list(dict.fromkeys(
        str(content.get(key) or "").strip()
        for key in ("localized_ru_title", "title", "original_title")
        if str(content.get(key) or "").strip()
    ))
    expected_titles.extend(
        title for title in russian_alternative_titles(content.get("alternative_titles"))
        if title not in expected_titles
    )
    if not expected_titles:
        return cleaned
    normalized_expected_titles = {normalize_ru_text(value) for value in expected_titles}

    try:
        year = int(content.get("year") or 0)
    except (TypeError, ValueError):
        year = 0
    media_type = str(content.get("media_type") or "").strip().casefold()
    result: List[Dict[str, Any]] = []

    # Import lazily: torrent_resolver uses the shared stream validator and this
    # keeps database initialization independent from provider initialization.
    from torrent_resolver import _release_matches_expected

    for item in cleaned:
        url = str(item.get("url") or "").strip()

        # Runtime Zona results are bound to the canonical card before they
        # reach this boundary. If an identity annotation is present, every
        # dimension is mandatory and exact; a valid URL from another card is
        # still a wrong source and must be rejected.
        annotated_id = str(
            item.get("catalog_media_id") or item.get("catalogMediaId") or ""
        ).strip()
        expected_id = str(content.get("id") or "").strip()
        if annotated_id and expected_id and annotated_id != expected_id:
            continue
        annotated_title = str(
            item.get("canonical_title") or item.get("canonicalTitle") or ""
        ).strip()
        annotated_original = str(
            item.get("canonical_original_title") or
            item.get("canonicalOriginalTitle") or ""
        ).strip()
        if annotated_title or annotated_original:
            if not any(
                normalize_ru_text(value) in normalized_expected_titles
                for value in (annotated_title, annotated_original)
                if value
            ):
                continue
        annotated_year = item.get("canonical_year", item.get("canonicalYear"))
        if annotated_year not in (None, "") and year:
            try:
                if int(annotated_year) != year:
                    continue
            except (TypeError, ValueError):
                continue
        annotated_type = str(
            item.get("canonical_media_type") or item.get("canonicalMediaType") or ""
        ).strip().casefold()
        if annotated_type:
            expected_type = "tv" if media_type in {
                "tv", "series", "tv_series", "serial", "limited_series",
            } else "movie" if media_type in {"movie", "movies", "film"} else ""
            if expected_type and annotated_type != expected_type:
                continue

        requested_season = content.get("season")
        requested_episode = content.get("episode")
        try:
            requested_season = int(requested_season) if requested_season is not None else None
        except (TypeError, ValueError):
            requested_season = None
        try:
            requested_episode = int(requested_episode) if requested_episode is not None else None
        except (TypeError, ValueError):
            requested_episode = None
        if requested_season is not None or requested_episode is not None:
            try:
                if requested_season is not None and int(item.get("season")) != requested_season:
                    continue
                if requested_episode is not None and int(item.get("episode")) != requested_episode:
                    continue
            except (TypeError, ValueError):
                continue
        release_title = _stream_identity_title(item)
        if not release_title:
            # HTTP/HLS candidates may not expose a release name; structural
            # validation still applies, while magnet candidates need identity.
            if url.lower().startswith("magnet:?"):
                continue
            result.append(item)
            continue

        # A movie card must never retain an explicitly episodic release.
        if media_type in {"movie", "movies", "film"} and re.search(
            r"(?i)\bs\d{1,3}(?:e\d{1,3})?\b|\b(?:season|сезон)\s*\d{1,3}\b",
            release_title,
        ):
            continue

        try:
            stream_season = int(item.get("season")) if item.get("season") is not None else None
        except (TypeError, ValueError):
            stream_season = None
        try:
            stream_episode = int(item.get("episode")) if item.get("episode") is not None else None
        except (TypeError, ValueError):
            stream_episode = None

        if not _release_matches_expected(
            release_title,
            expected_titles,
            year if year > 1900 else None,
            stream_season,
            stream_episode,
        ):
            continue

        normalized_item = dict(item)
        normalized_item.setdefault("title", release_title)
        result.append(normalized_item)

    return result
