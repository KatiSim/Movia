import unittest

from variant_coverage import variant_coverage


def row(voice, quality, suffix, **extra):
    value = {
        "source": "fixture",
        "provider": "fixture",
        "voice": voice,
        "quality": quality,
        "url": f"https://media.example/{suffix}.m3u8",
    }
    value.update(extra)
    return value


class VariantCoverageTests(unittest.TestCase):
    def test_three_by_three_target_is_complete(self):
        rows = [
            row("Dub", "1080p", "1"),
            row("Dub", "720p", "2"),
            row("Dub", "480p", "3"),
            row("LostFilm", "1080p", "4"),
            row("LostFilm", "720p", "5"),
            row("Original", "1080p", "6"),
        ]
        c = variant_coverage(rows, media_type="movie")
        self.assertTrue(c.complete)
        self.assertEqual(3, c.voices)
        self.assertEqual(3, c.qualities)

    def test_quality_aliases_do_not_fake_three_distinct_qualities(self):
        rows = [
            row("Dub A", "1080p", "1"),
            row("Dub B", "FullHD 1080", "2"),
            row("Dub C", "HD 720", "3"),
            row("Dub C", "720p", "4"),
        ]
        c = variant_coverage(rows, media_type="movie")
        self.assertFalse(c.complete)
        self.assertEqual(3, c.voices)
        self.assertEqual(2, c.qualities)

    def test_4k_aliases_collapse_to_one_quality(self):
        rows = [
            row("Dub A", "4K", "1"),
            row("Dub B", "2160p", "2"),
            row("Dub C", "UHD", "3"),
        ]
        c = variant_coverage(rows, media_type="movie")
        self.assertEqual(1, c.qualities)
        self.assertFalse(c.complete)

    def test_auto_and_unknown_do_not_fake_coverage(self):
        rows = [
            row("Auto", "Auto", "1"),
            row("Не указано", "1080p", "2"),
            row("Dub", "Не указано", "3"),
            row("Dub", "720p", "4"),
        ]
        c = variant_coverage(rows, media_type="movie")
        self.assertFalse(c.complete)
        self.assertEqual(1, c.voices)
        self.assertEqual(2, c.qualities)

    def test_series_card_level_streams_never_count_as_episode_coverage(self):
        rows = [
            row("Dub", "1080p", "1"),
            row("LostFilm", "720p", "2"),
            row("Original", "480p", "3"),
        ]
        c = variant_coverage(rows, media_type="tv")
        self.assertFalse(c.complete)
        self.assertTrue(c.requires_episode_identity)
        self.assertEqual(0, c.voices)
        self.assertEqual(0, c.qualities)

    def test_series_exact_episode_has_its_own_matrix(self):
        rows = [
            row("Dub", "1080p", "1", season=1, episode=1),
            row("Dub", "720p", "2", season=1, episode=1),
            row("Dub", "480p", "3", season=1, episode=1),
            row("LostFilm", "1080p", "4", season=1, episode=1),
            row("Original", "1080p", "5", season=1, episode=1),
            row("Wrong", "2160p", "6", season=1, episode=2),
        ]
        c = variant_coverage(rows, media_type="tv", season=1, episode=1)
        self.assertTrue(c.complete)
        self.assertEqual(3, c.voices)
        self.assertEqual(3, c.qualities)
        self.assertEqual(5, c.exact_episode_streams)


if __name__ == "__main__":
    unittest.main()
