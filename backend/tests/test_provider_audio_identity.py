import json
import unittest
from collaps_provider import parse_collaps_page
from balancer_integration import normalize_provider_voice
from stream_validation import sanitize_streams

class ProviderAudioIdentityTests(unittest.TestCase):
    def movie(self, names):
        return parse_collaps_page('hls:"https://media.example/master.m3u8",audio:'+json.dumps({"names": names}),"https://provider.example","tt0123456")
    def test_distinct_multivoice_names_do_not_collapse(self):
        names=["Рус. люб. многоголосый Студия А","Рус. люб. многоголосый Студия Б"]
        rows=self.movie(names)
        self.assertEqual(names,[r["voice"] for r in rows])
        self.assertEqual(2,len({r["stream_id"] for r in rows}))
        self.assertEqual([0,1],[r["audio_track_index"] for r in rows])
    def test_identical_source_labels_are_separate_selectable_tracks(self):
        rows=self.movie(["Многоголосый","Многоголосый"])
        self.assertEqual(["Многоголосый · дорожка 1","Многоголосый · дорожка 2"],[r["voice"] for r in rows])
        self.assertEqual(2,len({r["streamId"] for r in rows}))
    def test_pipeline_keeps_original_studio_and_ordinal(self):
        rows=self.movie(["Дубляж Пифагор","Многоголосый","Многоголосый"])
        for row in rows:
            self.assertEqual(row["voice"],normalize_provider_voice(row))
        clean=sanitize_streams(rows)
        self.assertEqual(3,len(clean))
        self.assertEqual([r["source_voice_label"] for r in rows],[r["source_voice_label"] for r in clean])
        self.assertEqual([0,1,2],[r["audio_track_index"] for r in clean])
    def test_series_identity_includes_episode_and_audio_ordinal(self):
        seasons=[{"season":2,"episodes":[{"episode":3,"hls":"https://media.example/master.m3u8","audio":{"names":["Многоголосый","Многоголосый"]}}]}]
        rows=parse_collaps_page("seasons:"+json.dumps(seasons,separators=(",",":")),"https://provider.example","tt0123456",2,3)
        self.assertEqual(2,len(rows))
        self.assertTrue(all("_s2e3_a" in r["stream_id"] for r in rows))
        self.assertTrue(all(r["season"]==2 and r["episode"]==3 for r in rows))
    def test_unknown_track_is_not_fabricated_as_dubbing(self):
        self.assertEqual("Не указано",self.movie([""])[0]["voice"])
    def test_stream_identity_does_not_depend_on_display_alias(self):
        first=self.movie(["Студия А"])[0]
        self.assertIn("_a0_",first["stream_id"])
        self.assertEqual(first["stream_id"],first["streamId"])
