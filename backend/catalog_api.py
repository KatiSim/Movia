import os
import re
import json
import math
import time
import sqlite3
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Any, List, Optional, Tuple
from catalog_localization import (
    is_user_visible_row,
    parse_alternative_titles,
    row_display_title,
)
from stream_validation import bind_stream_identity, sanitize_streams
from tmdb_client import tmdb
from metadata_quality import EAST_ASIA_COUNTRIES

# Keep the catalog reader and the sync writer on the same snapshot/deployment
# root.  The previous absolute path made a snapshot refresh a different DB
# from the one served by its own streamer.
DIR = Path(__file__).resolve().parent
DB_PATH = DIR / "catalog.db"

# One feed gate is shared by Home, catalog pages, search, recommendations and
# related titles.  Rows failing localization remain in catalog.db for later
# enrichment but are never rendered as cards.
_USER_VISIBLE_SQL = """
tmdb_id > 0
AND media_type IN ('movie','tv')
AND COALESCE(localized_ru_title, '') != ''
AND poster_url IS NOT NULL
AND poster_url != ''
""".strip()

SCORE_SQL = """
(
    (rating * 1.35) +
    (CASE
        WHEN vote_count >= 50000 THEN 4.5
        WHEN vote_count >= 10000 THEN 3.8
        WHEN vote_count >= 3000 THEN 3.1
        WHEN vote_count >= 1000 THEN 2.4
        WHEN vote_count >= 300 THEN 1.6
        WHEN vote_count >= 100 THEN 0.9
        WHEN vote_count >= 30 THEN 0.3
        ELSE -1.0
    END) +
    (CASE
        WHEN seeders >= 2000 THEN 1.5
        WHEN seeders >= 500 THEN 1.0
        WHEN seeders >= 100 THEN 0.5
        ELSE 0.0
    END)
)
"""

def get_db():
    conn = sqlite3.connect(str(DB_PATH), timeout=10.0)
    conn.row_factory = sqlite3.Row
    return conn

def parse_json_safely(val, default):
    if not val:
        return default
    if isinstance(val, (list, dict)):
        return val
    try:
        return json.loads(val)
    except Exception:
        return default


def _validated_row_streams(
    row_data: Dict[str, Any],
    *,
    catalog_media_id: Any,
    title: str,
    original_title: str,
    year: int,
    media_type: str,
    season: Optional[int] = None,
    episode: Optional[int] = None,
) -> List[Dict[str, Any]]:
    """Expose only streams that belong to this exact catalog card.

    Catalog rows are the last persisted boundary before Android.  Rebinding a
    valid URL to the row here would make a wrong-media record look legitimate,
    so identity filtering happens before the additive annotation.
    """
    raw_streams = parse_json_safely(row_data.get("streams"), [])
    try:
        # Lazy import keeps catalog_api usable during database initialization.
        from database import filter_streams_for_content

        raw_streams = filter_streams_for_content(
            raw_streams,
            {
                "id": catalog_media_id,
                "localized_ru_title": title,
                "title": title,
                "original_title": original_title,
                "alternative_titles": row_data.get("alternative_titles"),
                "year": year,
                "media_type": media_type,
                "season": season,
                "episode": episode,
            },
        )
    except Exception:
        # Validation failures must fail closed.  Returning unvalidated row
        # streams would violate the catalog-card identity invariant.
        return []
    return bind_stream_identity(
        raw_streams,
        catalog_media_id=catalog_media_id,
        title=title,
        original_title=original_title,
        year=year,
        media_type=media_type,
        season=season,
        episode=episode,
    )

def map_row_to_media(row: sqlite3.Row, compact: bool = True) -> Dict[str, Any]:
    d = dict(row)
    genres_raw = parse_json_safely(d.get("genres"), [])
    genres = [g.strip() for g in genres_raw if g and isinstance(g, str)] if isinstance(genres_raw, list) else []

    cat_raw = str(d.get("category") or "movies").lower()
    media_type = str(d.get("media_type") or "").lower()
    if cat_raw in ["series", "tv_series", "сериалы"]:
        category = "TV_SERIES"
    elif cat_raw in ["animation", "мультфильм", "мультфильмы"]:
        category = "ANIMATION"
    elif cat_raw in ["anime", "аниме"]:
        category = "ANIME"
    elif cat_raw in ["dramas_asian", "дорамы"]:
        category = "DRAMAS_ASIAN"
    elif cat_raw in ["documentaries", "документальные"]:
        category = "DOCUMENTARIES"
    elif cat_raw in ["limited_series", "мини-сериалы"]:
        category = "LIMITED_SERIES"
    else:
        category = "MOVIES"
    # Content form is independent from catalog shelf. Animation/anime/documentary
    # may be either a movie or a TV series. media_type is therefore authoritative.
    if media_type == "tv":
        ctype = "series"
    elif media_type == "movie":
        ctype = "movie"
    else:
        ctype = "series" if cat_raw in ["series", "tv_series", "limited_series", "dramas_asian"] else "movie"

    year = int(d.get("year") or 0)
    rating = float(d.get("rating") or 0.0)
    vote_count = int(d.get("vote_count") or 0)
    vote_average = float(d.get("vote_average") or rating)
    duration = int(d.get("duration_minutes") or 90)
    seasons_count = int(d.get("seasons_count") or 0)
    episodes_count = int(d.get("episodes_count") or 0)
    season_episode_counts_raw = parse_json_safely(d.get("season_episode_counts"), [])
    season_episode_counts = [int(x or 0) for x in season_episode_counts_raw] if isinstance(season_episode_counts_raw, list) else []
    collection_id = int(d.get("collection_id") or 0)
    poster = d.get("poster_url") or ""
    if poster and poster.startswith("/") and not poster.startswith("http"):
        poster = f"https://image.tmdb.org/t/p/w500{poster}"
    backdrop = d.get("backdrop_url") or poster
    if backdrop and backdrop.startswith("/") and not backdrop.startswith("http"):
        backdrop = f"https://image.tmdb.org/t/p/w780{backdrop}"
    # Never expose the legacy playback_url directly. The historical catalog
    # contains synthetic magnets; a playable URL must survive stream validation.
    playback_url = ""
    country = str(d.get("country") or "Зарубежный")
    # The legacy title/original_title fields are metadata/search inputs only.
    # A card has no display title until the verified Russian field exists.
    title = row_display_title(d) or ""
    original_title = str(d.get("original_title") or "")
    alternative_titles = parse_alternative_titles(d.get("alternative_titles"))
    raw_director = str(d.get("director") or "").strip()
    creators_raw = parse_json_safely(d.get("creators"), [])
    creators = [str(x).strip() for x in creators_raw if str(x).strip()] if isinstance(creators_raw, list) else []
    # Legacy TV rows can contain a stale movie director from old field-wise merges.
    # Fail closed: a TV credit is shown only when TMDb supplied created_by.
    director = ", ".join(creators) if media_type == "tv" else raw_director
    synopsis = str(d.get("synopsis") or "")
    if not synopsis and compact is False:
        kind = "Сериал" if ctype == "series" else "Фильм"
        synopsis = f"{kind} «{title}» ({year})."

    if ctype == "series" and seasons_count > 0:
        s_word = "сезон" if (seasons_count % 10 == 1 and seasons_count % 100 != 11) else ("сезона" if (seasons_count % 10 in [2,3,4] and seasons_count % 100 not in [12,13,14]) else "сезонов")
        if episodes_count > 0:
            duration_str = f"{seasons_count} {s_word} ({episodes_count} сер.) • {duration} мин/серия"
        else:
            duration_str = f"{seasons_count} {s_word} • {duration} мин/серия"
    elif ctype == "series":
        duration_str = f"{duration} мин/серия"
    else:
        duration_str = f"{duration} мин"

    streams_list = _validated_row_streams(
        d,
        catalog_media_id=d.get("id"),
        title=title,
        original_title=original_title,
        year=year,
        media_type=media_type or ("tv" if ctype == "series" else "movie"),
    )
    if streams_list:
        playback_url = streams_list[0]["url"]

    if compact:
        return {
            "id": str(d.get("id")),
            "title": title,
            "localized_ru_title": title,
            "localizedRuTitle": title,
            "original_title": original_title,
            "alternative_titles": alternative_titles,
            "alternativeTitles": alternative_titles,
            "originalTitle": original_title,
            "type": ctype,
            "mediaType": media_type or ("tv" if ctype == "series" else "movie"),
            "year": year,
            "rating": rating,
            "vote_count": vote_count,
            "voteCount": vote_count,
            "vote_average": vote_average,
            "voteAverage": vote_average,
            "seasons_count": seasons_count,
            "seasonsCount": seasons_count,
            "episodes_count": episodes_count,
            "episodesCount": episodes_count,
            "season_episode_counts": season_episode_counts,
            "seasonEpisodeCounts": season_episode_counts,
            "collection_id": collection_id,
            "collectionId": collection_id,
            "genres": genres,
            "country": country,
            "quality": str(d.get("quality") or "1080p"),
            "duration": duration_str,
            "durationMinutes": duration,
            "isNew": bool(year > 0 and year >= datetime.now(timezone.utc).year - 1),
            "popularity": min(2_000_000_000, vote_count * 10 + int(d.get("seeders") or 0)),
            "ageRating": 16,
            "category": category,
            "poster_url": poster,
            "posterUrl": poster,
            "backdrop_url": backdrop,
            "backdropUrl": backdrop,
            "playbackUrl": playback_url,
            "link_verified": int(d.get("link_verified") or 0),
            "linkVerified": bool(d.get("link_verified") or 0),
            "link_updated_at": d.get("link_updated_at"),
            "linkUpdatedAt": d.get("link_updated_at"),
            "director": director,
            "creators": creators,
            "imdb_id": str(d.get("imdb_id") or ""),
            "metadata_source": str(d.get("metadata_source") or ""),
            "description": synopsis,
            "synopsis": synopsis,
            "actors": [],
            "cast": [],
            "streams": streams_list
        }

    cast_raw = parse_json_safely(d.get("cast"), [])
    cast_list = []
    if isinstance(cast_raw, list):
        for c in cast_raw:
            if isinstance(c, str):
                c_name = c.strip()
                cast_list.append({
                    "name": c_name,
                    "role": "Актёр",
                    "photo_url": None,
                    "photoUrl": None
                })
            elif isinstance(c, dict) and c.get("name"):
                photo = c.get("photo_url") or c.get("photoUrl") or c.get("profile_path")
                role = c.get("role") or c.get("character") or "Актёр"
                cast_list.append({
                    "name": str(c.get("name", "")).strip(),
                    "role": role,
                    "photo_url": photo,
                    "photoUrl": photo
                })

    streams_list = _validated_row_streams(
        d,
        catalog_media_id=d.get("id"),
        title=title,
        original_title=original_title,
        year=year,
        media_type=media_type or ("tv" if ctype == "series" else "movie"),
    )
    if streams_list:
        playback_url = streams_list[0]["url"]

    return {
        "id": str(d.get("id")),
        "title": title,
        "localized_ru_title": title,
        "localizedRuTitle": title,
        "original_title": original_title,
        "alternative_titles": alternative_titles,
        "alternativeTitles": alternative_titles,
        "originalTitle": original_title,
        "type": ctype,
        "mediaType": media_type or ("tv" if ctype == "series" else "movie"),
        "year": year,
        "rating": rating,
        "vote_count": vote_count,
        "voteCount": vote_count,
        "vote_average": vote_average,
        "voteAverage": vote_average,
        "seasons_count": seasons_count,
        "seasonsCount": seasons_count,
        "episodes_count": episodes_count,
        "episodesCount": episodes_count,
        "season_episode_counts": season_episode_counts,
        "seasonEpisodeCounts": season_episode_counts,
        "collection_id": collection_id,
        "collectionId": collection_id,
        "genres": genres,
        "country": country,
        "quality": str(d.get("quality") or "1080p"),
        "duration": duration_str,
        "durationMinutes": duration,
        "isNew": bool(year > 0 and year >= datetime.now(timezone.utc).year - 1),
        "popularity": min(2_000_000_000, vote_count * 10 + int(d.get("seeders") or 0)),
        "ageRating": 16,
        "audioLanguages": ["Русский", "Оригинал"],
        "subtitleLanguages": ["Русские"],
        "director": director,
        "creators": creators,
        "imdb_id": str(d.get("imdb_id") or ""),
        "metadata_source": str(d.get("metadata_source") or ""),
        "synopsis": synopsis,
        "description": synopsis,
        "category": category,
        "poster_url": poster,
        "posterUrl": poster,
        "backdrop_url": backdrop,
        "backdropUrl": backdrop,
        "playbackUrl": playback_url,
        "link_verified": int(d.get("link_verified") or 0),
        "linkVerified": bool(d.get("link_verified") or 0),
        "link_updated_at": d.get("link_updated_at"),
        "linkUpdatedAt": d.get("link_updated_at"),
        "actors": cast_list,
        "cast": cast_list,
        "streams": streams_list
    }

def get_balanced_selection(cur, base_where: str, target_limit: int = 12) -> List[Dict[str, Any]]:
    """
    Selects a balanced, diverse list of titles representing USA (4), Europe (3), CIS (2), Korea/Turkey/LatAm (2), JP/CN (1)
    """
    regions = [
        ("country IN ('США', 'Канада')", 4),
        ("country IN ('Великобритания', 'Франция', 'Германия', 'Италия', 'Испания', 'Швеция', 'Дания', 'Норвегия', 'Финляндия', 'Нидерланды', 'Бельгия', 'Польша', 'Ирландия', 'Австрия', 'Швейцария')", 3),
        ("country IN ('Россия', 'СССР', 'Беларусь', 'Казахстан')", 2),
        ("country IN ('Южная Корея', 'Турция', 'Индия', 'Мексика', 'Бразилия', 'Аргентина', 'Австралия')", 2),
        ("country IN ('Китай', 'Япония', 'Гонконг', 'Тайвань')", 1)
    ]
    
    seen_ids = set()
    result = []

    for region_sql, count in regions:
        where = f"{_USER_VISIBLE_SQL} AND ({base_where}) AND {region_sql}" if base_where else f"{_USER_VISIBLE_SQL} AND {region_sql}"
        query = f"SELECT * FROM movies WHERE {where} ORDER BY {SCORE_SQL} DESC LIMIT {count};"
        cur.execute(query)
        for r in cur.fetchall():
            m = map_row_to_media(r, compact=True)
            if m["id"] not in seen_ids:
                seen_ids.add(m["id"])
                result.append(m)

    if len(result) < target_limit:
        needed = target_limit - len(result)
        exclude_sql = f"AND id NOT IN ({','.join(seen_ids)})" if seen_ids else ""
        query = f"SELECT * FROM movies WHERE {_USER_VISIBLE_SQL} AND ({base_where}) {exclude_sql} ORDER BY {SCORE_SQL} DESC LIMIT {needed};" if base_where else f"SELECT * FROM movies WHERE {_USER_VISIBLE_SQL} {exclude_sql} ORDER BY {SCORE_SQL} DESC LIMIT {needed};"
        cur.execute(query)
        for r in cur.fetchall():
            m = map_row_to_media(r, compact=True)
            if m["id"] not in seen_ids:
                seen_ids.add(m["id"])
                result.append(m)

    return result

_home_cache: Optional[Dict[str, Any]] = None
_home_cache_ts: float = 0.0
HOME_CACHE_TTL = 900.0  # 15 minutes

def invalidate_home_cache() -> None:
    global _home_cache, _home_cache_ts
    _home_cache = None
    _home_cache_ts = 0.0


def get_home_payload(force_refresh: bool = False) -> Dict[str, Any]:
    global _home_cache, _home_cache_ts
    now = time.time()
    if not force_refresh and _home_cache is not None and (now - _home_cache_ts) < HOME_CACHE_TTL:
        return _home_cache

    with get_db() as conn:
        cur = conn.cursor()
        excluded_ids = set()

        def not_in_sql():
            if not excluded_ids:
                return "1=1"
            return f"id NOT IN ({','.join(map(str, excluded_ids))})"

        # 1. Hero Promo Item (Top-1 blockbuster with vote_count > 3000, rating >= 8.0, valid backdrop)
        cur.execute(f"""
            SELECT * FROM movies 
            WHERE {_USER_VISIBLE_SQL}
              AND vote_count >= 3000 
              AND rating >= 8.0 
              AND backdrop_url IS NOT NULL 
              AND backdrop_url != '' 
              AND LENGTH(backdrop_url) > 10
            ORDER BY rating DESC, vote_count DESC, seeders DESC 
            LIMIT 1;
        """)
        hero_row = cur.fetchone()
        if not hero_row:
            cur.execute(f"""
                SELECT * FROM movies 
                WHERE {_USER_VISIBLE_SQL}
                  AND rating >= 7.5 AND backdrop_url IS NOT NULL AND backdrop_url != ''
                ORDER BY rating DESC, seeders DESC LIMIT 1;
            """)
            hero_row = cur.fetchone()

        featured = map_row_to_media(hero_row, compact=True) if hero_row else None
        if featured:
            excluded_ids.add(featured["id"])

        # 2. Section «Новинки» (current and previous UTC calendar year)
        current_year = datetime.now(timezone.utc).year
        recent_years = (current_year - 1, current_year)
        cur.execute(f"""
            SELECT * FROM movies 
            WHERE {_USER_VISIBLE_SQL}
              AND year IN (?, ?)
              AND vote_count >= 30
              AND rating >= 5.5
              AND country IN ('США', 'Канада')
              AND {not_in_sql()}
              AND poster_url IS NOT NULL AND poster_url != ''
            ORDER BY year DESC, vote_average DESC, seeders DESC 
            LIMIT 6;
        """, recent_years)
        usa_new = [map_row_to_media(r, compact=True) for r in cur.fetchall()]
        for it in usa_new:
            excluded_ids.add(it["id"])

        cur.execute(f"""
            SELECT * FROM movies 
            WHERE {_USER_VISIBLE_SQL}
              AND year IN (?, ?)
              AND vote_count >= 30
              AND rating >= 5.5
              AND country NOT IN ('США', 'Канада')
              AND {not_in_sql()}
              AND poster_url IS NOT NULL AND poster_url != ''
            ORDER BY year DESC, vote_average DESC, seeders DESC 
            LIMIT 4;
        """, recent_years)
        world_new = [map_row_to_media(r, compact=True) for r in cur.fetchall()]
        for it in world_new:
            excluded_ids.add(it["id"])

        new_releases = []
        u_idx, w_idx = 0, 0
        while u_idx < len(usa_new) or w_idx < len(world_new):
            if u_idx < len(usa_new):
                new_releases.append(usa_new[u_idx])
                u_idx += 1
            if w_idx < len(world_new):
                new_releases.append(world_new[w_idx])
                w_idx += 1
            if u_idx < len(usa_new):
                new_releases.append(usa_new[u_idx])
                u_idx += 1

        if len(new_releases) < 10:
            needed = 10 - len(new_releases)
            cur.execute(f"""
                SELECT * FROM movies 
                WHERE {_USER_VISIBLE_SQL}
                  AND year IN (?, ?)
                  AND {not_in_sql()}
                  AND poster_url IS NOT NULL AND poster_url != ''
                ORDER BY year DESC, rating DESC, seeders DESC 
                LIMIT {needed};
            """, recent_years)
            for r in cur.fetchall():
                m = map_row_to_media(r, compact=True)
                new_releases.append(m)
                excluded_ids.add(m["id"])

        # 3. Section «Сейчас популярно» (10 items: most seeded & viewed global hits)
        cur.execute(f"""
            SELECT * FROM movies 
            WHERE {_USER_VISIBLE_SQL}
              AND {not_in_sql()} 
              AND poster_url IS NOT NULL AND poster_url != ''
            ORDER BY (seeders * 10.0 + MIN(COALESCE(vote_count, 0), 20000) * 1.5) DESC 
            LIMIT 10;
        """)
        popular = [map_row_to_media(r, compact=True) for r in cur.fetchall()]
        for it in popular:
            excluded_ids.add(it["id"])

        # 4. Section «Для вас» (10 items: Diverse IMDb Top masterpieces with genre alternation)
        cur.execute(f"""
            SELECT * FROM movies 
            WHERE {_USER_VISIBLE_SQL}
              AND rating >= 7.8 
              AND vote_count >= 300
              AND {not_in_sql()} 
              AND poster_url IS NOT NULL AND poster_url != ''
            ORDER BY (rating * 2.0 + (seeders / 500.0)) DESC 
            LIMIT 40;
        """)
        candidates = [map_row_to_media(r, compact=True) for r in cur.fetchall()]
        for_you = []
        recent_genres = []
        for cand in candidates:
            if len(for_you) >= 10:
                break
            cand_genres = cand.get("genres", [])
            primary_g = cand_genres[0] if cand_genres else "Кино"
            # Check if last 2 were the same genre
            if len(recent_genres) >= 2 and recent_genres[-1] == primary_g and recent_genres[-2] == primary_g:
                continue
            for_you.append(cand)
            recent_genres.append(primary_g)
            excluded_ids.add(cand["id"])

        if len(for_you) < 10:
            for cand in candidates:
                if len(for_you) >= 10:
                    break
                if cand["id"] not in {m["id"] for m in for_you}:
                    for_you.append(cand)
                    excluded_ids.add(cand["id"])

        # 5. Section «Сериалы и Мультсериалы» (10 items: Strictly TV / Series)
        cur.execute(f"""
            SELECT * FROM movies 
            WHERE {_USER_VISIBLE_SQL}
              AND (category IN ('series', 'tv_series', 'tv', 'limited_series') OR seasons_count > 0)
              AND {not_in_sql()} 
              AND poster_url IS NOT NULL AND poster_url != ''
            ORDER BY (rating * 1.5 + (seeders / 1000.0)) DESC 
            LIMIT 10;
        """)
        series = [map_row_to_media(r, compact=True) for r in cur.fetchall()]
        for it in series:
            excluded_ids.add(it["id"])

        sections = [
            {
                "id": "new_releases",
                "title": "Новинки",
                "items": new_releases
            },
            {
                "id": "popular_now",
                "title": "Сейчас популярно",
                "items": popular
            },
            {
                "id": "for_you",
                "title": "Для вас",
                "items": for_you
            },
            {
                "id": "series",
                "title": "Сериалы и Мультсериалы",
                "items": series
            }
        ]

        hero_banners = [featured] if featured else (popular[:1] if popular else [])

        payload = {
            "featured": featured,
            "heroBanners": hero_banners,
            "newReleases": new_releases,
            "popular": popular,
            "forYou": for_you,
            "series": series,
            "sections": sections
        }

        _home_cache = payload
        _home_cache_ts = now
        return payload

def _normalized_identity(value: str) -> str:
    return re.sub(r"[^0-9a-zа-яё]+", "", (value or "").lower())


def _repair_media_type_if_ambiguous(conn: sqlite3.Connection, row: sqlite3.Row) -> sqlite3.Row:
    if str(row["media_type"] or "").lower() != "movie":
        return row
    if str(row["category"] or "").lower() not in {"animation", "anime", "documentaries"}:
        return row
    if int(row["seasons_count"] or 0) > 0:
        return row
    tmdb_id = int(row["tmdb_id"] or 0)
    if tmdb_id <= 0:
        return row
    tv = tmdb.get_tv_details(tmdb_id)
    if not tv:
        return row
    current_names = {_normalized_identity(str(row["title"] or "")), _normalized_identity(str(row["original_title"] or ""))}
    tv_names = {_normalized_identity(str(tv.get("title") or "")), _normalized_identity(str(tv.get("original_title") or ""))}
    current_names.discard(""); tv_names.discard("")
    if not current_names.intersection(tv_names):
        return row
    current_year = int(row["year"] or 0)
    tv_year = int(tv.get("year") or 0)
    if current_year and tv_year and abs(current_year - tv_year) > 1:
        return row
    existing_tv = conn.execute("SELECT id FROM movies WHERE media_type='tv' AND tmdb_id=? AND id!=?", (tmdb_id, int(row["id"]))).fetchone()
    if existing_tv:
        conn.execute("DELETE FROM movies WHERE id=?", (int(existing_tv["id"]),))
    conn.execute("UPDATE movies SET media_type='tv' WHERE id=?", (int(row["id"]),))
    conn.commit()
    return conn.execute("SELECT * FROM movies WHERE id=?", (int(row["id"]),)).fetchone()


def _enrich_tv_structure_if_needed(conn: sqlite3.Connection, row: sqlite3.Row) -> sqlite3.Row:
    if str(row["media_type"] or "").lower() != "tv":
        return row
    existing_counts = parse_json_safely(row["season_episode_counts"], [])
    if isinstance(existing_counts, list) and any(int(x or 0) > 0 for x in existing_counts):
        return row
    tmdb_id = int(row["tmdb_id"] or 0)
    if tmdb_id <= 0:
        return row
    details = tmdb.get_tv_details(tmdb_id)
    if not details:
        return row
    counts = [int(x or 0) for x in details.get("season_episode_counts", [])]
    if not counts:
        return row
    cast_json = json.dumps(details.get("cast") or [], ensure_ascii=False)
    conn.execute(
        """
        UPDATE movies SET
            duration_minutes = CASE WHEN ? > 0 THEN ? ELSE duration_minutes END,
            seasons_count = ?,
            episodes_count = ?,
            season_episode_counts = ?,
            "cast" = CASE WHEN ? != '[]' THEN ? ELSE "cast" END,
            synopsis = CASE WHEN synopsis IS NULL OR synopsis = '' THEN ? ELSE synopsis END
        WHERE id = ?
        """,
        (
            int(details.get("duration_minutes") or 0), int(details.get("duration_minutes") or 0),
            int(details.get("seasons_count") or len(counts)), int(details.get("episodes_count") or sum(counts)),
            json.dumps(counts), cast_json, cast_json, details.get("synopsis") or "", int(row["id"]),
        ),
    )
    conn.commit()
    return conn.execute("SELECT * FROM movies WHERE id=?", (int(row["id"]),)).fetchone()


def _is_east_asian_item(item: Dict[str, Any]) -> bool:
    return str(item.get("country") or "") in EAST_ASIA_COUNTRIES


_EUROPE_CATALOG_COUNTRIES = frozenset({
    "Великобритания", "Франция", "Германия", "Италия", "Испания", "Португалия",
    "Швеция", "Дания", "Норвегия", "Финляндия", "Нидерланды", "Бельгия",
    "Польша", "Ирландия", "Австрия", "Швейцария", "Чехия", "Венгрия",
    "Румыния", "Исландия", "Греция", "Россия", "СССР", "Беларусь", "Украина",
})


def _catalog_region_bucket(item: Dict[str, Any]) -> str:
    country = str(item.get("country") or "")
    if country in {"США", "Канада"}:
        return "north_america"
    if country in EAST_ASIA_COUNTRIES:
        return "east_asia"
    if country in _EUROPE_CATALOG_COUNTRIES:
        return "europe"
    return "other"


def _interleave_catalog_regions(items: List[Dict[str, Any]], max_streak: int = 2) -> List[Dict[str, Any]]:
    """Preserve rank as much as possible while breaking regional blocks.

    The input has already passed page-level regional caps. This second phase
    only changes presentation order: after two cards from the same broad
    region, the highest-ranked available card from another region is pulled
    forward. No item is added or removed.
    """
    remaining = list(items)
    result: List[Dict[str, Any]] = []
    while remaining:
        preferred_index = 0
        if len(result) >= max_streak:
            recent = [_catalog_region_bucket(x) for x in result[-max_streak:]]
            if len(set(recent)) == 1:
                blocked = recent[0]
                for idx, candidate in enumerate(remaining):
                    if _catalog_region_bucket(candidate) != blocked:
                        preferred_index = idx
                        break
        result.append(remaining.pop(preferred_index))
    return result


def _balanced_catalog_items(items: List[Dict[str, Any]], target: int) -> List[Dict[str, Any]]:
    """Return a ranked neutral feed with prefix-stable diversity.

    Quotas are evaluated for every output position rather than only for the
    final page size. Therefore the first 10 cards are already mixed, and the
    first page stays a prefix of later pages. Ranking order is preserved inside
    the constraints: each slot takes the highest-ranked still-eligible item.
    """
    if target <= 0:
        return []

    unique = []
    seen: set[str] = set()
    for item in items:
        iid = str(item.get("id"))
        if not iid or iid in seen:
            continue
        seen.add(iid)
        unique.append(item)

    selected: List[Dict[str, Any]] = []
    selected_ids: set[str] = set()
    asian = north_america = tv = animation = 0

    def flags(item: Dict[str, Any]) -> tuple[bool, bool, bool, bool]:
        is_asian = _is_east_asian_item(item)
        is_north_america = str(item.get("country") or "") in {"США", "Канада"}
        is_tv = str(item.get("mediaType") or "") == "tv"
        is_anim = str(item.get("category") or "").upper() in {"ANIMATION", "ANIME"}
        return is_asian, is_north_america, is_tv, is_anim

    while len(selected) < target:
        position = len(selected) + 1
        # Prefix caps. ceil() allows a category to appear early, but prevents
        # long homogeneous runs such as 12 US cards before any other region.
        max_asian = max(1, int(math.ceil(position * 0.20)))
        max_north_america = max(1, int(math.ceil(position * 0.45)))
        max_tv = max(1, int(math.ceil(position * 0.55)))
        max_animation = max(1, int(math.ceil(position * 0.25)))

        chosen = None
        # Pass 1: enforce both regional and content-form diversity.
        for item in unique:
            iid = str(item.get("id"))
            if iid in selected_ids:
                continue
            is_asian, is_na, is_tv, is_anim = flags(item)
            if is_asian and asian + 1 > max_asian:
                continue
            if is_na and north_america + 1 > max_north_america:
                continue
            if is_tv and tv + 1 > max_tv:
                continue
            if is_anim and animation + 1 > max_animation:
                continue
            chosen = item
            break

        # Pass 2: if the source pool is narrow, relax type/animation caps but
        # keep regional caps strict. This is the user-visible anti-skew rule.
        if chosen is None:
            for item in unique:
                iid = str(item.get("id"))
                if iid in selected_ids:
                    continue
                is_asian, is_na, _, _ = flags(item)
                if is_asian and asian + 1 > max_asian:
                    continue
                if is_na and north_america + 1 > max_north_america:
                    continue
                chosen = item
                break

        if chosen is None:
            break

        iid = str(chosen.get("id"))
        is_asian, is_na, is_tv, is_anim = flags(chosen)
        selected.append(chosen)
        selected_ids.add(iid)
        asian += int(is_asian)
        north_america += int(is_na)
        tv += int(is_tv)
        animation += int(is_anim)

    return _interleave_catalog_regions(selected)


def _similar_items(
    cur: sqlite3.Cursor,
    *,
    current: Dict[str, Any],
    media_type: str,
    excluded_ids: set[str],
    limit: int = 8,
) -> List[Dict[str, Any]]:
    target_genres = {str(x).casefold() for x in (current.get("genres") or []) if x}
    if not target_genres:
        return []
    cur.execute(
        f"SELECT * FROM movies WHERE {_USER_VISIBLE_SQL} AND media_type=? ORDER BY {SCORE_SQL} DESC LIMIT 1200",
        (media_type,),
    )
    target_country = str(current.get("country") or "")
    target_year = int(current.get("year") or 0)
    target_category = str(current.get("category") or "")
    target_is_asian = target_country in EAST_ASIA_COUNTRIES
    ranked: list[tuple[float, Dict[str, Any]]] = []
    for row in cur.fetchall():
        item = map_row_to_media(row, compact=True)
        if str(item.get("id")) in excluded_ids:
            continue
        cgenres = {str(x).casefold() for x in (item.get("genres") or []) if x}
        shared = target_genres & cgenres
        same_country = str(item.get("country") or "") == target_country and bool(target_country)
        year = int(item.get("year") or 0)
        close_era = bool(target_year and year and abs(year - target_year) <= 7)
        if len(shared) < 2 and not (len(shared) == 1 and same_country and close_era):
            continue
        union = target_genres | cgenres
        jaccard = len(shared) / max(1, len(union))
        votes = int(item.get("vote_count") or 0)
        score = len(shared) * 4.0 + jaccard * 7.0
        score += 2.0 if str(item.get("category") or "") == target_category else 0.0
        score += 1.5 if same_country else 0.0
        score += 1.0 if close_era else 0.0
        score += min(2.0, math.log10(max(1, votes)) / 2.0)
        score += float(item.get("rating") or 0.0) * 0.20
        if not target_is_asian and _is_east_asian_item(item):
            score -= 2.5
        ranked.append((score, item))
    ranked.sort(key=lambda pair: (-pair[0], -int(pair[1].get("vote_count") or 0), -float(pair[1].get("rating") or 0), str(pair[1].get("id"))))
    result: List[Dict[str, Any]] = []
    max_asian = limit if target_is_asian else max(1, int(limit * 0.20))
    asian_count = 0
    for _, item in ranked:
        is_asian = _is_east_asian_item(item)
        if is_asian and asian_count >= max_asian:
            continue
        result.append(item)
        asian_count += int(is_asian)
        if len(result) >= limit:
            break
    return result


def _refresh_authoritative_metadata_if_needed(conn: sqlite3.Connection, row: sqlite3.Row) -> sqlite3.Row:
    d = dict(row)
    source = str(d.get("metadata_source") or "")
    updated = str(d.get("metadata_updated_at") or "")
    stale = not updated
    if updated:
        try:
            parsed = datetime.fromisoformat(updated.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            stale = (datetime.now(timezone.utc) - parsed).total_seconds() > 30 * 86400
        except (TypeError, ValueError, OverflowError):
            stale = True
    if source == "tmdb_detail" and not stale:
        return row
    tmdb_id = int(d.get("tmdb_id") or 0)
    media_type = str(d.get("media_type") or "").lower()
    if tmdb_id <= 0 or media_type not in {"movie", "tv"}:
        return row
    try:
        detail = tmdb.get_tv_details(tmdb_id) if media_type == "tv" else tmdb.get_movie_details(tmdb_id)
        if detail:
            from metadata_repair import apply_authoritative_metadata
            apply_authoritative_metadata(conn, int(d.get("id")), detail)
            conn.commit()
            refreshed = conn.execute("SELECT * FROM movies WHERE id=?", (int(d.get("id")),)).fetchone()
            return refreshed or row
    except Exception as exc:
        print(f"[METADATA] lazy detail refresh failed id={d.get('id')}: {type(exc).__name__}: {exc}")
    return row


def get_movie_details(movie_id: str) -> Optional[Dict[str, Any]]:
    with get_db() as conn:
        cur = conn.cursor()
        
        # API ids emitted by Movia are local row ids. Prefer them deterministically;
        # only then fall back to external TMDb id or exact title.
        query_id = movie_id.replace("m_", "").strip()
        row = None
        if query_id.isdigit():
            cur.execute("SELECT * FROM movies WHERE id=? LIMIT 1", (int(query_id),))
            row = cur.fetchone()
        if row is None and query_id.isdigit():
            external_rows = cur.execute(
                "SELECT * FROM movies WHERE tmdb_id=? "
                "AND media_type IN ('movie','tv') LIMIT 2",
                (int(query_id),),
            ).fetchall()
            # An external id without media type is not enough to choose a
            # canonical entity when the catalog contains both forms.
            row = external_rows[0] if len(external_rows) == 1 else None
        if row is None:
            title_rows = cur.execute(
                "SELECT * FROM movies WHERE localized_ru_title=? "
                "OR original_title=? LIMIT 2",
                (movie_id, movie_id),
            ).fetchall()
            row = title_rows[0] if len(title_rows) == 1 else None
        if not row or not is_user_visible_row(dict(row)):
            return None
        row = _repair_media_type_if_ambiguous(conn, row)
        row = _refresh_authoritative_metadata_if_needed(conn, row)
        row = _enrich_tv_structure_if_needed(conn, row)
        if not row or not is_user_visible_row(dict(row)):
            return None
        movie_dict = map_row_to_media(row, compact=False)
        current_id = movie_dict["id"]
        genres = movie_dict["genres"]
        country = movie_dict["country"]
        year = movie_dict["year"]
        title = movie_dict["title"]
        media_type = str(row["media_type"] or "").lower()

        # 1. Sequels and Prequels (strictly by official TMDB collection_id)
        collection_id = movie_dict.get("collection_id") or (row["collection_id"] if "collection_id" in row.keys() else None)
        sequels = []
        if collection_id and int(collection_id) > 0:
            cur.execute(f"""
                SELECT * FROM movies 
                WHERE {_USER_VISIBLE_SQL}
                  AND collection_id = ? AND id != ?
                ORDER BY year ASC, rating DESC;
            """, (int(collection_id), current_id))
            sequels = [map_row_to_media(r, compact=True) for r in cur.fetchall()]

        sequel_ids = {s["id"] for s in sequels}
        sequel_ids.add(current_id)

        # 2. Similar content uses the complete genre set, canonical type,
        # era/country affinity and vote confidence. It never relies on the
        # first two genre array positions.
        similar = _similar_items(
            cur,
            current=movie_dict,
            media_type=media_type,
            excluded_ids={str(x) for x in sequel_ids},
            limit=8,
        )

        # Build universal JSON response supporting both direct fields and nested movie
        response = dict(movie_dict)
        response["sequels_and_prequels"] = sequels
        response["sequels"] = sequels
        response["similar"] = similar
        response["movie"] = dict(movie_dict)
        response["movie"]["sequels"] = sequels
        response["movie"]["similar"] = similar

        return response

def get_catalog_paged(
    limit: int = 40,
    offset: int = 0,
    sort: str = "POPULAR",
    category: Optional[str] = None,
    genre: Optional[str] = None,
    year_from: Optional[int] = None,
    year_to: Optional[int] = None,
    min_rating: Optional[float] = None,
    country: Optional[str] = None,
    query_text: Optional[str] = None
) -> Dict[str, Any]:
    conditions = [_USER_VISIBLE_SQL]
    params: List[Any] = []

    if category and category.upper() not in ["ALL", "ВСЕ"]:
        cat_u = category.upper()
        if cat_u in ["TV_SERIES", "СЕРИАЛЫ"]:
            conditions.append("(category = 'series' OR category = 'tv_series' OR category = 'limited_series')")
        elif cat_u in ["ANIMATION", "АНИМАЦИЯ"]:
            conditions.append("category = 'animation'")
        elif cat_u in ["ANIME", "АНИМЕ"]:
            conditions.append("category = 'anime'")
        elif cat_u in ["DRAMAS_ASIAN", "ДОРАМЫ"]:
            conditions.append("(category = 'dramas_asian' OR country = 'Южная Корея')")
        elif cat_u in ["DOCUMENTARIES", "ДОКУМЕНТАЛЬНЫЕ"]:
            conditions.append("category = 'documentaries'")
        else:
            conditions.append("category = 'movies'")

    if genre:
        conditions.append("genres LIKE ?")
        params.append(f"%{genre}%")

    if year_from is not None:
        conditions.append("year >= ?")
        params.append(year_from)

    if year_to is not None:
        conditions.append("year <= ?")
        params.append(year_to)

    if min_rating is not None and min_rating > 0.0:
        conditions.append("rating >= ?")
        params.append(min_rating)

    if country:
        conditions.append("country LIKE ?")
        params.append(f"%{country}%")

    if query_text:
        q = f"%{query_text.strip()}%"
        conditions.append("(title LIKE ? OR original_title LIKE ? OR director LIKE ?)")
        params.extend([q, q, q])

    where_sql = " WHERE " + " AND ".join(conditions) if conditions else ""

    sort_sql = f"ORDER BY {SCORE_SQL} DESC"
    sort_u = (sort or "POPULAR").upper()
    if sort_u == "RATING":
        sort_sql = f"ORDER BY rating DESC, {SCORE_SQL} DESC"
    elif sort_u == "NEWEST":
        sort_sql = f"ORDER BY year DESC, {SCORE_SQL} DESC"
    elif sort_u == "OLDEST":
        sort_sql = "ORDER BY year ASC, rating DESC"
    elif sort_u == "TITLE":
        sort_sql = "ORDER BY title ASC"

    with get_db() as conn:
        cur = conn.cursor()
        
        count_query = f"SELECT COUNT(*) FROM movies {where_sql};"
        cur.execute(count_query, tuple(params))
        total_count = cur.fetchone()[0]

        neutral_popular = (
            sort_u == "POPULAR" and not category and not genre and year_from is None
            and year_to is None and not min_rating and not country and not query_text
        )
        if neutral_popular:
            target = max(0, offset) + max(1, limit)
            candidate_limit = min(max(600, target * 14), 20000)
            items_query = f"SELECT * FROM movies {where_sql} {sort_sql} LIMIT ?;"
            cur.execute(items_query, tuple(list(params) + [candidate_limit]))
            candidates = [map_row_to_media(r, compact=True) for r in cur.fetchall()]
            balanced = _balanced_catalog_items(candidates, target)
            items = balanced[offset:offset + limit]
        else:
            items_query = f"SELECT * FROM movies {where_sql} {sort_sql} LIMIT ? OFFSET ?;"
            query_params = list(params) + [limit, offset]
            cur.execute(items_query, tuple(query_params))
            items = [map_row_to_media(r, compact=True) for r in cur.fetchall()]

        return {
            "total": total_count,
            "items": items,
            "limit": limit,
            "offset": offset
        }

def search_catalog(query_text: str, limit: int = 20) -> Dict[str, Any]:
    if not query_text or not query_text.strip():
        return {"movies": [], "people": []}
    
    q_clean = query_text.strip()
    q_wild = f"%{q_clean}%"

    with get_db() as conn:
        cur = conn.cursor()
        
        cur.execute(f"""
            SELECT * FROM movies 
            WHERE title LIKE ? OR original_title LIKE ? OR director LIKE ?
            ORDER BY 
                CASE 
                    WHEN title = ? THEN 1
                    WHEN title LIKE ? THEN 2
                    ELSE 3
                END,
                {SCORE_SQL} DESC
            LIMIT ?;
        """, (q_wild, q_wild, q_wild, q_clean, f"{q_clean}%", limit))
        
        movies = [map_row_to_media(r, compact=True) for r in cur.fetchall()]

        # A brand-new title may not have reached the periodic metadata sync yet.
        # On an exact local miss, discover metadata immediately and re-run locally.
        if not movies:
            try:
                import live_catalog_sync
                live_catalog_sync.discover_query(q_clean, limit=max(limit, 20))
                cur.execute(f"""
                    SELECT * FROM movies
                    WHERE title LIKE ? OR original_title LIKE ? OR director LIKE ?
                    ORDER BY
                        CASE
                            WHEN title = ? THEN 1
                            WHEN title LIKE ? THEN 2
                            ELSE 3
                        END,
                        {SCORE_SQL} DESC
                    LIMIT ?;
                """, (q_wild, q_wild, q_wild, q_clean, f"{q_clean}%", limit))
                movies = [map_row_to_media(r, compact=True) for r in cur.fetchall()]
            except Exception as exc:
                print(f"[CATALOG-SEARCH] live metadata discovery failed: {exc}")

        cur.execute("""
            SELECT DISTINCT director, poster_url FROM movies 
            WHERE director LIKE ? AND director != '' 
            LIMIT 5;
        """, (q_wild,))
        
        people = []
        for r in cur.fetchall():
            d_name = r["director"]
            if d_name:
                people.append({
                    "name": d_name,
                    "photoUrl": r["poster_url"],
                    "photo_url": r["poster_url"],
                    "role": "Режиссёр",
                    "knownFor": []
                })

        return {
            "movies": movies,
            "people": people
        }

def get_all_genres() -> List[str]:
    return [
        "аниме", "биография", "боевик", "вестерн", "военный",
        "детектив", "детский", "документальный", "драма", "игра",
        "история", "комедия", "концерт", "короткометражка", "криминал",
        "мелодрама", "музыка", "мультфильм", "мюзикл", "новости",
        "приключения", "реальное ТВ", "семейный", "спорт", "ток-шоу",
        "триллер", "ужасы", "фантастика", "фильм-нуар", "фэнтези", "церемония"
    ]

def clear_torrent_cache_dir() -> Dict[str, Any]:
    cache_dir = DIR / "torrent_cache"
    cleared_bytes = 0
    if cache_dir.exists():
        for item in cache_dir.iterdir():
            try:
                if item.is_file() or item.is_symlink():
                    cleared_bytes += item.stat().st_size
                    item.unlink()
                elif item.is_dir():
                    for sub in item.rglob("*"):
                        if sub.is_file():
                            cleared_bytes += sub.stat().st_size
                    shutil.rmtree(item, ignore_errors=True)
            except Exception as e:
                print(f"[CACHE] Error deleting {item}: {e}")
    return {"status": "ok", "cleared_bytes": cleared_bytes}


# --- Search/catalog v2 overrides ---
# Kept at module bottom so the migration can be adopted without deleting the
# existing playback/detail code or changing the current catalog identity.
from catalog_schema_v2 import ensure_schema as _ensure_catalog_schema
from catalog_schema_v2 import get_revision as _get_catalog_revision
from search_service import category_condition as _search_category_condition
from search_service import genre_values as _search_genre_values
from search_service import search_page as _indexed_search_page

try:
    _CATALOG_SCHEMA_BOOTSTRAP = _ensure_catalog_schema(DB_PATH)
except Exception as _schema_exc:
    _CATALOG_SCHEMA_BOOTSTRAP = {"error": f"{type(_schema_exc).__name__}: {_schema_exc}"}


def _catalog_conditions(
    category=None,
    genre=None,
    year_from=None,
    year_to=None,
    min_rating=None,
    country=None,
    media_type=None,
):
    conditions = [_USER_VISIBLE_SQL]
    params = []
    category_sql = _search_category_condition(category)
    if category_sql:
        conditions.append(category_sql)
        if category_sql == "category=?":
            params.append(str(category).lower())
    if media_type and str(media_type).lower() in {"movie", "tv"}:
        conditions.append("media_type=?")
        params.append(str(media_type).lower())
    genre_list = _search_genre_values(genre)
    if genre_list:
        conditions.append("(" + " OR ".join("genres LIKE ?" for _ in genre_list) + ")")
        params.extend(f"%{value}%" for value in genre_list)
    if year_from is not None:
        conditions.append("year >= ?")
        params.append(int(year_from))
    if year_to is not None:
        conditions.append("year <= ?")
        params.append(int(year_to))
    if min_rating is not None and float(min_rating) > 0:
        conditions.append("rating >= ?")
        params.append(float(min_rating))
    if country:
        conditions.append("country LIKE ?")
        params.append(f"%{country}%")
    return conditions, params


def _catalog_sort_sql(sort):
    value = str(sort or "POPULAR").upper()
    if value == "RATING":
        return "rating DESC, vote_count DESC, seeders DESC, id ASC"
    if value == "NEWEST":
        return "year DESC, rating DESC, vote_count DESC, id ASC"
    if value == "OLDEST":
        return "year ASC, rating DESC, vote_count DESC, id ASC"
    if value == "TITLE":
        return "normalized_ru_title COLLATE BINARY ASC, id ASC"
    return f"{SCORE_SQL} DESC, id ASC"


def _search_people_for_query(conn, normalized_query, limit):
    if not normalized_query:
        return []
    like = f"%{normalized_query}%"
    rows = conn.execute(
        "SELECT DISTINCT director, poster_url FROM movies "
        "WHERE director IS NOT NULL AND director!='' "
        "AND instr(lower(director), ?) > 0 LIMIT ?",
        (normalized_query, max(1, min(int(limit), 50))),
    ).fetchall()
    return [
        {
            "name": row["director"],
            "photoUrl": row["poster_url"],
            "photo_url": row["poster_url"],
            "role": "Режиссёр",
            "knownFor": [],
        }
        for row in rows
    ]


def _map_search_page(page, people, source="LOCAL"):
    return {
        "status": "OK" if page.rows or people else "NO_RESULTS",
        "source": source,
        "movies": [map_row_to_media(row, compact=True) for row in page.rows],
        "people": people,
        "total": page.total,
        "prefixCount": page.prefix_count,
        "exactCount": page.exact_count,
        "topScore": page.top_score,
        "weakLocal": page.exact_count == 0 and (
            page.prefix_count == 0 or page.total < 3 or page.top_score < 700
        ),
    }


def search_catalog(query_text: str, limit: int = 20, discover: bool = True) -> Dict[str, Any]:
    """Search canonical Russian titles, optionally enriching a weak local hit.

    Local indexed results are always computed first. Remote discovery is only
    eligible for normalized queries of at least three characters and is never
    used for one- or two-character live keystrokes.
    """
    from catalog_schema_v2 import normalize_ru_text

    normalized_query = normalize_ru_text(query_text)
    if not normalized_query:
        # Clearing search returns the ordinary bounded catalog feed. The
        # response keeps the search shape for Android clients but never
        # represents a healthy catalog as zero results.
        page = get_catalog_paged(
            limit=max(1, min(int(limit), 60)),
            offset=0,
            sort="POPULAR",
        )
        items = page.get("items", []) if isinstance(page, dict) else []
        return {
            "status": "OK" if items else page.get("status", "NO_RESULTS"),
            "source": "LOCAL",
            "movies": items,
            "people": [],
            "items": items,
            "total": int(page.get("total", 0) or 0) if isinstance(page, dict) else 0,
            "catalogRevision": page.get("catalogRevision") if isinstance(page, dict) else None,
            "search": False,
            "emptyQuery": True,
        }

    try:
        with get_db() as conn:
            page = _indexed_search_page(conn, normalized_query, limit=limit)
            people = _search_people_for_query(conn, normalized_query, limit)
            local_payload = _map_search_page(page, people, "LOCAL")

        remote_result = None
        if discover and len(normalized_query) >= 3 and local_payload["weakLocal"]:
            try:
                import live_catalog_sync
                remote_result = live_catalog_sync.discover_query(
                    normalized_query, limit=max(int(limit), 20)
                )
            except Exception as exc:
                remote_result = {
                    "error": f"{type(exc).__name__}: {exc}",
                    "seen": 0,
                    "inserted": 0,
                    "updated": 0,
                }

            with get_db() as conn:
                refreshed = _indexed_search_page(
                    conn, normalized_query, limit=limit
                )
                people = _search_people_for_query(conn, normalized_query, limit)
                final_payload = _map_search_page(
                    refreshed,
                    people,
                    "DISCOVERED" if remote_result and not remote_result.get("error") else "LOCAL",
                )
                final_payload["discovery"] = remote_result
                final_payload["catalogRevision"] = _get_catalog_revision(conn)
        else:
            final_payload = local_payload
            with get_db() as conn:
                final_payload["catalogRevision"] = _get_catalog_revision(conn)

        discovery_error = (
            remote_result.get("error")
            if isinstance(remote_result, dict) else None
        )
        if discovery_error and not final_payload.get("movies") and not final_payload.get("people"):
            final_payload["status"] = "NETWORK_ERROR"
            final_payload["errorCode"] = "PROVIDER_TIMEOUT_OR_NETWORK"
            final_payload["error"] = str(discovery_error)
        elif discovery_error:
            final_payload["remoteError"] = str(discovery_error)
        return final_payload
    except sqlite3.OperationalError as exc:
        return {
            "status": "DB_ERROR",
            "errorCode": "DB_ERROR",
            "error": str(exc),
            "source": "LOCAL",
            "movies": [],
            "people": [],
            "total": 0,
        }
    except Exception as exc:
        return {
            "status": "BACKEND_ERROR",
            "errorCode": "BACKEND_ERROR",
            "error": f"{type(exc).__name__}: {exc}",
            "source": "LOCAL",
            "movies": [],
            "people": [],
            "total": 0,
        }


def _get_catalog_paged_base(
    limit=40,
    offset=0,
    sort="POPULAR",
    category=None,
    genre=None,
    year_from=None,
    year_to=None,
    min_rating=None,
    country=None,
    query_text=None,
    media_type=None,
):
    """Return one bounded catalog page; query and filters share one SQL path."""
    from catalog_schema_v2 import normalize_ru_text

    bounded_limit = max(1, min(int(limit), 60))
    bounded_offset = max(0, int(offset))
    if query_text and normalize_ru_text(query_text):
        try:
            with get_db() as conn:
                page = _indexed_search_page(
                    conn,
                    query_text,
                    limit=bounded_limit,
                    offset=bounded_offset,
                    category=category,
                    genre=genre,
                    year_from=year_from,
                    year_to=year_to,
                    min_rating=min_rating,
                    country=country,
                    media_type=media_type,
                )
                return {
                    "status": "OK" if page.rows else "NO_RESULTS",
                    "total": page.total,
                    "items": [map_row_to_media(row, compact=True) for row in page.rows],
                    "limit": bounded_limit,
                    "offset": bounded_offset,
                    "catalogRevision": _get_catalog_revision(conn),
                    "search": True,
                    "prefixCount": page.prefix_count,
                    "exactCount": page.exact_count,
                    "topScore": page.top_score,
                    "weakLocal": page.exact_count == 0 and (page.prefix_count == 0 or page.total < 3 or page.top_score < 700),
                }
        except sqlite3.OperationalError as exc:
            return {
                "status": "DB_ERROR",
                "errorCode": "DB_ERROR",
                "error": str(exc),
                "total": 0,
                "items": [],
                "limit": bounded_limit,
                "offset": bounded_offset,
            }

    conditions, params = _catalog_conditions(
        category, genre, year_from, year_to, min_rating, country, media_type
    )
    where = " AND ".join(conditions)
    try:
        with get_db() as conn:
            total = int(conn.execute(
                "SELECT COUNT(*) FROM movies WHERE " + where, params
            ).fetchone()[0] or 0)
            neutral_popular = (
                str(sort or "POPULAR").upper() == "POPULAR"
                and not category and not genre and year_from is None and year_to is None
                and not min_rating and not country and not media_type
            )
            if neutral_popular:
                target = bounded_offset + bounded_limit
                candidate_limit = min(max(800, target * 16), 20000)
                candidate_rows = conn.execute(
                    "SELECT * FROM movies WHERE " + where
                    + " ORDER BY " + _catalog_sort_sql(sort)
                    + " LIMIT ?",
                    [*params, candidate_limit],
                ).fetchall()
                # Diversity only needs stable identity + region/form metadata.
                # Do NOT map every candidate here: map_row_to_media validates
                # persisted streams and is intentionally expensive. Mapping 800
                # candidates made even limit=1 exceed Android's 6s read timeout.
                proxies = [
                    {
                        "id": str(row["id"]),
                        "country": str(row["country"] or ""),
                        "mediaType": str(row["media_type"] or ""),
                        "category": str(row["category"] or "").upper(),
                    }
                    for row in candidate_rows
                ]
                balanced = _balanced_catalog_items(proxies, target)
                row_by_id = {str(row["id"]): row for row in candidate_rows}
                page_proxies = balanced[bounded_offset:bounded_offset + bounded_limit]
                items = [
                    map_row_to_media(row_by_id[str(proxy["id"])], compact=True)
                    for proxy in page_proxies
                    if str(proxy["id"]) in row_by_id
                ]
            else:
                rows = conn.execute(
                    "SELECT * FROM movies WHERE " + where
                    + " ORDER BY " + _catalog_sort_sql(sort)
                    + " LIMIT ? OFFSET ?",
                    [*params, bounded_limit, bounded_offset],
                ).fetchall()
                items = [map_row_to_media(row, compact=True) for row in rows]
            return {
                "status": "OK" if items else ("NO_RESULTS" if total == 0 else "OK"),
                "total": total,
                "items": items,
                "limit": bounded_limit,
                "offset": bounded_offset,
                "catalogRevision": _get_catalog_revision(conn),
                "search": False,
            }
    except sqlite3.OperationalError as exc:
        return {
            "status": "DB_ERROR",
            "errorCode": "DB_ERROR",
            "error": str(exc),
            "total": 0,
            "items": [],
            "limit": bounded_limit,
            "offset": bounded_offset,
        }


def get_catalog_paged(
    limit=40,
    offset=0,
    sort="POPULAR",
    category=None,
    genre=None,
    year_from=None,
    year_to=None,
    min_rating=None,
    country=None,
    query_text=None,
    media_type=None,
    discover=False,
):
    """Return a local page, optionally followed by bounded query discovery."""
    from catalog_schema_v2 import normalize_ru_text

    payload = _get_catalog_paged_base(
        limit=limit,
        offset=offset,
        sort=sort,
        category=category,
        genre=genre,
        year_from=year_from,
        year_to=year_to,
        min_rating=min_rating,
        country=country,
        query_text=query_text,
        media_type=media_type,
    )
    normalized = normalize_ru_text(query_text)
    if (
        not discover
        or not normalized
        or len(normalized) < 3
        or max(0, int(offset)) != 0
        or payload.get("status") in {"DB_ERROR", "BACKEND_ERROR"}
        or not payload.get("search")
    ):
        return payload

    exact_count = int(payload.get("exactCount", 0) or 0)
    prefix_count = int(payload.get("prefixCount", 0) or 0)
    total = int(payload.get("total", 0) or 0)
    top_score = int(payload.get("topScore", 0) or 0)
    weak_local = exact_count == 0 and (
        prefix_count == 0 or total < 3 or top_score < 700
    )
    if not weak_local:
        return payload

    try:
        import live_catalog_sync
        remote_result = live_catalog_sync.discover_query(
            normalized,
            limit=max(20, min(int(limit), 60)),
        )
    except Exception as exc:
        remote_result = {
            "error": f"{type(exc).__name__}: {exc}",
            "seen": 0,
            "inserted": 0,
            "updated": 0,
        }
    refreshed = _get_catalog_paged_base(
        limit=limit,
        offset=offset,
        sort=sort,
        category=category,
        genre=genre,
        year_from=year_from,
        year_to=year_to,
        min_rating=min_rating,
        country=country,
        query_text=query_text,
        media_type=media_type,
    )
    refreshed["discovery"] = remote_result
    discovery_error = (
        remote_result.get("error")
        if isinstance(remote_result, dict) else None
    )
    if discovery_error and not refreshed.get("items"):
        refreshed["status"] = "NETWORK_ERROR"
        refreshed["errorCode"] = "PROVIDER_TIMEOUT_OR_NETWORK"
        refreshed["error"] = str(discovery_error)
    elif discovery_error:
        refreshed["remoteError"] = str(discovery_error)
    return refreshed
