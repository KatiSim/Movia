#!/usr/bin/env python3
from __future__ import annotations

import urllib.parse

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

    def test_zona_v4_provider_aware_http_dedupe(self):
        for source_type in (2, 28):
            a = {
                "source": "Zona",
                "source_type_id": source_type,
                "url": "https://a.example/signed/100/200/video.mp4?token=one",
                "voice": "ru",
                "quality": "1080p",
            }
            b = dict(a, url="https://b.example/other/100/200/video.mp4?token=two")
            self.assertEqual(stream_variant_key(a), stream_variant_key(b))

        filmix_a = {
            "source": "Zona", "source_type_id": 3,
            "url": "https://a.example/s/opaque-a/folder/video.mp4?x=1",
            "voice": "ru", "quality": "1080p",
        }
        filmix_b = dict(filmix_a, url="https://b.example/s/opaque-b/folder/video.mp4?x=2")
        self.assertEqual(stream_variant_key(filmix_a), stream_variant_key(filmix_b))

    def test_unverified_http_source_type_keeps_exact_locator(self):
        a = {
            "source": "Zona", "source_type_id": 49,
            "url": "https://cdn.example/video.mp4?token=one",
            "voice": "ru", "quality": "1080p",
        }
        b = dict(a, url="https://cdn.example/video.mp4?token=two")
        self.assertNotEqual(stream_variant_key(a), stream_variant_key(b))

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

class TrackerEnrichmentTests(unittest.TestCase):
    def test_existing_provider_trackers_do_not_block_fallback_tracker_merge(self):
        from torrent_resolver import enrich_magnet_with_trackers
        base = (
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
            "&dn=release&tr=udp%3A%2F%2Fprovider.example%3A80%2Fannounce"
        )
        enriched = enrich_magnet_with_trackers(
            base,
            [
                "udp://provider.example:80/announce",
                "https://fallback.example/announce",
                "udp://second.example:1337/announce",
            ],
        )
        pairs = urllib.parse.parse_qsl(urllib.parse.urlsplit(enriched).query, keep_blank_values=True)
        trackers = [value for key, value in pairs if key == "tr"]
        self.assertEqual(trackers.count("udp://provider.example:80/announce"), 1)
        self.assertIn("https://fallback.example/announce", trackers)
        self.assertIn("udp://second.example:1337/announce", trackers)
        self.assertEqual(
            next(value for key, value in pairs if key == "xt"),
            "urn:btih:0123456789abcdef0123456789abcdef01234567",
        )

    def test_tracker_enrichment_is_idempotent(self):
        from torrent_resolver import enrich_magnet_with_trackers
        base = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        trackers = ["https://fallback.example/announce", "udp://second.example:1337/announce"]
        once = enrich_magnet_with_trackers(base, trackers)
        twice = enrich_magnet_with_trackers(once, trackers)
        self.assertEqual(once, twice)
