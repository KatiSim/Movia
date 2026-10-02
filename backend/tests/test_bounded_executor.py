import threading
import unittest
from bounded_executor import BoundedExecutor


class BoundedExecutorTests(unittest.TestCase):
    def test_stuck_provider_cannot_create_unbounded_threads_or_pending_futures(self):
        release, entered = threading.Event(), threading.Event()
        def blocked(): entered.set(); return release.wait(3)
        pool = BoundedExecutor(workers=1,max_pending=1)
        try:
            first=pool.submit(blocked); self.assertTrue(entered.wait(2))
            second=pool.submit(blocked)
            self.assertIsNotNone(second)
            self.assertTrue(all(pool.submit(blocked) is None for _ in range(1000)))
            release.set(); self.assertTrue(first.result(2)); self.assertTrue(second.result(2))
            self.assertEqual(7,pool.submit(lambda: 7).result(2))
        finally: release.set(); pool.close()

    def test_exception_releases_a_slot_without_poisoning_the_pool(self):
        pool=BoundedExecutor(workers=1,max_pending=0)
        try:
            def fail(): raise ValueError('provider error')
            with self.assertRaises(ValueError): pool.submit(fail).result(2)
            self.assertEqual(7,pool.submit(lambda: 7).result(2))
        finally: pool.close()

    def test_cancelled_pending_job_releases_its_slot(self):
        release, entered = threading.Event(), threading.Event()
        def blocked(): entered.set(); return release.wait(3)
        pool=BoundedExecutor(workers=1,max_pending=1)
        try:
            first=pool.submit(blocked); self.assertTrue(entered.wait(2))
            pending=pool.submit(lambda: 1); self.assertTrue(pending.cancel())
            replacement=pool.submit(lambda: 2); self.assertIsNotNone(replacement)
            release.set(); first.result(2); self.assertEqual(2,replacement.result(2))
        finally: release.set(); pool.close()

    def test_closed_pool_refuses_work_without_leaking_capacity(self):
        pool=BoundedExecutor(workers=1,max_pending=0); pool.close()
        for _ in range(10): self.assertIsNone(pool.submit(lambda: 1))
