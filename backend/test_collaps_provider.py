import unittest
from unittest.mock import patch

from collaps_provider import parse_collaps_page, resolve_collaps, get_last_collaps_diagnostics


class CollapsProviderTrackIndexTest(unittest.TestCase):
    def test_movie_voice_order_maps_to_hls_audio_track_indexes(self):
        html = '''
            <script>
            hls: "https://cdn.example/master.m3u8",
            audio: {"names":["Дубляж","Original (English)"]},
            cc: []
            </script>
        '''
        streams = parse_collaps_page(html, "https://api.example", "tt123")
        self.assertEqual(["Дубляж", "Original"], [s["voice"] for s in streams])
        self.assertEqual([0, 1], [s["audio_track_index"] for s in streams])
        self.assertEqual([9, 9], [s["source_type_id"] for s in streams])

    def test_series_voice_order_maps_to_hls_audio_track_indexes(self):
        html = '''
            seasons:[{"season":1,"episodes":[{"episode":1,"hls":"https://cdn.example/s1e1.m3u8","audio":{"names":["LostFilm","Original (English)"]},"cc":[]}]}]
        '''
        streams = parse_collaps_page(
            html,
            "https://api.example",
            "tt456",
            season=1,
            episode=1,
        )
        self.assertEqual(["LostFilm", "Original"], [s["voice"] for s in streams])
        self.assertEqual([0, 1], [s["audio_track_index"] for s in streams])
        self.assertEqual([9, 9], [s["source_type_id"] for s in streams])



    def test_all_mirror_errors_are_reported_as_provider_error(self):
        with patch("collaps_provider.get_imdb_id_from_db", return_value="tt123"), \
                patch("collaps_provider.urllib.request.urlopen", side_effect=TimeoutError("timeout")):
            streams = resolve_collaps("Example", year=2024)
        self.assertEqual(streams, [])
        diagnostics = get_last_collaps_diagnostics()
        self.assertEqual(diagnostics["status"], "PROVIDER_ERROR")
        self.assertGreaterEqual(diagnostics["error_count"], 1)

if __name__ == "__main__":
    unittest.main()
