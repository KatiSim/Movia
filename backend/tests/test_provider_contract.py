import unittest

from stream_validation import sanitize_streams
from database import _direct_stream_variant_identity

from provider_contract import (
    DeferredVariantLoader,
    ProviderArticle,
    ProviderDefinition,
    ProviderRequest,
    ProviderRequestProfile,
    VariantFolder,
    VariantStream,
    flatten_variant_tree,
)


class CountingLoader(DeferredVariantLoader):
    def __init__(self, result):
        self.result = result
        self.calls = 0

    def load(self, folder, request):
        self.calls += 1
        return self.result


class ProviderContractTests(unittest.TestCase):
    def definition(self):
        return ProviderDefinition(
            provider_id="lazy:hdrezka",
            name="HDRezka",
            family="lazy",
            enabled=True,
            request_profile=ProviderRequestProfile(
                headers={"Referer": "https://provider.example/"},
                user_agent="Movia fixture",
            ),
        )

    def article(self):
        return ProviderArticle(
            provider=self.definition(),
            item_id="article-42",
            title="Example",
            year=2024,
            article_ref="article:42",
        )

    def test_exact_episode_expands_only_selected_lazy_branch(self):
        wrong_loader = CountingLoader([
            VariantStream(url="https://media.example/s1e1.m3u8", quality="1080p"),
        ])
        right_loader = CountingLoader([
            VariantStream(url="https://media.example/s1e2.m3u8", quality="1080p"),
        ])
        root = VariantFolder(children=[
            VariantFolder(season=1, episode=1, loader=wrong_loader),
            VariantFolder(season=1, episode=2, loader=right_loader),
        ])

        rows = flatten_variant_tree(
            self.article(),
            root,
            ProviderRequest(media_id="m1", title="Example", year=2024, season=1, episode=2),
        )

        self.assertEqual(0, wrong_loader.calls)
        self.assertEqual(1, right_loader.calls)
        self.assertEqual(1, len(rows))
        self.assertEqual(1, rows[0]["season"])
        self.assertEqual(2, rows[0]["episode"])
        self.assertEqual("https://media.example/s1e2.m3u8", rows[0]["url"])

    def test_movie_never_falls_back_to_episode_branch(self):
        loader = CountingLoader([
            VariantStream(url="https://media.example/episode.m3u8", quality="720p"),
        ])
        root = VariantFolder(children=[
            VariantFolder(season=1, episode=1, loader=loader),
            VariantStream(url="https://media.example/movie.m3u8", quality="1080p"),
        ])

        rows = flatten_variant_tree(
            self.article(),
            root,
            ProviderRequest(media_id="m1", title="Example", year=2024),
        )

        self.assertEqual(0, loader.calls)
        self.assertEqual(["https://media.example/movie.m3u8"], [row["url"] for row in rows])

    def test_voice_and_quality_are_inherited_from_folders(self):
        root = VariantFolder(children=[
            VariantFolder(
                voice="Studio A",
                children=[
                    VariantFolder(
                        quality="1080p",
                        children=[VariantStream(url="https://media.example/a.m3u8")],
                    )
                ],
            )
        ])

        rows = flatten_variant_tree(
            self.article(),
            root,
            ProviderRequest(media_id="m1", title="Example", year=2024),
        )

        self.assertEqual("Studio A", rows[0]["voice"])
        self.assertEqual("1080p", rows[0]["quality"])

    def test_stream_metadata_overrides_folder_context(self):
        root = VariantFolder(
            voice="Folder voice",
            quality="720p",
            children=[
                VariantStream(
                    url="https://media.example/a.m3u8",
                    voice="Leaf voice",
                    quality="1080p",
                    audio_track_index=2,
                    headers={"Referer": "https://leaf.example/"},
                )
            ],
        )

        rows = flatten_variant_tree(
            self.article(),
            root,
            ProviderRequest(media_id="m1", title="Example", year=2024),
        )

        self.assertEqual("Leaf voice", rows[0]["voice"])
        self.assertEqual("1080p", rows[0]["quality"])
        self.assertEqual(2, rows[0]["audio_track_index"])
        self.assertEqual({"Referer": "https://leaf.example/"}, rows[0]["headers"])

    def test_same_url_keeps_distinct_voice_quality_variants(self):
        root = VariantFolder(children=[
            VariantFolder(voice="A", children=[
                VariantStream(url="https://media.example/master.m3u8", quality="720p"),
                VariantStream(url="https://media.example/master.m3u8", quality="1080p"),
            ]),
            VariantFolder(voice="B", children=[
                VariantStream(url="https://media.example/master.m3u8", quality="1080p"),
            ]),
        ])

        rows = flatten_variant_tree(
            self.article(),
            root,
            ProviderRequest(media_id="m1", title="Example", year=2024),
        )

        self.assertEqual(
            {("A", "720p"), ("A", "1080p"), ("B", "1080p")},
            {(row["voice"], row["quality"]) for row in rows},
        )
        self.assertEqual(3, len({row["provider_item_id"] for row in rows}))

    def test_logical_source_is_stable_across_rotating_url(self):
        request = ProviderRequest(media_id="m1", title="Example", year=2024)
        a = flatten_variant_tree(
            self.article(),
            VariantFolder(voice="A", children=[
                VariantStream(url="https://cdn-a.example/signed-a.m3u8", quality="1080p", stream_key="main"),
            ]),
            request,
        )[0]
        b = flatten_variant_tree(
            self.article(),
            VariantFolder(voice="A", children=[
                VariantStream(url="https://cdn-b.example/signed-b.m3u8", quality="1080p", stream_key="main"),
            ]),
            request,
        )[0]

        self.assertEqual(a["logical_source_id"], b["logical_source_id"])
        self.assertEqual(a["provider_item_id"], b["provider_item_id"])

    def test_provider_profile_is_not_implicitly_merged_into_playback_headers(self):
        root = VariantFolder(children=[
            VariantStream(url="https://media.example/a.m3u8", quality="720p"),
        ])
        rows = flatten_variant_tree(
            self.article(),
            root,
            ProviderRequest(media_id="m1", title="Example", year=2024),
        )

        self.assertEqual({}, rows[0]["headers"])
        self.assertEqual("Movia fixture", self.definition().request_profile.user_agent)

    def test_logical_source_survives_backend_sanitization(self):
        rows = flatten_variant_tree(
            self.article(),
            VariantFolder(voice="A", children=[
                VariantStream(url="https://media.example/a.m3u8", quality="1080p", stream_key="main"),
            ]),
            ProviderRequest(media_id="m1", title="Example", year=2024),
        )
        cleaned = sanitize_streams(rows, require_source=True)

        self.assertEqual(1, len(cleaned))
        self.assertEqual(rows[0]["logical_source_id"], cleaned[0]["logical_source_id"])

    def test_direct_reload_identity_prefers_explicit_logical_source(self):
        a = {
            "source": "HDRezka",
            "provider_id": "lazy:hdrezka",
            "logical_source_id": "logical-source:stable",
            "url": "https://cdn-a.example/signed-a.m3u8",
            "voice": "A",
            "quality": "1080p",
        }
        b = dict(a, url="https://cdn-b.example/signed-b.m3u8")
        self.assertEqual(_direct_stream_variant_identity(a), _direct_stream_variant_identity(b))
        self.assertEqual("logical-source", _direct_stream_variant_identity(a)[0])

    def test_partial_episode_coordinates_are_rejected(self):
        with self.assertRaises(ValueError):
            ProviderRequest(media_id="m1", title="Example", season=1)
        with self.assertRaises(ValueError):
            ProviderRequest(media_id="m1", title="Example", episode=1)


if __name__ == "__main__":
    unittest.main()
