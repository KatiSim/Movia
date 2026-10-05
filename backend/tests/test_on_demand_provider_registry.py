import unittest
from unittest.mock import patch
import streamer
from provider_discovery import ProviderDiscoveryOutcome

class OnDemandRegistryTests(unittest.TestCase):
    def test_clean_registry_budget_is_not_forwarded_as_provider_request(self):
        import provider_discovery as module
        env = {flag: "0" for flag in module._PROVIDER_FLAGS}
        env["MOVIA_ENABLE_ZONA_MOBI_PROVIDER_CONTRACT"] = "1"
        def resolve(*, enabled_flags, title, year, media_id, media_type,
                    season, episode, original_title):
            return ProviderDiscoveryOutcome([{"url": "https://media.test/a.mp4",
                "provider": "Native", "quality": "HQ", "voice": "Не указано",
                "catalog_media_id": media_id}], "OK", ("native",))
        with patch.dict("os.environ", env), patch.object(module, "_discover_provider_streams", resolve):
            rows = streamer._resolve_clean_provider_registry(
                "Example", 2008, None, None, None, "native-budget-test", "movie")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["catalog_media_id"], "native-budget-test")

    def test_playback_unions_registry_balancer_and_torrent_for_exact_card(self):
        identity = {"id": 77777, "title": "Example", "year": 2008,
                    "original_title": "Example", "media_type": "movie", "tmdb_id": 0}
        def row(provider, suffix, media_id="77777"):
            return {"url": "https://media.test/" + suffix + ".mp4",
                    "provider": provider, "voice": provider, "quality": "720p",
                    "catalog_media_id": media_id, "canonical_title": "Example",
                    "canonical_year": 2008, "canonical_media_type": "movie"}
        cache = {}
        with patch.object(streamer, "_catalog_identity_for_request", return_value=("OK", identity)), \
             patch.object(streamer, "get_cached_streams", side_effect=lambda key: cache.get(key, [])), \
             patch.object(streamer, "set_cached_streams", side_effect=lambda key, rows, **kwargs: cache.update({key: rows})), \
             patch.object(streamer, "get_recent_stale_direct_streams", return_value=[]), \
             patch.object(streamer, "_resolve_balancer_provider", return_value=[row("Balancer", "b")]), \
             patch.object(streamer, "_resolve_clean_provider_registry", return_value=[row("Native", "n"), row("Foreign", "foreign", "88888")]), \
             patch.object(streamer, "_resolve_torrent_provider", return_value=[row("Torrent", "t")]), \
             patch.object(streamer, "P2P_ENABLED", True), patch.object(streamer, "CLOUD_MODE", False):
            result = streamer.resolve_on_demand_streams(
                "Example", 2008, catalog_media_id=77777, media_type="movie",
                force_refresh=True, require_catalog_identity=True, _allow_stale_fast_path=False)
        self.assertEqual({x["provider"] for x in result}, {"Balancer", "Native", "Torrent"})
        self.assertTrue(all(x["catalog_media_id"] == "77777" for x in result))


if __name__ == "__main__":
    unittest.main()
