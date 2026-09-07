import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class BackgroundServicePolicyTests(unittest.TestCase):
    def test_stream_enricher_is_bounded_unmetered_background_work(self):
        text = (ROOT / 'agent/services/movia-stream-enricher/run').read_text()
        self.assertIn('content_filler.py --all --resume', text)
        self.assertIn('MOVIA_WIFI_ENRICH_WORKERS="${MOVIA_WIFI_ENRICH_WORKERS:-6}"', text)
        self.assertIn('MOVIA_MOBILE_ENRICH_WORKERS="${MOVIA_MOBILE_ENRICH_WORKERS:-1}"', text)
        self.assertIn('MOVIA_MOBILE_ENRICH_LIMIT="${MOVIA_MOBILE_ENRICH_LIMIT:-20}"', text)
        self.assertIn('MOVIA_BACKGROUND_MONTHLY_GIB="${MOVIA_BACKGROUND_MONTHLY_GIB:-4.2}"', text)
        self.assertIn('export MOVIA_BACKGROUND_ALLOW_METERED=0', text)
        self.assertIn('export MOVIA_BACKGROUND_ALLOW_METERED=1', text)
        self.assertIn('background_network_budget.py', text)

    def test_metadata_enricher_is_small_single_worker_background_work(self):
        text = (ROOT / 'agent/services/movia-metadata-enricher/run').read_text()
        self.assertIn('MOVIA_WIFI_METADATA_LIMIT="${MOVIA_WIFI_METADATA_LIMIT:-200}"', text)
        self.assertIn('MOVIA_WIFI_METADATA_WORKERS="${MOVIA_WIFI_METADATA_WORKERS:-4}"', text)
        self.assertIn('MOVIA_MOBILE_METADATA_LIMIT="${MOVIA_MOBILE_METADATA_LIMIT:-10}"', text)
        self.assertIn('MOVIA_MOBILE_METADATA_WORKERS="${MOVIA_MOBILE_METADATA_WORKERS:-1}"', text)
        self.assertIn('MOVIA_BACKGROUND_MONTHLY_GIB="${MOVIA_BACKGROUND_MONTHLY_GIB:-4.2}"', text)
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
