#!/usr/bin/env python3
"""Comprehensive hermetic unit tests for Movia torrent-playback pipeline."""
from __future__ import annotations

import io
import json
import os
import shutil
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import MagicMock, Mock, patch

import cache_pruner
import streamer
from streamer import episode_path_matches, parse_single_http_byte_range
from stream_validation import (
    canonical_stream_locator,
    is_valid_magnet,
    sanitize_streams,
    stable_stream_id,
    stream_variant_key,
)


class TorrentPlaybackIdentityTests(unittest.TestCase):
    """Tests for exact catalog identity, title/year/media type and episode constraints."""

    def test_media_type_classification(self):
        self.assertEqual(streamer._requested_catalog_media_type("movies", None, "movie"), "movie")
        self.assertEqual(streamer._requested_catalog_media_type("movies", 1, "movie"), "tv")
        self.assertEqual(streamer._requested_catalog_media_type("series", None, "series"), "tv")
        self.assertEqual(streamer._requested_catalog_media_type("tv", None, None), "tv")
        self.assertEqual(streamer._requested_catalog_media_type("anime", None, None), "tv")

    def test_catalog_identity_exact_matching(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "catalog.db"
            with sqlite3.connect(str(db_path)) as conn:
                conn.execute("""
                    CREATE TABLE movies (
                        id INTEGER PRIMARY KEY,
                        title TEXT,
                        original_title TEXT,
                        year INTEGER,
                        media_type TEXT,
                        category TEXT,
                        streams TEXT
                    )
                """)
                conn.execute(
                    "INSERT INTO movies VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (1, "Интерстеллар", "Interstellar", 2014, "movie", "movies", "[]")
                )
                conn.execute(
                    "INSERT INTO movies VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (2, "Тьма", "Dark", 2017, "tv", "series", "[]")
                )
                conn.commit()

            with patch.object(streamer, "DIR", Path(tmpdir)):
                status, movie = streamer._catalog_identity_for_request(
                    "Интерстеллар", 2014, "movies", None
                )
                self.assertEqual(status, "OK")
                self.assertIsNotNone(movie)
                self.assertEqual(movie["id"], 1)

                # Wrong year -> UNMATCHED
                status_wrong_year, _ = streamer._catalog_identity_for_request(
                    "Интерстеллар", 2020, "movies", None
                )
                self.assertEqual(status_wrong_year, "UNMATCHED")

                # Series with season -> matches TV
                status_tv, tv_match = streamer._catalog_identity_for_request(
                    "Тьма", 2017, "series", 1
                )
                self.assertEqual(status_tv, "OK")
                self.assertEqual(tv_match["id"], 2)


class EpisodePatternMatchingTests(unittest.TestCase):
    """Tests for exact episode pattern extraction and matching in torrent files."""

    def test_standard_s_e_format(self):
        self.assertTrue(episode_path_matches("Dark.S01E02.1080p.mkv", 1, 2))
        self.assertTrue(episode_path_matches("dark.s1e2.mkv", 1, 2))
        self.assertFalse(episode_path_matches("Dark.S01E03.1080p.mkv", 1, 2))
        self.assertFalse(episode_path_matches("Dark.S02E02.1080p.mkv", 1, 2))

    def test_cross_format_and_russian_text(self):
        self.assertTrue(episode_path_matches("Show 1x05 720p.avi", 1, 5))
        self.assertTrue(episode_path_matches("Сериал - Сезон 2 Серия 3.mp4", 2, 3))
        self.assertTrue(episode_path_matches("Series.S03.E07.HD.mkv", 3, 7))
        self.assertFalse(episode_path_matches("Series.S03.E08.HD.mkv", 3, 7))


class ContainerAndMimeTypeTests(unittest.TestCase):
    """Tests for raw container MIME type inference and container head detection."""

    def test_infer_stream_mime(self):
        self.assertEqual(streamer.infer_stream_mime("video.mp4"), "video/mp4")
        self.assertEqual(streamer.infer_stream_mime("video.m4v"), "video/mp4")
        self.assertEqual(streamer.infer_stream_mime("video.mkv"), "video/x-matroska")
        self.assertEqual(streamer.infer_stream_mime("video.avi"), "video/x-msvideo")
        self.assertEqual(streamer.infer_stream_mime("video.ts"), "video/mp2t")
        self.assertEqual(streamer.infer_stream_mime("video.webm"), "video/webm")
        self.assertEqual(streamer.infer_stream_mime("stream.m3u8"), "application/vnd.apple.mpegurl")

    def test_playable_container_head(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            mp4_file = Path(tmpdir) / "test.mp4"
            mp4_file.write_bytes(b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42isom")
            self.assertTrue(streamer.has_playable_container_head(mp4_file))

            mkv_file = Path(tmpdir) / "test.mkv"
            mkv_file.write_bytes(b"\x1a\x45\xdf\xa3\x93\x42\x82\x88matroska")
            self.assertTrue(streamer.has_playable_container_head(mkv_file))

            avi_file = Path(tmpdir) / "test.avi"
            avi_file.write_bytes(b"RIFF\x00\x00\x00\x00AVI LIST\x00\x00\x00\x00")
            self.assertTrue(streamer.has_playable_container_head(avi_file))

            empty_file = Path(tmpdir) / "empty.mkv"
            empty_file.write_bytes(b"")
            self.assertFalse(streamer.has_playable_container_head(empty_file))


class HttpRangeHeaderTests(unittest.TestCase):
    """Tests for HTTP byte range parsing and Range 206 semantics."""

    def test_parse_single_http_byte_range(self):
        total = 10000
        # Closed range
        self.assertEqual(parse_single_http_byte_range("bytes=0-499", total), (0, 499))
        self.assertEqual(parse_single_http_byte_range("bytes=100-200", total), (100, 200))
        # Open end
        self.assertEqual(parse_single_http_byte_range("bytes=5000-", total), (5000, 9999))
        # Suffix range
        self.assertEqual(parse_single_http_byte_range("bytes=-500", total), (9500, 9999))
        # Out of bounds & malformed
        self.assertIsNone(parse_single_http_byte_range("bytes=500-100", total))
        self.assertIsNone(parse_single_http_byte_range("invalid", total))
        self.assertIsNone(parse_single_http_byte_range("bytes=100-200,300-400", total))


class PieceReprioritizationAndPrebufferTests(unittest.TestCase):
    """Tests for aria2 bitfield checks and piece reprioritization."""

    def test_bitfield_piece_is_complete(self):
        # 0x80 = 10000000 in binary -> piece 0 complete, pieces 1-7 incomplete
        self.assertTrue(streamer._aria2_piece_is_complete("80", 0))
        self.assertFalse(streamer._aria2_piece_is_complete("80", 1))
        # 0xC0 = 11000000 in binary -> pieces 0, 1 complete
        self.assertTrue(streamer._aria2_piece_is_complete("c0", 0))
        self.assertTrue(streamer._aria2_piece_is_complete("c0", 1))
        self.assertFalse(streamer._aria2_piece_is_complete("c0", 2))

    def test_prioritize_and_wait_torrent_range_calls_rpc(self):
        rpc_calls = []
        def fake_rpc(method, params, timeout=2.0):
            rpc_calls.append((method, params))
            if method == "aria2.changeOption":
                return "OK"
            if method == "aria2.tellStatus":
                return {
                    "status": "active",
                    "pieceLength": str(1024 * 1024),
                    "numPieces": "10",
                    "bitfield": "ff00",
                    "files": [{"index": "1", "path": "/path/video.mkv", "length": str(10 * 1024 * 1024)}],
                }
            return {}

        with patch.object(streamer, "aria2_rpc", side_effect=fake_rpc):
            ready = streamer._prioritize_and_wait_torrent_range(
                "gid-123", Path("/path/video.mkv"), start=0, length=2 * 1024 * 1024, timeout_sec=0.5
            )
            self.assertTrue(ready)
            methods = [c[0] for c in rpc_calls]
            self.assertIn("aria2.tellStatus", methods)


class TorrentGidDeduplicationTests(unittest.TestCase):
    """Tests for single GID / session per infoHash deduplication and lifecycle."""

    def setUp(self):
        streamer._TORRENT_GIDS.clear()
        streamer._TORRENT_OWNED_GIDS.clear()

    def tearDown(self):
        streamer._TORRENT_GIDS.clear()
        streamer._TORRENT_OWNED_GIDS.clear()

    def test_single_session_per_info_hash(self):
        info_hash = "abcdef0123456789" * 2 + "12345678"
        magnet = f"magnet:?xt=urn:btih:{info_hash}&dn=Test"
        task_dir = Path("/tmp/torrent_test")

        active_tasks = [{
            "gid": "task-gid-1",
            "status": "active",
            "completedLength": "1000",
            "infoHash": info_hash,
        }]

        def fake_rpc(method, params, timeout=1.5):
            if method == "aria2.tellActive":
                return active_tasks
            if method == "aria2.tellWaiting":
                return []
            if method == "aria2.getFiles":
                return [{"index": "1", "path": "/tmp/video.mkv", "length": "5000"}]
            if method == "aria2.tellStatus":
                return active_tasks[0]
            raise AssertionError(f"unexpected rpc: {method}")

        with patch.object(streamer, "aria2_rpc", side_effect=fake_rpc):
            gid1 = streamer.get_or_create_torrent_gid(info_hash, magnet, task_dir)
            gid2 = streamer.get_or_create_torrent_gid(info_hash, magnet, task_dir)
            self.assertEqual(gid1, "task-gid-1")
            self.assertEqual(gid2, "task-gid-1")
            self.assertEqual(streamer._TORRENT_GIDS[info_hash], "task-gid-1")


class PlaybackRankingAndFallbackTests(unittest.TestCase):
    """Tests for primary torrent path ranking with direct HTTP fallback."""

    def test_torrent_is_primary_playback_path_when_seeded(self):
        streams = [
            {
                "stream_id": "direct:1",
                "source": "Direct CDN",
                "url": "https://cdn.example.test/stream.m3u8",
                "voice": "Дубляж",
                "quality": "1080p",
                "seeders": 0,
            },
            {
                "stream_id": "torrent:1",
                "source": "Rutor",
                "url": "magnet:?xt=urn:btih:" + "1" * 40,
                "voice": "Дубляж",
                "quality": "1080p",
                "seeders": 50,
            },
        ]
        ranked = streamer.rank_playback_streams(streams)
        self.assertEqual(ranked[0]["stream_id"], "torrent:1")
        self.assertEqual(ranked[1]["stream_id"], "direct:1")

    def test_fallback_to_direct_when_torrent_unseeded(self):
        streams = [
            {
                "stream_id": "torrent:zero-seed",
                "source": "Rutor",
                "url": "magnet:?xt=urn:btih:" + "2" * 40,
                "voice": "Дубляж",
                "quality": "1080p",
                "seeders": 0,
            },
            {
                "stream_id": "direct:1",
                "source": "Direct CDN",
                "url": "https://cdn.example.test/stream.m3u8",
                "voice": "Дубляж",
                "quality": "1080p",
                "seeders": 0,
            },
        ]
        ranked = streamer.rank_playback_streams(streams)
        self.assertEqual(ranked[0]["stream_id"], "direct:1")
        self.assertEqual(ranked[1]["stream_id"], "torrent:zero-seed")


class CacheQuotaAndLRUPrunerTests(unittest.TestCase):
    """Tests for cache pruner LRU eviction and quota budgeting."""

    def test_lru_prunes_oldest_when_quota_exceeded(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            cache_dir = Path(tmpdir) / "torrent_cache"
            cache_dir.mkdir(parents=True)

            # Create 3 task directories
            task1 = cache_dir / "hash1"
            task2 = cache_dir / "hash2"
            task3 = cache_dir / "hash3"
            for t in (task1, task2, task3):
                t.mkdir()
                (t / "video.mkv").write_bytes(b"x" * (1024 * 1024)) # 1 MB each

            # Set distinct mtimes: task1 oldest, task3 newest
            now = time.time()
            os.utime(str(task1), (now - 3000, now - 3000))
            os.utime(str(task2), (now - 2000, now - 2000))
            os.utime(str(task3), (now - 1000, now - 1000))

            # Patch cache_pruner constants: max cache 1.5 MB (budget for 1 file only)
            with patch.object(cache_pruner, "CACHE_DIR", cache_dir), \
                    patch.object(cache_pruner, "BASE_DIR", Path(tmpdir)), \
                    patch.object(cache_pruner, "MAX_CACHE_BYTES", 1500 * 1024), \
                    patch.object(cache_pruner, "MAX_ENTRY_AGE_SECONDS", 86400), \
                    patch.object(cache_pruner, "_aria2_protected_entries", return_value=set()), \
                    patch.object(cache_pruner, "_open_file_protected_entries", return_value=set()):
                cache_pruner.main()

class DiagnosticsAndErrorTaxonomyTests(unittest.TestCase):
    """Tests for /diagnostics endpoint payload and error responses."""

    def test_diagnostics_payload_structure(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            cache_dir = Path(tmpdir) / "torrent_cache"
            cache_dir.mkdir(parents=True)
            (cache_dir / "item1").mkdir()
            (cache_dir / "item1" / "file.mkv").write_bytes(b"12345")

            with patch.object(streamer, "DIR", Path(tmpdir)), \
                    patch.object(streamer, "_TORRENT_GIDS", {"hash1": "gid1"}), \
                    patch.object(streamer, "_TORRENT_OWNED_GIDS", {"gid1"}), \
                    patch.object(streamer, "_current_catalog_revision", return_value=42):
                
                # Mock a request handler
                handler = streamer.StreamRequestHandler.__new__(streamer.StreamRequestHandler)
                handler.path = "/diagnostics"
                handler.headers = {}
                handler.wfile = io.BytesIO()
                handler.send_response = Mock()
                handler.send_header = Mock()
                handler.end_headers = Mock()

                handler.handle_request(send_body=True)

                handler.send_response.assert_called_with(200)
                body = json.loads(handler.wfile.getvalue().decode("utf-8"))
                self.assertEqual(body["status"], "ok")
                self.assertEqual(body["service"], "movia-p2p-streamer-on-demand")
                self.assertEqual(body["catalog_revision"], 42)
                self.assertEqual(body["active_torrent_sessions"], 1)
                self.assertEqual(body["torrent_sessions"], {"hash1": "gid1"})
                self.assertIn("torrent_cache", body)
                self.assertEqual(body["torrent_cache"]["entries_count"], 1)
                self.assertEqual(body["torrent_cache"]["total_bytes"], 5)

    def test_invalid_magnet_returns_400(self):
        handler = streamer.StreamRequestHandler.__new__(streamer.StreamRequestHandler)
        handler.path = "/stream?magnet=magnet:?xt=urn:btih:invalid_hash"
        handler.headers = {}
        handler.wfile = io.BytesIO()
        handler.send_response = Mock()
        handler.send_header = Mock()
        handler.end_headers = Mock()

        handler.handle_request(send_body=True)
        handler.send_response.assert_called_with(400)
        body = json.loads(handler.wfile.getvalue().decode("utf-8"))
        self.assertEqual(body.get("error"), "invalid_magnet_btih")

    def test_missing_title_returns_400_on_resolve(self):
        handler = streamer.StreamRequestHandler.__new__(streamer.StreamRequestHandler)
        handler.path = "/resolve"
        handler.headers = {}
        handler.wfile = io.BytesIO()
        handler.send_response = Mock()
        handler.send_header = Mock()
        handler.end_headers = Mock()

        handler.handle_request(send_body=True)
        handler.send_response.assert_called_with(400)


if __name__ == "__main__":
    unittest.main()
