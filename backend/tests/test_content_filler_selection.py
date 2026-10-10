"""Regression checks for efficient, identity-safe movie background selection."""
import json
import sqlite3
import unittest
from datetime import datetime, timezone

import content_filler as filler


class SelectionTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.execute('''CREATE TABLE movies(
            id INTEGER PRIMARY KEY, tmdb_id INTEGER, media_type TEXT, title TEXT,
            original_title TEXT, year INTEGER, category TEXT, rating REAL,
            vote_count INTEGER, streams TEXT, playback_url TEXT, link_verified INTEGER,
            link_updated_at TEXT, metadata_source TEXT DEFAULT '')''')
        self.year = datetime.now(timezone.utc).year - 5

    def tearDown(self):
        self.db.close()

    def add(self, media_id, media_type, urls, *, verified=1, timestamp='2000-01-01'):
        streams = [{'provider':'fixture','source':'fixture','url':url} for url in urls]
        self.db.execute('INSERT INTO movies(id,tmdb_id,media_type,title,original_title,year,category,rating,vote_count,streams,playback_url,link_verified,link_updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)', (
            media_id, media_id, media_type, f'Film {media_id}', f'Film {media_id}',
            self.year, 'movies' if media_type == 'movie' else 'tv_series', 7, 1,
            json.dumps(streams), urls[0] if urls else '', verified, timestamp,
        ))

    def test_movie_without_stream_prioritized_and_tv_not_queried_without_episode(self):
        self.add(1, 'movie', [], verified=0)
        self.add(2, 'tv', [], verified=0)
        rows = filler._fetch_rows(self.db, 0, {'retry_after':{}, 'cloud_backfill_id':0})
        self.assertEqual([1], [row['id'] for row in rows])

    def test_p2p_only_saved_rows_do_not_churn_but_mixed_direct_refreshes(self):
        magnet = 'magnet:?xt=urn:btih:' + 'a'*40
        self.add(1, 'movie', [magnet])
        self.add(2, 'movie', [magnet, 'https://media.example/secondary.m3u8'])
        self.add(3, 'movie', ['https://media.example/direct.m3u8'])
        self.add(4, 'movie', [], verified=0)
        rows = filler._fetch_rows(self.db, 0, {'retry_after':{}, 'cloud_backfill_id':0})
        self.assertEqual({2,3,4}, {row['id'] for row in rows})

    def test_redirected_wrong_media_type_is_never_reenriched(self):
        self.add(1, 'movie', [], verified=0)
        self.add(2, 'movie', [], verified=0)
        self.db.execute("UPDATE movies SET metadata_source='tmdb_wrong_media_type' WHERE id=1")
        rows = filler._fetch_rows(self.db, 0, {'retry_after':{}, 'cloud_backfill_id':0})
        self.assertEqual([2], [row['id'] for row in rows])

    def test_fresh_direct_kept_out_of_background_refresh(self):
        self.add(1, 'movie', ['https://media.example/recent.m3u8'],
                 timestamp=datetime.now(timezone.utc).isoformat())
        self.add(2, 'movie', ['https://media.example/stale.m3u8'])
        rows = filler._fetch_rows(self.db, 0, {'retry_after':{}, 'cloud_backfill_id':0})
        self.assertEqual([2], [row['id'] for row in rows])


if __name__ == '__main__':
    unittest.main()
