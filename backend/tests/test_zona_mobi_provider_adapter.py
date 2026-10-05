import unittest
from unittest.mock import patch
from provider_contract import ProviderRequest, flatten_variant_tree
from zona_mobi_provider_adapter import ZonaMobiProviderAdapter, client_timestamp, duration_matches


class ZonaMobiTests(unittest.TestCase):
    def setUp(self):
        self.request = ProviderRequest("catalog-1", "Example", 2008)
        self.item = dict(mobi_link_id=42, name_id="example", name_rus="Example", year=2008, serial=False)
        self.article = dict(self.item, release_date={"year": 2008}, runtime={"value": 90})
        self.episode = dict(season=1, episode=2, mobi_link_id=77)
        self.video = {"url": "https://media.test/hq.mp4", "lqUrl": "https://media.test/lq.mp4",
                      "lqHlsUrl": "https://media.test/broken.m3u8", "mqUrl": None}
        self.calls = []

    def fetch(self, path, params=None):
        self.calls.append((path, params))
        if path.startswith("search/"): return {"items": [self.item]}, {}
        if "/season/" in path:
            return {"season_number": 1, "episodes": {"items": [self.episode]}}, {}
        if path == "video/": return {}, {"Date": "Mon, 05 Oct 2026 16:00:00 GMT"}
        if path.startswith("video/"): return self.video, {}
        return self.article, {}

    def adapter(self):
        return ZonaMobiProviderAdapter(fetch=self.fetch, user_agent="Test", probe=lambda u, expected: None if "broken" in u else {"transport": "direct", "duration": expected, "height": 720 if "hq" in u else 480})

    def test_preserves_real_tiers_without_fake_voice_or_height(self):
        a = self.adapter(); rows, error = a.search(self.request)
        self.assertIsNone(error)
        tree, article, error = a.resolve_source(rows[0], self.request)
        self.assertIsNone(error)
        leaves = flatten_variant_tree(article, tree, self.request)
        self.assertEqual(len(leaves), 2)
        self.assertEqual({x["quality"] for x in leaves}, {"720p", "480p"})
        self.assertTrue(all(not x.get("audio_track_index") for x in leaves))
        self.assertTrue(all(x.get("voice") != "Дубляж" for x in leaves))
        self.assertTrue(all(x["catalog_media_id"] == "catalog-1" for x in leaves))

    def test_foreign_year_not_selected(self):
        self.item["year"] = 2009
        self.assertEqual(self.adapter().search(self.request)[0], [])

    def test_foreign_type_not_selected(self):
        self.item["serial"] = True
        self.assertEqual(self.adapter().search(self.request)[0], [])

    def test_unknown_year_not_selected(self):
        self.item.pop("year")
        self.assertEqual(self.adapter().search(self.request)[0], [])

    def test_changed_article_identity_fails_closed(self):
        a = self.adapter(); rows, _ = a.search(self.request)
        self.article["mobi_link_id"] = 99
        self.assertEqual(a.resolve_source(rows[0], self.request)[2], "ZONA_MOBI_IDENTITY_MISMATCH")
        self.assertFalse(any(x[0].startswith("video/") for x in self.calls))

    def series(self):
        self.item["serial"] = self.article["serial"] = True
        return ProviderRequest("catalog-1", "Example", 2008, 1, 2, "tv")

    def test_exact_episode_id_selected(self):
        q = self.series(); a = self.adapter(); rows, _ = a.search(q)
        tree, article, error = a.resolve_source(rows[0], q)
        self.assertIsNone(error)
        self.assertTrue(any(x[0] == "video/77" for x in self.calls))
        leaves = flatten_variant_tree(article, tree, q)
        self.assertTrue(all((x["season"], x["episode"]) == (1, 2) for x in leaves))

    def test_wrong_episode_no_video(self):
        q = self.series(); self.episode["episode"] = 3
        a = self.adapter(); rows, _ = a.search(q)
        self.assertEqual(a.resolve_source(rows[0], q)[2], "ZONA_MOBI_EPISODE_MISMATCH")

    def test_duplicate_episode_ambiguous(self):
        q = self.series(); old = self.fetch
        def fetch(path, params=None):
            if "/season/" in path: return {"season_number": 1, "episodes": {"items": [self.episode, self.episode]}}, {}
            return old(path, params)
        a = ZonaMobiProviderAdapter(fetch=fetch, user_agent="Test", probe=lambda u, expected: {"transport": "direct", "duration": expected, "height": 720})
        rows, _ = a.search(q)
        self.assertEqual(a.resolve_source(rows[0], q)[2], "ZONA_MOBI_EPISODE_MISMATCH")

    def test_series_without_coordinates_never_searches(self):
        q = ProviderRequest("catalog-1", "Example", 2008, media_type="tv")
        self.assertEqual(self.adapter().search(q)[1], "EXACT_EPISODE_REQUIRED")
        self.assertEqual(self.calls, [])

    def test_minute_placeholder_cannot_count_as_feature_movie(self):
        self.assertFalse(duration_matches(60, 98 * 60))
        self.assertTrue(duration_matches(96 * 60, 98 * 60))
        self.assertFalse(duration_matches(float("nan"), 98 * 60))
        self.assertFalse(duration_matches(60, None))

    def test_short_film_is_checked_against_its_own_duration(self):
        self.assertTrue(duration_matches(60, 60))
        self.assertFalse(duration_matches(60, 300))

    def test_clock_matches_java_hash_known_value(self):
        self.assertEqual(client_timestamp(1700000000, "Test"), 1700000000232)

    def test_registry_keeps_other_provider_leaves(self):
        from provider_discovery import discover_provider_streams
        env = {"MOVIA_ENABLE_ZONA_MOBI_PROVIDER_CONTRACT": "1",
               "MOVIA_ENABLE_HDREZKA_PROVIDER_CONTRACT": "0",
               "MOVIA_ENABLE_COLLAPS_PROVIDER_CONTRACT": "0",
               "MOVIA_ENABLE_ZONA_PROVIDER_CONTRACT": "0",
               "MOVIA_ENABLE_FILMIX_CLEAN_PROVIDER": "0"}
        with patch.dict("os.environ", env), patch("provider_discovery.ZonaMobiProviderAdapter", return_value=self.adapter()):
            outcome = discover_provider_streams(title="Example", year=2008, media_id="catalog-1")
        self.assertEqual(len(outcome.streams), 2)


if __name__ == "__main__":
    unittest.main()
