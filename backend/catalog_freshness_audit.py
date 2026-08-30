#!/usr/bin/env python3
"""Read-only catalog freshness audit.

The audit never creates or changes a database and never downloads media. It
uses SQLite's read-only URI mode and, unless disabled, performs one GET of the
local sync-status endpoint.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, Optional
from urllib.parse import quote

DIR = Path(__file__).resolve().parent
DEFAULT_DB_PATH = DIR / "catalog.db"
DEFAULT_STATUS_URL = "http://127.0.0.1:8888/api/catalog/sync-status"
MIN_SYNC_INTERVAL_SECONDS = 300


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _parse_timestamp(value: Any) -> Optional[datetime]:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed
    except (TypeError, ValueError, OverflowError):
        return None


def _age_seconds(value: Any) -> Optional[float]:
    parsed = _parse_timestamp(value)
    if parsed is None:
        return None
    return max(0.0, datetime.now(timezone.utc).timestamp() - parsed.timestamp())


def _safe_int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError, OverflowError):
        return default


def _quote_identifier(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


def _read_only_connection(path: Path) -> sqlite3.Connection:
    uri = f"file:{quote(str(path.resolve()), safe='/\\:')}?mode=ro"
    conn = sqlite3.connect(uri, uri=True, timeout=5.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA query_only=ON")
    return conn


def audit_database(path: Path) -> Dict[str, Any]:
    path = Path(path)
    result: Dict[str, Any] = {
        "path": str(path),
        "exists": path.exists(),
        "read_only": True,
    }
    if not path.exists():
        result["error"] = "database_not_found"
        return result
    if not path.is_file():
        result["error"] = "database_is_not_a_file"
        return result

    try:
        with _read_only_connection(path) as conn:
            tables = {
                str(row[0])
                for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
            }
            result["tables"] = sorted(tables)
            if "movies" not in tables:
                result["error"] = "movies_table_not_found"
                return result

            columns = {
                str(row[1])
                for row in conn.execute("PRAGMA table_info(movies)")
            }
            result["columns"] = sorted(columns)
            total = _safe_int(conn.execute("SELECT COUNT(*) FROM movies").fetchone()[0])
            by_type = {
                str(row[0] or "<null>"): _safe_int(row[1])
                for row in conn.execute(
                    "SELECT media_type, COUNT(*) FROM movies GROUP BY media_type ORDER BY media_type"
                )
            }
            result["catalog_size"] = total
            result["by_media_type"] = by_type

            if "tmdb_id" in columns and "media_type" in columns:
                duplicate_sql = """
                    SELECT COALESCE(SUM(item_count - 1), 0), COUNT(*)
                    FROM (
                        SELECT media_type, tmdb_id, COUNT(*) AS item_count
                        FROM movies
                        WHERE tmdb_id IS NOT NULL AND tmdb_id > 0
                        GROUP BY media_type, tmdb_id
                        HAVING COUNT(*) > 1
                    )
                """
                duplicate_row = conn.execute(duplicate_sql).fetchone()
                result["duplicate_rows"] = _safe_int(duplicate_row[0])
                result["duplicate_groups"] = _safe_int(duplicate_row[1])
                result["invalid_tmdb_id_rows"] = _safe_int(
                    conn.execute(
                        "SELECT COUNT(*) FROM movies WHERE tmdb_id IS NULL OR tmdb_id <= 0"
                    ).fetchone()[0]
                )
            else:
                result["duplicate_rows"] = None
                result["duplicate_groups"] = None
                result["invalid_tmdb_id_rows"] = None

            release_info: Dict[str, Any] = {}
            if "year" in columns:
                year_rows = conn.execute(
                    """
                    SELECT COALESCE(media_type, '<null>'), MAX(year)
                    FROM movies
                    WHERE year IS NOT NULL AND year > 0
                    GROUP BY media_type
                    ORDER BY media_type
                    """
                ).fetchall()
                release_info["newest_year_by_media_type"] = {
                    str(row[0]): _safe_int(row[1]) for row in year_rows
                }
                release_info["unknown_year_rows"] = _safe_int(
                    conn.execute(
                        "SELECT COUNT(*) FROM movies WHERE year IS NULL OR year <= 0"
                    ).fetchone()[0]
                )
                current_year = datetime.now(timezone.utc).year
                release_info["future_year_rows"] = _safe_int(
                    conn.execute(
                        "SELECT COUNT(*) FROM movies WHERE year > ?",
                        (current_year,),
                    ).fetchone()[0]
                )
            else:
                release_info["newest_year_by_media_type"] = None

            date_columns = [
                name for name in ("release_date", "first_air_date", "primary_release_date")
                if name in columns
            ]
            release_info["date_columns"] = date_columns
            release_info["newest_dates"] = {}
            for name in date_columns:
                quoted = _quote_identifier(name)
                value = conn.execute(
                    f"SELECT MAX({quoted}) FROM movies WHERE {quoted} IS NOT NULL AND {quoted} != ''"
                ).fetchone()[0]
                release_info["newest_dates"][name] = value
            result["release_data"] = release_info
    except (OSError, sqlite3.Error) as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"
    return result


def fetch_status(url: str, timeout: float) -> Dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/json"},
        method="GET",
    )
    try:
        # The default endpoint is localhost; bypass ambient HTTP proxy
        # settings so the audit observes the local daemon directly.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=timeout) as response:
            payload = json.loads(response.read(1_000_000).decode("utf-8"))
        if not isinstance(payload, dict):
            return {"available": False, "error": "status_not_an_object", "url": url}
        return {"available": True, "url": url, "payload": payload}
    except (urllib.error.URLError, TimeoutError, ValueError, OSError) as exc:
        return {"available": False, "url": url, "error": f"{type(exc).__name__}: {exc}"}


def _legacy_success_timestamp(payload: Dict[str, Any]) -> tuple[Optional[str], str]:
    if "last_success_at" in payload:
        return payload.get("last_success_at"), "reported"
    if payload.get("last_finished_at") and not payload.get("last_error"):
        return payload.get("last_finished_at"), "inferred_from_legacy_status"
    return None, "unavailable"


def audit_sync(status_result: Dict[str, Any], default_interval: int = MIN_SYNC_INTERVAL_SECONDS) -> Dict[str, Any]:
    if not status_result.get("available"):
        return {
            "available": False,
            "url": status_result.get("url"),
            "error": status_result.get("error"),
            "overdue": None,
        }

    payload = status_result.get("payload") or {}
    interval = max(
        MIN_SYNC_INTERVAL_SECONDS,
        _safe_int(
            payload.get("sync_interval_seconds", payload.get("interval_seconds", default_interval)),
            default_interval,
        ),
    )
    success_at, success_source = _legacy_success_timestamp(payload)
    success_age = _age_seconds(success_at)
    # Calculate from timestamps instead of trusting an optional server-derived
    # flag, while retaining the server flag for comparison.
    overdue = success_age is None or success_age > interval
    return {
        "available": True,
        "url": status_result.get("url"),
        "running": bool(payload.get("running")),
        "worker_alive": payload.get("worker_alive"),
        "last_started_at": payload.get("last_started_at"),
        "last_finished_at": payload.get("last_finished_at"),
        "last_finished_age_seconds": _age_seconds(payload.get("last_finished_at")),
        "last_success_at": success_at,
        "last_success_source": success_source,
        "last_success_age_seconds": success_age,
        "last_seen": payload.get("last_seen"),
        "last_inserted": payload.get("last_inserted"),
        "last_updated": payload.get("last_updated"),
        "last_error": payload.get("last_error"),
        "last_feed_errors": payload.get("last_feed_errors", []),
        "consecutive_failures": payload.get("consecutive_failures"),
        "interval_seconds": interval,
        "overdue": overdue,
        "reported_overdue": payload.get("sync_overdue", payload.get("overdue")),
    }


def build_audit(db_path: Path, status_url: str, use_http: bool, timeout: float) -> Dict[str, Any]:
    status_result = fetch_status(status_url, timeout) if use_http else {
        "available": False,
        "url": status_url,
        "error": "status_probe_disabled",
    }
    return {
        "generated_at": _now(),
        "database": audit_database(db_path),
        "sync": audit_sync(status_result),
    }


def _format_age(value: Any) -> str:
    if value is None:
        return "unavailable"
    return f"{float(value):.1f}s"


def print_human_report(report: Dict[str, Any]) -> None:
    database = report["database"]
    sync = report["sync"]
    print("Movia catalog freshness audit")
    print(f"generated_at: {report['generated_at']}")
    print(f"database: {database['path']} ({'present' if database.get('exists') else 'missing'})")
    if database.get("error"):
        print(f"database_error: {database['error']}")
    elif "catalog_size" in database:
        print(f"catalog_size: {database['catalog_size']}")
        print(f"by_media_type: {json.dumps(database.get('by_media_type', {}), ensure_ascii=False, sort_keys=True)}")
        release_data = database.get("release_data") or {}
        print(
            "newest_release_years: "
            f"{json.dumps(release_data.get('newest_year_by_media_type'), ensure_ascii=False, sort_keys=True)}"
        )
        print(f"newest_release_dates: {json.dumps(release_data.get('newest_dates', {}), ensure_ascii=False)}")
        print(f"unknown_year_rows: {release_data.get('unknown_year_rows', 'unavailable')}")
        print(f"future_year_rows: {release_data.get('future_year_rows', 'unavailable')}")
        print(f"duplicate_groups: {database.get('duplicate_groups')}")
        print(f"duplicate_rows: {database.get('duplicate_rows')}")
        print(f"invalid_tmdb_id_rows: {database.get('invalid_tmdb_id_rows')}")

    print(f"sync_status_available: {sync.get('available')}")
    if sync.get("available"):
        print(f"sync_running: {sync.get('running')}")
        print(f"worker_alive: {sync.get('worker_alive')}")
        print(f"last_success_at: {sync.get('last_success_at')} ({sync.get('last_success_source')})")
        print(f"last_success_age: {_format_age(sync.get('last_success_age_seconds'))}")
        print(f"last_finished_at: {sync.get('last_finished_at')}")
        print(f"last_seen/inserted/updated: {sync.get('last_seen')}/{sync.get('last_inserted')}/{sync.get('last_updated')}")
        print(f"last_error: {sync.get('last_error')}")
        print(f"sync_overdue: {sync.get('overdue')}")
    else:
        print(f"sync_status_error: {sync.get('error')}")


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=DEFAULT_DB_PATH, help="catalog.db path (read-only)")
    parser.add_argument("--status-url", default=DEFAULT_STATUS_URL, help="GET-only sync status URL")
    parser.add_argument("--no-http", action="store_true", help="do not probe the status endpoint")
    parser.add_argument("--timeout", type=float, default=2.0, help="status probe timeout in seconds")
    parser.add_argument("--json", action="store_true", dest="as_json", help="emit JSON instead of text")
    parser.add_argument("--strict", action="store_true", help="return 1 when the audit is overdue or unavailable")
    args = parser.parse_args(list(argv) if argv is not None else None)

    report = build_audit(args.db, args.status_url, not args.no_http, max(0.1, args.timeout))
    if args.as_json:
        print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        print_human_report(report)

    if not args.strict:
        return 0
    database_ok = not report["database"].get("error")
    sync = report["sync"]
    return 0 if database_ok and sync.get("available") and not sync.get("overdue") else 1


if __name__ == "__main__":
    sys.exit(main())
