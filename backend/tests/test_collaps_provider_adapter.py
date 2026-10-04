import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from collaps_provider_adapter import CollapsProviderAdapter, build_collaps_variant_tree
from provider_contract import ProviderRequest, ProviderSearchResult, flatten_variant_tree


class CollapsProviderAdapterTests(unittest.TestCase):
    def request(self, **kwargs):
        values = dict(media_id="77", title="Example", year=2024, media_type="movie")
        values.update(kwargs)
        return ProviderRequest(**values)

    def test_exact_catalog_identity_uses_media_id_and_rejects_title_or_year_rebind(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "catalog.db"
            conn = sqlite3.connect(db)
            conn.execute("CREATE TABLE movies(id INTEGER PRIMARY KEY,title TEXT,original_title TEXT,year INTEGER,media_type TEXT,imdb_id TEXT)")
            conn.execute("INSERT INTO movies VALUES(77,'Example','Example Original',2024,'movie','tt123')")
            conn.commit(); conn.close()
            adapter = CollapsProviderAdapter()
            result, error = adapter.exact_catalog_result(self.request(), db_path=db)
            self.assertIsNone(error)
            self.assertEqual("tt123", result.item_id)
            wrong, error = adapter.exact_catalog_result(self.request(title="Other"), db_path=db)
            self.assertIsNone(wrong); self.assertEqual("COLLAPS_IDENTITY_MISMATCH", error)
            wrong, error = adapter.exact_catalog_result(self.request(year=2023), db_path=db)
            self.assertIsNone(wrong); self.assertEqual("COLLAPS_IDENTITY_MISMATCH", error)

    def test_movie_builds_voice_folders_and_transport_leaves_without_quality_guess(self):
        request = self.request()
        html = '''<script>
        source: {"hls":"https://cdn.example/master.m3u8","dash":"https://cdn.example/master.mpd",
                 "audio":{"names":["Dub","Original"]},"cc":[]}
        </script>'''
        tree = build_collaps_variant_tree(html, "https://api.example", request)
        source = ProviderSearchResult(CollapsProviderAdapter.definition,"tt123","Example",2024,"tt123","77")
        article = __import__('provider_contract').ProviderArticle(CollapsProviderAdapter.definition,"tt123","Example",2024,"x","77")
        rows = flatten_variant_tree(article, tree, request)
        self.assertEqual(4, len(rows))
        self.assertEqual({"Dub","Original"}, {r["voice"] for r in rows})
        self.assertEqual({"hls","dash"}, {r["transport"] for r in rows})
        self.assertEqual({"Не указано"}, {r["quality"] for r in rows})
        self.assertEqual({0,1}, {r["audio_track_index"] for r in rows})
        self.assertTrue(all(r.get("logical_source_id") for r in rows))
        self.assertTrue(all(r.get("provider_item_id") for r in rows))
        self.assertEqual({"77"}, {r["catalog_media_id"] for r in rows})

    def test_series_tree_exposes_only_exact_episode(self):
        request = self.request(media_type="tv", season=2, episode=3)
        html = '''seasons:[
          {"season":1,"episodes":[{"episode":3,"hls":"https://cdn.example/wrong.m3u8","audio":{"names":["Wrong"]}}]},
          {"season":2,"episodes":[{"episode":2,"hls":"https://cdn.example/wrong2.m3u8","audio":{"names":["Wrong2"]}},
                                   {"episode":3,"hls":"https://cdn.example/right.m3u8","audio":{"names":["LostFilm","Original"]}}]}
        ]'''
        tree = build_collaps_variant_tree(html, "https://api.example", request)
        article = __import__('provider_contract').ProviderArticle(CollapsProviderAdapter.definition,"tt456","Example",2024,"x","77")
        rows = flatten_variant_tree(article, tree, request)
        self.assertEqual(2, len(rows))
        self.assertEqual({"https://cdn.example/right.m3u8"}, {r["url"] for r in rows})
        self.assertEqual({2}, {r["season"] for r in rows})
        self.assertEqual({3}, {r["episode"] for r in rows})
        self.assertEqual({"LostFilm","Original"}, {r["voice"] for r in rows})

    def test_adapter_uses_movia_logger_and_mirror_fallback(self):
        request = self.request()
        source = ProviderSearchResult(CollapsProviderAdapter.definition,"tt123","Example",2024,"tt123","77")
        calls=[]
        def fetch(url, headers):
            calls.append(url)
            if len(calls) < 2:
                return None, "HTTP_503"
            return 'source:{"hls":"https://cdn.example/master.m3u8","audio":{"names":["Dub"]}}', None
        with self.assertLogs("collaps_provider_adapter", level="DEBUG") as logs:
            tree, article, error = CollapsProviderAdapter().resolve_source(source, request, fetch_text=fetch)
        self.assertIsNone(error); self.assertIsNotNone(tree); self.assertIsNotNone(article)
        self.assertEqual(2, len(calls))
        self.assertTrue(any("mirror failed" in line for line in logs.output))
        self.assertTrue(any("VariantTree resolved" in line for line in logs.output))


if __name__ == '__main__': unittest.main()
