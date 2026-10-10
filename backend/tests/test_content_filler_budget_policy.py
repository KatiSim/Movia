import os
import sqlite3
import unittest
from pathlib import Path
from unittest.mock import patch

import content_filler as c


class ContentFillerBudgetPolicyTests(unittest.TestCase):
    def test_direct_provider_budget_is_bounded(self):
        with patch.dict('os.environ', {'MOVIA_BACKGROUND_DIRECT_PROVIDER_BUDGET_SECONDS': '0'}, clear=False):
            self.assertEqual(0.05, c._background_direct_provider_budget_seconds())
        with patch.dict('os.environ', {'MOVIA_BACKGROUND_DIRECT_PROVIDER_BUDGET_SECONDS': '99'}, clear=False):
            self.assertEqual(8.0, c._background_direct_provider_budget_seconds())
        with patch.dict('os.environ', {'MOVIA_BACKGROUND_DIRECT_PROVIDER_BUDGET_SECONDS': 'bad'}, clear=False):
            self.assertEqual(c.BACKGROUND_DIRECT_PROVIDER_BUDGET_SECONDS, c._background_direct_provider_budget_seconds())

    def test_identity_cleanup_contract_version_is_v4(self):
        self.assertEqual(4, c.STREAM_CLEANUP_VERSION)

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

    def test_partial_coverage_retries_in_thirty_minutes(self):
        state = {"retry_after": {}, "failure_streaks": {"7": 2}}
        now = 3_000_000
        c._record_retry_outcome(state, 7, "partial_coverage", now)
        self.assertEqual(state["retry_after"]["7"], now + 30 * 60)
        self.assertNotIn("7", state["failure_streaks"])

    def test_complete_coverage_refreshes_daily(self):
        state = {"retry_after": {}, "failure_streaks": {}}
        now = 4_000_000
        c._record_retry_outcome(state, 7, "coverage_complete", now)
        self.assertEqual(state["retry_after"]["7"], now + 24 * 60 * 60)

    def test_recent_no_source_retries_in_thirty_minutes(self):
        state = {"retry_after": {}, "failure_streaks": {}}
        now = 5_000_000
        c._record_retry_outcome(state, 7, "no_source_recent", now)
        self.assertEqual(state["retry_after"]["7"], now + 30 * 60)

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

    def test_fetch_rows_prioritizes_incomplete_over_complete_variants(self):
        db = sqlite3.connect(":memory:")
        db.row_factory = sqlite3.Row
        db.execute("""CREATE TABLE movies(
            id INTEGER PRIMARY KEY, tmdb_id INTEGER, media_type TEXT, title TEXT,
            original_title TEXT, year INTEGER, category TEXT, rating REAL,
            vote_count INTEGER, streams TEXT, playback_url TEXT, link_verified INTEGER,
            link_updated_at TEXT, metadata_source TEXT DEFAULT ''
        )""")
        current_year = __import__("datetime").datetime.now(__import__("datetime").timezone.utc).year
        complete=[]
        for voice in ("Dub", "LostFilm", "Original"):
            for quality in ("1080p", "720p", "480p"):
                complete.append({
                    "source":"fixture","provider":"fixture","voice":voice,"quality":quality,
                    "url":f"https://media.example/{voice}-{quality}.m3u8",
                })
        incomplete=[{
            "source":"fixture","provider":"fixture","voice":"Dub","quality":"1080p",
            "url":"https://media.example/only.m3u8",
        }]
        # Eleven higher-vote complete cards would consume the recent quota if
        # coverage were ignored. The incomplete card must still be selected.
        for index in range(11):
            db.execute(
                "INSERT INTO movies(id,tmdb_id,media_type,title,original_title,year,category,rating,vote_count,streams,playback_url,link_verified,link_updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (100+index,index,"movie",f"Complete {index}",f"Complete {index}",current_year,
                 "movies",9.0,10000-index,__import__("json").dumps(complete),"https://media.example/x",1,"2000-01-01"),
            )
        db.execute(
            "INSERT INTO movies(id,tmdb_id,media_type,title,original_title,year,category,rating,vote_count,streams,playback_url,link_verified,link_updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (999,999,"movie","Incomplete","Incomplete",current_year,"movies",8.0,1,
             __import__("json").dumps(incomplete),"https://media.example/only.m3u8",1,"2000-01-01"),
        )
        rows = c._fetch_rows(db, 0, {"retry_after": {}, "cloud_backfill_id": 0})
        ids=[int(row["id"]) for row in rows]
        self.assertIn(999, ids[:10])
        db.close()

    def test_fetch_rows_reserves_popular_old_and_backfill_slots(self):
        db = sqlite3.connect(":memory:")
        db.row_factory = sqlite3.Row
        db.execute("""CREATE TABLE movies(
            id INTEGER PRIMARY KEY, tmdb_id INTEGER, media_type TEXT, title TEXT,
            original_title TEXT, year INTEGER, category TEXT, rating REAL,
            vote_count INTEGER, streams TEXT, playback_url TEXT, link_verified INTEGER,
            link_updated_at TEXT, metadata_source TEXT DEFAULT ''
        )""")
        current_year = __import__("datetime").datetime.now(__import__("datetime").timezone.utc).year
        for index in range(1, 26):
            db.execute(
                "INSERT INTO movies(id,tmdb_id,media_type,title,original_title,year,category,rating,vote_count,streams,playback_url,link_verified,link_updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (10_000+index, index, "movie", f"Recent {index}", f"Recent {index}",
                 current_year, "movies", 5.0, index, "[]", "", 0, ""),
            )
        db.execute(
            "INSERT INTO movies(id,tmdb_id,media_type,title,original_title,year,category,rating,vote_count,streams,playback_url,link_verified,link_updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (500, 500, "movie", "Popular Old", "Popular Old", current_year-5,
             "movies", 9.0, 999999, "[]", "", 0, ""),
        )
        db.execute(
            "INSERT INTO movies(id,tmdb_id,media_type,title,original_title,year,category,rating,vote_count,streams,playback_url,link_verified,link_updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (1, 1, "movie", "Backfill", "Backfill", current_year-20,
             "movies", 1.0, 0, "[]", "", 0, ""),
        )
        rows = c._fetch_rows(db, 0, {"retry_after": {}, "cloud_backfill_id": 0})
        ids = [int(row["id"]) for row in rows]
        self.assertLessEqual(len(ids), 30)
        self.assertIn(500, ids)
        self.assertIn(1, ids)
        self.assertGreaterEqual(sum(1 for value in ids if value >= 10_000), 10)
        db.close()

    def test_background_bulk_skips_torrent_fallback_by_default_source_contract(self):
        text = Path(c.__file__).read_text(encoding="utf-8")
        self.assertIn('MOVIA_BACKGROUND_TORRENT_LOOKUP', text)
        self.assertIn('should_resolve_torrent = not background_bulk or allow_background_torrent', text)


if __name__ == "__main__":
    unittest.main()
