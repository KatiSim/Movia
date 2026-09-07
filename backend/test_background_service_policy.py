import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class BackgroundServicePolicyTests(unittest.TestCase):
    def test_stream_enricher_is_bounded_unmetered_background_work(self):
        text = (ROOT / 'agent/services/movia-stream-enricher/run').read_text()
        self.assertNotIn('content_filler.py --all', text)
        self.assertIn('MOVIA_ENRICH_WORKERS="${MOVIA_ENRICH_WORKERS:-1}"', text)
        self.assertIn('MOVIA_ENRICH_BATCH_LIMIT="${MOVIA_ENRICH_BATCH_LIMIT:-40}"', text)
        self.assertIn('MOVIA_ENRICH_INTERVAL_SECONDS="${MOVIA_ENRICH_INTERVAL_SECONDS:-21600}"', text)
        self.assertIn('MOVIA_BLOCKED_RECHECK_SECONDS="${MOVIA_BLOCKED_RECHECK_SECONDS:-1800}"', text)
        self.assertIn('MOVIA_BACKGROUND_ALLOW_METERED="${MOVIA_BACKGROUND_ALLOW_METERED:-0}"', text)
        self.assertIn('background_network_budget.py', text)

    def test_metadata_enricher_is_small_single_worker_background_work(self):
        text = (ROOT / 'agent/services/movia-metadata-enricher/run').read_text()
        self.assertIn('MOVIA_METADATA_BATCH_LIMIT="${MOVIA_METADATA_BATCH_LIMIT:-10}"', text)
        self.assertIn('MOVIA_METADATA_WORKERS="${MOVIA_METADATA_WORKERS:-1}"', text)
        self.assertIn('MOVIA_METADATA_INTERVAL_SECONDS="${MOVIA_METADATA_INTERVAL_SECONDS:-43200}"', text)
        self.assertIn('MOVIA_BACKGROUND_ALLOW_METERED="${MOVIA_BACKGROUND_ALLOW_METERED:-0}"', text)
        self.assertIn('background_network_budget.py', text)

    def test_bulk_python_entrypoints_have_fail_closed_guard(self):
        filler = (ROOT / 'backend/content_filler.py').read_text()
        metadata = (ROOT / 'backend/metadata_repair.py').read_text()
        self.assertIn('background_bulk_allowed()', filler)
        self.assertIn('Background enrichment paused before batch', filler)
        self.assertIn('background_bulk_allowed()', metadata)
        self.assertIn('MOVIA_BACKGROUND_BULK', metadata)


if __name__ == '__main__':
    unittest.main()
