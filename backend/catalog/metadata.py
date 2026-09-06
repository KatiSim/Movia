from __future__ import annotations

import json
import math
import re
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Iterable, Mapping, Optional

from .schema import ensure_catalog_intelligence_schema, utc_now
from .sync import normalize_quality, normalize_voice, quality_sort_key


DEFAULT_SOURCE_POLICIES = {
    "movia_editorial": ("Movia editorial", 1.00),
    "kinopoisk": ("Кинопоиск", 0.97),
    "imdb": ("IMDb", 0.95),
    "tmdb": ("TMDb", 0.92),
    "provider": ("Playback provider", 0.68),
    "discovery": ("Discovery feed", 0.55),
}

FULL_CARD_FIELDS = (
    "title",
    "localized_ru_title",
    "original_title",
    "year",
    "synopsis",
    "poster_url",
    "backdrop_url",
    "genres",
    "rating",
    "vote_average",
    "vote_count",
    "duration_minutes",
    "country",
    "director",
    "creators",
    "media_type",
    "seasons_count",
    "episodes_count",
)

_LIST_FIELDS = {"genres", "creators", "alternative_titles"}
_WEIGHTED_AVERAGE_FIELDS = {"rating", "vote_average"}
_WEIGHTED_MEDIAN_FIELDS = {"year", "duration_minutes", "seasons_count", "episodes_count"}


@dataclass(frozen=True)
class SourcePolicy:
    source_id: str
    display_name: str
    trust_weight: float
    enabled: bool = True

    def __post_init__(self) -> None:
        if not self.source_id.strip():
            raise ValueError("source_id must not be empty")
        if not 0.0 <= float(self.trust_weight) <= 1.0:
            raise ValueError("trust_weight must be between 0 and 1")


def _json_load(value: Any, default: Any = None) -> Any:
    if value is None:
        return default
    if isinstance(value, (dict, list, int, float, bool)):
        return value
    text = str(value).strip()
    if not text:
        return default
    try:
        return json.loads(text)
    except (TypeError, ValueError, json.JSONDecodeError):
        return default


def _json_dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _clean_text(value: Any) -> str:
    return " ".join(str(value or "").strip().split())


def _normalise_scalar(value: Any) -> Any:
    if isinstance(value, str):
        return _clean_text(value).casefold()
    if isinstance(value, float):
        return round(value, 4)
    return value


def _normalise_value(field: str, value: Any) -> Any:
    if field in _LIST_FIELDS:
        if not isinstance(value, list):
            parsed = _json_load(value)
            if isinstance(parsed, list):
                value = parsed
            elif isinstance(value, str):
                value = [part.strip() for part in value.split(",") if part.strip()]
            else:
                value = []
        cleaned = [_clean_text(item) for item in value if _clean_text(item)]
        return sorted(dict.fromkeys(cleaned), key=str.casefold)
    if isinstance(value, dict):
        return {str(k): _normalise_scalar(v) for k, v in sorted(value.items())}
    return _normalise_scalar(value)


def _is_meaningful(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict, tuple, set)):
        return bool(value)
    if isinstance(value, float) and math.isnan(value):
        return False
    return True


def _weighted_median(pairs: list[tuple[float, float]]) -> float:
    ordered = sorted(pairs, key=lambda item: item[0])
    total = sum(weight for _, weight in ordered)
    threshold = total / 2.0
    running = 0.0
    for value, weight in ordered:
        running += weight
        if running >= threshold:
            return value
    return ordered[-1][0]


class MetadataEngine:
    """Metadata provenance, consensus, full-card and people-projects engine.

    It intentionally does not resolve playback. The only playback information
    read here is the normalized availability snapshot written by ``catalog.sync``.
    """

    def __init__(self, conn: sqlite3.Connection):
        self.conn = conn
        self.conn.row_factory = sqlite3.Row
        ensure_catalog_intelligence_schema(conn)
        self._bootstrap_default_sources()

    def _bootstrap_default_sources(self) -> None:
        now = utc_now()
        self.conn.executemany(
            "INSERT OR IGNORE INTO catalog_sources"
            "(source_id,display_name,trust_weight,enabled,updated_at) VALUES(?,?,?,?,?)",
            [
                (source_id, display_name, trust, 1, now)
                for source_id, (display_name, trust) in DEFAULT_SOURCE_POLICIES.items()
            ],
        )
        self.conn.commit()

    def register_source(self, policy: SourcePolicy) -> None:
        self.conn.execute(
            "INSERT INTO catalog_sources"
            "(source_id,display_name,trust_weight,enabled,updated_at) VALUES(?,?,?,?,?) "
            "ON CONFLICT(source_id) DO UPDATE SET "
            "display_name=excluded.display_name, trust_weight=excluded.trust_weight, "
            "enabled=excluded.enabled, updated_at=excluded.updated_at",
            (
                policy.source_id,
                policy.display_name,
                float(policy.trust_weight),
                1 if policy.enabled else 0,
                utc_now(),
            ),
        )
        self.conn.commit()

    def source_policies(self) -> list[SourcePolicy]:
        rows = self.conn.execute(
            "SELECT source_id,display_name,trust_weight,enabled FROM catalog_sources "
            "ORDER BY trust_weight DESC, source_id"
        ).fetchall()
        return [
            SourcePolicy(
                source_id=str(row["source_id"]),
                display_name=str(row["display_name"]),
                trust_weight=float(row["trust_weight"]),
                enabled=bool(row["enabled"]),
            )
            for row in rows
        ]

    def observe(
        self,
        media_id: int,
        source_id: str,
        fields: Mapping[str, Any],
        *,
        confidence: float = 1.0,
        observed_at: Optional[str] = None,
    ) -> int:
        if not 0.0 <= float(confidence) <= 1.0:
            raise ValueError("confidence must be between 0 and 1")
        source = self.conn.execute(
            "SELECT 1 FROM catalog_sources WHERE source_id=?", (source_id,)
        ).fetchone()
        if source is None:
            raise KeyError(f"unknown metadata source: {source_id}")
        timestamp = observed_at or utc_now()
        rows = []
        for field, value in fields.items():
            field_name = str(field).strip()
            if not field_name or not _is_meaningful(value):
                continue
            rows.append(
                (
                    int(media_id),
                    source_id,
                    field_name,
                    _json_dump(value),
                    float(confidence),
                    timestamp,
                )
            )
        if not rows:
            return 0
        self.conn.executemany(
            "INSERT INTO metadata_observations"
            "(media_id,source_id,field_name,value_json,confidence,observed_at) "
            "VALUES(?,?,?,?,?,?) "
            "ON CONFLICT(media_id,source_id,field_name) DO UPDATE SET "
            "value_json=excluded.value_json, confidence=excluded.confidence, "
            "observed_at=excluded.observed_at",
            rows,
        )
        self.conn.commit()
        return len(rows)

    def _evidence(self, media_id: int, field: Optional[str] = None) -> list[dict[str, Any]]:
        sql = (
            "SELECT o.field_name,o.value_json,o.confidence,o.observed_at,"
            "s.source_id,s.display_name,s.trust_weight "
            "FROM metadata_observations o JOIN catalog_sources s ON s.source_id=o.source_id "
            "WHERE o.media_id=? AND s.enabled=1"
        )
        params: list[Any] = [int(media_id)]
        if field is not None:
            sql += " AND o.field_name=?"
            params.append(field)
        sql += " ORDER BY o.field_name,s.trust_weight DESC,o.observed_at DESC"
        rows = self.conn.execute(sql, params).fetchall()
        return [
            {
                "field": str(row["field_name"]),
                "value": _json_load(row["value_json"]),
                "source_id": str(row["source_id"]),
                "source_name": str(row["display_name"]),
                "trust_weight": float(row["trust_weight"]),
                "confidence": float(row["confidence"]),
                "observed_at": str(row["observed_at"]),
            }
            for row in rows
        ]

    def consensus_field(self, media_id: int, field: str) -> Optional[dict[str, Any]]:
        evidence = [item for item in self._evidence(media_id, field) if _is_meaningful(item["value"])]
        if not evidence:
            return None
        for item in evidence:
            item["effective_weight"] = item["trust_weight"] * item["confidence"]
        total_weight = sum(item["effective_weight"] for item in evidence) or 1.0

        if field in _WEIGHTED_AVERAGE_FIELDS:
            numeric = []
            for item in evidence:
                try:
                    numeric.append((float(item["value"]), item["effective_weight"], item))
                except (TypeError, ValueError):
                    pass
            if numeric:
                value = sum(v * w for v, w, _ in numeric) / sum(w for _, w, _ in numeric)
                support = sum(w for _, w, _ in numeric) / total_weight
                return {
                    "value": round(value, 2),
                    "confidence": round(min(1.0, support), 4),
                    "method": "trusted_weighted_average",
                    "sources": [item["source_id"] for _, _, item in numeric],
                    "evidence": evidence,
                }

        if field in _WEIGHTED_MEDIAN_FIELDS:
            numeric_pairs = []
            numeric_evidence = []
            for item in evidence:
                try:
                    numeric_pairs.append((float(item["value"]), item["effective_weight"]))
                    numeric_evidence.append(item)
                except (TypeError, ValueError):
                    pass
            if numeric_pairs:
                value = _weighted_median(numeric_pairs)
                return {
                    "value": int(round(value)),
                    "confidence": round(sum(w for _, w in numeric_pairs) / total_weight, 4),
                    "method": "trusted_weighted_median",
                    "sources": [item["source_id"] for item in numeric_evidence],
                    "evidence": evidence,
                }

        buckets: dict[str, dict[str, Any]] = {}
        for item in evidence:
            normalized = _normalise_value(field, item["value"])
            key = _json_dump(normalized)
            bucket = buckets.setdefault(
                key,
                {"score": 0.0, "representative": item, "sources": []},
            )
            bucket["score"] += item["effective_weight"]
            bucket["sources"].append(item["source_id"])
            current = bucket["representative"]
            if (item["effective_weight"], item["observed_at"]) > (
                current["effective_weight"],
                current["observed_at"],
            ):
                bucket["representative"] = item
        winner = max(
            buckets.values(),
            key=lambda bucket: (
                bucket["score"],
                bucket["representative"]["effective_weight"],
                bucket["representative"]["observed_at"],
            ),
        )
        return {
            "value": winner["representative"]["value"],
            "confidence": round(winner["score"] / total_weight, 4),
            "method": "trusted_weighted_consensus",
            "sources": winner["sources"],
            "evidence": evidence,
        }

    def consensus(self, media_id: int, fields: Iterable[str] = FULL_CARD_FIELDS) -> dict[str, Any]:
        values: dict[str, Any] = {}
        provenance: dict[str, Any] = {}
        for field in fields:
            resolved = self.consensus_field(media_id, field)
            if resolved is None:
                continue
            values[field] = resolved["value"]
            provenance[field] = {
                "confidence": resolved["confidence"],
                "method": resolved["method"],
                "sources": resolved["sources"],
            }
        return {"values": values, "provenance": provenance}

    def record_credit(
        self,
        media_id: int,
        *,
        person_id: str,
        name: str,
        role_type: str = "cast",
        role_name: str = "",
        department: str = "",
        billing_order: int = 9999,
        profile_url: str = "",
        known_for_department: str = "",
        source_id: str = "tmdb",
    ) -> None:
        if not person_id.strip() or not name.strip():
            raise ValueError("person_id and name are required")
        now = utc_now()
        self.conn.execute(
            "INSERT INTO people(person_id,name,profile_url,known_for_department,source_id,updated_at) "
            "VALUES(?,?,?,?,?,?) ON CONFLICT(person_id) DO UPDATE SET "
            "name=excluded.name, profile_url=CASE WHEN excluded.profile_url!='' THEN excluded.profile_url ELSE people.profile_url END, "
            "known_for_department=CASE WHEN excluded.known_for_department!='' THEN excluded.known_for_department ELSE people.known_for_department END, "
            "source_id=excluded.source_id, updated_at=excluded.updated_at",
            (person_id, name, profile_url, known_for_department, source_id, now),
        )
        self.conn.execute(
            "INSERT INTO media_people(media_id,person_id,role_type,role_name,department,billing_order,source_id,updated_at) "
            "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(media_id,person_id,role_type,role_name) DO UPDATE SET "
            "department=excluded.department,billing_order=excluded.billing_order,source_id=excluded.source_id,updated_at=excluded.updated_at",
            (
                int(media_id),
                person_id,
                role_type,
                role_name,
                department,
                int(billing_order),
                source_id,
                now,
            ),
        )
        self.conn.commit()

    def _movie_row(self, media_id: int) -> Optional[dict[str, Any]]:
        row = self.conn.execute("SELECT * FROM movies WHERE id=?", (int(media_id),)).fetchone()
        return dict(row) if row is not None else None

    def _credits(self, media_id: int) -> list[dict[str, Any]]:
        rows = self.conn.execute(
            "SELECT p.person_id,p.name,p.profile_url,p.known_for_department,"
            "mp.role_type,mp.role_name,mp.department,mp.billing_order "
            "FROM media_people mp JOIN people p ON p.person_id=mp.person_id "
            "WHERE mp.media_id=? ORDER BY CASE mp.role_type WHEN 'cast' THEN 0 ELSE 1 END,mp.billing_order,p.name",
            (int(media_id),),
        ).fetchall()
        return [dict(row) for row in rows]

    def _availability(self, media_id: int) -> dict[str, Any]:
        rows = self.conn.execute(
            "SELECT source_key,source_name,source_kind,quality,voice,first_seen_at,last_seen_at "
            "FROM playable_sources WHERE media_id=? AND playable=1 ORDER BY last_seen_at DESC",
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
        sources.sort(
            key=lambda row: (quality_sort_key(row.get("quality", "")), row.get("last_seen_at", "")),
            reverse=True,
        )
        return {
            "playable": bool(rows),
            "best_quality": qualities[0] if qualities else "",
            "qualities": qualities,
            "voices": voices,
            "source_count": len(rows),
            "sources": sources,
        }

    def full_card(self, media_id: int) -> Optional[dict[str, Any]]:
        base = self._movie_row(media_id)
        if base is None:
            return None
        consensus = self.consensus(media_id)
        card = dict(base)
        for field, value in consensus["values"].items():
            if _is_meaningful(value):
                card[field] = value
        for field in ("genres", "creators", "alternative_titles", "season_episode_counts"):
            if field in card and isinstance(card[field], str):
                parsed = _json_load(card[field])
                if parsed is not None:
                    card[field] = parsed
        credits = self._credits(media_id)
        relational_cast = [credit for credit in credits if credit["role_type"] == "cast"]
        relational_crew = [credit for credit in credits if credit["role_type"] != "cast"]
        if relational_cast:
            card["cast"] = relational_cast
        elif isinstance(card.get("cast"), str):
            parsed_cast = _json_load(card.get("cast"))
            card["cast"] = parsed_cast if isinstance(parsed_cast, list) else []
        card["crew"] = relational_crew
        card["availability"] = self._availability(media_id)
        card["metadata_provenance"] = consensus["provenance"]
        required = ("title", "synopsis", "poster_url", "backdrop_url", "genres", "year")
        present = sum(1 for field in required if _is_meaningful(card.get(field)))
        card["metadata_completeness"] = round(present / len(required), 4)
        return card

    def find_people(self, query: str, limit: int = 20) -> list[dict[str, Any]]:
        pattern = f"%{_clean_text(query)}%"
        rows = self.conn.execute(
            "SELECT person_id,name,profile_url,known_for_department FROM people "
            "WHERE name LIKE ? COLLATE NOCASE ORDER BY name LIMIT ?",
            (pattern, max(1, min(int(limit), 100))),
        ).fetchall()
        return [dict(row) for row in rows]

    def person_projects(
        self,
        *,
        person_id: Optional[str] = None,
        name: Optional[str] = None,
        limit: int = 100,
    ) -> dict[str, Any]:
        if person_id:
            person = self.conn.execute(
                "SELECT * FROM people WHERE person_id=?", (person_id,)
            ).fetchone()
        elif name:
            person = self.conn.execute(
                "SELECT * FROM people WHERE name=? COLLATE NOCASE ORDER BY updated_at DESC LIMIT 1",
                (_clean_text(name),),
            ).fetchone()
        else:
            raise ValueError("person_id or name is required")
        if person is None:
            return {"person": None, "projects": []}
        rows = self.conn.execute(
            "SELECT m.*,mp.role_type,mp.role_name,mp.department,mp.billing_order "
            "FROM media_people mp JOIN movies m ON m.id=mp.media_id "
            "WHERE mp.person_id=? ORDER BY COALESCE(m.year,0) DESC,mp.billing_order,m.title LIMIT ?",
            (str(person["person_id"]), max(1, min(int(limit), 500))),
        ).fetchall()
        projects = []
        for row in rows:
            project = dict(row)
            for field in ("genres", "creators"):
                if isinstance(project.get(field), str):
                    parsed = _json_load(project[field])
                    if parsed is not None:
                        project[field] = parsed
            projects.append(project)
        return {"person": dict(person), "projects": projects}
