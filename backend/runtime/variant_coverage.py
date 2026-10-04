#!/usr/bin/env python3
"""Variant coverage policy for Movia enrichment.

Coverage is a target, not permission to fabricate variants. Only concrete
provider-returned voices/qualities count. "Auto" and unknown placeholders are
selectors/defaults, not distinct media variants.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional

from stream_validation import sanitize_streams
from playback_resolver_shadow_audit import canonical_quality, normalize_voice

MIN_CONCRETE_VOICES = 3
MIN_CONCRETE_QUALITIES = 3

_UNKNOWN = {"", "auto", "не указано", "unknown", "default", "none", "null"}


def _norm(value: Any) -> str:
    return " ".join(str(value or "").strip().split())


def _concrete(value: Any) -> bool:
    return _norm(value).casefold() not in _UNKNOWN


@dataclass(frozen=True)
class VariantCoverage:
    streams: int
    voices: int
    qualities: int
    voice_quality_pairs: int
    exact_episode_streams: int
    complete: bool
    requires_episode_identity: bool = False


def variant_coverage(
    streams: Any,
    *,
    media_type: str = "movie",
    season: Optional[int] = None,
    episode: Optional[int] = None,
) -> VariantCoverage:
    clean = sanitize_streams(streams, require_source=True)
    kind = str(media_type or "movie").strip().casefold()
    is_series = kind in {"tv", "series", "serial", "tv_series", "limited_series"}

    if (season is None) != (episode is None):
        return VariantCoverage(len(clean), 0, 0, 0, 0, False, True)

    scoped = []
    exact_episode = 0
    for row in clean:
        row_season = row.get("season")
        row_episode = row.get("episode")
        if is_series:
            if season is None:
                if row_season not in (None, "", 0, "0") and row_episode not in (None, "", 0, "0"):
                    exact_episode += 1
                continue
            try:
                if int(row_season) != int(season) or int(row_episode) != int(episode):
                    continue
            except (TypeError, ValueError):
                continue
            exact_episode += 1
        scoped.append(row)

    if is_series and season is None:
        return VariantCoverage(len(clean), 0, 0, 0, exact_episode, False, True)

    voices = {
        normalize_voice(row.get("voice") or row.get("translation"))
        for row in scoped
        if _concrete(row.get("voice") or row.get("translation"))
        and normalize_voice(row.get("voice") or row.get("translation"))
    }
    qualities = {
        canonical_quality(row.get("quality") or row.get("resolution"))
        for row in scoped
        if canonical_quality(row.get("quality") or row.get("resolution"))
    }
    pairs = {
        (
            normalize_voice(row.get("voice") or row.get("translation")),
            canonical_quality(row.get("quality") or row.get("resolution")),
        )
        for row in scoped
        if _concrete(row.get("voice") or row.get("translation"))
        and normalize_voice(row.get("voice") or row.get("translation"))
        and canonical_quality(row.get("quality") or row.get("resolution"))
    }
    complete = (
        len(voices) >= MIN_CONCRETE_VOICES
        and len(qualities) >= MIN_CONCRETE_QUALITIES
    )
    return VariantCoverage(
        streams=len(scoped),
        voices=len(voices),
        qualities=len(qualities),
        voice_quality_pairs=len(pairs),
        exact_episode_streams=exact_episode,
        complete=complete,
        requires_episode_identity=False,
    )
