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
        self.assertEqual(result[0]["url"], "https://media.example.test/title.m3u8")
        self.assertEqual(len(result), 2)

    def test_zona_mirrors_merge_same_locator_variants(self):
        import balancer_integration

        class FakeResponse:
            status = 200

            def __init__(self, payload):
                self.payload = payload

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self):
                return json.dumps(self.payload).encode("utf-8")

        def fake_urlopen(request, timeout):
            if "mirror-one" in request.full_url:
                voice, quality = "LostFilm", "1080p"
            else:
                voice, quality = "Original", "720p"
            return FakeResponse([{
                "id": voice,
                "url": "https://media.example.test/shared.m3u8",
                "voice": voice,
                "quality": quality,
                "seeders": 10,
            }])

        with patch.object(
            balancer_integration,
            "load_zona_mirrors_config",
            return_value=(
                ["https://mirror-one.test", "https://mirror-two.test"],
                1.0,
                {},
            ),
        ), patch.object(balancer_integration.urllib.request, "urlopen", side_effect=fake_urlopen):
            streams = balancer_integration.query_zona_api(
                "Example", year=2024, allow_torrent_fallback=False
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


if __name__ == "__main__":
    unittest.main()
