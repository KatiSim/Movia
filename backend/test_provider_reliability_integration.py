import asyncio
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import balancer_integration
import provider_reliability as reliability
import streamer
import torrent_resolver
from stream_validation import sanitize_streams


class ProviderReliabilityIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "provider_health.db"
        self.db_patch = patch.object(reliability, "DB_PATH", self.db)
        self.db_patch.start()

    def tearDown(self):
        self.db_patch.stop()
        self.tmp.cleanup()

    def test_sanitize_preserves_bounded_health_metadata(self):
        streams = sanitize_streams([
            {
                "source": "Collaps",
                "url": "https://media.example.test/master.m3u8",
                "voice": "Дубляж",
                "quality": "720p",
                "discovery_reliability": 4.2,
                "discovery_failure_count": -8,
                "health_score": -1.0,
                "recent_failure_count": -8,
                "startup_latency_ms": 1234,
            }
        ], require_source=True)
        self.assertEqual(len(streams), 1)
        self.assertEqual(streams[0]["discovery_reliability"], 1.0)
        self.assertEqual(streams[0]["health_score"], 0.0)
        self.assertEqual(streams[0]["discovery_failure_count"], 0)
        self.assertEqual(streams[0]["recent_failure_count"], 0)
        self.assertEqual(streams[0]["startup_latency_ms"], 1234)

    def test_discovery_outage_does_not_penalize_concrete_cached_stream(self):
        cached = {
            "source": "Collaps", "provider": "collaps",
            "url": "https://cdn.example.test/master.m3u8",
            "voice": "Дубляж", "quality": "1080p",
            "health_score": 0.95, "startup_latency_ms": 500,
        }
        for offset in range(3):
            reliability.observe("collaps", "PROVIDER_ERROR", now=200.0 + offset)
        self.assertFalse(reliability.should_call("collaps", now=203.0))
        ranked = streamer.rank_playback_streams([cached])
        self.assertEqual(ranked[0]["health_score"], 0.95)
        self.assertNotIn("provider_reliability", ranked[0])

    def test_temporary_coupled_discovery_metadata_is_scrubbed_from_cached_stream(self):
        poisoned = {
            "source": "Collaps", "provider": "collaps",
            "url": "https://cdn.example.test/legacy.m3u8",
            "voice": "Дубляж", "quality": "1080p",
            "health_score": 0.1922375,
            "provider_reliability": 0.1922375,
            "recent_failure_count": 3,
        }
        sanitized = sanitize_streams([poisoned], require_source=True)
        self.assertEqual(sanitized[0]["provider_reliability"], 0.1922375)
        ranked = streamer.rank_playback_streams(sanitized)
        self.assertNotIn("provider_reliability", ranked[0])
        self.assertNotIn("health_score", ranked[0])
        self.assertEqual(ranked[0]["recent_failure_count"], 0)
        self.assertEqual(ranked[0]["url"], poisoned["url"])

    def test_fresh_provider_result_carries_discovery_metadata_without_playback_penalty(self):
        reliability.observe("collaps", "OK", latency_ms=400, now=100.0)
        stream = reliability.annotate_streams([{
            "source": "Collaps", "provider": "collaps",
            "url": "https://cdn.example.test/fresh.m3u8",
            "voice": "Дубляж", "quality": "1080p",
        }], "collaps")[0]
        self.assertIn("discovery_reliability", stream)
        self.assertIn("discovery_failure_count", stream)
        self.assertNotIn("provider_reliability", stream)
        self.assertNotIn("health_score", stream)

    def test_collaps_circuit_skips_fourth_call_after_three_hard_failures(self):
        calls = []

        def broken_collaps(**kwargs):
            calls.append(kwargs.get("title"))
            raise TimeoutError("provider timeout")

        with patch("collaps_provider.resolve_collaps", side_effect=broken_collaps), \
                patch.object(balancer_integration, "query_zona_api", return_value=[]), \
                patch.object(
                    balancer_integration,
                    "get_last_resolution_diagnostics",
                    return_value={"status": "NO_RESULTS", "error_count": 0},
                ):
            for index in range(4):
                balancer_integration.query_open_balancer_stream(
                    title=f"Example {index}",
                    year=2024,
                    allow_torrent_fallback=False,
                )

        self.assertEqual(len(calls), 3)
        state = reliability.snapshot("collaps")
        self.assertTrue(state["disabled"])
        self.assertEqual(state["consecutive_failures"], 3)

    def test_torrent_guard_does_not_invoke_provider_during_cooldown(self):
        for _ in range(3):
            reliability.observe("apibay", "PROVIDER_TIMEOUT")
        invoked = []

        async def factory():
            invoked.append(True)
            return [{
                "source": "Apibay",
                "url": "magnet:?xt=urn:btih:" + "a" * 40,
                "voice": "Дубляж",
                "quality": "720p",
                "seeders": 10,
            }]

        result = asyncio.run(torrent_resolver._guarded_provider_call("apibay", factory))
        self.assertEqual(result, [])
        self.assertEqual(invoked, [])


if __name__ == "__main__":
    unittest.main()
