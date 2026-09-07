import os
import unittest
from pathlib import Path
from unittest.mock import patch

import content_filler as c


class ContentFillerBudgetPolicyTests(unittest.TestCase):
    def test_provider_errors_back_off_exponentially_and_cap_at_one_day(self):
        state = {"retry_after": {}, "failure_streaks": {}}
        now = 1_000_000
        c._record_retry_outcome(state, 7, "provider_error", now)
        self.assertEqual(state["retry_after"]["7"], now + 2 * 60 * 60)
        c._record_retry_outcome(state, 7, "provider_error", now)
        self.assertEqual(state["retry_after"]["7"], now + 4 * 60 * 60)
        for _ in range(10):
            c._record_retry_outcome(state, 7, "provider_error", now)
        self.assertEqual(state["retry_after"]["7"], now + 24 * 60 * 60)

    def test_no_source_and_identity_have_long_negative_cache(self):
        state = {"retry_after": {}, "failure_streaks": {}}
        now = 2_000_000
        c._record_retry_outcome(state, 8, "no_source", now)
        self.assertEqual(state["retry_after"]["8"], now + 7 * 24 * 60 * 60)
        c._record_retry_outcome(state, 9, "rejected_by_identity", now)
        self.assertEqual(state["retry_after"]["9"], now + 30 * 24 * 60 * 60)

    def test_success_clears_retry_state(self):
        state = {"retry_after": {"7": 123}, "failure_streaks": {"7": 3}}
        c._record_retry_outcome(state, 7, "persisted", 100)
        self.assertNotIn("7", state["retry_after"])
        self.assertNotIn("7", state["failure_streaks"])

    def test_retry_due_is_local_only(self):
        state = {"retry_after": {"7": 200}}
        self.assertFalse(c._retry_due(state, 7, 199))
        self.assertTrue(c._retry_due(state, 7, 200))
        self.assertTrue(c._retry_due(state, 8, 1))

    def test_background_bulk_skips_torrent_fallback_by_default_source_contract(self):
        text = Path(c.__file__).read_text(encoding="utf-8")
        self.assertIn('MOVIA_BACKGROUND_TORRENT_LOOKUP', text)
        self.assertIn('(not background_bulk or allow_background_torrent)', text)


if __name__ == "__main__":
    unittest.main()
