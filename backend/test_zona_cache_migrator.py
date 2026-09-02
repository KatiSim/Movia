#!/usr/bin/env python3
from __future__ import annotations

import copy
import unittest

import zona_cache_migrator as zcm
from stream_validation import stream_variant_key


class ZonaCacheMigratorTests(unittest.TestCase):
    def test_parse_cache_key_movie(self):
        parsed = zcm.parse_cache_key("жмурки_2005_movies")
        self.assertEqual(parsed["title"], "жмурки")
        self.assertEqual(parsed["year"], 2005)
        self.assertEqual(parsed["category"], "movies")
        self.assertIsNone(parsed["season"])
        self.assertIsNone(parsed["episode"])

    def test_parse_cache_key_episode(self):
        parsed = zcm.parse_cache_key("пацаны_2019_tv_series_s1_e2")
        self.assertEqual(parsed["category"], "tv_series")
        self.assertEqual(parsed["season"], 1)
        self.assertEqual(parsed["episode"], 2)

    def test_title_normalization(self):
        self.assertEqual(
            zcm.normalize_title("Прогулки с монстрами: Жизнь до динозавров"),
            zcm.normalize_title("ПРОГУЛКИ С МОНСТРАМИ — жизнь до динозавров"),
        )
        self.assertEqual(zcm.normalize_title("Ёлки"), zcm.normalize_title("елки"))

    def test_category_matching(self):
        self.assertTrue(zcm.category_matches("movies", {"media_type": "movie", "category": "movies"}))
        self.assertTrue(zcm.category_matches("tv_series", {"media_type": "tv", "category": "series"}))
        self.assertTrue(zcm.category_matches("animation", {"media_type": "movie", "category": "animation"}))
        self.assertFalse(zcm.category_matches("tv_series", {"media_type": "movie", "category": "movies"}))

    def test_analysis_is_additive_and_idempotent(self):
        stream = {
            "source": "Zona API",
            "provider": "test",
            "url": "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Movie.2020",
            "title": "Movie 2020",
            "voice": "Dub",
            "quality": "1080p",
        }
        card = {
            "id": 10,
            "tmdb_id": 1,
            "media_type": "movie",
            "title": "Movie",
            "original_title": "Movie",
            "year": 2020,
            "category": "movies",
            "streams": "[]",
            "playback_url": "",
            "link_verified": 0,
            "voice": "",
            "quality": "",
            "seeders": 0,
        }
        index = {(zcm.normalize_title("Movie"), 2020): [card]}
        entries = [{"cache_key": "movie_2020_movies", "parsed": zcm.parse_cache_key("movie_2020_movies"), "streams": [stream]}]
        report, additions = zcm.analyze(entries, index)
        self.assertEqual(report["stats"]["new_streams"], 1)
        self.assertEqual(len(additions[10]), 1)

        card2 = copy.deepcopy(card)
        import json
        card2["streams"] = json.dumps([stream])
        index2 = {(zcm.normalize_title("Movie"), 2020): [card2]}
        report2, additions2 = zcm.analyze(entries, index2)
        self.assertEqual(report2["stats"]["new_streams"], 0)
        self.assertEqual(report2["stats"]["duplicate_streams"], 1)
        self.assertFalse(additions2)

    def test_ambiguous_is_not_selected(self):
        parsed = zcm.parse_cache_key("same_2020_movies")
        entries = [{"cache_key": "same_2020_movies", "parsed": parsed, "streams": []}]
        c1 = {"id": 1, "title": "Same", "original_title": "Same", "year": 2020, "category": "movies", "media_type": "movie", "streams": "[]"}
        c2 = {"id": 2, "title": "Same", "original_title": "Same", "year": 2020, "category": "movies", "media_type": "movie", "streams": "[]"}
        index = {(zcm.normalize_title("Same"), 2020): [c1, c2]}
        report, additions = zcm.analyze(entries, index)
        self.assertEqual(report["stats"]["ambiguous_entries"], 1)
        self.assertFalse(additions)


if __name__ == "__main__":
    unittest.main()
