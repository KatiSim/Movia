import copy
import threading
import time
import unittest
from catalog_stream_service import CatalogStreamService
from cloud_api import ReadService


class CatalogStreamServiceTests(unittest.TestCase):
    def setUp(self):
        self.card = {'id': '7', 'title': 'Fixture', 'original_title': 'Fixture original',
                     'year': 2020, 'tmdb_id': 123, 'category': 'movies', 'media_type': 'movie', 'streams': []}
        self.calls, self.writes = [], []
        self.entered, self.release = threading.Event(), threading.Event()
        self.results = [self.row('new')]
        self.expiry = None
        outer = self
        class Catalog:
            def get_movie_playback_card(self, ident): return copy.deepcopy(outer.card) if str(ident) == '7' else None
        class Runtime:
            def cloud_exposable_streams(self, rows): return rows
            def _direct_stream_expiry_seconds(self, row): return outer.expiry
            def resolve_on_demand_streams(self, **kwargs):
                outer.calls.append(kwargs); outer.entered.set(); outer.release.wait(3)
                return copy.deepcopy(outer.results)
            def persist_resolved_streams_to_catalog(self, ident, rows):
                outer.writes.append((ident,rows)); outer.card['streams'] = rows; return True
        def exact(rows, card):
            return [row for row in rows if row.get('season') == card.get('season') and row.get('episode') == card.get('episode')]
        self.service = CatalogStreamService(Catalog(), Runtime(), exact, workers=1)
        self.api = ReadService(Catalog(), self.service)

    def tearDown(self): self.release.set(); self.assertTrue(self.service.close())
    def row(self, label, **kwargs):
        return dict({'source': 'Fixture provider', 'voice': label, 'quality': '720p',
                     'url': 'https://media.example/'+label+'.mp4'}, **kwargs)
    def wait(self, predicate):
        deadline = time.monotonic()+3
        while time.monotonic() < deadline:
            if predicate(): return
            time.sleep(.005)
        self.assertTrue(predicate())

    def test_cold_request_returns_quickly_and_enqueues_exact_identity(self):
        start = time.monotonic()
        code, body = self.service('7', None, None)
        self.assertLess(time.monotonic()-start, .25)
        self.assertEqual(200, code); self.assertEqual('DISCOVERY_PENDING', body['status'])
        self.assertTrue(self.entered.wait(2))
        request = self.calls[0]
        self.assertEqual('7', request['catalog_media_id'])
        self.assertEqual('Fixture original', request['original_title'])
        self.assertEqual(123, request['tmdb_id'])
        self.assertTrue(request['require_catalog_identity'])
        self.assertFalse(request['_allow_stale_fast_path'])

    def test_ready_variants_are_visible_immediately_after_pending_response(self):
        import json
        pending = json.loads(self.api.response('/api/movie/7/stream')[1])
        self.assertEqual('DISCOVERY_PENDING', pending['status'])
        self.release.set(); self.wait(lambda: self.service.queue.status(('7',None,None)) == 'READY')
        ready = json.loads(self.api.response('/api/movie/7/stream')[1])
        self.assertEqual('READY', ready['status']); self.assertEqual('new', ready['streams'][0]['voice'])
        self.assertEqual('7', ready['streams'][0]['catalog_media_id'])
        self.assertFalse(self.api.cache)

    def test_cached_ready_variants_do_not_trigger_provider_work(self):
        self.card['streams'] = [self.row('cached')]
        code, body = self.service('7',None,None)
        self.assertEqual('READY', body['status']); self.assertEqual('IDLE', body['discoveryStatus'])
        self.assertEqual([], self.calls)

    def test_refresh_keeps_valid_cached_streams_and_deduplicates_requests(self):
        self.card['streams'] = [self.row('cached')]
        for _ in range(20):
            code, body = self.service('7',None,None,force_refresh=True)
            self.assertEqual('cached', body['streams'][0]['voice'])
        self.assertTrue(self.entered.wait(2)); self.assertEqual(1,len(self.calls))
        self.results=[]; self.release.set()
        self.wait(lambda: self.service.queue.status(('7',None,None)) == 'UNAVAILABLE')
        self.assertEqual('cached', self.service('7',None,None)[1]['streams'][0]['voice'])
        self.assertEqual([], self.writes)

    def test_expired_signed_urls_are_not_claimed_ready(self):
        self.card['streams'] = [self.row('expired')]; self.expiry = 0
        self.assertEqual('DISCOVERY_PENDING', self.service('7',None,None)[1]['status'])

    def test_series_requires_exact_episode_and_preserves_it_in_discovery(self):
        self.card.update(media_type='tv', category='tv_series')
        self.assertEqual(400, self.service('7',None,None)[0]); self.assertEqual([], self.calls)
        self.assertEqual(400, self.service('7',1,None)[0])
        self.card['streams'] = [self.row('other', season=1,episode=3)]
        self.results = [self.row('right',season=1,episode=2), self.row('wrong',season=1,episode=3)]
        self.assertEqual('DISCOVERY_PENDING', self.service('7',1,2)[1]['status'])
        self.assertTrue(self.entered.wait(2)); self.release.set()
        self.wait(lambda: self.service.queue.status(('7',1,2)) == 'READY')
        rows = self.service('7',1,2)[1]['streams']
        self.assertEqual(['right'],[row['voice'] for row in rows])
        self.assertEqual((1,2),(self.calls[0]['season'],self.calls[0]['episode']))

    def test_movie_cannot_request_an_episode_and_unknown_cards_do_not_queue(self):
        self.assertEqual(400,self.service('7',1,1)[0])
        self.assertEqual(404,self.service('999',None,None)[0]); self.assertEqual(0,self.service.queue.stats()['accepted'])

    def test_terminal_discovery_failure_does_not_poll_or_retry_forever(self):
        self.results=[]; self.release.set(); self.service('7',None,None)
        self.wait(lambda: self.service.queue.status(('7',None,None)) == 'UNAVAILABLE')
        for _ in range(20): self.assertEqual('UNAVAILABLE',self.service('7',None,None)[1]['status'])
        self.assertEqual(1,len(self.calls))

    def test_force_refresh_and_invalid_refresh_reach_the_read_contract(self):
        self.assertEqual(400,self.api.response('/api/movie/7/stream?refresh=invalid')[0])
        self.assertEqual(200,self.api.response('/api/movie/7/stream?refresh=1')[0])
        self.assertTrue(self.entered.wait(2)); self.assertTrue(self.calls[0]['force_refresh'])
