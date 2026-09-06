from __future__ import annotations

import json
import math
import sqlite3
from collections import defaultdict
from typing import Any, Optional

from .metadata import MetadataEngine
from .schema import ensure_catalog_intelligence_schema, utc_now


def _genre_key(value: Any) -> str:
    return " ".join(str(value or "").strip().casefold().split())


def _genres_from_value(value: Any) -> list[str]:
    if isinstance(value, list):
        raw = value
    elif isinstance(value, str):
        text = value.strip()
        if not text:
            return []
        try:
            parsed = json.loads(text)
            raw = parsed if isinstance(parsed, list) else [text]
        except (TypeError, ValueError, json.JSONDecodeError):
            raw = [part.strip() for part in text.split(",")]
    else:
        return []
    result = []
    seen = set()
    for item in raw:
        display = " ".join(str(item or "").strip().split())
        key = _genre_key(display)
        if display and key not in seen:
            seen.add(key)
            result.append(display)
    return result


class RecommendationEngine:
    """Deterministic recommendations based on genre and cast affinity."""

    def __init__(self, conn: sqlite3.Connection, metadata: Optional[MetadataEngine] = None):
        self.conn = conn
        self.conn.row_factory = sqlite3.Row
        ensure_catalog_intelligence_schema(conn)
        self.metadata = metadata or MetadataEngine(conn)

    def refresh_media_index(self, media_id: int) -> int:
        card = self.metadata.full_card(media_id)
        if card is None:
            return 0
        genres = _genres_from_value(card.get("genres"))
        now = utc_now()
        with self.conn:
            self.conn.execute("DELETE FROM media_genres WHERE media_id=?", (int(media_id),))
            self.conn.executemany(
                "INSERT INTO media_genres(media_id,genre_key,display_genre,updated_at) VALUES(?,?,?,?)",
                [(int(media_id), _genre_key(genre), genre, now) for genre in genres],
            )
        return len(genres)

    def refresh_all(self) -> dict[str, int]:
        rows = self.conn.execute("SELECT id,genres FROM movies ORDER BY id").fetchall()
        now = utc_now()
        payload = []
        for row in rows:
            for genre in _genres_from_value(row["genres"]):
                payload.append((int(row["id"]), _genre_key(genre), genre, now))
        with self.conn:
            self.conn.execute("DELETE FROM media_genres")
            self.conn.executemany(
                "INSERT INTO media_genres(media_id,genre_key,display_genre,updated_at) VALUES(?,?,?,?)",
                payload,
            )
        return {"media_count": len(rows), "genre_links": len(payload)}

    def _ensure_index(self, media_id: int) -> None:
        row = self.conn.execute(
            "SELECT 1 FROM media_genres WHERE media_id=? LIMIT 1", (int(media_id),)
        ).fetchone()
        if row is None:
            self.refresh_media_index(media_id)

    def _genre_map(self, media_ids: list[int]) -> dict[int, set[str]]:
        if not media_ids:
            return {}
        placeholders = ",".join("?" for _ in media_ids)
        rows = self.conn.execute(
            f"SELECT media_id,genre_key FROM media_genres WHERE media_id IN ({placeholders})",
            media_ids,
        ).fetchall()
        result: dict[int, set[str]] = defaultdict(set)
        for row in rows:
            result[int(row["media_id"])].add(str(row["genre_key"]))
        return result

    def _cast_map(self, media_ids: list[int]) -> dict[int, set[str]]:
        if not media_ids:
            return {}
        placeholders = ",".join("?" for _ in media_ids)
        rows = self.conn.execute(
            f"SELECT media_id,person_id FROM media_people WHERE role_type='cast' AND media_id IN ({placeholders})",
            media_ids,
        ).fetchall()
        result: dict[int, set[str]] = defaultdict(set)
        for row in rows:
            result[int(row["media_id"])].add(str(row["person_id"]))
        return result

    def recommend(self, media_id: int, limit: int = 20) -> list[dict[str, Any]]:
        media_id = int(media_id)
        limit = max(1, min(int(limit), 100))
        self._ensure_index(media_id)
        target_genres = {
            str(row[0])
            for row in self.conn.execute(
                "SELECT genre_key FROM media_genres WHERE media_id=?", (media_id,)
            ).fetchall()
        }
        target_cast = {
            str(row[0])
            for row in self.conn.execute(
                "SELECT person_id FROM media_people WHERE media_id=? AND role_type='cast'",
                (media_id,),
            ).fetchall()
        }
        candidate_ids = set()
        if target_genres:
            placeholders = ",".join("?" for _ in target_genres)
            rows = self.conn.execute(
                f"SELECT DISTINCT media_id FROM media_genres WHERE genre_key IN ({placeholders}) AND media_id!=? LIMIT 1500",
                [*sorted(target_genres), media_id],
            ).fetchall()
            candidate_ids.update(int(row[0]) for row in rows)
        if target_cast:
            placeholders = ",".join("?" for _ in target_cast)
            rows = self.conn.execute(
                f"SELECT DISTINCT media_id FROM media_people WHERE role_type='cast' AND person_id IN ({placeholders}) AND media_id!=? LIMIT 1500",
                [*sorted(target_cast), media_id],
            ).fetchall()
            candidate_ids.update(int(row[0]) for row in rows)
        if not candidate_ids:
            return []

        ids = sorted(candidate_ids)
        placeholders = ",".join("?" for _ in ids)
        movie_rows = self.conn.execute(
            f"SELECT id,title,original_title,year,rating,vote_average,poster_url,backdrop_url,media_type FROM movies WHERE id IN ({placeholders})",
            ids,
        ).fetchall()
        genres = self._genre_map(ids)
        casts = self._cast_map(ids)
        person_names = {
            str(row["person_id"]): str(row["name"])
            for row in self.conn.execute("SELECT person_id,name FROM people").fetchall()
        }

        ranked = []
        for row in movie_rows:
            candidate_id = int(row["id"])
            candidate_genres = genres.get(candidate_id, set())
            candidate_cast = casts.get(candidate_id, set())
            shared_genres = target_genres & candidate_genres
            shared_cast = target_cast & candidate_cast
            if not shared_genres and not shared_cast:
                continue
            union = target_genres | candidate_genres
            genre_affinity = len(shared_genres) / len(union) if union else 0.0
            if target_cast and candidate_cast:
                cast_affinity = len(shared_cast) / math.sqrt(len(target_cast) * len(candidate_cast))
            else:
                cast_affinity = 0.0
            raw_rating = row["rating"] if row["rating"] not in (None, 0, 0.0) else row["vote_average"]
            try:
                rating_signal = max(0.0, min(float(raw_rating or 0.0) / 10.0, 1.0))
            except (TypeError, ValueError):
                rating_signal = 0.0
            score = 0.55 * genre_affinity + 0.40 * cast_affinity + 0.05 * rating_signal
            ranked.append(
                {
                    "id": candidate_id,
                    "title": row["title"],
                    "original_title": row["original_title"],
                    "year": row["year"],
                    "rating": raw_rating or 0.0,
                    "poster_url": row["poster_url"],
                    "backdrop_url": row["backdrop_url"],
                    "media_type": row["media_type"],
                    "recommendation_score": round(score, 6),
                    "reason": {
                        "shared_genres": sorted(shared_genres),
                        "shared_cast": [person_names.get(pid, pid) for pid in sorted(shared_cast)],
                        "genre_affinity": round(genre_affinity, 4),
                        "cast_affinity": round(cast_affinity, 4),
                    },
                }
            )
        ranked.sort(
            key=lambda item: (
                item["recommendation_score"],
                float(item["rating"] or 0),
                int(item["year"] or 0),
                -int(item["id"]),
            ),
            reverse=True,
        )
        return ranked[:limit]
