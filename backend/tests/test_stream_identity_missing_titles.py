"""A pending title enrichment must not disable catalog/episode identity checks."""
import copy
import unittest

from stream_identity import filter_streams_for_content


class MissingTitleIdentityTests(unittest.TestCase):
    def card(self, **changes):
        return dict({"id": "7", "title": "", "original_title": "", "year": 2020, "media_type": "movie"}, **changes)

    def row(self, **changes):
        return dict({"source": "Fixture provider", "voice": "Original", "quality": "720p",
                     "url": "https://media.example/video.mp4"}, **changes)

    def test_missing_titles_do_not_accept_another_catalog_id(self):
        for field in ("catalog_media_id", "catalogMediaId"):
            with self.subTest(field=field):
                self.assertEqual([], filter_streams_for_content([self.row(**{field: "99"})], self.card()))

    def test_missing_titles_do_not_turn_an_episode_into_a_movie(self):
        self.assertEqual([], filter_streams_for_content(
            [self.row(catalog_media_id="7", season=2, episode=3)], self.card()))

    def test_missing_titles_do_not_accept_a_conflicting_year(self):
        for field in ("canonical_year", "canonicalYear"):
            with self.subTest(field=field):
                self.assertEqual([], filter_streams_for_content(
                    [self.row(catalog_media_id="7", **{field: 2021})], self.card()))

    def test_missing_titles_do_not_accept_a_conflicting_media_type(self):
        self.assertEqual([], filter_streams_for_content(
            [self.row(catalog_media_id="7", canonical_media_type="tv")], self.card()))

    def test_missing_titles_do_not_accept_another_episode(self):
        self.assertEqual([], filter_streams_for_content(
            [self.row(catalog_media_id="7", season=2, episode=4)],
            self.card(media_type="tv", season=2, episode=3)))

    def test_missing_titles_do_not_accept_missing_episode_coordinates(self):
        self.assertEqual([], filter_streams_for_content(
            [self.row(catalog_media_id="7")], self.card(media_type="tv", season=2, episode=3)))

    def test_exact_card_remains_usable_before_title_enrichment(self):
        rows = filter_streams_for_content([self.row(
            catalog_media_id="7", canonical_title="Provider title", canonical_year=2020,
            canonical_media_type="movie")], self.card())
        self.assertEqual(1, len(rows))
        self.assertEqual("7", rows[0]["catalog_media_id"])

    def test_available_title_is_still_checked_for_conflicts(self):
        self.assertEqual([], filter_streams_for_content(
            [self.row(catalog_media_id="7", canonical_title="Unrelated title")], self.card(title="Expected title")))

    def test_missing_titles_do_not_skip_episodic_release_markers(self):
        self.assertEqual([], filter_streams_for_content(
            [self.row(catalog_media_id="7", title="Fixture S02E03 2020")], self.card()))

    def test_exact_episode_remains_usable_before_title_enrichment(self):
        rows = filter_streams_for_content(
            [self.row(catalog_media_id="7", season=2, episode=3, canonical_media_type="tv")],
            self.card(media_type="tv", season=2, episode=3))
        self.assertEqual(1, len(rows))
        self.assertEqual((2, 3), (rows[0]["season"], rows[0]["episode"]))

    def test_legacy_unnamed_http_rows_keep_existing_nonconflicting_behavior(self):
        self.assertEqual(1, len(filter_streams_for_content([self.row()], self.card())))

    def test_identity_filter_does_not_relabel_or_mutate_provider_input(self):
        rows = [self.row(catalog_media_id="7", season=2, episode=3)]
        card = self.card(media_type="tv", season=2, episode=3)
        before = copy.deepcopy((rows, card))
        filter_streams_for_content(rows, card)
        self.assertEqual(before, (rows, card))
