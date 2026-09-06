"""Movia catalog-intelligence API contract.

This module deliberately has no Flask/streamer dependency. The integration
agent can bind these methods to the existing HTTP server without editing this
agent's domain modules.
"""
from __future__ import annotations

import sqlite3
from datetime import date
from typing import Any, Iterable, Mapping, Optional

from catalog.metadata import MetadataEngine
from catalog.recommendation import RecommendationEngine
from catalog.schema import connect
from catalog.sync import PlayableSourceObservation, ReleaseTracker


class CatalogIntelligenceApi:
    def __init__(self, connection: sqlite3.Connection):
        self.connection = connection
        self.metadata = MetadataEngine(connection)
        self.recommendations = RecommendationEngine(connection, metadata=self.metadata)
        self.releases = ReleaseTracker(connection)

    @classmethod
    def from_db_path(cls, db_path: str) -> "CatalogIntelligenceApi":
        return cls(connect(db_path))

    def media_card(self, media_id: int) -> dict[str, Any]:
        card = self.metadata.full_card(media_id)
        if card is None:
            return {"ok": False, "error": "not_found", "media_id": int(media_id)}
        return {"ok": True, "item": card}

    def people_search(self, query: str, limit: int = 20) -> dict[str, Any]:
        return {"ok": True, "items": self.metadata.find_people(query, limit=limit)}

    def person_projects(
        self,
        *,
        person_id: Optional[str] = None,
        name: Optional[str] = None,
        limit: int = 100,
    ) -> dict[str, Any]:
        payload = self.metadata.person_projects(person_id=person_id, name=name, limit=limit)
        return {"ok": payload["person"] is not None, **payload}

    def recommendations_for(self, media_id: int, limit: int = 20) -> dict[str, Any]:
        if self.metadata.full_card(media_id) is None:
            return {"ok": False, "error": "not_found", "media_id": int(media_id), "items": []}
        return {
            "ok": True,
            "media_id": int(media_id),
            "items": self.recommendations.recommend(media_id, limit=limit),
        }

    def rebuild_recommendation_index(self) -> dict[str, Any]:
        return {"ok": True, **self.recommendations.refresh_all()}

    def record_metadata_observation(
        self,
        *,
        media_id: int,
        source_id: str,
        fields: Mapping[str, Any],
        confidence: float = 1.0,
        observed_at: Optional[str] = None,
    ) -> dict[str, Any]:
        count = self.metadata.observe(
            media_id,
            source_id,
            fields,
            confidence=confidence,
            observed_at=observed_at,
        )
        if "genres" in fields:
            self.recommendations.refresh_media_index(media_id)
        return {"ok": True, "updated_fields": count, "media_id": int(media_id)}

    def observe_playable_source(self, **payload: Any) -> dict[str, Any]:
        observation = PlayableSourceObservation(**payload)
        return {"ok": True, **self.releases.observe_source(observation)}

    def release_status(self, media_id: int) -> dict[str, Any]:
        status = self.releases.release_status(media_id)
        return {
            "ok": status.get("state") != "unknown",
            **status,
            "availability": self.releases.availability(media_id),
        }

    def new_releases(self, limit: int = 100) -> dict[str, Any]:
        return {"ok": True, "items": self.releases.new_releases(limit=limit, playable_only=True)}

    def run_daily_release_tracker(
        self,
        *,
        candidates: Iterable[Mapping[str, Any]] = (),
        sources: Iterable[PlayableSourceObservation | Mapping[str, Any]] = (),
        day: Optional[date] = None,
        force: bool = False,
    ) -> dict[str, Any]:
        return self.releases.run_daily(
            candidates=candidates,
            sources=sources,
            day=day,
            force=force,
        )


# Suggested integration routes (wiring only; do not place these in streamer.py here):
#   GET  /catalog/intelligence/media/<id>              -> media_card(id)
#   GET  /catalog/intelligence/media/<id>/recommend    -> recommendations_for(id)
#   GET  /catalog/intelligence/people?q=<name>         -> people_search(...)
#   GET  /catalog/intelligence/person/<id>/projects    -> person_projects(...)
#   POST /catalog/intelligence/metadata/observe        -> record_metadata_observation(...)
#   POST /catalog/intelligence/recommend/reindex       -> rebuild_recommendation_index()
#   POST /catalog/intelligence/source/observe          -> observe_playable_source(...)
#   GET  /catalog/intelligence/releases                -> new_releases(...)
#   POST /catalog/intelligence/releases/daily          -> run_daily_release_tracker(...)
