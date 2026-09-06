import json
import threading
import tempfile
import sqlite3
import time
from pathlib import Path
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
            {("LostFilm", "1080p"), ("Original (с субтитрами)", "1080p"), ("LostFilm", "720p")},
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
        self.assertEqual({item["voice"] for item in streams}, {"LostFilm", "Original (с субтитрами)"})


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
        self.assertNotEqual(first_ids["LostFilm"], first_ids["Original (с субтитрами)"])

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
        self.assertEqual(
            {item["url"] for item in result},
            {
                "magnet:?xt=urn:btih:" + "a" * 40,
                "https://media.example.test/title.m3u8",
            },
        )
        # Provider fanout concurrency is the invariant under test. Ordering is
        # delegated to health/reliability ranking; transport is not privileged.
        self.assertEqual(len(result), 2)

    def test_stream_cache_key_is_stable_across_global_catalog_revision(self):
        key = self.streamer._stream_cache_key(
            "375", "леон", 1994, "movies", None, None
        )
        self.assertEqual(key, "v6_375_леон_1994_movies_sNone_eNone")
        self.assertNotIn("_r", key)

    def test_recent_stale_direct_survives_old_revision_and_rejects_expired_signed_url(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            db = Path(tmpdir) / "streams_cache.db"
            suffix = self.streamer._stream_cache_suffix(
                "375", "леон", 1994, "movies", None, None
            )
            now = int(time.time())
            with patch.object(self.streamer, "CACHE_DB_PATH", db):
                self.streamer.init_cache_db()
                con = sqlite3.connect(str(db))
                con.execute(
                    "INSERT INTO streams_cache(cache_key,streams_json,expires_at,updated_at) VALUES(?,?,?,?)",
                    (
                        "v5_r8234" + suffix,
                        json.dumps([{
                            "source": "Collaps",
                            "url": "https://cdn.example.test/master.m3u8",
                            "voice": "Дубляж",
                            "quality": "1080p",
                        }]),
                        now - 100,
                        now - 1200,
                    ),
                )
                con.execute(
                    "INSERT INTO streams_cache(cache_key,streams_json,expires_at,updated_at) VALUES(?,?,?,?)",
                    (
                        "v5_r8235" + suffix,
                        json.dumps([{
                            "source": "Collaps",
                            "url": f"https://cdn.example.test/master.m3u8?t={now - 1}",
                            "voice": "Original",
                            "quality": "1080p",
                        }]),
                        now - 1,
                        now - 60,
                    ),
                )
                con.commit(); con.close()
                stale = self.streamer.get_recent_stale_direct_streams(suffix)
            self.assertEqual(len(stale), 1)
            self.assertEqual(stale[0]["voice"], "Дубляж")

    def test_stale_direct_fast_path_does_not_wait_for_providers(self):
        stale_direct = [{
            "source": "Collaps",
            "url": "https://media.example.test/last-good.m3u8",
            "voice": "Дубляж",
            "quality": "1080p",
        }]
        fake_thread = unittest.mock.MagicMock()
        with patch.object(self.streamer, "get_cached_streams", return_value=None), \
                patch.object(self.streamer, "get_recent_stale_direct_streams", return_value=stale_direct), \
                patch.object(self.streamer, "_resolve_balancer_provider") as balancer, \
                patch.object(self.streamer, "_resolve_torrent_provider") as torrent, \
                patch.object(self.streamer.threading, "Thread", return_value=fake_thread) as thread_ctor:
            with self.streamer._STREAM_REVALIDATION_LOCK:
                self.streamer._STREAM_REVALIDATION_INFLIGHT.clear()
            result = self.streamer.resolve_on_demand_streams(
                "Example", year=2024, category="movies", force_refresh=True
            )
        self.assertEqual(result[0]["url"], "https://media.example.test/last-good.m3u8")
        balancer.assert_not_called()
        torrent.assert_not_called()
        self.assertTrue(any(
            call.kwargs.get("name", "").startswith("movia-stream-revalidate-")
            for call in thread_ctor.call_args_list
        ))
        with self.streamer._STREAM_REVALIDATION_LOCK:
            self.streamer._STREAM_REVALIDATION_INFLIGHT.clear()

    def test_provider_outage_uses_recent_direct_metadata_as_fallback(self):
        stale_direct = [{
            "source": "Collaps",
            "url": "https://media.example.test/stale-but-recent.m3u8",
            "voice": "Дубляж",
            "quality": "1080p",
        }]
        torrent = [{
            "source": "Rutor",
            "url": "magnet:?xt=urn:btih:" + "b" * 40,
            "voice": "Не указано",
            "quality": "1080p",
            "seeders": 100,
        }]
        with patch.object(self.streamer, "get_cached_streams", return_value=None), \
                patch.object(self.streamer, "get_recent_stale_direct_streams", return_value=stale_direct), \
                patch.object(self.streamer, "set_cached_streams"), \
                patch.object(self.streamer, "_resolve_balancer_provider", return_value=[]), \
                patch.object(self.streamer, "_resolve_torrent_provider", return_value=torrent):
            result = self.streamer.resolve_on_demand_streams(
                "Example", year=2024, category="movies", force_refresh=True,
                _allow_stale_fast_path=False,
            )
        urls = {item["url"] for item in result}
        self.assertIn("https://media.example.test/stale-but-recent.m3u8", urls)
        self.assertIn("magnet:?xt=urn:btih:" + "b" * 40, urls)

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

    def test_direct_refresh_uses_stable_stream_id_across_provider_metadata_enrichment(self):
        import sqlite3
        import tempfile
        from pathlib import Path
        import database
        old_stream = {
            "stream_id": "collaps_tt123_Dub",
            "source": "Collaps",
            "provider": "collaps",
            "url": "https://media.example.test/old.m3u8",
            "voice": "Дубляж",
            "quality": "1080p",
        }
        fresh_stream = {
            "stream_id": "collaps_tt123_Dub",
            "source": "Collaps",
            "provider": "collaps",
            "source_type_id": 9,
            "audio_track_index": 0,
            "url": "https://media.example.test/fresh.m3u8",
            "voice": "Дубляж",
            "quality": "1080p",
        }

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
                        1, json.dumps([old_stream]), "Generic title",
                        "Generic title", 2020, "movie", "movies",
                        old_stream["url"], "Дубляж", "1080p", 0, 1,
                        "2026-08-30 10:00:00",
                    ),
                )
                conn.commit()

            with patch.object(database, "DB_PATH", db_path):
                saved = database.save_content({
                    "id": 1,
                    "streams": [fresh_stream],
                    "link_verified": 1,
                    "replace_direct_variants": True,
                })
                with database.get_db() as conn:
                    raw = conn.execute("SELECT streams FROM movies WHERE id=1").fetchone()[0]

        self.assertTrue(saved)
        persisted = json.loads(raw)
        self.assertEqual(1, len(persisted))
        self.assertEqual("https://media.example.test/fresh.m3u8", persisted[0]["url"])
        self.assertEqual(9, persisted[0]["source_type_id"])
        self.assertEqual(0, persisted[0]["audio_track_index"])

    def test_catalog_refresh_uses_timestamp_without_media_probe(self):
        import streamer
        direct = [{"source": "Zona", "url": "https://media.example.test/title.m3u8"}]
        self.assertFalse(streamer.catalog_streams_need_refresh({"link_updated_at": 1000}, direct, now=1000 + streamer.DIRECT_STREAM_REFRESH_SECONDS - 1))
        self.assertTrue(streamer.catalog_streams_need_refresh({"link_updated_at": 1000}, direct, now=1000 + streamer.DIRECT_STREAM_REFRESH_SECONDS))
        signed_direct = [{"source": "Collaps", "url": "https://cdn.example/master.m3u8?t=2000000500"}]
        self.assertFalse(streamer.catalog_streams_need_refresh({"link_updated_at": 1000}, signed_direct, now=2_000_000_000))
        expired_direct = [{"source": "Collaps", "url": "https://cdn.example/master.m3u8?t=1999999999"}]
        self.assertTrue(streamer.catalog_streams_need_refresh({"link_updated_at": 1000}, expired_direct, now=2_000_000_000))
        self.assertFalse(streamer.catalog_streams_need_refresh({}, [{"source": "Rutor", "url": "magnet:?xt=urn:btih:" + "a" * 40}], now=10_000_000))

    def test_persisted_torrent_candidate_forces_bounded_online_refresh(self):
        import streamer
        magnet = [{
            "stream_id": "torrent-ready",
            "transport": "torrent",
            "url": "magnet:?xt=urn:btih:" + "a" * 40,
        }]
        self.assertTrue(streamer.catalog_streams_need_provider_resolve(
            magnet,
            refresh_requested=False,
            persisted_needs_refresh=False,
            persisted_needs_variant_resolve=False,
            persisted_out_of_scope=False,
        ))
        self.assertTrue(streamer.catalog_streams_force_live_refresh(
            magnet, refresh_requested=False
        ))
        direct = [{
            "stream_id": "direct-ready",
            "transport": "hls",
            "url": "https://cdn.example.test/master.m3u8",
        }]
        self.assertFalse(streamer.catalog_streams_need_provider_resolve(
            direct,
            refresh_requested=False,
            persisted_needs_refresh=False,
            persisted_needs_variant_resolve=False,
            persisted_out_of_scope=False,
        ))
        self.assertFalse(streamer.catalog_streams_force_live_refresh(
            direct, refresh_requested=False
        ))
        self.assertTrue(streamer.catalog_streams_need_provider_resolve(
            [],
            refresh_requested=False,
            persisted_needs_refresh=False,
            persisted_needs_variant_resolve=False,
            persisted_out_of_scope=False,
        ))
        self.assertTrue(streamer.catalog_streams_need_provider_resolve(
            magnet,
            refresh_requested=True,
            persisted_needs_refresh=False,
            persisted_needs_variant_resolve=False,
            persisted_out_of_scope=False,
        ))

if __name__ == "__main__":
    unittest.main()
