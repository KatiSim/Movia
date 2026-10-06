import unittest
from unittest.mock import patch
from provider_contract import ProviderRequest
from hdrezka_provider_adapter import HDRezkaProviderAdapter, search_exact

HTML='''<div class="b-content__inline_item"><div class="b-content__inline_item-link"><a href="https://rezka.ag/series/thriller/17109-ochen-strannye-dela-2016-latest.html">Очень странные дела / Загадочные события</a><div>2016-2026, США, Триллеры</div></div></div>'''

class HDRezkaProviderAdapterTests(unittest.TestCase):
    def test_provider_slash_alias_is_exact_not_fuzzy(self):
        with patch('hdrezka_provider_adapter._get', return_value=(HTML,None)):
            rows,error=search_exact('Stranger Things',2016,accepted_titles=('Очень странные дела','Stranger Things'))
        self.assertIsNone(error);self.assertEqual(1,len(rows));self.assertEqual(2016,rows[0].year)
        with patch('hdrezka_provider_adapter._get', return_value=(HTML,None)):
            rows,_=search_exact('Очень странно',2016,accepted_titles=('Очень странно',))
        self.assertEqual([],rows)

    def test_adapter_search_unions_exact_title_and_original_alias(self):
        req=ProviderRequest(media_id='424',title='Очень странные дела',year=2016,media_type='tv',season=1,episode=1)
        with patch('hdrezka_provider_adapter._get', return_value=(HTML,None)):
            rows,error=HDRezkaProviderAdapter().search(req,aliases=('Stranger Things',))
        self.assertIsNone(error);self.assertEqual(1,len(rows))

    def test_search_does_not_hide_extra_exact_articles_after_thirty_results(self):
        html=''.join(HTML.replace('17109-',str(n)+'-') for n in range(31))
        with patch('hdrezka_provider_adapter._get',return_value=(html,None)):
            rows,_=search_exact('Очень странные дела',2016)
        self.assertEqual(31,len(rows))

    def test_known_year_rejects_search_rows_without_year_evidence(self):
        without_year=HTML.replace('2016-2026, США, Триллеры','США, Триллеры')
        with patch('hdrezka_provider_adapter._get',return_value=(without_year,None)):
            rows,_=search_exact('Очень странные дела',2016)
        self.assertEqual([],rows)

    def test_movie_request_rejects_same_title_series_article(self):
        req=ProviderRequest('42','Очень странные дела',2016,media_type='movie')
        with patch('hdrezka_provider_adapter._get',return_value=(HTML,None)):
            rows,_=HDRezkaProviderAdapter().search(req)
        self.assertEqual([],rows)

    def test_native_adapter_keeps_more_than_512_real_leaves_and_supports_reload(self):
        from provider_contract import ProviderSearchResult,flatten_variant_tree
        from hdrezka_provider_adapter import DEF
        request=ProviderRequest('42','Example',2024)
        source=ProviderSearchResult(DEF,'films/example','Example',2024,'article:42','films/example')
        streams=[{'url':f'https://cdn.example/{n}.mp4','voice':f'Voice {n}','quality':'720p'} for n in range(600)]
        with patch('hdrezka_provider_adapter._resolve_hdrezka',return_value=(streams,None)):
            tree,article,error=HDRezkaProviderAdapter().resolve_source(source,request)
        self.assertIsNone(error)
        rows=flatten_variant_tree(article,tree,request)
        self.assertEqual(600,len(rows))
        self.assertTrue(all(row['reload_supported'] for row in rows))

    def test_slow_measurements_bound_queue_without_truncating_inventory(self):
        from concurrent.futures import ThreadPoolExecutor
        from threading import BoundedSemaphore,Event
        from time import monotonic
        from provider_contract import ProviderSearchResult,flatten_variant_tree
        from hdrezka_provider_adapter import DEF
        request=ProviderRequest('42','Example',2024)
        source=ProviderSearchResult(DEF,'films/example','Example',2024,'article:42','films/example')
        streams=[{'url':f'https://cdn.example/{n}.mp4','voice':f'Voice {n}','quality':'720p'} for n in range(600)]
        gate=Event(); slots=BoundedSemaphore(3); executor=ThreadPoolExecutor(max_workers=1)
        futures=[]
        original_submit=executor.submit
        def submit(*args,**kwargs):
            future=original_submit(*args,**kwargs); futures.append(future); return future
        def slow_measure(*args):
            gate.wait(10); return None
        try:
            with patch('hdrezka_provider_adapter._CONTENT_PROBES',executor), patch('hdrezka_provider_adapter._CONTENT_PROBE_SLOTS',slots), patch.object(executor,'submit',side_effect=submit), patch('hdrezka_provider_adapter._resolve_hdrezka',return_value=(streams,None)):
                started=monotonic()
                tree,article,error=HDRezkaProviderAdapter(measure=slow_measure,expected_duration=lambda _:7200).resolve_source(source,request)
                elapsed=monotonic()-started
            self.assertIsNone(error)
            self.assertEqual(600,len(flatten_variant_tree(article,tree,request)))
            self.assertEqual(3,len(futures))
            self.assertEqual(2,sum(f.cancelled() for f in futures))
            self.assertLess(elapsed,4)
        finally:
            gate.set(); executor.shutdown(wait=True,cancel_futures=True)
        acquired=[slots.acquire(blocking=False) for _ in range(4)]
        self.assertEqual([True,True,True,False],acquired)
        for ok in acquired:
            if ok:slots.release()

if __name__=='__main__': unittest.main()
