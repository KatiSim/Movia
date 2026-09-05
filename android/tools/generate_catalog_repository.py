#!/usr/bin/env python3
"""Deterministic generator for Movia's bundled catalog.

The database category is authoritative for the content type:
- movies -> ContentType.MOVIE
- series -> ContentType.SERIES

Genres only refine the catalog category (animation, documentary, etc.).
No index-based heuristics are allowed: a movie must never become a series
because of its position in the database.
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
from collections import Counter
from pathlib import Path
from typing import Any

PROJECT_ROOT = Path("/data/data/com.termux/files/home/projects/movia")
DB_PATH = Path("/data/data/com.termux/files/home/projects/media-parser/media_catalog.db")
OUTPUT_PATH = PROJECT_ROOT / "app/src/main/java/app/movia/android/data/catalog/CatalogRepository.kt"

SERIES_CATEGORY_TOKENS = (
    "series",
    "serial",
    "сериал",
    "tv",
    "show",
    "limited",
    "mini",
    "мини",
)

ANIMATION_GENRE_TOKENS = (
    "animation",
    "animated",
    "мультфильм",
    "мультсериал",
    "анимация",
    "аниме",
    "anime",
)

ANIME_GENRE_TOKENS = ("anime", "аниме")
DOCUMENTARY_GENRE_TOKENS = (
    "documentary",
    "документальный",
    "документал",
)

ASIAN_DRAMA_GENRE_TOKENS = (
    "дорама",
    "корей",
    "k-drama",
    "asian drama",
)

CATEGORY_ORDER = {
    "MOVIES": 0,
    "TV_SERIES": 1,
    "LIMITED_SERIES": 2,
    "ANIMATION": 3,
    "ANIME": 4,
    "DRAMAS_ASIAN": 5,
    "DOCUMENTARIES": 6,
    "THEATER_MUSICALS": 7,
    "STANDUP": 8,
    "INTERACTIVE": 9,
}


def normalize_text(value: Any) -> str:
    if value is None:
        return ""
    text = str(value).strip().lower().replace("ё", "е")
    return re.sub(r"[^0-9a-zа-я]+", " ", text).strip()


def contains_any(text: str, tokens: tuple[str, ...]) -> bool:
    normalized = normalize_text(text)
    return any(normalize_text(token) in normalized for token in tokens)


def parse_collection(value: Any) -> list[Any]:
    if value is None or str(value).strip() == "":
        return []
    raw = str(value).strip()
    if raw.startswith("["):
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, list) else []
        except (TypeError, ValueError, json.JSONDecodeError):
            pass
    return [part.strip() for part in raw.split(",") if part.strip()]


def kotlin_str(value: Any) -> str:
    if value is None or str(value).strip() == "":
        return "null"
    # JSON escaping is valid for Kotlin ordinary strings and preserves
    # quotes, slashes, CR/LF, tabs, Unicode and control characters.
    dumped = json.dumps(str(value), ensure_ascii=False)
    # Kotlin string templates treat '$' specially.
    return dumped.replace("$", r"\$")


def kotlin_set(value: Any) -> str:
    elements = [
        kotlin_str(item)
        for item in parse_collection(value)
        if str(item).strip()
    ]
    return f"setOf({', '.join(elements)})" if elements else "emptySet()"


def kotlin_list(value: Any) -> str:
    elements = [
        kotlin_str(item)
        for item in parse_collection(value)
        if str(item).strip()
    ]
    return f"listOf({', '.join(elements)})" if elements else "emptyList()"


def is_series_source(category: Any) -> bool:
    category_text = normalize_text(category or "movies")
    if not category_text:
        category_text = "movies"
    return (
        category_text in {"series", "serial", "сериал", "сериалы", "tv", "show"}
        or contains_any(category_text, SERIES_CATEGORY_TOKENS)
    )


def classify_row(row: dict[str, Any]) -> tuple[str, str]:
    """Return (ContentType enum, CatalogCategory enum).

    The source category controls MOVIE/SERIES. Genres never override that
    type; they only select a more precise catalog category.
    """
    source_category = normalize_text(row.get("category") or "movies")
    genres_text = normalize_text(row.get("genres") or "")
    source_is_series = is_series_source(source_category)

    if contains_any(source_category, ("limited", "mini", "мини")):
        catalog_category = "CatalogCategory.LIMITED_SERIES"
    elif contains_any(genres_text, ANIME_GENRE_TOKENS):
        catalog_category = "CatalogCategory.ANIME"
    elif contains_any(genres_text, ANIMATION_GENRE_TOKENS):
        catalog_category = "CatalogCategory.ANIMATION"
    elif contains_any(genres_text, ASIAN_DRAMA_GENRE_TOKENS):
        catalog_category = "CatalogCategory.DRAMAS_ASIAN"
    elif contains_any(genres_text, DOCUMENTARY_GENRE_TOKENS):
        catalog_category = "CatalogCategory.DOCUMENTARIES"
    elif source_is_series:
        catalog_category = "CatalogCategory.TV_SERIES"
    else:
        catalog_category = "CatalogCategory.MOVIES"

    content_type = "ContentType.SERIES" if source_is_series else "ContentType.MOVIE"
    return content_type, catalog_category


def numeric_rating(value: Any) -> float:
    try:
        rating = float(value or 0)
    except (TypeError, ValueError):
        return 0.0
    return rating if rating == rating else 0.0


def numeric_year(value: Any) -> int:
    try:
        year = int(value or 0)
    except (TypeError, ValueError):
        return 0
    return year if year > 0 else 2024


def stable_popularity(row_id: Any) -> int:
    """Keep the previous deterministic popularity scale without depending on
    the output order of the newly classified catalog.
    """
    try:
        numeric_id = int(row_id)
    except (TypeError, ValueError):
        numeric_id = 0
    return 100 - ((max(numeric_id, 1) - 1) % 30)


def classify_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    classified: list[dict[str, Any]] = []
    for source_index, row in enumerate(rows):
        content_type, category = classify_row(row)
        classified.append(
            {
                "row": row,
                "content_type": content_type,
                "category": category,
                "source_index": source_index,
            }
        )

    # Stable, meaningful repository order:
    # 1) movies before episodic content;
    # 2) precise category;
    # 3) newest year;
    # 4) rating;
    # 5) title and database id as deterministic tie-breakers.
    classified.sort(
        key=lambda item: (
            0 if item["content_type"] == "ContentType.MOVIE" else 1,
            CATEGORY_ORDER[item["category"].removeprefix("CatalogCategory.")],
            -numeric_year(item["row"].get("year")),
            -numeric_rating(item["row"].get("rating")),
            normalize_text(item["row"].get("title")),
            int(item["row"].get("id") or 0),
        )
    )
    return classified


def read_rows() -> list[dict[str, Any]]:
    with sqlite3.connect(DB_PATH) as connection:
        connection.row_factory = sqlite3.Row
        rows = connection.execute("SELECT * FROM movies ORDER BY id ASC").fetchall()
    return [dict(row) for row in rows]


def validate(classified: list[dict[str, Any]], source_rows: list[dict[str, Any]]) -> None:
    if len(classified) != len(source_rows):
        raise AssertionError(
            f"catalog size mismatch: generated={len(classified)}, source={len(source_rows)}"
        )

    source_series_ids = {
        int(row["id"]) for row in source_rows if is_series_source(row.get("category"))
    }
    generated_series_ids = {
        int(item["row"]["id"])
        for item in classified
        if item["content_type"] == "ContentType.SERIES"
    }
    if source_series_ids != generated_series_ids:
        raise AssertionError(
            "series classification mismatch: "
            f"source_only={sorted(source_series_ids - generated_series_ids)}, "
            f"generated_only={sorted(generated_series_ids - source_series_ids)}"
        )

    for item in classified:
        row = item["row"]
        source_is_series = is_series_source(row.get("category"))
        generated_is_series = item["content_type"] == "ContentType.SERIES"
        if source_is_series != generated_is_series:
            raise AssertionError(f"wrong type for id={row['id']} title={row['title']}")

    ids = [int(item["row"]["id"]) for item in classified]
    if len(ids) != len(set(ids)):
        raise AssertionError("duplicate ids in generated catalog")

    categories = Counter(item["category"] for item in classified)
    types = Counter(item["content_type"] for item in classified)
    print(f"[CHECK] source rows: {len(source_rows)}")
    print(f"[CHECK] generated rows: {len(classified)}")
    print(f"[CHECK] types: {dict(types)}")
    print(f"[CHECK] categories: {dict(categories)}")
    print(f"[CHECK] series ids: {sorted(source_series_ids)}")


def stream_fields(row: dict[str, Any]) -> tuple[str, str]:
    playback_url = "null"
    quality = kotlin_str("1080p HD")
    streams = parse_collection(row.get("streams"))
    if streams:
        first = streams[0]
        if isinstance(first, dict):
            if first.get("url"):
                playback_url = kotlin_str(first["url"])
            if first.get("quality"):
                quality = kotlin_str(first["quality"])
        elif isinstance(first, str) and first.strip():
            playback_url = kotlin_str(first)
    return playback_url, quality


def render_entry(item: dict[str, Any]) -> str:
    row = item["row"]
    playback_url, quality = stream_fields(row)
    title_raw = row.get("title") or "Untitled"
    year = numeric_year(row.get("year"))
    rating = numeric_rating(row.get("rating")) or 7.5
    duration = int(row.get("duration_minutes") or 95)
    source_index = int(item["source_index"])
    original_title = row.get("original_title") or title_raw
    poster_url = row.get("poster_url")
    backdrop_url = row.get("backdrop_url") or poster_url
    return f"""        MediaContent(
            id = {kotlin_str(f"m_{row['id']}")},
            title = {kotlin_str(title_raw)},
            type = {item["content_type"]},
            year = {year},
            rating = {rating:.1f},
            genres = {kotlin_set(row.get("genres"))},
            country = {kotlin_str(row.get("country") or "США / Европа")},
            quality = {quality},
            durationMinutes = {duration},
            isNew = {str(source_index < 20).lower()},
            popularity = {stable_popularity(row.get("id"))},
            ageRating = 16,
            originalTitle = {kotlin_str(original_title)},
            director = {kotlin_str(row.get("director"))},
            cast = {kotlin_list(row.get("cast"))},
            synopsis = {kotlin_str(row.get("synopsis") or f"Увлекательная история: {title_raw}")},
            category = {item["category"]},
            playbackUrl = {playback_url},
            posterUrl = {kotlin_str(poster_url)},
            backdropUrl = {kotlin_str(backdrop_url)},
            licenseName = "Public Domain",
            licenseUrl = "https://creativecommons.org/publicdomain/mark/1.0/"
        )"""


def render_repository(classified: list[dict[str, Any]]) -> str:
    entries = ",\n".join(render_entry(item) for item in classified)
    return f"""package app.movia.android.data.catalog

import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.Person

interface CatalogRepository {{
    fun all(): List<MediaContent>
    fun search(query: String): List<MediaContent>
    fun searchPeople(query: String): List<Person>
    fun findByTitle(title: String): MediaContent?
    fun findById(id: String): MediaContent?
}}

object DemoCatalogRepository : CatalogRepository {{
    private val catalog: List<MediaContent> = listOf(
{entries}
    )

    override fun all(): List<MediaContent> = catalog

    override fun search(query: String): List<MediaContent> {{
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        return catalog.filter {{ item ->
            item.title.lowercase().contains(normalized) ||
                item.originalTitle?.lowercase()?.contains(normalized) == true ||
                item.genres.any {{ it.lowercase().contains(normalized) }} ||
                item.country.lowercase().contains(normalized) ||
                item.director?.lowercase()?.contains(normalized) == true ||
                item.synopsis?.lowercase()?.contains(normalized) == true ||
                item.licenseName?.lowercase()?.contains(normalized) == true ||
                item.category.label.lowercase().contains(normalized) ||
                (item.year > 0 && item.year.toString().contains(normalized))
        }}.sortedWith(
            compareByDescending<MediaContent> {{ it.popularity }}
                .thenByDescending {{ it.rating }}
                .thenBy {{ it.title.lowercase() }}
        )
    }}

    override fun searchPeople(query: String): List<Person> {{
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        return catalog.mapNotNull {{ it.director }}.distinct()
            .filter {{ it.lowercase().contains(normalized) }}
            .map {{ name ->
                Person(
                    name = name,
                    knownFor = catalog.filter {{ it.director == name }}.map {{ it.title }},
                )
            }}
            .sortedBy {{ it.name }}
    }}

    override fun findByTitle(title: String): MediaContent? = catalog.firstOrNull {{
        it.title.equals(title, ignoreCase = true)
    }}

    override fun findById(id: String): MediaContent? = catalog.firstOrNull {{ it.id == id }}
}}
"""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="validate classification and sorting without rewriting Kotlin",
    )
    args = parser.parse_args()

    source_rows = read_rows()
    classified = classify_rows(source_rows)
    validate(classified, source_rows)

    if not args.check:
        OUTPUT_PATH.write_text(render_repository(classified), encoding="utf-8")
        print(f"[OK] wrote {OUTPUT_PATH} with {len(classified)} titles")
    else:
        print("[OK] classification check passed; Kotlin file was not changed")


if __name__ == "__main__":
    main()
