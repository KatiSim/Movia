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
from octopus_provider_adapter import OctopusProviderAdapter
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
    get_text = fetch_text or _fetch_text
    post_form = fetch_post_form_text or _fetch_post_form_text
    attempted: list[str] = []
    error_count = 0
    terminal_statuses: list[str] = []

    # Filmix is kept disabled by default because its pinned 3.466 partner API
    # now returns HTTP 403. The adapter remains fully testable behind the gate.
    if os.environ.get("MOVIA_ENABLE_FILMIX_CLEAN_PROVIDER", "0") == "1":
        attempted.append("filmix")
        if is_series:
            terminal_statuses.append("UNSUPPORTED_SERIES")
        else:
            adapter = FilmixProviderAdapter()
            expected_titles = _normalized_titles((clean_title, original_title))
            results, search_error = adapter.search(clean_title, fetch_text=get_text)
            if search_error:
                error_count += 1
                terminal_statuses.append("PROVIDER_ERROR")
            else:
                exact = []
                for result in results:
                    if normalize_ru_text(result.title) not in expected_titles:
                        continue
                    if int(year or 0) > 0 and result.year is not None and int(result.year) != int(year):
                        continue
                    exact.append(result)
                exact = list({item.item_id: item for item in exact}.values())
                if len(exact) > 1:
                    terminal_statuses.append("AMBIGUOUS")
                elif not exact:
                    terminal_statuses.append("NO_MATCH")
                else:
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
                        error_count += 1
                        terminal_statuses.append("PROVIDER_ERROR")
                    else:
                        rows = sanitize_streams(
                            flatten_variant_tree(article, tree, request),
                            require_source=True,
                        )
                        if rows:
                            return ProviderDiscoveryOutcome(rows, "OK", tuple(attempted), error_count)
                        terminal_statuses.append("NO_RESULTS")

    # Octopus search and article transport are live, but its current iframe
    # still needs a clean-room playback decoder. Discovery is available behind
    # a separate diagnostic gate and never fabricates a voice/quality stream.
    if os.environ.get("MOVIA_ENABLE_OCTOPUS_DISCOVERY_ONLY", "0") == "1":
        attempted.append("octopus")
        if is_series:
            terminal_statuses.append("UNSUPPORTED_SERIES")
        else:
            adapter = OctopusProviderAdapter()
            results, search_error = adapter.search(clean_title, fetch_text=get_text)
            if search_error:
                error_count += 1
                terminal_statuses.append("PROVIDER_ERROR")
            else:
                selected, status = adapter.exact_match(
                    results,
                    title=clean_title,
                    original_title=original_title,
                    year=int(year or 0),
                )
                if selected is None:
                    terminal_statuses.append(status)
                else:
                    article, article_error = adapter.resolve_article(selected, fetch_text=get_text)
                    if article_error or article is None:
                        error_count += 1
                        terminal_statuses.append("PROVIDER_ERROR")
                    else:
                        return ProviderDiscoveryOutcome(
                            [], "PLAYBACK_DECODER_REQUIRED", tuple(attempted), error_count
                        )

    if not attempted:
        return ProviderDiscoveryOutcome([], "PROVIDER_DISABLED")
    priority = (
        "AMBIGUOUS", "PLAYBACK_DECODER_REQUIRED", "PROVIDER_ERROR",
        "UNSUPPORTED_SERIES", "NO_RESULTS", "NO_MATCH",
    )
    status = next((value for value in priority if value in terminal_statuses), terminal_statuses[-1] if terminal_statuses else "NO_RESULTS")
    return ProviderDiscoveryOutcome([], status, tuple(attempted), error_count)
