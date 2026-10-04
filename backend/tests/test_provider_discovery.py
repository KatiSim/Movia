import json
import unittest

from provider_discovery import discover_provider_streams


class ProviderDiscoveryTests(unittest.TestCase):
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

        outcome = discover_provider_streams(
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

    def test_live_default_is_gated_until_filmix_search_contract_is_reverified(self):
        outcome = discover_provider_streams(
            title="Example", year=2024, media_id="77", media_type="movie",
        )
        self.assertEqual("PROVIDER_DISABLED", outcome.status)
        self.assertEqual([], outcome.streams)

    def test_filmix_is_fail_closed_for_series_until_episode_contract_is_verified(self):
        outcome = discover_provider_streams(
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

        outcome = discover_provider_streams(
            title="Example", year=2024, media_id="77", media_type="movie",
            fetch_text=fetch_text,
            fetch_post_form_text=lambda *_: self.fail("ambiguous search must not resolve article"),
        )
        self.assertEqual("AMBIGUOUS", outcome.status)
        self.assertEqual([], outcome.streams)


if __name__ == "__main__":
    unittest.main()
