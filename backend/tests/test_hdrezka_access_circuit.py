"""Search endpoint access denial is a provider condition, not a movie miss."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import provider_discovery
import provider_reliability
from hdrezka_provider_adapter import HDRezkaProviderAdapter


class HDRezkaAccessCircuitTests(unittest.TestCase):
    def test_access_denied_trips_shared_circuit_and_stops_search_requests(self):
        with tempfile.TemporaryDirectory() as temp, \
             patch.object(provider_reliability, 'DB_PATH', Path(temp) / 'health.db'), \
             patch.object(HDRezkaProviderAdapter, 'search', return_value=([], 'HTTP_ERROR:403')) as search:
            responses=[provider_discovery._discover_hdrezka(title='Example', year=2024,
                media_id=str(i), media_type='movie') for i in range(4)]
            self.assertEqual(['ACCESS_DENIED'] * 3 + ['PROVIDER_COOLDOWN'],
                             [result.status for result in responses])
            self.assertEqual([1,1,1,0], [result.error_count for result in responses])
            self.assertEqual(3, search.call_count)
            state=provider_reliability.snapshot('hdrezka')
            self.assertEqual(3, state['hard_failures'])
            self.assertTrue(state['disabled'])
            self.assertEqual('ACCESS_DENIED',state['last_status'])

    def test_normal_missing_match_is_not_a_hard_failure(self):
        with tempfile.TemporaryDirectory() as temp, \
             patch.object(provider_reliability, 'DB_PATH', Path(temp) / 'health.db'), \
             patch.object(HDRezkaProviderAdapter, 'search', return_value=([], None)):
            result=provider_discovery._discover_hdrezka(title='Example',year=2024,
                media_id='77',media_type='movie')
            self.assertEqual('NO_MATCH',result.status)
            self.assertEqual(0,result.error_count)
            self.assertEqual(0,provider_reliability.snapshot('hdrezka')['hard_failures'])


if __name__ == '__main__':
    unittest.main()
