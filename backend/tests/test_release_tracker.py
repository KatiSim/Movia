import sqlite3
import unittest
from datetime import date

from catalog.metadata import MetadataEngine
from catalog.sync import PlayableSourceObservation, ReleaseTracker, normalize_quality
from catalog_intelligence_api import CatalogIntelligenceApi


def make_db():
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.executescript(
        """
        CREATE TABLE movies(
            id INTEGER PRIMARY KEY,
            title TEXT NOT NULL,
            media_type TEXT NOT NULL DEFAULT 'movie',
            original_title TEXT DEFAULT '',
            year INTEGER DEFAULT 0,
            rating REAL DEFAULT 0,
            vote_average REAL DEFAULT 0,
            vote_count INTEGER DEFAULT 0,
            duration_minutes INTEGER DEFAULT 0,
            synopsis TEXT DEFAULT '',
            poster_url TEXT DEFAULT '',
            backdrop_url TEXT DEFAULT '',
            genres TEXT DEFAULT '[]',
            cast TEXT DEFAULT '[]',
            director TEXT DEFAULT '',
            country TEXT DEFAULT '',
            creators TEXT DEFAULT '[]',
            alternative_titles TEXT DEFAULT '[]',
            localized_ru_title TEXT DEFAULT '',
            seasons_count INTEGER DEFAULT 0,
            episodes_count INTEGER DEFAULT 0,
            season_episode_counts TEXT DEFAULT '[]',
            quality TEXT DEFAULT '',
            voice TEXT DEFAULT '',
            link_verified INTEGER DEFAULT 0,
            link_updated_at TEXT DEFAULT ''
        );
        INSERT INTO movies(id,title,media_type,year,genres) VALUES(1,'Fresh movie','movie',2026,'["Action"]');
        INSERT INTO movies(id,title,media_type,year,genres) VALUES(2,'Waiting movie','movie',2026,'["Drama"]');
        """
    )
    return conn


class ReleaseTrackerTest(unittest.TestCase):
    def setUp(self):
        self.conn = make_db()
        self.tracker = ReleaseTracker(self.conn)

    def tearDown(self):
        self.conn.close()

    def test_camrip_is_valid_first_playable_source(self):
        self.tracker.announce(
            media_id=1,
            title="Fresh movie",
            release_date="2026-09-06",
            discovery_source="tmdb",
        )
        self.assertEqual(self.tracker.new_releases(), [])

        status = self.tracker.observe_source(
            PlayableSourceObservation(
                media_id=1,
                source_key="provider:cam:1",
                source_name="provider",
                source_kind="direct",
                url="https://media.invalid/first.m3u8",
                quality="cam rip 720p",
                voice="Original",
                playable=True,
                observed_at="2026-09-06T08:00:00+00:00",
            )
        )

        self.assertTrue(status["promoted"])
        self.assertEqual(status["state"], "playable")
        self.assertEqual(status["first_playable_at"], "2026-09-06T08:00:00+00:00")
        self.assertEqual(status["availability"]["best_quality"], "CAMRip 720p")
        releases = self.tracker.new_releases()
        self.assertEqual([item["media_id"] for item in releases], [1])

    def test_quality_and_voice_are_updated_when_better_source_appears(self):
        self.tracker.observe_source(
            PlayableSourceObservation(
                media_id=1,
                source_key="cam",
                quality="CAMRip",
                voice="Original",
                playable=True,
            )
        )
        self.tracker.observe_source(
            PlayableSourceObservation(
                media_id=1,
                source_key="web",
                quality="web-dl 1080p",
                voice="Dub RU",
                playable=True,
            )
        )

        availability = self.tracker.availability(1)
        row = self.conn.execute("SELECT quality,voice,link_verified FROM movies WHERE id=1").fetchone()

        self.assertEqual(availability["best_quality"], "WEB-DL 1080p")
        self.assertEqual(availability["qualities"], ["WEB-DL 1080p", "CAMRip"])
        self.assertEqual(availability["voices"], ["Dub RU", "Original"])
        self.assertEqual(row["quality"], "WEB-DL 1080p")
        self.assertEqual(row["voice"], "Dub RU, Original")
        self.assertEqual(row["link_verified"], 1)

    def test_reactivation_is_not_promoted_twice_and_clears_legacy_state(self):
        first = self.tracker.observe_source(
            PlayableSourceObservation(media_id=1, source_key="one", quality="CAMRip", voice="Original", playable=True)
        )
        disabled = self.tracker.observe_source(
            PlayableSourceObservation(media_id=1, source_key="one", quality="CAMRip", voice="Original", playable=False)
        )
        row = self.conn.execute("SELECT quality,voice,link_verified FROM movies WHERE id=1").fetchone()
        restored = self.tracker.observe_source(
            PlayableSourceObservation(media_id=1, source_key="one", quality="WEB-DL 1080p", voice="Dub RU", playable=True)
        )

        self.assertTrue(first["promoted"])
        self.assertEqual(disabled["state"], "waiting")
        self.assertEqual((row["quality"], row["voice"], row["link_verified"]), ("", "", 0))
        self.assertFalse(restored["promoted"])
        self.assertEqual(restored["first_playable_at"], first["first_playable_at"])

    def test_daily_tracker_is_idempotent_and_promotes_only_playable(self):
        first = self.tracker.run_daily(
            day=date(2026, 9, 6),
            candidates=[
                {"media_id": 1, "title": "Fresh movie", "release_date": "2026-09-06"},
                {"media_id": 2, "title": "Waiting movie", "release_date": "2026-09-06"},
            ],
            sources=[
                {
                    "media_id": 1,
                    "source_key": "first",
                    "quality": "CAMRip",
                    "voice": "Original",
                    "playable": True,
                }
            ],
        )
        second = self.tracker.run_daily(day=date(2026, 9, 6))

        self.assertEqual(first["promoted_media_ids"], [1])
        self.assertEqual(first["playable_release_count"], 1)
        self.assertTrue(second["skipped"])
        self.assertEqual(self.tracker.release_status(2)["state"], "waiting")

    def test_api_full_card_reflects_normalized_availability(self):
        api = CatalogIntelligenceApi(self.conn)
        api.observe_playable_source(
            media_id=1,
            source_key="provider:1",
            quality="WEBRip 1080p",
            voice="RU voice",
            playable=True,
        )
        card = api.media_card(1)["item"]
        self.assertTrue(card["availability"]["playable"])
        self.assertEqual(card["availability"]["qualities"], ["WEBRip 1080p"])
        self.assertEqual(card["availability"]["voices"], ["RU voice"])

    def test_quality_normalization_keeps_camrip_explicit(self):
        self.assertEqual(normalize_quality("CAM"), "CAMRip")
        self.assertEqual(normalize_quality("cam-rip 1080p"), "CAMRip 1080p")
        self.assertEqual(normalize_quality("WEB-DL 4K"), "WEB-DL 2160p")


if __name__ == "__main__":
    unittest.main()
