import copy
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

# test_streamer_torrent_gid.py uses lightweight import stubs when run first in
# the same unittest process. Remove only those stubs so this test still covers
# the real catalog module in an aggregate run.
for _module_name in ("catalog_api", "live_catalog_sync"):
    if _module_name in sys.modules and not getattr(sys.modules[_module_name], "__file__", None):
        del sys.modules[_module_name]

import live_catalog_sync as sync
from tmdb_client import TMDbClient


class _Response:
    def __init__(self, status_code, payload=None, headers=None):
        self.status_code = status_code
        self._payload = payload
        self.headers = headers or {}

    def json(self):
        return self._payload


class CatalogSyncTests(unittest.TestCase):
    def setUp(self):
        self.status_backup = copy.deepcopy(sync._STATUS)
        sync._STATUS.update(
            running=False,
            last_success_at=None,
            last_finished_at=None,
            last_error=None,
            last_feed_errors=[],
            last_cache_error=None,
            last_run_ok=None,
            consecutive_failures=0,
        )

    def tearDown(self):
        sync._STATUS.clear()
        sync._STATUS.update(self.status_backup)

    @staticmethod
    def _create_schema(path: Path):
        from catalog_schema_v2 import ensure_schema
        with sqlite3.connect(path) as conn:
            conn.execute(
                """
                CREATE TABLE movies (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tmdb_id INTEGER NOT NULL,
                    media_type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    original_title TEXT,
                    year INTEGER,
                    rating REAL DEFAULT 0,
                    duration_minutes INTEGER DEFAULT 0,
                    synopsis TEXT,
                    poster_url TEXT,
                    backdrop_url TEXT,
                    genres TEXT,
                    cast TEXT,
                    director TEXT,
                    country TEXT,
                    category TEXT,
                    streams TEXT DEFAULT '[]',
                    vote_count INTEGER DEFAULT 0,
                    vote_average REAL DEFAULT 0,
                    seasons_count INTEGER DEFAULT 0,
                    episodes_count INTEGER DEFAULT 0,
                    season_episode_counts TEXT DEFAULT '[]',
                    created_at TEXT DEFAULT '',
                    updated_at TEXT DEFAULT '',
                    UNIQUE(media_type, tmdb_id)
                )
                """
            )
            conn.execute(
                """
                INSERT INTO movies (
                    tmdb_id, media_type, title, original_title, year, rating,
                    duration_minutes, synopsis, poster_url, backdrop_url,
                    genres, cast, director, country, category, streams,
                    vote_count, vote_average, seasons_count, episodes_count,
                    season_episode_counts
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    202,
                    "tv",
                    "Обогащённый сериал",
                    "Enriched Series",
                    2017,
                    8.2,
                    52,
                    "trusted synopsis",
                    "https://image.test/poster.jpg",
                    "https://image.test/backdrop.jpg",
                    json.dumps(["драма"], ensure_ascii=False),
                    "[]",
                    "",
                    "США",
                    "tv_series",
                    json.dumps(
                        [{
                            "source": "Zona API",
                            "voice": "Кубик в Кубе",
                            "quality": "720p",
                            "url": "https://media.example.test/episode.m3u8",
                        }],
                        ensure_ascii=False,
                    ),
                    1200,
                    8.2,
                    3,
                    24,
                    json.dumps([8, 8, 8]),
                ),
            )
        ensure_schema(path)

    def test_sync_preserves_enriched_fields_and_unknown_years(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "catalog.db"
            self._create_schema(db_path)
            movie = {
                "id": 101,
                "title": "Undated Movie",
                "original_title": "Undated Movie",
                "release_date": "",
                "genre_ids": [18],
                "poster_path": "/poster.jpg",
                "vote_average": 7.5,
                "vote_count": 100,
            }
            tv = {
                "id": 202,
                "name": "Обогащённый сериал",
                "original_name": "Enriched Series",
                "first_air_date": "",
                "genre_ids": [18],
                "poster_path": "/tv.jpg",
                "vote_average": 8.0,
                "vote_count": 90,
            }
            calls = []

            def fake_get(endpoint, params, **kwargs):
                calls.append((endpoint, kwargs.get("max_retries")))
                media_type = "tv" if ("/tv/" in endpoint or endpoint.endswith("/tv")) else "movie"
                return {"results": [tv] if media_type == "tv" else [movie]}

            with patch.object(sync, "DB_PATH", db_path), \
                    patch.object(sync.tmdb, "_get", side_effect=fake_get):
                result = sync.sync_once(pages=1)

            self.assertTrue(result["last_run_ok"])
            self.assertEqual(result["last_seen"], 2)
            self.assertTrue(result["last_success_at"])
            self.assertTrue(calls)
            self.assertEqual(len(calls), len(sync.FEEDS) + 1)
            self.assertTrue(all(retry == 1 for _, retry in calls))

            with sqlite3.connect(db_path) as conn:
                movie_row = conn.execute(
                    "SELECT year FROM movies WHERE media_type='movie' AND tmdb_id=101"
                ).fetchone()
                tv_row = conn.execute(
                    """
                    SELECT year, seasons_count, episodes_count,
                           season_episode_counts, streams
                    FROM movies WHERE media_type='tv' AND tmdb_id=202
                    """
                ).fetchone()

            self.assertEqual(movie_row[0], 0)
            self.assertEqual(tv_row[0], 2017)
            self.assertEqual(tv_row[1:3], (3, 24))
            self.assertEqual(json.loads(tv_row[3]), [8, 8, 8])
            self.assertIn("Кубик в Кубе", tv_row[4])

    def test_missing_provider_labels_stay_unknown(self):
        from balancer_integration import normalize_voice_name
        from torrent_resolver import classify_voice_and_quality

        self.assertEqual(normalize_voice_name(None), "Не указано")
        self.assertEqual(
            classify_voice_and_quality("Example Movie 2030 WEB release"),
            ("Не указано", "Не указано"),
        )

    def test_detail_call_does_not_retry_unless_requested(self):
        client = TMDbClient(api_key="test")
        client.session = Mock()
        client.session.get.return_value = _Response(503)
        with patch("tmdb_client.time.sleep"):
            self.assertIsNone(client._get("/movie/1", {}))
        self.assertEqual(client.session.get.call_count, 1)

        client.session.reset_mock()
        client.session.get.side_effect = [
            _Response(503),
            _Response(200, {"results": []}),
        ]
        with patch("tmdb_client.time.sleep"):
            self.assertEqual(client._get("/movie/popular", {}, max_retries=1), {"results": []})
        self.assertEqual(client.session.get.call_count, 2)


if __name__ == "__main__":
    unittest.main()
