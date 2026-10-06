import unittest
from unittest.mock import patch, MagicMock
import media_content_probe as p


class NativeHLSDimensionTests(unittest.TestCase):
    def response(self, data):
        result = MagicMock()
        result.__enter__.return_value = result
        result.iter_content.return_value = iter([data])
        return result

    def test_dimensions_come_from_bounded_middle_segment_not_label(self):
        text = "#EXTM3U\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n#EXTINF:6,\nc.ts\n#EXT-X-ENDLIST\n"
        with patch.object(p.requests, "get", return_value=self.response(b"x" * 100000)) as get, patch.object(p, "_measure_video_sample", return_value={"duration": .2, "height": 720, "width": 1280}) as inspect:
            measured = p._hls_video_dimensions(text, "https://cdn.example/film/1080p.m3u8", {"Referer": "https://provider.example"})
        self.assertEqual({"height": 720, "width": 1280}, measured)
        self.assertEqual("https://cdn.example/film/b.ts", get.call_args.args[0])
        self.assertEqual("bytes=0-65535", get.call_args.kwargs["headers"]["Range"])
        self.assertEqual(65536, len(inspect.call_args.args[0]))

    def test_segment_duration_cannot_replace_full_playlist_duration(self):
        data = b"#EXTM3U\n#EXTINF:6,\na.ts\n#EXTINF:4,\nb.ts\n#EXT-X-ENDLIST\n"
        with patch.object(p, "_CACHE", {}), patch.object(p.requests, "get", return_value=self.response(data)), patch.object(p, "_schedule_hls_dimensions", side_effect=lambda text,url,headers,result: result.update(height=480,width=854)):
            # The actual cache uses ordered storage.
            from collections import OrderedDict
            with patch.object(p, "_CACHE", OrderedDict()):
                value = p.measure_hls_content("https://cdn.example/sample.m3u8", {})
        self.assertEqual(10, value["duration"])
        self.assertEqual(480, value["height"])
        self.assertEqual("hls", value["container"])

    def test_encrypted_or_byte_range_playlist_does_not_guess_height(self):
        for tag in ('#EXT-X-KEY:METHOD=AES-128,URI="key"', '#EXT-X-BYTERANGE:1000@0'):
            with patch.object(p.requests, "get") as get:
                self.assertIsNone(p._hls_video_dimensions("#EXTM3U\n" + tag + "\na.ts", "https://cdn.example/playlist.m3u8", {}))
                get.assert_not_called()

    def test_failed_dimension_probe_preserves_known_complete_duration(self):
        data = b"#EXTM3U\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST\n"
        from collections import OrderedDict
        with patch.object(p, "_CACHE", OrderedDict()), patch.object(p.requests, "get", return_value=self.response(data)), patch.object(p, "_schedule_hls_dimensions", return_value=None):
            value = p.measure_hls_content("https://cdn.example/sample.m3u8", {})
        self.assertEqual({"duration": 6, "container": "hls"}, value)

    def test_slow_dimension_probe_cannot_delay_complete_playlist_runtime(self):
        from concurrent.futures import ThreadPoolExecutor
        from threading import BoundedSemaphore, Event
        from collections import OrderedDict
        from time import monotonic
        gate = Event()
        executor = ThreadPoolExecutor(max_workers=1)
        data = b"#EXTM3U\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST\n"
        def slow(*args):
            gate.wait(5)
            return {"height": 720, "width": 1280}
        with patch.object(p, "_CACHE", OrderedDict()), patch.object(p, "_PLAYLIST_TEXT", OrderedDict()), patch.object(p, "_DIMENSION_ACTIVE", set()), patch.object(p, "_DIMENSION_WORKERS", executor), patch.object(p, "_DIMENSION_SLOTS", BoundedSemaphore(3)), patch.object(p, "_hls_video_dimensions", side_effect=slow), patch.object(p.requests, "get", return_value=self.response(data)):
            try:
                started = monotonic()
                result = p.measure_hls_content("https://cdn.example/nonblocking.m3u8", {})
                self.assertLess(monotonic() - started, .2)
                self.assertEqual(6, result["duration"])
                self.assertNotIn("height", result)
            finally:
                gate.set()
                executor.shutdown(wait=True, cancel_futures=True)
            self.assertEqual(720, result["height"])
            self.assertEqual(6, result["duration"])
