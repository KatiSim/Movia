#!/usr/bin/env python3
"""Tests for the clean-room LAMPA/Lampac metadata adapter."""
from __future__ import annotations

import unittest

from lampa_compat import normalize_lampa_result, parse_lampa_release_title
from stream_validation import sanitize_streams


class LampaReleaseParserTests(unittest.TestCase):
    def test_extracts_episode_voice_quality_and_hdr(self):
        parsed = parse_lampa_release_title(
            "Breaking Bad S01E02 1080p LostFilm HDR10"
        )
        self.assertEqual(parsed["season"], 1)
        self.assertEqual(parsed["episode"], 2)
        self.assertEqual(parsed["quality"], "1080p")
        self.assertEqual(parsed["hdr"], "HDR10")
        self.assertEqual(parsed["voices"], ["LostFilm"])

    def test_extracts_lampa_pack_seasons_and_russian_episode(self):
        parsed = parse_lampa_release_title(
            "Сериал Сезоны 1-4 Серия 7 1920x1080 Кубик в Кубе"
        )
        self.assertEqual(parsed["seasons"], [1, 2, 3, 4])
        self.assertEqual(parsed["episode"], 7)
        self.assertEqual(parsed["resolution"], "1920x1080")
        self.assertEqual(parsed["voices"], ["Кубик в Кубе"])

    def test_does_not_treat_tracker_page_link_as_playback_url(self):
        raw = {
            "Tracker": "Rutor",
            "Title": "Movie 2024 1080p",
            "Link": "https://tracker.example/details/42",
        }
        normalized = normalize_lampa_result(raw)
        self.assertNotIn("url", normalized)
        self.assertEqual(normalized["source_url"], raw["Link"])
        self.assertEqual(sanitize_streams([raw]), [])

    def test_maps_lampac_result_into_movia_stream_contract(self):
        magnet = "magnet:?xt=urn:btih:" + "a" * 40
        raw = {
            "Tracker": "Rutor",
            "Title": "Breaking Bad S01E02 1080p LostFilm",
            "Seeders": "42",
            "Peers": "7",
            "MagnetUri": magnet,
            "Link": "https://tracker.example/details/42",
            "Info": {
                "voices": ["LostFilm"],
                "quality": "1080p",
            },
            "ffprobe": {
                "streams": [
                    {"index": 0, "codec_type": "video"},
                    {"index": 1, "codec_type": "audio", "tags": {"title": "LostFilm"}},
                    {"index": 2, "codec_type": "subtitle"},
                ]
            },
        }
        streams = sanitize_streams([raw])
        self.assertEqual(len(streams), 1)
        stream = streams[0]
        self.assertEqual(stream["source"], "Rutor")
        self.assertEqual(stream["voice"], "LostFilm")
        self.assertEqual(stream["quality"], "1080p")
        self.assertEqual(stream["seeders"], 42)
        self.assertEqual(stream["season"], 1)
        self.assertEqual(stream["episode"], 2)
        self.assertEqual(stream["info_hash"], "a" * 40)
        self.assertEqual(stream["transport"], "torrent")
        self.assertTrue(stream["is_use_internal_subtitles"])

    def test_builds_magnet_from_explicit_hash(self):
        raw = {
            "Tracker": "Jackett",
            "Title": "Movie 2024 720p",
            "InfoHash": "b" * 40,
            "Seeders": 3,
        }
        normalized = normalize_lampa_result(raw)
        self.assertTrue(normalized["url"].startswith("magnet:?xt=urn:btih:"))
        streams = sanitize_streams([raw])
        self.assertEqual(len(streams), 1)
        self.assertEqual(streams[0]["info_hash"], "b" * 40)


if __name__ == "__main__":
    unittest.main()
