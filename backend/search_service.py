#!/usr/bin/env python3
"""Indexed Russian catalog search with stable ranking and pagination."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable, Optional

from catalog_schema_v2 import normalize_ru_text, prefix_successor


@dataclass(frozen=True)
class SearchPage:
    rows: list[Any]
    total: int
    prefix_count: int
    exact_count: int
    top_score: int


def category_condition(category: Optional[str]) -> Optional[str]:
    if not category or category.upper() in {"ALL", "ВСЕ"}:
        return None
    value = category.upper()
    if value in {"TV_SERIES", "СЕРИАЛЫ"}:
        return "(category IN ('series','tv_series','limited_series') OR media_type='tv')"
    if value == "LIMITED_SERIES":
        return "category='limited_series'"
    if value in {"ANIMATION", "АНИМАЦИЯ"}:
        return "category='animation'"
    if value in {"ANIME", "АНИМЕ"}:
        return "category='anime'"
    if value in {"DRAMAS_ASIAN", "ДОРАМЫ"}:
        return "(category='dramas_asian' OR country='Южная Корея')"
    if value in {"DOCUMENTARIES", "ДОКУМЕНТАЛЬНЫЕ"}:
        return "category='documentaries'"
    if value in {"THEATER_MUSICALS", "STANDUP", "INTERACTIVE"}:
        return "category=?"
    return "category='movies'"


def _base_conditions(
    category: Optional[str],
    genre: Optional[str],
    year_from: Optional[int],
    year_to: Optional[int],
    min_rating: Optional[float],
    country: Optional[str],
    media_type: Optional[str] = None,
) -> tuple[list[str], list[Any]]:
    conditions = ["poster_url IS NOT NULL"]
    params: list[Any] = []
    category_sql = category_condition(category)
    if category_sql:
        conditions.append(category_sql)
        if category_sql == "category=?":
            params.append(str(category).lower())
    if media_type and str(media_type).lower() in {"movie", "tv"}:
        conditions.append("media_type=?")
        params.append(str(media_type).lower())
    if genre:
        conditions.append("genres LIKE ?")
        params.append(f"%{genre}%")
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


def _prefix_sql(field: str) -> str:
    return f"({field} >= ? AND {field} < ?)"


def _fts_query(q: str) -> str:
    # The trigram tokenizer provides indexed substring fallback for queries
    # of at least three characters. Quotes keep spaces a phrase separator.
    return '"' + q.replace('"', " ") + '"'


def _score_sql() -> str:
    return """
    CASE
      WHEN normalized_ru_title = ? THEN 1000
      WHEN normalized_ru_title >= ? AND normalized_ru_title < ? THEN 800
      WHEN normalized_original_title = ? THEN 700
      WHEN instr(' ' || normalized_ru_title, ' ' || ?) > 0 THEN 500
      WHEN instr(normalized_ru_title, ?) > 0
        OR instr(normalized_original_title, ?) > 0 THEN 250
      ELSE 0
    END
    """


def _score_params(q: str, upper: str) -> list[str]:
    return [q, q, upper, q, q, q, q]


def _search_predicate(q: str, upper: str) -> tuple[str, list[str]]:
    fields = ("normalized_ru_title", "normalized_original_title")
    parts = [_prefix_sql(field) for field in fields]
    params: list[str] = []
    for _ in fields:
        params.extend([q, upper])
    if len(q) >= 3:
        parts.append(
            "id IN (SELECT rowid FROM movies_search_trigram "
            "WHERE movies_search_trigram MATCH ?)"
        )
        params.append(_fts_query(q))
    return "(" + " OR ".join(parts) + ")", params


def _filtered(base: Iterable[str], extra: str) -> str:
    return " AND ".join([*base, extra])


def search_page(
    conn: Any,
    query: str,
    *,
    limit: int = 20,
    offset: int = 0,
    category: Optional[str] = None,
    genre: Optional[str] = None,
    year_from: Optional[int] = None,
    year_to: Optional[int] = None,
    min_rating: Optional[float] = None,
    country: Optional[str] = None,
    media_type: Optional[str] = None,
) -> SearchPage:
    q = normalize_ru_text(query)
    if not q:
        return SearchPage([], 0, 0, 0, 0)
    upper = prefix_successor(q)
    base, base_params = _base_conditions(
        category, genre, year_from, year_to, min_rating, country, media_type
    )
    predicate, predicate_params = _search_predicate(q, upper)
    prefix_predicate = "(" + " OR ".join(
        _prefix_sql(field) for field in
        ("normalized_ru_title", "normalized_original_title")
    ) + ")"
    prefix_params = [q, upper, q, upper]

    prefix_count = int(conn.execute(
        "SELECT COUNT(*) FROM movies WHERE " + _filtered(base, prefix_predicate),
        [*base_params, *prefix_params],
    ).fetchone()[0] or 0)
    exact_count = int(conn.execute(
        "SELECT COUNT(*) FROM movies WHERE " + _filtered(
            base, "(normalized_ru_title=? OR normalized_original_title=?)"
        ),
        [*base_params, q, q],
    ).fetchone()[0] or 0)

    # Prefix refinement is monotone: once a prefix exists, a longer query
    # cannot broaden the result set with unrelated contains hits. Contains is
    # an indexed fallback only for a query with no prefix candidates.
    if prefix_count > 0:
        predicate = prefix_predicate
        predicate_params = prefix_params
    else:
        predicate, predicate_params = _search_predicate(q, upper)

    total = int(conn.execute(
        "SELECT COUNT(*) FROM movies WHERE " + _filtered(base, predicate),
        [*base_params, *predicate_params],
    ).fetchone()[0] or 0)

    score = _score_sql()
    score_params = _score_params(q, upper)
    rows = conn.execute(
        "SELECT movies.*, (" + score + ") AS search_score FROM movies WHERE "
        + _filtered(base, predicate)
        + " ORDER BY search_score DESC, seeders DESC, rating DESC, "
          "vote_count DESC, id ASC LIMIT ? OFFSET ?",
        [*score_params, *base_params, *predicate_params,
         max(1, min(int(limit), 100)), max(0, int(offset))],
    ).fetchall()
    top_score = int(rows[0]["search_score"]) if rows else 0
    return SearchPage(rows, total, prefix_count, exact_count, top_score)
