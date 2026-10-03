"""Provider selectors must distinguish an unknown track from real track zero.

These tests use synthetic dictionaries only; they do not fetch media or providers.
"""
import copy
import unittest

from stream_validation import sanitize_streams, stable_stream_id


class TrackIndexContractTests(unittest.TestCase):
    def row(self, **changes):
        return dict({
            "source": "Fixture provider", "voice": "Original", "quality": "720p",
            "url": "https://media.example/video.mp4",
        }, **changes)

    def test_negative_sentinels_do_not_become_track_zero(self):
        for field in ("audio_track_index", "audioTrackIndex", "video_track_index", "videoTrackIndex"):
            canonical = "audio_track_index" if field.startswith("audio") else "video_track_index"
            for value in (-1, "-1", -2):
                with self.subTest(field=field, value=value):
                    result = sanitize_streams([self.row(**{field: value})])[0]
                    self.assertNotIn(canonical, result)

    def test_real_zero_is_retained_in_both_alias_forms(self):
        for audio, video in (("audio_track_index", "video_track_index"), ("audioTrackIndex", "videoTrackIndex")):
            result = sanitize_streams([self.row(**{audio: 0, video: "0"})])[0]
            self.assertEqual(0, result["audio_track_index"])
            self.assertEqual(0, result["video_track_index"])

    def test_positive_selectors_are_retained_without_alias_duplicates(self):
        result = sanitize_streams([self.row(audioTrackIndex="2", videoTrackIndex=3)])[0]
        self.assertEqual((2, 3), (result["audio_track_index"], result["video_track_index"]))
        self.assertNotIn("audioTrackIndex", result)
        self.assertNotIn("videoTrackIndex", result)

    def test_booleans_fractions_nonfinite_and_malformed_values_are_not_selectors(self):
        for value in (True, False, 1.7, float("nan"), float("inf"), "1.7", "first", "", [], {}):
            with self.subTest(value=repr(value)):
                result = sanitize_streams([self.row(audio_track_index=value, video_track_index=value)])[0]
                self.assertNotIn("audio_track_index", result)
                self.assertNotIn("video_track_index", result)

    def test_selector_must_fit_android_signed_integer(self):
        result = sanitize_streams([self.row(audio_track_index=2**31, video_track_index=str(2**31))])[0]
        self.assertNotIn("audio_track_index", result)
        self.assertNotIn("video_track_index", result)

    def test_unknown_and_negative_sentinel_do_not_create_two_streams(self):
        result = sanitize_streams([self.row(), self.row(audio_track_index=-1, video_track_index=-1)])
        self.assertEqual(1, len(result))
        self.assertNotIn("audio_track_index", result[0])
        self.assertNotIn("video_track_index", result[0])

    def test_integer_spellings_and_aliases_have_one_stable_identity(self):
        rows = [self.row(audio_track_index=1), self.row(audioTrackIndex="01"), self.row(audio_track_index=" 1 ")]
        self.assertEqual(1, len({stable_stream_id(row) for row in rows}))
        self.assertEqual(1, len(sanitize_streams(rows)))

    def test_unknown_video_selector_does_not_verify_adaptive_quality(self):
        for field in ("video_track_index", "videoTrackIndex"):
            with self.subTest(field=field):
                result = sanitize_streams([self.row(
                    source="Collaps", url="https://media.example/master.m3u8", quality="1080p",
                    **{field: -1},
                )])[0]
                self.assertEqual("Auto", result["quality"])
                self.assertEqual("1080p", result["transport_metadata"]["provider_reported_quality"])

    def test_real_video_selector_retains_concrete_quality(self):
        result = sanitize_streams([self.row(
            source="Collaps", url="https://media.example/master.m3u8", quality="1080p", video_track_index=0,
        )])[0]
        self.assertEqual("1080p", result["quality"])
        self.assertEqual(0, result["video_track_index"])

    def test_zero_is_not_merged_with_automatic_selection(self):
        result = sanitize_streams([self.row(), self.row(audio_track_index=0)])
        self.assertEqual(2, len(result))
        self.assertEqual(2, len({row["stream_id"] for row in result}))

    def test_normalization_is_idempotent_and_does_not_mutate_provider_input(self):
        rows = [self.row(), self.row(audio_track_index=-1), self.row(audio_track_index="0")]
        before = copy.deepcopy(rows)
        once = sanitize_streams(rows)
        self.assertEqual(before, rows)
        self.assertEqual(once, sanitize_streams(once))
        self.assertEqual(2, len(once))

    def test_non_selector_numeric_metadata_keeps_existing_behavior(self):
        result = sanitize_streams([self.row(duration=-1, size=0, source_type_id=0)])[0]
        self.assertEqual(0, result["duration"])
        self.assertEqual(0, result["size"])
        self.assertEqual(0, result["source_type_id"])
