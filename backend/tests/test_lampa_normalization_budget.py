import unittest,copy,json,random,string
import lampa_compat as module

class NormalizationBudgetTests(unittest.TestCase):
    def setUp(self):module._cached_voice_names.cache_clear()
    def test_cached_voice_detection_cannot_be_mutated_by_a_caller(self):
        first=module._detected_voices("LostFilm")
        first.append("fabricated")
        self.assertEqual(["LostFilm"],module._detected_voices("LostFilm"))
    def test_voice_cache_has_a_fixed_entry_budget(self):
        for index in range(1300):module._detected_voices("unique "+str(index))
        self.assertLessEqual(module._cached_voice_names.cache_info().currsize,1024)
    def test_large_external_strings_are_not_retained_in_cache(self):
        module._detected_voices("x"*5000)
        self.assertEqual(0,module._cached_voice_names.cache_info().currsize)
    def test_case_insensitive_fields_preserve_last_key_collision_and_source_values(self):
        source={"Quality":"360p","quality":"720p","Voice":"LostFilm",
            "URL":"https://cdn.example/Film.MP4?Token=AbC","HEADERS":{"Referer":"https://origin.example/A"}}
        before=copy.deepcopy(source);result=module.normalize_lampa_result(source)
        self.assertEqual("720p",result["quality"])
        self.assertEqual(before,source)
        self.assertEqual(source["URL"],result["url"])
        self.assertEqual(source["HEADERS"]["Referer"],result["HEADERS"]["Referer"])
    def test_explicit_physical_selectors_survive_normalization(self):
        source={"source":"Native","url":"https://cdn.example/film.mp4","voice":"Original",
            "quality":"Не указано","stream_id":"provider-item:v2:scope:leaf",
            "audio_track_index":1,"file_path":"Film.mkv","transport_metadata":{"scope":"exact"}}
        result=module.normalize_lampa_result(source)
        self.assertEqual(source["stream_id"],result["stream_id"])
        self.assertEqual(1,result["audio_track_index"])
        self.assertEqual("Film.mkv",result["file_path"])
        self.assertNotIn("_FoldedFields",json.dumps(result))
