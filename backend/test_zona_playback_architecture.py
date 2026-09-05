import unittest

from stream_validation import sanitize_streams, stable_stream_id
from zona_playback_architecture import (
    LegacyStreamBuilder,
    VideoSourceRef,
    ZONA_P2P_SOURCE_TYPES,
    ZONA_SOURCE_REGISTRY,
    source_capabilities,
)


class ZonaPlaybackArchitectureTests(unittest.TestCase):
    def test_registry_is_complete_and_type_51_is_not_p2p(self):
        self.assertEqual(len(ZONA_SOURCE_REGISTRY), 51)
        self.assertEqual(ZONA_SOURCE_REGISTRY[10], "Bazon Mirror (CZX)")
        self.assertIn("NOT P2P", ZONA_SOURCE_REGISTRY[51])
        self.assertEqual(ZONA_P2P_SOURCE_TYPES, frozenset())
        self.assertFalse(source_capabilities(51)["p2p"])

    def test_video_source_serializer_shape_defaults_strings_to_empty(self):
        source = VideoSourceRef.from_mapping({
            "id": 123,
            "video_source_type_id": 32,
            "video_content_type_id": 4,
            "kinopoisk_id": 456,
        })
        self.assertEqual(source.id, 123)
        self.assertEqual(source.video_source_type_id, 32)
        self.assertEqual(source.video_content_type_id, 4)
        self.assertEqual(source.kinopoisk_id, 456)
        self.assertEqual(source.download_link_key, "")
        self.assertEqual(source.episode_key, "")
        self.assertEqual(source.info, "")

    def test_builder_mapping_preserves_streaminfo_contract_without_secrets(self):
        source = VideoSourceRef(id=7, video_source_type_id=32, video_content_type_id=2)
        stream = LegacyStreamBuilder(
            video_source=source,
            url="https://media.example.test/master.mpd",
            translation="Русский",
            language="ru",
            quality="1080p",
            resolution="1920x1080",
            headers={"User-Agent": "UA", "Referer": "https://example.test/"},
            codec="AV1",
            download_url="https://media.example.test/file.mp4",
            download_format="MP4",
            video_track_index=1,
            audio_track_index=2,
            duration=12345,
            size=98765,
        ).to_stream_dict()
        self.assertEqual(stream["source_type_id"], 32)
        self.assertFalse(stream["unavailable_quality"])
        self.assertEqual(stream["user_agent"], "UA")
        self.assertEqual(stream["download_url"], "https://media.example.test/file.mp4")
        self.assertEqual(stream["video_track_index"], 1)
        self.assertEqual(stream["audio_track_index"], 2)
        self.assertEqual(stream["duration"], 12345)
        self.assertEqual(stream["size"], 98765)
        self.assertEqual(stream["transport_metadata"]["zona_media_probe"], "ALL")

    def test_non_mp4_download_format_does_not_become_download_url(self):
        stream = LegacyStreamBuilder(
            video_source=VideoSourceRef(video_source_type_id=11),
            url="https://media.example.test/master.m3u8",
            download_url="https://media.example.test/download.m3u8",
            download_format="M3U8",
        ).to_stream_dict()
        self.assertEqual(stream["download_url"], "")

    def test_track_variants_survive_sanitizer_and_get_distinct_ids(self):
        base = {
            "source": "Zona",
            "source_type_id": 32,
            "url": "https://media.example.test/master.mpd",
            "voice": "Русский",
            "quality": "1080p",
        }
        first = dict(base, video_track_index=0, audio_track_index=0)
        second = dict(base, video_track_index=0, audio_track_index=1)
        clean = sanitize_streams([first, second], require_source=True)
        self.assertEqual(len(clean), 2)
        self.assertNotEqual(stable_stream_id(first), stable_stream_id(second))


if __name__ == "__main__":
    unittest.main()
