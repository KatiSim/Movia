import threading
import unittest
from concurrent.futures import wait as real_wait
from unittest.mock import patch
import provider_discovery as module


class ProviderParallelTests(unittest.TestCase):
    def setUp(self):
        with module._PROVIDER_CACHE_LOCK:
            module._PROVIDER_CACHE.clear()
        self.env = {flag: "0" for flag in module._PROVIDER_FLAGS}
        self.env["MOVIA_ENABLE_ZONA_MOBI_PROVIDER_CONTRACT"] = "1"
        self.env["MOVIA_ENABLE_HDREZKA_PROVIDER_CONTRACT"] = "1"

    def test_slow_provider_does_not_hide_fast_and_late_result_retained(self):
        release = threading.Event()
        finished = threading.Event()
        cached = threading.Event()
        original_cache = module._cache_completed
        def cache(key, future):
            original_cache(key, future)
            if future.result().providers == ("slow",):
                cached.set()
        def resolve(enabled_flags, **request):
            if "MOVIA_ENABLE_ZONA_MOBI_PROVIDER_CONTRACT" in enabled_flags:
                release.wait(2)
                finished.set()
                name = "slow"
            else:
                name = "fast"
            return module.ProviderDiscoveryOutcome(
                [{"url": "https://media.test/" + name + ".mp4", "provider": name,
                  "voice": "Не указано", "quality": "Не указано"}],
                "OK", (name,),
            )
        def bounded_wait(futures, timeout):
            return real_wait(futures, timeout=0.1)
        try:
            with patch.dict("os.environ", self.env), patch.object(module, "_discover_provider_streams", resolve), patch.object(module, "wait", bounded_wait), patch.object(module, "_cache_completed", cache):
                first = module.discover_provider_streams(media_id="parallel", title="Example", year=2008)
                self.assertEqual(first.providers, ("fast",))
                self.assertEqual(len(first.streams), 1)
                release.set()
                self.assertTrue(finished.wait(2))
                self.assertTrue(cached.wait(2))
                second = module.discover_provider_streams(media_id="parallel", title="Example", year=2008)
                self.assertEqual(set(second.providers), {"fast", "slow"})
                self.assertEqual(len(second.streams), 2)
        finally:
            release.set()

    def test_foreign_catalog_cannot_receive_cached_rows(self):
        key = ("MOVIA_ENABLE_ZONA_MOBI_PROVIDER_CONTRACT", ("other", "Example", "2008", "", "", "", ""))
        import time
        with module._PROVIDER_CACHE_LOCK:
            module._PROVIDER_CACHE[key] = (time.monotonic() + 60,
                module.ProviderDiscoveryOutcome([{"url": "https://media.test/foreign.mp4"}], "OK"))
        def resolve(**kwargs):
            return module.ProviderDiscoveryOutcome([], "NO_MATCH")
        with patch.dict("os.environ", self.env), patch.object(module, "_discover_provider_streams", resolve):
            result = module.discover_provider_streams(media_id="correct", title="Example", year=2008)
        self.assertEqual(result.streams, [])


if __name__ == "__main__":
    unittest.main()
