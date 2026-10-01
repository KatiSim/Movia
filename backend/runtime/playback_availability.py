"""Playback source truth and availability index for Movia.

The module deliberately separates provider claims from verified media facts.
It never proxies media and never persists raw playback URLs, query tokens or
HTTP headers. HLS manifests are inspected with a bounded request and only
non-secret facts (renditions/audio inventory) enter the persistent index.
"""
from __future__ import annotations
from contextlib import closing

import csv
import hashlib
import ipaddress
import json
import math
import re
import socket
import sqlite3
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional


_UNKNOWN_QUALITY = {"", "auto", "any", "не указано", "unknown", "n/a"}
_P2P_TRANSPORTS = {"torrent", "p2p", "torrent_p2p", "magnet"}
_SAFE_REQUEST_HEADERS = {"accept", "accept-language", "origin", "referer", "user-agent"}
_MAX_MANIFEST_BYTES = 256 * 1024


def _num(value: Any, default: float = 0.0) -> float:
    try:
        parsed = float(value)
        return parsed if math.isfinite(parsed) else default
    except (TypeError, ValueError, OverflowError):
        return default


def _text(value: Any) -> str:
    return str(value or "").strip()


def _locator_hash(value: Any) -> str:
    return hashlib.sha256(_text(value).encode("utf-8")).hexdigest()


def _quality_height(value: Any) -> int:
    text = _text(value).casefold()
    if not text:
        return 0
    if "2160" in text or "4k" in text or "uhd" in text:
        return 2160
    if "1440" in text or "2k" in text:
        return 1440
    for height in (1080, 720, 576, 480, 360, 240, 144):
        if re.search(rf"(?<!\d){height}(?:p)?(?!\d)", text):
            return height
    if text == "hd":
        return 720
    if text == "sd":
        return 480
    return 0


def _quality_label(height: int) -> str:
    return f"{int(height)}p" if int(height) > 0 else ""


def _unique(items: Iterable[str]) -> List[str]:
    result: List[str] = []
    seen: set[str] = set()
    for raw in items:
        value = _text(raw)
        key = value.casefold()
        if value and key not in seen:
            seen.add(key)
            result.append(value)
    return result


def _sorted_qualities(values: Iterable[Any]) -> List[str]:
    by_height: Dict[int, str] = {}
    extras: List[str] = []
    seen_extra: set[str] = set()
    for raw in values:
        value = _text(raw)
        if not value or value.casefold() in _UNKNOWN_QUALITY:
            continue
        height = _quality_height(value)
        if height > 0:
            by_height[height] = _quality_label(height)
        elif value.casefold() not in seen_extra:
            seen_extra.add(value.casefold())
            extras.append(value)
    return [by_height[h] for h in sorted(by_height, reverse=True)] + extras


def media_key(media_id: Any, season: Optional[int] = None, episode: Optional[int] = None) -> str:
    media = _text(media_id)
    if not media:
        raise ValueError("media_id is required")
    if (season is None) != (episode is None):
        raise ValueError("season and episode must be supplied together")
    if season is None:
        return media
    season_i = int(season)
    episode_i = int(episode)
    if season_i <= 0 or episode_i <= 0:
        raise ValueError("season and episode must be positive")
    return f"{media}:s{season_i}e{episode_i}"


def is_hls_stream(candidate: Dict[str, Any]) -> bool:
    if not isinstance(candidate, dict):
        return False
    transport = _text(candidate.get("transport")).casefold()
    if transport == "hls":
        return True
    url = _text(candidate.get("url") or candidate.get("playback_url"))
    try:
        path = urllib.parse.urlsplit(url).path.casefold()
    except Exception:
        return False
    return path.endswith(".m3u8") or ".m3u8/" in path


def _parse_attribute_list(raw: str) -> Dict[str, str]:
    """Parse an HLS attribute list while preserving commas inside quotes."""
    fields: List[str] = []
    current: List[str] = []
    quoted = False
    escaped = False
    for char in raw:
        if escaped:
            current.append(char)
            escaped = False
            continue
        if char == "\\" and quoted:
            current.append(char)
            escaped = True
            continue
        if char == '"':
            quoted = not quoted
            current.append(char)
            continue
        if char == "," and not quoted:
            fields.append("".join(current).strip())
            current = []
            continue
        current.append(char)
    if current:
        fields.append("".join(current).strip())

    result: Dict[str, str] = {}
    for field in fields:
        if "=" not in field:
            continue
        key, value = field.split("=", 1)
        result[key.strip().upper()] = value.strip().strip('"')
    return result


def inspect_hls_manifest_text(text: Any) -> Dict[str, Any]:
    body = str(text or "")
    lines = [line.strip() for line in body.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    is_hls = any(line == "#EXTM3U" for line in lines)
    variant_rows: List[Dict[str, Any]] = []
    audio_tracks: List[Dict[str, Any]] = []

    for line in lines:
        if line.startswith("#EXT-X-STREAM-INF:"):
            attrs = _parse_attribute_list(line.split(":", 1)[1])
            width = height = 0
            resolution = attrs.get("RESOLUTION", "")
            match = re.fullmatch(r"\s*(\d+)x(\d+)\s*", resolution, re.IGNORECASE)
            if match:
                width, height = int(match.group(1)), int(match.group(2))
            variant_rows.append({
                "width": width,
                "height": height,
                "bandwidth": int(_num(attrs.get("BANDWIDTH"), 0)),
                "codecs": _text(attrs.get("CODECS")),
                "audioGroup": _text(attrs.get("AUDIO")),
            })
        elif line.startswith("#EXT-X-MEDIA:"):
            attrs = _parse_attribute_list(line.split(":", 1)[1])
            if _text(attrs.get("TYPE")).upper() != "AUDIO":
                continue
            name = _text(attrs.get("NAME"))
            language = _text(attrs.get("LANGUAGE"))
            track = {
                "name": name or language or "Audio",
                "language": language,
                "groupId": _text(attrs.get("GROUP-ID")),
                "default": _text(attrs.get("DEFAULT")).upper() == "YES",
                "autoselect": _text(attrs.get("AUTOSELECT")).upper() == "YES",
            }
            # Many providers expose a byte-identical failover audio group.
            # For availability truth we need logical tracks, not duplicate CDN
            # failover groups, so de-duplicate by factual name+language.
            identity = (track["name"].casefold(), track["language"].casefold())
            if identity not in {
                (x["name"].casefold(), x["language"].casefold()) for x in audio_tracks
            }:
                audio_tracks.append(track)

    qualities = _sorted_qualities(_quality_label(row["height"]) for row in variant_rows if row["height"] > 0)
    kind = "master" if variant_rows or audio_tracks else ("media" if is_hls else "unknown")
    codecs = _unique(
        codec.strip()
        for row in variant_rows
        for codec in _text(row.get("codecs")).split(",")
        if codec.strip()
    )
    return {
        "kind": kind,
        "variantCount": len(variant_rows),
        "qualities": qualities,
        "audioTracks": audio_tracks,
        "codecs": codecs,
    }


def _safe_public_http_url(url: str, *, resolve_dns: bool = True) -> bool:
    try:
        parsed = urllib.parse.urlsplit(url)
    except Exception:
        return False
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return False
    if parsed.username or parsed.password:
        return False
    host = parsed.hostname.casefold().rstrip(".")
    if host in {"localhost", "localhost.localdomain"} or host.endswith(".localhost"):
        return False

    addresses: List[str] = []
    try:
        addresses.append(str(ipaddress.ip_address(host)))
    except ValueError:
        if resolve_dns:
            try:
                addresses.extend({row[4][0] for row in socket.getaddrinfo(host, parsed.port or 443, type=socket.SOCK_STREAM)})
            except OSError:
                return False
    for value in addresses:
        try:
            ip = ipaddress.ip_address(value)
        except ValueError:
            return False
        if not ip.is_global:
            return False
    return True


def _probe_headers(candidate: Dict[str, Any]) -> Dict[str, str]:
    raw = candidate.get("headers") or candidate.get("http_headers") or {}
    if not isinstance(raw, dict):
        return {}
    result: Dict[str, str] = {}
    for key, value in raw.items():
        name = _text(key)
        text = _text(value)
        if name.casefold() not in _SAFE_REQUEST_HEADERS or not text:
            continue
        if "\r" in text or "\n" in text or len(text) > 2048:
            continue
        result[name] = text
    return result


def probe_hls_manifest(
    candidate: Dict[str, Any],
    *,
    timeout_seconds: float = 2.0,
    max_bytes: int = _MAX_MANIFEST_BYTES,
    opener=urllib.request.urlopen,
) -> Optional[Dict[str, Any]]:
    """Fetch a bounded public HLS manifest and return non-secret media facts."""
    if not is_hls_stream(candidate):
        return None
    url = _text(candidate.get("url") or candidate.get("playback_url"))
    if not _safe_public_http_url(url):
        return None
    request = urllib.request.Request(url, headers=_probe_headers(candidate), method="GET")
    try:
        with opener(request, timeout=max(0.1, min(float(timeout_seconds), 10.0))) as response:
            status = int(getattr(response, "status", 200) or 200)
            if status < 200 or status >= 300:
                return None
            final_url = _text(response.geturl() if hasattr(response, "geturl") else url) or url
            if not _safe_public_http_url(final_url):
                return None
            payload = response.read(max(1024, min(int(max_bytes), _MAX_MANIFEST_BYTES)) + 1)
            if len(payload) > max(1024, min(int(max_bytes), _MAX_MANIFEST_BYTES)):
                return None
    except Exception:
        return None
    try:
        text = payload.decode("utf-8-sig", errors="replace")
    except Exception:
        return None
    facts = inspect_hls_manifest_text(text)
    if facts.get("kind") == "unknown":
        return None
    facts["httpStatus"] = status
    facts["verifiedAt"] = time.time()
    return facts


def apply_hls_manifest_truth(
    candidate: Dict[str, Any],
    facts: Optional[Dict[str, Any]],
    *,
    verified_at: Optional[float] = None,
) -> Dict[str, Any]:
    result = dict(candidate or {})
    if not facts or not is_hls_stream(result):
        return result
    metadata_raw = result.get("transport_metadata") or result.get("transportMetadata") or {}
    metadata = dict(metadata_raw) if isinstance(metadata_raw, dict) else {}
    reported_quality = _text(result.get("quality"))
    if reported_quality.casefold() not in _UNKNOWN_QUALITY:
        metadata.setdefault("provider_reported_quality", reported_quality)

    qualities = _sorted_qualities(facts.get("qualities") or [])
    audio_tracks = []
    for raw in facts.get("audioTracks") or []:
        if not isinstance(raw, dict):
            continue
        audio_tracks.append({
            "name": _text(raw.get("name")) or "Audio",
            "language": _text(raw.get("language")),
            "groupId": _text(raw.get("groupId")),
            "default": bool(raw.get("default")),
            "autoselect": bool(raw.get("autoselect")),
        })

    metadata["manifest_verified"] = True
    metadata["adaptive_manifest_unverified"] = False
    metadata["manifest_kind"] = _text(facts.get("kind")) or "unknown"
    metadata["available_qualities"] = qualities
    metadata["variant_count"] = int(_num(facts.get("variantCount"), 0))
    if audio_tracks:
        metadata["audio_tracks"] = audio_tracks
        metadata["audio_track_count"] = len(audio_tracks)
        raw_audio_index = result.get("audio_track_index", result.get("audioTrackIndex"))
        try:
            audio_index = int(raw_audio_index) if raw_audio_index is not None else None
        except (TypeError, ValueError, OverflowError):
            audio_index = None
        if audio_index is not None:
            metadata["audio_track_verified"] = 0 <= audio_index < len(audio_tracks)
            if 0 <= audio_index < len(audio_tracks):
                selected_audio = audio_tracks[audio_index]
                metadata["manifest_audio_track"] = selected_audio
                language = _text(selected_audio.get("language"))
                if language:
                    result["language"] = language
    codecs = _unique(facts.get("codecs") or [])
    if codecs:
        metadata["manifest_codecs"] = codecs
    verified = _num(verified_at if verified_at is not None else facts.get("verifiedAt"), time.time())
    metadata["manifest_verified_at"] = verified

    # A master playlist is an adaptive container, not one fixed quality. The
    # actual rendition is selected by Media3 and exposed through track facts.
    # A media playlist without dimensions also cannot prove a fixed quality.
    if result.get("video_track_index") is None and result.get("videoTrackIndex") is None:
        result["quality"] = "Auto"
    result["transport_metadata"] = metadata
    result.pop("transportMetadata", None)
    return result


def _is_p2p(candidate: Dict[str, Any]) -> bool:
    transport = _text(candidate.get("transport")).casefold()
    url = _text(candidate.get("url")).casefold()
    return transport in _P2P_TRANSPORTS or url.startswith("magnet:")


def _is_direct(candidate: Dict[str, Any]) -> bool:
    if _is_p2p(candidate):
        return False
    url = _text(candidate.get("url")).casefold()
    return url.startswith("http://") or url.startswith("https://")


def _manifest_metadata(candidate: Dict[str, Any]) -> Dict[str, Any]:
    value = candidate.get("transport_metadata") or candidate.get("transportMetadata") or {}
    return dict(value) if isinstance(value, dict) else {}


def summarize_streams(streams: Iterable[Dict[str, Any]]) -> Dict[str, Any]:
    rows = [dict(item) for item in streams if isinstance(item, dict)]
    direct = [item for item in rows if _is_direct(item)]
    p2p = [item for item in rows if _is_p2p(item)]
    verified_direct = [item for item in direct if bool(_manifest_metadata(item).get("manifest_verified"))]

    quality_values: List[Any] = []
    voices: List[str] = []
    sources: List[str] = []
    for item in rows:
        quality_values.append(item.get("quality"))
        voice = _text(item.get("voice") or item.get("translation"))
        if voice and voice.casefold() not in {"не указано", "unknown", "n/a"}:
            voices.append(voice)
        source = _text(item.get("source") or item.get("provider"))
        if source:
            sources.append(source)
        metadata = _manifest_metadata(item)
        quality_values.extend(metadata.get("available_qualities") or [])
        for audio in metadata.get("audio_tracks") or []:
            if isinstance(audio, dict):
                voices.append(_text(audio.get("name")))

    if verified_direct:
        status = "VERIFIED_DIRECT"
    elif direct:
        status = "DIRECT_DISCOVERED"
    elif p2p:
        status = "P2P_DISCOVERED"
    else:
        status = "NO_SOURCE"
    return {
        "available": bool(rows),
        "status": status,
        "streamCount": len(rows),
        "directCount": len(direct),
        "p2pCount": len(p2p),
        "verifiedDirectCount": len(verified_direct),
        "qualities": _sorted_qualities(quality_values),
        "voices": _unique(voices),
        "sources": _unique(sources),
    }


def score_stream(candidate: Dict[str, Any]) -> float:
    """Compatibility score retained for callers; facts outrank claims."""
    score = 0.0
    if candidate.get("source"):
        score += 15
    if candidate.get("url"):
        score += 15
    if _is_direct(candidate):
        score += 10
    if bool(_manifest_metadata(candidate).get("manifest_verified")):
        score += 25
    startup = _num(candidate.get("startup_latency_ms"), 10000)
    score += max(0.0, min(15.0, 15.0 - startup / 700.0))
    health = _num(candidate.get("health_score"), 0.5)
    score += max(0.0, min(20.0, health * 20.0))
    return max(0.0, min(100.0, score))


def rank_available_streams(streams: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    ranked = []
    for item in streams:
        row = dict(item)
        row["availability_score"] = score_stream(row)
        ranked.append(row)
    return sorted(ranked, key=lambda x: x["availability_score"], reverse=True)


def _safe_source_facts(candidate: Dict[str, Any]) -> Dict[str, Any]:
    metadata = _manifest_metadata(candidate)
    safe_metadata: Dict[str, Any] = {}
    for key in (
        "manifest_verified", "manifest_kind", "available_qualities", "variant_count",
        "audio_tracks", "manifest_codecs", "manifest_verified_at", "provider_reported_quality",
    ):
        if key in metadata:
            safe_metadata[key] = metadata[key]
    return {
        "streamId": _text(candidate.get("stream_id") or candidate.get("streamId")),
        "source": _text(candidate.get("source")),
        "provider": _text(candidate.get("provider")),
        "transport": _text(candidate.get("transport")),
        "quality": _text(candidate.get("quality")),
        "voice": _text(candidate.get("voice") or candidate.get("translation")),
        "seeders": int(max(0, _num(candidate.get("seeders"), 0))),
        "metadata": safe_metadata,
    }


def _safe_manifest_facts(facts: Dict[str, Any]) -> Dict[str, Any]:
    audio_tracks = []
    for raw in facts.get("audioTracks") or []:
        if isinstance(raw, dict):
            audio_tracks.append({
                "name": _text(raw.get("name")) or "Audio",
                "language": _text(raw.get("language")),
                "groupId": _text(raw.get("groupId")),
                "default": bool(raw.get("default")),
                "autoselect": bool(raw.get("autoselect")),
            })
    return {
        "kind": _text(facts.get("kind")) or "unknown",
        "variantCount": int(_num(facts.get("variantCount"), 0)),
        "qualities": _sorted_qualities(facts.get("qualities") or []),
        "audioTracks": audio_tracks,
        "codecs": _unique(facts.get("codecs") or []),
        "httpStatus": int(_num(facts.get("httpStatus"), 0)) if facts.get("httpStatus") is not None else None,
    }


class PlaybackAvailabilityIndex:
    def __init__(self, db_path: Path | str):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._ensure_schema()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(str(self.db_path), timeout=10.0)
        conn.execute("PRAGMA busy_timeout=10000")
        return conn

    def _ensure_schema(self) -> None:
        with closing(self._connect()) as conn, conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS availability_snapshot (
                    media_key TEXT PRIMARY KEY,
                    facts_json TEXT NOT NULL,
                    verified_at REAL NOT NULL,
                    expires_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS source_availability (
                    media_key TEXT NOT NULL,
                    locator_hash TEXT NOT NULL,
                    facts_json TEXT NOT NULL,
                    verified_at REAL NOT NULL,
                    expires_at REAL NOT NULL,
                    PRIMARY KEY (media_key, locator_hash)
                );
                CREATE INDEX IF NOT EXISTS idx_source_availability_media
                    ON source_availability(media_key);
                CREATE TABLE IF NOT EXISTS manifest_truth (
                    locator_hash TEXT PRIMARY KEY,
                    facts_json TEXT NOT NULL,
                    verified_at REAL NOT NULL,
                    expires_at REAL NOT NULL
                );
                """
            )

    @staticmethod
    def _table_columns(conn: sqlite3.Connection, table: str) -> set[str]:
        return {str(row[1]) for row in conn.execute(f"PRAGMA table_info({table})")}

    @staticmethod
    def _compat_stream_id(candidate: Dict[str, Any], locator: str) -> str:
        explicit = _text(candidate.get("stream_id") or candidate.get("streamId"))
        if explicit:
            return explicit
        identity = "\x1f".join((
            locator,
            _text(candidate.get("quality")),
            _text(candidate.get("voice") or candidate.get("translation")),
            _text(candidate.get("video_track_index") or candidate.get("videoTrackIndex")),
            _text(candidate.get("audio_track_index") or candidate.get("audioTrackIndex")),
        ))
        return "availability:" + hashlib.sha256(identity.encode("utf-8")).hexdigest()[:24]

    def record(
        self,
        media_id: Any,
        streams: Iterable[Dict[str, Any]],
        *,
        season: Optional[int] = None,
        episode: Optional[int] = None,
        verified_at: Optional[float] = None,
        ttl_seconds: float = 5 * 60,
        failure_code: Optional[str] = None,
    ) -> Dict[str, Any]:
        key = media_key(media_id, season, episode)
        verified = _num(verified_at, time.time()) if verified_at is not None else time.time()
        expires = verified + max(1.0, float(ttl_seconds))
        rows = [dict(item) for item in streams if isinstance(item, dict)]
        summary = summarize_streams(rows)
        if failure_code and not rows:
            summary["status"] = "FAILED"
            summary["available"] = False
        snapshot = {
            "mediaKey": key,
            **summary,
            "failureCode": _text(failure_code) or None,
            "verifiedAt": verified,
            "expiresAt": expires,
        }
        snapshot_json = json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))

        with closing(self._connect()) as conn, conn:
            conn.execute(
                "INSERT OR REPLACE INTO availability_snapshot(media_key,facts_json,verified_at,expires_at) VALUES(?,?,?,?)",
                (key, snapshot_json, verified, expires),
            )
            conn.execute("DELETE FROM source_availability WHERE media_key=?", (key,))
            source_columns = self._table_columns(conn, "source_availability")
            legacy_source_schema = {
                "stream_id", "provider", "transport", "manifest_verified"
            }.issubset(source_columns)
            for item in rows:
                locator = _text(item.get("url") or item.get("playback_url"))
                if not locator:
                    continue
                facts = _safe_source_facts(item)
                facts_json = json.dumps(facts, ensure_ascii=False, separators=(",", ":"))
                locator_digest = _locator_hash(locator)
                if legacy_source_schema:
                    metadata = _manifest_metadata(item)
                    provider = _text(item.get("provider") or item.get("source")) or "unknown"
                    transport = _text(item.get("transport")) or (
                        "torrent_p2p" if _is_p2p(item) else ("hls" if is_hls_stream(item) else "direct")
                    )
                    conn.execute(
                        """INSERT OR REPLACE INTO source_availability(
                            media_key,stream_id,provider,transport,locator_hash,quality,voice,
                            manifest_verified,facts_json,verified_at,expires_at
                        ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                        (
                            key, self._compat_stream_id(item, locator), provider, transport,
                            locator_digest, _text(item.get("quality")) or None,
                            _text(item.get("voice") or item.get("translation")) or None,
                            1 if metadata.get("manifest_verified") else 0,
                            facts_json, verified, expires,
                        ),
                    )
                else:
                    conn.execute(
                        "INSERT OR REPLACE INTO source_availability(media_key,locator_hash,facts_json,verified_at,expires_at) VALUES(?,?,?,?,?)",
                        (key, locator_digest, facts_json, verified, expires),
                    )
        return snapshot

    def get(
        self,
        media_id: Any,
        *,
        season: Optional[int] = None,
        episode: Optional[int] = None,
        now: Optional[float] = None,
        include_expired: bool = False,
    ) -> Optional[Dict[str, Any]]:
        key = media_key(media_id, season, episode)
        with closing(self._connect()) as conn, conn:
            row = conn.execute(
                "SELECT facts_json,expires_at FROM availability_snapshot WHERE media_key=?",
                (key,),
            ).fetchone()
        if not row:
            return None
        if not include_expired and float(row[1]) < (time.time() if now is None else float(now)):
            return None
        try:
            value = json.loads(row[0])
            return value if isinstance(value, dict) else None
        except (TypeError, ValueError, json.JSONDecodeError):
            return None

    def put_manifest_truth(
        self,
        url: str,
        facts: Dict[str, Any],
        *,
        verified_at: Optional[float] = None,
        ttl_seconds: float = 15 * 60,
    ) -> None:
        locator = _text(url)
        if not locator:
            return
        verified = _num(verified_at, time.time()) if verified_at is not None else time.time()
        expires = verified + max(1.0, float(ttl_seconds))
        safe = _safe_manifest_facts(facts or {})
        safe["verifiedAt"] = verified
        with closing(self._connect()) as conn, conn:
            columns = self._table_columns(conn, "manifest_truth")
            payload = json.dumps(safe, ensure_ascii=False, separators=(",", ":"))
            if "schema_version" in columns:
                conn.execute(
                    "INSERT OR REPLACE INTO manifest_truth(locator_hash,facts_json,verified_at,expires_at,schema_version) VALUES(?,?,?,?,?)",
                    (_locator_hash(locator), payload, verified, expires, 2),
                )
            else:
                conn.execute(
                    "INSERT OR REPLACE INTO manifest_truth(locator_hash,facts_json,verified_at,expires_at) VALUES(?,?,?,?)",
                    (_locator_hash(locator), payload, verified, expires),
                )

    def get_manifest_truth(
        self,
        url: str,
        *,
        now: Optional[float] = None,
        include_expired: bool = False,
    ) -> Optional[Dict[str, Any]]:
        locator = _text(url)
        if not locator:
            return None
        with closing(self._connect()) as conn, conn:
            row = conn.execute(
                "SELECT facts_json,expires_at,verified_at FROM manifest_truth WHERE locator_hash=?",
                (_locator_hash(locator),),
            ).fetchone()
        if not row:
            return None
        if not include_expired and float(row[1]) < (time.time() if now is None else float(now)):
            return None
        try:
            value = json.loads(row[0])
        except (TypeError, ValueError, json.JSONDecodeError):
            return None
        if not isinstance(value, dict):
            return None
        value.setdefault("verifiedAt", float(row[2]))
        return value
