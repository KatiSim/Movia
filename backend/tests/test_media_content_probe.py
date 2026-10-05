import unittest, sqlite3, tempfile
from pathlib import Path
from unittest.mock import patch
from media_content_probe import catalog_duration_seconds,duration_matches
from provider_contract import ProviderRequest,ProviderSearchResult,flatten_variant_tree
from hdrezka_provider_adapter import DEF,HDRezkaProviderAdapter

class MediaContentProbeTests(unittest.TestCase):
    def test_feature_duration_rejects_a_minute_placeholder(self):
        self.assertFalse(duration_matches(60,169*60))
    def test_real_short_films_are_not_rejected_by_arbitrary_minimum_duration(self):
        self.assertTrue(duration_matches(60,60))
    def test_non_numeric_measurement_never_fabricates_duration(self):
        self.assertFalse(duration_matches(None,169*60))
    def test_catalog_duration_requires_exact_identity_and_movie_type(self):
        with tempfile.TemporaryDirectory() as tmp:
            db=Path(tmp)/"catalog.db"
            with sqlite3.connect(db) as c:
                c.execute("CREATE TABLE movies(id INTEGER,title TEXT,original_title TEXT,year INTEGER,media_type TEXT,duration_minutes INTEGER)")
                c.execute("INSERT INTO movies VALUES(42,'Example','Original',2024,'movie',169)")
            self.assertEqual(169*60,catalog_duration_seconds(ProviderRequest('42','Example',2024),db))
            self.assertIsNone(catalog_duration_seconds(ProviderRequest('42','Other',2024),db))
            self.assertIsNone(catalog_duration_seconds(ProviderRequest('42','Example',2025),db))
    def test_series_card_duration_cannot_be_an_episode_duration(self):
        self.assertIsNone(catalog_duration_seconds(ProviderRequest('42','Example',2024,1,1,'tv')))
    def resolve(self,measure):
        source=ProviderSearchResult(DEF,'films/example','Example',2024,'article:42','films/example')
        request=ProviderRequest('42','Example',2024)
        rows=[{'url':'https://cdn.example/movie.mp4','voice':'Original','quality':'1080p'}]
        adapter=HDRezkaProviderAdapter(measure=measure,expected_duration=lambda _:169*60)
        with patch('hdrezka_provider_adapter._resolve_hdrezka',return_value=(rows,None)):
            tree,article,error=adapter.resolve_source(source,request)
        return flatten_variant_tree(article,tree,request) if tree else [],error
    def test_measured_wrong_content_is_not_a_playable_leaf(self):
        rows,error=self.resolve(lambda *_:{'duration':60,'height':1080,'width':1920})
        self.assertEqual([],rows)
        self.assertEqual('HDREZKA_NO_PLAYABLE_LEAVES',error)
    def test_measured_height_replaces_advertised_quality_without_voice_guess(self):
        rows,error=self.resolve(lambda *_:{'duration':169*60,'height':720,'width':1280})
        self.assertIsNone(error)
        self.assertEqual('720p',rows[0]['quality'])
        self.assertEqual('Original',rows[0]['voice'])
        self.assertEqual(720,rows[0]['transport_metadata']['measured_height'])
    def test_inconclusive_probe_is_not_reported_as_measured_evidence(self):
        rows,error=self.resolve(lambda *_:None)
        self.assertIsNone(error)
        self.assertEqual('1080p',rows[0]['quality'])
        self.assertNotIn('transport_metadata',rows[0])
