import unittest
from unittest.mock import Mock, patch

from provider_contract import ProviderRequest, ProviderSearchResult, flatten_variant_tree
from provider_discovery import ProviderDiscoveryOutcome, ProviderRegistration, _discover_provider_streams
from hdrezka_provider_adapter import DEF, HDRezkaProviderAdapter, HDRezkaVariantLoadError


class ProviderRegistryBoundaryTests(unittest.TestCase):
    def test_exception_in_one_provider_does_not_hide_other_provider(self):
        broken = Mock(side_effect=RuntimeError("unavailable"))
        good = Mock(return_value=ProviderDiscoveryOutcome([{
            "url": "https://cdn.example/real.mp4", "provider": "Healthy",
            "voice": "Actual studio", "quality": "720p",
        }], "OK"))
        disabled = Mock(side_effect=AssertionError("must not run"))
        registry = (ProviderRegistration("BROKEN", "broken", broken),
                    ProviderRegistration("GOOD", "good", good),
                    ProviderRegistration("OFF", "off", disabled))
        with patch("provider_discovery.PROVIDER_REGISTRY", registry):
            result = _discover_provider_streams(enabled_flags=frozenset({"BROKEN", "GOOD"}),
                                                title="Example", year=2024, media_id="77")
        self.assertEqual("OK", result.status)
        self.assertEqual(1, len(result.streams))
        self.assertEqual(1, result.error_count)
        self.assertEqual(("broken", "good"), result.providers)
        disabled.assert_not_called()

    def test_disabled_registry_does_not_invoke_network(self):
        callback = Mock(side_effect=AssertionError("must not run"))
        with patch("provider_discovery.PROVIDER_REGISTRY",
                   (ProviderRegistration("FLAG", "provider", callback),)):
            result = _discover_provider_streams(enabled_flags=frozenset(),
                                                title="Example", media_id="77")
        self.assertEqual("PROVIDER_DISABLED", result.status)
        callback.assert_not_called()

    def test_hdrezka_deferred_article_performs_no_transport_until_selected(self):
        adapter = HDRezkaProviderAdapter(expected_duration=lambda _: None)
        request = ProviderRequest("77", "Example", 2024, season=2, episode=3, media_type="tv")
        source = ProviderSearchResult(DEF, "series/123-example-2024", "Example", 2024)
        with patch("hdrezka_provider_adapter._resolve_hdrezka") as transport:
            root, article, error = adapter.deferred_source(source, request)
            transport.assert_not_called()
            self.assertIsNone(error)
            # An unrelated episode is pruned before loader invocation.
            wrong = ProviderRequest("77", "Example", 2024, season=2, episode=4, media_type="tv")
            self.assertEqual([], flatten_variant_tree(article, root, wrong))
            transport.assert_not_called()
        with patch.object(adapter, "resolve_source", return_value=(None, None, "HTTP_403")) as resolver:
            with self.assertRaisesRegex(HDRezkaVariantLoadError, "HTTP_403"):
                flatten_variant_tree(article, root, request)
            resolver.assert_called_once_with(source, request)

    def test_deferred_article_rejects_rebinding_media_id_before_http(self):
        adapter = HDRezkaProviderAdapter()
        request = ProviderRequest("77", "Example", 2024)
        source = ProviderSearchResult(DEF, "films/123-example-2024", "Example", 2024)
        root, article, _ = adapter.deferred_source(source, request)
        with patch.object(adapter, "resolve_source") as resolver:
            with self.assertRaisesRegex(HDRezkaVariantLoadError, "IDENTITY_MISMATCH"):
                flatten_variant_tree(article, root, ProviderRequest("88", "Example", 2024))
            resolver.assert_not_called()

    def test_deferred_success_preserves_all_leaves_and_loads_once(self):
        from provider_contract import ProviderArticle, VariantFolder, VariantStream
        adapter = HDRezkaProviderAdapter()
        request = ProviderRequest("77", "Example", 2024)
        source = ProviderSearchResult(DEF, "films/123-example-2024", "Example", 2024)
        tree = VariantFolder(children=tuple(VariantStream(
            url=f"https://cdn.example/{i}.mp4", voice=f"Studio {i}", quality="720p",
            stream_key=f"representation-{i}") for i in range(600)))
        article = ProviderArticle(DEF, source.item_id, source.title, source.year)
        root, deferred_article, _ = adapter.deferred_source(source, request)
        with patch.object(adapter, "resolve_source", return_value=(tree, article, None)) as resolver:
            self.assertEqual(600, len(flatten_variant_tree(deferred_article, root, request)))
            self.assertEqual(600, len(flatten_variant_tree(deferred_article, root, request)))
            resolver.assert_called_once()
