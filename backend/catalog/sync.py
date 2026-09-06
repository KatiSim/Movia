from __future__ import annotations

import json
import re
import sqlite3
from dataclasses import dataclass
from datetime import date, datetime, timezone
from typing import Any, Iterable, Mapping, Optional

from .schema import ensure_catalog_intelligence_schema, utc_now


_SOURCE_RANK = {
    "": 0,
    "CAMRip": 10,
    "TS": 20,
    "TC": 25,
    "DVDScr": 30,
    "HDTV": 40,
    "WEBRip": 50,
    "WEB-DL": 60,
    "HDRip": 65,
    "BDRip": 70,
    "BluRay": 75,
}


def _clean(value: Any) -> str:
    return " ".join(str(value or "").strip().split())


def normalize_voice(value: Any) -> str:
    if isinstance(value, (list, tuple, set)):
        parts = [_clean(item) for item in value]
        return ", ".join(dict.fromkeys(item for item in parts if item))
    return _clean(value)


def normalize_quality(value: Any) -> str:
    text = _clean(value)
    if not text:
        return ""
    folded = text.casefold().replace("_", "-")
    source = ""
    patterns = (
        (r"\b(?:cam\s*rip|camrip|cam-rip|cam)\b", "CAMRip"),
        (r"\b(?:tele\s*sync|telesync|ts)\b", "TS"),
        (r"\b(?:tele\s*cine|telecine|tc)\b", "TC"),
        (r"\b(?:dvd\s*scr|dvdscr|scr)\b", "DVDScr"),
        (r"\b(?:hdtv)\b", "HDTV"),
        (r"\b(?:web\s*[-.]?\s*dl|webdl)\b", "WEB-DL"),
        (r"\b(?:web\s*[-.]?\s*rip|webrip)\b", "WEBRip"),
        (r"\b(?:hd\s*[-.]?\s*rip|hdrip)\b", "HDRip"),
        (r"\b(?:bd\s*[-.]?\s*rip|bdrip)\b", "BDRip"),
        (r"\b(?:blu\s*[-.]?\s*ray|bluray)\b", "BluRay"),
    )
    for pattern, canonical in patterns:
        if re.search(pattern, folded, flags=re.IGNORECASE):
            source = canonical
            break
    resolution = ""
    resolution_match = re.search(r"\b(4320|2160|1440|1080|720|576|480|360)p\b", folded)
    if resolution_match:
        resolution = f"{resolution_match.group(1)}p"
    elif re.search(r"\b(?:4k|uhd)\b", folded):
        resolution = "2160p"
    if source and resolution:
        return f"{source} {resolution}"
    if source:
        return source
    if resolution:
        return resolution
    return text


def quality_sort_key(value: str) -> tuple[int, int, str]:
    value = normalize_quality(value)
    source = next((name for name in _SOURCE_RANK if name and value.startswith(name)), "")
    match = re.search(r"\b(4320|2160|1440|1080|720|576|480|360)p\b", value)
    resolution = int(match.group(1)) if match else 0
    return (_SOURCE_RANK.get(source, 0), resolution, value.casefold())


@dataclass(frozen=True)
class PlayableSourceObservation:
    media_id: int
    source_key: str
    source_name: str = ""
    url: str = ""
    source_kind: str = ""
    quality: str = ""
    voice: str = ""
    playable: bool = True
    observed_at: Optional[str] = None
    metadata: Optional[Mapping[str, Any]] = None
    title: str = ""
    media_type: str = "movie"
    release_date: str = ""

    def __post_init__(self) -> None:
        if not str(self.source_key).strip():
            raise ValueError("source_key must not be empty")
        if int(self.media_id) <= 0:
            raise ValueError("media_id must be positive")


class ReleaseTracker:
    """Daily release promotion and normalized playable availability tracker.

    A release is visible from this tracker only after the first upstream source
    is explicitly reported as playable. CAMRip is a valid first source; quality
    affects ranking, never eligibility.
    """

    def __init__(self, conn: sqlite3.Connection):
        self.conn = conn
        self.conn.row_factory = sqlite3.Row
        ensure_catalog_intelligence_schema(conn)
        self._movie_columns = {
            str(row[1]) for row in self.conn.execute("PRAGMA table_info(movies)").fetchall()
        }

    def announce(
        self,
        *,
        media_id: int,
        title: str,
        media_type: str = "movie",
        release_date: str = "",
        discovery_source: str = "",
        discovered_at: Optional[str] = None,
    ) -> dict[str, Any]:
        timestamp = discovered_at or utc_now()
        self.conn.execute(
            "INSERT INTO release_candidates"
            "(media_id,title,media_type,release_date,discovery_source,discovered_at,state) "
            "VALUES(?,?,?,?,?,?, 'waiting') ON CONFLICT(media_id) DO UPDATE SET "
            "title=CASE WHEN excluded.title!='' THEN excluded.title ELSE release_candidates.title END,"
            "media_type=CASE WHEN excluded.media_type!='' THEN excluded.media_type ELSE release_candidates.media_type END,"
            "release_date=CASE WHEN excluded.release_date!='' THEN excluded.release_date ELSE release_candidates.release_date END,"
            "discovery_source=CASE WHEN excluded.discovery_source!='' THEN excluded.discovery_source ELSE release_candidates.discovery_source END",
            (
                int(media_id),
                _clean(title),
                _clean(media_type) or "movie",
                _clean(release_date),
                _clean(discovery_source),
                timestamp,
            ),
        )
        self.conn.commit()
        return self.release_status(media_id)

    def _movie_identity(self, media_id: int) -> tuple[str, str]:
        if not self._movie_columns:
            return ("", "movie")
        row = self.conn.execute("SELECT title,media_type FROM movies WHERE id=?", (int(media_id),)).fetchone()
        if row is None:
            return ("", "movie")
        return (_clean(row["title"]), _clean(row["media_type"]) or "movie")

    def _ensure_candidate_from_observation(self, observation: PlayableSourceObservation) -> None:
        row = self.conn.execute(
            "SELECT 1 FROM release_candidates WHERE media_id=?", (int(observation.media_id),)
        ).fetchone()
        if row is not None:
            return
        movie_title, movie_type = self._movie_identity(observation.media_id)
        title = _clean(observation.title) or movie_title or f"media:{observation.media_id}"
        self.announce(
            media_id=observation.media_id,
            title=title,
            media_type=_clean(observation.media_type) or movie_type,
            release_date=observation.release_date,
            discovery_source=observation.source_name or observation.source_kind,
            discovered_at=observation.observed_at,
        )

    def observe_source(self, observation: PlayableSourceObservation) -> dict[str, Any]:
        self._ensure_candidate_from_observation(observation)
        timestamp = observation.observed_at or utc_now()
        quality = normalize_quality(observation.quality)
        voice = normalize_voice(observation.voice)
        existing = self.conn.execute(
            "SELECT first_seen_at FROM playable_sources WHERE media_id=? AND source_key=?",
            (int(observation.media_id), observation.source_key),
        ).fetchone()
        first_seen = str(existing["first_seen_at"]) if existing is not None else timestamp
        before = self.conn.execute(
            "SELECT state,first_playable_at FROM release_candidates WHERE media_id=?",
            (int(observation.media_id),),
        ).fetchone()
        was_playable = bool(before and before["state"] == "playable")
        had_ever_playable = bool(before and str(before["first_playable_at"] or "").strip())
        with self.conn:
            self.conn.execute(
                "INSERT INTO playable_sources"
                "(media_id,source_key,source_name,url,source_kind,quality,voice,playable,first_seen_at,last_seen_at,metadata_json) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(media_id,source_key) DO UPDATE SET "
                "source_name=excluded.source_name,url=excluded.url,source_kind=excluded.source_kind,"
                "quality=excluded.quality,voice=excluded.voice,playable=excluded.playable,"
                "last_seen_at=excluded.last_seen_at,metadata_json=excluded.metadata_json",
                (
                    int(observation.media_id),
                    _clean(observation.source_key),
                    _clean(observation.source_name),
                    _clean(observation.url),
                    _clean(observation.source_kind),
                    quality,
                    voice,
                    1 if observation.playable else 0,
                    first_seen,
                    timestamp,
                    json.dumps(dict(observation.metadata or {}), ensure_ascii=False, sort_keys=True),
                ),
            )
            playable_count = int(
                self.conn.execute(
                    "SELECT COUNT(*) FROM playable_sources WHERE media_id=? AND playable=1",
                    (int(observation.media_id),),
                ).fetchone()[0]
                or 0
            )
            if playable_count > 0:
                self.conn.execute(
                    "UPDATE release_candidates SET state='playable',"
                    "first_playable_at=CASE WHEN first_playable_at='' THEN ? ELSE first_playable_at END,"
                    "last_playable_at=? WHERE media_id=?",
                    (timestamp, timestamp, int(observation.media_id)),
                )
            elif was_playable:
                self.conn.execute(
                    "UPDATE release_candidates SET state='waiting' WHERE media_id=?",
                    (int(observation.media_id),),
                )
        availability = self.availability(observation.media_id)
        self._sync_legacy_movie_summary(observation.media_id, availability)
        status = self.release_status(observation.media_id)
        status["promoted"] = bool(status.get("state") == "playable" and not had_ever_playable)
        status["availability"] = availability
        return status

    def _sync_legacy_movie_summary(self, media_id: int, availability: Mapping[str, Any]) -> None:
        assignments = []
        values: list[Any] = []
        playable = bool(availability.get("playable"))
        if "quality" in self._movie_columns:
            assignments.append("quality=?")
            values.append(availability.get("best_quality", "") if playable else "")
        if "voice" in self._movie_columns:
            assignments.append("voice=?")
            values.append(", ".join(availability.get("voices", [])) if playable else "")
        if "link_verified" in self._movie_columns:
            assignments.append("link_verified=?")
            values.append(1 if playable else 0)
        if "link_updated_at" in self._movie_columns:
            assignments.append("link_updated_at=?")
            values.append(utc_now())
        if not assignments:
            return
        values.append(int(media_id))
        with self.conn:
            self.conn.execute(
                f"UPDATE movies SET {','.join(assignments)} WHERE id=?",
                values,
            )

    def availability(self, media_id: int) -> dict[str, Any]:
        rows = self.conn.execute(
            "SELECT source_key,source_name,url,source_kind,quality,voice,first_seen_at,last_seen_at "
            "FROM playable_sources WHERE media_id=? AND playable=1",
            (int(media_id),),
        ).fetchall()
        qualities = sorted(
            {normalize_quality(row["quality"]) for row in rows if normalize_quality(row["quality"])},
            key=quality_sort_key,
            reverse=True,
        )
        voices = sorted(
            {normalize_voice(row["voice"]) for row in rows if normalize_voice(row["voice"])},
            key=str.casefold,
        )
        sources = [dict(row) for row in rows]
        sources.sort(key=lambda row: (quality_sort_key(row.get("quality", "")), row.get("last_seen_at", "")), reverse=True)
        return {
            "playable": bool(rows),
            "best_quality": qualities[0] if qualities else "",
            "qualities": qualities,
            "voices": voices,
            "source_count": len(rows),
            "sources": sources,
        }

    def release_status(self, media_id: int) -> dict[str, Any]:
        row = self.conn.execute(
            "SELECT * FROM release_candidates WHERE media_id=?", (int(media_id),)
        ).fetchone()
        if row is None:
            return {"media_id": int(media_id), "state": "unknown"}
        return dict(row)

    def new_releases(self, limit: int = 100, *, playable_only: bool = True) -> list[dict[str, Any]]:
        sql = "SELECT * FROM release_candidates"
        params: list[Any] = []
        if playable_only:
            sql += " WHERE state='playable'"
        sql += " ORDER BY COALESCE(NULLIF(first_playable_at,''),discovered_at) DESC,media_id DESC LIMIT ?"
        params.append(max(1, min(int(limit), 1000)))
        rows = self.conn.execute(sql, params).fetchall()
        result = []
        for row in rows:
            item = dict(row)
            item["availability"] = self.availability(int(row["media_id"]))
            result.append(item)
        return result

    def _last_daily_run(self) -> str:
        row = self.conn.execute(
            "SELECT value FROM catalog_intelligence_meta WHERE key='last_release_tracker_day'"
        ).fetchone()
        return str(row[0]) if row else ""

    def run_daily(
        self,
        *,
        candidates: Iterable[Mapping[str, Any]] = (),
        sources: Iterable[PlayableSourceObservation | Mapping[str, Any]] = (),
        day: Optional[date] = None,
        force: bool = False,
    ) -> dict[str, Any]:
        target_day = (day or datetime.now(timezone.utc).date()).isoformat()
        if not force and self._last_daily_run() == target_day:
            return {"ok": True, "day": target_day, "skipped": True, "promoted_media_ids": []}
        announced = 0
        for candidate in candidates:
            self.announce(
                media_id=int(candidate["media_id"]),
                title=str(candidate.get("title") or ""),
                media_type=str(candidate.get("media_type") or "movie"),
                release_date=str(candidate.get("release_date") or ""),
                discovery_source=str(candidate.get("discovery_source") or ""),
                discovered_at=candidate.get("discovered_at"),
            )
            announced += 1
        promoted: list[int] = []
        observed = 0
        for raw in sources:
            observation = raw if isinstance(raw, PlayableSourceObservation) else PlayableSourceObservation(**dict(raw))
            status = self.observe_source(observation)
            observed += 1
            if status.get("promoted"):
                promoted.append(int(observation.media_id))
        now = utc_now()
        with self.conn:
            self.conn.execute(
                "INSERT INTO catalog_intelligence_meta(key,value,updated_at) VALUES('last_release_tracker_day',?,?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at",
                (target_day, now),
            )
        return {
            "ok": True,
            "day": target_day,
            "skipped": False,
            "announced": announced,
            "sources_observed": observed,
            "promoted_media_ids": sorted(set(promoted)),
            "playable_release_count": len(self.new_releases(limit=1000, playable_only=True)),
        }
