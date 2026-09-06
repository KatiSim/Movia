from __future__ import annotations

import sqlite3
from datetime import datetime, timezone

SCHEMA_VERSION = 1


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def connect(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path, timeout=30.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=30000")
    return conn


def ensure_catalog_intelligence_schema(conn: sqlite3.Connection) -> None:
    """Create only additive catalog-intelligence tables.

    The existing ``movies`` table remains owned by the current catalog backend.
    This module can therefore be deployed next to the live database without a
    destructive rebuild or playback-side migration.
    """
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS catalog_intelligence_meta(
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS catalog_sources(
            source_id TEXT PRIMARY KEY,
            display_name TEXT NOT NULL,
            trust_weight REAL NOT NULL DEFAULT 0.5,
            enabled INTEGER NOT NULL DEFAULT 1,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS metadata_observations(
            media_id INTEGER NOT NULL,
            source_id TEXT NOT NULL,
            field_name TEXT NOT NULL,
            value_json TEXT NOT NULL,
            confidence REAL NOT NULL DEFAULT 1.0,
            observed_at TEXT NOT NULL,
            PRIMARY KEY(media_id, source_id, field_name),
            FOREIGN KEY(source_id) REFERENCES catalog_sources(source_id)
        );
        CREATE INDEX IF NOT EXISTS idx_metadata_observations_media
            ON metadata_observations(media_id, field_name);

        CREATE TABLE IF NOT EXISTS people(
            person_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            profile_url TEXT NOT NULL DEFAULT '',
            known_for_department TEXT NOT NULL DEFAULT '',
            source_id TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_people_name ON people(name COLLATE NOCASE);

        CREATE TABLE IF NOT EXISTS media_people(
            media_id INTEGER NOT NULL,
            person_id TEXT NOT NULL,
            role_type TEXT NOT NULL,
            role_name TEXT NOT NULL DEFAULT '',
            department TEXT NOT NULL DEFAULT '',
            billing_order INTEGER NOT NULL DEFAULT 9999,
            source_id TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL,
            PRIMARY KEY(media_id, person_id, role_type, role_name),
            FOREIGN KEY(person_id) REFERENCES people(person_id)
        );
        CREATE INDEX IF NOT EXISTS idx_media_people_person
            ON media_people(person_id, role_type, billing_order);
        CREATE INDEX IF NOT EXISTS idx_media_people_media
            ON media_people(media_id, role_type, billing_order);

        CREATE TABLE IF NOT EXISTS media_genres(
            media_id INTEGER NOT NULL,
            genre_key TEXT NOT NULL,
            display_genre TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            PRIMARY KEY(media_id, genre_key)
        );
        CREATE INDEX IF NOT EXISTS idx_media_genres_key
            ON media_genres(genre_key, media_id);

        CREATE TABLE IF NOT EXISTS release_candidates(
            media_id INTEGER PRIMARY KEY,
            title TEXT NOT NULL,
            media_type TEXT NOT NULL DEFAULT 'movie',
            release_date TEXT NOT NULL DEFAULT '',
            discovery_source TEXT NOT NULL DEFAULT '',
            discovered_at TEXT NOT NULL,
            first_playable_at TEXT NOT NULL DEFAULT '',
            last_playable_at TEXT NOT NULL DEFAULT '',
            state TEXT NOT NULL DEFAULT 'waiting'
        );
        CREATE INDEX IF NOT EXISTS idx_release_candidates_state
            ON release_candidates(state, release_date);

        CREATE TABLE IF NOT EXISTS playable_sources(
            media_id INTEGER NOT NULL,
            source_key TEXT NOT NULL,
            source_name TEXT NOT NULL DEFAULT '',
            url TEXT NOT NULL DEFAULT '',
            source_kind TEXT NOT NULL DEFAULT '',
            quality TEXT NOT NULL DEFAULT '',
            voice TEXT NOT NULL DEFAULT '',
            playable INTEGER NOT NULL DEFAULT 1,
            first_seen_at TEXT NOT NULL,
            last_seen_at TEXT NOT NULL,
            metadata_json TEXT NOT NULL DEFAULT '{}',
            PRIMARY KEY(media_id, source_key)
        );
        CREATE INDEX IF NOT EXISTS idx_playable_sources_media
            ON playable_sources(media_id, playable, last_seen_at);
        """
    )
    now = utc_now()
    conn.execute(
        "INSERT INTO catalog_intelligence_meta(key,value,updated_at) VALUES(?,?,?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at",
        ("schema_version", str(SCHEMA_VERSION), now),
    )
    conn.commit()
