import unittest

from collaps_provider import parse_collaps_page


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


if __name__ == "__main__":
    unittest.main()
