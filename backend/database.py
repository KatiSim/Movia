import sqlite3
from stream_validation import sanitize_streams, stream_variant_key
import os
import json
import re
from pathlib import Path
from typing import List, Dict, Any, Optional
from urllib.parse import parse_qs, unquote, urlparse
from catalog_localization import (
    meta_localized_title,
    parse_alternative_titles,
    russian_alternative_titles,
)
from catalog_schema_v2 import ensure_schema, normalize_ru_text

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
        for key in ("localized_ru_title", "title", "original_title")
        if str(content.get(key) or "").strip()
    ))
    expected_titles.extend(
        title for title in russian_alternative_titles(content.get("alternative_titles"))
        if title not in expected_titles
    )
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

        # Runtime Zona results are bound to the canonical card before they
        # reach this boundary. If an identity annotation is present, every
        # dimension is mandatory and exact; a valid URL from another card is
        # still a wrong source and must be rejected.
        annotated_id = str(
            item.get("catalog_media_id") or item.get("catalogMediaId") or ""
        ).strip()
        expected_id = str(content.get("id") or "").strip()
        if annotated_id and expected_id and annotated_id != expected_id:
            continue
        annotated_title = str(
            item.get("canonical_title") or item.get("canonicalTitle") or ""
        ).strip()
        annotated_original = str(
            item.get("canonical_original_title") or
            item.get("canonicalOriginalTitle") or ""
        ).strip()
        if annotated_title or annotated_original:
            if not any(
                normalize_ru_text(value) in {
                    normalize_ru_text(expected) for expected in expected_titles
                }
                for value in (annotated_title, annotated_original)
                if value
            ):
                continue
        annotated_year = item.get("canonical_year", item.get("canonicalYear"))
        if annotated_year not in (None, "") and year:
            try:
                if int(annotated_year) != year:
                    continue
            except (TypeError, ValueError):
                continue
        annotated_type = str(
            item.get("canonical_media_type") or item.get("canonicalMediaType") or ""
        ).strip().casefold()
        if annotated_type:
            expected_type = "tv" if media_type in {
                "tv", "series", "tv_series", "serial", "limited_series",
            } else "movie" if media_type in {"movie", "movies", "film"} else ""
            if expected_type and annotated_type != expected_type:
                continue

        requested_season = content.get("season")
        requested_episode = content.get("episode")
        try:
            requested_season = int(requested_season) if requested_season is not None else None
        except (TypeError, ValueError):
            requested_season = None
        try:
            requested_episode = int(requested_episode) if requested_episode is not None else None
        except (TypeError, ValueError):
            requested_episode = None
        if requested_season is not None or requested_episode is not None:
            try:
                if requested_season is not None and int(item.get("season")) != requested_season:
                    continue
                if requested_episode is not None and int(item.get("episode")) != requested_episode:
                    continue
            except (TypeError, ValueError):
                continue
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
    conn.execute("PRAGMA busy_timeout=30000")
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
    # Additive migration only: catalog.db remains the one metadata SSOT and
    # no existing card or stream is rebuilt here.
    ensure_schema(DB_PATH)

def save_or_update_movie(meta: Dict[str, Any], streams: List[Dict[str, Any]]) -> bool:
    genres_json = json.dumps(meta.get("genres", []), ensure_ascii=False)
    cast_json = json.dumps(meta.get("cast", []), ensure_ascii=False)
    streams = sanitize_streams(streams, require_source=True)
    streams_json = json.dumps(streams, ensure_ascii=False)
    localized_title = meta_localized_title(meta) or ""
    alternative_titles = json.dumps(
        parse_alternative_titles(meta.get("alternative_titles")),
        ensure_ascii=False,
        separators=(",", ":"),
    )

    query = """
        INSERT INTO movies 
        (tmdb_id, media_type, title, original_title, localized_ru_title, alternative_titles,
         localization_source, localization_updated_at, year, rating, duration_minutes, 
         synopsis, poster_url, backdrop_url, genres, "cast", director, country, category, streams)
        VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(media_type, tmdb_id) DO UPDATE SET
            rating = CASE WHEN movies.metadata_source='tmdb_detail' THEN movies.rating ELSE excluded.rating END,
            poster_url = CASE WHEN movies.metadata_source='tmdb_detail' THEN movies.poster_url ELSE COALESCE(excluded.poster_url, movies.poster_url) END,
            backdrop_url = CASE WHEN movies.metadata_source='tmdb_detail' THEN movies.backdrop_url ELSE COALESCE(excluded.backdrop_url, movies.backdrop_url) END,
            synopsis = CASE WHEN movies.metadata_source='tmdb_detail' THEN movies.synopsis ELSE excluded.synopsis END,
            "cast" = CASE WHEN movies.metadata_source='tmdb_detail' THEN movies."cast" ELSE excluded."cast" END,
            streams = excluded.streams,
            localized_ru_title = CASE WHEN excluded.localized_ru_title!=''
                                      THEN excluded.localized_ru_title
                                      ELSE movies.localized_ru_title END,
            alternative_titles = CASE WHEN excluded.alternative_titles!='[]'
                                      THEN excluded.alternative_titles
                                      ELSE movies.alternative_titles END,
            localization_source = CASE WHEN excluded.localized_ru_title!=''
                                       THEN excluded.localization_source
                                       ELSE movies.localization_source END,
            localization_updated_at = CASE WHEN excluded.localized_ru_title!=''
                                           THEN CURRENT_TIMESTAMP
                                           ELSE movies.localization_updated_at END
    """
    with get_db() as conn:
        cur = conn.execute(query, (
            meta.get("tmdb_id"),
            meta.get("media_type", "movie"),
            meta.get("title", "Без названия"),
            meta.get("original_title"),
            localized_title,
            alternative_titles,
            str(meta.get("localization_source") or ("tmdb_ru" if localized_title else "")),
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
                d["playback_url"] = (
                    d["streams"][0].get("url")
                    or d["streams"][0].get("playback_url")
                    or ""
                )
                d["source_id"] = (
                    d["streams"][0].get("source_id")
                    or d["streams"][0].get("source")
                    or "direct"
                )
                d["media_type"] = d["streams"][0].get("media_type", "movie")
            else:
                # An empty stream list is an explicit NO_SOURCE state.
                # Never expose a TMDb metadata page as a playable URL.
                d["playback_url"] = ""
                d["source_id"] = "no_source"
                d["media_type"] = d.get("media_type") or "movie"
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

def _direct_stream_variant_identity(raw: Dict[str, Any]) -> Optional[tuple[str, str, str, str, str, str, str]]:
    """Return a URL-independent identity for one expiring direct variant."""
    if not isinstance(raw, dict):
        return None
    url = str(raw.get("url") or raw.get("playback_url") or "").strip().lower()
    if not url.startswith(("http://", "https://")):
        return None

    def text(*keys: str) -> str:
        for key in keys:
            value = raw.get(key)
            if value is not None and str(value).strip():
                return re.sub(r"\s+", " ", str(value).strip()).casefold()
        return ""

    # A provider/public stream ID is the strongest URL-independent identity.
    # Prefer it before provider metadata because source_type/provider annotations
    # can be enriched over time while the logical stream remains the same.
    stable_id = text("stream_id", "streamId")
    # ``sanitize_streams`` synthesizes ``stream:<hash>`` IDs from the locator;
    # those rotate with an expiring URL and therefore are not reload identity.
    # Provider-supplied IDs (for example Collaps ``collaps_...``) are stable.
    if stable_id and not stable_id.startswith("stream:"):
        return ("stream-id", stable_id, "", "", "", "", "")

    source = text("source", "source_id", "sourceId")
    extractor = text("source_type_id", "video_source_type_id", "videoSourceTypeId")
    provider = text("provider", "provider_id", "providerId")
    logical_provider = extractor or provider
    if not source and not logical_provider:
        return None
    if not logical_provider:
        logical_provider = source
    return (
        source,
        logical_provider,
        text("voice", "translation"),
        text("quality", "resolution", "videoResolution", "video_resolution"),
        text("season"),
        text("episode"),
        text("file_index", "fileIndex") + "|" + text("file_path", "filePath"),
    )


def merge_streams_additive(content_id: int, streams: Any, *, mark_verified: bool = True) -> Dict[str, Any]:
    """Append only new validated variants to one existing Movia card.

    Existing raw stream records are preserved byte-for-byte at the JSON object
    level; validation is used only to decide whether incoming records are safe
    and whether an equivalent variant already exists.
    """
    incoming = sanitize_streams(streams, require_source=True)
    if not incoming:
        return {"status": "no_source", "added": 0, "accepted": 0, "rejected": 0}

    with get_db() as conn:
        row = conn.execute(
            """
            SELECT id, title, original_title, year, media_type, category,
                   streams, playback_url, link_verified, voice, quality, seeders
            FROM movies WHERE id = ?
            """,
            (int(content_id),),
        ).fetchone()
        if row is None:
            return {"status": "unmatched_card", "added": 0, "accepted": 0, "rejected": len(incoming)}

        identity = dict(row)
        accepted = filter_streams_for_content(incoming, identity)
        rejected = len(incoming) - len(accepted)
        if not accepted:
            return {"status": "rejected_by_identity", "added": 0, "accepted": 0, "rejected": rejected}

        try:
            existing_raw = json.loads(row["streams"] or "[]")
        except (TypeError, ValueError, json.JSONDecodeError):
            existing_raw = []
        if not isinstance(existing_raw, list):
            existing_raw = []

        existing_valid = sanitize_streams(existing_raw, require_source=True)
        seen = {stream_variant_key(item) for item in existing_valid}
        new_streams: List[Dict[str, Any]] = []
        for item in accepted:
            key = stream_variant_key(item)
            if key in seen:
                continue
            seen.add(key)
            new_streams.append(item)

        if not new_streams:
            if mark_verified and existing_valid and int(row["link_verified"] or 0) != 1:
                conn.execute(
                    "UPDATE movies SET link_verified = 1, link_updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    (int(content_id),),
                )
                conn.commit()
            return {"status": "duplicate", "added": 0, "accepted": len(accepted), "rejected": rejected}

        merged = existing_raw + new_streams
        first = new_streams[0]
        playback_url = str(row["playback_url"] or "").strip() or str(first["url"])
        voice = str(row["voice"] or "").strip() or str(first.get("voice") or "Не указано")
        quality = str(row["quality"] or "").strip() or str(first.get("quality") or "Не указано")
        seeders = int(row["seeders"] or 0) or int(first.get("seeders") or 0)
        conn.execute(
            """
            UPDATE movies
            SET streams = ?, playback_url = ?, voice = ?, quality = ?, seeders = ?,
                link_verified = ?, link_updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            (
                json.dumps(merged, ensure_ascii=False),
                playback_url,
                voice,
                quality,
                seeders,
                1 if mark_verified else int(row["link_verified"] or 0),
                int(content_id),
            ),
        )
        conn.commit()
        return {"status": "persisted", "added": len(new_streams), "accepted": len(accepted), "rejected": rejected}

def save_content(data: Dict[str, Any]) -> bool:
    content_id = data.get("id")
    if not content_id:
        return False
    voice = data.get("voice") or ""
    quality = data.get("quality") or ""
    seeders = int(data.get("seeders") or 0)
    replace_direct_variants = bool(data.get("replace_direct_variants"))
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

        if replace_direct_variants:
            refresh_keys = set()
            for incoming in incoming_streams:
                key = _direct_stream_variant_identity(incoming)
                if key is not None:
                    refresh_keys.add(key)
            if refresh_keys:
                retained_streams = [
                    retained
                    for retained in retained_streams
                    if _direct_stream_variant_identity(retained) not in refresh_keys
                ]

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
