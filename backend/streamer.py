#!/usr/bin/env python3
import os
import sys
import subprocess
import re
import time
import json
import random
import signal
import threading
import sqlite3
import hashlib
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from pathlib import Path
from datetime import datetime, timezone
from typing import Dict, Any, Optional, List, Tuple
import catalog_api
import live_catalog_sync
from catalog_schema_v2 import get_revision as get_catalog_revision, normalize_ru_text
from stream_validation import (
    bind_stream_identity,
    canonical_stream_locator,
    is_valid_magnet,
    sanitize_streams,
    stable_stream_id,
    stream_variant_key,
)

class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

HOST = "127.0.0.1"
PORT = 8888
# Keep this checkout self-contained. A release worker can copy the directory
# as a unit without an accidental read/write through an old live absolute path.
DIR = Path(__file__).resolve().parent

TEST_STREAM_PATTERNS = [
    "devstreaming-cdn.apple.com",
    "storage.googleapis.com",
    "bipbop",
    "bigbuckbunny",
    "exoplayer-test-media"
]

def is_test_stream_url(url: Optional[str]) -> bool:
    if not url:
        return False
    low = url.lower()
    return any(p in low for p in TEST_STREAM_PATTERNS)


def enrich_stream_identity(
    streams: List[Dict[str, Any]],
    season: Optional[int],
    episode: Optional[int],
) -> List[Dict[str, Any]]:
    """Attach a deterministic release identity to every resolver result."""
    result: List[Dict[str, Any]] = []
    used_ids = set()
    for raw in streams:
        stream = dict(raw)
        url = str(stream.get("url") or "").strip()
        match = re.search(r"(?:^|[?&])xt=urn:btih:([^&\s]+)", url, re.IGNORECASE)
        info_hash = str(
            stream.get("info_hash") or stream.get("infoHash") or ""
        ).strip() or (match.group(1).lower() if match else "")
        resolved_season = stream.get("season")
        resolved_episode = stream.get("episode")
        stream["season"] = season if resolved_season is None else resolved_season
        stream["episode"] = episode if resolved_episode is None else resolved_episode
        if info_hash:
            stream["info_hash"] = info_hash
        existing_id = str(
            stream.get("stream_id") or stream.get("streamId") or ""
        ).strip()
        if existing_id and existing_id not in used_ids:
            stream["stream_id"] = existing_id
            used_ids.add(existing_id)
        else:
            # Magnet tracker lists and display names are mutable. Use the
            # stable content locator while retaining voice and quality in the
            # identity so distinct variants never share a stream ID.
            identity = "|".join(
                str(stream.get(key) or "").strip().lower()
                for key in (
                    "source", "provider_item_id", "info_hash",
                    "file_index", "file_path", "season", "episode",
                    "quality", "voice",
                )
            ) + "|" + canonical_stream_locator(url)
            candidate_id = stable_stream_id(stream, url)
            if candidate_id in used_ids:
                candidate_id = "stream:" + hashlib.sha256(
                    (identity + "|" + str(len(result))).encode("utf-8")
                ).hexdigest()[:24]
            stream["stream_id"] = candidate_id
            used_ids.add(candidate_id)
        result.append(stream)
    return result
import ipaddress
import socket
from urllib.parse import urlparse

# Allowed public CDN and balancer domains for proxying
ALLOWED_PROXY_DOMAINS = {
    "hdrezka.ag", "rezka.ag", "voidboost.net", "voidboost.cc",
    "kodik.info", "kodik.biz", "kodik.cc",
    "collaps.org", "api.collaps.org",
    "alloha.tv", "alloha.app",
    "archive.org", "yts.mx", "yts.am", "yts.lt",
    "apibay.org", "thepiratebay.org"
}

def is_safe_proxy_url(url: str) -> bool:
    """
    Validates that the URL targets a public server from the allowed whitelist
    and is not a private, loopback, link-local, or reserved IP (SSRF guard).
    """
    if not url or not isinstance(url, str):
        return False
    if is_test_stream_url(url):
        return False
    try:
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https"):
            return False
        hostname = parsed.hostname
        if not hostname:
            return False

        # 1. Check if hostname is an IP address
        try:
            ip = ipaddress.ip_address(hostname)
            if ip.is_private or ip.is_loopback or ip.is_reserved or ip.is_link_local:
                return False
        except ValueError:
            # 2. Hostname is a domain name -> resolve DNS and check resolved IP addresses
            try:
                resolved_ips = socket.getaddrinfo(hostname, None)
                for res in resolved_ips:
                    ip = ipaddress.ip_address(res[4][0])
                    if ip.is_private or ip.is_loopback or ip.is_reserved or ip.is_link_local:
                        return False
            except (socket.gaierror, Exception):
                return False

        # 3. Check if hostname matches allowed domain whitelist
        low_host = hostname.lower()
        base_domain = ".".join(low_host.split(".")[-2:]) if "." in low_host else low_host
        return any(low_host == d or low_host.endswith("." + d) for d in ALLOWED_PROXY_DOMAINS) or \
               base_domain in ALLOWED_PROXY_DOMAINS

    except Exception:
        return False

PID_FILE = DIR / ".streamer.pid"
CACHE_DIR = DIR / "stream_cache"
CACHE_DIR.mkdir(parents=True, exist_ok=True)
CACHE_DB_PATH = CACHE_DIR / "streams_cache.db"

# A short in-process cache avoids reopening SQLite for repeated playback
# requests while the persistent cache remains the cross-process source of truth.
_STREAM_MEMORY_CACHE: Dict[str, tuple[float, List[Dict[str, Any]]]] = {}
_STREAM_MEMORY_CACHE_LOCK = threading.Lock()
_RESOLVE_LOCKS: Dict[str, threading.Lock] = {}
_RESOLVE_LOCKS_LOCK = threading.Lock()
STREAM_CACHE_VERSION = "v5"
STREAM_MEMORY_CACHE_MAX_SECONDS = 30.0

DIRECT_STREAM_REFRESH_SECONDS = 5 * 60


def _timestamp_seconds(value: Any) -> Optional[float]:
    """Parse SQLite/ISO timestamps as UTC without probing a media URL."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip()
    if not text:
        return None
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        try:
            parsed = datetime.strptime(text, "%Y-%m-%d %H:%M:%S")
        except ValueError:
            return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.timestamp()


def catalog_streams_need_refresh(
    movie: Dict[str, Any],
    streams: List[Dict[str, Any]],
    *,
    now: Optional[float] = None,
) -> bool:
    """Refresh only time-sensitive HTTP candidates, never magnets.

    Zona/CDN URLs can expire while the metadata card remains valid.  The
    catalog timestamp is the freshness contract; no HEAD request or playback
    attempt is made here.  A missing timestamp is treated conservatively as
    stale so old rows are refreshed once and then receive a timestamp.
    """
    if not streams:
        return True

    has_direct = any(
        isinstance(stream, dict)
        and str(stream.get("url") or stream.get("playback_url") or "")
        .strip()
        .lower()
        .startswith(("http://", "https://"))
        for stream in streams
    )
    if not has_direct:
        return False

    updated_at = movie.get("link_updated_at") or movie.get("linkUpdatedAt")
    updated_seconds = _timestamp_seconds(updated_at)
    if updated_seconds is None:
        return True

    current_seconds = time.time() if now is None else float(now)
    return current_seconds - updated_seconds >= DIRECT_STREAM_REFRESH_SECONDS

ARIA2_RPC_URL = "http://127.0.0.1:6800/jsonrpc"
ARIA2_RPC_TOKEN = "token:movia_secret"
_TORRENT_GIDS: Dict[str, str] = {}
# Only GIDs created by this streamer process may be automatically removed on request timeout.
# Rediscovered aria2 tasks can be shared with later requests and must survive a client failure.
_TORRENT_OWNED_GIDS: set[str] = set()
_TORRENT_GIDS_LOCK = threading.Lock()
_TORRENT_REUSABLE_STATUSES = {"active", "waiting", "paused"}
_TORRENT_STATUS_PRIORITY = {
    "active": 2,
    "waiting": 1,
    "paused": 1,
}
_TORRENT_STATUS_KEYS = ["gid", "status", "completedLength", "infoHash", "followedBy", "following"]
_TORRENT_MEDIA_SUFFIXES = {".mp4", ".mkv", ".avi", ".ts", ".m4v", ".webm"}
_TORRENT_PLAYBACK_LEASE_FILENAME = ".movia-playback-lease"

def _touch_torrent_playback_lease(task_dir: Path) -> None:
    """Mark an exact torrent cache entry as recently used by playback."""
    try:
        task_dir.mkdir(parents=True, exist_ok=True)
        lease = task_dir / _TORRENT_PLAYBACK_LEASE_FILENAME
        lease.touch(exist_ok=True)
    except OSError:
        # Lease failure must not turn an otherwise playable stream into a hard
        # error; the pruner remains fail-closed on its own safety probes.
        pass


def _torrent_path_is_media(raw_path: Any) -> bool:
    try:
        path = urllib.parse.urlsplit(str(raw_path or "")).path
    except Exception:
        path = str(raw_path or "")
    return Path(path).suffix.lower() in _TORRENT_MEDIA_SUFFIXES


def _torrent_files_have_media(files: Any) -> Optional[bool]:
    if not isinstance(files, list) or not files:
        return None
    for item in files:
        if not isinstance(item, dict) or not _torrent_path_is_media(item.get("path")):
            continue
        try:
            if int(item.get("length") or 0) > 0:
                return True
        except (TypeError, ValueError):
            continue
    return False


def _torrent_file_by_index(
    files: Any,
    file_index: Optional[int],
) -> Optional[Dict[str, Any]]:
    """Return the exact playable torrent file addressed by a Lampa/TorrServer index."""
    if file_index is None or not isinstance(files, list):
        return None
    wanted = str(file_index)
    for item in files:
        if not isinstance(item, dict):
            continue
        if str(item.get("index")) != wanted:
            continue
        if _torrent_path_is_media(item.get("path")):
            return item
        return None
    return None


def _torrent_media_profile(gid: str, timeout: float = 3.0) -> Optional[bool]:
    try:
        files = aria2_rpc("aria2.getFiles", [gid], timeout=timeout) or []
    except Exception:
        return None
    return _torrent_files_have_media(files)


def aria2_rpc(method: str, params: List[Any], timeout: float = 3.0) -> Any:
    payload = json.dumps({
        "jsonrpc": "2.0",
        "id": "movia",
        "method": method,
        "params": [ARIA2_RPC_TOKEN, *params],
    }).encode("utf-8")
    req = urllib.request.Request(ARIA2_RPC_URL, data=payload, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    if data.get("error"):
        raise RuntimeError(str(data["error"]))
    return data.get("result")


def _normalize_torrent_info_hash(value: Any) -> str:
    return str(value or "").strip().lower()


def _torrent_task_from_status(
    raw_task: Any,
    expected_info_hash: str,
    fallback_status: Optional[str] = None,
    assume_info_hash: bool = False,
) -> Optional[Dict[str, Any]]:
    if not isinstance(raw_task, dict):
        return None

    gid = str(raw_task.get("gid") or "").strip()
    status = str(raw_task.get("status") or fallback_status or "").strip().lower()
    if not gid or status not in _TORRENT_REUSABLE_STATUSES:
        return None

    raw_info_hash = (
        raw_task.get("infoHash")
        or raw_task.get("info_hash")
        or raw_task.get("infohash")
    )
    task_info_hash = _normalize_torrent_info_hash(raw_info_hash)
    if task_info_hash:
        if task_info_hash != expected_info_hash:
            return None
    elif not assume_info_hash:
        # A task without an infoHash cannot be safely matched to this magnet.
        return None

    try:
        completed_length = max(0, int(raw_task.get("completedLength") or 0))
    except (TypeError, ValueError):
        completed_length = 0

    followed_by = [
        str(value).strip()
        for value in (raw_task.get("followedBy") or [])
        if str(value or "").strip()
    ]
    following = str(raw_task.get("following") or "").strip()

    return {
        "gid": gid,
        "status": status,
        "completedLength": completed_length,
        "infoHash": expected_info_hash,
        "followedBy": followed_by,
        "following": following,
    }


def _discover_torrent_tasks(info_hash: str) -> Dict[str, Dict[str, Any]]:
    """Find reusable aria2 BitTorrent tasks, including after a process restart."""
    candidates: Dict[str, Dict[str, Any]] = {}
    for method, params, fallback_status in (
        ("aria2.tellActive", [_TORRENT_STATUS_KEYS], "active"),
        ("aria2.tellWaiting", [0, 1000, _TORRENT_STATUS_KEYS], "waiting"),
    ):
        try:
            tasks = aria2_rpc(method, params, timeout=1.5) or []
        except Exception:
            continue
        if not isinstance(tasks, list):
            continue
        for raw_task in tasks:
            task = _torrent_task_from_status(
                raw_task,
                info_hash,
                fallback_status=fallback_status,
            )
            if task:
                candidates[task["gid"]] = task
    for task in candidates.values():
        task["has_media_files"] = _torrent_media_profile(task["gid"])
    return candidates


def _torrent_task_sort_key(task: Dict[str, Any]) -> tuple[int, int, int, str]:
    """Prefer materialized media tasks, then active/progress, with stable ties."""
    media_profile = task.get("has_media_files")
    media_rank = 0 if media_profile is True else 1 if media_profile is None else 2
    return (
        media_rank,
        -_TORRENT_STATUS_PRIORITY.get(str(task.get("status") or "").lower(), -1),
        -int(task.get("completedLength") or 0),
        str(task.get("gid") or ""),
    )


def _mapped_torrent_task(
    info_hash: str,
    gid: str,
) -> Optional[Dict[str, Any]]:
    try:
        status = aria2_rpc(
            "aria2.tellStatus",
            [gid, _TORRENT_STATUS_KEYS],
            timeout=1.5,
        ) or {}
    except Exception:
        return None
    if not isinstance(status, dict):
        return None
    status = dict(status)
    status.setdefault("gid", gid)
    # The in-memory key already associates this GID with the requested hash;
    # only use that association when aria2 omits the hash from its response.
    task = _torrent_task_from_status(
        status,
        info_hash,
        assume_info_hash=True,
    )
    if task is not None:
        task["has_media_files"] = _torrent_media_profile(gid)
    return task


def _adopt_materialized_torrent_gid(
    info_hash: str,
    parent_gid: str,
    child_gid: str,
) -> str:
    """Atomically move a logical torrent session from metadata parent to media child."""
    normalized_info_hash = _normalize_torrent_info_hash(info_hash)
    child_gid = str(child_gid or "").strip()
    parent_gid = str(parent_gid or "").strip()
    if not child_gid:
        return parent_gid
    with _TORRENT_GIDS_LOCK:
        _TORRENT_GIDS[normalized_info_hash] = child_gid
        if info_hash != normalized_info_hash:
            _TORRENT_GIDS.pop(info_hash, None)
        if parent_gid in _TORRENT_OWNED_GIDS:
            _TORRENT_OWNED_GIDS.add(child_gid)
    return child_gid


def _followed_torrent_media_gid(
    info_hash: str,
    parent_gid: str,
) -> Optional[str]:
    """Return aria2's materialized media child for a magnet metadata task.

    ``aria2.addUri(magnet)`` with ``follow-torrent=mem`` first creates a
    metadata task.  When metadata is ready aria2 exposes the real BitTorrent
    download through ``followedBy``.  Removing/re-adding the parent before that
    transition prevents materialization and leaves Media3 buffering forever.
    """
    normalized_info_hash = _normalize_torrent_info_hash(info_hash)
    parent_gid = str(parent_gid or "").strip()
    if not parent_gid:
        return None
    try:
        parent = aria2_rpc(
            "aria2.tellStatus",
            [parent_gid, _TORRENT_STATUS_KEYS],
            timeout=1.5,
        ) or {}
    except Exception:
        return None
    if not isinstance(parent, dict):
        return None

    child_gids = [
        str(value).strip()
        for value in (parent.get("followedBy") or [])
        if str(value or "").strip()
    ]
    for child_gid in child_gids:
        try:
            child_status = aria2_rpc(
                "aria2.tellStatus",
                [child_gid, _TORRENT_STATUS_KEYS],
                timeout=1.5,
            ) or {}
        except Exception:
            continue
        if not isinstance(child_status, dict):
            continue
        child_hash = _normalize_torrent_info_hash(child_status.get("infoHash"))
        if child_hash and child_hash != normalized_info_hash:
            continue
        if _torrent_media_profile(child_gid, timeout=1.5) is not True:
            continue
        return _adopt_materialized_torrent_gid(
            normalized_info_hash,
            parent_gid,
            child_gid,
        )
    return None


def _force_remove_duplicate_torrent_tasks(
    candidates: List[Dict[str, Any]],
    canonical_gid: str,
) -> None:
    for task in sorted(candidates, key=lambda item: str(item.get("gid") or "")):
        gid = str(task.get("gid") or "")
        if not gid or gid == canonical_gid:
            continue
        try:
            # forceRemove removes the aria2 task but leaves downloaded files in place.
            aria2_rpc("aria2.forceRemove", [gid], timeout=1.5)
            _TORRENT_OWNED_GIDS.discard(gid)
        except Exception:
            pass


def _magnet_tracker_option(magnet: str) -> str:
    """Return a comma-separated aria2 bt-tracker option from magnet tr params."""
    try:
        query = urllib.parse.urlsplit(str(magnet or "")).query
        trackers = []
        seen = set()
        for key, value in urllib.parse.parse_qsl(query, keep_blank_values=True):
            if str(key or "").casefold() != "tr":
                continue
            tracker = str(value or "").strip()
            if not tracker or tracker in seen:
                continue
            seen.add(tracker)
            trackers.append(tracker)
            if len(trackers) >= 32:
                break
        return ",".join(trackers)
    except Exception:
        return ""


def _refresh_torrent_trackers(gid: str, magnet: str) -> None:
    tracker_option = _magnet_tracker_option(magnet)
    if not gid or not tracker_option:
        return
    try:
        aria2_rpc(
            "aria2.changeOption",
            [str(gid), {"bt-tracker": tracker_option}],
            timeout=1.5,
        )
    except Exception:
        # Tracker refresh is additive resilience; failure must not invalidate
        # an otherwise reusable task.
        pass


def get_or_create_torrent_gid(info_hash: str, magnet: str, task_dir: Path) -> str:
    normalized_info_hash = _normalize_torrent_info_hash(info_hash)
    with _TORRENT_GIDS_LOCK:
        candidates = _discover_torrent_tasks(normalized_info_hash)

        existing = _TORRENT_GIDS.get(normalized_info_hash)
        if not existing and info_hash != normalized_info_hash:
            # Tolerate an entry created by an older caller that did not normalize
            # the hash, while storing the selected GID under the canonical key.
            existing = _TORRENT_GIDS.get(info_hash)
        if existing:
            existing = str(existing)
            if existing not in candidates:
                mapped = _mapped_torrent_task(normalized_info_hash, existing)
                if mapped:
                    candidates[existing] = mapped
                else:
                    _TORRENT_GIDS.pop(normalized_info_hash, None)
                    if info_hash != normalized_info_hash:
                        _TORRENT_GIDS.pop(info_hash, None)

        usable_candidates = [
            task for task in candidates.values()
            if task.get("has_media_files") is not False
        ]
        if usable_candidates:
            canonical_task = min(usable_candidates, key=_torrent_task_sort_key)
            canonical_gid = str(canonical_task["gid"])
            # Keep metadata-only tasks intact; they may belong to another request
            # and are not a usable playback task. Only deduplicate candidates that
            # can represent actual media or whose profile is temporarily unknown.
            _force_remove_duplicate_torrent_tasks(
                usable_candidates,
                canonical_gid,
            )
            _TORRENT_GIDS[normalized_info_hash] = canonical_gid
            if info_hash != normalized_info_hash:
                _TORRENT_GIDS.pop(info_hash, None)
            _refresh_torrent_trackers(canonical_gid, magnet)
            return canonical_gid

        metadata_candidates = [
            task for task in candidates.values()
            if task.get("has_media_files") is False
        ]
        if metadata_candidates:
            # A magnet metadata task is a valid *pending* logical session.
            # Reuse it and let /stream adopt aria2's followedBy media child.
            pending = min(metadata_candidates, key=_torrent_task_sort_key)
            pending_gid = str(pending["gid"])
            _TORRENT_GIDS[normalized_info_hash] = pending_gid
            if info_hash != normalized_info_hash:
                _TORRENT_GIDS.pop(info_hash, None)
            _refresh_torrent_trackers(pending_gid, magnet)
            return pending_gid

        # No reusable or pending task exists. Start one bounded magnet metadata
        # download; /stream will adopt its followedBy media child when ready.
        add_options = {
            "dir": str(task_dir),
            "follow-torrent": "mem",
            "bt-prioritize-piece": "head=20M,tail=10M",
            "file-allocation": "none",
            "seed-time": "0",
        }
        tracker_option = _magnet_tracker_option(magnet)
        if tracker_option:
            add_options["bt-tracker"] = tracker_option
        gid = aria2_rpc("aria2.addUri", [[magnet], add_options], timeout=4.0)
        if not gid:
            raise RuntimeError("aria2.addUri returned no gid")
        gid = str(gid)
        _TORRENT_GIDS[normalized_info_hash] = gid
        _TORRENT_OWNED_GIDS.add(gid)
        return gid

def release_torrent_gid(info_hash: str, gid: Optional[str], remove_task: bool = False) -> None:
    normalized_info_hash = _normalize_torrent_info_hash(info_hash)
    with _TORRENT_GIDS_LOCK:
        if _TORRENT_GIDS.get(normalized_info_hash) == gid:
            _TORRENT_GIDS.pop(normalized_info_hash, None)
        if info_hash != normalized_info_hash and _TORRENT_GIDS.get(info_hash) == gid:
            _TORRENT_GIDS.pop(info_hash, None)
    if remove_task and gid and gid in _TORRENT_OWNED_GIDS:
        try:
            aria2_rpc("aria2.forceRemove", [gid], timeout=1.5)
        except Exception:
            pass
        finally:
            _TORRENT_OWNED_GIDS.discard(gid)


USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64; rv:126.0) Gecko/20100101 Firefox/126.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Safari/605.1.15",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0"
]

def get_random_user_agent() -> str:
    return random.choice(USER_AGENTS)

def init_cache_db():
    conn = sqlite3.connect(str(CACHE_DB_PATH))
    cur = conn.cursor()
    cur.execute("CREATE TABLE IF NOT EXISTS streams_cache (cache_key TEXT PRIMARY KEY, streams_json TEXT NOT NULL, expires_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);")
    conn.commit()
    conn.close()

init_cache_db()

def get_cached_streams(cache_key: str) -> Optional[List[Dict[str, Any]]]:
    now_mono = time.monotonic()
    with _STREAM_MEMORY_CACHE_LOCK:
        memory_entry = _STREAM_MEMORY_CACHE.get(cache_key)
        if memory_entry and memory_entry[0] > now_mono:
            return [dict(stream) for stream in memory_entry[1]]
        if memory_entry:
            _STREAM_MEMORY_CACHE.pop(cache_key, None)

    try:
        conn = sqlite3.connect(str(CACHE_DB_PATH))
        cur = conn.cursor()
        now = int(time.time())
        cur.execute(
            "SELECT streams_json, expires_at FROM streams_cache "
            "WHERE cache_key = ? AND expires_at > ? LIMIT 1;",
            (cache_key, now),
        )
        row = cur.fetchone()
        conn.close()
        if row and row[0]:
            cached = sanitize_streams(json.loads(row[0]), require_source=True)
            if cached:
                remaining = max(1.0, float(row[1]) - time.time())
                with _STREAM_MEMORY_CACHE_LOCK:
                    _STREAM_MEMORY_CACHE[cache_key] = (
                        time.monotonic() + min(remaining, STREAM_MEMORY_CACHE_MAX_SECONDS),
                        [dict(stream) for stream in cached],
                    )
                return cached
    except Exception:
        pass
    return None

def set_cached_streams(
    cache_key: str,
    streams: List[Dict[str, Any]],
    ttl_hours: int = 48,
    ttl_seconds: Optional[int] = None,
):
    try:
        clean_streams = sanitize_streams(streams, require_source=True)
        if not clean_streams:
            return
        conn = sqlite3.connect(str(CACHE_DB_PATH))
        cur = conn.cursor()
        now = int(time.time())
        effective_ttl = int(ttl_seconds) if ttl_seconds is not None else int(ttl_hours * 3600)
        expires_at = now + max(1, effective_ttl)
        cur.execute(
            "INSERT OR REPLACE INTO streams_cache "
            "(cache_key, streams_json, expires_at, updated_at) VALUES (?, ?, ?, ?);",
            (cache_key, json.dumps(clean_streams, ensure_ascii=False), expires_at, now),
        )
        conn.commit()
        conn.close()
        with _STREAM_MEMORY_CACHE_LOCK:
            _STREAM_MEMORY_CACHE[cache_key] = (
                time.monotonic() + min(max(1.0, float(effective_ttl)), STREAM_MEMORY_CACHE_MAX_SECONDS),
                [dict(stream) for stream in clean_streams],
            )
    except Exception:
        pass


def _resolve_lock_for(cache_key: str) -> threading.Lock:
    """Return a stable per-title lock so concurrent misses share one lookup."""
    with _RESOLVE_LOCKS_LOCK:
        return _RESOLVE_LOCKS.setdefault(cache_key, threading.Lock())

def sanitize_magnet_uri(raw_uri: str) -> Optional[str]:
    if not raw_uri or not isinstance(raw_uri, str):
        return None
    raw_uri = raw_uri.strip()
    if not raw_uri.startswith("magnet:?"):
        return None
    if not is_valid_magnet(raw_uri):
        return None
    bad_chars = ['\n', '\r', '\t', '`', '$', ';', '|', '<', '>', '"']
    sanitized = raw_uri
    for c in bad_chars:
        sanitized = sanitized.replace(c, '')
    try:
        from torrent_resolver import enrich_magnet_with_trackers
        sanitized = enrich_magnet_with_trackers(sanitized)
    except Exception:
        pass
    return sanitized

DOH_CACHE: Dict[str, str] = {}
DOH_PROVIDERS = [
    ("https://cloudflare-dns.com/dns-query", {"Accept": "application/dns-json"}),
    ("https://1.1.1.1/dns-query", {"Accept": "application/dns-json"}),
    ("https://dns.google/resolve", {"Accept": "application/json"}),
]

def doh_resolve_host(hostname: str) -> Optional[str]:
    if not hostname:
        return None
    if hostname in DOH_CACHE:
        return DOH_CACHE[hostname]
    
    # Do not resolve IP addresses
    try:
        ipaddress.ip_address(hostname)
        return hostname
    except ValueError:
        pass

    for provider_url, headers in DOH_PROVIDERS:
        try:
            doh_url = f"{provider_url}?name={urllib.parse.quote(hostname)}&type=A"
            req_headers = dict(headers)
            req_headers["User-Agent"] = get_random_user_agent()
            req = urllib.request.Request(doh_url, headers=req_headers)
            with urllib.request.urlopen(req, timeout=3) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    answers = data.get("Answer", [])
                    for ans in answers:
                        if ans.get("type") == 1 and ans.get("data"):
                            resolved_ip = ans.get("data")
                            DOH_CACHE[hostname] = resolved_ip
                            return resolved_ip
        except Exception:
            continue
    return None

class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

def has_playable_container_head(path: Path) -> bool:
    """Return True only after the sparse torrent file has a recognizable media header.

    aria2 creates files at their final logical size before the first pieces are
    downloaded. Checking st_size alone therefore exposes zero-filled sparse data
    to Media3 and causes container sniff failures.
    """
    try:
        if not path.exists() or not path.is_file():
            return False
        suffix = path.suffix.lower()
        with path.open("rb") as fh:
            head = fh.read(64 * 1024)
        if len(head) < 16:
            return False
        if suffix in {".mp4", ".m4v"}:
            # ISO-BMFF: first box normally contains the ftyp brand.
            return b"ftyp" in head[:64]
        if suffix == ".mkv":
            return head.startswith(b"\x1a\x45\xdf\xa3")
        if suffix == ".avi":
            return head.startswith(b"RIFF") and head[8:12] == b"AVI "
        if suffix in {".ts", ".m2ts"}:
            # MPEG-TS sync byte (m2ts may carry a 4-byte timestamp prefix).
            return head[0] == 0x47 or (len(head) > 4 and head[4] == 0x47)
        return any(head)
    except Exception:
        return False


def _aria2_piece_is_complete(bitfield_hex: str, piece_index: int) -> bool:
    try:
        raw = bytes.fromhex(bitfield_hex or "")
        byte_index = piece_index // 8
        if byte_index < 0 or byte_index >= len(raw):
            return False
        mask = 1 << (7 - (piece_index % 8))
        return (raw[byte_index] & mask) != 0
    except Exception:
        return False


def _torrent_file_layout(status: Dict[str, Any], file_path: Path) -> tuple[int, int]:
    """Return (global torrent byte offset, file length) for a selected file."""
    target = os.path.realpath(str(file_path))
    offset = 0
    for item in sorted(status.get("files") or [], key=lambda x: int(x.get("index") or 0)):
        length = int(item.get("length") or 0)
        current = os.path.realpath(str(item.get("path") or ""))
        if current == target:
            return offset, length
        offset += length
    return 0, int(file_path.stat().st_size if file_path.exists() else 0)


def _torrent_range_ready(gid: Optional[str], file_path: Path, start: int, length: int) -> bool:
    if not gid:
        return True
    try:
        status = aria2_rpc(
            "aria2.tellStatus",
            [gid, ["status", "pieceLength", "numPieces", "bitfield", "files"]],
            timeout=2.0,
        ) or {}
        if status.get("status") == "complete":
            return True
        piece_length = int(status.get("pieceLength") or 0)
        bitfield = str(status.get("bitfield") or "")
        if piece_length <= 0 or not bitfield:
            return False
        file_offset, file_length = _torrent_file_layout(status, file_path)
        if file_length <= 0:
            return False
        local_start = max(0, min(int(start), file_length - 1))
        local_end = max(local_start, min(file_length - 1, local_start + max(1, int(length)) - 1))
        first_piece = (file_offset + local_start) // piece_length
        last_piece = (file_offset + local_end) // piece_length
        return all(_aria2_piece_is_complete(bitfield, i) for i in range(first_piece, last_piece + 1))
    except Exception:
        return False


def _prioritize_and_wait_torrent_range(
    gid: Optional[str],
    file_path: Path,
    start: int,
    length: int,
    timeout_sec: float = 12.0,
) -> bool:
    if not gid:
        return True
    if _torrent_range_ready(gid, file_path, start, length):
        return True
    # aria2 cannot prioritize an arbitrary piece directly. Expanding the selected
    # file's head priority up to the requested byte makes short forward seeks fetch
    # the missing pieces first while retaining the tail priority needed by MKV cues.
    try:
        priority_head = max(32 * 1024 * 1024, int(start) + int(length) + 32 * 1024 * 1024)
        aria2_rpc(
            "aria2.changeOption",
            [gid, {"bt-prioritize-piece": f"head={priority_head},tail=16M"}],
            timeout=2.0,
        )
    except Exception:
        pass
    deadline = time.monotonic() + max(0.5, timeout_sec)
    while time.monotonic() < deadline:
        if _torrent_range_ready(gid, file_path, start, length):
            return True
        time.sleep(0.20)
    return False


VIDEO_SUFFIXES = {".mp4", ".mkv", ".avi", ".ts", ".m4v", ".webm"}


def is_physically_complete_file(path: Path) -> bool:
    """True only when a regular file has real allocated blocks for its full logical size.

    aria2 creates sparse files at final logical size before their pieces exist, so
    st_size alone is not evidence that the media is complete. POSIX st_blocks is
    counted in 512-byte units and lets us reject those sparse placeholders.
    """
    try:
        stat = path.stat()
        if not path.is_file() or stat.st_size <= 0:
            return False
        allocated_bytes = int(getattr(stat, "st_blocks", 0) or 0) * 512
        return allocated_bytes >= int(stat.st_size)
    except OSError:
        return False


def episode_path_matches(path: Any, season: Optional[int], episode: Optional[int]) -> bool:
    if season is None or episode is None:
        return False
    try:
        season_i = int(season)
        episode_i = int(episode)
    except (TypeError, ValueError):
        return False
    if season_i < 0 or episode_i < 0:
        return False
    name = str(path or "").lower()
    # Require both the season and episode tokens.
    patterns = [
        rf"s0*{season_i}[._\s-]*e0*{episode_i}(?![0-9])",
        rf"\b0*{season_i}x0*{episode_i}(?![0-9])",
        rf"сезон\s*0*{season_i}[^0-9]*серия\s*0*{episode_i}(?![0-9])",
    ]
    return any(re.search(pat, name, re.IGNORECASE) is not None for pat in patterns)


def find_completed_cached_video(
    task_dir: Path,
    season: Optional[int] = None,
    episode: Optional[int] = None,
    file_index: Optional[int] = None,
) -> Optional[Path]:
    # The cache path does not retain aria2/TorrServer file indexes. When the
    # caller supplies one, re-read torrent metadata instead of guessing from
    # the largest cached video.
    if file_index is not None:
        return None
    candidates: List[Path] = []
    try:
        for path in task_dir.rglob("*"):
            if (
                path.is_file()
                and path.suffix.lower() in VIDEO_SUFFIXES
                and is_physically_complete_file(path)
                and has_playable_container_head(path)
            ):
                candidates.append(path)
    except OSError:
        return None

    if season is not None and episode is not None:
        matches = [p for p in candidates if episode_path_matches(p, season, episode)]
        if not matches:
            return None
        # A release should normally contain one exact episode. Deterministic
        # ordering makes duplicate encodes stable without ever selecting another episode.
        return max(matches, key=lambda p: (p.stat().st_size, str(p)))

    if not candidates:
        return None
    # Movie torrents may contain samples/extras. Prefer the largest complete video.
    return max(candidates, key=lambda p: (p.stat().st_size, str(p)))


def infer_stream_mime(url: str, header_type: str = "") -> str:
    clean_url = url.split("?")[0].lower()
    if header_type and "text/html" not in header_type and "text/plain" not in header_type and "octet-stream" not in header_type:
        return header_type
    if clean_url.endswith(".m3u8"):
        return "application/vnd.apple.mpegurl"
    elif clean_url.endswith(".mp4") or clean_url.endswith(".m4v"):
        return "video/mp4"
    elif clean_url.endswith(".ts"):
        return "video/mp2t"
    elif clean_url.endswith(".webm"):
        return "video/webm"
    elif clean_url.endswith(".mkv"):
        return "video/x-matroska"
    elif clean_url.endswith(".avi"):
        return "video/x-msvideo"
    elif clean_url.endswith(".mpd"):
        return "application/dash+xml"
    return header_type or "video/mp4"


_RUSSIAN_VOICE_HINTS = (
    "дубляж", "lostfilm", "hdrezka", "red head sound", "кубик", "кураж",
    "alexfilm", "newstudio", "flarrow", "jaskier", "tvshows", "le-vitation",
    "пифагор", "сыендук", "кравец", "2x2", "2х2", "профессиональный", "мво",
    "двухголосый", "дво", "авторский", "одноголосый", "чистый звук",
)


def _quality_rank(value: Any) -> int:
    """Prefer higher declared quality without rewriting provider metadata."""
    low = str(value or "").strip().lower()
    if any(token in low for token in ("2160", "4k", "uhd")):
        return 0
    if "1440" in low:
        return 1
    if any(token in low for token in ("1080", "fullhd", "fhd")):
        return 2
    if "720" in low or low in {"hd", "hd 720"}:
        return 3
    if "480" in low or "sd" in low:
        return 4
    if not low or low == "не указано":
        return 6
    if any(token in low for token in ("cam", "telesync", "ts")):
        return 7
    return 5


def _voice_rank(value: Any) -> int:
    """Rank language class, never a provider/translator brand."""
    low = str(value or "").strip().casefold()
    if any(token in low for token in ("original", "оригинал", "english", "англ")):
        return 2
    if not low or low == "не указано":
        return 1
    if any(token in low for token in ("рус", "russian", "ru-")):
        return 0
    return 1


def _stream_local_ready_video(stream: Dict[str, Any]) -> bool:
    """Return true only when this exact P2P variant already has a complete local video.

    Local readiness is runtime health/startup evidence, not a transport preference.
    Direct streams are unaffected; a cold torrent still competes on the ordinary
    health/latency/peer criteria.
    """
    url = str(stream.get("url") or "").strip()
    transport = str(stream.get("transport") or "").strip().casefold()
    if not (url.casefold().startswith("magnet:") or transport in {"torrent", "p2p", "torrent_p2p", "magnet"}):
        return False
    info_hash = str(stream.get("info_hash") or stream.get("infoHash") or "").strip().lower()
    if not info_hash:
        match = re.search(r"(?:^|[?&])xt=urn:btih:([^&\s]+)", url, re.IGNORECASE)
        info_hash = match.group(1).lower() if match else ""
    if not info_hash:
        return False
    season = _stream_part_number(stream, "season")
    episode = _stream_part_number(stream, "episode")
    task_dir = DIR / "torrent_cache" / info_hash
    if not task_dir.exists():
        return False
    try:
        return find_completed_cached_video(
            task_dir,
            season=season,
            episode=episode,
            file_index=_stream_part_number(stream, "file_index"),
        ) is not None
    except Exception:
        return False


def _playback_stream_sort_key(
    stream: Dict[str, Any],
    *,
    requested_voice: Optional[str] = None,
    requested_quality: Optional[str] = None,
    failed_stream_ids: Optional[set[str]] = None,
) -> tuple:
    failed_stream_ids = failed_stream_ids or set()
    stream_id = str(stream.get("stream_id") or stream.get("streamId") or "").strip()
    normalized_requested_voice = str(requested_voice or "").strip().casefold()
    normalized_requested_quality = str(requested_quality or "").strip().casefold()
    voice = str(stream.get("voice") or stream.get("translation") or "").strip()
    quality = str(stream.get("quality") or "").strip()
    try:
        seeders = max(0, int(stream.get("seeders") or 0))
    except (TypeError, ValueError):
        seeders = 0
    raw_startup_latency = stream.get("startup_latency_ms")
    try:
        startup_latency = (
            max(0.0, float(raw_startup_latency))
            if raw_startup_latency is not None and str(raw_startup_latency).strip() != ""
            else float("inf")
        )
    except (TypeError, ValueError):
        startup_latency = float("inf")
    local_ready = _stream_local_ready_video(stream)
    try:
        health = float(stream.get("health_score"))
    except (TypeError, ValueError):
        health = 0.5
    health = min(max(health, 0.0), 1.0)
    transport = str(stream.get("transport") or "").strip().casefold()
    url = str(stream.get("url") or "").strip().casefold()
    is_p2p = transport in {"torrent", "p2p", "torrent_p2p", "magnet"} or url.startswith("magnet:")

    # Transport type is not a provider preference. A healthy seeded swarm and a
    # healthy direct/CDN stream compete on requested variant + measured health.
    # Only an unseeded P2P candidate receives a transport-specific penalty.
    p2p_no_peers_penalty = 1 if (is_p2p and seeders <= 0) else 0

    requested_voice_penalty = 0 if (
        normalized_requested_voice
        and normalized_requested_voice not in {"auto", "any"}
        and voice.casefold() == normalized_requested_voice
    ) else (1 if normalized_requested_voice not in {"", "auto", "any"} else 0)
    requested_quality_penalty = 0 if (
        normalized_requested_quality
        and normalized_requested_quality not in {"auto", "any"}
        and (
            quality.casefold() == normalized_requested_quality
            or _quality_rank(quality) == _quality_rank(requested_quality)
        )
    ) else (1 if normalized_requested_quality not in {"", "auto", "any"} else 0)
    if local_ready:
        # Complete local bytes are direct evidence of both health and zero
        # network startup for this exact logical episode/variant.
        health = 1.0
        startup_latency = 0.0

    return (
        1 if stream_id in failed_stream_ids or stream.get("problematic") else 0,
        1 if stream.get("unavailable_quality") else 0,
        requested_voice_penalty,
        requested_quality_penalty,
        _voice_rank(voice),
        round((1.0 - health) * 10.0, 3),
        (1_000_000_000.0 if startup_latency == float("inf") else round(startup_latency / 1000.0, 3)),
        p2p_no_peers_penalty,
        -seeders,
        _quality_rank(quality),
        str(stream.get("source") or "").strip().casefold(),
        voice.casefold(),
        quality.casefold(),
        repr(stream_variant_key(stream)),
    )


def _annotate_runtime_stream_health(stream: Dict[str, Any]) -> Dict[str, Any]:
    """Attach ephemeral playback evidence without mutating catalog storage."""
    candidate = dict(stream)
    if _stream_local_ready_video(candidate):
        candidate["local_ready"] = True
        candidate["health_score"] = 1.0
        candidate["startup_latency_ms"] = 0
    return candidate


def rank_playback_streams(
    streams: List[Dict[str, Any]],
    *,
    requested_voice: Optional[str] = None,
    requested_quality: Optional[str] = None,
    failed_stream_ids: Optional[set[str]] = None,
) -> List[Dict[str, Any]]:
    """Rank strict matches, health and startup evidence while retaining variants.

    ``source`` is a stable tie-break only. Provider names and URL schemes never
    decide the winner on their own. Runtime-local readiness is emitted as
    ephemeral health/startup evidence so Android sees the same fact as the
    backend sorter; catalog rows are not rewritten by this annotation.
    """
    annotated = [_annotate_runtime_stream_health(stream) for stream in streams]
    return sorted(
        annotated,
        key=lambda stream: _playback_stream_sort_key(
            stream,
            requested_voice=requested_voice,
            requested_quality=requested_quality,
            failed_stream_ids=failed_stream_ids,
        ),
    )


def _stream_part_number(stream: Dict[str, Any], key: str) -> Optional[int]:
    value = stream.get(key)
    if value is None or str(value).strip() == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _torrent_declared_seasons(stream: Dict[str, Any]) -> Optional[set[int]]:
    """Return explicitly advertised seasons from torrent metadata/name.

    ``None`` means the torrent name does not make a reliable season claim, so it
    remains eligible as a generic fallback. A non-empty set is authoritative
    enough to reject a request outside that advertised coverage before aria2 is
    started.
    """
    url = str(stream.get("url") or "").strip()
    transport = str(stream.get("transport") or "").strip().casefold()
    if not (url.lower().startswith("magnet:") or transport in {"torrent", "p2p", "torrent_p2p", "magnet"}):
        return None

    texts = [
        str(stream.get("name") or ""),
        str(stream.get("title") or ""),
        str(stream.get("release_name") or stream.get("releaseName") or ""),
    ]
    if url.lower().startswith("magnet:"):
        try:
            parsed = urllib.parse.urlsplit(url)
            dn = urllib.parse.parse_qs(parsed.query).get("dn", [])
            texts.extend(str(value) for value in dn)
        except Exception:
            pass
    text = " ".join(value for value in texts if value).strip()
    if not text:
        return None

    seasons: set[int] = set()
    declared = False

    # Common release forms: [S01-04], S01-S04, S05, S05E06.
    for match in re.finditer(r"(?i)\bS0*(\d{1,2})\s*[-–—]\s*S?0*(\d{1,2})(?!\d)", text):
        declared = True
        start, end = int(match.group(1)), int(match.group(2))
        if 0 < start <= end <= 99:
            seasons.update(range(start, end + 1))
    for match in re.finditer(r"(?i)\bS0*(\d{1,2})(?!\d)", text):
        declared = True
        value = int(match.group(1))
        if 0 < value <= 99:
            seasons.add(value)

    # Russian release labels occasionally use "Сезоны 1-4" / "Сезон 5".
    for match in re.finditer(r"(?i)\bсезон(?:ы|а|ов)?\s*0*(\d{1,2})\s*[-–—]\s*0*(\d{1,2})(?!\d)", text):
        declared = True
        start, end = int(match.group(1)), int(match.group(2))
        if 0 < start <= end <= 99:
            seasons.update(range(start, end + 1))
    for match in re.finditer(r"(?i)\bсезон\s*0*(\d{1,2})(?!\d)", text):
        declared = True
        value = int(match.group(1))
        if 0 < value <= 99:
            seasons.add(value)

    return seasons if declared and seasons else None


def filter_streams_for_episode(
    streams: List[Dict[str, Any]],
    season: Optional[int] = None,
    episode: Optional[int] = None,
) -> List[Dict[str, Any]]:
    """Keep only streams compatible with the requested episode.

    Explicitly tagged streams from another season/episode are never returned.
    Untagged streams are retained only as a last-resort generic series/torrent
    package; the torrent playback layer still selects the requested file.
    """
    clean = sanitize_streams(streams, require_source=True)
    if season is None and episode is None:
        return clean

    compatible: List[Dict[str, Any]] = []
    generic: List[Dict[str, Any]] = []
    for stream in clean:
        stream_season = _stream_part_number(stream, "season")
        stream_episode = _stream_part_number(stream, "episode")
        if season is not None and stream_season is not None and stream_season != int(season):
            continue
        if episode is not None and stream_episode is not None and stream_episode != int(episode):
            continue
        if season is not None and stream_season is None and stream_episode is None:
            declared_seasons = _torrent_declared_seasons(stream)
            if declared_seasons is not None and int(season) not in declared_seasons:
                continue
        compatible.append(stream)
        if stream_season is None and stream_episode is None:
            generic.append(stream)

    exact = [
        stream for stream in compatible
        if (season is None or _stream_part_number(stream, "season") == int(season))
        and (episode is None or _stream_part_number(stream, "episode") == int(episode))
    ]
    return exact or generic


def _resolve_torrent_provider(
    title: str,
    year: int,
    category: str,
    season: Optional[int],
    episode: Optional[int],
) -> List[Dict[str, Any]]:
    try:
        from torrent_resolver import resolve_torrents_for_query
        return resolve_torrents_for_query(
            title=title,
            year=year,
            category=category,
            season=season,
            episode=episode,
        ) or []
    except Exception as exc:
        print(f"Torrent resolve error: {exc}")
        return []


def _resolve_balancer_provider(
    title: str,
    tmdb_id: int,
    year: int,
    season: Optional[int],
    episode: Optional[int],
    expected_titles: Optional[List[str]] = None,
    media_type: Optional[str] = None,
    force_refresh: bool = False,
) -> List[Dict[str, Any]]:
    try:
        from balancer_integration import query_open_balancer_stream
        # Torrent discovery is already running independently, so do not invoke
        # the balancer's legacy recursive torrent fallback here.
        return query_open_balancer_stream(
            title=title,
            tmdb_id=tmdb_id,
            year=year,
            season=season,
            episode=episode,
            allow_torrent_fallback=False,
            expected_titles=expected_titles,
            media_type=media_type,
            allow_zona_content_lookup=True,
            force_refresh=force_refresh,
        ) or []
    except Exception as exc:
        print(f"[DEBUG] Balancer query error: {exc}")
        return []

def _current_catalog_revision() -> int:
    """Read the catalog revision without changing the working database."""
    db_file = DIR / "catalog.db"
    if not db_file.exists():
        return 1
    conn = None
    try:
        conn = sqlite3.connect(str(db_file), timeout=2.0)
        return max(1, int(get_catalog_revision(conn)))
    except Exception:
        return 1
    finally:
        if conn is not None:
            conn.close()


def _requested_catalog_media_type(
    category: str,
    season: Optional[int],
    media_type: Optional[str] = None,
) -> str:
    raw = str(media_type or category or "").strip().casefold()
    if season is not None or raw in {
        "tv", "series", "serial", "tv_series", "limited_series",
        "dramas_asian", "anime",
    }:
        return "tv"
    return "movie"


def _catalog_identity_for_request(
    title: str,
    year: int,
    category: str,
    season: Optional[int],
    *,
    original_title: Optional[str] = None,
    catalog_media_id: Any = None,
    media_type: Optional[str] = None,
) -> Tuple[str, Optional[Dict[str, Any]]]:
    """Resolve one existing catalog card, without a prefix/title fallback.

    The resolver is intentionally allowed to have a non-strict seam for old
    unit callers that inject provider functions. HTTP playback routes pass
    ``require_catalog_identity=True`` and therefore cannot use that seam.
    """
    expected_titles = {
        normalize_ru_text(value)
        for value in (title, original_title)
        if normalize_ru_text(value)
    }
    expected_kind = _requested_catalog_media_type(category, season, media_type)
    requested_year = int(year or 0)
    db_file = DIR / "catalog.db"
    if not db_file.exists() or not expected_titles:
        return "UNMATCHED", None

    conn = None
    try:
        conn = sqlite3.connect(str(db_file))
        conn.row_factory = sqlite3.Row
        if catalog_media_id is not None and str(catalog_media_id).strip():
            clean_id = str(catalog_media_id).strip()
            if not clean_id.isdigit():
                return "IDENTITY_MISMATCH", None
            rows = conn.execute(
                "SELECT * FROM movies WHERE id=? LIMIT 1", (int(clean_id),)
            ).fetchall()
        else:
            # Exact SQL candidates keep this lookup bounded even on the large
            # catalog. Python then applies the punctuation/ё normalizer; this
            # is never a LIKE/prefix or first-result fallback.
            raw_titles = [str(value).strip() for value in (title, original_title) if str(value or "").strip()]
            title_clauses = []
            title_params: List[Any] = []
            for raw_title in raw_titles:
                title_clauses.extend(["title=? COLLATE NOCASE", "original_title=? COLLATE NOCASE"])
                title_params.extend([raw_title, raw_title])
            where = ["media_type=?"]
            params: List[Any] = [expected_kind]
            if requested_year > 0:
                where.append("year=?")
                params.append(requested_year)
            if title_clauses:
                where.append("(" + " OR ".join(title_clauses) + ")")
                params.extend(title_params)
            rows = conn.execute(
                "SELECT * FROM movies WHERE " + " AND ".join(where),
                tuple(params),
            ).fetchall()
    except Exception as exc:
        print(f"[DEBUG] SQLite catalog identity lookup error: {exc}")
        return "UNMATCHED", None
    finally:
        if conn is not None:
            conn.close()

    matches: List[Dict[str, Any]] = []
    for raw_row in rows:
        row = dict(raw_row)
        row_kind = _requested_catalog_media_type(
            str(row.get("category") or ""),
            None,
            row.get("media_type"),
        )
        if row_kind != expected_kind:
            continue
        row_title_values = {
            normalize_ru_text(row.get("title")),
            normalize_ru_text(row.get("original_title")),
        }
        if not expected_titles.intersection(value for value in row_title_values if value):
            continue
        row_year = int(row.get("year") or 0)
        if requested_year > 0 and row_year != requested_year:
            continue
        matches.append(row)

    if catalog_media_id is not None and str(catalog_media_id).strip():
        return ("OK", matches[0]) if len(matches) == 1 else ("IDENTITY_MISMATCH", None)
    if len(matches) == 1:
        return "OK", matches[0]
    if len(matches) > 1:
        return "AMBIGUOUS", None
    return "UNMATCHED", None


def _scope_streams_to_catalog_card(
    streams: Any,
    identity: Optional[Dict[str, Any]],
    season: Optional[int],
    episode: Optional[int],
) -> List[Dict[str, Any]]:
    clean = sanitize_streams(streams, require_source=True)
    if not identity:
        return clean
    try:
        from database import filter_streams_for_content

        content = dict(identity)
        if season is not None:
            content["season"] = season
        if episode is not None:
            content["episode"] = episode
        clean = filter_streams_for_content(clean, content)
    except Exception as exc:
        print(f"[DEBUG] Catalog stream identity filter error: {exc}")
        return []
    return bind_stream_identity(
        clean,
        catalog_media_id=identity.get("id"),
        title=identity.get("title"),
        original_title=identity.get("original_title"),
        year=identity.get("year"),
        media_type=identity.get("media_type"),
        season=season,
        episode=episode,
    )


def resolve_on_demand_streams(
    title: str,
    year: int = 2024,
    category: str = "movies",
    season: Optional[int] = None,
    episode: Optional[int] = None,
    tmdb_id: int = 0,
    force_refresh: bool = False,
    original_title: Optional[str] = None,
    catalog_media_id: Any = None,
    media_type: Optional[str] = None,
    require_catalog_identity: bool = False,
) -> List[Dict[str, Any]]:
    clean_title = str(title or "").strip()
    clean_category = str(category or "movies").strip().lower()
    normalized_title = normalize_ru_text(clean_title) or clean_title.casefold()
    identity_status, catalog_identity = _catalog_identity_for_request(
        clean_title,
        year,
        clean_category,
        season,
        original_title=original_title,
        catalog_media_id=catalog_media_id,
        media_type=media_type,
    )
    if catalog_media_id is not None and str(catalog_media_id).strip() and identity_status != "OK":
        print(f"[IDENTITY] rejected catalog_media_id={catalog_media_id} status={identity_status}")
        return []
    if require_catalog_identity and identity_status != "OK":
        print(f"[IDENTITY] rejected title={clean_title!r} year={year} status={identity_status}")
        return []
    if identity_status == "AMBIGUOUS":
        print(f"[IDENTITY] rejected ambiguous title={clean_title!r} year={year}")
        return []

    canonical_id = catalog_identity.get("id") if catalog_identity else None
    canonical_title = catalog_identity.get("title") if catalog_identity else clean_title
    canonical_original_title = (
        catalog_identity.get("original_title") if catalog_identity else original_title
    )
    canonical_year = int(catalog_identity.get("year") or year or 0) if catalog_identity else year
    canonical_media_type = (
        str(catalog_identity.get("media_type") or "").strip().casefold()
        if catalog_identity else _requested_catalog_media_type(clean_category, season, media_type)
    )
    identity_key = str(canonical_id or "unbound")
    catalog_revision = _current_catalog_revision()
    cache_key = (
        f"{STREAM_CACHE_VERSION}_r{catalog_revision}_{identity_key}_{normalized_title}_{year}_"
        f"{clean_category}_s{season}_e{episode}"
    )
    if not force_refresh:
        cached = get_cached_streams(cache_key)
        if cached:
            scoped = _scope_streams_to_catalog_card(cached, catalog_identity, season, episode)
            return rank_playback_streams(filter_streams_for_episode(scoped, season, episode))

    resolve_lock = _resolve_lock_for(cache_key)
    resolve_lock.acquire()
    try:
        if not force_refresh:
            cached = get_cached_streams(cache_key)
            if cached:
                scoped = _scope_streams_to_catalog_card(cached, catalog_identity, season, episode)
                return rank_playback_streams(filter_streams_for_episode(scoped, season, episode))

        effective_tmdb_id = tmdb_id
        if effective_tmdb_id == 0 and catalog_identity:
            try:
                effective_tmdb_id = int(catalog_identity.get("tmdb_id") or 0)
            except (TypeError, ValueError):
                effective_tmdb_id = 0
        if effective_tmdb_id == 0 and not catalog_identity:
            try:
                db_file = DIR / "catalog.db"
                if db_file.exists():
                    with sqlite3.connect(str(db_file)) as conn:
                        row = conn.execute(
                            "SELECT tmdb_id FROM movies WHERE id=? LIMIT 1",
                            (int(catalog_media_id),),
                        ).fetchone() if catalog_media_id is not None else None
                    if row and row[0]:
                        effective_tmdb_id = int(row[0])
            except Exception as exc:
                print(f"[DEBUG] SQLite tmdb_id lookup error: {exc}")

        # Independent provider branches run together; the union is required to
        # expose all real voice/quality variants.
        pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="movia-resolve")
        try:
            torrent_future = pool.submit(
                _resolve_torrent_provider,
                clean_title, year, clean_category, season, episode,
            )
            expected_titles = list(dict.fromkeys(
                value for value in (clean_title, str(original_title or "").strip())
                if value
            ))
            balancer_future = pool.submit(
                _resolve_balancer_provider,
                canonical_title,
                effective_tmdb_id,
                canonical_year,
                season,
                episode,
                list(dict.fromkeys(value for value in (
                    canonical_title, canonical_original_title
                ) if value)),
                canonical_media_type,
                force_refresh,
            )
            try:
                direct_streams = balancer_future.result(timeout=4.0)
            except Exception as exc:
                print(f"[DEBUG] Balancer query error or timeout: {exc}")
                direct_streams = []
            try:
                torrent_timeout = 0.5 if direct_streams else 3.5
                torrent_streams = torrent_future.result(timeout=torrent_timeout)
            except Exception as exc:
                print(f"Torrent resolve error or timeout: {exc}")
                torrent_streams = []
        finally:
            pool.shutdown(wait=False, cancel_futures=True)

        candidates = []
        for candidate in list(direct_streams or []) + list(torrent_streams or []):
            if not isinstance(candidate, dict):
                continue
            candidate = dict(candidate)
            if candidate.get("season") is None:
                candidate["season"] = season
            if candidate.get("episode") is None:
                candidate["episode"] = episode
            candidates.append(candidate)
        streams = enrich_stream_identity(
            _scope_streams_to_catalog_card(
                sanitize_streams(candidates, require_source=True),
                catalog_identity,
                season,
                episode,
            ),
            season,
            episode,
        )
        streams = filter_streams_for_episode(streams, season, episode)
        streams = rank_playback_streams(streams)

        has_direct_http = any(
            str(s.get("url", "")).lower().startswith(("http://", "https://"))
            for s in streams
        )
        cache_ttl_seconds = 5 * 60 if has_direct_http else 48 * 60 * 60
        set_cached_streams(cache_key, streams, ttl_seconds=cache_ttl_seconds)
        return streams
    finally:
        resolve_lock.release()


def persist_resolved_streams_to_catalog(content_id: Any, streams: List[Dict[str, Any]]) -> bool:
    """Persist only validated on-demand sources for an existing catalog row.

    Stream discovery is allowed to be lazy, but a successful discovery must not
    disappear after the request. The local numeric catalog ID is the identity;
    title text is deliberately not used for the write target.
    """
    clean_id = str(content_id or "").strip()
    if not clean_id.isdigit():
        return False
    clean_streams = sanitize_streams(streams, require_source=True)
    if not clean_streams:
        return False
    try:
        from database import save_content

        primary = clean_streams[0]
        return bool(save_content({
            "id": int(clean_id),
            "playback_url": primary.get("url", ""),
            "voice": primary.get("voice", ""),
            "quality": primary.get("quality", ""),
            "seeders": primary.get("seeders", 0),
            "streams": clean_streams,
            "link_verified": 1,
            "replace_direct_variants": True,
        }))
    except Exception as exc:
        print(f"[DEBUG] Persist resolved streams error: {exc}")
        return False

def parse_single_http_byte_range(range_header: str, total_size: int) -> Optional[tuple[int, int]]:
    """Parse one RFC 7233 byte range: start-end, start-, or -suffix."""
    if total_size <= 0 or not range_header:
        return None
    value = range_header.strip()
    if not value.lower().startswith("bytes="):
        return None
    spec = value[6:].strip()
    if not spec or "," in spec or "-" not in spec:
        return None
    left, right = spec.split("-", 1)
    try:
        if left:
            start = int(left)
            if start < 0 or start >= total_size:
                return None
            end = int(right) if right else total_size - 1
            if end < start:
                return None
            return start, min(end, total_size - 1)
        # Suffix range: bytes=-N means the final N bytes.
        suffix = int(right)
        if suffix <= 0:
            return None
        suffix = min(suffix, total_size)
        return total_size - suffix, total_size - 1
    except (TypeError, ValueError):
        return None


class StreamRequestHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Range, Content-Type, Referer, Origin")
        self.end_headers()

    def do_HEAD(self):
        try:
            self.handle_request(send_body=False)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        try:
            self.handle_request(send_body=True)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def handle_request(self, send_body=True):
        try:
            self._handle_request_internal(send_body=send_body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _handle_request_internal(self, send_body=True):
        gid = None
        raw_path = self.path
        try:
            decoded_path = raw_path.encode('iso-8859-1').decode('utf-8')
        except Exception:
            decoded_path = raw_path
        parsed = urllib.parse.urlparse(decoded_path)
        params = urllib.parse.parse_qs(parsed.query)

        if parsed.path == "/health":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if send_body:
                self.wfile.write(b"{\"status\":\"ok\",\"service\":\"movia-p2p-streamer-on-demand\",\"port\":8888,\"security\":\"isolated-localhost\"}")
            return

        if parsed.path == "/diagnostics":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if send_body:
                with _TORRENT_GIDS_LOCK:
                    active_gids = dict(_TORRENT_GIDS)
                    owned_gids = list(_TORRENT_OWNED_GIDS)
                torrent_cache_dir = DIR / "torrent_cache"
                cache_entries_count = 0
                cache_bytes = 0
                if torrent_cache_dir.exists():
                    try:
                        for entry in torrent_cache_dir.iterdir():
                            cache_entries_count += 1
                            if entry.is_file():
                                cache_bytes += entry.stat().st_size
                            elif entry.is_dir():
                                for root, _, files in os.walk(entry):
                                    for f in files:
                                        try:
                                            cache_bytes += (Path(root) / f).stat().st_size
                                        except OSError:
                                            pass
                    except Exception:
                        pass
                diag_payload = {
                    "status": "ok",
                    "service": "movia-p2p-streamer-on-demand",
                    "port": PORT,
                    "catalog_revision": _current_catalog_revision(),
                    "active_torrent_sessions": len(active_gids),
                    "torrent_sessions": active_gids,
                    "owned_gids": owned_gids,
                    "torrent_cache": {
                        "entries_count": cache_entries_count,
                        "total_bytes": cache_bytes,
                    },
                    "timestamp": datetime.now(timezone.utc).isoformat(),
                }
                self.wfile.write(json.dumps(diag_payload, ensure_ascii=False).encode("utf-8"))
            return

        if parsed.path == "/stream/test":
            test_data = b"0" * (1024 * 1024 * 10) # 10 MB test stream buffer
            total_len = len(test_data)
            start = 0
            end = total_len - 1
            is_range = False
            if "Range" in self.headers:
                parsed_range = parse_single_http_byte_range(self.headers["Range"], total_len)
                if parsed_range is None:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{total_len}")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    return
                start, end = parsed_range
                is_range = True

            if start >= total_len:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{total_len}")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                return

            if end >= total_len:
                end = total_len - 1

            chunk_len = end - start + 1
            status_code = 206 if is_range else 200
            self.send_response(status_code)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(chunk_len))
            if is_range:
                self.send_header("Content-Range", f"bytes {start}-{end}/{total_len}")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if send_body:
                self.wfile.write(test_data[start:end+1])
            return

        if parsed.path == "/api/catalog/sync-status":
            payload = live_catalog_sync.status()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            if send_body:
                self.wfile.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
            return

        if parsed.path == "/api/home":
            payload = catalog_api.get_home_payload()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if send_body:
                self.wfile.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
            return

        if parsed.path == "/api/catalog":
            limit = int(params.get("limit", [40])[0])
            offset = int(params.get("offset", [0])[0])
            sort = params.get("sort", ["POPULAR"])[0]
            category = params.get("category", [None])[0]
            genre = params.get("genre") or None
            year_from = int(params.get("yearFrom", [0])[0]) if params.get("yearFrom") else None
            year_to = int(params.get("yearTo", [0])[0]) if params.get("yearTo") else None
            min_rating = float(params.get("minRating", [0])[0]) if params.get("minRating") else None
            country = params.get("country", [None])[0]
            media_type = params.get("mediaType", [None])[0]
            query_text = params.get("query", [None])[0]
            discover = params.get("discover", ["0"])[0].strip().lower() in {
                "1", "true", "yes", "on"
            }

            payload = catalog_api.get_catalog_paged(
                limit=limit, offset=offset, sort=sort, category=category,
                genre=genre, year_from=year_from, year_to=year_to,
                min_rating=min_rating, country=country, query_text=query_text,
                media_type=media_type,
                discover=discover
            )
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if send_body:
                self.wfile.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
            return

        if parsed.path == "/api/movie/search":
            query_text = params.get("query", [""])[0] or params.get("q", [""])[0]
            limit = int(params.get("limit", [20])[0])
            discover = params.get("discover", ["1"])[0].strip().lower() in {
                "1", "true", "yes", "on"
            }
            payload = catalog_api.search_catalog(
                query_text, limit=limit, discover=discover
            )
            movies = payload.get("movies", [])
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if send_body:
                self.wfile.write(json.dumps(movies, ensure_ascii=False).encode("utf-8"))
            return

        if parsed.path == "/api/search":
            query_text = params.get("query", [""])[0] or params.get("q", [""])[0]
            limit = int(params.get("limit", [20])[0])
            discover = params.get("discover", ["1"])[0].strip().lower() in {
                "1", "true", "yes", "on"
            }
            payload = catalog_api.search_catalog(
                query_text, limit=limit, discover=discover
            )
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if send_body:
                self.wfile.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
            return

        if parsed.path == "/api/genres":
            genres = catalog_api.get_all_genres()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if send_body:
                self.wfile.write(json.dumps(genres, ensure_ascii=False).encode("utf-8"))
            return

        if parsed.path == "/api/clear_cache":
            res = catalog_api.clear_torrent_cache_dir()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if send_body:
                self.wfile.write(json.dumps(res, ensure_ascii=False).encode("utf-8"))
            return

        if parsed.path.startswith("/api/movie/"):
            parts = [p for p in parsed.path.split("/") if p]
            if len(parts) >= 3 and parts[2] != "search":
                movie_id = urllib.parse.unquote(parts[2])
                is_stream_request = len(parts) >= 4 and parts[3] == "stream"
                is_sequels_request = len(parts) >= 4 and parts[3] in ["sequels", "franchise"]

                details = catalog_api.get_movie_details(movie_id)
                if details and details.get("movie"):
                    movie_obj = details["movie"]
                    if is_sequels_request:
                        sequels_list = details.get("sequels", [])
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json; charset=utf-8")
                        self.send_header("Access-Control-Allow-Origin", "*")
                        self.end_headers()
                        if send_body:
                            self.wfile.write(json.dumps(sequels_list, ensure_ascii=False).encode("utf-8"))
                        return
                    elif not is_stream_request:
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json; charset=utf-8")
                        self.send_header("Access-Control-Allow-Origin", "*")
                        self.end_headers()
                        if send_body:
                            self.wfile.write(json.dumps(details, ensure_ascii=False).encode("utf-8"))
                        return
                    else:
                        # Catalog streams are structurally validated first,
                        # then scoped to the requested episode. A stream explicitly
                        # tagged for another episode is never exposed here.
                        season_raw = params.get("season", [None])[0]
                        episode_raw = params.get("episode", [None])[0]
                        season = int(season_raw) if season_raw and str(season_raw).isdigit() else None
                        episode = int(episode_raw) if episode_raw and str(episode_raw).isdigit() else None
                        card_identity = {
                            "id": movie_obj.get("id"),
                            "title": movie_obj.get("title"),
                            "original_title": movie_obj.get("original_title"),
                            "year": movie_obj.get("year"),
                            "media_type": movie_obj.get("mediaType") or (
                                "tv" if movie_obj.get("type") == "series" else "movie"
                            ),
                        }
                        stored_streams = _scope_streams_to_catalog_card(
                            movie_obj.get("streams", []),
                            card_identity,
                            season,
                            episode,
                        )
                        streams_list = rank_playback_streams(
                            filter_streams_for_episode(stored_streams, season, episode)
                        )
                        refresh_requested = params.get("refresh", ["0"])[0].lower() in {"1", "true", "yes"}
                        persisted_needs_refresh = catalog_streams_need_refresh(
                            movie_obj,
                            streams_list,
                        )
                        persisted_needs_variant_resolve = any(
                            not (s.get("stream_id") or s.get("streamId"))
                            for s in streams_list
                        )
                        persisted_out_of_scope = bool(stored_streams) and not streams_list
                        has_direct_playable = any(
                            str(s.get("url") or "").strip().lower().startswith(("http://", "https://"))
                            for s in streams_list
                        )
                        should_resolve = bool(
                            refresh_requested
                            or not streams_list
                            or not has_direct_playable
                            or persisted_needs_refresh
                            or persisted_needs_variant_resolve
                            or persisted_out_of_scope
                        )
                        resolution_status = "RESULTS" if streams_list else "NO_RESULTS"
                        resolution_error = None
                        if should_resolve:
                            try:
                                category = "series" if season is not None else ("series" if movie_obj.get("type") == "series" else "movies")
                                live_streams = resolve_on_demand_streams(
                                    title=movie_obj.get("title", ""),
                                    year=int(movie_obj.get("year") or 0),
                                    category=category,
                                    season=season,
                                    episode=episode,
                                    force_refresh=refresh_requested,
                                    original_title=movie_obj.get("original_title"),
                                    catalog_media_id=movie_obj.get("id"),
                                    media_type=card_identity["media_type"],
                                    require_catalog_identity=True,
                                )
                                live_streams = filter_streams_for_episode(
                                    live_streams, season, episode
                                )
                                if live_streams:
                                    streams_list = live_streams
                                    persist_resolved_streams_to_catalog(
                                        movie_obj.get("id"), live_streams
                                    )
                            except Exception as e:
                                print(f"[DEBUG] On-demand stream resolve error: {e}")
                                resolution_error = "RESOLUTION_ERROR"
                                if not streams_list:
                                    streams_list = []
                                    resolution_status = "ERROR"
                                else:
                                    resolution_status = "RESULTS"

                        if streams_list:
                            streams_list = rank_playback_streams(streams_list)
                            resolution_status = "RESULTS"
                        elif resolution_status != "ERROR":
                            resolution_status = "NO_RESULTS"

                        self.send_response(200)
                        self.send_header("Content-Type", "application/json; charset=utf-8")
                        self.send_header("Access-Control-Allow-Origin", "*")
                        self.end_headers()
                        if send_body:
                            resp_payload = {
                                "id": movie_obj.get("id"),
                                "title": movie_obj.get("title"),
                                "year": movie_obj.get("year"),
                                "playback_url": streams_list[0]["url"] if streams_list else "",
                                "top_stream": streams_list[0] if streams_list else None,
                                "streams": streams_list,
                                "status": resolution_status,
                                "errorCode": resolution_error,
                            }
                            self.wfile.write(json.dumps(resp_payload, ensure_ascii=False).encode("utf-8"))
                        return
                else:
                    self.send_response(404)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    if send_body:
                        self.wfile.write(b"{\"error\":\"movie_not_found\"}")
                    return

        if parsed.path == "/resolve":
            title = params.get("title", [""])[0]
            year_param = params.get("year", ["0"])[0]
            year = int(year_param) if year_param and year_param.isdigit() else 0
            category = params.get("category", ["movies"])[0]
            tmdb_id = int(params.get("tmdb_id", ["0"])[0] or 0)
            season_raw = params.get("season", [None])[0]
            episode_raw = params.get("episode", [None])[0]
            season = int(season_raw) if season_raw and season_raw.isdigit() else None
            episode = int(episode_raw) if episode_raw and episode_raw.isdigit() else None

            if not title:
                self.send_response(400)
                self.end_headers()
                if send_body:
                    self.wfile.write(b"{\"error\":\"missing title\"}")
                return
            
            print(f"[DEBUG] Resolving title={title}, year={year}")
            refresh_requested = params.get("refresh", ["0"])[0].lower() in {"1", "true", "yes"}
            resolve_status = "RESULTS"
            resolve_error = None
            identity_status, identity = _catalog_identity_for_request(
                title,
                year,
                category,
                season,
            )
            if identity_status != "OK" or not identity:
                streams = []
                resolve_status = identity_status if identity_status in {"AMBIGUOUS", "UNMATCHED"} else "NO_RESULTS"
                resolve_error = "EXACT_CATALOG_IDENTITY_REQUIRED"
            else:
                try:
                    streams = resolve_on_demand_streams(
                        title=identity.get("title") or title,
                        year=int(identity.get("year") or year or 0),
                        category=category,
                        season=season,
                        episode=episode,
                        tmdb_id=tmdb_id,
                        force_refresh=refresh_requested,
                        original_title=identity.get("original_title"),
                        catalog_media_id=identity.get("id"),
                        media_type=identity.get("media_type"),
                        require_catalog_identity=True,
                    )
                except Exception as exc:
                    streams = []
                    resolve_status = "ERROR"
                    resolve_error = "RESOLUTION_ERROR"
                    print(f"[DEBUG] On-demand resolve error: {exc}")
            if not streams and resolve_status != "ERROR":
                resolve_status = "NO_RESULTS"
            print(f"[DEBUG] Total streams found: {len(streams)}")
            for s in streams:
                print(f"[DEBUG] Stream: {s.get('source')} | {str(s.get('url', ''))[:80]}")
            
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if send_body:
                resp_payload = {
                    "title": title,
                    "year": year,
                    "season": season,
                    "episode": episode,
                    "top_stream": streams[0] if streams else None,
                    "streams": streams,
                    "status": resolve_status,
                    "errorCode": resolve_error,
                }
                self.wfile.write(json.dumps(resp_payload, ensure_ascii=False).encode("utf-8"))
            return

        if parsed.path == "/stream":
            raw_magnet = params.get("magnet", [None])[0]
            raw_url = params.get("url", [None])[0]

            # 1. P2P Torrent stream via aria2 RPC
            if raw_magnet and raw_magnet.startswith("magnet:"):
                sanitized_magnet = sanitize_magnet_uri(raw_magnet)
                if not sanitized_magnet:
                    self.send_response(400)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    if send_body:
                        self.wfile.write(b'{"error":"invalid_magnet_btih"}')
                    return

                # Extract info_hash for directory isolation
                hash_match = re.search(r"xt=urn:btih:([a-zA-Z0-9]+)", sanitized_magnet, re.IGNORECASE)
                info_hash = hash_match.group(1).lower() if hash_match else hashlib.md5(sanitized_magnet.encode()).hexdigest()[:20]
                
                torrent_cache_dir = DIR / "torrent_cache"
                task_dir = torrent_cache_dir / info_hash
                try:
                    task_dir.mkdir(parents=True, exist_ok=True)
                    _touch_torrent_playback_lease(task_dir)
                except OSError as exc:
                    print(f"[DEBUG] Torrent cache storage error: {exc}")
                    status_code = 507 if getattr(exc, "errno", None) == 28 else 503
                    self.send_response(status_code)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    if send_body:
                        self.wfile.write(json.dumps({
                            "error": "storage_unavailable",
                            "status": "ERROR",
                        }, ensure_ascii=False).encode("utf-8"))
                    return
                gid = None
                requested_season_raw = params.get("season", [None])[0]
                requested_episode_raw = params.get("episode", [None])[0]
                requested_file_index_raw = params.get(
                    "file_index",
                    params.get("fileIndex", [None]),
                )[0]
                requested_season = int(requested_season_raw) if str(requested_season_raw or "").isdigit() else None
                requested_episode = int(requested_episode_raw) if str(requested_episode_raw or "").isdigit() else None
                requested_file_index = (
                    int(requested_file_index_raw)
                    if str(requested_file_index_raw or "").isdigit()
                    else None
                )
                # A fully allocated requested episode is authoritative even when a stale
                # .aria2 control file remains in this task directory. Sparse placeholders
                # are deliberately rejected by find_completed_cached_video().
                completed_cached_video = find_completed_cached_video(
                    task_dir,
                    season=requested_season,
                    episode=requested_episode,
                    file_index=requested_file_index,
                )

                if completed_cached_video is not None:
                    print(f"[DEBUG] Reusing completed torrent cache: {completed_cached_video}")
                else:
                    try:
                        gid = get_or_create_torrent_gid(info_hash, sanitized_magnet, task_dir)
                        print(f"[DEBUG] aria2 torrent GID: {gid} in {task_dir}")
                    except Exception as e:
                        print(f"[DEBUG] aria2 RPC error: {e}")

                # Wait for video file to appear inside task_dir / begin downloading
                file_path = completed_cached_video
                target_file_path = completed_cached_video
                file_total_length = 0
                file_selected = completed_cached_video is not None
                episode_selection_failed = False
                file_index_selection_failed = False

                for loop_i in range(60):
                    # Check aria2 getFiles to auto-select target episode if multi-file.
                    # A magnet initially exposes only a metadata task. Do not remove
                    # it: aria2 later links the materialized torrent through followedBy.
                    if gid and not file_selected:
                        try:
                            torrent_files = aria2_rpc("aria2.getFiles", [gid], timeout=5.0) or []
                            if _torrent_files_have_media(torrent_files) is False:
                                materialized_gid = _followed_torrent_media_gid(info_hash, gid)
                                if materialized_gid:
                                    if materialized_gid != gid:
                                        print(
                                            f"[DEBUG] Torrent metadata GID {gid} -> "
                                            f"media GID {materialized_gid}"
                                        )
                                    gid = materialized_gid
                                    target_file_path = None
                                    file_selected = False
                                    continue
                                # Metadata is still in flight. Reuse this task instead
                                # of spawning another magnet and give aria2 time to emit
                                # followedBy before the next bounded poll.
                                time.sleep(0.25)
                                continue
                            if requested_file_index is not None:
                                selected_file = _torrent_file_by_index(
                                    torrent_files,
                                    requested_file_index,
                                )
                                if selected_file is None:
                                    file_index_selection_failed = True
                                    print(
                                        f"[DEBUG] Requested torrent file index "
                                        f"{requested_file_index} is absent or not a video"
                                    )
                                else:
                                    best_idx = str(selected_file.get("index"))
                                    target_file_path = Path(
                                        str(selected_file.get("path") or "")
                                    )
                                    if len(torrent_files) > 1:
                                        aria2_rpc(
                                            "aria2.changeOption",
                                            [gid, {"select-file": best_idx}],
                                            timeout=2.0,
                                        )
                                    file_selected = True
                            elif len(torrent_files) > 1:
                                # Target the exact requested episode. A season pack
                                # without an SxxEyy match is not a playable answer:
                                # selecting its largest file would play the wrong
                                # episode and violates the card identity contract.
                                req_s = requested_season
                                req_e = requested_episode
                                best_idx = None
                                best_len = 0
                                for tf in torrent_files:
                                    p = tf.get("path", "").lower()
                                    l = int(tf.get("length", 0))
                                    tf_index = str(tf.get("index") or "")
                                    if p.endswith((".mp4", ".mkv", ".avi", ".ts", ".m4v")):
                                        if req_s is not None and req_e is not None and episode_path_matches(p, req_s, req_e):
                                            best_idx = tf_index
                                            best_len = l
                                            target_file_path = Path(tf.get("path", ""))
                                            break

                                if not best_idx and req_s is not None and req_e is not None:
                                    episode_selection_failed = True
                                    print(
                                        f"[DEBUG] Requested episode S{req_s:02d}E{req_e:02d} "
                                        "is absent from torrent metadata"
                                    )
                                elif not best_idx:
                                    # Movie torrents may contain samples/extras;
                                    # largest-file selection is allowed only when
                                    # no exact series episode was requested.
                                    for tf in torrent_files:
                                        p = tf.get("path", "").lower()
                                        l = int(tf.get("length", 0))
                                        if p.endswith((".mp4", ".mkv", ".avi", ".ts", ".m4v")) and l > best_len:
                                            best_len = l
                                            best_idx = str(tf.get("index") or "")
                                            target_file_path = Path(tf.get("path", ""))
                                
                                if best_idx:
                                    aria2_rpc("aria2.changeOption", [gid, {"select-file": str(best_idx)}], timeout=2.0)
                                    file_selected = True
                                    print(f"[DEBUG] Multi-file season pack: focused download on file #{best_idx} ({best_len / (1024*1024):.1f} MB)")
                        except Exception as e:
                            print(f"[DEBUG] Episode file selection error: {e}")

                    # Only expose a file after actual bytes exist. For season packs,
                    # wait specifically for the requested episode rather than the
                    # first zero-length placeholder created by aria2.
                    if file_index_selection_failed or episode_selection_failed:
                        break

                    if target_file_path is not None:
                        if (
                            (
                                requested_file_index is not None
                                or requested_season is None
                                or requested_episode is None
                                or episode_path_matches(target_file_path, requested_season, requested_episode)
                            )
                            and has_playable_container_head(target_file_path)
                        ):
                            file_path = target_file_path
                            break
                    else:
                        for root, dirs, files in os.walk(str(task_dir)):
                            for f in files:
                                if (
                                    f.lower().endswith((".mp4", ".mkv", ".avi", ".ts", ".m4v"))
                                    and not f.endswith(".aria2")
                                    and (requested_season is None or requested_episode is None or
                                         episode_path_matches(f, requested_season, requested_episode))
                                ):
                                    candidate_path = Path(root) / f
                                    if has_playable_container_head(candidate_path):
                                        file_path = candidate_path
                                        break
                            if file_path and has_playable_container_head(file_path):
                                break
                        if file_path and has_playable_container_head(file_path):
                            break

                    # Do not silently switch to a different torrent here. The Android
                    # client already owns ordered mirror failover and preserves voice/quality intent.
                    time.sleep(0.5)

                if file_index_selection_failed:
                    release_torrent_gid(info_hash, gid, remove_task=True)
                    self.send_response(404)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    if send_body:
                        self.wfile.write(json.dumps({
                            "error": "requested_file_index_not_found",
                            "file_index": requested_file_index,
                        }, ensure_ascii=False).encode("utf-8"))
                    return

                if episode_selection_failed:
                    release_torrent_gid(info_hash, gid, remove_task=True)
                    self.send_response(404)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    if send_body:
                        self.wfile.write(json.dumps({
                            "error": "requested_episode_not_found",
                            "season": requested_season,
                            "episode": requested_episode,
                        }, ensure_ascii=False).encode("utf-8"))
                    return

                if not file_path or not has_playable_container_head(file_path):
                    release_torrent_gid(info_hash, gid, remove_task=True)
                    self.send_response(404)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    if send_body:
                        self.wfile.write(b'{"error":"torrent_stream_buffering_timeout"}')
                    return

                # Default to raw/original container. On-the-fly MP4 remuxing only if explicitly requested.
                requested_format = params.get("format", [""])[0].lower()
                if requested_format == "mp4" and not str(file_path).lower().endswith((".mp4", ".m4v")):
                    try:
                        self.send_response(200)
                        self.send_header("Content-Type", "video/mp4")
                        self.send_header("Accept-Ranges", "none")
                        self.send_header("Access-Control-Allow-Origin", "*")
                        self.end_headers()

                        if send_body:
                            cmd = [
                                "ffmpeg",
                                "-i", str(file_path),
                                "-c:v", "copy",
                                "-c:a", "aac",
                                "-movflags", "frag_keyframe+empty_moov+default_base_moof",
                                "-f", "mp4",
                                "pipe:1"
                            ]
                            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=64 * 1024)
                            try:
                                while True:
                                    chunk = proc.stdout.read(64 * 1024)
                                    if not chunk:
                                        if proc.poll() is not None:
                                            break
                                        time.sleep(0.1)
                                        continue
                                    self.wfile.write(chunk)
                                    self.wfile.flush()
                            except (BrokenPipeError, ConnectionResetError):
                                pass
                            finally:
                                try:
                                    proc.kill()
                                except Exception:
                                    pass
                        return
                    except Exception as e:
                        print(f"[DEBUG] ffmpeg remux error: {e}")

                try:
                    _touch_torrent_playback_lease(task_dir)
                    # Determine true total length of video file from aria2 metadata
                    total_file_len = file_path.stat().st_size
                    try:
                        for tf in (aria2_rpc("aria2.getFiles", [gid], timeout=1.5) or []):
                            if tf.get("path") == str(file_path):
                                total_file_len = int(tf.get("length", total_file_len))
                                break
                    except Exception:
                        pass

                    curr_disk_size = file_path.stat().st_size
                    # aria2 reports the logical file length even while its sparse
                    # file is still incomplete. Never invent a minimum length:
                    # Content-Length and Content-Range must describe this file exactly.
                    file_size = max(total_file_len, curr_disk_size)
                    if file_size <= 0:
                        raise ValueError("empty_stream_file")

                    start = 0
                    end = file_size - 1
                    is_range = False

                    if "Range" in self.headers:
                        parsed_range = parse_single_http_byte_range(self.headers["Range"], file_size)
                        if parsed_range is None:
                            self.send_response(416)
                            self.send_header("Content-Range", f"bytes */{file_size}")
                            self.send_header("Access-Control-Allow-Origin", "*")
                            self.end_headers()
                            return
                        start, end = parsed_range
                        is_range = True
                        print(f"[DEBUG] HTTP Range {self.headers['Range']} -> {start}-{end}/{file_size} file={file_path.name}")

                    # 1. Reject invalid ranges beyond total file size
                    if start >= file_size:
                        self.send_response(416)
                        self.send_header("Content-Range", f"bytes */{file_size}")
                        self.send_header("Access-Control-Allow-Origin", "*")
                        self.end_headers()
                        return

                    # Sparse aria2 files have their final logical st_size immediately,
                    # so stat() cannot tell whether the requested bytes are downloaded.
                    # Consult aria2's piece bitfield before exposing a Range response.
                    probe_len = min(2 * 1024 * 1024, max(1, end - start + 1))
                    if gid and not _prioritize_and_wait_torrent_range(gid, file_path, start, probe_len, timeout_sec=12.0):
                        print(f"[DEBUG] Range waiting timeout: start={start} len={probe_len} file={file_path.name}")
                        self.send_response(503)
                        self.send_header("Retry-After", "1")
                        self.send_header("Content-Type", "application/json; charset=utf-8")
                        self.send_header("Access-Control-Allow-Origin", "*")
                        self.end_headers()
                        if send_body:
                            self.wfile.write(b'{"error":"torrent_range_not_ready"}')
                        return

                    # Adjust end if exceeding total size
                    if end >= file_size:
                        end = file_size - 1

                    content_length = end - start + 1

                    # For short requests, wait for the complete requested range
                    # before sending headers. For larger requests, only the initial
                    # probe is gated here; each subsequent chunk is gated below.
                    if gid and content_length <= 16 * 1024 * 1024:
                        if not _prioritize_and_wait_torrent_range(
                            gid, file_path, start, content_length, timeout_sec=30.0
                        ):
                            print(
                                f"[DEBUG] Range not ready before headers: "
                                f"start={start} len={content_length} file={file_path.name}"
                            )
                            self.send_response(503)
                            self.send_header("Retry-After", "1")
                            self.send_header("Content-Type", "application/json; charset=utf-8")
                            self.send_header("Access-Control-Allow-Origin", "*")
                            self.end_headers()
                            if send_body:
                                self.wfile.write(b'{"error":"torrent_range_not_ready"}')
                            return

                    status_code = 206 if is_range else 200
                    ct = infer_stream_mime(str(file_path))

                    self.send_response(status_code)
                    self.send_header("Content-Type", ct)
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Content-Length", str(content_length))
                    if is_range:
                        self.send_header("Content-Range", f"bytes {start}-{end}/{file_size}")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()

                    if send_body:
                        with open(str(file_path), "rb") as f:
                            f.seek(start)
                            remaining = content_length
                            current_offset = start
                            chunk_size = 1024 * 1024
                            while remaining > 0:
                                wanted = min(chunk_size, remaining)
                                if gid and not _prioritize_and_wait_torrent_range(
                                    gid, file_path, current_offset, wanted, timeout_sec=15.0
                                ):
                                    raise TimeoutError(
                                        "torrent_range_not_ready at byte=" +
                                        str(current_offset)
                                    )
                                chunk = f.read(wanted)
                                if not chunk:
                                    raise EOFError(
                                        "short_range_eof at byte=" + str(current_offset)
                                    )
                                if len(chunk) > wanted:
                                    chunk = chunk[:wanted]
                                self.wfile.write(chunk)
                                self.wfile.flush()
                                remaining -= len(chunk)
                                current_offset += len(chunk)
                    return
                except (BrokenPipeError, ConnectionResetError):
                    return
                except Exception as e:
                    print(f"[DEBUG] Error streaming file {file_path}: {e}")
                    return

            # 2. Direct HTTP/HTTPS proxying
            target_stream_url = raw_url
            if not target_stream_url or not is_safe_proxy_url(target_stream_url):
                self.send_response(404)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                if send_body:
                    self.wfile.write(b'{"error":"stream_not_found_or_unsafe"}')
                return

            try:
                req = urllib.request.Request(target_stream_url, headers={
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer": "https://kodik.info/",
                    "Origin": "https://kodik.info",
                    "Accept": "*/*"
                })
                range_requested = "Range" in self.headers
                if range_requested:
                    req.add_header("Range", self.headers["Range"])

                with urllib.request.urlopen(req, timeout=10.0) as response:
                    raw_ct = response.headers.get("Content-Type", "").lower()
                    status_code = response.status

                    # A Range request must remain a Range response. Forwarding
                    # an upstream 200 for a requested offset causes wrong bytes
                    # to be decoded after seek.
                    if range_requested and status_code != 206:
                        raise ValueError(
                            "upstream_range_not_honored status=" + str(status_code)
                        )
                    if range_requested and not response.headers.get("Content-Length"):
                        raise ValueError("upstream_range_without_content_length")

                    # If upstream returned HTML (blocking/captcha page), abort and return 502
                    if "text/html" in raw_ct or status_code >= 400:
                        raise ValueError(f"Upstream returned HTML ({raw_ct}) or error status ({status_code})")

                    resolved_ct = infer_stream_mime(target_stream_url, raw_ct)
                    self.send_response(status_code)
                    for header, value in response.headers.items():
                        hl = header.lower()
                        if hl == "content-type":
                            self.send_header("Content-Type", resolved_ct)
                        elif hl in ["content-length", "content-range", "accept-ranges", "last-modified", "etag"]:
                            self.send_header(header, value)
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.send_header("Accept-Ranges", "bytes")
                    self.end_headers()

                    if send_body:
                        # Initial 2MB sequential piece buffering for instant ExoPlayer playback
                        initial_buffer_target = 2 * 1024 * 1024
                        buffered_data = bytearray()
                        while len(buffered_data) < initial_buffer_target:
                            chunk = response.read(128 * 1024)
                            if not chunk:
                                break
                            buffered_data.extend(chunk)

                        if buffered_data:
                            self.wfile.write(buffered_data)
                            try:
                                self.wfile.flush()
                            except Exception:
                                pass

                        # Continuous streaming of subsequent chunks
                        while True:
                            chunk = response.read(128 * 1024)
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            try:
                                self.wfile.flush()
                            except Exception:
                                pass
            except Exception as e:
                self.send_response(502)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                if send_body:
                    err_payload = json.dumps({"error": "upstream_proxy_failed", "detail": str(e)}, ensure_ascii=False).encode("utf-8")
                    self.wfile.write(err_payload)
                return

        self.send_response(404)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        if send_body:
            self.wfile.write(b'{"error":"not_found"}')

def is_streamer_running() -> bool:
    try:
        req = urllib.request.Request(f"http://127.0.0.1:{PORT}/health")
        with urllib.request.urlopen(req, timeout=1) as resp:
            return resp.status == 200
    except Exception:
        return False

def run_server():
    live_catalog_sync.start_background_sync(interval_seconds=300)
    try:
        print("⚡ Pre-warming catalog home cache...")
        catalog_api.get_home_payload(force_refresh=True)
        print("✅ Home cache pre-warmed successfully (<20ms response ready).")
    except Exception as e:
        print(f"⚠️ Cache pre-warm warning: {e}")

    server = ThreadedHTTPServer((HOST, PORT), StreamRequestHandler)
    print(f"🛡️ Movia Secure Streamer & On-Demand Gateway запущен на http://{HOST}:{PORT} (PID: {os.getpid()})")
    with open(PID_FILE, "w") as f:
        f.write(str(os.getpid()))

    def shutdown_handler(signum, frame):
        print("Stopping streamer server...")
        try:
            if PID_FILE.exists():
                PID_FILE.unlink()
        except OSError:
            pass
        sys.exit(0)

    if hasattr(signal, "SIGHUP"):
        signal.signal(signal.SIGHUP, signal.SIG_IGN)
    signal.signal(signal.SIGTERM, shutdown_handler)
    signal.signal(signal.SIGINT, shutdown_handler)

    try:
        server.serve_forever()
    finally:
        server.server_close()
        if PID_FILE.exists():
            try:
                PID_FILE.unlink()
            except OSError:
                pass

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--check":
        if is_streamer_running():
            print("RUNNING")
            sys.exit(0)
        else:
            print("STOPPED")
            sys.exit(1)
    run_server()
