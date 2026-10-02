import json
import sqlite3
import tempfile
import threading
import time
import unittest
from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

import database
import streamer
from bounded_executor import BoundedExecutor


class DiscoveryPersistenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)
        self.card = {'id': 7, 'title': 'Fixture', 'original_title': 'Fixture', 'year': 2020,
                     'media_type': 'movie', 'category': 'movies', 'tmdb_id': 77}
        self.cache = patch.object(streamer, 'CACHE_DB_PATH', self.path / 'streams.db')
        self.cache.start()
        streamer.init_cache_db()
        with streamer._STREAM_MEMORY_CACHE_LOCK: streamer._STREAM_MEMORY_CACHE.clear()

    def tearDown(self):
        self.cache.stop()
        with streamer._STREAM_MEMORY_CACHE_LOCK: streamer._STREAM_MEMORY_CACHE.clear()
        self.temp.cleanup()

    def row(self, voice, **kwargs):
        return dict({'source': 'Fixture provider', 'provider': 'fixture', 'voice': voice,
                     'quality': '720p', 'url': 'https://media.example/'+voice+'.mp4'}, **kwargs)

    def test_provider_completed_between_timeout_and_callback_registration_is_retained(self):
        pool = BoundedExecutor(workers=2, max_pending=0, name='test-late-provider')
        release, finished = threading.Event(), threading.Event()
        def slow(*_):
            release.wait(3)
            finished.set()
            return [self.row('B')]
        original = streamer.set_cached_streams
        first = [True]
        def write_then_complete(*args, **kwargs):
            original(*args, **kwargs)
            if first[0]:
                first[0] = False
                release.set()
                self.assertTrue(finished.wait(2))
                # Future state changes just after its resolver returns.
                time.sleep(.02)
        try:
            with patch.object(streamer, '_PROVIDER_EXECUTOR', pool), \
                    patch.object(streamer, 'P2P_ENABLED', True), patch.object(streamer, 'CLOUD_MODE', False), \
                    patch.object(streamer, '_catalog_identity_for_request', return_value=('OK', self.card)), \
                    patch.object(streamer, 'get_recent_stale_direct_streams', return_value=[]), \
                    patch.object(streamer, '_resolve_balancer_provider', return_value=[self.row('A')]), \
                    patch.object(streamer, '_resolve_torrent_provider', side_effect=slow), \
                    patch.object(streamer, 'set_cached_streams', side_effect=write_then_complete), \
                    patch.object(streamer, 'persist_resolved_streams_to_catalog', return_value=True) as persisted:
                rows = streamer.resolve_on_demand_streams('Fixture', 2020, catalog_media_id=7,
                    media_type='movie', force_refresh=True, _allow_stale_fast_path=False)
                self.assertEqual({'A', 'B'}, {row['voice'] for row in rows})
                self.assertEqual(7, persisted.call_args.args[0])
                self.assertEqual({'A', 'B'}, {row['voice'] for row in persisted.call_args.args[1]})
        finally: release.set(); pool.close()

    def test_late_result_for_another_catalog_identity_is_rejected(self):
        streamer.set_cached_streams('fixture', [self.row('A')])
        future = Future(); future.set_result([self.row('B', catalog_media_id='8')])
        with patch.object(streamer, 'persist_resolved_streams_to_catalog') as persist:
            streamer._persist_late_provider_results(future, 'fixture', self.card, None, None)
            self.assertFalse(persist.called)
        self.assertEqual(['A'], [row['voice'] for row in streamer.get_cached_streams('fixture')])

    def test_memory_cache_and_identity_locks_stay_bounded_for_large_catalog(self):
        locks = {id(streamer._resolve_lock_for(str(index))) for index in range(5000)}
        self.assertLessEqual(len(locks), 64)
        with streamer._STREAM_MEMORY_CACHE_LOCK:
            for index in range(200): streamer._remember_streams(str(index), [self.row('A')], 60)
            self.assertLessEqual(len(streamer._STREAM_MEMORY_CACHE), 64)
            self.assertIn('199', streamer._STREAM_MEMORY_CACHE)

    def test_parallel_database_provider_completions_keep_every_variant(self):
        db_path = self.path / 'catalog.db'
        with patch.object(database, 'DB_PATH', db_path):
            database.init_db(); database.ensure_cloud_playback_columns()
            with sqlite3.connect(db_path) as conn:
                conn.execute("INSERT INTO movies(id,tmdb_id,title,original_title,year,media_type,category) VALUES(7,77,'Fixture','Fixture',2020,'movie','movies')")
            barrier = threading.Barrier(8)
            def persist(index):
                barrier.wait(3)
                return database.save_content({'id': 7, 'streams': [self.row('Studio'+str(index))]})
            with ThreadPoolExecutor(max_workers=8) as workers:
                self.assertTrue(all(workers.map(persist, range(8))))
            with sqlite3.connect(db_path) as conn:
                rows = json.loads(conn.execute('SELECT streams FROM movies WHERE id=7').fetchone()[0])
            self.assertEqual({'Studio'+str(index) for index in range(8)}, {row['voice'] for row in rows})
