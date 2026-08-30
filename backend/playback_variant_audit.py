#!/usr/bin/env python3
"""Read-only playback startup and variant audit for a running Movia backend.

The audit requests only resolver metadata from ``/resolve`` (or a caller-
supplied endpoint).  It never calls ``/stream``, follows a media URL, downloads
media, changes Android state, or writes a local cache/database.

Examples:
    python3 playback_variant_audit.py
    python3 playback_variant_audit.py --base-url http://127.0.0.1:8888 \
        --spec 'Breaking Bad|2008|tv_series|1|1'
    python3 playback_variant_audit.py --title 'Inception' --year 2010 \
        --category movies --repeat 3 --json
"""
from __future__ import annotations

import argparse
import json
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional, Sequence, Tuple

from stream_validation import is_valid_stream_url


DEFAULT_TITLE = "Breaking Bad"
DEFAULT_YEAR = 2008
DEFAULT_CATEGORY = "tv_series"
DEFAULT_SEASON = 1
DEFAULT_EPISODE = 1
MAX_METADATA_BYTES = 4 * 1024 * 1024
def parse_optional_int(value: str) -> Optional[int]:
    if value in (None, "", "-", "none", "None"):
        return None
    return int(value)


def parse_spec(spec: str) -> Tuple[str, int, str, Optional[int], Optional[int]]:
    """Parse title|year|category|season|episode, with title-only support."""
    parts = [part.strip() for part in spec.split("|")]
    if not parts or not parts[0]:
        raise ValueError("empty title in --spec")
    if len(parts) == 1:
        return parts[0], DEFAULT_YEAR, DEFAULT_CATEGORY, DEFAULT_SEASON, DEFAULT_EPISODE
    if len(parts) != 5:
        raise ValueError("--spec must be title|year|category|season|episode")
    return (
        parts[0],
        int(parts[1]),
        parts[2] or DEFAULT_CATEGORY,
        parse_optional_int(parts[3]),
        parse_optional_int(parts[4]),
    )


def transport_for(stream: Dict[str, Any]) -> str:
    url = str(stream.get("url") or stream.get("playback_url") or "").strip()
    if url.lower().startswith("magnet:?"):
        return "p2p"
    if url.lower().startswith(("http://", "https://")):
        return "direct"
    return "other"


def is_structurally_playable(stream: Dict[str, Any]) -> bool:
    url = str(stream.get("url") or stream.get("playback_url") or "").strip()
    return bool(url and is_valid_stream_url(url))


def first_playable(streams: Sequence[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    for index, stream in enumerate(streams):
        if not isinstance(stream, dict) or not is_structurally_playable(stream):
            continue
        url = str(stream.get("url") or stream.get("playback_url") or "").strip()
        return {
            "index": index,
            "stream_id": stream.get("stream_id") or stream.get("streamId"),
            "source": stream.get("source") or stream.get("source_id"),
            "provider": stream.get("provider"),
            "voice": stream.get("voice") or stream.get("translation") or "Не указано",
            "quality": stream.get("quality") or "Не указано",
            "transport": transport_for(stream),
            "url": url,
        }
    return None


def extract_streams(payload: Any) -> List[Dict[str, Any]]:
    if not isinstance(payload, dict):
        return []
    raw = payload.get("streams") or payload.get("all_sources") or []
    if not raw and isinstance(payload.get("movie"), dict):
        raw = payload["movie"].get("streams") or []
    return [stream for stream in raw if isinstance(stream, dict)] if isinstance(raw, list) else []


def request_resolution(
    base_url: str,
    endpoint: str,
    title: str,
    year: int,
    category: str,
    season: Optional[int],
    episode: Optional[int],
    timeout: float,
    refresh: bool,
) -> Tuple[float, Optional[Dict[str, Any]], Optional[str], Optional[int]]:
    params: Dict[str, str] = {
        "title": title,
        "year": str(year),
        "category": category,
    }
    if season is not None:
        params["season"] = str(season)
    if episode is not None:
        params["episode"] = str(episode)
    if refresh:
        params["refresh"] = "1"
    url = base_url.rstrip("/") + "/" + endpoint.lstrip("/")
    request_url = url + "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(
        request_url,
        method="GET",
        headers={"Accept": "application/json", "User-Agent": "MoviaPlaybackAudit/1.0"},
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = int(response.status)
            body = response.read(MAX_METADATA_BYTES + 1)
        elapsed = (time.perf_counter() - started) * 1000.0
        if len(body) > MAX_METADATA_BYTES:
            return elapsed, None, "metadata_response_too_large", status
        payload = json.loads(body.decode("utf-8"))
        if not isinstance(payload, dict):
            return elapsed, None, "metadata_response_not_object", status
        return elapsed, payload, None, status
    except urllib.error.HTTPError as exc:
        elapsed = (time.perf_counter() - started) * 1000.0
        return elapsed, None, f"http_{exc.code}", int(exc.code)
    except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as exc:
        elapsed = (time.perf_counter() - started) * 1000.0
        return elapsed, None, f"backend_unavailable: {exc}", None
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        elapsed = (time.perf_counter() - started) * 1000.0
        return elapsed, None, f"invalid_metadata: {exc}", None


def audit_title(
    base_url: str,
    endpoint: str,
    title: str,
    year: int,
    category: str,
    season: Optional[int],
    episode: Optional[int],
    repeat: int,
    timeout: float,
    refresh: bool,
) -> Dict[str, Any]:
    samples: List[float] = []
    payload: Optional[Dict[str, Any]] = None
    error: Optional[str] = None
    status: Optional[int] = None
    for _ in range(max(1, repeat)):
        elapsed, current, current_error, current_status = request_resolution(
            base_url, endpoint, title, year, category, season, episode, timeout, refresh
        )
        samples.append(elapsed)
        status = current_status
        if current is not None:
            payload = current
            error = None
        else:
            error = current_error
            # A failed sample cannot prove availability; keep trying only to
            # expose repeat latency, but do not merge partial data.

    streams = extract_streams(payload)
    voices = sorted({str(s.get("voice") or s.get("translation") or "Не указано").strip() or "Не указано" for s in streams})
    qualities = sorted({str(s.get("quality") or "Не указано").strip() or "Не указано" for s in streams})
    direct_count = sum(1 for stream in streams if transport_for(stream) == "direct")
    p2p_count = sum(1 for stream in streams if transport_for(stream) == "p2p")
    other_count = len(streams) - direct_count - p2p_count
    latencies = {
        "min_ms": round(min(samples), 2) if samples else None,
        "max_ms": round(max(samples), 2) if samples else None,
        "avg_ms": round(sum(samples) / len(samples), 2) if samples else None,
        "samples": [round(sample, 2) for sample in samples],
    }
    return {
        "title": title,
        "year": year,
        "category": category,
        "season": season,
        "episode": episode,
        "status": "ok" if payload is not None else "unavailable",
        "http_status": status,
        "resolve_latency_ms": latencies,
        "total_streams": len(streams),
        "structurally_playable_streams": sum(1 for stream in streams if is_structurally_playable(stream)),
        "unique_voices": voices,
        "unique_qualities": qualities,
        "direct_count": direct_count,
        "p2p_count": p2p_count,
        "other_count": other_count,
        "first_playable_candidate": first_playable(streams),
        "error": error,
    }


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8888")
    parser.add_argument("--endpoint", default="/resolve")
    parser.add_argument("--title", action="append", help="Title; may be repeated")
    parser.add_argument("--spec", action="append", help="title|year|category|season|episode; may be repeated")
    parser.add_argument("--year", type=int, default=DEFAULT_YEAR)
    parser.add_argument("--category", default=DEFAULT_CATEGORY)
    parser.add_argument("--season", type=int, default=None)
    parser.add_argument("--episode", type=int, default=None)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--refresh", action="store_true", help="Request provider refresh; may be slower")
    parser.add_argument("--json", action="store_true", dest="as_json")
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    if args.repeat < 1 or args.timeout <= 0:
        print("--repeat must be >= 1 and --timeout must be > 0", file=sys.stderr)
        return 2

    specs: List[Tuple[str, int, str, Optional[int], Optional[int]]] = []
    try:
        for spec in args.spec or []:
            specs.append(parse_spec(spec))
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    if not specs:
        titles = args.title or [DEFAULT_TITLE]
        for title in titles:
            if not args.title:
                specs.append((title, DEFAULT_YEAR, DEFAULT_CATEGORY, DEFAULT_SEASON, DEFAULT_EPISODE))
            else:
                specs.append((title, args.year, args.category, args.season, args.episode))

    results = [
        audit_title(
            args.base_url,
            args.endpoint,
            title,
            year,
            category,
            season,
            episode,
            args.repeat,
            args.timeout,
            args.refresh,
        )
        for title, year, category, season, episode in specs
    ]
    if args.as_json:
        print(json.dumps({"base_url": args.base_url, "results": results}, ensure_ascii=False, indent=2))
    else:
        print(f"Backend: {args.base_url}  endpoint: {args.endpoint}")
        for result in results:
            location = result["title"]
            if result["season"] is not None:
                location += f" S{result['season']:02d}E{result['episode']:02d}"
            latency = result["resolve_latency_ms"]
            print(
                f"{location} [{result['status']}] "
                f"latency={latency['avg_ms']}ms streams={result['total_streams']} "
                f"voices={len(result['unique_voices'])} qualities={len(result['unique_qualities'])} "
                f"direct={result['direct_count']} p2p={result['p2p_count']} "
                f"first={result['first_playable_candidate']}"
            )
            if result.get("error"):
                print(f"  error: {result['error']}")
    return 0 if all(result["status"] == "ok" for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
