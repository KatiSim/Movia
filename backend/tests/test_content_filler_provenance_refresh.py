"""Refresh only a saved article whose complete catalog identity was observed."""
import json
import unittest
from concurrent.futures import Future
from unittest.mock import patch

import content_filler as filler
from hdrezka_provider_adapter import HDRezkaProviderAdapter, DEF
from provider_contract import ProviderRequest, ProviderSearchResult, flatten_variant_tree
from provider_discovery import ProviderDiscoveryOutcome


class ProvenanceRefreshTests(unittest.TestCase):
    def setUp(self):
        request = ProviderRequest('42','Example',2024)
        ref = 'https://rezka.ag/films/drama/42-example-2024.html'
        article = ProviderSearchResult(DEF,'films/drama/42-example-2024','Example',2024,
                                       ref,'films/drama/42-example-2024')
        fake = [{'url':'https://cdn.example/real.mp4','voice':'Original',
                 'stream_key':'translator:1:rendition:480'}]
        with patch('hdrezka_provider_adapter._resolve_hdrezka',return_value=(fake,None)):
            tree, found, error = HDRezkaProviderAdapter().resolve_source(article,request)
        self.assertIsNone(error)
        self.assertIsNotNone(found)
        self.saved = flatten_variant_tree(found,tree,request)
        self.row = {'id':42,'tmdb_id':42,'title':'Example','original_title':'Example',
                    'year':2024,'category':'movies','media_type':'movie',
                    'streams':json.dumps(self.saved * 2), 'rating':8.0}

    def test_exact_saved_article_deduplicates_by_article_not_renditions(self):
        result = filler._saved_hdrezka_refresh_sources(self.row,request_title='Example')
        self.assertEqual(1,len(result))
        self.assertEqual(self.saved[0]['logical_source_id'],result[0]['logical_source_id'])

    def test_changed_media_id_year_title_or_provenance_is_rejected(self):
        for key,value in [('id',43),('year',2025),('title','Other')]:
            with self.subTest(key=key):
                altered=dict(self.row)
                altered[key]=value
                self.assertEqual([],filler._saved_hdrezka_refresh_sources(altered,request_title=str(altered['title'])))
        ordinary=[dict(self.saved[0],reload_data=None,transport_metadata={})]
        altered=dict(self.row,streams=json.dumps(ordinary))
        self.assertEqual([],filler._saved_hdrezka_refresh_sources(altered,request_title='Example'))
        self.assertEqual([],filler._saved_hdrezka_refresh_sources(dict(self.row,streams='invalid'),request_title='Example'))

    def test_background_discovery_reuses_proven_article_and_bypasses_stale_search_cache(self):
        registry = Future()
        registry.set_result(ProviderDiscoveryOutcome([], 'PROVIDER_COOLDOWN',('hdrezka',),0))
        balancer = Future()
        balancer.set_result((None,{'status':'PROVIDER_COOLDOWN','error_count':0}))
        with patch.dict('os.environ',{'MOVIA_BACKGROUND_BULK':'1',
                         'MOVIA_BACKGROUND_TORRENT_LOOKUP':'0','MOVIA_CLOUD_MODE':'0'},clear=False), \
             patch.object(filler._FILL_PROVIDER_EXECUTOR,'submit',return_value=registry) as submit, \
             patch.object(filler._FILL_BALANCER_EXECUTOR,'submit',return_value=balancer), \
             patch.object(filler,'wait',return_value=({registry,balancer},set())):
            result = filler._process_row(self.row,1,1)
        self.assertEqual('provider_deferred',result['status'])
        self.assertTrue(submit.call_args.kwargs['force_refresh'])
        self.assertEqual(1,len(submit.call_args.kwargs['known_sources']))
        self.assertEqual('42',submit.call_args.kwargs['media_id'])


if __name__ == '__main__':
    unittest.main()
