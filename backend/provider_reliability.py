#!/usr/bin/env python3
"""Persistent, bounded reliability memory for Movia stream providers.

Only non-sensitive aggregate outcome metadata is stored. Provider payloads,
URLs, headers, cookies and credentials never enter this database.
"""
from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

DIR = Path(__file__).resolve().parent
DB_PATH = DIR / "stream_cache" / "provider_health.db"
_LOCK = threading.RLock()

DEFAULT_RELIABILITY = 0.70
HARD_FAILURE_THRESHOLD = 3
BASE_COOLDOWN_SECONDS = 60
MAX_COOLDOWN_SECONDS = 15 * 60
EWMA_ALPHA = 0.25

_HARD_FAILURE_STATUSES = {
    "NETWORK_ERROR",
    "PROVIDER_TIMEOUT",
    "RATE_LIMIT",
    "INVALID_RESPONSE",
    "DB_ERROR",
    "PROVIDER_ERROR",
}

_ALIASES = {
    "collaps": "collaps",
    "collaps.org": "collaps",
    "zona": "zona",
    "zona api": "zona",
    "zona_api": "zona",
    "rutor": "rutor",
    "apibay": "apibay",
    "the pirate bay": "apibay",
    "piratebay": "apibay",
    "yts": "yts",
    "eztv": "eztv",
    "nyaa": "nyaa",
    "archive.org": "archive",
    "archive": "archive",
}


def normalize_provider(value: Any) -> str:
    raw = str(value or "").strip().casefold()
    if not raw:
        return "unknown"
    return _ALIASES.get(raw, raw[:96])


def _connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(DB_PATH), timeout=2.0)
    try:
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=2000")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS provider_health (
                provider TEXT PRIMARY KEY,
                reliability REAL NOT NULL,
                successes INTEGER NOT NULL DEFAULT 0,
                hard_failures INTEGER NOT NULL DEFAULT 0,
                soft_misses INTEGER NOT NULL DEFAULT 0,
                consecutive_failures INTEGER NOT NULL DEFAULT 0,
                consecutive_misses INTEGER NOT NULL DEFAULT 0,
                ewma_latency_ms REAL,
                disabled_until REAL NOT NULL DEFAULT 0,
                last_status TEXT NOT NULL DEFAULT 'UNKNOWN',
                updated_at REAL NOT NULL DEFAULT 0
            )
            """
        )
        return conn
    except Exception:
        conn.close()
        raise


def _default_snapshot(provider: str) -> Dict[str, Any]:
    return {
        "provider": provider,
        "reliability": DEFAULT_RELIABILITY,
        "successes": 0,
        "hard_failures": 0,
        "soft_misses": 0,
        "consecutive_failures": 0,
        "consecutive_misses": 0,
        "ewma_latency_ms": None,
        "disabled_until": 0.0,
        "last_status": "UNKNOWN",
        "updated_at": 0.0,
    }


def snapshot(provider: Any, *, now: Optional[float] = None) -> Dict[str, Any]:
    key = normalize_provider(provider)
    with _LOCK:
        conn = _connect()
        try:
            row = conn.execute(
                "SELECT * FROM provider_health WHERE provider=?", (key,)
            ).fetchone()
        finally:
            conn.close()
    result = _default_snapshot(key) if row is None else dict(row)
    current = time.time() if now is None else float(now)
    result["disabled"] = float(result.get("disabled_until") or 0.0) > current
    result["reliability"] = min(max(float(result.get("reliability") or DEFAULT_RELIABILITY), 0.0), 1.0)
    return result


def should_call(provider: Any, *, now: Optional[float] = None) -> bool:
    """Return False only while a provider's bounded cooldown is active.

    Once the cooldown expires the next call is a half-open recovery probe. A
    successful probe clears failure streaks; a new hard failure reopens it.
    """
    return not bool(snapshot(provider, now=now).get("disabled"))


def _latency_target(latency_ms: Optional[float]) -> float:
    if latency_ms is None:
        return 0.85
    value = max(0.0, float(latency_ms))
    if value <= 1_000:
        return 1.00
    if value <= 2_500:
        return 0.90
    if value <= 4_000:
        return 0.75
    if value <= 6_000:
        return 0.55
    return 0.35


def observe(
    provider: Any,
    status: str,
    *,
    latency_ms: Optional[float] = None,
    now: Optional[float] = None,
) -> Dict[str, Any]:
    """Record one aggregate provider outcome and return the updated snapshot."""
    key = normalize_provider(provider)
    current = time.time() if now is None else float(now)
    normalized_status = str(status or "UNKNOWN").strip().upper() or "UNKNOWN"

    with _LOCK:
        conn = _connect()
        try:
            row = conn.execute(
                "SELECT * FROM provider_health WHERE provider=?", (key,)
            ).fetchone()
            state = _default_snapshot(key) if row is None else dict(row)

            reliability = min(max(float(state.get("reliability") or DEFAULT_RELIABILITY), 0.0), 1.0)
            successes = int(state.get("successes") or 0)
            hard_failures = int(state.get("hard_failures") or 0)
            soft_misses = int(state.get("soft_misses") or 0)
            consecutive_failures = int(state.get("consecutive_failures") or 0)
            consecutive_misses = int(state.get("consecutive_misses") or 0)
            disabled_until = float(state.get("disabled_until") or 0.0)
            old_latency = state.get("ewma_latency_ms")
            ewma_latency = float(old_latency) if old_latency is not None else None

            if normalized_status == "OK":
                successes += 1
                consecutive_failures = 0
                consecutive_misses = 0
                disabled_until = 0.0
                target = _latency_target(latency_ms)
                reliability = (1.0 - EWMA_ALPHA) * reliability + EWMA_ALPHA * target
                if latency_ms is not None:
                    value = max(0.0, float(latency_ms))
                    ewma_latency = value if ewma_latency is None else (
                        (1.0 - EWMA_ALPHA) * ewma_latency + EWMA_ALPHA * value
                    )
            elif normalized_status in _HARD_FAILURE_STATUSES:
                hard_failures += 1
                consecutive_failures += 1
                consecutive_misses = 0
                reliability = max(0.05, reliability * 0.65)
                if consecutive_failures >= HARD_FAILURE_THRESHOLD:
                    exponent = min(4, consecutive_failures - HARD_FAILURE_THRESHOLD)
                    cooldown = min(MAX_COOLDOWN_SECONDS, BASE_COOLDOWN_SECONDS * (2 ** exponent))
                    disabled_until = max(disabled_until, current + cooldown)
            elif normalized_status == "NO_RESULTS":
                # A valid empty response proves the provider infrastructure is
                # reachable; it says nothing about coverage for other titles.
                soft_misses += 1
                consecutive_misses += 1
                consecutive_failures = 0
                disabled_until = 0.0
            else:
                # Ambiguous/identity outcomes are content-level facts, not
                # infrastructure failures. Preserve reliability unchanged.
                consecutive_misses = 0

            conn.execute(
                """
                INSERT INTO provider_health(
                    provider,reliability,successes,hard_failures,soft_misses,
                    consecutive_failures,consecutive_misses,ewma_latency_ms,
                    disabled_until,last_status,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(provider) DO UPDATE SET
                    reliability=excluded.reliability,
                    successes=excluded.successes,
                    hard_failures=excluded.hard_failures,
                    soft_misses=excluded.soft_misses,
                    consecutive_failures=excluded.consecutive_failures,
                    consecutive_misses=excluded.consecutive_misses,
                    ewma_latency_ms=excluded.ewma_latency_ms,
                    disabled_until=excluded.disabled_until,
                    last_status=excluded.last_status,
                    updated_at=excluded.updated_at
                """,
                (
                    key, reliability, successes, hard_failures, soft_misses,
                    consecutive_failures, consecutive_misses, ewma_latency,
                    disabled_until, normalized_status, current,
                ),
            )
            conn.commit()
        finally:
            conn.close()
    return snapshot(key, now=current)


def annotate_streams(streams: Iterable[Dict[str, Any]], provider: Any = None) -> List[Dict[str, Any]]:
    """Attach discovery-only aggregate metadata; never alter playback health.

    Discovery reliability answers whether another provider lookup is worthwhile.
    It is intentionally separate from the health of a concrete resolved media URL.
    """
    result: List[Dict[str, Any]] = []
    for raw in streams or []:
        if not isinstance(raw, dict):
            continue
        item = dict(raw)
        key = provider or item.get("provider") or item.get("source") or "unknown"
        state = snapshot(key)
        item["discovery_reliability"] = float(state["reliability"])
        item["discovery_failure_count"] = int(state.get("consecutive_failures") or 0)
        result.append(item)
    return result


def snapshots(providers: Iterable[Any], *, now: Optional[float] = None) -> List[Dict[str, Any]]:
    return [snapshot(provider, now=now) for provider in providers]
