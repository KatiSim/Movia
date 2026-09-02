#!/usr/bin/env python3
"""Canonical catalog schema and Russian text normalization.

This module is deliberately independent from playback resolution.  Metadata
writers and readers use the same normalization contract and additive schema
migration; the current catalog database is never rebuilt.
"""
from __future__ import annotations

import sqlite3
import threading
import unicodedata
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Optional

from catalog_localization import (
    choose_localized_ru_title,
    clean_title,
    is_russian_display_title,
    parse_alternative_titles,
)

SCHEMA_VERSION = 4
NORMALIZATION_VERSION = 1
_DASHES = frozenset("‐‑‒–—―−﹘﹣－")
_SCHEMA_LOCK = threading.RLock()


def normalize_ru_text(value: Any) -> str:
    """Return one stable, punctuation-tolerant search representation.

    No translation or title-specific aliasing is performed.  Punctuation and
    dash variants become word separators, digits and letters are preserved,
    Cyrillic ё is canonicalized to е, and whitespace is collapsed.
    """
    text = unicodedata.normalize("NFKC", str(value or "")).casefold()
    out: list[str] = []
    pending_space = False
    for char in text:
        if char in _DASHES or char == "_":
            pending_space = True
            continue
        category = unicodedata.category(char)
        if char.isspace() or category.startswith("P") or category.startswith("S"):
            pending_space = True
            continue
        if pending_space and out:
            out.append(" ")
        pending_space = False
        out.append("е" if char == "ё" else char)
    return " ".join("".join(out).strip().split())


def prefix_successor(prefix: str) -> str:
    """Return an exclusive BINARY upper bound for a non-empty prefix."""
    if not prefix:
        return "U0010ffff"
    chars = list(prefix)
    for index in range(len(chars) - 1, -1, -1):
        codepoint = ord(chars[index])
        if codepoint < 0x10FFFF:
            return "".join(chars[:index]) + chr(codepoint + 1)
    return prefix + "U0010ffff"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def connect_catalog(path: str | Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(path), timeout=30.0)
    conn.execute("PRAGMA busy_timeout=30000")
    conn.row_factory = sqlite3.Row
    return conn


def _meta_int(conn: sqlite3.Connection, key: str, default: int = 0) -> int:
    row = conn.execute("SELECT value FROM catalog_meta WHERE key=?", (key,)).fetchone()
    try:
        return int(row[0]) if row else default
    except (TypeError, ValueError):
        return default


def get_revision(conn: sqlite3.Connection) -> int:
    return _meta_int(conn, "catalog_revision", 1)


def bump_revision(conn: sqlite3.Connection) -> int:
    conn.execute(
        "INSERT OR IGNORE INTO catalog_meta(key,value) VALUES ('catalog_revision','0')"
    )
    conn.execute(
        "UPDATE catalog_meta SET value=CAST(value AS INTEGER)+1 "
        "WHERE key='catalog_revision'"
    )
    return get_revision(conn)


def set_meta(conn: sqlite3.Connection, key: str, value: Any) -> None:
    conn.execute(
        "INSERT INTO catalog_meta(key,value) VALUES (?,?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, str(value)),
    )


def get_cursor(conn: sqlite3.Connection, feed_key: str) -> int:
    row = conn.execute(
        "SELECT next_page FROM discovery_state WHERE feed_key=?", (feed_key,)
    ).fetchone()
    try:
        return max(2, int(row[0])) if row else 2
    except (TypeError, ValueError):
        return 2


def set_cursor(
    conn: sqlite3.Connection,
    feed_key: str,
    media_type: str,
    endpoint: str,
    next_page: int,
    total_pages: int = 0,
) -> None:
    conn.execute(
        """
        INSERT INTO discovery_state
            (feed_key,media_type,endpoint,next_page,last_total_pages,updated_at)
        VALUES (?,?,?,?,?,?)
        ON CONFLICT(feed_key) DO UPDATE SET
            media_type=excluded.media_type,
            endpoint=excluded.endpoint,
            next_page=excluded.next_page,
            last_total_pages=excluded.last_total_pages,
            updated_at=excluded.updated_at
        """,
        (
            feed_key,
            media_type,
            endpoint,
            max(1, int(next_page)),
            max(0, int(total_pages)),
            utc_now(),
        ),
    )


def upsert_trigram_index(
    conn: sqlite3.Connection,
    row_id: int,
    normalized_title: str,
    normalized_original_title: str,
) -> None:
    """Keep the optional FTS5 trigram index synchronized for substring search."""
    try:
        conn.execute(
            "DELETE FROM movies_search_trigram WHERE rowid=?",
            (int(row_id),),
        )
        conn.execute(
            "INSERT INTO movies_search_trigram"
            "(rowid,movie_id,normalized_ru_title,normalized_original_title)"
            " VALUES (?,?,?,?)",
            (
                int(row_id),
                int(row_id),
                normalized_title,
                normalized_original_title,
            ),
        )
    except sqlite3.OperationalError:
        # The schema bootstrap creates this table before any writer can use it.
        # Keep older direct importer callers safe during a rolling upgrade.
        pass


def ensure_schema(path: str | Path) -> dict[str, Any]:
    """Apply only additive migration and backfill the searchable columns."""
    db_path = Path(path)
    with _SCHEMA_LOCK:
        with connect_catalog(db_path) as conn:
            columns = {
                str(row[1])
                for row in conn.execute("PRAGMA table_info(movies)").fetchall()
            }
            added: list[str] = []
            if "normalized_ru_title" not in columns:
                conn.execute(
                    "ALTER TABLE movies ADD COLUMN normalized_ru_title TEXT NOT NULL DEFAULT ''"
                )
                added.append("normalized_ru_title")
            if "normalized_original_title" not in columns:
                conn.execute(
                    "ALTER TABLE movies ADD COLUMN normalized_original_title TEXT NOT NULL DEFAULT ''"
                )
                added.append("normalized_original_title")
            if "updated_at" not in columns:
                conn.execute(
                    "ALTER TABLE movies ADD COLUMN updated_at TEXT NOT NULL DEFAULT ''"
                )
                added.append("updated_at")
            if "localized_ru_title" not in columns:
                conn.execute(
                    "ALTER TABLE movies ADD COLUMN localized_ru_title TEXT NOT NULL DEFAULT ''"
                )
                added.append("localized_ru_title")
            if "alternative_titles" not in columns:
                conn.execute(
                    "ALTER TABLE movies ADD COLUMN alternative_titles TEXT NOT NULL DEFAULT '[]'"
                )
                added.append("alternative_titles")
            if "localization_source" not in columns:
                conn.execute(
                    "ALTER TABLE movies ADD COLUMN localization_source TEXT NOT NULL DEFAULT ''"
                )
                added.append("localization_source")
            if "localization_updated_at" not in columns:
                conn.execute(
                    "ALTER TABLE movies ADD COLUMN localization_updated_at TEXT NOT NULL DEFAULT ''"
                )
                added.append("localization_updated_at")
            if "imdb_id" not in columns:
                conn.execute(
                    "ALTER TABLE movies ADD COLUMN imdb_id TEXT NOT NULL DEFAULT ''"
                )
                added.append("imdb_id")
            if "creators" not in columns:
                conn.execute(
                    "ALTER TABLE movies ADD COLUMN creators TEXT NOT NULL DEFAULT '[]'"
                )
                added.append("creators")
            if "metadata_source" not in columns:
                conn.execute(
                    "ALTER TABLE movies ADD COLUMN metadata_source TEXT NOT NULL DEFAULT ''"
                )
                added.append("metadata_source")
            if "metadata_updated_at" not in columns:
                conn.execute(
                    "ALTER TABLE movies ADD COLUMN metadata_updated_at TEXT NOT NULL DEFAULT ''"
                )
                added.append("metadata_updated_at")

            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS catalog_meta(
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS discovery_state(
                    feed_key TEXT PRIMARY KEY,
                    media_type TEXT NOT NULL,
                    endpoint TEXT NOT NULL,
                    next_page INTEGER NOT NULL DEFAULT 2,
                    last_total_pages INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT NOT NULL
                )
                """
            )
            conn.execute(
                "INSERT OR IGNORE INTO catalog_meta(key,value) VALUES "
                "('schema_version',?),('normalization_version',?),"
                "('catalog_revision','1')",
                (SCHEMA_VERSION, NORMALIZATION_VERSION),
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_movies_normalized_ru_title "
                "ON movies(normalized_ru_title COLLATE BINARY)"
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_movies_normalized_original_title "
                "ON movies(normalized_original_title COLLATE BINARY)"
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_movies_updated_at "
                "ON movies(updated_at)"
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_movies_localized_ru_title "
                "ON movies(localized_ru_title COLLATE BINARY)"
            )
            conn.execute(
                "CREATE VIRTUAL TABLE IF NOT EXISTS movies_search_trigram "
                "USING fts5(movie_id UNINDEXED, normalized_ru_title, "
                "normalized_original_title, tokenize='trigram')"
            )

            updated = 0
            rows = conn.execute(
                """
                SELECT id,title,original_title,localized_ru_title,
                       alternative_titles,localization_source,localization_updated_at,
                       created_at
                FROM movies
                WHERE normalized_ru_title='' OR normalized_ru_title IS NULL
                   OR normalized_original_title='' OR normalized_original_title IS NULL
                   OR updated_at='' OR updated_at IS NULL
                   OR localized_ru_title='' OR localized_ru_title IS NULL
                   OR alternative_titles='' OR alternative_titles IS NULL
                ORDER BY id
                """
            ).fetchall()
            for start in range(0, len(rows), 1000):
                batch = rows[start:start + 1000]
                conn.executemany(
                    """
                    UPDATE movies SET
                        localized_ru_title=?,
                        alternative_titles=?,
                        localization_source=?,
                        localization_updated_at=CASE
                            WHEN ?!='' THEN COALESCE(NULLIF(localization_updated_at,''),?)
                            ELSE localization_updated_at END,
                        normalized_ru_title=?,
                        normalized_original_title=?,
                        updated_at=CASE WHEN updated_at='' OR updated_at IS NULL
                                        THEN COALESCE(created_at,?) ELSE updated_at END
                    WHERE id=?
                    """,
                    [
                        (
                            choose_localized_ru_title(
                                localized_ru_title=row["localized_ru_title"],
                                title=row["title"],
                                original_title=row["original_title"],
                                alternative_titles=row["alternative_titles"],
                            ) or "",
                            json_dumps_alternative_titles(row["alternative_titles"]),
                            (
                                str(row["localization_source"] or "").strip()
                                or (
                                    "legacy_cyrillic"
                                    if is_russian_display_title(row["title"], row["original_title"])
                                    else ""
                                )
                            ),
                            choose_localized_ru_title(
                                localized_ru_title=row["localized_ru_title"],
                                title=row["title"],
                                original_title=row["original_title"],
                                alternative_titles=row["alternative_titles"],
                            ) or "",
                            utc_now(),
                            normalize_ru_text(
                                choose_localized_ru_title(
                                    localized_ru_title=row["localized_ru_title"],
                                    title=row["title"],
                                    original_title=row["original_title"],
                                    alternative_titles=row["alternative_titles"],
                                ) or ""
                            ),
                            normalize_ru_text(row["original_title"]),
                            utc_now(),
                            int(row["id"]),
                        )
                        for row in batch
                    ],
                )
                updated += len(batch)
            movie_count = int(
                conn.execute("SELECT COUNT(*) FROM movies").fetchone()[0] or 0
            )
            trigram_count = int(
                conn.execute(
                    "SELECT COUNT(*) FROM movies_search_trigram"
                ).fetchone()[0] or 0
            )
            trigram_backfilled = 0
            if trigram_count < movie_count:
                conn.execute("DELETE FROM movies_search_trigram")
                trigram_rows = conn.execute(
                    "SELECT id,normalized_ru_title,normalized_original_title "
                    "FROM movies ORDER BY id"
                ).fetchall()
                for start in range(0, len(trigram_rows), 1000):
                    batch = trigram_rows[start:start + 1000]
                    conn.executemany(
                        "INSERT INTO movies_search_trigram"
                        "(rowid,movie_id,normalized_ru_title,normalized_original_title)"
                        " VALUES (?,?,?,?)",
                        [
                            (
                                int(row["id"]),
                                int(row["id"]),
                                row["normalized_ru_title"] or "",
                                row["normalized_original_title"] or "",
                            )
                            for row in batch
                        ],
                    )
                trigram_backfilled = len(trigram_rows)
            if added or updated or trigram_backfilled:
                bump_revision(conn)
            set_meta(conn, "schema_version", SCHEMA_VERSION)
            set_meta(conn, "normalization_version", NORMALIZATION_VERSION)
            conn.commit()
            return {
                "schema_version": SCHEMA_VERSION,
                "normalization_version": NORMALIZATION_VERSION,
                "added_columns": added,
                "backfilled_rows": updated,
                "trigram_backfilled_rows": trigram_backfilled,
                "revision": get_revision(conn),
            }


def refresh_normalized_rows(
    conn: sqlite3.Connection,
    row_ids: Optional[Iterable[int]] = None,
) -> int:
    """Refresh only rows written by an importer, preserving all other data."""
    if row_ids is None:
        rows = conn.execute(
            "SELECT id,title,original_title,localized_ru_title,alternative_titles "
            "FROM movies "
            "WHERE normalized_ru_title='' OR normalized_ru_title IS NULL"
        ).fetchall()
    else:
        ids = [int(value) for value in row_ids]
        if not ids:
            return 0
        placeholders = ",".join("?" for _ in ids)
        rows = conn.execute(
            f"SELECT id,title,original_title,localized_ru_title,alternative_titles "
            f"FROM movies WHERE id IN ({placeholders})",
            ids,
        ).fetchall()
    if not rows:
        return 0
    conn.executemany(
        "UPDATE movies SET localized_ru_title=?,normalized_ru_title=?,"
        "normalized_original_title=?,updated_at=? WHERE id=?",
        [
            (
                choose_localized_ru_title(
                    localized_ru_title=row["localized_ru_title"],
                    title=row["title"],
                    original_title=row["original_title"],
                    alternative_titles=row["alternative_titles"],
                ) or "",
                normalize_ru_text(
                    choose_localized_ru_title(
                        localized_ru_title=row["localized_ru_title"],
                        title=row["title"],
                        original_title=row["original_title"],
                        alternative_titles=row["alternative_titles"],
                    ) or ""
                ),
                normalize_ru_text(row["original_title"]),
                utc_now(),
                int(row["id"]),
            )
            for row in rows
        ],
    )
    bump_revision(conn)
    return len(rows)


def json_dumps_alternative_titles(value: Any) -> str:
    """Return a bounded canonical JSON representation for title aliases."""
    return json.dumps(
        parse_alternative_titles(value), ensure_ascii=False, separators=(",", ":")
    )
