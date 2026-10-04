#!/usr/bin/env python3
"""Unified clean-room provider discovery boundary.

This module is intentionally provider-registry shaped rather than tied to one
consumer. User-triggered playback and background enrichment call the same
entrypoint and receive the same StreamCandidate-compatible rows.

Filmix is the first migrated LazyMedia-derived adapter. Series are deliberately
fail-closed until the Filmix episode branch is independently verified.
"""
from __future__ import annotations

import os
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Callable, Dict, Optional, Sequence, Tuple

from catalog_schema_v2 import normalize_ru_text
from filmix_provider_adapter import FilmixProviderAdapter
from provider_contract import ProviderRequest, flatten_variant_tree
from stream_validation import sanitize_streams


TextFetcher = Callable[[str, Dict[str, str]], Tuple[Optional[str], Optional[str]]]
PostFormFetcher = Callable[
    [str, Dict[str, str], Dict[str, str]],
    Tuple[Optional[str], Optional[str]],
]

_MAX_PROVIDER_BYTES = 2 * 1024 * 1024
_PROVIDER_TIMEOUT_SECONDS = 2.5


@dataclass(frozen=True)
class ProviderDiscoveryOutcome:
    streams: list[dict]
    status: str
    providers: tuple[str, ...] = ()
    error_count: int = 0


def _read_bounded(response) -> str:
    raw = response.read(_MAX_PROVIDER_BYTES + 1)
    if len(raw) > _MAX_PROVIDER_BYTES:
        raise ValueError("RESPONSE_TOO_LARGE")
    encoding = response.headers.get_content_charset() or "utf-8"
    return raw.decode(encoding, errors="replace")


def _fetch_text(url: str, headers: Dict[str, str]) -> tuple[Optional[str], Optional[str]]:
    try:
        request = urllib.request.Request(url, headers=dict(headers), method="GET")
        with urllib.request.urlopen(request, timeout=_PROVIDER_TIMEOUT_SECONDS) as response:
            return _read_bounded(response), None
    except Exception as exc:
        return None, f"{type(exc).__name__}"


def _fetch_post_form_text(
    url: str,
    headers: Dict[str, str],
    form: Dict[str, str],
) -> tuple[Optional[str], Optional[str]]:
    try:
        body = urllib.parse.urlencode(form).encode("utf-8")
        request_headers = dict(headers)
        request_headers.setdefault("Content-Type", "application/x-www-form-urlencoded")
        request = urllib.request.Request(url, data=body, headers=request_headers, method="POST")
        with urllib.request.urlopen(request, timeout=_PROVIDER_TIMEOUT_SECONDS) as response:
            return _read_bounded(response), None
    except Exception as exc:
        return None, f"{type(exc).__name__}"


def _normalized_titles(values: Sequence[Optional[str]]) -> set[str]:
    return {
        normalized
        for value in values
        if value is not None
        for normalized in [normalize_ru_text(str(value))]
        if normalized
    }


def discover_provider_streams(
    *,
    title: str,
    year: int = 0,
    media_id: str,
    media_type: str = "movie",
    season: Optional[int] = None,
    episode: Optional[int] = None,
    original_title: Optional[str] = None,
    fetch_text: Optional[TextFetcher] = None,
    fetch_post_form_text: Optional[PostFormFetcher] = None,
) -> ProviderDiscoveryOutcome:
    clean_title = str(title or "").strip()
    if not clean_title or not str(media_id or "").strip():
        return ProviderDiscoveryOutcome([], "INVALID_REQUEST", error_count=1)

    kind = str(media_type or "movie").strip().casefold().replace("-", "_")
    is_series = season is not None or episode is not None or kind in {
        "tv", "series", "serial", "tv_series", "limited_series", "dramas_asian",
    }
    if is_series:
        # Filmix series parsing must not assign an arbitrary requested episode to
        # a card-level payload. Keep this fail-closed until its exact episode
        # response contract has been verified against the pinned 3.466 engine.
        return ProviderDiscoveryOutcome([], "UNSUPPORTED_SERIES")

    if os.environ.get("MOVIA_ENABLE_FILMIX_CLEAN_PROVIDER", "0") != "1" and fetch_text is None and fetch_post_form_text is None:
        return ProviderDiscoveryOutcome([], "PROVIDER_DISABLED")

    adapter = FilmixProviderAdapter()
    get_text = fetch_text or _fetch_text
    post_form = fetch_post_form_text or _fetch_post_form_text
    expected_titles = _normalized_titles((clean_title, original_title))

    results, search_error = adapter.search(clean_title, fetch_text=get_text)
    if search_error:
        return ProviderDiscoveryOutcome([], "PROVIDER_ERROR", ("filmix",), 1)

    exact = []
    for result in results:
        if normalize_ru_text(result.title) not in expected_titles:
            continue
        if int(year or 0) > 0 and result.year is not None and int(result.year) != int(year):
            continue
        exact.append(result)

    unique_by_id = {item.item_id: item for item in exact}
    exact = list(unique_by_id.values())
    if not exact:
        return ProviderDiscoveryOutcome([], "NO_MATCH", ("filmix",))
    if len(exact) != 1:
        return ProviderDiscoveryOutcome([], "AMBIGUOUS", ("filmix",))

    selected = exact[0]
    request = ProviderRequest(
        media_id=str(media_id),
        title=clean_title,
        year=int(year) if int(year or 0) > 0 else None,
        media_type="movie",
    )
    tree, article, resolve_error = adapter.resolve_source(
        {"downloadLinkKey": selected.item_id},
        request,
        fetch_text=get_text,
        fetch_post_form_text=post_form,
    )
    if resolve_error or tree is None or article is None:
        return ProviderDiscoveryOutcome([], "PROVIDER_ERROR", ("filmix",), 1)

    rows = sanitize_streams(
        flatten_variant_tree(article, tree, request),
        require_source=True,
    )
    if not rows:
        return ProviderDiscoveryOutcome([], "NO_RESULTS", ("filmix",))
    return ProviderDiscoveryOutcome(rows, "OK", ("filmix",))
