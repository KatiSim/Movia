#!/usr/bin/env python3
"""Fail-closed validation for a mounted Movia cloud catalog snapshot."""
from __future__ import annotations

import json
import os
import sqlite3
from pathlib import Path

DIR = Path(__file__).resolve().parent
DB_PATH = Path(os.environ.get("MOVIA_CATALOG_DB", str(DIR / "catalog.db")))
MIN_ROWS = max(1, int(os.environ.get("MOVIA_MIN_CATALOG_ROWS", "1")))
REQUIRED_COLUMNS = {
    "id",
    "tmdb_id",
    "media_type",
    "title",
    "original_title",
    "year",
    "streams",
    "vote_count",
    "vote_average",
    "metadata_source",
    "metadata_updated_at",
}


def validate_catalog(path: Path = DB_PATH, min_rows: int = MIN_ROWS) -> dict:
    if not path.is_file():
        raise RuntimeError(f"catalog_missing:{path}")
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=10.0)
    try:
        check = str(conn.execute("PRAGMA quick_check").fetchone()[0])
        if check.lower() != "ok":
            raise RuntimeError(f"catalog_quick_check_failed:{check}")
        table = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='movies'"
        ).fetchone()
        if not table:
            raise RuntimeError("catalog_movies_table_missing")
        columns = {str(row[1]) for row in conn.execute("PRAGMA table_info(movies)")}
        missing = sorted(REQUIRED_COLUMNS - columns)
        if missing:
            raise RuntimeError("catalog_columns_missing:" + ",".join(missing))
        rows = int(conn.execute("SELECT COUNT(*) FROM movies").fetchone()[0])
        if rows < max(1, int(min_rows)):
            raise RuntimeError(f"catalog_too_small:{rows}<{max(1, int(min_rows))}")
        return {
            "status": "ok",
            "path": str(path),
            "rows": rows,
            "requiredColumns": len(REQUIRED_COLUMNS),
            "quickCheck": check,
        }
    finally:
        conn.close()


def main() -> int:
    try:
        print(json.dumps(validate_catalog(), ensure_ascii=False, separators=(",", ":")))
        return 0
    except Exception as exc:
        print(json.dumps({
            "status": "error",
            "error": str(exc),
            "path": str(DB_PATH),
        }, ensure_ascii=False, separators=(",", ":")))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
