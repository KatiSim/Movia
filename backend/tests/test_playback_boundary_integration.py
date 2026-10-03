"""Exercise real API/queue/identity/normalization code with in-memory providers.

No public media requests, Android application or production database are used.
"""
import copy
import json
import unittest

from catalog_stream_service import CatalogStreamService
from cloud_api import ReadService
from stream_identity import filter_streams_for_content


class PlaybackBoundaryIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.card = {
            "id": "7", "title": "", "original_title": "", "year": 2020,
            "media_type": "movie", "category": "movies", "streams": [],
        }
        self.resolved = []
        self.requests = []
        self.persisted = []
        outer = self

        class Catalog:
            def get_movie_playback_card(self, ident):
                return copy.deepcopy(outer.card) if str(ident) == "7" else None

        class Runtime:
            def cloud_exposable_streams(self, rows):
                return rows

            def _direct_stream_expiry_seconds(self, row):
                return None

            def resolve_on_demand_streams(self, **request):
                outer.requests.append(copy.deepcopy(request))
                return copy.deepcopy(outer.resolved)

            def persist_resolved_streams_to_catalog(self, ident, rows):
                outer.persisted.append((str(ident), copy.deepcopy(rows)))
                outer.card["streams"] = copy.deepcopy(rows)
                return True

        catalog = Catalog()
        self.service = CatalogStreamService(catalog, Runtime(), filter_streams_for_content,
                                            workers=1, max_pending=2)
        self.api = ReadService(catalog, self.service)

    def tearDown(self):
        self.assertTrue(self.service.close())

    def row(self, name="source", **changes):
        return dict({
            "source": "Fixture provider", "voice": "Original", "quality": "720p",
            "url": "https://media.example/" + name + ".mp4", "catalog_media_id": "7",
        }, **changes)

    def response(self, query=""):
        code, data = self.api.response("/api/movie/7/stream" + query)
        self.assertEqual(200, code)
        return json.loads(data)

    def finish_discovery(self):
        with self.service.queue.condition:
            self.assertTrue(self.service.queue.condition.wait_for(
                lambda: self.service.queue.finished >= 1, timeout=3.0))

    def test_cached_master_does_not_publish_fake_first_track_or_fixed_quality(self):
        self.card["streams"] = [self.row(
            source="Collaps", url="https://media.example/master.m3u8", quality="1080p",
            audio_track_index=-1, video_track_index=-1,
            headers={"Referer": "https://provider.example/"},
        )]
        body = self.response()
        self.assertEqual("READY", body["status"])
        row = body["streams"][0]
        self.assertEqual("Auto", row["quality"])
        self.assertNotIn("audio_track_index", row)
        self.assertNotIn("video_track_index", row)
        self.assertEqual("7", row["catalog_media_id"])
        self.assertEqual("https://provider.example/", row["headers"]["Referer"])
        self.assertEqual(0, self.service.queue.stats()["accepted"])

    def test_api_preserves_real_zero_separately_from_automatic_selection(self):
        self.card["streams"] = [self.row(), self.row(audio_track_index=0)]
        rows = self.response()["streams"]
        self.assertEqual(2, len(rows))
        self.assertEqual(2, len({row["stream_id"] for row in rows}))
        self.assertEqual(1, sum("audio_track_index" not in row for row in rows))
        self.assertEqual([0], [row["audio_track_index"] for row in rows if "audio_track_index" in row])

    def test_api_cannot_rebind_foreign_movie_sources_when_title_is_empty(self):
        self.card["streams"] = [
            self.row("wrong-id", catalog_media_id="99"),
            self.row("wrong-year", canonical_year=2021),
            self.row("wrong-type", canonical_media_type="tv"),
            self.row("episode", season=2, episode=3),
            self.row("right"),
        ]
        rows = self.response()["streams"]
        self.assertEqual(["https://media.example/right.mp4"], [row["url"] for row in rows])
        self.assertEqual("7", rows[0]["catalog_media_id"])
        self.assertEqual(0, self.service.queue.stats()["accepted"])

    def test_api_keeps_only_exact_episode_without_waiting_for_title_enrichment(self):
        self.card.update(media_type="tv", category="tv_series")
        self.card["streams"] = [
            self.row("other-episode", season=2, episode=4),
            self.row("other-season", season=3, episode=3),
            self.row("missing-episode"),
            self.row("right", season=2, episode=3),
        ]
        rows = self.response("?season=2&episode=3")["streams"]
        self.assertEqual(["https://media.example/right.mp4"], [row["url"] for row in rows])
        self.assertEqual((2, 3), (rows[0]["season"], rows[0]["episode"]))
        self.assertEqual("tv", rows[0]["canonical_media_type"])

    def test_discovery_cannot_persist_a_foreign_movie_as_the_requested_card(self):
        self.resolved = [self.row("wrong-id", catalog_media_id="99")]
        self.response()
        self.finish_discovery()
        body = self.response()
        self.assertEqual("UNAVAILABLE", body["status"])
        self.assertEqual([], body["streams"])
        self.assertEqual([], self.persisted)
        self.assertEqual(1, len(self.requests))
        self.assertTrue(self.requests[0]["require_catalog_identity"])
        self.assertEqual("7", self.requests[0]["catalog_media_id"])

    def test_discovery_normalizes_unknown_selectors_before_cache_persistence(self):
        self.resolved = [self.row(audioTrackIndex=-1, videoTrackIndex=-1)]
        self.response()
        self.finish_discovery()
        body = self.response()
        self.assertEqual("READY", body["status"])
        self.assertEqual(1, len(self.persisted))
        ident, saved = self.persisted[0]
        self.assertEqual("7", ident)
        self.assertNotIn("audio_track_index", saved[0])
        self.assertNotIn("video_track_index", saved[0])
        self.assertEqual(saved[0]["stream_id"], body["streams"][0]["stream_id"])
        self.assertEqual(1, len(self.requests))
