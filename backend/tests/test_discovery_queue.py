import threading
import time
import unittest
from discovery_queue import DiscoveryQueue


class DiscoveryQueueTests(unittest.TestCase):
    def wait(self, predicate):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if predicate(): return
            time.sleep(.005)
        self.assertTrue(predicate())

    def test_request_burst_is_single_flight_and_work_never_runs_on_caller(self):
        entered, release = threading.Event(), threading.Event()
        callers = []
        def resolve(key):
            callers.append(threading.get_ident()); entered.set(); release.wait(3); return True
        queue = DiscoveryQueue(resolve, workers=1)
        try:
            self.assertTrue(queue.submit(('7', 2, 3))); self.assertTrue(entered.wait(2))
            self.assertTrue(all(not queue.submit(('7', 2, 3)) for _ in range(1000)))
            self.assertEqual('RUNNING', queue.status(('7', 2, 3)))
            self.assertEqual(1, queue.stats()['accepted'])
            self.assertNotIn(threading.get_ident(), callers)
            release.set(); self.wait(lambda: queue.status(('7', 2, 3)) == 'READY')
        finally: release.set(); self.assertTrue(queue.close())

    def test_pending_queue_and_active_workers_have_fixed_limits(self):
        release = threading.Event()
        queue = DiscoveryQueue(lambda key: release.wait(3), workers=2, max_pending=2)
        try:
            for n in range(2): self.assertTrue(queue.submit((str(n), None, None)))
            self.wait(lambda: queue.stats()['active'] == 2)
            accepted = sum(queue.submit((str(n), None, None)) for n in range(2, 1002))
            self.assertEqual(2, accepted)
            self.assertEqual(2, queue.stats()['pending'])
            self.assertEqual(2, queue.stats()['active'])
        finally: release.set(); self.assertTrue(queue.close())

    def test_exact_episode_keys_are_independent(self):
        seen = []
        queue = DiscoveryQueue(lambda key: seen.append(key) or True, workers=1)
        keys = [('7', 1, 2), ('7', 1, 3), ('7', 2, 2)]
        try:
            for key in keys: self.assertTrue(queue.submit(key))
            self.wait(lambda: queue.stats()['finished'] == 3)
            self.assertEqual(keys, seen)
        finally: self.assertTrue(queue.close())

    def test_failed_jobs_have_cooldown_and_workers_survive_exceptions(self):
        clock = [100.0]
        def fail(key): raise RuntimeError('provider unavailable')
        queue = DiscoveryQueue(fail, workers=1, failure_ttl=30, clock=lambda: clock[0])
        try:
            key = ('7', None, None); self.assertTrue(queue.submit(key))
            self.wait(lambda: queue.status(key) == 'UNAVAILABLE')
            self.assertFalse(queue.submit(key)); clock[0] = 131
            self.assertTrue(queue.submit(key)); self.wait(lambda: queue.stats()['finished'] == 2)
            self.assertEqual(2, queue.stats()['failures'])
        finally: self.assertTrue(queue.close())

    def test_finished_job_metadata_does_not_grow_without_bound(self):
        queue = DiscoveryQueue(lambda key: True, workers=1, remembered=3)
        try:
            for n in range(20):
                self.assertTrue(queue.submit((str(n), None, None)))
                self.wait(lambda: queue.stats()['finished'] >= n+1)
            self.assertEqual(3, queue.stats()['remembered'])
        finally: self.assertTrue(queue.close())

    def test_close_cancels_queued_work_and_rejects_new_requests(self):
        entered, release = threading.Event(), threading.Event()
        seen = []
        def resolve(key): seen.append(key); entered.set(); release.wait(3); return True
        queue = DiscoveryQueue(resolve, workers=1)
        queue.submit(('1',None,None)); self.assertTrue(entered.wait(2))
        queue.submit(('2',None,None))
        self.assertFalse(queue.close(timeout=.01))
        self.assertFalse(queue.submit(('3',None,None)))
        release.set(); self.assertTrue(queue.close())
        self.assertEqual([('1',None,None)], seen)

    def test_invalid_resource_budgets_are_rejected(self):
        for kwargs in ({'workers': 0}, {'workers': 3}, {'max_pending': 65}, {'remembered': 513}):
            with self.assertRaises(ValueError): DiscoveryQueue(lambda key: True, **kwargs)
