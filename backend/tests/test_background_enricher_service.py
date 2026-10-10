import unittest
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / 'runtime' / 'background_enricher_service.sh'

class BackgroundEnricherServiceTests(unittest.TestCase):
    def test_media_type_audit_precedes_stream_enrichment_on_wifi(self):
        text = SCRIPT.read_text()
        audit = 'metadata_repair.py --audit-media-type'
        filler = 'content_filler.py --all --resume'
        self.assertIn(audit, text)
        self.assertIn(filler, text)
        self.assertLess(text.index(audit), text.index(filler))
        self.assertIn('MOVIA_WIFI_METADATA_AUDIT_LIMIT', text)

    def test_mobile_audit_is_bounded_and_resumable(self):
        text = SCRIPT.read_text()
        self.assertIn('MOVIA_MOBILE_METADATA_AUDIT_LIMIT', text)
        self.assertGreaterEqual(text.count('--audit-media-type'), 2)
        self.assertGreaterEqual(text.count('--resume'), 4)

if __name__ == '__main__': unittest.main()
