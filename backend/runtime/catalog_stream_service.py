"""Identity-bound cached playback API with bounded server-side discovery."""
import time
from discovery_queue import DiscoveryQueue
from stream_validation import bind_stream_identity


class CatalogStreamService:
    def __init__(self, catalog, runtime, content_filter, *, workers=2, max_pending=64):
        self.catalog, self.runtime, self.content_filter = catalog, runtime, content_filter
        self.queue = DiscoveryQueue(self._resolve, workers=workers, max_pending=max_pending)

    @staticmethod
    def _is_series(card):
        return str(card.get("media_type") or card.get("category") or "").casefold() in {
            "tv", "tv_series", "series", "serial", "limited_series", "dramas_asian", "anime"}

    def _rows(self, card, rows, season, episode):
        scoped = self.content_filter(rows, dict(card, season=season, episode=episode))
        exposed = self.runtime.cloud_exposable_streams(scoped)
        now = time.time()
        eligible = []
        for row in exposed:
            expiry = self.runtime._direct_stream_expiry_seconds(row)
            if expiry is None or expiry > now + 15: eligible.append(row)
        exposed = eligible
        return bind_stream_identity(exposed, catalog_media_id=card["id"], title=card.get("title"),
                                    original_title=card.get("original_title"), year=card.get("year"),
                                    media_type=card.get("media_type"), season=season, episode=episode)

    def __call__(self, movie_id, season, episode, *, force_refresh=False):
        card = self.catalog.get_movie_playback_card(movie_id)
        if not card: return 404, {"code": "NOT_FOUND"}
        if (season is None) != (episode is None) or (self._is_series(card) and season is None):
            return 400, {"code": "EXACT_EPISODE_REQUIRED"}
        if not self._is_series(card) and season is not None:
            return 400, {"code": "MOVIE_EPISODE_NOT_ALLOWED"}
        rows = self._rows(card, card.get("streams", []), season, episode)
        key = (str(card["id"]), season, episode)
        if not rows or force_refresh: self.queue.submit(key)
        discovery = self.queue.status(key)
        if not rows and discovery == "READY":
            updated = self.catalog.get_movie_playback_card(movie_id)
            if updated: rows = self._rows(updated, updated.get("streams", []), season, episode)
        if rows: status = "READY"
        elif discovery in {"QUEUED", "RUNNING"}: status = "DISCOVERY_PENDING"
        elif discovery == "UNAVAILABLE": status = "UNAVAILABLE"
        elif discovery == "STOPPED": status = "TEMPORARILY_UNAVAILABLE"
        else: status = "BUSY"
        return 200, {"streams": rows, "status": status, "discoveryStatus": discovery,
                     "refreshing": discovery in {"QUEUED", "RUNNING"}, "retryAfterMs": 350,
                     "mediaId": str(card["id"]), "season": season, "episode": episode}

    def _resolve(self, key):
        movie_id, season, episode = key
        card = self.catalog.get_movie_playback_card(movie_id)
        if not card: return False
        rows = self.runtime.resolve_on_demand_streams(
            title=card.get("title") or "", year=int(card.get("year") or 0),
            category=card.get("category") or ("tv_series" if self._is_series(card) else "movies"),
            season=season, episode=episode, tmdb_id=int(card.get("tmdb_id") or 0),
            force_refresh=True, original_title=card.get("original_title"),
            catalog_media_id=card["id"], media_type=card.get("media_type"),
            require_catalog_identity=True, _allow_stale_fast_path=False)
        rows = self._rows(card, rows, season, episode)
        if not rows: return False
        return bool(self.runtime.persist_resolved_streams_to_catalog(card["id"], rows))

    def close(self): return self.queue.close()
