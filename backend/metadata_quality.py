#!/usr/bin/env python3
"""Shared metadata quality rules for Movia.

The catalog stores one canonical entity per ``(media_type, tmdb_id)``.  All
metadata fields below come from one authoritative TMDb detail response so old
legacy values cannot survive field-by-field merges indefinitely.
"""
from __future__ import annotations

from typing import Any, Iterable

COUNTRY_NAMES = {
    "US": "США", "CA": "Канада", "GB": "Великобритания", "RU": "Россия", "SU": "СССР",
    "FR": "Франция", "DE": "Германия", "IT": "Италия", "ES": "Испания", "PT": "Португалия",
    "JP": "Япония", "KR": "Южная Корея", "CN": "Китай", "HK": "Гонконг", "TW": "Тайвань",
    "TR": "Турция", "IN": "Индия", "TH": "Таиланд", "ID": "Индонезия", "VN": "Вьетнам",
    "AU": "Австралия", "NZ": "Новая Зеландия", "BR": "Бразилия", "MX": "Мексика",
    "AR": "Аргентина", "CL": "Чили", "CO": "Колумбия", "ZA": "ЮАР", "DK": "Дания",
    "SE": "Швеция", "NO": "Норвегия", "FI": "Финляндия", "NL": "Нидерланды",
    "BE": "Бельгия", "PL": "Польша", "IE": "Ирландия", "AT": "Австрия",
    "CH": "Швейцария", "CZ": "Чехия", "HU": "Венгрия", "RO": "Румыния",
    "UA": "Украина", "BY": "Беларусь", "KZ": "Казахстан", "IL": "Израиль",
    "GR": "Греция", "IS": "Исландия",
}

EAST_ASIA_COUNTRIES = frozenset({
    "Южная Корея", "Япония", "Китай", "Гонконг", "Тайвань", "Таиланд",
})


def bayesian_rating(vote_average: Any, vote_count: Any, *, prior_mean: float = 6.5, prior_count: int = 100) -> float:
    """Return a confidence-adjusted 0..10 rating.

    A raw 10.0 based on a handful of votes should never outrank an established
    title with thousands of votes.  ``vote_average`` remains stored separately
    for audit; this function produces the user-facing/ranking score.
    """
    try:
        r = float(vote_average or 0.0)
        v = max(0, int(vote_count or 0))
    except (TypeError, ValueError, OverflowError):
        return 0.0
    if v <= 0 or r <= 0:
        return 0.0
    r = min(10.0, max(0.0, r))
    score = (v * r + prior_count * prior_mean) / (v + prior_count)
    return round(score, 1)


def country_from_tmdb(data: dict[str, Any], media_type: str) -> str:
    codes: list[str] = []
    if str(media_type).lower() == "tv":
        codes.extend(str(x).upper() for x in (data.get("origin_country") or []) if x)
    for row in data.get("production_countries") or []:
        if isinstance(row, dict) and row.get("iso_3166_1"):
            codes.append(str(row["iso_3166_1"]).upper())
    for code in codes:
        if code in COUNTRY_NAMES:
            return COUNTRY_NAMES[code]
    return "Зарубежный"


def country_codes_from_tmdb(data: dict[str, Any], media_type: str) -> list[str]:
    result: list[str] = []
    if str(media_type).lower() == "tv":
        result.extend(str(x).upper() for x in (data.get("origin_country") or []) if x)
    for row in data.get("production_countries") or []:
        if isinstance(row, dict) and row.get("iso_3166_1"):
            result.append(str(row["iso_3166_1"]).upper())
    return list(dict.fromkeys(result))


def category_for(media_type: str, genres: Iterable[str], country_codes: Iterable[str]) -> str:
    low = {str(g).strip().casefold() for g in genres if g}
    codes = {str(c).upper() for c in country_codes if c}
    animation = any("мульт" in g or "animation" in g for g in low)
    documentary = any("документ" in g or "documentary" in g for g in low)
    if animation and "JP" in codes:
        return "anime"
    if animation:
        return "animation"
    if documentary:
        return "documentaries"
    return "tv_series" if str(media_type).lower() == "tv" else "movies"


def creators_from_tmdb(data: dict[str, Any], media_type: str) -> list[str]:
    if str(media_type).lower() == "tv":
        names = [str(x.get("name") or "").strip() for x in (data.get("created_by") or []) if isinstance(x, dict)]
        return [x for x in dict.fromkeys(names) if x]
    return []


def directors_from_credits(credits: dict[str, Any]) -> list[str]:
    names = [
        str(x.get("name") or "").strip()
        for x in (credits or {}).get("crew", [])
        if isinstance(x, dict) and x.get("job") == "Director"
    ]
    return [x for x in dict.fromkeys(names) if x]
