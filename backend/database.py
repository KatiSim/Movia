import sqlite3
from stream_validation import sanitize_streams
import os
import json
import re
from pathlib import Path
from typing import List, Dict, Any, Optional
from urllib.parse import parse_qs, unquote, urlparse

DB_PATH = Path(__file__).resolve().parent / "catalog.db"


def _stream_identity_title(raw: Dict[str, Any]) -> str:
    """Get provider release identity from explicit title or magnet display name."""
    title = str(raw.get("title") or "").strip()
    if title:
        return title
    url = str(raw.get("url") or raw.get("playback_url") or "").strip()
    if not url.lower().startswith("magnet:?"):
        return ""
    try:
        values = parse_qs(urlparse(url).query, keep_blank_values=True).get("dn") or []
        return unquote(str(values[0])).strip() if values else ""
    except Exception:
        return ""


def filter_streams_for_content(
    streams: Any,
    content: Dict[str, Any],
) -> List[Dict[str, Any]]:
    """Keep structurally valid streams whose release identity matches the card."""
    cleaned = sanitize_streams(streams, require_source=True)
    expected_titles = list(dict.fromkeys(
        str(content.get(key) or "").strip()
        for key in ("title", "original_title")
        if str(content.get(key) or "").strip()
    ))
    if not expected_titles:
        return cleaned

    try:
        year = int(content.get("year") or 0)
    except (TypeError, ValueError):
        year = 0
    media_type = str(content.get("media_type") or "").strip().casefold()
    result: List[Dict[str, Any]] = []

    # Import lazily: torrent_resolver uses the shared stream validator and this
    # keeps database initialization independent from provider initialization.
    from torrent_resolver import _release_matches_expected

    for item in cleaned:
        url = str(item.get("url") or "").strip()
        release_title = _stream_identity_title(item)
        if not release_title:
            # HTTP/HLS candidates may not expose a release name; structural
            # validation still applies, while magnet candidates need identity.
            if url.lower().startswith("magnet:?"):
                continue
            result.append(item)
            continue

        # A movie card must never retain an explicitly episodic release.
        if media_type in {"movie", "movies", "film"} and re.search(
            r"(?i)\bs\d{1,3}(?:e\d{1,3})?\b|\b(?:season|сезон)\s*\d{1,3}\b",
            release_title,
        ):
            continue

        try:
            stream_season = int(item.get("season")) if item.get("season") is not None else None
        except (TypeError, ValueError):
            stream_season = None
        try:
            stream_episode = int(item.get("episode")) if item.get("episode") is not None else None
        except (TypeError, ValueError):
            stream_episode = None

        if not _release_matches_expected(
            release_title,
            expected_titles,
            year if year > 1900 else None,
            stream_season,
            stream_episode,
        ):
            continue

        normalized_item = dict(item)
        normalized_item.setdefault("title", release_title)
        result.append(normalized_item)

    return result

def get_db():
    conn = sqlite3.connect(DB_PATH, timeout=30.0)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    with get_db() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS movies (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tmdb_id INTEGER NOT NULL,
                media_type TEXT NOT NULL DEFAULT 'movie',
                title TEXT NOT NULL,
                original_title TEXT,
                year INTEGER,
                rating REAL DEFAULT 0.0,
                duration_minutes INTEGER DEFAULT 0,
                synopsis TEXT,
                poster_url TEXT,
                backdrop_url TEXT,
                genres TEXT,
                cast TEXT,
                director TEXT,
                country TEXT,
                category TEXT DEFAULT 'movies',
                streams TEXT DEFAULT '[]',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                season_episode_counts TEXT DEFAULT '[]',
                UNIQUE(media_type, tmdb_id)
            )
        """)
        conn.commit()

def save_or_update_movie(meta: Dict[str, Any], streams: List[Dict[str, Any]]) -> bool:
    genres_json = json.dumps(meta.get("genres", []), ensure_ascii=False)
    cast_json = json.dumps(meta.get("cast", []), ensure_ascii=False)
    streams = sanitize_streams(streams, require_source=True)
    streams_json = json.dumps(streams, ensure_ascii=False)

    query = """
        INSERT INTO movies
        (tmdb_id, media_type, title, original_title, year, rating, duration_minutes,
         synopsis, poster_url, backdrop_url, genres, "cast", director, country, category, streams)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(media_type, tmdb_id) DO UPDATE SET
            rating = excluded.rating,
            poster_url = COALESCE(excluded.poster_url, movies.poster_url),
            backdrop_url = COALESCE(excluded.backdrop_url, movies.backdrop_url),
            synopsis = excluded.synopsis,
            cast = excluded.cast,
            streams = excluded.streams
    """
    with get_db() as conn:
        cur = conn.execute(query, (
            meta.get("tmdb_id"),
            meta.get("media_type", "movie"),
            meta.get("title", "Без названия"),
            meta.get("original_title"),
            meta.get("year") or 0,
            meta.get("rating", 0.0),
            meta.get("duration_minutes", 0),
            meta.get("synopsis", ""),
            meta.get("poster_url"),
            meta.get("backdrop_url") or meta.get("poster_url"),
            genres_json,
            cast_json,
            meta.get("director", ""),
            meta.get("country", "Зарубежный"),
            meta.get("category", "movies"),
            streams_json
        ))
        conn.commit()
        return cur.rowcount > 0

def get_catalog_items(limit: int = 50, offset: int = 0, genre: Optional[str] = None, category: Optional[str] = None, sort_by: str = "popularity") -> List[Dict[str, Any]]:
    conditions = []
    params = []

    if category and category.lower() not in ["all", "все"]:
        conditions.append("category = ?")
        params.append("series" if category in ["Сериалы", "series", "Мини-сериалы"] else "movies")

    if genre:
        conditions.append("genres LIKE ?")
        params.append(f"%{genre}%")

    where_clause = f"WHERE {' AND '.join(conditions)}" if conditions else ""
    query = f"SELECT * FROM movies {where_clause} ORDER BY rating DESC, id DESC LIMIT ? OFFSET ?"
    params.extend([limit, offset])

    with get_db() as conn:
        cur = conn.execute(query, tuple(params))
        rows = []
        for r in cur.fetchall():
            d = dict(r)
            d["genres"] = json.loads(d["genres"]) if d.get("genres") else []
            d["cast"] = json.loads(d["cast"]) if d.get("cast") else []
            d["streams"] = sanitize_streams(
                json.loads(d["streams"]) if d.get("streams") else [],
                require_source=True,
            )

            if not d.get("backdrop_url"):
                d["backdrop_url"] = d.get("poster_url")

            if d["streams"]:
                d["playback_url"] = d["streams"][0].get("playback_url")
                d["source_id"] = d["streams"][0].get("source_id", "direct")
                d["media_type"] = d["streams"][0].get("media_type", "movie")
            else:
                d["playback_url"] = f"https://www.themoviedb.org/movie/{d.get('tmdb_id')}"
                d["source_id"] = "tmdb"
                d["media_type"] = "info"
            rows.append(d)
        return rows

def get_movie_by_id(item_id: int) -> Optional[Dict[str, Any]]:
    with get_db() as conn:
        cur = conn.execute("SELECT * FROM movies WHERE id = ?", (item_id,))
        row = cur.fetchone()
        if not row:
            return None
        d = dict(row)
        d["genres"] = json.loads(d["genres"]) if d.get("genres") else []
        d["cast"] = json.loads(d["cast"]) if d.get("cast") else []
        d["streams"] = sanitize_streams(
            json.loads(d["streams"]) if d.get("streams") else [],
            require_source=True,
        )
        return d

def get_catalog_count(genre: Optional[str] = None, category: Optional[str] = None) -> int:
    conditions = []
    params = []
    if category and category.lower() not in ["all", "все"]:
        conditions.append("category = ?")
        params.append("series" if category in ["Сериалы", "series", "Мини-сериалы"] else "movies")
    if genre:
        conditions.append("genres LIKE ?")
        params.append(f"%{genre}%")
    where_clause = f"WHERE {' AND '.join(conditions)}" if conditions else ""

    with get_db() as conn:
        cur = conn.execute(f"SELECT COUNT(*) FROM movies {where_clause}", tuple(params))
        return cur.fetchone()[0]

def get_unresolved_titles(limit: int = 100) -> List[Dict[str, Any]]:
    with get_db() as conn:
        cur = conn.execute("""
            SELECT id, tmdb_id, title, original_title, year, category, streams, playback_url, link_verified
            FROM movies
            WHERE playback_url IS NULL
               OR playback_url = ''
               OR streams IS NULL
               OR streams = '[]'
               OR link_verified = 0
            ORDER BY rating DESC, id ASC
            LIMIT ?
        """, (limit,))
        rows = []
        for r in cur.fetchall():
            d = dict(r)
            try:
                d["streams"] = json.loads(d["streams"]) if d.get("streams") else []
            except Exception:
                d["streams"] = []
            rows.append(d)
        return rows

def save_content(data: Dict[str, Any]) -> bool:
    content_id = data.get("id")
    if not content_id:
        return False
    voice = data.get("voice") or ""
    quality = data.get("quality") or ""
    seeders = int(data.get("seeders") or 0)
    streams = sanitize_streams(data.get("streams") or [], require_source=True)
    playback_url = streams[0]["url"] if streams else ""
    if not streams:
        return False
    streams_json = json.dumps(streams, ensure_ascii=False)
    explicit_verified = data.get("link_verified") in (1, True, "1", "true", "True")
    verified = 1 if explicit_verified and streams else 0

    with get_db() as conn:
        existing_row = conn.execute(
            """
            SELECT streams, title, original_title, year, media_type, category
            FROM movies
            WHERE id = ?
            """,
            (content_id,),
        ).fetchone()
        existing_streams = []
        content_identity: Dict[str, Any] = dict(existing_row) if existing_row else {}
        if existing_row:
            try:
                existing_streams = json.loads(existing_row["streams"] or "[]")
            except (TypeError, ValueError, json.JSONDecodeError):
                existing_streams = []

        # Enforce provider identity at the persistence boundary. This prevents
        # an old or remote fallback payload from being merged into the wrong card.
        incoming_streams = filter_streams_for_content(streams, content_identity)
        retained_streams = filter_streams_for_content(existing_streams, content_identity)

        # Discovery is additive: retain previously valid voice/quality variants
        # and merge the newly resolved candidates by the shared stable key.
        merged_streams = sanitize_streams(
            list(incoming_streams) + retained_streams,
            require_source=True,
        )
        if not merged_streams:
            return False
        primary = merged_streams[0]
        merged_json = json.dumps(merged_streams, ensure_ascii=False)
        cur = conn.execute("""
            UPDATE movies
            SET playback_url = ?,
                voice = ?,
                quality = ?,
                seeders = ?,
                streams = ?,
                link_verified = ?,
                link_updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
        """, (
            primary["url"],
            primary.get("voice", voice),
            primary.get("quality", quality),
            int(primary.get("seeders") or seeders),
            merged_json,
            verified,
            content_id,
        ))
        conn.commit()
        return cur.rowcount > 0

init_db()
