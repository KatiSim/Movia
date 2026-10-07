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
                    patch.object(streamer, '_resolve_clean_provider_registry', return_value=[]), \
                    patch.object(streamer, '_resolve_torrent_provider', side_effect=slow), \
                    patch.object(streamer, 'set_cached_streams', side_effect=write_then_complete), \
                    patch.object(streamer, 'persist_resolved_streams_to_catalog', return_value=True) as persisted:
                rows = streamer.resolve_on_demand_streams('Fixture', 2020, catalog_media_id=7,
                    media_type='movie', force_refresh=True, _allow_stale_fast_path=False)
                self.assertEqual({'A', 'B'}, {row['voice'] for row in rows})
                self.assertEqual(7, persisted.call_args.args[0])
                self.assertEqual({'A', 'B'}, {row['voice'] for row in persisted.call_args.args[1]})
        finally: release.set(); pool.close()

    def test_clean_provider_registry_is_unioned_with_balancer_inventory(self):
        pool = BoundedExecutor(workers=2, max_pending=0, name='test-provider-union')
        filmix = self.row('Filmix Dub', provider='Filmix', source='Filmix', quality='1080p')
        balancer = self.row('Balancer Dub', provider='Rutor', source='Rutor', quality='720p')
        try:
            with patch.object(streamer, '_PROVIDER_EXECUTOR', pool), \
                    patch.object(streamer, 'P2P_ENABLED', False), \
                    patch.object(streamer, '_catalog_identity_for_request', return_value=('OK', self.card)), \
                    patch.object(streamer, 'get_recent_stale_direct_streams', return_value=[]), \
                    patch.object(streamer, '_resolve_balancer_provider', return_value=[balancer]), \
                    patch.object(streamer, '_resolve_clean_provider_registry', return_value=[filmix]):
                rows = streamer.resolve_on_demand_streams(
                    'Fixture', 2020, catalog_media_id=7, media_type='movie',
                    force_refresh=True, _allow_stale_fast_path=False,
                )
            self.assertEqual({'Filmix', 'Rutor'}, {row['provider'] for row in rows})
            self.assertEqual({'Filmix Dub', 'Balancer Dub'}, {row['voice'] for row in rows})
        finally:
            pool.close()

    def test_late_result_for_another_catalog_identity_is_rejected(self):
        streamer.set_cached_streams('fixture', [self.row('A')])
        future = Future(); future.set_result([self.row('B', catalog_media_id='8')])
        with patch.object(streamer, 'persist_resolved_streams_to_catalog') as persist:
            streamer._persist_late_provider_results(future, 'fixture', self.card, None, None)
            self.assertFalse(persist.called)
        self.assertEqual(['A'], [row['voice'] for row in streamer.get_cached_streams('fixture')])

    def test_source_truth_overlay_reads_while_another_sqlite_writer_is_busy(self):
        from playback_availability_index import PlaybackAvailabilityService, MEDIA_MOVIE
        index=PlaybackAvailabilityService(self.path/'truth.db')
        source=self.row('Studio')
        index.record_discovery('7',[source],kind=MEDIA_MOVIE)
        connection=index.repository.connect();connection.execute('BEGIN IMMEDIATE')
        try:
            with patch.object(streamer,'PLAYBACK_SOURCE_TRUTH_INDEX',index):
                started=time.monotonic()
                rows=streamer._annotate_streams_with_source_truth(self.card,[source],None,None)
                self.assertLess(time.monotonic()-started,.5)
                self.assertIn('sourceId',rows[0])
        finally:connection.rollback();connection.close()

    def test_expired_verification_is_not_published_by_the_readonly_overlay(self):
        from playback_availability_index import PlaybackMediaKey, MEDIA_MOVIE
        class Index:
            expiry_margin_seconds=15
            def get_by_key(self,key,**kwargs):
                import hashlib
                return {'sources':[{'sourceId':'expired','provider':'fixture','locatorHash':hashlib.sha256(source['url'].encode()).hexdigest(),
                    'requestProfileHash':__import__('native_variant_feedback').feedback_fingerprints(source)['native_feedback_profile_hash'],
                    'verificationStatus':'VERIFIED','expiresAt':time.time()-1,'actualQuality':'720p','actualQualities':['720p']}]}
            def get(self,*args,**kwargs):raise AssertionError('Mutating read')
        source=self.row('Studio')
        with patch.object(streamer,'PLAYBACK_SOURCE_TRUTH_INDEX',Index()):
            row=streamer._annotate_streams_with_source_truth(self.card,[source],None,None)[0]
        self.assertEqual('expired',row['sourceId'])
        self.assertEqual('EXPIRED',row['sourceTruth']['verificationStatus'])
        self.assertFalse(row['sourceTruth']['decodedPlayback'])
        self.assertNotIn('actualQuality',row['sourceTruth'])

    def test_decoded_evidence_reaches_active_scoped_rows_without_replacing_provider_claim(self):
        source=self.row('Studio')
        import hashlib
        class Index:
            expiry_margin_seconds=15
            def get_by_key(inner,key,**kwargs):
                return {'sources':[{'sourceId':'src:known','provider':'fixture',
                    'locatorHash':hashlib.sha256(source['url'].encode()).hexdigest(),
                    'requestProfileHash':__import__('native_variant_feedback').feedback_fingerprints(source)['native_feedback_profile_hash'],
                    'verificationStatus':'VERIFIED','verificationMethod':'MEDIA3_SUCCESS',
                    'healthScore':.98,'startupLatencyMs':2345,'consecutiveFailures':0,
                    'actualQuality':'576p','actualQualities':['576p'],'expiresAt':None}]}
        with patch.object(streamer,'PLAYBACK_SOURCE_TRUTH_INDEX',Index()):
            row=streamer._annotate_streams_with_source_truth(self.card,[source],None,None)[0]
        self.assertEqual(source['quality'],row['quality'])
        self.assertEqual('576p',row['sourceTruth']['actualQuality'])
        self.assertTrue(row['sourceTruth']['decodedPlayback'])
        self.assertEqual(2345,row['startup_latency_ms'])
        self.assertEqual(.98,row['health_score'])
        self.assertTrue(row['transport_metadata']['playback_decoded'])

    def test_failed_locator_exposes_health_without_reusing_success_latency(self):
        source=self.row('Studio')
        import hashlib
        class Index:
            expiry_margin_seconds=15
            def get_by_key(inner,key,**kwargs):
                return {'sources':[{'sourceId':'src:failed','provider':'fixture',
                    'locatorHash':hashlib.sha256(source['url'].encode()).hexdigest(),
                    'requestProfileHash':__import__('native_variant_feedback').feedback_fingerprints(source)['native_feedback_profile_hash'],
                    'verificationStatus':'COOLDOWN','verificationMethod':'MEDIA3_SUCCESS',
                    'healthScore':.1,'startupLatencyMs':2345,'consecutiveFailures':3,
                    'actualQuality':'576p','expiresAt':None}]}
        with patch.object(streamer,'PLAYBACK_SOURCE_TRUTH_INDEX',Index()):
            row=streamer._annotate_streams_with_source_truth(self.card,[source],None,None)[0]
        self.assertEqual('COOLDOWN',row['sourceTruth']['verificationStatus'])
        self.assertFalse(row['sourceTruth']['decodedPlayback'])
        self.assertIsNone(row['startup_latency_ms'])
        self.assertEqual(3,row['recent_failure_count'])
        self.assertNotIn('actualQuality',row['sourceTruth'])

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


class EpisodeDiscoveryIdentityTest(unittest.TestCase):
    def test_stream_id_enrichment_does_not_invent_episode_coordinates(self):
        generic={'source':'HDRezka','url':'https://media.example/series.m3u8','voice':'Dub','quality':'720p'}
        result=streamer.enrich_stream_identity([generic],1,2)
        self.assertNotIn('season',result[0]);self.assertNotIn('episode',result[0])
        self.assertEqual([],streamer.filter_streams_for_episode(result,1,2))

    def test_multiseason_pack_is_not_rebound_to_requested_episode(self):
        card={'id':7,'title':'Fixture','year':2020,'media_type':'tv'}
        pack={'source':'Rutor','url':'magnet:?xt=urn:btih:'+'a'*40,
              'title':'Fixture (2020) [S01-04]','quality':'720p','voice':'Dub','season':1,'episode':1}
        self.assertEqual([],streamer._scope_streams_to_catalog_card([pack],card,2,1))
        self.assertEqual((1,1),(pack['season'],pack['episode']))

    def test_foreground_union_rejects_generic_and_wrong_episode_rows(self):
        with tempfile.TemporaryDirectory() as temp:
            pool=BoundedExecutor(workers=2,max_pending=0,name='test-exact-episode')
            card={'id':7,'title':'Fixture','year':2020,'media_type':'tv','category':'tv_series','tmdb_id':77}
            generic={'source':'Fixture','url':'https://media.example/generic.m3u8','voice':'Dub','quality':'720p'}
            wrong=dict(generic,url='https://media.example/wrong.m3u8',season=1,episode=1)
            exact=dict(generic,url='https://media.example/exact.m3u8',season=1,episode=2)
            try:
                with patch.object(streamer,'CACHE_DB_PATH',Path(temp)/'streams.db'), patch.object(streamer,'_PROVIDER_EXECUTOR',pool), patch.object(streamer,'P2P_ENABLED',False), patch.object(streamer,'_catalog_identity_for_request',return_value=('OK',card)), patch.object(streamer,'get_recent_stale_direct_streams',return_value=[]), patch.object(streamer,'_resolve_balancer_provider',return_value=[generic,wrong]), patch.object(streamer,'_resolve_clean_provider_registry',return_value=[exact]):
                    streamer.init_cache_db()
                    with streamer._STREAM_MEMORY_CACHE_LOCK:streamer._STREAM_MEMORY_CACHE.clear()
                    rows=streamer.resolve_on_demand_streams('Fixture',2020,category='tv_series',season=1,episode=2,catalog_media_id=7,media_type='tv',force_refresh=True,_allow_stale_fast_path=False)
                self.assertEqual([exact['url']],[row['url'] for row in rows])
                self.assertEqual([(1,2)],[(row['season'],row['episode']) for row in rows])
            finally:
                pool.close()
                with streamer._STREAM_MEMORY_CACHE_LOCK:streamer._STREAM_MEMORY_CACHE.clear()

    def test_shared_binding_rejects_missing_wrong_and_noninteger_coordinates(self):
        from stream_validation import bind_stream_identity
        from stream_identity import filter_streams_for_content
        card={'id':7,'title':'Fixture','year':2020,'media_type':'tv','season':1,'episode':2}
        base={'source':'Fixture','url':'https://media.example/episode.m3u8','voice':'Dub','quality':'720p'}
        for coordinates in ({},{'season':1,'episode':1},{'season':True,'episode':2},{'season':1,'episode':2.9},{'season':1,'episode':'2.0'}):
            with self.subTest(coordinates=coordinates):
                row=dict(base,**coordinates)
                self.assertEqual([],filter_streams_for_content([row],card))
                self.assertEqual([],bind_stream_identity([row],catalog_media_id=7,title='Fixture',year=2020,media_type='tv',season=1,episode=2))
        valid=dict(base,season='01',episode='02')
        self.assertEqual(1,len(filter_streams_for_content([valid],card)))
        self.assertEqual(1,len(bind_stream_identity([valid],catalog_media_id=7,title='Fixture',year=2020,media_type='tv',season=1,episode=2)))

class ResolverOutcomePropagationTests(unittest.TestCase):
    setUp=DiscoveryPersistenceTest.setUp
    tearDown=DiscoveryPersistenceTest.tearDown
    row=DiscoveryPersistenceTest.row
    def resolve(self,outcome):
        pool=BoundedExecutor(workers=2,max_pending=2,name="test-outcome-propagation")
        try:
            with patch.object(streamer,"_PROVIDER_EXECUTOR",pool),patch.object(streamer,"P2P_ENABLED",False),patch.object(streamer,"_catalog_identity_for_request",return_value=("OK",self.card)),patch.object(streamer,"get_recent_stale_direct_streams",return_value=[]),patch.object(streamer,"_resolve_balancer_provider",return_value=[]),patch.object(streamer,"_resolve_clean_provider_registry",return_value=outcome):
                return streamer.resolve_on_demand_streams("Fixture",2020,catalog_media_id=7,media_type="movie",force_refresh=True,_allow_stale_fast_path=False)
        finally:pool.close()
    def test_actual_resolver_keeps_empty_provider_error(self):
        from provider_discovery import ProviderDiscoveryOutcome
        result=self.resolve(ProviderDiscoveryOutcome([],"PROVIDER_ERROR",error_count=1))
        self.assertEqual([],result)
        self.assertEqual(1,result.discovery_trace.snapshot()["providerErrorCount"])
    def test_actual_resolver_keeps_late_future_and_its_error(self):
        from provider_discovery import ProviderDiscoveryOutcome
        late=Future()
        result=self.resolve(ProviderDiscoveryOutcome([],"PROVIDER_TIMEOUT",pending_futures=(late,)))
        self.assertEqual(1,result.discovery_trace.snapshot()["pendingProviderCount"])
        late.set_exception(RuntimeError("private backend URI"))
        self.assertEqual(1,result.discovery_trace.snapshot()["providerErrorCount"])
