import unittest
from unittest.mock import patch
from dataclasses import replace
from provider_contract import ProviderDefinition, ProviderArticle, ProviderRequest, VariantFolder, VariantStream, flatten_variant_tree, ProviderSearchResult
from stream_validation import sanitize_streams
from hdrezka_provider_adapter import HDRezkaProviderAdapter, DEF
from hdrezka_transport import _rows, Translator

class StableProviderSourceIdentityTests(unittest.TestCase):
    provider = ProviderDefinition("movia:test", "Example provider", "native")
    article = ProviderArticle(provider, "article:1", "Example", 2024)
    request = ProviderRequest("77", "Example", 2024)

    def leaf(self, stream, request=None, article=None):
        return sanitize_streams(flatten_variant_tree(article or self.article, VariantFolder(children=[stream]), request or self.request), require_source=True)[0]

    def assert_ids_equal(self, a, b):
        for key in ("logical_source_id", "provider_item_id", "stream_id"):
            self.assertEqual(a[key], b[key], key)

    def test_measured_quality_and_language_do_not_change_authoritative_leaf_identity(self):
        before = VariantStream("https://cdn-a.example/signed.mp4?token=one", stream_key="translator:10|rendition:main", voice="Original", quality="Не указано")
        after = replace(before, url="https://cdn-b.example/new.mp4?token=two", voice="Localized studio label", quality="480p", language="ja", resolution="854x480")
        self.assert_ids_equal(self.leaf(before), self.leaf(after))

    def test_catalog_identity_episode_tracks_and_files_remain_distinct(self):
        base = VariantStream("https://cdn.example/a.mp4", stream_key="main", quality="720p")
        first = self.leaf(base)
        variants = [self.leaf(base, request=replace(self.request, media_id="88")), self.leaf(base, request=replace(self.request, year=2025)), self.leaf(base, request=replace(self.request, media_type="tv")), self.leaf(base, request=replace(self.request, is_trailer=True)), self.leaf(replace(base, audio_track_index=0)), self.leaf(replace(base, audio_track_index=1)), self.leaf(replace(base, video_track_index=1)), self.leaf(replace(base, file_index=0)), self.leaf(replace(base, file_index=1))]
        self.assertEqual(len(variants)+1, len({x['logical_source_id'] for x in [first, *variants]}))
        s1 = self.leaf(replace(base, season=1, episode=1), request=replace(self.request, media_type="tv", season=1, episode=1))
        s2 = self.leaf(replace(base, season=1, episode=2), request=replace(self.request, media_type="tv", season=1, episode=2))
        self.assertNotEqual(s1['logical_source_id'], s2['logical_source_id'])

    def test_fallback_leaf_is_order_independent_without_claiming_signed_reload(self):
        a = VariantStream("https://cdn.example/a.mp4", voice="A", quality="720p")
        b = VariantStream("https://cdn.example/b.mp4", voice="B", quality="1080p")
        def ids(children):return {x['url']:x['logical_source_id'] for x in flatten_variant_tree(self.article,VariantFolder(children=children),self.request)}
        self.assertEqual(ids([a,b]), ids([b,a]))
        self.assertNotEqual(self.leaf(a)['logical_source_id'], self.leaf(replace(a,url=a.url+'?token=new'))['logical_source_id'])

    def test_different_representation_keys_and_transport_remain_distinct(self):
        a=VariantStream("https://cdn.example/master.m3u8", stream_key="rendition:720", quality="720p", transport="hls")
        self.assertNotEqual(self.leaf(a)['logical_source_id'], self.leaf(replace(a,stream_key="rendition:1080",quality="1080p"))['logical_source_id'])
        self.assertNotEqual(self.leaf(a)['logical_source_id'], self.leaf(replace(a,transport="dash"))['logical_source_id'])

    def test_native_translator_key_survives_labels_signed_urls_and_reordering(self):
        t=Translator("123","10","Original",True)
        def rows(url,translator=t):return _rows({"url":url},translator,"https://rezka.ag/films/123.html","Movia",None,None,"embedded")
        a=rows('[720p]https://a.example/signed/100/200/video.m3u8?token=one')[0]
        b=rows('[720p]https://b.example/changed/100/200/video.m3u8?token=two',replace(t,voice="Renamed"))[0]
        self.assertEqual(a['stream_key'], b['stream_key'])
        self.assertNotEqual(a['stream_key'],rows('[720p]https://b.example/changed/100/200/video.m3u8?token=two',replace(t,translator_id="20"))[0]['stream_key'])
        self.assertNotEqual(a['stream_key'],rows('[1080p]https://a.example/signed/100/200/video.m3u8?token=one')[0]['stream_key'])

    def test_hdrezka_probe_and_inventory_order_do_not_change_public_ids(self):
        request=ProviderRequest("77","Example",2024);source=ProviderSearchResult(DEF,"films/123-example","Example",2024)
        streams=[{'url':'https://cdn.example/a.mp4','voice':'A','quality':'Не указано','stream_key':'translator:10|rendition:a'}, {'url':'https://cdn.example/b.mp4','voice':'B','quality':'Не указано','stream_key':'translator:20|rendition:b'}]
        def run(rows, measure):
            adapter=HDRezkaProviderAdapter(measure=measure, expected_duration=lambda _:7200)
            with patch('hdrezka_provider_adapter._resolve_hdrezka',return_value=(rows,None)):
                tree,article,error=adapter.resolve_source(source,request)
            self.assertIsNone(error)
            return {x['voice']:x for x in sanitize_streams(flatten_variant_tree(article,tree,request),require_source=True)}
        a=run(streams,lambda *args:None);b=run(list(reversed(streams)),lambda *args:{'duration':7200,'height':480})
        for voice in a:self.assert_ids_equal(a[voice],b[voice])
        self.assertEqual('480p',b['A']['quality'])

    def test_identity_encoding_does_not_alias_separator_in_provider_key(self):
        from provider_contract import _stable_id
        self.assertNotEqual(_stable_id('test:', 'a\x1fb', 'c'), _stable_id('test:', 'a', 'b\x1fc'))

class StableTorrentSourceIdentityTests(unittest.TestCase):
    def rewrite(self, rows):
        from torrent_provider_adapter import rewrite_torrent_rows_as_variant_tree
        return rewrite_torrent_rows_as_variant_tree(rows, ProviderRequest("77","Example",2024))

    def test_one_btih_survives_base32_encoding_and_tracker_churn(self):
        import base64
        hexhash="aabbccddeeff00112233445566778899aabbccdd"
        b32=base64.b32encode(bytes.fromhex(hexhash)).decode()
        row={'source':'Rutor','voice':'Dub','quality':'Не указано','title':'Example 2024'}
        a=self.rewrite([{**row,'url':'magnet:?xt=urn:btih:'+hexhash+'&tr=https%3A%2F%2Fa'}])[0]
        b=self.rewrite([{**row,'url':'magnet:?xt=urn:btih:'+b32+'&tr=https%3A%2F%2Fb','info_hash':b32,'quality':'1080p','voice':'MVO'}])[0]
        for key in ('logical_source_id','provider_item_id','stream_id','info_hash'):self.assertEqual(a[key],b[key],key)

    def test_file_selection_and_separate_releases_never_merge(self):
        row={'source':'Rutor','voice':'Dub','quality':'1080p','title':'Example 2024'}
        rows=[{**row,'url':'magnet:?xt=urn:btih:'+'a'*40+'&so=1'}, {**row,'url':'magnet:?xt=urn:btih:'+'a'*40+'&so=2'}, {**row,'url':'magnet:?xt=urn:btih:'+'b'*40+'&so=1'}]
        self.assertEqual(3,len({x['logical_source_id'] for x in self.rewrite(rows)}))

    def test_provider_and_inventory_order_are_stable_and_isolated(self):
        row={'source':'Rutor','voice':'Dub','quality':'1080p','title':'Example 2024','url':'magnet:?xt=urn:btih:'+'a'*40}
        other={**row,'source':'Other provider'}
        a=self.rewrite([row,other]);b=self.rewrite([other,row])
        self.assertEqual({x['stream_id'] for x in a},{x['stream_id'] for x in b})
        self.assertEqual(1,len(a))  # Common BTIH dedupe keeps one physical torrent.
        self.assertNotEqual(self.rewrite([row])[0]['logical_source_id'], self.rewrite([other])[0]['logical_source_id'])

class StableGatedZonaIdentityTests(unittest.TestCase):
    def test_reordered_provider_rows_preserve_article_and_every_real_leaf(self):
        from zona_provider_adapter import ZonaProviderAdapter, ZONA_PROVIDER
        source=ProviderSearchResult(ZONA_PROVIDER,"123","Example",2024)
        request=ProviderRequest("77","Example",2024)
        rows=[{'url':f'https://cdn.example/{i}.mp4','voice':'Studio','quality':'720p','source_type_id':9,'provider_content_id':str(i)} for i in range(600)]
        def resolve(items):
            lookup=type('L',(),{'status':'OK','streams':items})()
            with patch('zona_contract.resolve_zona_for_title',return_value=lookup):tree,article,error=ZonaProviderAdapter().resolve_source(source,request)
            self.assertIsNone(error)
            return article,flatten_variant_tree(article,tree,request)
        aa,a=resolve(rows);bb,b=resolve(list(reversed(rows)))
        self.assertEqual('123',aa.item_id);self.assertEqual(aa.item_id,bb.item_id)
        self.assertEqual(600,len(a))
        self.assertEqual({x['url']:x['stream_id'] for x in a},{x['url']:x['stream_id'] for x in b})
