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

    def test_only_complete_media_playlist_establishes_episode_runtime(self):
        from media_content_probe import hls_duration_from_playlist
        playlist='#EXTM3U\n#EXTINF:6.25,\na.ts\n#EXTINF:3.75,\nb.ts\n#EXT-X-ENDLIST\n'
        self.assertEqual(10,hls_duration_from_playlist(playlist))
        self.assertIsNone(hls_duration_from_playlist(playlist.replace('#EXT-X-ENDLIST','')))
        self.assertIsNone(hls_duration_from_playlist('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\na.m3u8'))
        self.assertIsNone(hls_duration_from_playlist(playlist.replace('6.25','nan')))

    def test_verified_episode_manifest_rejects_minute_mp4_without_using_series_card_duration(self):
        request=ProviderRequest('159','Series',2008,season=1,episode=2,media_type='tv')
        source=ProviderSearchResult(DEF,'series/example','Series',2008,'article:159','series/example')
        meta={'hdrezka_episode_verified':True,'hdrezka_episode_evidence':'embedded-page'}
        base={'source':'HDRezka','voice':'TVShows','quality':'720p','season':1,'episode':2,'transport_metadata':meta}
        streams=[dict(base,url='https://cdn.example/full.m3u8'),dict(base,url='https://cdn.example/full480.m3u8',quality='480p'),dict(base,url='https://cdn.example/placeholder.mp4')]
        adapter=HDRezkaProviderAdapter(expected_duration=lambda _:None,measure=lambda *_:{'duration':60,'height':1080,'width':1920},measure_playlist=lambda *_:2880)
        with patch('hdrezka_provider_adapter._resolve_hdrezka',return_value=(streams,None)):
            tree,article,error=adapter.resolve_source(source,request)
        rows=flatten_variant_tree(article,tree,request)
        self.assertIsNone(error);self.assertEqual(2,len(rows));self.assertTrue(all(row['url'].endswith('.m3u8') for row in rows))
        self.assertEqual(2880000,rows[0]['transport_metadata']['expected_episode_duration_ms'])

    def test_real_short_episode_with_matching_manifest_remains_playable(self):
        request=ProviderRequest('159','Series',2008,season=1,episode=2,media_type='tv')
        source=ProviderSearchResult(DEF,'series/example','Series',2008,'article:159','series/example')
        base={'source':'HDRezka','voice':'TVShows','quality':'720p','season':1,'episode':2,'transport_metadata':{'hdrezka_episode_verified':True}}
        streams=[dict(base,url='https://cdn.example/short.m3u8'),dict(base,url='https://cdn.example/short480.m3u8',quality='480p'),dict(base,url='https://cdn.example/short.mp4')]
        adapter=HDRezkaProviderAdapter(expected_duration=lambda _:None,measure=lambda *_:{'duration':60,'height':720,'width':1280},measure_playlist=lambda *_:60)
        with patch('hdrezka_provider_adapter._resolve_hdrezka',return_value=(streams,None)):
            tree,article,error=adapter.resolve_source(source,request)
        self.assertIsNone(error);self.assertEqual(3,len(flatten_variant_tree(article,tree,request)))

    def test_mp4_substituted_for_hls_is_rejected_against_other_complete_qualities(self):
        request=ProviderRequest('159','Series',2008,season=1,episode=2,media_type='tv')
        source=ProviderSearchResult(DEF,'series/example','Series',2008,'article:159','series/example')
        base={'source':'HDRezka','voice':'TVShows','quality':'720p','season':1,'episode':2,'transport_metadata':{'hdrezka_episode_verified':True}}
        streams=[dict(base,url='https://cdn.example/fake.m3u8'),dict(base,url='https://cdn.example/full.m3u8'),dict(base,url='https://cdn.example/full480.m3u8',quality='480p')]
        def measure(url,_):
            return {'duration':60,'height':1080,'container':'mp4'} if 'fake' in url else {'duration':2880,'container':'hls'}
        adapter=HDRezkaProviderAdapter(expected_duration=lambda _:None,measure_playlist=measure)
        with patch('hdrezka_provider_adapter._resolve_hdrezka',return_value=(streams,None)):
            tree,article,error=adapter.resolve_source(source,request)
        rows=flatten_variant_tree(article,tree,request)
        self.assertIsNone(error);self.assertEqual(2,len(rows));self.assertFalse(any('fake' in row['url'] for row in rows))

    def test_conflicting_complete_playlists_do_not_fabricate_episode_runtime(self):
        request=ProviderRequest('159','Series',2008,season=1,episode=2,media_type='tv')
        source=ProviderSearchResult(DEF,'series/example','Series',2008,'article:159','series/example')
        base={'source':'HDRezka','voice':'TVShows','season':1,'episode':2,'transport_metadata':{'hdrezka_episode_verified':True}}
        streams=[dict(base,url=f'https://cdn.example/{group}{quality}.m3u8',quality=quality) for group in ('clip','full') for quality in ('480p','720p')]
        adapter=HDRezkaProviderAdapter(expected_duration=lambda _:None,measure_playlist=lambda url,_:{'duration':60 if 'clip' in url else 2880,'container':'hls'})
        with patch('hdrezka_provider_adapter._resolve_hdrezka',return_value=(streams,None)):
            tree,article,error=adapter.resolve_source(source,request)
        self.assertIsNone(tree)
        self.assertEqual('HDREZKA_EPISODE_CONTENT_UNVERIFIED',error)


class IndependentSegmentDimensionsTests(unittest.TestCase):
    def test_segment_dimensions_do_not_require_sample_duration(self):
        from types import SimpleNamespace
        from media_content_probe import _measure_video_sample
        response=SimpleNamespace(stdout='{"streams":[{"codec_type":"video","height":480,"width":854}],"format":{}}')
        with patch('media_content_probe.subprocess.run',return_value=response):
            self.assertEqual({"height":480,"width":854},_measure_video_sample(b"segment",require_duration=False))
            self.assertIsNone(_measure_video_sample(b"segment"))
    def test_audio_only_segment_never_fabricates_video_dimensions(self):
        from types import SimpleNamespace
        from media_content_probe import _measure_video_sample
        with patch('media_content_probe.subprocess.run',return_value=SimpleNamespace(stdout='{"streams":[{"codec_type":"audio"}]}')):
            self.assertIsNone(_measure_video_sample(b"segment",require_duration=False))
