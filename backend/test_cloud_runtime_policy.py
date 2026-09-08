#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import subprocess
import sys
import unittest
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import patch

import content_filler as filler


ROOT = Path(__file__).resolve().parent


class CloudRuntimePolicyTests(unittest.TestCase):
    def _probe_streamer(self, **env_updates: str) -> dict:
        env = os.environ.copy()
        for key in (
            "MOVIA_CLOUD_MODE", "MOVIA_P2P_ENABLED", "MOVIA_HOST", "MOVIA_PORT",
            "MOVIA_CATALOG_SYNC_ENABLED",
        ):
            env.pop(key, None)
        env.update(env_updates)
        code = (
            "import json, streamer; "
            "print(json.dumps({"
            "'cloud': streamer.CLOUD_MODE, "
            "'p2p': streamer.P2P_ENABLED, "
            "'host': streamer.HOST, "
            "'port': streamer.PORT, "
            "'catalog_sync': streamer.CATALOG_SYNC_ENABLED, "
            "'torrent': streamer._resolve_torrent_provider('x', 2024, 'movies', None, None)"
            "}))"
        )
        result = subprocess.run(
            [sys.executable, "-c", code],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
            timeout=20,
            check=True,
        )
        return json.loads(result.stdout.strip().splitlines()[-1])

    def test_local_defaults_preserve_phone_runtime_contract(self):
        data = self._probe_streamer()
        self.assertFalse(data["cloud"])
        self.assertTrue(data["p2p"])
        self.assertEqual(data["host"], "127.0.0.1")
        self.assertEqual(data["port"], 8888)
        self.assertTrue(data["catalog_sync"])

    def test_cloud_mode_forces_direct_only_even_if_p2p_requested(self):
        data = self._probe_streamer(
            MOVIA_CLOUD_MODE="1",
            MOVIA_P2P_ENABLED="1",
            MOVIA_HOST="0.0.0.0",
            MOVIA_PORT="8080",
        )
        self.assertTrue(data["cloud"])
        self.assertFalse(data["p2p"])
        self.assertEqual(data["host"], "0.0.0.0")
        self.assertEqual(data["port"], 8080)
        self.assertFalse(data["catalog_sync"])
        self.assertEqual(data["torrent"], [])

    def test_cloud_bulk_filler_never_calls_torrent_even_with_override(self):
        row = {
            "id": 1,
            "tmdb_id": 1,
            "title": "Тест",
            "original_title": "Test",
            "year": 2024,
            "category": "movies",
            "media_type": "movie",
            "streams": "[]",
        }
        with patch.dict(
            os.environ,
            {
                "MOVIA_CLOUD_MODE": "1",
                "MOVIA_BACKGROUND_BULK": "1",
                "MOVIA_BACKGROUND_TORRENT_LOOKUP": "1",
            },
            clear=False,
        ), patch.object(filler, "resolve_balancer", return_value=None), patch.object(
            filler, "get_last_resolution_diagnostics", return_value={}
        ), patch.object(
            filler, "resolve_torrent", side_effect=AssertionError("torrent must stay disabled in cloud")
        ):
            result = filler._process_row(row, 1, 1)
        self.assertEqual(result["status"], "no_source")
        self.assertEqual(result["provider_errors"], 0)

    def test_cloud_bulk_skips_android_networkstats_guard(self):
        state = {
            "last_id": 0,
            "pass_number": 0,
            "retry_after": {},
            "failure_streaks": {},
        }
        with patch.dict(
            os.environ,
            {"MOVIA_CLOUD_MODE": "1", "MOVIA_BACKGROUND_BULK": "1"},
            clear=False,
        ), patch.object(filler, "background_bulk_allowed", side_effect=AssertionError(
            "Android NetworkStats guard must not run in cloud mode"
        )), patch.object(filler, "load_state", return_value=state), patch.object(
            filler, "get_db", return_value=nullcontext(object())
        ), patch.object(filler, "_fetch_rows", return_value=[]), patch.object(
            filler, "save_state"
        ):
            result = filler.fill_content(resume=False)
        self.assertEqual(result["processed"], 0)
        self.assertTrue(result["pass_completed"])

    def test_cloud_stream_route_is_source_contract_direct_only(self):
        text = (ROOT / "streamer.py").read_text(encoding="utf-8")
        self.assertIn('if CLOUD_MODE and parsed.path.startswith("/stream"):', text)
        self.assertIn('"media_proxy_disabled"', text)
        self.assertIn('if not P2P_ENABLED:', text)

    def test_cloud_exposure_filters_magnets_and_local_proxy_urls(self):
        env = os.environ.copy()
        env["MOVIA_CLOUD_MODE"] = "1"
        env["MOVIA_P2P_ENABLED"] = "1"
        code = """
import json
import streamer
items = [
    {"url": "magnet:?xt=urn:btih:" + "a" * 40},
    {"url": "http://127.0.0.1:8888/stream?x=1"},
    {"url": "http://localhost:8888/stream?x=1"},
    {"url": "https://cdn.example/video/master.m3u8", "source": "direct"},
]
print(json.dumps(streamer.cloud_exposable_streams(items)))
"""
        result = subprocess.run(
            [sys.executable, "-c", code],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
            timeout=20,
            check=True,
        )
        filtered = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(
            filtered,
            [{"url": "https://cdn.example/video/master.m3u8", "source": "direct"}],
        )


    def test_cloud_catalog_sync_is_opt_in(self):
        default_data = self._probe_streamer(MOVIA_CLOUD_MODE="1")
        enabled_data = self._probe_streamer(
            MOVIA_CLOUD_MODE="1", MOVIA_CATALOG_SYNC_ENABLED="1"
        )
        self.assertFalse(default_data["catalog_sync"])
        self.assertTrue(enabled_data["catalog_sync"])

    def test_magnet_only_cache_falls_through_to_direct_resolver_in_cloud(self):
        env = os.environ.copy()
        env["MOVIA_CLOUD_MODE"] = "1"
        env["MOVIA_P2P_ENABLED"] = "1"
        code = """
import json
import streamer
identity = {
    "id": 7, "title": "Test", "original_title": "Test",
    "year": 2024, "media_type": "movie", "tmdb_id": 77,
}
streamer._catalog_identity_for_request = lambda *a, **k: ("OK", identity)
streamer.get_cached_streams = lambda *a, **k: [
    {"url": "magnet:?xt=urn:btih:" + "a" * 40, "source": "Rutor"}
]
streamer.get_recent_stale_direct_streams = lambda *a, **k: []
streamer._scope_streams_to_catalog_card = lambda streams, *a, **k: list(streams)
streamer.filter_streams_for_episode = lambda streams, *a, **k: list(streams)
streamer.rank_playback_streams = lambda streams: list(streams)
streamer.sanitize_streams = lambda streams, require_source=True: list(streams)
streamer.enrich_stream_identity = lambda streams, *a, **k: list(streams)
streamer._resolve_balancer_provider = lambda *a, **k: [
    {"url": "https://cdn.example/video/master.m3u8", "source": "Collaps"}
]
streamer.set_cached_streams = lambda *a, **k: None
result = streamer.resolve_on_demand_streams(
    title="Test", year=2024, category="movies", require_catalog_identity=True
)
print(json.dumps(result))
"""
        result = subprocess.run(
            [sys.executable, "-c", code],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
            timeout=20,
            check=True,
        )
        streams = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(len(streams), 1)
        self.assertEqual(streams[0]["url"], "https://cdn.example/video/master.m3u8")

    def test_metadata_cloud_mode_bypasses_phone_network_guard_source_contract(self):
        text = (ROOT / "metadata_repair.py").read_text(encoding="utf-8")
        self.assertIn('cloud_mode = os.environ.get("MOVIA_CLOUD_MODE", "0") == "1"', text)
        self.assertIn('and not cloud_mode', text)
        self.assertIn('background_bulk_allowed()', text)


if __name__ == "__main__":
    unittest.main()
