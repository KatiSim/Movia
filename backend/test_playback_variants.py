import json
import threading
import time
import unittest
from unittest.mock import patch

from stream_validation import sanitize_streams


class VariantSanitizationTests(unittest.TestCase):
    def test_same_http_locator_keeps_distinct_voice_and_quality(self):
        url = "https://media.example.test/title/master.m3u8"
        streams = sanitize_streams([
            {"source": "provider", "url": url, "voice": "LostFilm", "quality": "1080p"},
            {"source": "provider", "url": url, "voice": "Original", "quality": "1080p"},
            {"source": "provider", "url": url, "voice": "LostFilm", "quality": "720p"},
            {"source": "provider", "url": url, "voice": "LostFilm", "quality": "1080p"},
        ])

        self.assertEqual(len(streams), 3)
        self.assertEqual(
            {(item["voice"], item["quality"]) for item in streams},
            {("LostFilm", "1080p"), ("Original", "1080p"), ("LostFilm", "720p")},
        )

    def test_magnet_tracker_churn_does_not_duplicate_same_variant(self):
        info_hash = "0123456789abcdef" * 2 + "01234567"
        streams = sanitize_streams([
            {
                "source": "Rutor",
                "url": f"magnet:?xt=urn:btih:{info_hash}&dn=release&tr=udp%3A%2F%2Fone",
                "voice": "LostFilm",
                "quality": "1080p",
            },
            {
                "source": "Apibay",
                "url": f"magnet:?xt=urn:btih:{info_hash}&dn=other&tr=udp%3A%2F%2Ftwo",
                "voice": "LostFilm",
                "quality": "1080p",
            },
            {
                "source": "Apibay",
                "url": f"magnet:?xt=urn:btih:{info_hash}&dn=other&tr=udp%3A%2F%2Ftwo",
                "voice": "Original",
                "quality": "1080p",
            },
        ])

        self.assertEqual(len(streams), 2)
        self.assertEqual({item["voice"] for item in streams}, {"LostFilm", "Original"})


class ResolverIdentityAndConcurrencyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import streamer
        cls.streamer = streamer

    def test_stream_id_is_tracker_stable_and_voice_distinct(self):
        info_hash = "fedcba9876543210" * 2 + "fedcba98"
        first = sanitize_streams([
            {
                "source": "Rutor",
                "url": f"magnet:?xt=urn:btih:{info_hash}&tr=udp%3A%2F%2Fone",
                "voice": "LostFilm",
                "quality": "1080p",
            },
            {
                "source": "Rutor",
                "url": f"magnet:?xt=urn:btih:{info_hash}&tr=udp%3A%2F%2Fone",
                "voice": "Original",
                "quality": "1080p",
            },
        ])
        second = sanitize_streams([
            {
                "source": "Rutor",
                "url": f"magnet:?xt=urn:btih:{info_hash}&tr=udp%3A%2F%2Ftwo",
                "voice": "LostFilm",
                "quality": "1080p",
            },
            {
                "source": "Rutor",
                "url": f"magnet:?xt=urn:btih:{info_hash}&tr=udp%3A%2F%2Ftwo",
                "voice": "Original",
                "quality": "1080p",
            },
        ])

        first_ids = {
            item["voice"]: item["stream_id"]
            for item in self.streamer.enrich_stream_identity(first, 1, 1)
        }
        second_ids = {
            item["voice"]: item["stream_id"]
            for item in self.streamer.enrich_stream_identity(second, 1, 1)
        }
        self.assertEqual(first_ids, second_ids)
        self.assertNotEqual(first_ids["LostFilm"], first_ids["Original"])

    def test_independent_provider_branches_start_together(self):
        started = []
        both_started = threading.Event()

        def fake_torrent(*args, **kwargs):
            started.append("torrent")
            if len(started) == 2:
                both_started.set()
            self.assertTrue(both_started.wait(1.0))
            time.sleep(0.04)
            return [{
                "source": "Rutor",
                "voice": "LostFilm",
                "quality": "1080p",
                "seeders": 20,
                "url": "magnet:?xt=urn:btih:" + "a" * 40,
            }]

        def fake_balancer(*args, **kwargs):
            started.append("balancer")
            if len(started) == 2:
                both_started.set()
            self.assertTrue(both_started.wait(1.0))
            time.sleep(0.04)
            return [{
                "source": "Zona API",
                "voice": "HDRezka",
                "quality": "1080p",
                "seeders": 100,
                "url": "https://media.example.test/title.m3u8",
            }]

        with patch.object(self.streamer, "get_cached_streams", return_value=None), \
                patch.object(self.streamer, "set_cached_streams"), \
                patch.object(self.streamer, "_resolve_torrent_provider", side_effect=fake_torrent), \
                patch.object(self.streamer, "_resolve_balancer_provider", side_effect=fake_balancer):
            result = self.streamer.resolve_on_demand_streams(
                "Example", year=2024, category="movies", tmdb_id=1, force_refresh=True
            )

        self.assertEqual(set(started), {"torrent", "balancer"})
        self.assertEqual(result[0]["url"], "magnet:?xt=urn:btih:" + "a" * 40)
        self.assertEqual(result[1]["url"], "https://media.example.test/title.m3u8")
        self.assertEqual(len(result), 2)

    def test_zona_contract_merges_same_locator_variants(self):
        import balancer_integration
        from zona_contract import ZonaLookup

        direct_variants = [
            {
                "source": "Zona",
                "provider": "extractor-a",
                "url": "https://media.example.test/shared.m3u8",
                "voice": "LostFilm",
                "quality": "1080p",
            },
            {
                "source": "Zona",
                "provider": "extractor-b",
                "url": "https://media.example.test/shared.m3u8",
                "voice": "Original",
                "quality": "720p",
            },
        ]
        with patch.object(
            balancer_integration,
            "resolve_zona_for_title",
            return_value=ZonaLookup("OK", direct_variants, suggestions=1, source_refs=2),
        ):
            streams = balancer_integration.query_zona_api(
                "Example",
                year=2024,
                allow_torrent_fallback=False,
                allow_zona_content_lookup=True,
            )

        self.assertEqual(len(streams), 2)
        self.assertEqual(
            {(item["voice"], item["quality"]) for item in streams},
            {("LostFilm", "1080p"), ("Original (с субтитрами)", "720p")},
        )

    def test_read_only_audit_reports_transport_and_first_candidate(self):
        import playback_variant_audit as audit

        payload = {
            "streams": [
                {
                    "stream_id": "stream:direct",
                    "source": "Zona API",
                    "voice": "LostFilm",
                    "quality": "1080p",
                    "url": "https://media.example.test/title.m3u8",
                },
                {
                    "stream_id": "stream:p2p",
                    "source": "Rutor",
                    "voice": "Original",
                    "quality": "4K",
                    "url": "magnet:?xt=urn:btih:" + "b" * 40,
                },
            ]
        }
        with patch.object(audit, "request_resolution", return_value=(4.0, payload, None, 200)):
            result = audit.audit_title(
                "http://backend", "/resolve", "Example", 2024, "movies",
                None, None, 1, 1.0, False,
            )

        self.assertEqual(result["total_streams"], 2)
        self.assertEqual(result["direct_count"], 1)
        self.assertEqual(result["p2p_count"], 1)
        self.assertEqual(result["first_playable_candidate"]["stream_id"], "stream:direct")



    def test_refresh_replaces_same_direct_variant_only(self):
        import sqlite3
        import tempfile
        from pathlib import Path
        import database

        old_magnet = "magnet:?xt=urn:btih:" + "c" * 40 + "&dn=Generic%20title%20%282020%29"
        old_streams = [
            {
                "source": "Zona",
                "source_type_id": 7,
                "url": "https://media.example.test/old-1080.m3u8",
                "voice": "Дубляж",
                "quality": "1080p",
            },
            {
                "source": "Zona",
                "source_type_id": 7,
                "url": "https://media.example.test/old-720.m3u8",
                "voice": "Дубляж",
                "quality": "720p",
            },
            {
                "source": "Rutor",
                "title": "Generic title",
                "url": old_magnet,
                "voice": "Original",
                "quality": "1080p",
            },
        ]
        incoming = [
            {
                "source": "Zona",
                "source_type_id": 7,
                "url": "https://media.example.test/new-1080.m3u8",
                "voice": "Дубляж",
                "quality": "1080p",
            },
        ]

        with tempfile.TemporaryDirectory() as temp:
            db_path = Path(temp) / "catalog.db"
            with sqlite3.connect(db_path) as conn:
                conn.execute("""
                    CREATE TABLE movies (
                        id INTEGER PRIMARY KEY, streams TEXT, title TEXT,
                        original_title TEXT, year INTEGER, media_type TEXT,
                        category TEXT, playback_url TEXT, voice TEXT,
                        quality TEXT, seeders INTEGER, link_verified INTEGER,
                        link_updated_at TEXT
                    )
                """)
                conn.execute(
                    "INSERT INTO movies VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        1, json.dumps(old_streams), "Generic title",
                        "Generic title", 2020, "movie", "movies",
                        old_magnet, "Дубляж", "1080p", 0, 1,
                        "2026-08-30 10:00:00",
                    ),
                )
                conn.commit()

            with patch.object(database, "DB_PATH", db_path):
                saved = database.save_content({
                    "id": 1,
                    "streams": incoming,
                    "link_verified": 1,
                    "replace_direct_variants": True,
                })
                with database.get_db() as conn:
                    raw = conn.execute("SELECT streams FROM movies WHERE id=1").fetchone()[0]

        self.assertTrue(saved)
        urls = {item["url"] for item in json.loads(raw)}
        self.assertIn("https://media.example.test/new-1080.m3u8", urls)
        self.assertIn("https://media.example.test/old-720.m3u8", urls)
        self.assertIn(old_magnet, urls)
        self.assertNotIn("https://media.example.test/old-1080.m3u8", urls)

    def test_catalog_refresh_uses_timestamp_without_media_probe(self):
        import streamer
        direct = [{"source": "Zona", "url": "https://media.example.test/title.m3u8"}]
        self.assertFalse(streamer.catalog_streams_need_refresh({"link_updated_at": 1000}, direct, now=1000 + streamer.DIRECT_STREAM_REFRESH_SECONDS - 1))
        self.assertTrue(streamer.catalog_streams_need_refresh({"link_updated_at": 1000}, direct, now=1000 + streamer.DIRECT_STREAM_REFRESH_SECONDS))
        self.assertFalse(streamer.catalog_streams_need_refresh({}, [{"source": "Rutor", "url": "magnet:?xt=urn:btih:" + "a" * 40}], now=10_000_000))

if __name__ == "__main__":
    unittest.main()
