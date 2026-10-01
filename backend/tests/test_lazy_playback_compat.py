import base64
import unittest
from zona_legacy_adapters import _hdrezka_static_decode, _hdrezka_stream_candidates
from balancer_integration import normalize_voice_name

class LazyPlaybackCompatibilityTests(unittest.TestCase):
    def test_all_alternative_urls_keep_their_quality(self):
        choices,dynamic=_hdrezka_stream_candidates('[720p]https://a.test/movie.m3u8 or https://b.test/movie.mp4,[1080p]https://c.test/movie.mp4',None)
        self.assertEqual([('https://a.test/movie.m3u8','720p'),('https://b.test/movie.mp4','720p'),('https://c.test/movie.mp4','1080p')],choices)
        self.assertFalse(dynamic)
    def test_dynamic_provider_marker_is_decoded_like_lazy(self):
        text='[720p]https://media.test/master.m3u8'
        value=base64.b64encode(text.encode()).decode()
        value=value[:8]+'//_//'+'abcdefghijklmnop'+value[8:]
        self.assertEqual(text,_hdrezka_static_decode('#h'+value))
    def test_truncated_marker_is_rejected(self):self.assertIsNone(_hdrezka_static_decode('#hYWJj//_//broken'))
    def test_voice_category_prefix_does_not_replace_the_studio(self):
        self.assertEqual('Кубик в Кубе',normalize_voice_name('Многоголосый Кубик в Кубе'))
        self.assertEqual('LostFilm',normalize_voice_name('Двухголосый LostFilm'))
    def test_unknown_voice_is_not_claimed_as_a_studio(self):self.assertEqual('Не указано',normalize_voice_name(None))
    def test_quality_groups_support_relative_protocol_and_deduplication(self):
        choices,_=_hdrezka_stream_candidates('[1080p]//media.test/a.m3u8 or //media.test/a.m3u8',None)
        self.assertEqual([('https://media.test/a.m3u8','1080p')],choices)
