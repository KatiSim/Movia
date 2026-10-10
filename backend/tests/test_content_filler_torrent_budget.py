"""Background torrent bounds must never claim a failed search is a missing title."""
import time
import unittest
from concurrent.futures import Future
from threading import BoundedSemaphore
from unittest.mock import patch

import content_filler as filler
from provider_discovery import ProviderDiscoveryOutcome


class BackgroundTorrentDeadlineTests(unittest.TestCase):
    def row(self):
        return {'id': 77, 'tmdb_id': 77, 'media_type': 'movie',
                'title': 'Example', 'original_title': 'Example', 'year': 2020,
                'category': 'movies', 'streams': '[]', 'link_verified': 0}

    def direct_futures(self):
        registry=Future()
        registry.set_result(ProviderDiscoveryOutcome([], 'NO_MATCH', ('hdrezka',), 0))
        balancer=Future()
        balancer.set_result((None, {'status':'NO_RESULTS', 'error_count':0}))
        return registry,balancer

    def test_configured_budget_clamps_to_safe_bounds(self):
        for value, expected in [('0',4.0), ('100',30.0), ('invalid',16.0)]:
            with self.subTest(value=value), patch.dict('os.environ',{
                        'MOVIA_BACKGROUND_TORRENT_BUDGET_SECONDS':value},clear=False):
                self.assertEqual(expected,filler._background_torrent_budget_seconds())

    def test_running_torrent_times_out_then_capacity_defers_next_row(self):
        registry,balancer=self.direct_futures()
        running=Future()
        self.assertTrue(running.set_running_or_notify_cancel())
        third=Future()
        third.set_result(None)
        slots=BoundedSemaphore(1)
        env={'MOVIA_BACKGROUND_BULK':'1','MOVIA_BACKGROUND_TORRENT_LOOKUP':'1',
             'MOVIA_CLOUD_MODE':'0'}
        with patch.dict('os.environ',env,clear=False), \
             patch.object(filler, '_FILL_BACKGROUND_TORRENT_SLOTS',slots), \
             patch.object(filler, '_background_torrent_budget_seconds',return_value=0.02), \
             patch.object(filler._FILL_PROVIDER_EXECUTOR,'submit',return_value=registry), \
             patch.object(filler._FILL_BALANCER_EXECUTOR,'submit',return_value=balancer), \
             patch.object(filler._FILL_TORRENT_EXECUTOR,'submit',side_effect=[running,third]) as submit, \
             patch.object(filler,'wait',return_value=({registry,balancer},set())):
            begin=time.monotonic()
            first=filler._process_row(self.row(),1,3)
            self.assertLess(time.monotonic()-begin,1.0)
            self.assertEqual('BUDGET_TIMEOUT',first['torrent_status'])
            self.assertEqual('provider_timeout',first['status'])
            self.assertEqual(1,first['provider_timeouts'])
            self.assertEqual(0,first['provider_errors'])
            self.assertFalse(running.cancelled())  # started network task cannot be killed
            second=filler._process_row(self.row(),2,3)
            self.assertEqual('CAPACITY_DEFERRED',second['torrent_status'])
            self.assertEqual('provider_deferred',second['status'])
            self.assertEqual(1,submit.call_count)
            running.set_result(None)  # callback releases permit only on termination
            third_result=filler._process_row(self.row(),3,3)
            self.assertEqual('NO_RESULTS',third_result['torrent_status'])
            self.assertEqual(2,submit.call_count)
            self.assertTrue(slots.acquire(blocking=False))

    def test_disabled_background_torrent_has_no_deadline_work(self):
        registry,balancer=self.direct_futures()
        with patch.dict('os.environ',{'MOVIA_BACKGROUND_BULK':'1',
                    'MOVIA_BACKGROUND_TORRENT_LOOKUP':'0','MOVIA_CLOUD_MODE':'0'},clear=False), \
             patch.object(filler._FILL_PROVIDER_EXECUTOR,'submit',return_value=registry), \
             patch.object(filler._FILL_BALANCER_EXECUTOR,'submit',return_value=balancer), \
             patch.object(filler._FILL_TORRENT_EXECUTOR,'submit') as submit, \
             patch.object(filler,'wait',return_value=({registry,balancer},set())):
            result=filler._process_row(self.row(),1,1)
        submit.assert_not_called()
        self.assertEqual('NOT_REQUESTED',result['torrent_status'])


if __name__ == '__main__':
    unittest.main()
