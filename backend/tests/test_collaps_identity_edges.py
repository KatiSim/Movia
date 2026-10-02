import json
import unittest
from collaps_provider import parse_collaps_page


class CollapsIdentityEdgesTest(unittest.TestCase):
    def parse(self, text, season=None, episode=None):
        return parse_collaps_page(text, 'https://provider.example', 'tt1234567', season, episode)

    def series(self, names=None):
        return [{"season": "2", "episodes": [{"episode": "3", "hls": "https://media.example/s2e3.m3u8",
                "audio": {"names": names or ['Studio [special]', 'Studio B']}}]}]

    def test_whitespace_string_season_and_brackets_inside_labels(self):
        rows = self.parse('seasons : ' + json.dumps(self.series()), 2, 3)
        self.assertEqual(['Studio [special]', 'Studio B'], [row['voice'] for row in rows])
        self.assertTrue(all(row['season'] == 2 and row['episode'] == 3 for row in rows))

    def test_quotes_braces_and_unicode_inside_movie_labels(self):
        names = ['Студия {A} "B"', 'Studio ] [']
        rows = self.parse('hls:"https://media.example/movie.m3u8",audio:' + json.dumps({'names': names}))
        self.assertEqual(names, [row['voice'] for row in rows])

    def test_missing_episode_never_falls_through_to_movie_url(self):
        text = 'seasons:' + json.dumps(self.series()) + ',hls:"https://media.example/wrong.m3u8"'
        self.assertEqual([], self.parse(text, 2, 4))

    def test_series_without_exact_request_has_no_default_episode(self):
        self.assertEqual([], self.parse('seasons:' + json.dumps(self.series())))

    def test_movie_is_not_relabelled_as_episode(self):
        self.assertEqual([], self.parse('hls:"https://media.example/movie.m3u8"', 2, 3))

    def test_missing_audio_is_unknown_and_does_not_force_first_rendition(self):
        row = self.parse('hls:"https://media.example/movie.m3u8"')[0]
        self.assertEqual('Не указано', row['voice'])
        self.assertNotIn('audio_track_index', row)

    def test_malformed_audio_names_do_not_become_character_voices(self):
        for names in ('Studio', {'first': 'Studio'}, 3):
            rows = self.parse('hls:"https://media.example/movie.m3u8",audio:' + json.dumps({'names': names}))
            self.assertEqual(['Не указано'], [row['voice'] for row in rows])

    def test_malformed_season_json_never_uses_nested_url(self):
        self.assertEqual([], self.parse('seasons:[broken],hls:"https://media.example/wrong.m3u8"', 1, 1))

    def test_bad_season_objects_are_skipped_without_discarding_valid_episode(self):
        rows = self.parse('"seasons": ' + json.dumps([None, 'bad'] + self.series()), 2, 3)
        self.assertEqual(2, len(rows))

    def test_boolean_identity_is_not_episode_one(self):
        self.assertEqual([], self.parse('seasons:' + json.dumps(self.series()), True, True))
