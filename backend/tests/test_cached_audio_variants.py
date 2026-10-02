import copy
import json
import unittest
from stream_validation import sanitize_streams


class CachedAudioVariantTests(unittest.TestCase):
    def row(self, index, **changes):
        return dict({'source': 'Collaps', 'provider': 'Collaps', 'voice': 'Многоголосый',
                     'quality': 'Auto', 'url': 'https://media.example/master.m3u8',
                     'stream_id': 'legacy:collapsed', 'audio_track_index': index}, **changes)

    def test_legacy_colliding_ids_and_names_remain_independently_selectable(self):
        rows = sanitize_streams([self.row(3), self.row(9)])
        self.assertEqual(['Многоголосый · дорожка 4', 'Многоголосый · дорожка 10'], [row['voice'] for row in rows])
        self.assertEqual(2, len({row['stream_id'] for row in rows}))
        self.assertEqual([3, 9], [row['audio_track_index'] for row in rows])

    def test_zero_audio_index_is_a_real_track(self):
        rows = sanitize_streams([self.row(0), self.row(1)])
        self.assertEqual(['Многоголосый · дорожка 1', 'Многоголосый · дорожка 2'], [row['voice'] for row in rows])

    def test_db_round_trip_is_idempotent_and_does_not_modify_inputs(self):
        raw = [self.row(0), self.row(1)]
        original = copy.deepcopy(raw)
        first = sanitize_streams(raw)
        second = sanitize_streams(json.loads(json.dumps(first)))
        self.assertEqual(original, raw)
        self.assertEqual(first, second)
        self.assertEqual(first, sanitize_streams(first))

    def test_identity_is_independent_of_response_order(self):
        raw = [self.row(3), self.row(9)]
        ids = lambda rows: {row['audio_track_index']: row['stream_id'] for row in rows}
        self.assertEqual(ids(sanitize_streams(raw)), ids(sanitize_streams(raw[::-1])))

    def test_quality_variants_of_one_audio_track_have_one_voice(self):
        rows = sanitize_streams([self.row(0, source='Other', provider='Other', quality='360p'),
                                 self.row(0, source='Other', provider='Other', quality='720p')])
        self.assertEqual(['Многоголосый', 'Многоголосый'], [row['voice'] for row in rows])
        self.assertEqual(2, len({row['stream_id'] for row in rows}))

    def test_audio_groups_from_different_episodes_do_not_rename_each_other(self):
        rows = sanitize_streams([self.row(0, season=1, episode=2), self.row(1, season=1, episode=3)])
        self.assertTrue(all(row['voice'] == 'Многоголосый' for row in rows))
        self.assertEqual(2, len({row['stream_id'] for row in rows}))

    def test_distinct_playback_headers_are_retained_and_have_stable_ids(self):
        rows = sanitize_streams([self.row(0, headers={'Referer': 'https://provider.example/A'}),
                                 self.row(0, headers={'Referer': 'https://provider.example/B'})])
        self.assertEqual(2, len(rows))
        self.assertEqual(2, len({row['stream_id'] for row in rows}))
        self.assertTrue(all(row['voice'] == 'Многоголосый' for row in rows))
        self.assertEqual(rows, sanitize_streams(rows))

    def test_header_case_and_ignored_credentials_do_not_create_fake_variants(self):
        rows = sanitize_streams([self.row(0, headers={'Referer': 'https://provider.example', 'Authorization': 'not-persisted'}),
                                 self.row(0, headers={'referer': 'https://provider.example'})])
        self.assertEqual(1, len(rows))
        self.assertNotIn('Authorization', rows[0]['headers'])

    def test_distinct_studio_labels_are_preserved_verbatim(self):
        rows = sanitize_streams([self.row(0, voice='Кубик в Кубе', stream_id='voice:a'),
                                 self.row(1, voice='LostFilm', stream_id='voice:b')])
        self.assertEqual(['Кубик в Кубе', 'LostFilm'], [row['voice'] for row in rows])

    def test_identical_repeated_rows_are_not_additional_tracks(self):
        self.assertEqual(1, len(sanitize_streams([self.row(0), self.row(0)])))
