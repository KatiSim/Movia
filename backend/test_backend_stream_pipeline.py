#!/usr/bin/env python3
from __future__ import annotations

import json
import unittest

from database import filter_streams_for_content
from stream_validation import canonical_stream_locator, is_valid_magnet, is_valid_stream_url, sanitize_streams, stream_variant_key


class BackendStreamPipelineTests(unittest.TestCase):
    def test_valid_http_and_invalid_placeholder(self):
        self.assertTrue(is_valid_stream_url("https://example.org/video.m3u8"))
        self.assertFalse(is_valid_stream_url("https://dlcache4.vibio.tv/direct/out60.mp4"))
        self.assertFalse(is_valid_stream_url("https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"))

    def test_magnet_btih_validation(self):
        valid = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=x"
        invalid = "magnet:?xt=urn:btih:not-a-hash&dn=x"
        self.assertTrue(is_valid_magnet(valid))
        self.assertFalse(is_valid_magnet(invalid))

    def test_tracker_and_dn_do_not_change_magnet_identity(self):
        a = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=A&tr=udp://tracker-a"
        b = "magnet:?tr=udp://tracker-b&dn=B&xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        self.assertEqual(canonical_stream_locator(a), canonical_stream_locator(b))

    def test_file_selection_changes_identity(self):
        base = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        a = base + "&so=1"
        b = base + "&so=2"
        self.assertNotEqual(canonical_stream_locator(a), canonical_stream_locator(b))

    def test_voice_quality_are_variant_dimensions(self):
        url = "https://example.org/video.m3u8"
        a = {"source": "Zona API", "url": url, "voice": "A", "quality": "1080p"}
        b = {"source": "Zona API", "url": url, "voice": "B", "quality": "1080p"}
        self.assertNotEqual(stream_variant_key(a), stream_variant_key(b))

    def test_sanitize_requires_source(self):
        raw = [{"url": "https://example.org/video.m3u8"}]
        self.assertEqual(sanitize_streams(raw, require_source=True), [])

    def test_movie_rejects_explicit_episode_release(self):
        stream = {
            "source": "Rutor",
            "url": "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Movie.S01E01.2020",
            "title": "Movie S01E01 2020",
        }
        card = {"title": "Movie", "original_title": "Movie", "year": 2020, "media_type": "movie", "category": "movies"}
        self.assertEqual(filter_streams_for_content([stream], card), [])


if __name__ == "__main__":
    unittest.main()
