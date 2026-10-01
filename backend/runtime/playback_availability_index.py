#!/usr/bin/env python3
"""Playback Availability Index (Source Truth) for Movia.

Catalog metadata answers what content exists.  This module answers what Movia
actually knows about playback availability.  Discovery claims are deliberately
kept separate from verified playback facts.

Step-1 constraints:
* no provider/network calls;
* no manifest parsing;
* a discovered source is NOT playable until verified by explicit evidence;
* episode identity is distinct from the parent TV series;
* catalog.db remains untouched and authoritative for catalog metadata.
"""
from __future__ import annotations

from dataclasses import dataclass
from contextlib import contextmanager
import hashlib
import json
import math
import re
import sqlite3
import time
import urllib.parse
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence

SCHEMA_VERSION = 2
SIGNED_URL_EXPIRY_MARGIN_SECONDS = 60.0

MEDIA_MOVIE = "MOVIE"
MEDIA_SERIES = "SERIES"
MEDIA_EPISODE = "EPISODE"
MEDIA_KINDS = {MEDIA_MOVIE, MEDIA_SERIES, MEDIA_EPISODE}

STATUS_UNKNOWN = "UNKNOWN"
STATUS_NO_SOURCE = "NO_SOURCE"
STATUS_DISCOVERED = "DISCOVERED"
STATUS_VERIFYING = "VERIFYING"
STATUS_VERIFIED = "VERIFIED"
STATUS_STALE = "STALE"
STATUS_EXPIRED = "EXPIRED"
STATUS_FAILED = "FAILED"
STATUS_COOLDOWN = "COOLDOWN"
STATUSES = {
    STATUS_UNKNOWN,
    STATUS_NO_SOURCE,
    STATUS_DISCOVERED,
    STATUS_VERIFYING,
    STATUS_VERIFIED,
    STATUS_STALE,
    STATUS_EXPIRED,
    STATUS_FAILED,
    STATUS_COOLDOWN,
}

VERIFICATION_NONE = "NONE"
VERIFICATION_LEGACY_SUCCESS = "LEGACY_PLAYBACK_SUCCESS"
VERIFICATION_HTTP_PROBE = "HTTP_PROBE"
VERIFICATION_MANIFEST = "MANIFEST"
VERIFICATION_MEDIA3 = "MEDIA3_SUCCESS"
VERIFICATION_METHODS = {
    VERIFICATION_NONE,
    VERIFICATION_LEGACY_SUCCESS,
    VERIFICATION_HTTP_PROBE,
    VERIFICATION_MANIFEST,
    VERIFICATION_MEDIA3,
}

DISCOVERY_PROVIDER = "PROVIDER_SEARCH"
DISCOVERY_LEGACY = "LEGACY_CACHE"
DISCOVERY_RESOLVER = "RESOLVER"
DISCOVERY_PLAYBACK = "PLAYBACK_SUCCESS"
DISCOVERY_BACKGROUND = "BACKGROUND_ENRICHMENT"
DISCOVERY_METHODS = {
    DISCOVERY_PROVIDER,
    DISCOVERY_LEGACY,
    DISCOVERY_RESOLVER,
    DISCOVERY_PLAYBACK,
    DISCOVERY_BACKGROUND,
}

SOURCE_HLS = "HLS"
SOURCE_MP4 = "MP4"
SOURCE_HTTP = "HTTP"
SOURCE_P2P = "P2P"
SOURCE_UNKNOWN = "UNKNOWN"
SOURCE_TYPES = {SOURCE_HLS, SOURCE_MP4, SOURCE_HTTP, SOURCE_P2P, SOURCE_UNKNOWN}

_P2P_SCHEMES = {"magnet"}
_P2P_TRANSPORTS = {"torrent", "torrent_p2p", "p2p", "magnet"}
_HLS_TRANSPORTS = {"hls", "m3u8"}


def _text(value: Any) -> str:
    return str(value or "").strip()


def _finite_float(value: Any) -> Optional[float]:
    if value is None or value == "":
        return None
    try:
        result = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return result if math.isfinite(result) else None


def _positive_int(value: Any) -> Optional[int]:
    try:
        result = int(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return result if result > 0 else None


def _json_list(value: Any) -> str:
    if value is None:
        items: List[Any] = []
    elif isinstance(value, list):
        items = value
    elif isinstance(value, tuple):
        items = list(value)
    elif isinstance(value, str):
        items = [value] if value.strip() else []
    else:
        items = [value]
    return json.dumps(items, ensure_ascii=False, separators=(",", ":"))


def _loads_list(value: Any) -> List[Any]:
    if not value:
        return []
    try:
        parsed = json.loads(str(value))
    except (TypeError, ValueError, json.JSONDecodeError):
        return []
    return parsed if isinstance(parsed, list) else []


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class PlaybackMediaKey:
    media_id: str
    kind: str
    season: Optional[int] = None
    episode: Optional[int] = None

    def __post_init__(self) -> None:
        media_id = _text(self.media_id)
        kind = _text(self.kind).upper()
        if not media_id:
            raise ValueError("media_id is required")
        if kind not in MEDIA_KINDS:
            raise ValueError(f"unsupported media kind: {self.kind}")
        if kind == MEDIA_EPISODE:
            if _positive_int(self.season) is None or _positive_int(self.episode) is None:
                raise ValueError("episode identity requires positive season and episode")
        elif self.season is not None or self.episode is not None:
            raise ValueError(f"{kind} identity cannot contain season/episode")
        object.__setattr__(self, "media_id", media_id)
        object.__setattr__(self, "kind", kind)
        if kind == MEDIA_EPISODE:
            object.__setattr__(self, "season", int(self.season))
            object.__setattr__(self, "episode", int(self.episode))

    @property
    def value(self) -> str:
        if self.kind == MEDIA_MOVIE:
            return f"movie:{self.media_id}"
        if self.kind == MEDIA_SERIES:
            return f"series:{self.media_id}"
        return f"series:{self.media_id}:s{int(self.season):03d}:e{int(self.episode):04d}"


def media_key(
    media_id: Any,
    *,
    kind: str,
    season: Optional[int] = None,
    episode: Optional[int] = None,
) -> str:
    return PlaybackMediaKey(_text(media_id), kind, season, episode).value


def infer_media_kind(media_type: Any, season: Optional[int] = None, episode: Optional[int] = None) -> str:
    if season is not None or episode is not None:
        if _positive_int(season) is None or _positive_int(episode) is None:
            raise ValueError("season and episode must be positive and supplied together")
        return MEDIA_EPISODE
    raw = _text(media_type).casefold()
    if raw in {"tv", "series", "serial", "show"}:
        return MEDIA_SERIES
    return MEDIA_MOVIE


def infer_source_type(candidate: Dict[str, Any]) -> str:
    transport = _text(candidate.get("transport") or candidate.get("type")).casefold()
    locator = _text(candidate.get("url") or candidate.get("playback_url") or candidate.get("locator"))
    if transport in _P2P_TRANSPORTS or locator.casefold().startswith("magnet:"):
        return SOURCE_P2P
    if transport in _HLS_TRANSPORTS:
        return SOURCE_HLS
    try:
        parsed = urllib.parse.urlsplit(locator)
    except Exception:
        parsed = None
    path = (parsed.path if parsed else "").casefold()
    if path.endswith(".m3u8") or ".m3u8/" in path:
        return SOURCE_HLS
    if path.endswith((".mp4", ".m4v", ".webm", ".mkv")):
        return SOURCE_MP4
    if locator.casefold().startswith(("http://", "https://")):
        return SOURCE_HTTP
    return SOURCE_UNKNOWN


def signed_url_expiry(locator: Any) -> Optional[float]:
    raw = _text(locator)
    if not raw.lower().startswith(("http://", "https://")):
        return None
    try:
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(raw).query)
    except Exception:
        return None
    for key in ("expires", "expire", "exp", "t"):
        token = (query.get(key) or [None])[0]
        value = _finite_float(token)
        if value is not None and value >= 1_000_000_000:
            return value
    return None


def _provider_audio(candidate: Dict[str, Any]) -> List[Any]:
    raw = candidate.get("provider_audio")
    if raw is None:
        raw = candidate.get("providerAudio")
    if raw is None:
        raw = candidate.get("audio_tracks")
    if raw is None:
        raw = candidate.get("audioTracks")
    if raw is None:
        raw = candidate.get("voice") or candidate.get("translation")
    if raw is None:
        return []
    if isinstance(raw, list):
        return raw
    if isinstance(raw, tuple):
        return list(raw)
    return [raw]


def _actual_audio(candidate: Dict[str, Any]) -> List[Any]:
    raw = candidate.get("actual_audio")
    if raw is None:
        raw = candidate.get("actualAudio")
    if raw is None:
        raw = candidate.get("actual_audio_tracks")
    if raw is None:
        raw = candidate.get("actualAudioTracks")
    return list(raw) if isinstance(raw, (list, tuple)) else []


def _source_id(media_key_value: str, candidate: Dict[str, Any], locator: str) -> str:
    explicit = _text(candidate.get("stream_id") or candidate.get("streamId") or candidate.get("source_id"))
    provider = _text(candidate.get("provider") or candidate.get("source")) or "unknown"
    if explicit and not explicit.startswith("stream:"):
        identity = f"{media_key_value}\x1f{provider.casefold()}\x1f{explicit}"
    else:
        identity = "\x1f".join((
            media_key_value,
            provider.casefold(),
            _sha256(locator),
            _text(candidate.get("quality")),
            _text(candidate.get("voice") or candidate.get("translation")),
            _text(candidate.get("season")),
            _text(candidate.get("episode")),
        ))
    return "src:" + _sha256(identity)[:32]


def _canonical_quality(value: Any) -> str:
    text = _text(value).casefold().replace(" ", "")
    if not text or text in {"auto", "any", "unknown", "n/a", "неуказано"}:
        return ""
    if "2160" in text or "4k" in text or "uhd" in text:
        return "2160p"
    if "1440" in text or "2k" in text:
        return "1440p"
    for height in (1080, 720, 576, 480, 360, 240, 144):
        if str(height) in text:
            return f"{height}p"
    return text


def _quality_sort_key(value: str) -> tuple[int, str]:
    canonical = _canonical_quality(value)
    digits = "".join(ch for ch in canonical if ch.isdigit())
    try:
        height = int(digits)
    except ValueError:
        height = 0
    return (-height, canonical)


def _normalize_audio_text(value: Any) -> str:
    text = _text(value).casefold().replace("ё", "е")
    return " ".join(re.sub(r"[^0-9a-zа-яіїєґ]+", " ", text).split())


def _canonical_audio_language(value: Any) -> str:
    text = _normalize_audio_text(value)
    if not text:
        return ""
    if text in {"ru", "rus", "russian", "рус", "русский", "русская", "русские"} or text.startswith("ru "):
        return "ru"
    if text in {"uk", "ukr", "ua", "ukrainian", "украинский", "украинская", "украинские", "український", "українська", "українські"} or text.startswith(("uk ", "ua ")):
        return "uk"
    if text in {"en", "eng", "english", "английский", "английская", "original", "оригинал"} or text.startswith("en "):
        return "en"
    return ""


def _audio_track_label(track: Any) -> str:
    if isinstance(track, dict):
        return _text(track.get("name") or track.get("label") or track.get("title"))
    return _text(track)


def _audio_track_language(track: Any) -> str:
    if isinstance(track, dict):
        explicit = _canonical_audio_language(track.get("language") or track.get("lang"))
        if explicit:
            return explicit
    label = _normalize_audio_text(_audio_track_label(track))
    if not label:
        return ""
    if any(token in label for token in ("укр", "укра", "ukrain", "ukr")):
        return "uk"
    if any(token in label for token in ("english", "original", "оригинал", "англ")) or label.startswith(("eng", "en ")):
        return "en"
    if any(token in label for token in ("рус", "russian")) or label.startswith(("rus", "ru ")):
        return "ru"
    return ""


def _canonical_audio_request(value: Any) -> tuple[str, str]:
    raw = _text(value)
    normalized = _normalize_audio_text(raw)
    if not normalized or normalized in {"auto", "any", "авто"}:
        return ("auto", "Auto")
    language = _canonical_audio_language(raw)
    if language:
        return ("language", language)
    return ("voice", normalized)


class PlaybackAvailabilityRepository:
    """SQLite persistence. Business invariants live in PlaybackAvailabilityService."""

    def __init__(self, db_path: Path | str):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._ensure_schema()

    def connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(str(self.db_path), timeout=10.0)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("PRAGMA foreign_keys=ON")
        return conn

    @contextmanager
    def connection(self):
        conn = self.connect()
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def _ensure_schema(self) -> None:
        with self.connection() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS playback_meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS playback_availability (
                    media_key TEXT PRIMARY KEY,
                    media_id TEXT NOT NULL,
                    media_kind TEXT NOT NULL,
                    season_number INTEGER,
                    episode_number INTEGER,
                    availability_status TEXT NOT NULL DEFAULT 'UNKNOWN',
                    available INTEGER NOT NULL DEFAULT 0,
                    source_count INTEGER NOT NULL DEFAULT 0,
                    verified_source_count INTEGER NOT NULL DEFAULT 0,
                    best_source_id TEXT,
                    last_discovery_at REAL,
                    last_verification_at REAL,
                    last_success_at REAL,
                    next_check_at REAL,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    revision INTEGER NOT NULL DEFAULT 1,
                    CHECK (media_kind IN ('MOVIE','SERIES','EPISODE')),
                    CHECK (available IN (0,1)),
                    CHECK (
                        (media_kind='EPISODE' AND season_number IS NOT NULL AND episode_number IS NOT NULL)
                        OR
                        (media_kind!='EPISODE' AND season_number IS NULL AND episode_number IS NULL)
                    )
                );

                CREATE TABLE IF NOT EXISTS playback_sources (
                    source_id TEXT PRIMARY KEY,
                    media_key TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    locator TEXT NOT NULL,
                    locator_hash TEXT NOT NULL,
                    verification_status TEXT NOT NULL DEFAULT 'DISCOVERED',
                    provider_quality TEXT,
                    actual_quality TEXT,
                    actual_qualities_json TEXT NOT NULL DEFAULT '[]',
                    provider_audio_json TEXT NOT NULL DEFAULT '[]',
                    actual_audio_json TEXT NOT NULL DEFAULT '[]',
                    manifest_type TEXT,
                    expires_at REAL,
                    discovery_method TEXT NOT NULL DEFAULT 'LEGACY_CACHE',
                    verification_method TEXT NOT NULL DEFAULT 'NONE',
                    discovered_at REAL NOT NULL,
                    last_checked_at REAL,
                    last_success_at REAL,
                    last_failure_at REAL,
                    startup_latency_ms REAL,
                    consecutive_failures INTEGER NOT NULL DEFAULT 0,
                    failure_reason TEXT,
                    health_score REAL,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    FOREIGN KEY(media_key) REFERENCES playback_availability(media_key) ON DELETE CASCADE,
                    CHECK (source_type IN ('HLS','MP4','HTTP','P2P','UNKNOWN')),
                    CHECK (verification_status IN ('DISCOVERED','VERIFYING','VERIFIED','STALE','EXPIRED','FAILED','COOLDOWN'))
                );

                CREATE INDEX IF NOT EXISTS idx_pa_media_id
                    ON playback_availability(media_id);
                CREATE INDEX IF NOT EXISTS idx_pa_status
                    ON playback_availability(availability_status);
                CREATE INDEX IF NOT EXISTS idx_pa_next_check
                    ON playback_availability(next_check_at);
                CREATE INDEX IF NOT EXISTS idx_ps_media_key
                    ON playback_sources(media_key);
                CREATE INDEX IF NOT EXISTS idx_ps_provider
                    ON playback_sources(provider);
                CREATE INDEX IF NOT EXISTS idx_ps_status
                    ON playback_sources(verification_status);
                CREATE INDEX IF NOT EXISTS idx_ps_expires
                    ON playback_sources(expires_at);
                CREATE INDEX IF NOT EXISTS idx_ps_last_success
                    ON playback_sources(last_success_at);
                CREATE UNIQUE INDEX IF NOT EXISTS idx_ps_fingerprint
                    ON playback_sources(media_key, provider, locator_hash);
                """
            )
            source_columns = {str(row[1]) for row in conn.execute("PRAGMA table_info(playback_sources)")}
            if "actual_qualities_json" not in source_columns:
                conn.execute(
                    "ALTER TABLE playback_sources ADD COLUMN actual_qualities_json TEXT NOT NULL DEFAULT '[]'"
                )
            conn.execute(
                "INSERT INTO playback_meta(key,value) VALUES('schema_version',?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (str(SCHEMA_VERSION),),
            )

    def ensure_media(self, key: PlaybackMediaKey, *, now: Optional[float] = None) -> None:
        ts = time.time() if now is None else float(now)
        with self.connection() as conn:
            conn.execute(
                """
                INSERT INTO playback_availability(
                    media_key,media_id,media_kind,season_number,episode_number,
                    availability_status,available,source_count,verified_source_count,
                    created_at,updated_at,revision
                ) VALUES(?,?,?,?,?,'UNKNOWN',0,0,0,?,?,1)
                ON CONFLICT(media_key) DO UPDATE SET
                    media_id=excluded.media_id,
                    media_kind=excluded.media_kind,
                    season_number=excluded.season_number,
                    episode_number=excluded.episode_number,
                    updated_at=excluded.updated_at
                """,
                (key.value, key.media_id, key.kind, key.season, key.episode, ts, ts),
            )

    def get_media_row(self, key_value: str) -> Optional[sqlite3.Row]:
        with self.connection() as conn:
            return conn.execute(
                "SELECT * FROM playback_availability WHERE media_key=?", (key_value,)
            ).fetchone()

    def get_source_row(self, source_id: str) -> Optional[sqlite3.Row]:
        with self.connection() as conn:
            return conn.execute(
                "SELECT * FROM playback_sources WHERE source_id=?", (source_id,)
            ).fetchone()

    def get_source_rows(self, key_value: str) -> List[sqlite3.Row]:
        with self.connection() as conn:
            return conn.execute(
                "SELECT * FROM playback_sources WHERE media_key=? ORDER BY updated_at DESC, source_id",
                (key_value,),
            ).fetchall()


class PlaybackAvailabilityService:
    def __init__(
        self,
        db_path: Path | str,
        *,
        expiry_margin_seconds: float = SIGNED_URL_EXPIRY_MARGIN_SECONDS,
    ):
        self.repository = PlaybackAvailabilityRepository(db_path)
        self.expiry_margin_seconds = max(0.0, float(expiry_margin_seconds))

    @property
    def db_path(self) -> Path:
        return self.repository.db_path

    def ensure_media(
        self,
        media_id: Any,
        *,
        kind: str,
        season: Optional[int] = None,
        episode: Optional[int] = None,
        now: Optional[float] = None,
    ) -> str:
        key = PlaybackMediaKey(_text(media_id), kind, season, episode)
        self.repository.ensure_media(key, now=now)
        return key.value

    def _effective_status(self, status: str, expires_at: Optional[float], now: float) -> str:
        if expires_at is not None and expires_at <= now + self.expiry_margin_seconds:
            return STATUS_EXPIRED
        return status if status in STATUSES else STATUS_DISCOVERED

    def _recompute(self, key_value: str, *, now: Optional[float] = None) -> Dict[str, Any]:
        ts = time.time() if now is None else float(now)
        with self.repository.connection() as conn:
            rows = conn.execute(
                "SELECT * FROM playback_sources WHERE media_key=?", (key_value,)
            ).fetchall()
            for row in rows:
                effective = self._effective_status(
                    str(row["verification_status"]),
                    _finite_float(row["expires_at"]),
                    ts,
                )
                if effective != row["verification_status"]:
                    conn.execute(
                        "UPDATE playback_sources SET verification_status=?,updated_at=? WHERE source_id=?",
                        (effective, ts, row["source_id"]),
                    )
            rows = conn.execute(
                "SELECT * FROM playback_sources WHERE media_key=?", (key_value,)
            ).fetchall()
            verified = [r for r in rows if r["verification_status"] == STATUS_VERIFIED]
            source_count = len(rows)
            verified_count = len(verified)
            available = verified_count > 0
            if available:
                status = STATUS_VERIFIED
            elif not rows:
                status = STATUS_NO_SOURCE
            else:
                statuses = {str(r["verification_status"]) for r in rows}
                if STATUS_VERIFYING in statuses:
                    status = STATUS_VERIFYING
                elif STATUS_DISCOVERED in statuses:
                    status = STATUS_DISCOVERED
                elif statuses == {STATUS_EXPIRED}:
                    status = STATUS_EXPIRED
                elif STATUS_COOLDOWN in statuses:
                    status = STATUS_COOLDOWN
                elif STATUS_STALE in statuses:
                    status = STATUS_STALE
                elif STATUS_FAILED in statuses:
                    status = STATUS_FAILED
                else:
                    status = STATUS_UNKNOWN
            best_source_id = None
            if verified:
                best = sorted(
                    verified,
                    key=lambda r: (
                        -float(r["health_score"] if r["health_score"] is not None else 0.5),
                        float(r["startup_latency_ms"] if r["startup_latency_ms"] is not None else 1e12),
                        -float(r["last_success_at"] if r["last_success_at"] is not None else 0.0),
                        str(r["source_id"]),
                    ),
                )[0]
                best_source_id = best["source_id"]
            conn.execute(
                """
                UPDATE playback_availability
                SET availability_status=?,available=?,source_count=?,verified_source_count=?,
                    best_source_id=?,last_verification_at=CASE WHEN ?>0 THEN ? ELSE last_verification_at END,
                    updated_at=?,revision=revision+1
                WHERE media_key=?
                """,
                (
                    status, 1 if available else 0, source_count, verified_count,
                    best_source_id, verified_count, ts, ts, key_value,
                ),
            )
        value = self.get_by_key(key_value, include_sources=True, include_locator=False)
        if value is None:
            raise RuntimeError(f"availability row disappeared: {key_value}")
        return value

    def reconcile_expired_sources(
        self,
        *,
        now: Optional[float] = None,
        media_key_value: Optional[str] = None,
    ) -> int:
        """Expire signed VERIFIED sources and refresh only affected aggregates.

        This is local SQLite maintenance only. It never probes providers or the
        network. A bounded exact-key read may pass media_key_value; diagnostics
        use the global form to reconcile all rows whose signed expiry elapsed.
        """
        ts = time.time() if now is None else float(now)
        threshold = ts + self.expiry_margin_seconds
        with self.repository.connection() as conn:
            if media_key_value is None:
                rows = conn.execute(
                    """SELECT DISTINCT media_key FROM playback_sources
                       WHERE verification_status='VERIFIED'
                         AND expires_at IS NOT NULL AND expires_at<=?""",
                    (threshold,),
                ).fetchall()
            else:
                rows = conn.execute(
                    """SELECT DISTINCT media_key FROM playback_sources
                       WHERE media_key=? AND verification_status='VERIFIED'
                         AND expires_at IS NOT NULL AND expires_at<=?""",
                    (str(media_key_value), threshold),
                ).fetchall()
        keys = [str(row[0]) for row in rows]
        for key_value in keys:
            self._recompute(key_value, now=ts)
        return len(keys)

    def record_discovery(
        self,
        media_id: Any,
        streams: Iterable[Dict[str, Any]],
        *,
        kind: str,
        season: Optional[int] = None,
        episode: Optional[int] = None,
        discovery_method: str = DISCOVERY_RESOLVER,
        failure_code: Optional[str] = None,
        now: Optional[float] = None,
    ) -> Dict[str, Any]:
        if discovery_method not in DISCOVERY_METHODS:
            raise ValueError(f"unsupported discovery method: {discovery_method}")
        ts = time.time() if now is None else float(now)
        key = PlaybackMediaKey(_text(media_id), kind, season, episode)
        self.repository.ensure_media(key, now=ts)
        clean = [dict(s) for s in streams if isinstance(s, dict)]
        source_ids: List[str] = []
        with self.repository.connection() as conn:
            for candidate in clean:
                locator = _text(candidate.get("url") or candidate.get("playback_url") or candidate.get("locator"))
                if not locator:
                    continue
                source_ids.append(
                    self._upsert_discovered_source_conn(
                        conn, key, candidate, locator, discovery_method, ts
                    )
                )
            conn.execute(
                "UPDATE playback_availability SET last_discovery_at=?,updated_at=? WHERE media_key=?",
                (ts, ts, key.value),
            )
            if failure_code and not source_ids:
                conn.execute(
                    "UPDATE playback_availability SET availability_status='FAILED',available=0,updated_at=?,revision=revision+1 WHERE media_key=?",
                    (ts, key.value),
                )
        result = self._recompute(key.value, now=ts)
        if failure_code and not source_ids:
            with self.repository.connection() as conn:
                conn.execute(
                    "UPDATE playback_availability SET availability_status='FAILED',available=0,updated_at=? WHERE media_key=?",
                    (ts, key.value),
                )
            result = self.get_by_key(key.value, include_sources=True, include_locator=False) or result
        return result

    def _upsert_discovered_source_conn(
        self,
        conn: sqlite3.Connection,
        key: PlaybackMediaKey,
        candidate: Dict[str, Any],
        locator: str,
        discovery_method: str,
        ts: float,
    ) -> str:
        provider = _text(candidate.get("provider") or candidate.get("source")) or "unknown"
        source_type = infer_source_type(candidate)
        source_id = _source_id(key.value, candidate, locator)
        locator_hash = _sha256(locator)
        expiry = _finite_float(candidate.get("expires_at") or candidate.get("expiresAt"))
        if expiry is None:
            expiry = signed_url_expiry(locator)
        status = STATUS_EXPIRED if expiry is not None and expiry <= ts + self.expiry_margin_seconds else STATUS_DISCOVERED
        provider_quality = _text(candidate.get("provider_quality") or candidate.get("providerQuality") or candidate.get("quality")) or None
        provider_audio_json = _json_list(_provider_audio(candidate))

        # Source Truth rule: actual_* is accepted only with explicit verification evidence.
        verification_method = _text(candidate.get("verification_method") or candidate.get("verificationMethod")).upper() or VERIFICATION_NONE
        requested_status = _text(candidate.get("verification_status") or candidate.get("verificationStatus")).upper()
        explicit_verified = requested_status == STATUS_VERIFIED and verification_method in VERIFICATION_METHODS - {VERIFICATION_NONE}
        actual_quality = None
        actual_qualities_json = "[]"
        actual_audio_json = "[]"
        last_checked_at = None
        last_success_at = None
        if explicit_verified and status != STATUS_EXPIRED:
            status = STATUS_VERIFIED
            actual_quality = _text(candidate.get("actual_quality") or candidate.get("actualQuality")) or None
            actual_qualities_json = _json_list(
                candidate.get("actual_qualities") or candidate.get("actualQualities") or []
            )
            actual_audio_json = _json_list(_actual_audio(candidate))
            last_checked_at = ts
            if verification_method in {VERIFICATION_MEDIA3, VERIFICATION_LEGACY_SUCCESS}:
                last_success_at = ts
        else:
            verification_method = VERIFICATION_NONE

        existing_sql = (
            "SELECT source_id,created_at,actual_quality,actual_qualities_json,actual_audio_json,verification_status,"
            "verification_method,last_checked_at,last_success_at,last_failure_at,"
            "consecutive_failures,startup_latency_ms,health_score "
            "FROM playback_sources "
        )
        existing = conn.execute(existing_sql + "WHERE source_id=?", (source_id,)).fetchone()
        if existing is None:
            # A provider can expose the same physical locator under multiple
            # logical stream IDs/quality labels. Source Truth stores the
            # physical source once; provider labels remain non-authoritative.
            existing = conn.execute(
                existing_sql + "WHERE media_key=? AND provider=? AND locator_hash=? LIMIT 1",
                (key.value, provider, locator_hash),
            ).fetchone()
            if existing is not None:
                source_id = str(existing["source_id"])
        created_at = float(existing["created_at"]) if existing is not None else ts
        if existing is not None and not explicit_verified:
            if existing["verification_status"] == STATUS_VERIFIED and status != STATUS_EXPIRED:
                status = STATUS_VERIFIED
                verification_method = str(existing["verification_method"] or VERIFICATION_NONE)
                actual_quality = existing["actual_quality"]
                actual_qualities_json = existing["actual_qualities_json"] or "[]"
                actual_audio_json = existing["actual_audio_json"] or "[]"
                last_checked_at = existing["last_checked_at"]
                last_success_at = existing["last_success_at"]
        conn.execute(
            """
            INSERT INTO playback_sources(
                source_id,media_key,provider,source_type,locator,locator_hash,
                verification_status,provider_quality,actual_quality,actual_qualities_json,
                provider_audio_json,actual_audio_json,manifest_type,expires_at,
                discovery_method,verification_method,discovered_at,last_checked_at,
                last_success_at,last_failure_at,startup_latency_ms,consecutive_failures,
                failure_reason,health_score,created_at,updated_at
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(source_id) DO UPDATE SET
                media_key=excluded.media_key,
                provider=excluded.provider,
                source_type=excluded.source_type,
                locator=excluded.locator,
                locator_hash=excluded.locator_hash,
                verification_status=excluded.verification_status,
                provider_quality=excluded.provider_quality,
                actual_quality=excluded.actual_quality,
                actual_qualities_json=excluded.actual_qualities_json,
                provider_audio_json=excluded.provider_audio_json,
                actual_audio_json=excluded.actual_audio_json,
                expires_at=excluded.expires_at,
                discovery_method=excluded.discovery_method,
                verification_method=excluded.verification_method,
                discovered_at=excluded.discovered_at,
                last_checked_at=COALESCE(excluded.last_checked_at,playback_sources.last_checked_at),
                last_success_at=COALESCE(excluded.last_success_at,playback_sources.last_success_at),
                updated_at=excluded.updated_at
            """,
            (
                source_id, key.value, provider, source_type, locator, locator_hash,
                status, provider_quality, actual_quality, actual_qualities_json,
                provider_audio_json, actual_audio_json, None, expiry,
                discovery_method, verification_method, ts, last_checked_at,
                last_success_at,
                existing["last_failure_at"] if existing is not None else None,
                existing["startup_latency_ms"] if existing is not None else None,
                int(existing["consecutive_failures"] or 0) if existing is not None else 0,
                None,
                existing["health_score"] if existing is not None else None,
                created_at, ts,
            ),
        )
        return source_id

    def _upsert_discovered_source(
        self,
        key: PlaybackMediaKey,
        candidate: Dict[str, Any],
        locator: str,
        discovery_method: str,
        ts: float,
    ) -> str:
        with self.repository.connection() as conn:
            return self._upsert_discovered_source_conn(
                conn, key, candidate, locator, discovery_method, ts
            )

    def verify_candidate(
        self,
        media_id: Any,
        candidate: Dict[str, Any],
        *,
        kind: str,
        verification_method: str,
        season: Optional[int] = None,
        episode: Optional[int] = None,
        discovery_method: str = DISCOVERY_RESOLVER,
        actual_quality: Optional[str] = None,
        actual_qualities: Optional[Sequence[Any]] = None,
        actual_audio_tracks: Optional[Sequence[Any]] = None,
        manifest_type: Optional[str] = None,
        startup_latency_ms: Optional[float] = None,
        health_score: Optional[float] = None,
        success: bool = False,
        now: Optional[float] = None,
    ) -> Dict[str, Any]:
        """Atomically ensure a physical candidate exists, then verify it.

        This closes the race between asynchronous discovery and independent
        verification workers. Provider claims are still stored separately; only
        facts supplied with an explicit verification method become actual facts.
        """
        method = _text(verification_method).upper()
        if method not in VERIFICATION_METHODS - {VERIFICATION_NONE}:
            raise ValueError("verification_method must provide explicit evidence")
        if discovery_method not in DISCOVERY_METHODS:
            raise ValueError(f"unsupported discovery method: {discovery_method}")
        ts = time.time() if now is None else float(now)
        key = PlaybackMediaKey(_text(media_id), kind, season, episode)
        locator = _text(
            (candidate or {}).get("url")
            or (candidate or {}).get("playback_url")
            or (candidate or {}).get("locator")
        )
        if not locator:
            raise ValueError("candidate locator is required")
        self.repository.ensure_media(key, now=ts)
        with self.repository.connection() as conn:
            source_id = self._upsert_discovered_source_conn(
                conn, key, dict(candidate or {}), locator, discovery_method, ts
            )
        return self.mark_source_verified(
            source_id,
            verification_method=method,
            actual_quality=actual_quality,
            actual_qualities=actual_qualities,
            actual_audio_tracks=actual_audio_tracks,
            manifest_type=manifest_type,
            startup_latency_ms=startup_latency_ms,
            health_score=health_score,
            success=success,
            now=ts,
        )

    def mark_source_verified(
        self,
        source_id: str,
        *,
        verification_method: str,
        actual_quality: Optional[str] = None,
        actual_qualities: Optional[Sequence[Any]] = None,
        actual_audio_tracks: Optional[Sequence[Any]] = None,
        manifest_type: Optional[str] = None,
        startup_latency_ms: Optional[float] = None,
        health_score: Optional[float] = None,
        success: bool = False,
        now: Optional[float] = None,
    ) -> Dict[str, Any]:
        method = _text(verification_method).upper()
        if method not in VERIFICATION_METHODS - {VERIFICATION_NONE}:
            raise ValueError("verification_method must provide explicit evidence")
        ts = time.time() if now is None else float(now)
        with self.repository.connection() as conn:
            row = conn.execute("SELECT * FROM playback_sources WHERE source_id=?", (source_id,)).fetchone()
            if row is None:
                raise KeyError(source_id)
            expiry = _finite_float(row["expires_at"])
            status = STATUS_EXPIRED if expiry is not None and expiry <= ts + self.expiry_margin_seconds else STATUS_VERIFIED
            conn.execute(
                """
                UPDATE playback_sources SET
                    verification_status=?,verification_method=?,actual_quality=COALESCE(?,actual_quality),
                    actual_qualities_json=COALESCE(?,actual_qualities_json),
                    actual_audio_json=COALESCE(?,actual_audio_json),
                    manifest_type=COALESCE(?,manifest_type),last_checked_at=?,last_success_at=CASE WHEN ? THEN ? ELSE last_success_at END,
                    startup_latency_ms=COALESCE(?,startup_latency_ms),
                    consecutive_failures=CASE WHEN ? THEN 0 ELSE consecutive_failures END,
                    failure_reason=CASE WHEN ? THEN NULL ELSE failure_reason END,
                    health_score=COALESCE(?,health_score),updated_at=?
                WHERE source_id=?
                """,
                (
                    status, method, _text(actual_quality) or None,
                    (_json_list(actual_qualities) if actual_qualities is not None else None),
                    (_json_list(actual_audio_tracks) if actual_audio_tracks is not None else None),
                    _text(manifest_type) or None, ts, 1 if success else 0, ts,
                    _finite_float(startup_latency_ms), 1 if success else 0, 1 if success else 0,
                    _finite_float(health_score), ts, source_id,
                ),
            )
            key_value = str(row["media_key"])
            if success:
                conn.execute(
                    "UPDATE playback_availability SET last_success_at=?,updated_at=? WHERE media_key=?",
                    (ts, ts, key_value),
                )
        return self._recompute(key_value, now=ts)

    def record_playback_success(
        self,
        source_id: str,
        *,
        startup_latency_ms: Optional[float] = None,
        actual_quality: Optional[str] = None,
        actual_qualities: Optional[Sequence[Any]] = None,
        actual_audio_tracks: Optional[Sequence[Any]] = None,
        now: Optional[float] = None,
    ) -> Dict[str, Any]:
        return self.mark_source_verified(
            source_id,
            verification_method=VERIFICATION_MEDIA3,
            actual_quality=actual_quality,
            actual_qualities=actual_qualities,
            actual_audio_tracks=actual_audio_tracks,
            startup_latency_ms=startup_latency_ms,
            success=True,
            now=now,
        )

    def record_source_failure(
        self,
        source_id: str,
        *,
        reason: str,
        cooldown: bool = False,
        now: Optional[float] = None,
    ) -> Dict[str, Any]:
        ts = time.time() if now is None else float(now)
        with self.repository.connection() as conn:
            row = conn.execute("SELECT * FROM playback_sources WHERE source_id=?", (source_id,)).fetchone()
            if row is None:
                raise KeyError(source_id)
            status = STATUS_COOLDOWN if cooldown else STATUS_FAILED
            conn.execute(
                """
                UPDATE playback_sources SET verification_status=?,last_checked_at=?,last_failure_at=?,
                    consecutive_failures=consecutive_failures+1,failure_reason=?,updated_at=?
                WHERE source_id=?
                """,
                (status, ts, ts, _text(reason) or "UNKNOWN", ts, source_id),
            )
            key_value = str(row["media_key"])
        return self._recompute(key_value, now=ts)

    def set_no_source(
        self,
        media_id: Any,
        *,
        kind: str,
        season: Optional[int] = None,
        episode: Optional[int] = None,
        now: Optional[float] = None,
    ) -> Dict[str, Any]:
        ts = time.time() if now is None else float(now)
        key = PlaybackMediaKey(_text(media_id), kind, season, episode)
        self.repository.ensure_media(key, now=ts)
        with self.repository.connection() as conn:
            count = conn.execute("SELECT COUNT(*) FROM playback_sources WHERE media_key=?", (key.value,)).fetchone()[0]
            if int(count) == 0:
                conn.execute(
                    "UPDATE playback_availability SET availability_status='NO_SOURCE',available=0,last_discovery_at=?,updated_at=?,revision=revision+1 WHERE media_key=?",
                    (ts, ts, key.value),
                )
        return self.get_by_key(key.value, include_sources=True, include_locator=False) or {}

    def get(
        self,
        media_id: Any,
        *,
        kind: str,
        season: Optional[int] = None,
        episode: Optional[int] = None,
        include_sources: bool = True,
        include_locator: bool = False,
        now: Optional[float] = None,
    ) -> Optional[Dict[str, Any]]:
        key = PlaybackMediaKey(_text(media_id), kind, season, episode)
        if self.repository.get_media_row(key.value) is None:
            return None
        self.reconcile_expired_sources(now=now, media_key_value=key.value)
        return self.get_by_key(key.value, include_sources=include_sources, include_locator=include_locator)

    def get_by_key(
        self,
        key_value: str,
        *,
        include_sources: bool = True,
        include_locator: bool = False,
    ) -> Optional[Dict[str, Any]]:
        row = self.repository.get_media_row(key_value)
        if row is None:
            return None
        result: Dict[str, Any] = {
            "mediaKey": row["media_key"],
            "mediaId": row["media_id"],
            "kind": row["media_kind"],
            "season": row["season_number"],
            "episode": row["episode_number"],
            "available": bool(row["available"]),
            "status": row["availability_status"],
            "sourceCount": int(row["source_count"]),
            "verifiedSourceCount": int(row["verified_source_count"]),
            "bestSourceId": row["best_source_id"],
            "lastDiscoveryAt": row["last_discovery_at"],
            "lastVerificationAt": row["last_verification_at"],
            "lastSuccessAt": row["last_success_at"],
            "nextCheckAt": row["next_check_at"],
            "revision": int(row["revision"]),
            "schemaVersion": SCHEMA_VERSION,
        }
        if include_sources:
            sources: List[Dict[str, Any]] = []
            for source in self.repository.get_source_rows(key_value):
                item = {
                    "sourceId": source["source_id"],
                    "provider": source["provider"],
                    "type": source["source_type"],
                    "verificationStatus": source["verification_status"],
                    "providerQuality": source["provider_quality"],
                    "actualQuality": source["actual_quality"],
                    "actualQualities": _loads_list(source["actual_qualities_json"]),
                    "providerAudioTracks": _loads_list(source["provider_audio_json"]),
                    "actualAudioTracks": _loads_list(source["actual_audio_json"]),
                    "manifestType": source["manifest_type"],
                    "expiresAt": source["expires_at"],
                    "discoveryMethod": source["discovery_method"],
                    "verificationMethod": source["verification_method"],
                    "lastCheckedAt": source["last_checked_at"],
                    "lastSuccessAt": source["last_success_at"],
                    "lastFailureAt": source["last_failure_at"],
                    "startupLatencyMs": source["startup_latency_ms"],
                    "consecutiveFailures": int(source["consecutive_failures"]),
                    "failureReason": source["failure_reason"],
                    "healthScore": source["health_score"],
                    "locatorHash": source["locator_hash"],
                }
                if include_locator:
                    item["locator"] = source["locator"]
                sources.append(item)
            result["sources"] = sources
        return result

    def select_verified_source(
        self,
        media_id: Any,
        *,
        kind: str,
        season: Optional[int] = None,
        episode: Optional[int] = None,
        requested_quality: Optional[str] = None,
        requested_audio: Optional[str] = None,
        include_locator: bool = False,
        now: Optional[float] = None,
    ) -> Dict[str, Any]:
        """Select only from verified, unexpired source evidence.

        Provider-reported quality never satisfies an explicit quality request. A
        concrete request is accepted only when actual_quality or verified
        actual_qualities contains it; otherwise QUALITY_NOT_AVAILABLE is returned
        rather than silently falling back to another rendition. Audio follows the
        same evidence rule: provider voice labels never satisfy a request. Only
        verified physical actual_audio tracks may satisfy Auto/RU/UK/voice.
        """
        ts = time.time() if now is None else float(now)
        key = PlaybackMediaKey(_text(media_id), kind, season, episode)
        audio_request_kind, audio_request_value = _canonical_audio_request(requested_audio)
        if self.repository.get_media_row(key.value) is None:
            return {
                "status": "NOT_INDEXED",
                "errorCode": "AVAILABILITY_NOT_INDEXED",
                "mediaKey": key.value,
                "requestedQuality": _canonical_quality(requested_quality) or "Auto",
                "requestedAudio": audio_request_value,
                "availableQualities": [],
                "availableAudioLanguages": [],
                "source": None,
            }

        # Expiry is part of Source Truth; normalize stale VERIFIED rows before
        # selection so an expired signed URL can never survive a read.
        self._recompute(key.value, now=ts)
        rows = [
            row for row in self.repository.get_source_rows(key.value)
            if str(row["verification_status"]) == STATUS_VERIFIED
        ]
        if not rows:
            return {
                "status": "NOT_AVAILABLE",
                "errorCode": "NO_VERIFIED_SOURCE",
                "mediaKey": key.value,
                "requestedQuality": _canonical_quality(requested_quality) or "Auto",
                "requestedAudio": audio_request_value,
                "availableQualities": [],
                "availableAudioLanguages": [],
                "source": None,
            }

        def factual_qualities(row: sqlite3.Row) -> List[str]:
            values = list(_loads_list(row["actual_qualities_json"]))
            if row["actual_quality"]:
                values.append(row["actual_quality"])
            normalized = {_canonical_quality(value) for value in values}
            return sorted((q for q in normalized if q), key=_quality_sort_key)

        all_qualities = sorted(
            {q for row in rows for q in factual_qualities(row)},
            key=_quality_sort_key,
        )
        wanted = _canonical_quality(requested_quality)
        if wanted:
            quality_rows = [row for row in rows if wanted in factual_qualities(row)]
            if not quality_rows:
                return {
                    "status": "NOT_AVAILABLE",
                    "errorCode": "QUALITY_NOT_AVAILABLE",
                    "mediaKey": key.value,
                    "requestedQuality": wanted,
                    "requestedAudio": audio_request_value,
                    "availableQualities": all_qualities,
                    "availableAudioLanguages": [],
                    "source": None,
                }
            rows = quality_rows

        allowed_audio_languages = {"ru", "uk"}

        def factual_audio_tracks(row: sqlite3.Row) -> List[Any]:
            return list(_loads_list(row["actual_audio_json"]))

        def allowed_tracks(row: sqlite3.Row) -> List[tuple[Any, str]]:
            result: List[tuple[Any, str]] = []
            for track in factual_audio_tracks(row):
                language = _audio_track_language(track)
                if language in allowed_audio_languages:
                    result.append((track, language))
            return result

        available_audio_languages = sorted({
            language for row in rows for _, language in allowed_tracks(row)
        }, key=lambda language: (0 if language == "ru" else 1, language))

        selected_audio_by_source: Dict[str, Any] = {}
        audio_rows: List[sqlite3.Row] = []
        for row in rows:
            matches = allowed_tracks(row)
            selected_track = None
            if audio_request_kind == "auto":
                preferred = sorted(
                    matches,
                    key=lambda pair: (
                        0 if pair[1] == "ru" else 1,
                        0 if isinstance(pair[0], dict) and bool(pair[0].get("default")) else 1,
                        _normalize_audio_text(_audio_track_label(pair[0])),
                    ),
                )
                selected_track = preferred[0][0] if preferred else None
            elif audio_request_kind == "language":
                if audio_request_value in allowed_audio_languages:
                    language_matches = [pair for pair in matches if pair[1] == audio_request_value]
                    language_matches.sort(
                        key=lambda pair: (
                            0 if isinstance(pair[0], dict) and bool(pair[0].get("default")) else 1,
                            _normalize_audio_text(_audio_track_label(pair[0])),
                        )
                    )
                    selected_track = language_matches[0][0] if language_matches else None
            else:
                voice_matches = [
                    pair for pair in matches
                    if _normalize_audio_text(_audio_track_label(pair[0])) == audio_request_value
                ]
                voice_matches.sort(
                    key=lambda pair: (
                        0 if pair[1] == "ru" else 1,
                        0 if isinstance(pair[0], dict) and bool(pair[0].get("default")) else 1,
                    )
                )
                selected_track = voice_matches[0][0] if voice_matches else None
            if selected_track is not None:
                audio_rows.append(row)
                selected_audio_by_source[str(row["source_id"])] = selected_track

        if not audio_rows:
            return {
                "status": "NOT_AVAILABLE",
                "errorCode": "AUDIO_NOT_AVAILABLE",
                "mediaKey": key.value,
                "requestedQuality": wanted or "Auto",
                "requestedAudio": audio_request_value,
                "availableQualities": all_qualities,
                "availableAudioLanguages": available_audio_languages,
                "source": None,
            }
        rows = audio_rows

        transport_rank = {SOURCE_HLS: 0, SOURCE_MP4: 1, SOURCE_HTTP: 2, SOURCE_P2P: 3, SOURCE_UNKNOWN: 4}
        best = sorted(
            rows,
            key=lambda row: (
                transport_rank.get(str(row["source_type"]), 9),
                -float(row["health_score"] if row["health_score"] is not None else 0.5),
                float(row["startup_latency_ms"] if row["startup_latency_ms"] is not None else 1e12),
                -float(row["last_success_at"] if row["last_success_at"] is not None else 0.0),
                str(row["source_id"]),
            ),
        )[0]
        source = {
            "sourceId": best["source_id"],
            "provider": best["provider"],
            "type": best["source_type"],
            "verificationStatus": best["verification_status"],
            "verificationMethod": best["verification_method"],
            "actualQuality": best["actual_quality"],
            "actualQualities": factual_qualities(best),
            "actualAudioTracks": _loads_list(best["actual_audio_json"]),
            "selectedAudioTrack": selected_audio_by_source.get(str(best["source_id"])),
            "manifestType": best["manifest_type"],
            "expiresAt": best["expires_at"],
            "startupLatencyMs": best["startup_latency_ms"],
            "healthScore": best["health_score"],
        }
        if include_locator:
            source["locator"] = best["locator"]
        return {
            "status": "AVAILABLE",
            "errorCode": None,
            "mediaKey": key.value,
            "requestedQuality": wanted or "Auto",
            "requestedAudio": audio_request_value,
            "availableQualities": all_qualities,
            "availableAudioLanguages": available_audio_languages,
            "source": source,
        }

    def readiness(self, *, now: Optional[float] = None) -> Dict[str, Any]:
        """Return strict-read readiness facts without network/provider calls."""
        ts = time.time() if now is None else float(now)
        self.reconcile_expired_sources(now=ts)
        with self.repository.connection() as conn:
            rows = conn.execute(
                """SELECT source_id,media_key,source_type,verification_method,actual_quality,
                          actual_qualities_json,actual_audio_json,expires_at
                   FROM playback_sources
                   WHERE verification_status='VERIFIED'"""
            ).fetchall()

        verified_rows: List[sqlite3.Row] = []
        for row in rows:
            expiry = _finite_float(row["expires_at"])
            if expiry is not None and expiry <= ts + self.expiry_margin_seconds:
                continue
            verified_rows.append(row)

        quality_sources = 0
        allowed_audio_sources = 0
        ru_sources = 0
        uk_sources = 0
        strict_auto_sources = 0
        strict_quality_sources = 0
        verified_media: set[str] = set()
        quality_media: set[str] = set()
        audio_media: set[str] = set()
        strict_auto_media: set[str] = set()
        strict_quality_media: set[str] = set()
        ru_media: set[str] = set()
        uk_media: set[str] = set()
        by_transport: Dict[str, Dict[str, int]] = {}

        for row in verified_rows:
            media_key_value = str(row["media_key"])
            verified_media.add(media_key_value)
            qualities = {_canonical_quality(value) for value in _loads_list(row["actual_qualities_json"])}
            if row["actual_quality"]:
                qualities.add(_canonical_quality(row["actual_quality"]))
            qualities.discard("")
            has_quality = bool(qualities)

            audio_languages = {
                _audio_track_language(track)
                for track in _loads_list(row["actual_audio_json"])
            }
            audio_languages &= {"ru", "uk"}
            has_allowed_audio = bool(audio_languages)

            if has_quality:
                quality_sources += 1
                quality_media.add(media_key_value)
            if has_allowed_audio:
                allowed_audio_sources += 1
                audio_media.add(media_key_value)
                strict_auto_sources += 1
                strict_auto_media.add(media_key_value)
            if "ru" in audio_languages:
                ru_sources += 1
                ru_media.add(media_key_value)
            if "uk" in audio_languages:
                uk_sources += 1
                uk_media.add(media_key_value)
            if has_quality and has_allowed_audio:
                strict_quality_sources += 1
                strict_quality_media.add(media_key_value)

            transport = str(row["source_type"] or SOURCE_UNKNOWN)
            bucket = by_transport.setdefault(transport, {
                "verified": 0, "qualityEvidence": 0, "allowedAudio": 0,
                "strictAuto": 0, "strictQuality": 0,
            })
            bucket["verified"] += 1
            if has_quality:
                bucket["qualityEvidence"] += 1
            if has_allowed_audio:
                bucket["allowedAudio"] += 1
                bucket["strictAuto"] += 1
            if has_quality and has_allowed_audio:
                bucket["strictQuality"] += 1

        verified_count = len(verified_rows)
        verified_media_count = len(verified_media)

        def pct(value: int, total: int) -> float:
            return round((100.0 * value / total), 2) if total else 0.0

        return {
            "schemaVersion": SCHEMA_VERSION,
            "verifiedSources": verified_count,
            "verifiedMedia": verified_media_count,
            "qualityEvidenceSources": quality_sources,
            "qualityEvidenceMedia": len(quality_media),
            "qualityEvidenceSourcePct": pct(quality_sources, verified_count),
            "qualityEvidenceMediaPct": pct(len(quality_media), verified_media_count),
            "allowedAudioSources": allowed_audio_sources,
            "allowedAudioMedia": len(audio_media),
            "allowedAudioSourcePct": pct(allowed_audio_sources, verified_count),
            "allowedAudioMediaPct": pct(len(audio_media), verified_media_count),
            "strictAutoSources": strict_auto_sources,
            "strictAutoMedia": len(strict_auto_media),
            "strictAutoMediaPct": pct(len(strict_auto_media), verified_media_count),
            "strictQualitySources": strict_quality_sources,
            "strictQualityMedia": len(strict_quality_media),
            "strictQualityMediaPct": pct(len(strict_quality_media), verified_media_count),
            "ruSources": ru_sources,
            "ruMedia": len(ru_media),
            "ukSources": uk_sources,
            "ukMedia": len(uk_media),
            "byTransport": by_transport,
        }

    def stats(self, *, now: Optional[float] = None) -> Dict[str, Any]:
        ts = time.time() if now is None else float(now)
        self.reconcile_expired_sources(now=ts)
        with self.repository.connection() as conn:
            total = int(conn.execute("SELECT COUNT(*) FROM playback_availability").fetchone()[0])
            source_total = int(conn.execute("SELECT COUNT(*) FROM playback_sources").fetchone()[0])
            statuses = {str(r[0]): int(r[1]) for r in conn.execute(
                "SELECT availability_status,COUNT(*) FROM playback_availability GROUP BY availability_status"
            )}
            kinds = {str(r[0]): int(r[1]) for r in conn.execute(
                "SELECT media_kind,COUNT(*) FROM playback_availability GROUP BY media_kind"
            )}
            source_types = {str(r[0]): int(r[1]) for r in conn.execute(
                "SELECT source_type,COUNT(*) FROM playback_sources GROUP BY source_type"
            )}
            providers = {str(r[0]): int(r[1]) for r in conn.execute(
                "SELECT provider,COUNT(*) FROM playback_sources GROUP BY provider ORDER BY COUNT(*) DESC"
            )}
            verified = int(conn.execute(
                "SELECT COUNT(*) FROM playback_sources WHERE verification_status='VERIFIED'"
            ).fetchone()[0])
            available = int(conn.execute(
                "SELECT COUNT(*) FROM playback_availability WHERE available=1"
            ).fetchone()[0])
        return {
            "schemaVersion": SCHEMA_VERSION,
            "objects": total,
            "availableObjects": available,
            "sources": source_total,
            "verifiedSources": verified,
            "statuses": statuses,
            "kinds": kinds,
            "sourceTypes": source_types,
            "providers": providers,
        }

    def invariant_errors(self, *, now: Optional[float] = None, limit: int = 100) -> List[Dict[str, Any]]:
        ts = time.time() if now is None else float(now)
        self.reconcile_expired_sources(now=ts)
        errors: List[Dict[str, Any]] = []
        with self.repository.connection() as conn:
            queries = [
                (
                    "AVAILABLE_WITHOUT_VERIFIED_SOURCE",
                    "SELECT media_key FROM playback_availability WHERE available=1 AND verified_source_count=0",
                    (),
                ),
                (
                    "EXPIRED_AND_AVAILABLE",
                    """SELECT DISTINCT a.media_key FROM playback_availability a
                       JOIN playback_sources s ON s.media_key=a.media_key
                       WHERE a.available=1 AND s.verification_status='VERIFIED'
                         AND s.expires_at IS NOT NULL AND s.expires_at<=?""",
                    (ts + self.expiry_margin_seconds,),
                ),
                (
                    "EPISODE_IDENTITY_INVALID",
                    "SELECT media_key FROM playback_availability WHERE media_kind='EPISODE' AND (season_number IS NULL OR episode_number IS NULL OR season_number<=0 OR episode_number<=0)",
                    (),
                ),
                (
                    "NON_EPISODE_HAS_PARTS",
                    "SELECT media_key FROM playback_availability WHERE media_kind!='EPISODE' AND (season_number IS NOT NULL OR episode_number IS NOT NULL)",
                    (),
                ),
                (
                    "ACTUAL_WITHOUT_EVIDENCE",
                    """SELECT source_id FROM playback_sources
                       WHERE (actual_quality IS NOT NULL OR actual_qualities_json!='[]' OR actual_audio_json!='[]')
                         AND (verification_method='NONE' OR verification_status='DISCOVERED')""",
                    (),
                ),
                (
                    "VERIFIED_WITHOUT_EVIDENCE",
                    "SELECT source_id FROM playback_sources WHERE verification_status='VERIFIED' AND verification_method='NONE'",
                    (),
                ),
            ]
            for code, sql, params in queries:
                for row in conn.execute(sql, params).fetchmany(max(1, int(limit))):
                    errors.append({"code": code, "id": row[0]})
                    if len(errors) >= limit:
                        return errors
            duplicates = conn.execute(
                """SELECT media_key,provider,locator_hash,COUNT(*) c FROM playback_sources
                   GROUP BY media_key,provider,locator_hash HAVING c>1 LIMIT ?""",
                (max(1, int(limit)),),
            ).fetchall()
            for row in duplicates:
                errors.append({
                    "code": "DUPLICATE_SOURCE_FINGERPRINT",
                    "id": f"{row['media_key']}:{row['provider']}:{row['locator_hash']}",
                })
                if len(errors) >= limit:
                    break
        return errors


# Backwards-friendly name for callers that want one object rather than explicit
# repository/service construction.
PlaybackAvailabilityIndex = PlaybackAvailabilityService
