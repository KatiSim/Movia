#!/usr/bin/env python3
"""Classify metadata from real provider releases.

Stream availability is resolved by provider adapters and persisted only after
validation. This module intentionally contains no synthetic stream generator.
"""

from __future__ import annotations

import re
from typing import Any, Dict, List


VOICE_PATTERNS = [
    (re.compile(r"(дубляж|лицензи|dub\b|полное дублирование|профессиональн|дублированный)", re.I), "Дубляж"),
    (re.compile(r"(lostfilm|лостфильм)", re.I), "LostFilm"),
    (re.compile(r"(hdrezka|rezka|резка)", re.I), "HDRezka"),
    (re.compile(r"(red\s*head\s*sound|rhs\b|ред\s*хед)", re.I), "Red Head Sound"),
    (re.compile(r"(кубик\s*в\s*кубе|kubik\s*v\s*kube)", re.I), "Кубик в Кубе"),
    (re.compile(r"(кураж[- ]бамбей|kuraj[- ]bambey)", re.I), "Кураж-Бамбей"),
    (re.compile(r"(tvshows|твшоуз)", re.I), "TVShows"),
    (re.compile(r"(newstudio|ньюстудио)", re.I), "NewStudio"),
    (re.compile(r"(alexfilm|алексфильм)", re.I), "AlexFilm"),
    (re.compile(r"(пифагор|pifagor)", re.I), "Пифагор"),
    (re.compile(r"(original|english|английский|eng\b)", re.I), "Original"),
]

QUALITY_PATTERNS = [
    (re.compile(r"(2160p|4k\b|uhd\b)", re.I), "4K"),
    (re.compile(r"(1080p|fhd\b|full\s*hd)", re.I), "1080p"),
    (re.compile(r"(720p|hd\b)", re.I), "720p"),
    (re.compile(r"(480p|sd\b|dvd\b)", re.I), "480p"),
]


def classify_voice(text: str) -> str:
    """Return a normalized voice label inferred from a real release name."""
    for pattern, voice_name in VOICE_PATTERNS:
        if pattern.search(text):
            return voice_name
    return "Дубляж"


def classify_quality(text: str) -> str:
    """Return a normalized quality label inferred from a real release name."""
    for pattern, quality_name in QUALITY_PATTERNS:
        if pattern.search(text):
            return quality_name
    return "1080p"


def generate_default_streams_for_title(
    title: str,
    year: int,
    category: str,
    rating: float,
) -> List[Dict[str, Any]]:
    """Compatibility shim: synthetic availability is never generated."""
    del title, year, category, rating
    return []


def batch_populate_database() -> None:
    """Compatibility shim: catalog population belongs to real providers."""
    print("Synthetic stream population is disabled; no database changes were made.")


if __name__ == "__main__":
    batch_populate_database()
