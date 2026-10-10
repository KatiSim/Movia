import copy,unittest
from dataclasses import asdict,replace
from unittest.mock import patch
from hdrezka_provider_adapter import HDRezkaProviderAdapter,DEF
from provider_contract import ProviderRequest,ProviderSearchResult,flatten_variant_tree
from provider_discovery import _discover_hdrezka

class SavedArticleTests(unittest.TestCase):
    def setUp(self):
        self.request=ProviderRequest("42","Example",2024)
        self.source=ProviderSearchResult(DEF,"films/drama/42-example-2024","Example",2024,
            "https://rezka.ag/films/drama/42-example-2024.html","films/drama/42-example-2024")
        self.streams=[{"url":"https://cdn.example/film.mp4","voice":"Original","stream_key":"translator:1:rendition:480"}]
        with patch("hdrezka_provider_adapter._resolve_hdrezka",return_value=(self.streams,None)):
            tree,article,error=HDRezkaProviderAdapter().resolve_source(self.source,self.request)
        self.rows=flatten_variant_tree(article,tree,self.request)
    def test_proven_article_is_published_and_survives_sanitization(self):
        from stream_validation import sanitize_streams
        row=sanitize_streams(self.rows,require_source=True)[0]
        self.assertEqual(self.source.article_ref,row["reload_data"]["article_ref"])
        self.assertIsNotNone(HDRezkaProviderAdapter().saved_source(row,self.request))
    def test_reload_never_searches_and_keeps_logical_identity_after_url_rotation(self):
        fresh=[dict(self.streams[0],url="https://other.example/rotated.mp4")]
        with patch.object(HDRezkaProviderAdapter,"search",side_effect=AssertionError("search forbidden")), \
             patch("hdrezka_provider_adapter._resolve_hdrezka",return_value=(fresh,None)) as transport:
            result=_discover_hdrezka(title="Example",year=2024,media_id="42",known_sources=self.rows)
        self.assertEqual("OK",result.status)
        self.assertEqual(self.rows[0]["logical_source_id"],result.streams[0]["logical_source_id"])
        self.assertEqual(self.rows[0]["voice"],result.streams[0]["voice"])
        self.assertEqual(self.source.item_id,transport.call_args.args[0]["downloadLinkKey"])
    def test_wrong_media_year_episode_kind_or_article_is_rejected(self):
        adapter=HDRezkaProviderAdapter()
        for request in [replace(self.request,media_id="43"),replace(self.request,year=2023),
                        replace(self.request,season=1,episode=2,media_type="tv")]:
            self.assertIsNone(adapter.saved_source(self.rows[0],request))
        for changes in [{"article_ref":"https://evil.example/films/drama/42-example-2024.html"},
                        {"article_ref":"https://rezka.ag/films/drama/43-other.html"},
                        {"item_id":"hash:guess"},{"provider_id":"other"}]:
            row=copy.deepcopy(self.rows[0]);row["reload_data"].update(changes)
            self.assertIsNone(adapter.saved_source(row,self.request))
    def test_article_failure_does_not_turn_into_search_or_guessed_link(self):
        with patch.object(HDRezkaProviderAdapter,"search",side_effect=AssertionError("search forbidden")), \
             patch("hdrezka_provider_adapter._resolve_hdrezka",return_value=([], "HTTP_ERROR:500")):
            result=_discover_hdrezka(title="Example",year=2024,media_id="42",known_sources=self.rows)
        self.assertEqual("PROVIDER_ERROR",result.status)
        self.assertEqual([],result.streams)


    def test_older_native_article_header_requires_exact_scoped_provenance(self):
        from stream_validation import bind_stream_identity
        row=copy.deepcopy(self.rows[0]);row.pop("reload_data")
        row["headers"]={"Referer":self.source.article_ref}
        row["transport_metadata"]={"hdrezka_native_transport":True}
        row=bind_stream_identity([row],catalog_media_id="42",title="Example",year=2024,media_type="movie")[0]
        adapter=HDRezkaProviderAdapter()
        source=adapter.saved_source(row,self.request)
        self.assertEqual(self.source.item_id,source.item_id)
        for field,value in [("catalog_media_id","43"),("canonical_year",2023),
                            ("canonical_title","Other"),("canonical_media_type","tv")]:
            altered=copy.deepcopy(row);altered[field]=value
            self.assertIsNone(adapter.saved_source(altered,self.request))
        altered=copy.deepcopy(row);altered["transport_metadata"]={}
        self.assertIsNone(adapter.saved_source(altered,self.request))
        altered=copy.deepcopy(row);altered["headers"]={"Referer":"https://evil.example/films/drama/42-example-2024.html"}
        self.assertIsNone(adapter.saved_source(altered,self.request))
