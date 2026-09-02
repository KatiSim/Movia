#!/usr/bin/env python3
from __future__ import annotations

import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

import database
from database import filter_streams_for_content, save_content
from stream_validation import sanitize_streams, stream_variant_key
from zona_cache_migrator import normalize_title, parse_cache_key, category_matches


class BackendPipelineSafetyTests(unittest.TestCase):
    def setUp(self):
        self._old_db_path = database.DB_PATH

    def tearDown(self):
        database.DB_PATH = self._old_db_path

    def _temporary_catalog(self):
        tmp = tempfile.TemporaryDirectory()
        db_path = Path(tmp.name) / "catalog.db"
        conn = sqlite3.connect(db_path)
        conn.execute(
            """
            CREATE TABLE movies (
                id INTEGER PRIMARY KEY,
                tmdb_id INTEGER,
                media_type TEXT,
                title TEXT,
                original_title TEXT,
                year INTEGER,
                category TEXT,
                streams TEXT,
                playback_url TEXT,
                link_verified INTEGER,
                voice TEXT,
                quality TEXT,
                seeders INTEGER,
                link_updated_at TEXT
            )
            """
        )
        conn.execute(
            "INSERT INTO movies VALUES (1, 101, 'movie', 'Тестовый фильм', 'Test Movie', 2024, 'movies', '[]', '', 0, '', '', 0, NULL)"
        )
        conn.commit()
        conn.close()
        database.DB_PATH = db_path
        return tmp, db_path

    def test_save_content_is_additive_and_idempotent(self):
        tmp, db_path = self._temporary_catalog()
        self.addCleanup(tmp.cleanup)
        first = {
            "source": "Provider A",
            "url": "https://cdn.example.com/movie-1080.m3u8",
            "voice": "Дубляж",
            "quality": "1080p",
        }
        second = {
            "source": "Provider B",
            "url": "https://cdn.example.com/movie-720.m3u8",
            "voice": "Дубляж",
            "quality": "720p",
        }
        self.assertTrue(save_content({"id": 1, "streams": [first], "link_verified": 1}))
        self.assertTrue(save_content({"id": 1, "streams": [second], "link_verified": 1}))
        self.assertTrue(save_content({"id": 1, "streams": [second], "link_verified": 1}))
        conn = sqlite3.connect(db_path)
        streams = json.loads(conn.execute("SELECT streams FROM movies WHERE id=1").fetchone()[0])
        verified = conn.execute("SELECT link_verified FROM movies WHERE id=1").fetchone()[0]
        conn.close()
        self.assertEqual(2, len(streams))
        self.assertEqual(2, len({stream_variant_key(item) for item in streams}))
        self.assertEqual(1, verified)

    def test_movie_rejects_explicit_episode_release(self):
        stream = {
            "source": "Torrent",
            "url": "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Test.Movie.S01E01.2024",
            "title": "Test Movie S01E01 2024",
            "quality": "1080p",
        }
        card = {
            "title": "Тестовый фильм",
            "original_title": "Test Movie",
            "year": 2024,
            "media_type": "movie",
            "category": "movies",
        }
        self.assertEqual([], filter_streams_for_content([stream], card))

    def test_cache_key_parser_and_title_normalization(self):
        parsed = parse_cache_key("пацаны_2019_tv_series_s1_e1")
        self.assertEqual(2019, parsed["year"])
        self.assertEqual("tv_series", parsed["category"])
        self.assertEqual(1, parsed["season"])
        self.assertEqual(1, parsed["episode"])
        self.assertEqual(normalize_title("Ёлки—Новые"), normalize_title("елки новые"))

    def test_category_match_does_not_randomly_cross_categories(self):
        movie = {"media_type": "movie", "category": "movies"}
        animation = {"media_type": "movie", "category": "animation"}
        self.assertTrue(category_matches("movies", movie))
        self.assertTrue(category_matches("animation", animation))
        self.assertFalse(category_matches("animation", movie))

    def test_structural_dedup_is_stable(self):
        raw = [
            {"source": "A", "url": "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=one&tr=x", "voice": "X", "quality": "1080p"},
            {"source": "B", "url": "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=two&tr=y", "voice": "X", "quality": "1080p"},
        ]
        clean = sanitize_streams(raw, require_source=True)
        self.assertEqual(1, len(clean))

    def test_legacy_scripts_do_not_write_media_catalog(self):
        root = Path(__file__).resolve().parent
        updater = (root / "content_updater.py").read_text(encoding="utf-8")
        manual = (root / "update_streams.py").read_text(encoding="utf-8")
        self.assertNotIn('DB_PATH = DIR / "media_catalog.db"', updater)
        self.assertNotIn("MEDIA_CATALOG_DB", manual)
        self.assertNotIn("for db_path in [CATALOG_DB, MEDIA_CATALOG_DB]", manual)
        self.assertIn("save_content", updater)
        self.assertIn("save_content", manual)


if __name__ == "__main__":
    unittest.main()
