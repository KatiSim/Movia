import json
import unittest
from unittest.mock import patch

from provider_discovery import discover_provider_streams


class ProviderDiscoveryTests(unittest.TestCase):
    def filmix(self, **kwargs):
        with patch.dict("os.environ", {"MOVIA_ENABLE_FILMIX_CLEAN_PROVIDER": "1"}, clear=False):
            return discover_provider_streams(**kwargs)

    def test_collaps_contract_is_gated_and_returns_variant_tree_rows_when_enabled(self):
        from collaps_provider_adapter import CollapsProviderAdapter
        from provider_contract import ProviderArticle, ProviderSearchResult, VariantFolder, VariantStream
        selected = ProviderSearchResult(CollapsProviderAdapter.definition, "tt123", "Example", 2024, "tt123", "77")
        article = ProviderArticle(CollapsProviderAdapter.definition, "tt123", "Example", 2024, "article", "77")
        tree = VariantFolder(children=(VariantFolder(voice="Dub", children=(VariantStream(
            url="https://cdn.example/master.m3u8", stream_key="hls|audio:0", voice="Dub",
            quality="Не указано", headers={"Referer":"https://api.example/"}, transport="hls", audio_track_index=0,
        ),)),))
        with patch.dict("os.environ", {
            "MOVIA_ENABLE_COLLAPS_PROVIDER_CONTRACT": "1",
            "MOVIA_ENABLE_FILMIX_CLEAN_PROVIDER": "0",
            "MOVIA_ENABLE_OCTOPUS_DISCOVERY_ONLY": "0",
        }, clear=False), \
                patch.object(CollapsProviderAdapter, "exact_catalog_result", return_value=(selected, None)), \
                patch.object(CollapsProviderAdapter, "resolve_source", return_value=(tree, article, None)):
            outcome = discover_provider_streams(
                title="Example", year=2024, media_id="77", media_type="movie",
                fetch_text=lambda *_: self.fail("fixture resolve_source is patched"),
            )
        self.assertEqual("OK", outcome.status)
        self.assertEqual(("collaps",), outcome.providers)
        self.assertEqual(1, len(outcome.streams))
        self.assertEqual("Dub", outcome.streams[0]["voice"])
        self.assertEqual("77", outcome.streams[0]["catalog_media_id"])
        self.assertTrue(outcome.streams[0].get("logical_source_id"))

    def test_filmix_exact_title_year_returns_all_variant_leaves(self):
        calls = []

        def fetch_text(url, headers):
            calls.append(("GET", url))
            if "partner_api/list" in url:
                return json.dumps({"items": [
                    {"id": 1, "title": "Example", "year": 2023, "category": "movie"},
                    {"id": 2, "title": "Example", "year": 2024, "category": "movie"},
                    {"id": 3, "title": "Example 2", "year": 2024, "category": "movie"},
                ]}), None
            self.assertEqual("https://filmix.ac/play/2", url)
            return "<html>filmix</html>", None

        def fetch_post(url, headers, form):
            calls.append(("POST", url))
            self.assertEqual({"post_id": "2", "showfull": "true"}, form)
            return json.dumps({"message": {"translations": {"video": {
                "Dub": {
                    "1080p": "https://cdn.example/dub-1080.m3u8",
                    "720p": "https://cdn.example/dub-720.m3u8",
                },
                "Original": "https://cdn.example/original-480.mp4",
            }}}}), None

        outcome = self.filmix(
            title="Example",
            original_title="Example",
            year=2024,
            media_id="77",
            media_type="movie",
            fetch_text=fetch_text,
            fetch_post_form_text=fetch_post,
        )

        self.assertEqual("OK", outcome.status)
        self.assertEqual(3, len(outcome.streams))
        self.assertEqual({"Dub", "Original"}, {row["voice"] for row in outcome.streams})
        self.assertEqual({"1080p", "720p", "480p"}, {row["quality"] for row in outcome.streams})
        self.assertEqual({"77"}, {row["catalog_media_id"] for row in outcome.streams})
        self.assertEqual(("filmix",), outcome.providers)

    def test_live_default_is_gated_until_provider_contracts_are_reverified(self):
        with patch.dict("os.environ", {
            "MOVIA_ENABLE_FILMIX_CLEAN_PROVIDER": "0",
            "MOVIA_ENABLE_OCTOPUS_DISCOVERY_ONLY": "0",
        }, clear=False):
            outcome = discover_provider_streams(
                title="Example", year=2024, media_id="77", media_type="movie",
            )
        self.assertEqual("PROVIDER_DISABLED", outcome.status)
        self.assertEqual([], outcome.streams)

    def test_filmix_is_fail_closed_for_series_until_episode_contract_is_verified(self):
        outcome = self.filmix(
            title="Series",
            year=2024,
            media_id="88",
            media_type="tv",
            season=1,
            episode=2,
            fetch_text=lambda *_: self.fail("series must not invoke Filmix yet"),
            fetch_post_form_text=lambda *_: self.fail("series must not invoke Filmix yet"),
        )
        self.assertEqual("UNSUPPORTED_SERIES", outcome.status)
        self.assertEqual([], outcome.streams)

    def test_ambiguous_exact_matches_do_not_guess_article(self):
        def fetch_text(url, headers):
            return json.dumps({"items": [
                {"id": 1, "title": "Example", "year": 2024, "category": "movie"},
                {"id": 2, "title": "Example", "year": 2024, "category": "movie"},
            ]}), None

        outcome = self.filmix(
            title="Example", year=2024, media_id="77", media_type="movie",
            fetch_text=fetch_text,
            fetch_post_form_text=lambda *_: self.fail("ambiguous search must not resolve article"),
        )
        self.assertEqual("AMBIGUOUS", outcome.status)
        self.assertEqual([], outcome.streams)


    def test_octopus_discovery_only_does_not_fabricate_stream(self):
        search_html = """<div class='top__slider_div__item'><a href='/film/42-example-2024.html'><span>Example (2024)</span></a></div>"""
        article_html = """<div class='full-story_iframe-block'><iframe src='https://player.example/movie/abc/iframe'></iframe></div>"""
        def fetch(url, headers):
            return (article_html if '42-example' in url else search_html), None
        with patch.dict("os.environ", {
            "MOVIA_ENABLE_FILMIX_CLEAN_PROVIDER": "0",
            "MOVIA_ENABLE_OCTOPUS_DISCOVERY_ONLY": "1",
        }, clear=False):
            outcome = discover_provider_streams(
                title="Example", year=2024, media_id="77", media_type="movie",
                fetch_text=fetch,
            )
        self.assertEqual("PLAYBACK_DECODER_REQUIRED", outcome.status)
        self.assertEqual([], outcome.streams)
        self.assertEqual(("octopus",), outcome.providers)



if __name__ == "__main__":
    unittest.main()
