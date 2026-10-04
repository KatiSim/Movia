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

if __name__=='__main__': unittest.main()
