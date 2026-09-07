import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import provider_reliability as reliability


class ProviderReliabilityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "provider_health.db"
        self.db_patch = patch.object(reliability, "DB_PATH", self.db)
        self.db_patch.start()

    def tearDown(self):
        self.db_patch.stop()
        self.tmp.cleanup()

    def test_three_hard_failures_open_bounded_circuit_and_recover(self):
        now = 1_000.0
        self.assertTrue(reliability.should_call("Collaps", now=now))
        first = reliability.observe("Collaps", "PROVIDER_TIMEOUT", now=now)
        second = reliability.observe("Collaps", "NETWORK_ERROR", now=now + 1)
        third = reliability.observe("Collaps", "PROVIDER_ERROR", now=now + 2)
        self.assertFalse(first["disabled"])
        self.assertFalse(second["disabled"])
        self.assertTrue(third["disabled"])
        self.assertFalse(reliability.should_call("collaps", now=now + 30))
        self.assertTrue(reliability.should_call("collaps", now=now + 63))

        recovered = reliability.observe("collaps", "OK", latency_ms=800, now=now + 63)
        self.assertFalse(recovered["disabled"])
        self.assertEqual(recovered["consecutive_failures"], 0)
        self.assertGreater(recovered["reliability"], third["reliability"])

    def test_no_results_never_opens_circuit_and_resets_hard_failure_streak(self):
        now = 2_000.0
        first = reliability.observe("Rutor", "PROVIDER_TIMEOUT", now=now)
        self.assertEqual(first["consecutive_failures"], 1)
        for offset in range(1, 25):
            state = reliability.observe("Rutor", "NO_RESULTS", now=now + offset)
            self.assertFalse(state["disabled"])
            self.assertEqual(state["consecutive_failures"], 0)
        self.assertEqual(state["hard_failures"], 1)
        self.assertEqual(state["soft_misses"], 24)

    def test_fast_success_improves_reliability_and_latency_uses_ewma(self):
        slow = reliability.observe("YTS", "OK", latency_ms=5_500, now=3_000)
        fast = reliability.observe("YTS", "OK", latency_ms=400, now=3_001)
        self.assertGreater(fast["reliability"], slow["reliability"])
        self.assertGreater(fast["ewma_latency_ms"], 400)
        self.assertLess(fast["ewma_latency_ms"], 5_500)

    def test_annotation_contains_only_discovery_metadata(self):
        reliability.observe("Zona API", "PROVIDER_TIMEOUT", now=4_000)
        out = reliability.annotate_streams([
            {
                "source": "Zona API",
                "url": "https://media.example.test/master.m3u8",
                "voice": "LostFilm",
                "quality": "720p",
            }
        ])
        self.assertEqual(len(out), 1)
        self.assertIn("discovery_reliability", out[0])
        self.assertNotIn("health_score", out[0])
        self.assertEqual(out[0]["discovery_failure_count"], 1)
        self.assertNotIn("disabled_until", out[0])
        self.assertNotIn("last_status", out[0])


if __name__ == "__main__":
    unittest.main()
