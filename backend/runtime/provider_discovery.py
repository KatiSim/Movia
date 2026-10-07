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
import time
import threading
from collections import OrderedDict
import urllib.parse
import urllib.request
from dataclasses import dataclass
from concurrent.futures import Future, ThreadPoolExecutor, wait
from typing import Any, Callable, Dict, Optional, Sequence, Tuple

from catalog_schema_v2 import normalize_ru_text
from collaps_provider_adapter import CollapsProviderAdapter
from filmix_provider_adapter import FilmixProviderAdapter
from hdrezka_provider_adapter import HDRezkaProviderAdapter
from octopus_provider_adapter import OctopusProviderAdapter
from zona_provider_adapter import ZonaProviderAdapter
from zona_mobi_provider_adapter import ZonaMobiProviderAdapter
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
    pending_futures: tuple = ()


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



def _finish_discovery(attempted, collected_streams, terminal_statuses, error_count):
    if collected_streams:
        return ProviderDiscoveryOutcome(sanitize_streams(collected_streams, require_source=True),
                                        "OK", tuple(attempted), error_count)
    if not attempted:
        return ProviderDiscoveryOutcome([], "PROVIDER_DISABLED")
    priority = ("AMBIGUOUS", "PLAYBACK_DECODER_REQUIRED", "PROVIDER_ERROR",
                "UNSUPPORTED_SERIES", "NO_RESULTS", "NO_MATCH")
    status = next((value for value in priority if value in terminal_statuses),
                  terminal_statuses[-1] if terminal_statuses else "NO_RESULTS")
    return ProviderDiscoveryOutcome([], status, tuple(attempted), error_count)

def _discover_zona_mobi(*, title: str, year: int = 0, media_id: str, media_type: str = "movie",
    season: Optional[int] = None, episode: Optional[int] = None,
    original_title: Optional[str] = None, fetch_text: Optional[TextFetcher] = None,
    fetch_post_form_text: Optional[PostFormFetcher] = None) -> ProviderDiscoveryOutcome:
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
    collected_streams: list[Dict[str, Any]] = []

    attempted.append("zona.mobi")
    try:
        request = ProviderRequest(str(media_id), clean_title, int(year) if int(year or 0) > 0 else None,
                                  season, episode, "tv" if is_series else "movie")
        adapter = ZonaMobiProviderAdapter()
        results, search_error = adapter.search(request, aliases=(original_title,))
        if search_error:
            terminal_statuses.append(search_error)
        elif len(results) != 1:
            terminal_statuses.append("AMBIGUOUS" if results else "NO_MATCH")
        else:
            tree, article, resolve_error = adapter.resolve_source(results[0], request)
            if resolve_error or tree is None or article is None:
                terminal_statuses.append(resolve_error or "PROVIDER_ERROR")
            else:
                collected_streams.extend(sanitize_streams(flatten_variant_tree(article, tree, request), require_source=True))
    except Exception:
        error_count += 1
        terminal_statuses.append("PROVIDER_ERROR")

    return _finish_discovery(attempted, collected_streams, terminal_statuses, error_count)

def _discover_hdrezka(*, title: str, year: int = 0, media_id: str, media_type: str = "movie",
    season: Optional[int] = None, episode: Optional[int] = None,
    original_title: Optional[str] = None, fetch_text: Optional[TextFetcher] = None,
    fetch_post_form_text: Optional[PostFormFetcher] = None) -> ProviderDiscoveryOutcome:
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
    collected_streams: list[Dict[str, Any]] = []

    attempted.append("hdrezka")
    try:
        request = ProviderRequest(
            media_id=str(media_id), title=clean_title,
            year=int(year) if int(year or 0) > 0 else None,
            season=season, episode=episode,
            media_type="tv" if is_series else "movie",
        )
        adapter = HDRezkaProviderAdapter()
        results, search_error = adapter.search(request, aliases=(original_title,))
        if search_error:
            error_count += 1
            terminal_statuses.append("PROVIDER_ERROR")
        elif len(results) > 1:
            terminal_statuses.append("AMBIGUOUS")
        elif not results:
            terminal_statuses.append("NO_MATCH")
        else:
            tree, article, resolve_error = adapter.deferred_source(results[0], request)
            if resolve_error or tree is None or article is None:
                error_count += 1
                terminal_statuses.append("PROVIDER_ERROR")
            else:
                rows = sanitize_streams(
                    flatten_variant_tree(article, tree, request), require_source=True
                )
                if rows:
                    collected_streams.extend(rows)
                else:
                    terminal_statuses.append("NO_RESULTS")
    except Exception:
        error_count += 1
        terminal_statuses.append("PROVIDER_ERROR")

    return _finish_discovery(attempted, collected_streams, terminal_statuses, error_count)

def _discover_collaps(*, title: str, year: int = 0, media_id: str, media_type: str = "movie",
    season: Optional[int] = None, episode: Optional[int] = None,
    original_title: Optional[str] = None, fetch_text: Optional[TextFetcher] = None,
    fetch_post_form_text: Optional[PostFormFetcher] = None) -> ProviderDiscoveryOutcome:
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
    collected_streams: list[Dict[str, Any]] = []

    attempted.append("collaps")
    try:
        request = ProviderRequest(
            media_id=str(media_id),
            title=clean_title,
            year=int(year) if int(year or 0) > 0 else None,
            season=season,
            episode=episode,
            media_type="tv" if is_series else "movie",
        )
        adapter = CollapsProviderAdapter()
        selected, identity_error = adapter.exact_catalog_result(request)
        if selected is None:
            if identity_error in {"COLLAPS_IDENTITY_MISMATCH", "COLLAPS_CATALOG_LOOKUP_FAILED"}:
                error_count += 1
                terminal_statuses.append("PROVIDER_ERROR")
            else:
                terminal_statuses.append("NO_MATCH")
        else:
            tree, article, resolve_error = adapter.resolve_source(
                selected, request, fetch_text=get_text,
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
                    collected_streams.extend(rows)
                else:
                    terminal_statuses.append("NO_RESULTS")
    except Exception:
        error_count += 1
        terminal_statuses.append("PROVIDER_ERROR")

    return _finish_discovery(attempted, collected_streams, terminal_statuses, error_count)

def _discover_zona(*, title: str, year: int = 0, media_id: str, media_type: str = "movie",
    season: Optional[int] = None, episode: Optional[int] = None,
    original_title: Optional[str] = None, fetch_text: Optional[TextFetcher] = None,
    fetch_post_form_text: Optional[PostFormFetcher] = None) -> ProviderDiscoveryOutcome:
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
    collected_streams: list[Dict[str, Any]] = []

    attempted.append("zona")
    try:
        request = ProviderRequest(
            media_id=str(media_id),
            title=clean_title,
            year=int(year) if int(year or 0) > 0 else None,
            season=season,
            episode=episode,
            media_type="tv" if is_series else "movie",
        )
        adapter = ZonaProviderAdapter()
        selected, identity_error = adapter.exact_catalog_result(request)
        if selected is None:
            if identity_error in {"ZONA_IDENTITY_MISMATCH", "ZONA_CATALOG_LOOKUP_FAILED"}:
                error_count += 1
                terminal_statuses.append("PROVIDER_ERROR")
            else:
                terminal_statuses.append("NO_MATCH")
        else:
            tree, article, resolve_error = adapter.resolve_source(selected, request)
            if resolve_error or tree is None or article is None:
                error_count += 1
                terminal_statuses.append("PROVIDER_ERROR")
            else:
                rows = sanitize_streams(
                    flatten_variant_tree(article, tree, request),
                    require_source=True,
                )
                if rows:
                    collected_streams.extend(rows)
                else:
                    terminal_statuses.append("NO_RESULTS")
    except Exception:
        error_count += 1
        terminal_statuses.append("PROVIDER_ERROR")

    return _finish_discovery(attempted, collected_streams, terminal_statuses, error_count)

def _discover_filmix(*, title: str, year: int = 0, media_id: str, media_type: str = "movie",
    season: Optional[int] = None, episode: Optional[int] = None,
    original_title: Optional[str] = None, fetch_text: Optional[TextFetcher] = None,
    fetch_post_form_text: Optional[PostFormFetcher] = None) -> ProviderDiscoveryOutcome:
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
    collected_streams: list[Dict[str, Any]] = []

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
                if int(year or 0) > 0 and (result.year is None or int(result.year) != int(year)):
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
                        collected_streams.extend(rows)
                    else:
                        terminal_statuses.append("NO_RESULTS")

    return _finish_discovery(attempted, collected_streams, terminal_statuses, error_count)

def _discover_octopus(*, title: str, year: int = 0, media_id: str, media_type: str = "movie",
    season: Optional[int] = None, episode: Optional[int] = None,
    original_title: Optional[str] = None, fetch_text: Optional[TextFetcher] = None,
    fetch_post_form_text: Optional[PostFormFetcher] = None) -> ProviderDiscoveryOutcome:
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
    collected_streams: list[Dict[str, Any]] = []

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
                    terminal_statuses.append("PLAYBACK_DECODER_REQUIRED")

    return _finish_discovery(attempted, collected_streams, terminal_statuses, error_count)

@dataclass(frozen=True)
class ProviderRegistration:
    flag: str
    name: str
    discover: Callable[..., ProviderDiscoveryOutcome]


PROVIDER_REGISTRY = (
    ProviderRegistration('MOVIA_ENABLE_ZONA_MOBI_PROVIDER_CONTRACT', 'zona.mobi', _discover_zona_mobi),
    ProviderRegistration('MOVIA_ENABLE_HDREZKA_PROVIDER_CONTRACT', 'hdrezka', _discover_hdrezka),
    ProviderRegistration('MOVIA_ENABLE_COLLAPS_PROVIDER_CONTRACT', 'collaps', _discover_collaps),
    ProviderRegistration('MOVIA_ENABLE_ZONA_PROVIDER_CONTRACT', 'zona', _discover_zona),
    ProviderRegistration('MOVIA_ENABLE_FILMIX_CLEAN_PROVIDER', 'filmix', _discover_filmix),
    ProviderRegistration('MOVIA_ENABLE_OCTOPUS_DISCOVERY_ONLY', 'octopus', _discover_octopus),
)
if len({entry.flag for entry in PROVIDER_REGISTRY}) != len(PROVIDER_REGISTRY):
    raise ValueError("DUPLICATE_PROVIDER_REGISTRATION")


def _discover_provider_streams(*, enabled_flags: frozenset[str], **request) -> ProviderDiscoveryOutcome:
    if not str(request.get("title") or "").strip() or not str(request.get("media_id") or "").strip():
        return ProviderDiscoveryOutcome([], "INVALID_REQUEST", error_count=1)
    rows, attempted, statuses, errors = [], [], [], 0
    for registration in PROVIDER_REGISTRY:
        if registration.flag not in enabled_flags:
            continue
        attempted.append(registration.name)
        try:
            outcome = registration.discover(**request)
            rows.extend(outcome.streams)
            statuses.append(outcome.status)
            errors += outcome.error_count
        except Exception:
            # Discovery-only and gated providers have the same isolation contract.
            statuses.append("PROVIDER_ERROR")
            errors += 1
    return _finish_discovery(attempted, rows, statuses, errors)


_PROVIDER_FLAGS = tuple(entry.flag for entry in PROVIDER_REGISTRY)
_PROVIDER_EXECUTOR = ThreadPoolExecutor(max_workers=24, thread_name_prefix="movia-provider")
_PROVIDER_CACHE = OrderedDict()
_PROVIDER_CACHE_LOCK = threading.Lock()


def _cache_completed(key, future):
    try:
        result = future.result()
        if not result.streams:
            return
        with _PROVIDER_CACHE_LOCK:
            _PROVIDER_CACHE[key] = (time.monotonic() + 60, result)
            _PROVIDER_CACHE.move_to_end(key)
            while len(_PROVIDER_CACHE) > 256:
                _PROVIDER_CACHE.popitem(last=False)
    except Exception:
        pass


def discover_provider_streams(*, budget_seconds=3.7, on_provider_result=None, **request) -> ProviderDiscoveryOutcome:
    """Run enabled providers independently; publish the union within one budget.

    A slow provider cannot hide the completed leaves of another provider.
    Each task receives immutable flags rather than changing process environment.
    """
    def publish_completed(future):
        if not callable(on_provider_result): return
        try:
            outcome = future.result()
            on_provider_result(ProviderDiscoveryOutcome(
                [dict(row) for row in outcome.streams], outcome.status,
                outcome.providers, outcome.error_count))
        except Exception:
            # The normal provider result/cache remains available for retry.
            pass
    enabled = [flag for flag in _PROVIDER_FLAGS if os.environ.get(flag, "0") == "1"]
    if not enabled:
        return ProviderDiscoveryOutcome([], "PROVIDER_DISABLED")
    futures, cached = [], []
    # A completed late task remains available to the next exact-identity poll.
    # Callables used for deterministic transport tests bypass this live cache.
    use_cache = not request.get("fetch_text") and not request.get("fetch_post_form_text")
    identity = tuple(str(request.get(k) or "") for k in
                     ("media_id", "title", "year", "media_type", "season", "episode", "original_title"))
    for flag in enabled:
        key = (flag, identity)
        with _PROVIDER_CACHE_LOCK:
            entry = _PROVIDER_CACHE.get(key) if use_cache else None
            if entry and entry[0] <= time.monotonic():
                _PROVIDER_CACHE.pop(key, None)
                entry = None
        if entry:
            cached.append(entry[1])
            if callable(on_provider_result):
                ready = Future()
                ready.set_result(entry[1])
                publish_completed(ready)
            continue
        future = _PROVIDER_EXECUTOR.submit(_discover_provider_streams, enabled_flags=frozenset({flag}), **request)
        futures.append(future)
        if callable(on_provider_result): future.add_done_callback(publish_completed)
        if use_cache:
            future.add_done_callback(lambda f, key=key: _cache_completed(key, f))
    done, pending = wait(futures, timeout=min(12.0, max(0.1, float(budget_seconds))))
    rows, providers, statuses = [], [], []
    for result in cached:
        rows.extend(result.streams)
        providers.extend(result.providers)
        statuses.append(result.status)
    errors = sum(result.error_count for result in cached)
    for future in pending:
        future.cancel()
    for future in futures:
        if future not in done:
            continue
        try:
            result = future.result()
            rows.extend(result.streams)
            providers.extend(result.providers)
            statuses.append(result.status)
            errors += result.error_count
        except Exception:
            errors += 1
            statuses.append("PROVIDER_ERROR")
    if rows:
        return ProviderDiscoveryOutcome(sanitize_streams(rows, require_source=True), "OK", tuple(providers), errors, tuple(pending))
    if pending:
        return ProviderDiscoveryOutcome([], "PROVIDER_TIMEOUT", tuple(providers), errors, tuple(pending))
    priority = ("INVALID_REQUEST", "AMBIGUOUS", "EXACT_EPISODE_REQUIRED", "PROVIDER_ERROR", "UNSUPPORTED_SERIES", "NO_RESULTS", "NO_MATCH")
    status = next((x for x in priority if x in statuses), statuses[-1] if statuses else "NO_RESULTS")
    return ProviderDiscoveryOutcome([], status, tuple(providers), errors)
