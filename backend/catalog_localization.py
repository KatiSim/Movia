#!/usr/bin/env python3
"""Shared display-title and catalog-card visibility policy.

Canonical identity is deliberately not derived from any title in this module.
The title helpers only decide whether a canonical row is safe to show in a
Russian UI and which already-verified Russian value should be displayed.
"""
from __future__ import annotations

import json
import re
import unicodedata
from typing import Any, Iterable, Mapping, Optional


_CYRILLIC_RE = re.compile(r"[А-Яа-яЁё]")
_CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
_SPACE_RE = re.compile(r"\s+")


def clean_title(value: Any) -> str:
    """Normalize presentation whitespace without translating the value."""
    return _SPACE_RE.sub(
        " ", unicodedata.normalize("NFKC", str(value or "")).strip()
    )


def has_cyrillic(value: Any) -> bool:
    return bool(_CYRILLIC_RE.search(clean_title(value)))


def has_cjk(value: Any) -> bool:
    return bool(_CJK_RE.search(clean_title(value)))


def normalized_title_key(value: Any) -> str:
    """Return a comparison key; never use this key as canonical identity."""
    value = clean_title(value).casefold().replace("ё", "е")
    return re.sub(r"[^0-9a-zа-я]+", "", value)


def is_russian_display_title(value: Any, original_title: Any = None) -> bool:
    """Accept only an actual Cyrillic localized title.

    A Cyrillic character is required and CJK is rejected. Latin abbreviations
    inside an otherwise Russian title are allowed (for example, «Миссия: FIFA»)
    but a foreign/original value copied verbatim is not accepted.
    """
    title = clean_title(value)
    if not title or not has_cyrillic(title) or has_cjk(title):
        return False
    original = clean_title(original_title)
    if original and not has_cyrillic(original) and normalized_title_key(title) == normalized_title_key(original):
        return False
    return True


def parse_alternative_titles(value: Any) -> list[dict[str, str]]:
    """Parse only small title records, retaining no credentials or payloads."""
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except (TypeError, ValueError, json.JSONDecodeError):
            return []
    if isinstance(value, Mapping):
        value = value.get("titles", value.get("results", []))
    if not isinstance(value, Iterable) or isinstance(value, (bytes, str)):
        return []
    result: list[dict[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for item in value:
        if isinstance(item, str):
            title = clean_title(item)
            iso = ""
        elif isinstance(item, Mapping):
            title = clean_title(
                item.get("title") or item.get("name") or item.get("value")
            )
            iso = clean_title(
                item.get("iso_3166_1") or item.get("iso3166_1") or item.get("country")
            ).upper()
        else:
            continue
        if not title:
            continue
        key = (title.casefold(), iso)
        if key in seen:
            continue
        seen.add(key)
        result.append({"title": title, "iso_3166_1": iso})
        if len(result) >= 64:
            break
    return result


def russian_alternative_titles(value: Any) -> list[str]:
    result: list[str] = []
    for item in parse_alternative_titles(value):
        iso = item.get("iso_3166_1", "").upper()
        if iso not in {"RU", "RUS", "RU-RU", "RUSSIAN"}:
            continue
        title = item.get("title", "")
        if is_russian_display_title(title) and title not in result:
            result.append(title)
    return result


def choose_localized_ru_title(
    *,
    localized_ru_title: Any = None,
    title: Any = None,
    original_title: Any = None,
    alternative_titles: Any = None,
) -> Optional[str]:
    """Choose a display value in the specified trust order.

    ``title`` is considered only when it is already Cyrillic. No translation,
    transliteration, fuzzy matching, or first-result selection happens here.
    """
    candidates = [localized_ru_title, title]
    candidates.extend(russian_alternative_titles(alternative_titles))
    for candidate in candidates:
        value = clean_title(candidate)
        if is_russian_display_title(value, original_title):
            return value
    return None


def row_display_title(row: Mapping[str, Any]) -> Optional[str]:
    return choose_localized_ru_title(
        localized_ru_title=row.get("localized_ru_title"),
        title=row.get("title"),
        original_title=row.get("original_title"),
        alternative_titles=row.get("alternative_titles"),
    ) if row.get("localized_ru_title") else None


def is_user_visible_row(row: Mapping[str, Any]) -> bool:
    """Common feed gate for Home/catalog/search/similar/recommendations."""
    media_type = clean_title(row.get("media_type")).casefold()
    try:
        tmdb_id = int(row.get("tmdb_id") or 0)
    except (TypeError, ValueError, OverflowError):
        tmdb_id = 0
    poster = clean_title(row.get("poster_url"))
    display = clean_title(row.get("localized_ru_title"))
    return (
        tmdb_id > 0
        and media_type in {"movie", "tv"}
        and bool(poster)
        and is_russian_display_title(display, row.get("original_title"))
    )


def meta_localized_title(meta: Mapping[str, Any]) -> Optional[str]:
    return choose_localized_ru_title(
        localized_ru_title=meta.get("localized_ru_title")
        or meta.get("localizedRuTitle")
        or meta.get("ru_title")
        or meta.get("title_ru"),
        title=meta.get("title"),
        original_title=meta.get("original_title") or meta.get("originalTitle"),
        alternative_titles=meta.get("alternative_titles")
        or meta.get("alternativeTitles"),
    )
