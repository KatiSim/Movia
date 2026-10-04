import json
import unittest
from pathlib import Path
from unittest.mock import patch

import zona_legacy_adapters
import filmix_provider_adapter
from filmix_provider_adapter import FilmixProviderAdapter
from provider_contract import ProviderRequest, flatten_variant_tree


class FilmixProviderAdapterTests(unittest.TestCase):
    def setUp(self):
        self.adapter = FilmixProviderAdapter()

    def movie_payload(self):
        return json.dumps({
            "message": {
                "translations": {
                    "video": {
                        "Дубляж": {
                            "1080p": "https://cdn.example.test/movie-1080.m3u8",
                            "720p": "https://cdn.example.test/movie-720.m3u8",
                        },
                        "Original": "https://cdn.example.test/movie-original-480.mp4",
                    },
                    "trailers": {"Trailer": "https://cdn.example.test/trailer.mp4"},
                }
            }
        })

    def test_search_uses_decoded_lazy_3466_contract(self):
        calls = []

        def fetch_text(url, headers):
            calls.append((url, dict(headers)))
            return json.dumps({
                "items": [
                    {
                        "id": 12345,
                        "title": "Интерстеллар",
                        "year": "2014",
                        "poster": "https://img.example/poster.jpg",
                        "ratingImdb": "8.7",
                        "quality": "BDRip",
                        "category": "movie",
                    },
                    {
                        "id": 999,
                        "title": "Excluded",
                        "year": "2024",
                        "category": "s87",
                    },
                    {"id": "", "title": "Broken", "category": "movie"},
                ]
            }, ensure_ascii=False), None

        results, error = self.adapter.search(
            "Интерстеллар 2014",
            fetch_text=fetch_text,
            page=2,
            request_user_agent="Filmix-UA",
        )

        self.assertIsNone(error)
        self.assertEqual(1, len(results))
        self.assertEqual("12345", results[0].item_id)
        self.assertEqual("Интерстеллар", results[0].title)
        self.assertEqual(2014, results[0].year)
        self.assertEqual("12345", results[0].article_ref)
        self.assertEqual(
            "http://5.61.56.18/partner_api/list?page=2&sort=date&search="
            "%D0%98%D0%BD%D1%82%D0%B5%D1%80%D1%81%D1%82%D0%B5%D0%BB%D0%BB%D0%B0%D1%80%202014",
            calls[0][0],
        )
        self.assertEqual("Filmix-UA", calls[0][1]["User-Agent"])

    def test_search_rejects_invalid_payload_without_inventing_results(self):
        results, error = self.adapter.search(
            "Example",
            fetch_text=lambda url, headers: (json.dumps({"unexpected": []}), None),
        )
        self.assertEqual([], results)
        self.assertEqual("filmix-search:NO_ITEMS", error)

    def test_adapter_runtime_has_no_zona_legacy_dependency(self):
        source = Path(filmix_provider_adapter.__file__).read_text()
        self.assertNotIn("zona_legacy_adapters", source)

    def test_movie_tree_matches_verified_flat_legacy_contract(self):
        gets = []
        posts = []

        def fetch_text(url, headers):
            gets.append((url, dict(headers)))
            return "<html><body>filmix page</body></html>", None

        def fetch_post_form(url, headers, form):
            posts.append((url, dict(headers), dict(form)))
            return self.movie_payload(), None

        source = {
            "videoSourceTypeId": 3,
            "downloadLinkKey": "12345-extra",
            "id": 77,
        }
        request = ProviderRequest(media_id="m1", title="Example", year=2024)

        with patch.object(zona_legacy_adapters.time, "time", return_value=1700000000.123),              patch("filmix_provider_adapter.time.time", return_value=1700000000.123):
            tree, article, error = self.adapter.resolve_source(
                source,
                request,
                fetch_text=fetch_text,
                fetch_post_form_text=fetch_post_form,
                request_user_agent="Filmix-UA",
            )
            legacy_rows, legacy_error = zona_legacy_adapters.resolve_local_source(
                source,
                fetch_text=lambda url, headers: ("<html><body>filmix page</body></html>", None),
                fetch_post_form_text=lambda url, headers, form: (self.movie_payload(), None),
                request_user_agent="Filmix-UA",
            )

        self.assertIsNone(error)
        self.assertIsNone(legacy_error)
        rows = flatten_variant_tree(article, tree, request)
        self.assertEqual(
            sorted((r["voice"], r["quality"], r["url"]) for r in legacy_rows),
            sorted((r["voice"], r["quality"], r["url"]) for r in rows),
        )
        self.assertTrue(all(r["source_type_id"] == 3 for r in rows))
        self.assertEqual("https://filmix.ac/play/12345", gets[0][0])
        self.assertEqual("https://filmix.ac/api/movies/player-data?t=1700000000123", posts[0][0])
        self.assertEqual({"post_id": "12345", "showfull": "true"}, posts[0][2])
        self.assertFalse(any("trailer" in r["url"] for r in rows))

    def test_series_tree_encodes_exact_episode_before_flatten(self):
        def fetch_text(url, headers):
            return "page", None

        def fetch_post_form(url, headers, form):
            return json.dumps({
                "message": {"translations": {"video": {
                    "LostFilm": {
                        "1080p": "https://cdn.example.test/show-s02e03-1080.m3u8",
                        "720p": "https://cdn.example.test/show-s02e03-720.m3u8",
                    }
                }}}
            }), None

        request = ProviderRequest(
            media_id="series-1", title="Example Series", year=2024,
            season=2, episode=3, media_type="series",
        )
        tree, article, error = self.adapter.resolve_source(
            {"videoSourceTypeId": 3, "downloadLinkKey": "https://filmix.ac/play/555-some-title"},
            request,
            fetch_text=fetch_text,
            fetch_post_form_text=fetch_post_form,
        )
        self.assertIsNone(error)
        self.assertEqual(2, tree.children[0].season)
        self.assertEqual(3, tree.children[0].children[0].episode)

        rows = flatten_variant_tree(article, tree, request)
        self.assertEqual(2, len(rows))
        self.assertEqual({"LostFilm"}, {r["voice"] for r in rows})
        self.assertEqual({2}, {r["season"] for r in rows})
        self.assertEqual({3}, {r["episode"] for r in rows})

        wrong_request = ProviderRequest(
            media_id="series-1", title="Example Series", year=2024,
            season=2, episode=4, media_type="series",
        )
        self.assertEqual([], flatten_variant_tree(article, tree, wrong_request))

    def test_opaque_value_fails_without_fabricating_stream(self):
        tree, article, error = self.adapter.resolve_source(
            {"videoSourceTypeId": 3, "downloadLinkKey": "777"},
            ProviderRequest(media_id="m1", title="Example"),
            fetch_text=lambda url, headers: ("page", None),
            fetch_post_form_text=lambda url, headers, form: (
                json.dumps({"message": {"translations": {"video": {
                    "Dub": "#opaque-packed-filmix-value"
                }}}}), None
            ),
        )
        self.assertIsNone(tree)
        self.assertIsNone(article)
        self.assertEqual("filmix:OPAQUE_LINK_DECODER_REQUIRED", error)

    def test_page_mirror_fallback_is_preserved(self):
        gets = []
        posts = []

        def fetch_text(url, headers):
            gets.append(url)
            if url.startswith("https://filmix.ac/"):
                return None, "HTTP_ERROR:404"
            return "page", None

        def fetch_post_form(url, headers, form):
            posts.append(url)
            return json.dumps({"message": {"translations": {"video": {
                "Dub": "https://cdn.example.test/movie.mp4"
            }}}}), None

        tree, article, error = self.adapter.resolve_source(
            {"videoSourceTypeId": 3, "downloadLinkKey": "999"},
            ProviderRequest(media_id="m1", title="Example"),
            fetch_text=fetch_text,
            fetch_post_form_text=fetch_post_form,
        )

        self.assertIsNone(error)
        self.assertIsNotNone(tree)
        self.assertIsNotNone(article)
        self.assertEqual([
            "https://filmix.ac/play/999",
            "http://filmixapp.cyou/play/999",
        ], gets[:2])
        self.assertTrue(posts[0].startswith("http://filmixapp.cyou/api/movies/player-data?t="))

    def test_playback_headers_are_leaf_specific_not_provider_discovery_profile(self):
        tree, article, error = self.adapter.resolve_source(
            {"videoSourceTypeId": 3, "downloadLinkKey": "101"},
            ProviderRequest(media_id="m1", title="Example"),
            fetch_text=lambda url, headers: ("page", None),
            fetch_post_form_text=lambda url, headers, form: (
                json.dumps({"message": {"translations": {"video": {
                    "Dub": "https://cdn.example.test/movie.mp4"
                }}}}), None
            ),
            request_user_agent="Filmix-UA",
        )
        self.assertIsNone(error)
        rows = flatten_variant_tree(article, tree, ProviderRequest(media_id="m1", title="Example"))
        self.assertEqual("Filmix-UA", rows[0]["headers"]["User-Agent"])
        self.assertIn("Referer", rows[0]["headers"])
        self.assertIn("Origin", rows[0]["headers"])
        self.assertNotIn("X-Requested-With", rows[0]["headers"])


if __name__ == "__main__":
    unittest.main()
