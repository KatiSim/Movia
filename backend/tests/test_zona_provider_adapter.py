import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from provider_contract import ProviderRequest, flatten_variant_tree
from zona_provider_adapter import ZonaProviderAdapter, _real_quality


class ZonaProviderAdapterTests(unittest.TestCase):
    def request(self, **kwargs):
        values = dict(media_id='77', title='Example', year=2024, media_type='movie')
        values.update(kwargs)
        return ProviderRequest(**values)

    def test_exact_identity_rejects_wrong_title_and_year(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / 'catalog.db'
            conn = sqlite3.connect(db)
            conn.execute('CREATE TABLE movies(id INTEGER PRIMARY KEY,title TEXT,original_title TEXT,year INTEGER,media_type TEXT,tmdb_id INTEGER)')
            conn.execute("INSERT INTO movies VALUES(77,'Example','Example Original',2024,'movie',123)")
            conn.commit(); conn.close()
            adapter = ZonaProviderAdapter()
            found, error = adapter.exact_catalog_result(self.request(), db_path=db)
            self.assertIsNone(error); self.assertEqual('123', found.item_id)
            found, error = adapter.exact_catalog_result(self.request(title='Other'), db_path=db)
            self.assertIsNone(found); self.assertEqual('ZONA_IDENTITY_MISMATCH', error)
            found, error = adapter.exact_catalog_result(self.request(year=2023), db_path=db)
            self.assertIsNone(found); self.assertEqual('ZONA_IDENTITY_MISMATCH', error)

    def test_quality_never_invents_hq_lq_resolution(self):
        self.assertEqual('Не указано', _real_quality('HQ'))
        self.assertEqual('Не указано', _real_quality('LQ'))
        self.assertEqual('Не указано', _real_quality('MEDIUM'))
        self.assertEqual('720p', _real_quality('HD 720'))
        self.assertEqual('1080p', _real_quality('1080p'))

    def test_stream_rows_become_variant_tree_with_movia_identity(self):
        adapter = ZonaProviderAdapter()
        request = self.request()
        source = type('S', (), {'item_id':'123','title':'Example','year':2024,'article_ref':'77','content_ref':'Example Original'})()
        lookup = type('L', (), {
            'status':'OK','suggestions':1,'source_refs':2,'errors':[],
            'streams':[
                {'url':'https://cdn.example/a.m3u8','voice':'Dub','quality':'HQ','source_type_id':9,'provider_content_id':'zona-abc','headers':{'Referer':'https://example/'},'audio_track_index':0,'reload_data':{'id':1}},
                {'url':'https://cdn.example/b.m3u8','voice':'Original','quality':'720p','source_type_id':9,'provider_content_id':'zona-abc','audio_track_index':1},
            ]
        })()
        with patch('zona_contract.resolve_zona_for_title', return_value=lookup):
            tree, article, error = adapter.resolve_source(source, request)
        self.assertIsNone(error)
        rows = flatten_variant_tree(article, tree, request)
        self.assertEqual(2, len(rows))
        self.assertEqual({'Dub','Original'}, {r['voice'] for r in rows})
        self.assertEqual({'Не указано','720p'}, {r['quality'] for r in rows})
        self.assertEqual({'77'}, {r['catalog_media_id'] for r in rows})
        self.assertTrue(all(r.get('logical_source_id') for r in rows))
        self.assertTrue(all(r.get('provider_item_id') for r in rows))
        self.assertTrue(any(r.get('reload_supported') for r in rows))

    def test_series_tree_is_exact_episode_only(self):
        adapter = ZonaProviderAdapter()
        request = self.request(media_type='tv', season=2, episode=3)
        source = type('S', (), {'item_id':'123','title':'Example','year':2024,'article_ref':'77','content_ref':''})()
        lookup = type('L', (), {'status':'OK','suggestions':1,'source_refs':1,'errors':[], 'streams':[
            {'url':'https://cdn.example/s2e3.m3u8','voice':'LostFilm','quality':'1080p','source_type_id':15,'season':2,'episode':3}
        ]})()
        with patch('zona_contract.resolve_zona_for_title', return_value=lookup):
            tree, article, error = adapter.resolve_source(source, request)
        rows = flatten_variant_tree(article, tree, request)
        self.assertEqual(1, len(rows))
        self.assertEqual(2, rows[0]['season']); self.assertEqual(3, rows[0]['episode'])
        self.assertEqual('LostFilm', rows[0]['voice'])

if __name__ == '__main__': unittest.main()
