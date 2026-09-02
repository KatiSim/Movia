import base64
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
import urllib.parse
from unittest.mock import patch

import balancer_integration
import zona_contract
import zona_legacy_adapters
from stream_validation import sanitize_streams


class ZonaTransferArchitectureTests(unittest.TestCase):
    def setUp(self):
        with zona_contract._SOURCE_CACHE_LOCK:
            zona_contract._SOURCE_CACHE.clear()
        with zona_contract._MIRROR_LOCK:
            zona_contract._MIRROR_FAILURES.clear()
            zona_contract._MIRROR_OPEN_UNTIL.clear()
        with zona_contract._TIME_LOCK:
            zona_contract._TIME_OFFSET_MS = 0
            zona_contract._TIME_EXPIRES_AT = time.monotonic() + 300.0

    def test_legacy_get_parameters_are_flattened_generically(self):
        query = zona_contract._params_query({
            "query": "русское название",
            "timeoutMs": 3500,
            "userInfo": {"locale": "ru-RU"},
            "optional": None,
        })

        self.assertEqual(
            query,
            [
                ("query", "русское название"),
                ("timeoutMs", "3500"),
                ("userInfo", '{"locale":"ru-RU"}'),
            ],
        )

    def test_legacy_get_video_sources_uses_json_envelope_and_optional_fields(self):
        calls = []

        def fake_request(base, path, query=None):
            calls.append((base, path, list(query or [])))
            payload = {
                "data": [
                    {
                        "id": 77,
                        "video_source_type_id": 8,
                        "download_link_key": "opaque-source-key",
                    }
                ]
            }
            return json.dumps(payload).encode("utf-8"), {}, None

        with patch.object(zona_contract, "_mirrors", return_value=["https://api.example.test"]), \
             patch.object(zona_contract, "_request", side_effect=fake_request), \
             patch.object(zona_contract, "_client_time", return_value="1700000000000.083"):
            sources, errors = zona_contract._fetch_video_sources(
                "101",
                "episode-1",
                movie_source_types=["7", 8, True],
                trailer=False,
                user_info={"userId": 42, "isPremium": False},
                installer_package="app.example",
            )

        self.assertEqual(errors, [])
        self.assertEqual(len(sources), 1)
        self.assertEqual(len(calls), 1)
        path = calls[0][1]
        query = calls[0][2]
        self.assertEqual(path, "getVideoSources")
        self.assertEqual([key for key, _ in query], ["params", "client_time"])
        params = json.loads(dict(query)["params"])
        self.assertEqual(
            params,
            {
                "kinopoiskId": 101,
                "episodeKey": "episode-1",
                "movieSourceTypes": ["7", "8"],
                "trailer": False,
                "userInfo": {"userId": 42, "isPremium": False},
                "installerPackage": "app.example",
            },
        )
        self.assertEqual(dict(query)["client_time"], "1700000000000.083")

    def test_legacy_get_video_sources_default_dto_is_minimal(self):
        params = zona_contract._normalized_video_sources_params(101, "")
        self.assertEqual(params, {"kinopoiskId": 101, "episodeKey": ""})
        self.assertEqual(
            zona_contract._serialized_params_query(params),
            [("params", '{"kinopoiskId":101,"episodeKey":""}')]
        )

    def test_default_movie_source_registry_is_forwarded(self):
        calls = []

        def fake_request(base, path, query=None):
            calls.append((base, path, list(query or [])))
            return json.dumps([
                {"id": 77, "videoSourceTypeId": 1, "downloadLinkKey": "opaque-source-key"}
            ]).encode("utf-8"), {}, None

        with patch.object(zona_contract, "_mirrors", return_value=["https://api.example.test"]),              patch.object(zona_contract, "_request", side_effect=fake_request),              patch.object(zona_contract, "_client_time", return_value="1700000000000.083"):
            sources, errors = zona_contract._fetch_video_sources(101, "")

        self.assertEqual(errors, [])
        self.assertEqual(len(sources), 1)
        params = json.loads(dict(calls[0][2])["params"])
        self.assertEqual(
            params["movieSourceTypes"],
            list(zona_contract.DEFAULT_MOVIE_SOURCE_TYPES),
        )

    def test_normal_content_resolution_explicitly_requests_non_trailer_sources(self):
        with patch.object(
            zona_contract, "_fetch_suggestions",
            return_value=([{"_provider_id": 501}], []),
        ):
            with patch.object(
                zona_contract, "_fetch_video_sources",
                return_value=([], []),
            ) as fetch_sources:
                with patch.object(
                    zona_contract, "resolve_zona_source_refs",
                    return_value=zona_contract.ZonaLookup("NO_RESULTS"),
                ):
                    result = zona_contract.resolve_zona_for_title(
                        "Generic title", media_type="movie",
                    )

        self.assertEqual(result.status, "NO_RESULTS")
        self.assertIs(fetch_sources.call_args.kwargs["trailer"], False)

    def test_request_advertises_json_content_type(self):
        class FakeResponse:
            status = 200
            headers = {}

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc_value, traceback):
                return False

            def read(self, limit=None):
                return b"{}"

        with patch.object(
            zona_contract._OPENER,
            "open",
            return_value=FakeResponse(),
        ) as open_mock:
            raw, headers, error = zona_contract._request(
                "https://api.example.test",
                "getVideoSources",
            )

        self.assertEqual(raw, b"{}")
        self.assertEqual(headers, {})
        self.assertIsNone(error)
        request = open_mock.call_args.args[0]
        self.assertEqual(request.get_header("Content-type"), "application/json")

    def test_protected_zona_request_attaches_cookie_but_stream_route_does_not(self):
        class FakeResponse:
            status = 200
            headers = {}

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc_value, traceback):
                return False

            def read(self, limit=None):
                return b"{}"

        with patch.object(zona_contract, "_zona_cookie", return_value="a" * 32), \
             patch.object(zona_contract._OPENER, "open", return_value=FakeResponse()) as open_mock:
            zona_contract._request(
                "https://api.example.test",
                "getVideoSources",
                [("client_time", "1700000000000.083")],
            )
            protected_request = open_mock.call_args.args[0]
            self.assertEqual(protected_request.get_header("Cookie"), "s=" + ("a" * 32))

            zona_contract._request(
                "https://api.example.test",
                "getMovieOrSerialSuggests/",
                [("client_time", "1700000000000.083")],
            )
            stream_request = open_mock.call_args.args[0]
            self.assertIsNone(stream_request.get_header("Cookie"))

    def test_zona_cookie_is_apk_derived_and_cached_for_one_day(self):
        with tempfile.NamedTemporaryFile() as handle:
            handle.write(b"\x00\x01\x7f\x80\xff\x10")
            handle.flush()
            path = Path(handle.name)
            stat = path.stat()
            identity = (path, int(stat.st_mtime_ns), int(stat.st_size))
            with patch.object(zona_contract, "_legacy_apk_identity", return_value=identity), \
                 patch.object(zona_contract.secrets, "randbits", return_value=0):
                first = zona_contract._zona_cookie(1_700_000_000_000)
                second = zona_contract._zona_cookie(1_700_000_000_001)

        self.assertEqual(first, second)
        self.assertRegex(first, r"^[0-9a-f]{32}$")
        self.assertEqual(len(first), 32)

    def test_zona_cookie_reproduces_jvm_signed_int_shift(self):
        with tempfile.NamedTemporaryFile() as handle:
            handle.write(b"\x00")
            handle.flush()
            path = Path(handle.name)
            stat = path.stat()
            identity = (path, int(stat.st_mtime_ns), int(stat.st_size))
            client_time_ms = (1 << 31) * 1000
            with patch.object(zona_contract, "_legacy_apk_identity", return_value=identity), \
                 patch.object(zona_contract.secrets, "randbits", return_value=0):
                cookie = zona_contract._zona_cookie(client_time_ms)

        day = client_time_ms // zona_contract._MILLIS_IN_DAY
        sum_a, sum_b = 0, 1
        for raw_byte in b"\x00":
            signed_byte = raw_byte if raw_byte < 128 else raw_byte - 256
            sum_b = ((signed_byte + day) % 256 + sum_b) % zona_contract._ADLER_MOD
            sum_a = (sum_a + sum_b) % zona_contract._ADLER_MOD
        checksum = (sum_a << 16) + sum_b
        expected_first = 0xFFFFFFFF80000000
        expected_second = (checksum ^ expected_first) & ((1 << 64) - 1)
        expected = (
            expected_first.to_bytes(8, "big").hex()
            + expected_second.to_bytes(8, "big").hex()
        )
        self.assertEqual(cookie, expected)

    def test_mirror_circuit_is_scoped_per_endpoint(self):
        base = "https://api.example.test"
        zona_contract._mirror_failure(base, "getMovieOrSerialSuggests")

        self.assertFalse(zona_contract._mirror_available(base, "getMovieOrSerialSuggests"))
        self.assertTrue(zona_contract._mirror_available(base, "search"))

        zona_contract._mirror_success(base, "getMovieOrSerialSuggests")
        self.assertTrue(zona_contract._mirror_available(base, "getMovieOrSerialSuggests"))

    def test_legacy_search_fallback_uses_top_level_pagination_and_type(self):
        calls = []

        def fake_request(base, path, query=None):
            params = dict(query or [])
            calls.append((path, params))
            if path == "getMovieOrSerialSuggests":
                return None, {}, "HTTP_ERROR:404"
            if path == "search":
                if params.get("query") == "Северный маршрут":
                    payload = {"total": 0, "data": [], "collation": []}
                else:
                    payload = {
                        "total": 2,
                        "data": [
                            {
                                "id": 501,
                                "name": "Northern Route",
                                "originalName": "Северный маршрут",
                                "serial": False,
                                "year": 2021,
                            },
                            {
                                "id": 502,
                                "name": "Southern Route",
                                "originalName": "Южный маршрут",
                                "serial": False,
                                "year": 2021,
                            },
                        ],
                        "collation": [],
                    }
                return json.dumps(payload).encode("utf-8"), {}, None
            raise AssertionError(path)

        with patch.object(zona_contract, "_mirrors", return_value=["https://api.example.test"]),              patch.object(zona_contract, "_request", side_effect=fake_request):
            matches, errors = zona_contract._fetch_suggestions(
                "Северный маршрут",
                ["Северный маршрут"],
                2021,
                "movie",
                None,
            )

        self.assertEqual([item["_provider_id"] for item in matches], [501])
        self.assertEqual(errors, [])
        search_calls = [params for path, params in calls if path == "search"]
        self.assertEqual(len(search_calls), 2)
        for params in search_calls:
            self.assertEqual(params["limit"], "200")
            self.assertEqual(params["offset"], "0")
            self.assertEqual(params["hideUnavailable"], "true")
        self.assertEqual(zona_contract._item_kind({"serial": True}), "tv")
        self.assertEqual(zona_contract._item_kind({"serial": False}), "movie")

    def test_logical_source_cache_is_bounded_and_force_refreshable(self):
        calls = []

        def fake_request(base, path, query=None):
            calls.append((base, path))
            payload = [
                {
                    "id": "source-a",
                    "videoSourceTypeId": 7,
                    "downloadLinkKey": "opaque-source-key",
                }
            ]
            return json.dumps(payload).encode("utf-8"), {}, None

        with patch.object(zona_contract, "_mirrors", return_value=["https://api.example.test"]),                 patch.object(zona_contract, "_request", side_effect=fake_request),                 patch.object(zona_contract, "_client_time", return_value="1700000000000.083"):
            first, first_errors = zona_contract._fetch_video_sources(101, "episode-1")
            second, second_errors = zona_contract._fetch_video_sources(101, "episode-1")
            refreshed, refreshed_errors = zona_contract._fetch_video_sources(
                101, "episode-1", force_refresh=True
            )

        self.assertEqual(len(calls), 2)
        self.assertEqual(first, second)
        self.assertEqual(first, refreshed)
        self.assertEqual(first_errors, [])
        self.assertEqual(second_errors, [])
        self.assertEqual(refreshed_errors, [])

    def test_extractor_fanout_is_parallel_and_order_is_deterministic(self):
        active = 0
        peak = 0
        lock = threading.Lock()

        def fake_stream(source, season, episode, episode_key):
            nonlocal active, peak
            with lock:
                active += 1
                peak = max(peak, active)
            try:
                time.sleep(0.04)
                extractor = source["videoSourceTypeId"]
                return [{
                    "source": "Zona",
                    "provider": f"extractor-{extractor}",
                    "url": f"https://media.example.test/{extractor}.m3u8",
                    "voice": "Original",
                    "quality": "1080p",
                }], None
            finally:
                with lock:
                    active -= 1

        sources = [
            {"id": "a", "videoSourceTypeId": 7, "downloadLinkKey": "key-a"},
            {"id": "b", "videoSourceTypeId": 8, "downloadLinkKey": "key-b"},
            {"id": "c", "videoSourceTypeId": 9, "downloadLinkKey": "key-c"},
        ]
        with patch.object(zona_contract, "_refresh_server_time"),                 patch.object(zona_contract, "_fetch_streams_for_source", side_effect=fake_stream),                 patch.dict(zona_contract.os.environ, {"ZONA_MAX_EXTRACTORS": "3"}):
            result = zona_contract.resolve_zona_source_refs(
                provider_id=101,
                sources=sources,
                season=1,
                episode=1,
            )

        self.assertEqual(result.status, "OK")
        self.assertEqual(result.source_refs, 3)
        self.assertGreaterEqual(peak, 2)
        self.assertEqual(
            [item["url"] for item in result.streams],
            [
                "https://media.example.test/7.m3u8",
                "https://media.example.test/8.m3u8",
                "https://media.example.test/9.m3u8",
            ],
        )

    def test_link_extractor_returns_direct_source_url_and_metadata(self):
        source = {
            "id": "logical-source-77",
            "videoSourceTypeId": 49,
            "videoContentTypeId": 3,
            "downloadLinkKey": "https://media.example.test/logical.m3u8",
            "info": json.dumps({
                "tran": "Original",
                "lang": "ru",
                "res": "1080p",
            }),
        }
        streams, error = zona_contract._fetch_streams_for_source(
            source, season=None, episode=None, episode_key=None,
        )

        self.assertIsNone(error)
        self.assertEqual(len(streams), 1)
        self.assertEqual(streams[0]["provider_item_id"], "logical-source-77")
        self.assertEqual(streams[0]["source_id"], "logical-source-77")
        self.assertEqual(streams[0]["source_type_id"], 49)
        self.assertEqual(streams[0]["url"], "https://media.example.test/logical.m3u8")
        self.assertEqual(streams[0]["voice"], "Original")
        self.assertEqual(streams[0]["quality"], "1080p")

    def test_bazon_is_explicitly_blocked_without_fabricating_stream(self):
        streams, error = zona_legacy_adapters.resolve_local_source(
            {"videoSourceTypeId": 5, "downloadLinkKey": "opaque-bazon-hash"},
        )
        self.assertEqual(streams, [])
        self.assertEqual(
            error,
            "ADAPTER_BLOCKED:bazon:RECAPTCHA_RSA_AES_TRANSFORMER_REQUIRED",
        )

    def test_unported_extractor_does_not_call_fabricated_stream_route(self):
        source = {
            "id": "logical-source-11",
            "videoSourceTypeId": 11,
            "downloadLinkKey": "opaque-source-key",
        }
        with patch.object(zona_contract, "_request") as request_mock:
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=None, episode=None, episode_key=None,
            )

        self.assertEqual(streams, [])
        self.assertEqual(error, "ADAPTER_NOT_PORTED:ustore")
        request_mock.assert_not_called()

    def test_streaminfo_metadata_is_preserved_only_when_safe(self):
        cleaned = sanitize_streams([{
            "source": "Zona",
            "url": "https://media.example.test/master.m3u8",
            "voice": "Original",
            "quality": "1080p",
            "downloadUrl": "https://media.example.test/file.mp4",
            "downloadHeaders": {
                "Accept": "video/*",
                "Cookie": "session=must-not-cross-boundary",
            },
            "skipIntervals": [
                {"startMs": 10, "endMs": 20},
                {"start": -1, "end": 4},
                ["bad", 5],
            ],
            "reloadData": {"token": "must-not-be-persisted"},
        }])

        self.assertEqual(len(cleaned), 1)
        stream = cleaned[0]
        self.assertEqual(stream["download_url"], "https://media.example.test/file.mp4")
        self.assertEqual(stream["download_headers"], {"Accept": "video/*"})
        self.assertEqual(stream["skip_intervals"], [{"start": 10, "end": 20}])
        self.assertNotIn("reload_data", stream)
        self.assertNotIn("Cookie", stream.get("download_headers", {}))

    def test_bulk_resolver_uses_zona_content_lookup(self):
        captured = {}

        def fake_query(**kwargs):
            captured.update(kwargs)
            return [{
                "source": "Zona",
                "url": "https://media.example.test/bulk.m3u8",
                "voice": "Original",
                "quality": "1080p",
            }]

        with patch.object(
            balancer_integration,
            "query_open_balancer_stream",
            side_effect=fake_query,
        ):
            result = balancer_integration.resolve_balancer(
                title="Generic title",
                year=2020,
                expected_titles=["Generic title"],
                media_type="movie",
            )

        self.assertIsNotNone(result)
        self.assertTrue(captured["allow_zona_content_lookup"])
        self.assertEqual(result["streams"][0]["source"], "Zona")


    def test_successful_mirrors_stop_after_first_valid_response(self):
        bases = ["https://api-a.example.test", "https://api-b.example.test"]
        suggest_calls = []
        source_calls = []

        def fake_request(base, path, query=None):
            if path == "getMovieOrSerialSuggests":
                suggest_calls.append(base)
                payload = {
                    "data": [{
                        "id": 501,
                        "name": "Generic title",
                        "year": 2020,
                        "serial": False,
                    }],
                }
                return json.dumps(payload).encode("utf-8"), {}, None
            if path == "getVideoSources":
                source_calls.append(base)
                payload = {
                    "data": [{
                        "id": 77,
                        "videoSourceTypeId": 8,
                        "downloadLinkKey": "opaque-source-key",
                    }],
                }
                return json.dumps(payload).encode("utf-8"), {}, None
            raise AssertionError(path)

        with patch.object(zona_contract, "_mirrors", return_value=bases):
            with patch.object(zona_contract, "_request", side_effect=fake_request):
                with patch.object(zona_contract, "_server_time_offset_ms", return_value=0):
                    matches, errors = zona_contract._fetch_suggestions(
                        "Generic title",
                        ["Generic title"],
                        2020,
                        "movie",
                        None,
                    )
                    sources, source_errors = zona_contract._fetch_video_sources(
                        101,
                        "episode-1",
                    )

        self.assertEqual([item["_provider_id"] for item in matches], [501])
        self.assertEqual(len(sources), 1)
        self.assertEqual(errors, [])
        self.assertEqual(source_errors, [])
        self.assertEqual(suggest_calls, [bases[0]])
        self.assertEqual(source_calls, [bases[0]])
    def test_provider_outcome_diagnostics_are_preserved(self):
        with patch.object(
            balancer_integration,
            "resolve_zona_for_title",
            return_value=zona_contract.ZonaLookup(
                "PROVIDER_ERROR",
                [],
                suggestions=1,
                source_refs=1,
                errors=["e1", "e2"],
            ),
        ):
            result = balancer_integration.query_zona_api(
                "Generic title",
                year=2020,
                media_type="movie",
                allow_torrent_fallback=False,
                allow_zona_content_lookup=True,
            )
        self.assertEqual(result, [])
        diagnostics = balancer_integration.get_last_resolution_diagnostics()
        self.assertEqual(diagnostics["status"], "PROVIDER_ERROR")
        self.assertEqual(diagnostics["error_count"], 2)

    def test_episode_key_is_derived_for_series_requests(self):
        with patch.object(
            zona_contract,
            "_fetch_suggestions",
            return_value=([{"_provider_id": 501}], []),
        ):
            with patch.object(
                zona_contract,
                "_fetch_video_sources",
                return_value=([], []),
            ) as fetch_sources:
                with patch.object(
                    zona_contract,
                    "resolve_zona_source_refs",
                    return_value=zona_contract.ZonaLookup("NO_RESULTS"),
                ) as resolve_refs:
                    zona_contract.resolve_zona_for_title(
                        "Generic title",
                        media_type="tv",
                        season=2,
                        episode=3,
                    )

        self.assertEqual(
            fetch_sources.call_args.args[1],
            "S02E03",
        )
        self.assertEqual(
            resolve_refs.call_args.kwargs["episode_key"],
            "S02E03",
        )


    def test_mobilink_adapter_uses_old_get_mobi_video_contract(self):
        calls = []

        def fake_provider(path, query):
            calls.append((path, list(query)))
            return {"lqUrl": "https://media.example.test/mobilink-lq.mp4"}, None

        source = {
            "id": "mobilink-source-1",
            "videoSourceTypeId": 1,
            "downloadLinkKey": "opaque-mobilink-id",
        }
        with patch.object(
            zona_contract,
            "_fetch_provider_json",
            side_effect=fake_provider,
        ), patch.object(
            zona_contract,
            "_client_time",
            return_value="1700000000000.083",
        ), patch.object(
            zona_contract,
            "zona_user_agent",
            return_value="Zona/test",
        ):
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=None, episode=None, episode_key=None,
            )

        self.assertIsNone(error)
        self.assertEqual(calls, [(
            "getMobiVideo",
            [
                ("id", "opaque-mobilink-id"),
                ("client_time", "1700000000000.083"),
            ],
        )])
        self.assertEqual(streams[0]["url"], "https://media.example.test/mobilink-lq.mp4")
        self.assertEqual(streams[0]["voice"], "Русский язык")
        self.assertEqual(streams[0]["quality"], "LQ")
        self.assertEqual(streams[0]["source_type_id"], 1)


    def test_veoveo_adapter_uses_catalog_api_and_episode_filter(self):
        payload = [
            {
                "order": 1,
                "season": {"order": 1},
                "episodeVariants": [
                    {
                        "title": "1080p",
                        "filepath": "https://cdn.example.test/veoveo/1080.m3u8",
                    },
                    {
                        "title": "720p",
                        "filepath": "https://cdn.example.test/veoveo/720.mp4",
                    },
                ],
            },
            {
                "order": 2,
                "season": {"order": 1},
                "episodeVariants": [
                    {
                        "title": "1080p",
                        "filepath": "https://cdn.example.test/veoveo/wrong.m3u8",
                    },
                ],
            },
        ]
        calls = []

        def fake_fetch(url, headers):
            calls.append((url, dict(headers)))
            self.assertEqual(
                url,
                (
                    "https://api.rstprgapipt.com/balancer-api/proxy/"
                    "playlists/catalog-api/episodes?content-id=content-45"
                ),
            )
            return json.dumps(payload), None

        source = {
            "id": "veoveo-source-45",
            "videoSourceTypeId": 45,
            "downloadLinkKey": "content-45",
        }
        with patch.object(
            zona_contract,
            "_fetch_provider_text",
            side_effect=fake_fetch,
        ), patch.object(
            zona_contract,
            "zona_user_agent",
            return_value="Zona/test",
        ):
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=1, episode=1, episode_key="S01E01",
            )

        self.assertIsNone(error)
        self.assertEqual(calls, [
            (
                (
                    "https://api.rstprgapipt.com/balancer-api/proxy/"
                    "playlists/catalog-api/episodes?content-id=content-45"
                ),
                {"User-Agent": "Zona/test"},
            ),
        ])
        self.assertEqual(
            [stream["url"] for stream in streams],
            [
                "https://cdn.example.test/veoveo/1080.m3u8",
                "https://cdn.example.test/veoveo/720.mp4",
            ],
        )
        self.assertEqual(
            [stream["quality"] for stream in streams],
            ["1080p", "720p"],
        )
        self.assertEqual(streams[0]["source_type_id"], 45)
        self.assertEqual(streams[0]["translation"], "1080p")


    def test_rutube_adapter_uses_legacy_options_and_playlist_contract(self):
        api_payload = {
            "captions": [
                {
                    "langTitle": "Русские",
                    "file": "https://cdn.example.test/subtitles/ru.vtt",
                    "code": "ru",
                    "is_autogenerated": False,
                },
                {
                    "langTitle": "Auto",
                    "file": "https://cdn.example.test/subtitles/auto.vtt",
                    "code": "ru",
                    "is_autogenerated": True,
                },
            ],
            "video_balancer": {
                "m3u8": "https://stream.example.test/rutube/master.m3u8",
            },
        }
        playlist = (
            "https://cdn.example.test/v.mp4?i=1920x1080_1 "
            "https://cdn.example.test/v.mp4?i=1280x720_2"
        )
        calls = []

        def fake_fetch(url, headers):
            calls.append((url, dict(headers)))
            if url == (
                "https://rutube.ru/api/play/options/abc-123/"
                "?no_404=true&referer=&pver=v2&client=wdp&mq=all&ac_client=web"
            ):
                return json.dumps(api_payload, ensure_ascii=False), None
            if url == "https://stream.example.test/rutube/master.m3u8":
                return playlist, None
            raise AssertionError(url)

        source = {
            "id": "rutube-source-42",
            "videoSourceTypeId": 42,
            "downloadLinkKey": "abc-123",
        }
        with patch.object(
            zona_contract,
            "_fetch_provider_text",
            side_effect=fake_fetch,
        ):
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=None, episode=None, episode_key=None,
            )

        expected_headers = {
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36"
            ),
        }
        self.assertIsNone(error)
        self.assertEqual(
            calls,
            [
                (
                    "https://rutube.ru/api/play/options/abc-123/"
                    "?no_404=true&referer=&pver=v2&client=wdp&mq=all&ac_client=web",
                    expected_headers,
                ),
                (
                    "https://stream.example.test/rutube/master.m3u8",
                    expected_headers,
                ),
            ],
        )
        self.assertEqual(
            [stream["quality"] for stream in streams],
            ["1080p", "720p"],
        )
        self.assertEqual(
            [stream["url"] for stream in streams],
            [
                "https://cdn.example.test/v.mp4?i=1920x1080_1",
                "https://cdn.example.test/v.mp4?i=1280x720_2",
            ],
        )
        self.assertEqual(streams[0]["source_type_id"], 42)
        self.assertEqual(len(streams[0]["subtitles"]), 1)


    def test_kinoteatr_adapter_fetches_page_and_extracts_video_src(self):
        page = """
        <html>
          <h1 itemprop="name">Test Film</h1>
          <div class="category_header_nolink"><h3>Русский язык</h3></div>
          <video data-video-src="/video/test-film/master.mp4"></video>
        </html>
        """
        source = {
            "id": "kinoteatr-source-14",
            "videoSourceTypeId": 14,
            "downloadLinkKey": "12345",
        }
        with patch.object(
            zona_contract,
            "_fetch_provider_text",
            return_value=(page, None),
        ) as fetch_page:
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=None, episode=None, episode_key=None,
            )

        self.assertIsNone(error)
        fetch_page.assert_called_once_with(
            "https://www.kino-teatr.ru/video/12345/",
            {"User-Agent": zona_contract.zona_user_agent()},
        )
        self.assertEqual(len(streams), 1)
        self.assertEqual(
            streams[0]["url"],
            "https://www.kino-teatr.ru/video/test-film/master.mp4",
        )
        self.assertEqual(streams[0]["source_type_id"], 14)
        self.assertEqual(streams[0]["quality"], "LQ")
        self.assertEqual(streams[0]["voice"], "Русский язык")
        self.assertEqual(streams[0]["translation"], "Русский язык")



    def test_kinomania_adapter_fetches_public_page_sources(self):
        page = """
        <video-js id="player-7">
          <source src="/media/primary.mp4">
        </video-js>
        <script>
          const playerId = "player-7"; title: "Русская дорожка (рус.)"
          modalVideo('/media/extra.mp4', 'Original')
        </script>
        """
        calls = []

        def fake_fetch(url, headers):
            calls.append((url, dict(headers)))
            return page, None

        source = {
            "id": "kinomania-source-7",
            "videoSourceTypeId": 7,
            "downloadLinkKey": "12345",
        }
        with patch.object(
            zona_contract,
            "_fetch_provider_text",
            side_effect=fake_fetch,
        ), patch.object(
            zona_contract,
            "zona_user_agent",
            return_value="Zona/test",
        ):
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=None, episode=None, episode_key=None,
            )

        self.assertIsNone(error)
        self.assertEqual(
            calls,
            [(
                "https://www.kinomania.ru/film/12345/trailers",
                {"User-Agent": "Zona/test"},
            )],
        )
        self.assertEqual(
            [stream["url"] for stream in streams],
            [
                "https://www.kinomania.ru/media/primary.mp4",
                "https://www.kinomania.ru/media/extra.mp4",
            ],
        )
        self.assertEqual([stream["quality"] for stream in streams], ["LQ", "LQ"])
        self.assertEqual(streams[0]["voice"], "Русская дорожка (рус.)")
        self.assertEqual(streams[0]["language"], "ru")
        self.assertEqual(streams[0]["source_type_id"], 7)


    def test_filmru_adapter_uses_legacy_trailer_page_and_extracts_both_formats(self):
        page = """
        <video-js id="film-player">
          <source src="/trailers/primary.mp4" type="video/mp4">
          description="Трейлер (англ.)"
        </video-js>
        ads:
        <script>
          "trailerplayer", {src: '/trailers/fallback.mp4', type: 'video/mp4',
          description: 'Дублированный (рус.)'}
        ads:
        </script>
        """
        source = {
            "id": "filmru-source-36",
            "videoSourceTypeId": 36,
            "downloadLinkKey": "12345",
        }
        with patch.object(
            zona_contract,
            "_fetch_provider_text",
            return_value=(page, None),
        ) as fetch_page:
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=None, episode=None, episode_key=None,
            )

        self.assertIsNone(error)
        fetch_page.assert_called_once_with(
            "https://www.film.ru/node/12345/trailers",
            {"User-Agent": zona_contract.zona_user_agent()},
        )
        self.assertEqual(
            [stream["url"] for stream in streams],
            [
                "https://www.film.ru/trailers/primary.mp4",
                "https://www.film.ru/trailers/fallback.mp4",
            ],
        )
        self.assertEqual([stream["quality"] for stream in streams], ["LQ", "LQ"])
        self.assertEqual(streams[0]["language"], "en")
        self.assertEqual(streams[1]["language"], "ru")
        self.assertTrue(all(stream["trailer"] for stream in streams))
        self.assertTrue(all(stream["source_type_id"] == 36 for stream in streams))



    def test_ok_adapter_uses_video_page_data_options_and_profiles(self):
        options = {
            "flashvars": {
                "metadata": {
                    "videos": [
                        {
                            "name": "hd",
                            "url": "https://cdn.example.test/ok/720.mp4",
                        },
                        {
                            "name": "full",
                            "url": "https://cdn.example.test/ok/1080.mp4",
                        },
                    ],
                },
            },
        }
        encoded_options = json.dumps(options).replace('"', "&quot;")
        page = f'<div data-options="{encoded_options}"></div>'
        source = {
            "id": "ok-source-46",
            "videoSourceTypeId": 46,
            "downloadLinkKey": "987654",
        }
        with patch.object(
            zona_contract,
            "_fetch_provider_text",
            return_value=(page, None),
        ) as fetch_page:
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=None, episode=None, episode_key=None,
            )

        self.assertIsNone(error)
        fetch_page.assert_called_once_with(
            "https://ok.ru/video/987654",
            {"User-Agent": zona_contract.zona_user_agent()},
        )
        self.assertEqual(
            [stream["url"] for stream in streams],
            [
                "https://cdn.example.test/ok/720.mp4",
                "https://cdn.example.test/ok/1080.mp4",
            ],
        )
        self.assertEqual([stream["quality"] for stream in streams], ["720p", "1080p"])
        self.assertEqual([stream["resolution"] for stream in streams], ["720p", "1080p"])
        self.assertTrue(all(stream["source_type_id"] == 46 for stream in streams))
        self.assertEqual(streams[0]["downloadFormat"], "M3U8")



    def test_kinobadi_adapter_uses_player_index_profiles_and_episode_query(self):
        page = """
        <select id="translator-name">
          <option value="ru" selected="selected">Русская дорожка</option>
        </select>
        <script>
          player = {file:"[720]/media/720.m3u8,[1080]/media/1080.m3u8"};
        </script>
        """
        source = {
            "id": "kinobadi-source-51",
            "videoSourceTypeId": 51,
            "downloadLinkKey": "movie-abc",
        }
        with patch.object(
            zona_contract,
            "_fetch_provider_text",
            return_value=(page, None),
        ) as fetch_page:
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=2, episode=7, episode_key=None,
            )

        self.assertIsNone(error)
        fetch_page.assert_called_once_with(
            "https://vip.kinobadi.im/player_index.php?id=movie-abc&season=2&episode=7",
            {"User-Agent": zona_contract.zona_user_agent()},
        )
        self.assertEqual(
            [stream["url"] for stream in streams],
            [
                "https://vip.kinobadi.im/media/720.m3u8",
                "https://vip.kinobadi.im/media/1080.m3u8",
            ],
        )
        self.assertEqual([stream["quality"] for stream in streams], ["720p", "1080p"])
        self.assertEqual([stream["voice"] for stream in streams], ["Русская дорожка"] * 2)
        self.assertEqual([stream["language"] for stream in streams], ["ru", "ru"])
        self.assertTrue(all(stream["source_type_id"] == 51 for stream in streams))



    def test_plvideo_adapter_uses_public_api_and_balancer_playlist(self):
        api_payload = {
            "item": {
                "profiles": {
                    "captions": [
                        {
                            "langTitle": "Русские",
                            "file": "https://cdn.example.test/subtitles/ru.vtt",
                            "code": "ru",
                            "is_autogenerated": False,
                        },
                        {
                            "langTitle": "Auto",
                            "file": "https://cdn.example.test/subtitles/auto.vtt",
                            "code": "ru",
                            "is_autogenerated": True,
                        },
                    ],
                },
            },
            "video_balancer": {
                "m3u8": "https://stream.example.test/master.m3u8",
            },
        }
        playlist = (
            'https://cdn.example.test/v.mp4?i=1920x1080_1 '
            'https://cdn.example.test/v.mp4?i=1280x720_2'
        )
        calls = []

        def fake_fetch(url, headers):
            calls.append((url, dict(headers)))
            if url.endswith("/abc123?aud=16"):
                return json.dumps(api_payload, ensure_ascii=False), None
            if url == "https://stream.example.test/master.m3u8":
                return playlist, None
            raise AssertionError(url)

        source = {
            "id": "plvideo-source-43",
            "videoSourceTypeId": 43,
            "downloadLinkKey": "abc123",
        }
        with patch.object(
            zona_contract,
            "_fetch_provider_text",
            side_effect=fake_fetch,
        ):
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=None, episode=None, episode_key=None,
            )

        self.assertIsNone(error)
        self.assertEqual(
            calls,
            [
                (
                    "https://api.g1.plvideo.ru/v1/videos/abc123?aud=16",
                    {
                        "User-Agent": (
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            "AppleWebKit/537.36 (KHTML, like Gecko) "
                            "Chrome/137.0.0.0 Safari/537.36"
                        ),
                    },
                ),
                (
                    "https://stream.example.test/master.m3u8",
                    {
                        "User-Agent": (
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            "AppleWebKit/537.36 (KHTML, like Gecko) "
                            "Chrome/137.0.0.0 Safari/537.36"
                        ),
                    },
                ),
            ],
        )
        self.assertEqual(
            [stream["quality"] for stream in streams],
            ["1080p", "720p"],
        )
        self.assertEqual(
            [stream["url"] for stream in streams],
            [
                "https://cdn.example.test/v.mp4?i=1920x1080_1",
                "https://cdn.example.test/v.mp4?i=1280x720_2",
            ],
        )
        self.assertEqual(streams[0]["source_type_id"], 43)
        self.assertEqual(streams[0]["language"], "ru")
        self.assertEqual(len(streams[0]["subtitles"]), 1)



    def test_cdnvideohub_adapter_retries_dynamic_publisher_and_resolves_hls(self):
        playlist_payload = {
            "items": [
                {
                    "vkId": "item-hls",
                    "voiceStudio": "Русская дорожка",
                    "source": "CDNVideoHub",
                },
                {
                    "vkId": "item-profiles",
                    "voiceStudio": "Оригинал",
                    "source": "CDNVideoHub",
                },
            ],
        }
        fallback_hls = {
            "source": {
                "hlsUrl": "https://stream.example.test/cdnvideohub/master.m3u8",
            },
        }
        fallback_profiles = {
            "source": {
                "hlsUrl": "",
                "mpegFullHdUrl": "https://cdn.example.test/cdnvideohub/1080.m3u8",
                "mpegHighUrl": "https://cdn.example.test/cdnvideohub/720.m3u8",
            },
        }
        hls_playlist = (
            "#EXTM3U\n"
            "#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1920x1080\n"
            "1080.m3u8\n"
            "#EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=1280x720\n"
            "720.m3u8\n"
        )
        calls = []

        def fake_fetch(url, headers):
            calls.append((url, dict(headers)))
            if "playlist?pub=6&id=movie-33&aggr=kp" in url:
                return "", "HTTP_ERROR:404"
            if "playlist?pub=7&id=movie-33&aggr=kp" in url:
                return json.dumps(playlist_payload), None
            if url.endswith("/fallback/item-hls"):
                return json.dumps(fallback_hls), None
            if url == "https://stream.example.test/cdnvideohub/master.m3u8":
                return hls_playlist, None
            if url.endswith("/fallback/item-profiles"):
                return json.dumps(fallback_profiles), None
            raise AssertionError(url)

        source = {
            "id": "cdnvideohub-source-33",
            "videoSourceTypeId": 33,
            "downloadLinkKey": "movie-33",
        }
        with patch.object(
            zona_contract,
            "_fetch_provider_text",
            side_effect=fake_fetch,
        ):
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=None, episode=None, episode_key=None,
            )

        self.assertIsNone(error)
        self.assertEqual(
            [url for url, _ in calls],
            [
                (
                    "https://plapi.cdnvideohub.com/api/v1/player/sv/playlist"
                    "?pub=6&id=movie-33&aggr=kp"
                ),
                (
                    "https://plapi.cdnvideohub.com/api/v1/player/sv/playlist"
                    "?pub=7&id=movie-33&aggr=kp"
                ),
                (
                    "https://plapi.cdnvideohub.com/api/v1/player/sv/video/fallback"
                    "/item-hls"
                ),
                "https://stream.example.test/cdnvideohub/master.m3u8",
                (
                    "https://plapi.cdnvideohub.com/api/v1/player/sv/video/fallback"
                    "/item-profiles"
                ),
            ],
        )
        self.assertEqual(
            [stream["url"] for stream in streams],
            [
                "https://stream.example.test/cdnvideohub/1080.m3u8",
                "https://stream.example.test/cdnvideohub/720.m3u8",
                "https://cdn.example.test/cdnvideohub/1080.m3u8",
                "https://cdn.example.test/cdnvideohub/720.m3u8",
            ],
        )
        self.assertEqual(
            [stream["quality"] for stream in streams],
            ["1080p", "720p", "1080p", "720p"],
        )
        self.assertEqual([stream["source_type_id"] for stream in streams], [33] * 4)
        self.assertEqual(streams[0]["voice"], "Русская дорожка (CDNVideoHub)")
        self.assertEqual(streams[2]["voice"], "Оригинал (CDNVideoHub)")

    def test_sooplive_adapter_posts_legacy_headers_and_reads_quantity_info(self):
        payload = {
            "data": {
                "files": [
                    {
                        "quantity_info": [
                            {
                                "resolution": "1920x1080",
                                "file": "https://cdn.example.test/sooplive/1080.mp4",
                            },
                            {
                                "resolution": "1280x720",
                                "file": "https://cdn.example.test/sooplive/720.mp4",
                            },
                        ],
                    },
                ],
            },
        }
        calls = []

        def fake_post(url, headers):
            calls.append((url, dict(headers)))
            return json.dumps(payload, ensure_ascii=False), None

        source = {
            "id": "sooplive-source-39",
            "videoSourceTypeId": 39,
            "downloadLinkKey": "title-123",
            "info": {"lang": "ru", "tran": "Русская дорожка"},
        }
        with patch.object(
            zona_contract,
            "_fetch_provider_post_text",
            side_effect=fake_post,
        ), patch.object(
            zona_contract,
            "zona_user_agent",
            return_value="Zona/test",
        ):
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=None, episode=None, episode_key=None,
            )

        self.assertIsNone(error)
        self.assertEqual(
            calls,
            [
                (
                    "https://api.m.sooplive.co.kr/station/video/a/view",
                    {
                        "User-Agent": "Zona/test",
                        "nTitleNo": "title-123",
                        "nApiLevel": "10",
                        "nPlaylistIdx": "0",
                    },
                ),
            ],
        )
        self.assertEqual(
            [stream["url"] for stream in streams],
            [
                "https://cdn.example.test/sooplive/1080.mp4",
                "https://cdn.example.test/sooplive/720.mp4",
            ],
        )
        self.assertEqual(
            [stream["quality"] for stream in streams],
            ["1080p", "720p"],
        )
        self.assertEqual(streams[0]["source_type_id"], 39)
        self.assertEqual(streams[0]["voice"], "Русская дорожка")
        self.assertEqual(streams[0]["language"], "ru")


    def test_fancdn_adapter_loads_dynamic_config_and_hls_profiles(self):
        config = {
            "u": "FanCDN/test",
            "r": "https://fancdn.net/",
            "t": "/video/{movieId}",
        }
        playlist_page = (
            "var playlist = "
            "[{\"title\":\"Русская дорожка\","
            "\"file\":\"https://fancdn.net/player/abc\","
            "\"subtitles\":\"[Русские]https://cdn.example.test/subs/ru.vtt\"}];"
        )
        player_page = (
            "./720.mp4:hls:master.m3u8 "
            "./1080.mp4:hls:master.m3u8"
        )
        calls = []

        def fake_fetch(url, headers):
            calls.append((url, dict(headers)))
            if url == "https://fancdn.net/static/ext35.txt":
                return json.dumps(config), None
            if url == "https://fancdn.net/video/abc":
                return playlist_page, None
            if url == "https://fancdn.net/player/abc":
                return player_page, None
            raise AssertionError(url)

        source = {
            "id": "fancdn-source-35",
            "videoSourceTypeId": 35,
            "downloadLinkKey": "abc",
        }
        with patch.object(
            zona_contract,
            "_fetch_provider_text",
            side_effect=fake_fetch,
        ), patch.object(
            zona_contract,
            "zona_user_agent",
            return_value="Zona/test",
        ):
            streams, error = zona_contract._fetch_streams_for_source(
                source, season=None, episode=None, episode_key=None,
            )

        self.assertIsNone(error)
        self.assertEqual(
            calls,
            [
                (
                    "https://fancdn.net/static/ext35.txt",
                    {"User-Agent": "Zona/test"},
                ),
                (
                    "https://fancdn.net/video/abc",
                    {
                        "User-Agent": "FanCDN/test",
                        "Referer": "https://fancdn.net/",
                    },
                ),
                (
                    "https://fancdn.net/player/abc",
                    {
                        "User-Agent": "FanCDN/test",
                        "Referer": "https://fancdn.net/",
                    },
                ),
            ],
        )
        self.assertEqual(
            [stream["url"] for stream in streams],
            [
                "https://fancdn.net/player/720.mp4:hls:master.m3u8",
                "https://fancdn.net/player/1080.mp4:hls:master.m3u8",
            ],
        )
        self.assertEqual(
            [stream["quality"] for stream in streams],
            ["720p", "1080p"],
        )
        self.assertEqual(streams[0]["source_type_id"], 35)
        self.assertEqual(streams[0]["voice"], "Русская дорожка")
        self.assertEqual(streams[0]["language"], "ru")
        self.assertEqual(len(streams[0]["subtitles"]), 1)


class HdrezkaLegacyAdapterTests(unittest.TestCase):
    def setUp(self):
        with zona_legacy_adapters._HDREZKA_CONFIG_LOCK:
            zona_legacy_adapters._HDREZKA_CONFIG_CACHE.clear()
            zona_legacy_adapters._HDREZKA_CONFIG_EXPIRES_AT = 0.0

    @staticmethod
    def _inner(value):
        raw = bytearray(json.dumps(value, ensure_ascii=False).encode("utf-8"))
        for index in range(len(raw)):
            raw[index] ^= 59
        return base64.b64encode(bytes(raw)).decode("ascii")

    @classmethod
    def _config(cls):
        payload = {
            "u": "Test-UA/1.0",
            "r": {},
            "q": {"1080p": "1080p"},
            "hoah": cls._inner({"X-Test-Header": "dynamic"}),
            "hl": cls._inner([]),
            "a_": {},
        }
        return base64.b64encode(json.dumps(payload).encode("utf-8")).decode("ascii")

    def test_hdrezka_config_mirror_fallback_and_decode(self):
        calls = []
        def fetch_text(url, headers):
            calls.append(url)
            if "vsr01" in url:
                return None, "TLS_ERROR"
            return self._config(), None

        config = zona_legacy_adapters._hdrezka_get_config(fetch_text, "fallback-UA")
        self.assertEqual(len(calls), 2)
        self.assertEqual(config["u"], "Test-UA/1.0")
        self.assertEqual(config["_headers"], {"X-Test-Header": "dynamic"})
        self.assertEqual(config["_hosts"], [])

    def test_hdrezka_movie_contract_and_stream_parse(self):
        page = (
            '<li title="Дубляж" data-id="123" data-translator_id="45" '
            'data-camrip="0" data-ads="0" data-director="0">Дубляж</li>'
        )
        gets = []
        posts = []
        def fetch_text(url, headers):
            gets.append((url, dict(headers)))
            if url.endswith("/static/ext2.txt"):
                return self._config(), None
            return page, None
        def post_form(url, headers, form):
            posts.append((url, dict(headers), dict(form)))
            return json.dumps({
                "url": "[1080p]https://cdn.example.test/movie.mp4",
                "subtitle": "[ru]https://cdn.example.test/sub.vtt",
                "subtitle_lns": {"ru": "Русский"},
            }), None

        streams, error = zona_legacy_adapters.resolve_local_source(
            {"video_source_type_id": 2, "download_link_key": "123"},
            fetch_text=fetch_text,
            fetch_post_form_text=post_form,
            request_user_agent="fallback-UA",
        )
        self.assertIsNone(error)
        self.assertEqual(len(streams), 1)
        self.assertEqual(streams[0]["url"], "https://cdn.example.test/movie.mp4")
        self.assertEqual(streams[0]["quality"], "1080p")
        self.assertEqual(streams[0]["voice"], "Дубляж")
        self.assertEqual(len(posts), 1)
        url, headers, form = posts[0]
        self.assertRegex(url, r"^https://hdrezka\.ag/ajax/get_cdn_series/\?t=\d+$")
        self.assertEqual(form, {
            "action": "get_movie",
            "id": "123",
            "translator_id": "45",
            "is_camrip": "0",
            "is_ads": "0",
            "is_director": "0",
        })
        self.assertEqual(headers["Origin"], "https://hdrezka.ag")
        self.assertEqual(headers["Referer"], "https://hdrezka.ag/123.html")
        self.assertEqual(headers["X-Test-Header"], "dynamic")

    def test_hdrezka_series_contract_preserves_episode(self):
        page = '<li title="Voice" data-id="777" data-translator_id="9">Voice</li>'
        posts = []
        def fetch_text(url, headers):
            if url.endswith("/static/ext2.txt"):
                return self._config(), None
            return page, None
        def post_form(url, headers, form):
            posts.append(dict(form))
            return json.dumps({"streams": "[720p]https://cdn.example.test/s02e03.m3u8"}), None

        streams, error = zona_legacy_adapters.resolve_local_source(
            {"video_source_type_id": 2, "download_link_key": "777"},
            fetch_text=fetch_text,
            fetch_post_form_text=post_form,
            request_user_agent="UA",
            season=2,
            episode=3,
        )
        self.assertIsNone(error)
        self.assertEqual(posts[0]["action"], "get_stream")
        self.assertEqual(posts[0]["season"], "2")
        self.assertEqual(posts[0]["episode"], "3")
        self.assertEqual(streams[0]["season"], 2)
        self.assertEqual(streams[0]["episode"], 3)

    def test_hdrezka_static_encoded_url_decoder(self):
        raw = "[720p]https://cdn.example.test/encoded.mp4"
        encoded = base64.b64encode(raw.encode("utf-8")).decode("ascii")
        marker = zona_legacy_adapters.HDREZKA_DECODER_MARKERS[0]
        token = (
            zona_legacy_adapters.HDREZKA_DECODER_SEPARATOR
            + base64.b64encode(marker.encode("utf-8")).decode("ascii")
        )
        wrapped = "#h" + encoded[:8] + token + encoded[8:]
        candidates, dynamic = zona_legacy_adapters._hdrezka_stream_candidates(wrapped, {})
        self.assertFalse(dynamic)
        self.assertEqual(candidates, [("https://cdn.example.test/encoded.mp4", "720p")])

    def test_hdrezka_opaque_dynamic_decoder_never_fabricates_stream(self):
        candidates, dynamic = zona_legacy_adapters._hdrezka_stream_candidates("#h%%%%not-base64%%%%", {})
        self.assertEqual(candidates, [])
        self.assertTrue(dynamic)

    def test_provider_post_form_is_urlencoded_and_get_forwards_headers(self):
        seen = []
        class FakeResponse:
            status = 200
            headers = {"Content-Type": "application/json; charset=utf-8"}
            def __enter__(self): return self
            def __exit__(self, exc_type, exc_value, traceback): return False
            def read(self, limit=None): return b'{"ok":true}'
        class FakeOpener:
            def open(self, request, timeout=None):
                seen.append(request)
                return FakeResponse()

        with patch.object(zona_contract, "_OPENER", FakeOpener()):
            text, error = zona_contract._fetch_provider_post_form_text(
                "https://provider.example/ajax",
                {"User-Agent": "UA", "Origin": "https://provider.example", "Referer": "https://provider.example/item"},
                {"action": "get_movie", "id": "123", "translator_id": "45"},
            )
            self.assertIsNone(error)
            self.assertEqual(json.loads(text), {"ok": True})
            request = seen[-1]
            self.assertEqual(request.get_method(), "POST")
            self.assertEqual(urllib.parse.parse_qs(request.data.decode("utf-8")), {
                "action": ["get_movie"], "id": ["123"], "translator_id": ["45"]
            })
            self.assertEqual(request.get_header("Origin"), "https://provider.example")
            self.assertEqual(request.get_header("Referer"), "https://provider.example/item")

            zona_contract._fetch_provider_text(
                "https://provider.example/item",
                {"User-Agent": "UA", "Origin": "https://provider.example", "Referer": "https://provider.example/root"},
            )
            get_request = seen[-1]
            self.assertEqual(get_request.get_method(), "GET")
            self.assertEqual(get_request.get_header("Origin"), "https://provider.example")
            self.assertEqual(get_request.get_header("Referer"), "https://provider.example/root")



class AwmZoneLegacyAdapterTests(unittest.TestCase):
    def setUp(self):
        with zona_legacy_adapters._AWMZONE_CONFIG_LOCK:
            zona_legacy_adapters._AWMZONE_CONFIG_CACHE.clear()
            zona_legacy_adapters._AWMZONE_CONFIG_EXPIRES_AT = 0.0
            zona_legacy_adapters._AWMZONE_ENDPOINT_CACHE.clear()
            zona_legacy_adapters._AWMZONE_ENDPOINT_EXPIRES_AT = 0.0

    @staticmethod
    def _x59(text):
        return base64.b64encode(bytes((b ^ 59) for b in text.encode("utf-8"))).decode("ascii")

    @classmethod
    def _ext9(cls):
        payload = {
            "u": "AWM-UA",
            "h": {"Accept-Language": "ru-RU,ru;q=0.9"},
            "k": cls._x59("/embed-players/{key}"),
            "dp": cls._x59(r"BLOB:([A-Za-z0-9+/=]+)"),
            "pjs": cls._x59(json.dumps({
                "bk": ["bibaiboba", "dvadolboeba", "pososikloun"],
                "fs": "//",
            }, separators=(",", ":"))),
        }
        return base64.b64encode(json.dumps(payload).encode("utf-8")).decode("ascii")

    @staticmethod
    def _ext0():
        return base64.b64encode(json.dumps({
            "9": ["https://awm.example.test"],
        }).encode("utf-8")).decode("ascii")

    @staticmethod
    def _playerjs_file(decoded_text, with_markers=True):
        encoded = base64.b64encode(decoded_text.encode("utf-8")).decode("ascii")
        if with_markers:
            keys = ["bibaiboba", "dvadolboeba", "pososikloun"]
            cuts = [3, 8, 13]
            offset = 0
            for cut, key in zip(cuts, keys):
                marker = "//" + base64.b64encode(key.encode("utf-8")).decode("ascii")
                pos = min(cut + offset, len(encoded))
                encoded = encoded[:pos] + marker + encoded[pos:]
                offset += len(marker)
        return "#x" + encoded

    @classmethod
    def _page(cls, file_value):
        payload = base64.b64encode(json.dumps({"file": file_value}).encode("utf-8")).decode("ascii")
        return "<html><script>BLOB:" + payload + "</script></html>"

    def test_awmzone_dynamic_embed_playerjs_and_quality_playlist(self):
        decoded_tracks = (
            "{Original}https://cdn.example.test/original.list;"
            "{LostFilm}https://cdn.example.test/lostfilm.list"
        )
        page = self._page(self._playerjs_file(decoded_tracks))
        calls = []

        def fetch_text(url, headers):
            calls.append((url, dict(headers)))
            if url.endswith("/static/ext9.txt"):
                return self._ext9(), None
            if url.endswith("/static/ext0.txt"):
                return self._ext0(), None
            if url == "https://awm.example.test/embed-players/movie-key":
                self.assertEqual(headers["User-Agent"], "AWM-UA")
                return page, None
            if url == "https://cdn.example.test/original.list":
                self.assertEqual(headers["Referer"], "https://awm.example.test/")
                return (
                    "https://media.example.test/original/1080.mp4\n"
                    "https://media.example.test/original/720.mp4\n",
                    None,
                )
            if url == "https://cdn.example.test/lostfilm.list":
                return "https://media.example.test/lostfilm/720.mp4\n", None
            return None, "UNEXPECTED_GET"

        streams, error = zona_legacy_adapters.resolve_local_source(
            {"videoSourceTypeId": 9, "downloadLinkKey": "movie-key"},
            fetch_text=fetch_text,
        )
        self.assertIsNone(error)
        self.assertEqual(len(streams), 3)
        self.assertEqual({item["source_type_id"] for item in streams}, {9})
        self.assertEqual({item["voice"] for item in streams}, {"Original", "LostFilm"})
        self.assertEqual({item["quality"] for item in streams}, {"1080p", "720p"})
        self.assertEqual(
            {item["url"] for item in streams},
            {
                "https://media.example.test/original/1080.mp4",
                "https://media.example.test/original/720.mp4",
                "https://media.example.test/lostfilm/720.mp4",
            },
        )
        self.assertTrue(any(url.endswith("/static/ext9.txt") for url, _ in calls))
        self.assertTrue(any(url.endswith("/static/ext0.txt") for url, _ in calls))

    def test_awmzone_series_selects_non_padded_episode_id_from_episode_key(self):
        target_file = self._playerjs_file("{Dub}https://cdn.example.test/s02e03.list", with_markers=False)
        other_file = self._playerjs_file("{Other}https://cdn.example.test/s02e04.list", with_markers=False)
        file_tree = [{
            "folder": [
                {"id": "S2E3", "file": target_file},
                {"id": "S2E4", "file": other_file},
            ]
        }]
        page = self._page(file_tree)

        def fetch_text(url, headers):
            if url.endswith("/static/ext9.txt"):
                return self._ext9(), None
            if url.endswith("/static/ext0.txt"):
                return self._ext0(), None
            if url == "https://awm.example.test/embed-players/serial-key":
                return page, None
            if url == "https://cdn.example.test/s02e03.list":
                return "https://media.example.test/serial/1080.mp4\n", None
            if url == "https://cdn.example.test/s02e04.list":
                self.fail("resolver selected wrong episode")
            return None, "UNEXPECTED_GET"

        streams, error = zona_legacy_adapters.resolve_local_source(
            {
                "videoSourceTypeId": 9,
                "downloadLinkKey": "serial-key",
                "episodeKey": "S02E03",
            },
            fetch_text=fetch_text,
        )
        self.assertIsNone(error)
        self.assertEqual(len(streams), 1)
        self.assertEqual(streams[0]["voice"], "Dub")
        self.assertEqual(streams[0]["quality"], "1080p")
        self.assertEqual(streams[0]["season"], 2)
        self.assertEqual(streams[0]["episode"], 3)

    def test_awmzone_playerjs_marker_removal_matches_legacy_reverse_key_loop(self):
        config = json.loads(base64.b64decode(self._ext9()).decode("utf-8"))
        expected = "{Original}https://cdn.example.test/file.list"
        encoded = self._playerjs_file(expected, with_markers=True)
        decoded, error = zona_legacy_adapters._awmzone_decode_playerjs_file(encoded, config)
        self.assertIsNone(error)
        self.assertEqual(decoded, expected)

    def test_awmzone_runtime_config_cache_and_strict_source_ref(self):
        calls = []

        def fetch_text(url, headers):
            calls.append(url)
            if url.endswith("/static/ext9.txt"):
                return self._ext9(), None
            if url.endswith("/static/ext0.txt"):
                return self._ext0(), None
            return None, "NO_PAGE"

        streams, error = zona_legacy_adapters.resolve_local_source(
            {"videoSourceTypeId": 9, "downloadLinkKey": "https://invalid.example/file"},
            fetch_text=fetch_text,
        )
        self.assertEqual(streams, [])
        self.assertEqual(error, "SOURCE_REF_INCOMPLETE")
        self.assertEqual(calls, [])

        source = {"videoSourceTypeId": 9, "downloadLinkKey": "movie-key"}
        zona_legacy_adapters.resolve_local_source(source, fetch_text=fetch_text)
        zona_legacy_adapters.resolve_local_source(source, fetch_text=fetch_text)
        self.assertEqual(sum(url.endswith("/static/ext9.txt") for url in calls), 1)
        self.assertEqual(sum(url.endswith("/static/ext0.txt") for url in calls), 1)


class AllohaLegacyAdapterTests(unittest.TestCase):
    def setUp(self):
        with zona_legacy_adapters._ALLOHA_CONFIG_LOCK:
            zona_legacy_adapters._ALLOHA_CONFIG_CACHE.clear()
            zona_legacy_adapters._ALLOHA_CONFIG_EXPIRES_AT = 0.0
            zona_legacy_adapters._ALLOHA_ENDPOINT_CACHE.clear()
            zona_legacy_adapters._ALLOHA_ENDPOINT_EXPIRES_AT = 0.0

    @staticmethod
    def _x59(text):
        return base64.b64encode(bytes((b ^ 59) for b in text.encode("utf-8"))).decode("ascii")

    @classmethod
    def _ext8(cls):
        payload = {
            "u": "Alloha-UA",
            "m": "rs",
            "hml": "c,l",
            "ih": cls._x59(json.dumps({"Accept": "text/html,*/*", "Referer": ""})),
            "ph": cls._x59(json.dumps({"Accept": "*/*", "X-Requested-With": "XMLHttpRequest"})),
        }
        return base64.b64encode(json.dumps(payload).encode("utf-8")).decode("ascii")

    @staticmethod
    def _ext0():
        payload = {"8": ["https://front.example.test/;https://alloha.example.test?token=runtime-token"]}
        return base64.b64encode(json.dumps(payload).encode("utf-8")).decode("ascii")

    def test_alloha_dynamic_runtime_endpoint_player_ajax_and_hls(self):
        get_calls = []
        post_calls = []
        page = (
            '<html><head><script src="/js/player.min.js"></script></head><body>'
            '<script>var id = 55;</script>'
            '<button data-translation-m="7" data-id-file="777" class="active voice">LostFilm</button>'
            '</body></html>'
        )

        def fetch_text(url, headers):
            get_calls.append((url, dict(headers)))
            if url.endswith("/static/ext8.txt"):
                return self._ext8(), None
            if url.endswith("/static/ext0.txt"):
                return self._ext0(), None
            if url.startswith("https://alloha.example.test/?token="):
                self.assertIn("token_movie=abcdef0123456789abcdef012345", url)
                self.assertEqual(headers["Referer"], "https://front.example.test/")
                return page, None
            if url == "https://cdn.example.test/master.m3u8":
                return (
                    "#EXTM3U\n"
                    "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\n"
                    "./1080/index.m3u8\n"
                    "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\n"
                    "./720/index.m3u8\n",
                    None,
                )
            return None, "UNEXPECTED_GET"

        def fetch_post_form(url, headers, form):
            post_calls.append((url, dict(headers), dict(form)))
            if url == "https://alloha.example.test/":
                self.assertEqual(form, {
                    "player_ajax": "1",
                    "id_file": "777",
                    "token": "runtime-token",
                    "av1": "true",
                })
                return json.dumps({"url": "{1080p}https://cdn.example.test/master.m3u8", "subtitle": "", "tokenq": ""}), None
            return None, "UNEXPECTED_POST"

        streams, error = zona_legacy_adapters.resolve_local_source(
            {"videoSourceTypeId": 8, "downloadLinkKey": "abcdef0123456789abcdef012345"},
            fetch_text=fetch_text,
            fetch_post_form_text=fetch_post_form,
        )
        self.assertIsNone(error)
        self.assertEqual(len(streams), 2)
        self.assertEqual({item["quality"] for item in streams}, {"1080p", "720p"})
        self.assertEqual({item["voice"] for item in streams}, {"LostFilm"})
        self.assertEqual({item["source_type_id"] for item in streams}, {8})
        self.assertEqual(len(post_calls), 1)
        self.assertTrue(any(url.endswith("/static/ext0.txt") for url, _ in get_calls))

    def test_alloha_aes_payload_matches_legacy_md5_derivation(self):
        import hashlib
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

        password = "runtime-password"
        plaintext = "{1080p}https://cdn.example.test/video.m3u8"
        salt = bytes.fromhex("0011223344556677")
        iv = bytes.fromhex("00112233445566778899aabbccddeeff")
        material = password.encode("utf-8") + salt
        key = b""
        previous = b""
        while len(key) < 32:
            previous = hashlib.md5(previous + material).digest()
            key += previous
        key = key[:32]
        raw = plaintext.encode("utf-8")
        pad = 16 - (len(raw) % 16)
        padded = raw + bytes([pad]) * pad
        encryptor = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
        ciphertext = encryptor.update(padded) + encryptor.finalize()
        payload = "#x" + base64.b64encode(ciphertext).decode("ascii") + "##" + iv.hex() + "##" + salt.hex()

        decoded, error = zona_legacy_adapters._alloha_decrypt_payload(payload, password)
        self.assertIsNone(error)
        self.assertEqual(decoded, plaintext)

    def test_alloha_series_filelist_uses_episode_key(self):
        file_list = {
            "2": {
                "3": {
                    "LostFilm": {
                        "translation": "LostFilm",
                        "file": "[720p]https://cdn.example.test/s02e03.m3u8",
                    }
                }
            }
        }
        escaped = json.dumps(file_list, separators=(",", ":")).replace("\\", "\\\\").replace("'", "\\'")
        page = "<html><script>const fileList = JSON.parse('" + escaped + "');\n</script></html>"

        def fetch_text(url, headers):
            if url.endswith("/static/ext8.txt"):
                return self._ext8(), None
            if url.endswith("/static/ext0.txt"):
                return self._ext0(), None
            if url.startswith("https://alloha.example.test/?token="):
                return page, None
            if url == "https://cdn.example.test/s02e03.m3u8":
                return "#EXTM3U\n#EXTINF:10,\nseg.ts\n", None
            return None, "UNEXPECTED_GET"

        streams, error = zona_legacy_adapters.resolve_local_source(
            {
                "videoSourceTypeId": 8,
                "downloadLinkKey": "abcdef0123456789abcdef012345",
                "episodeKey": "S02E03",
            },
            fetch_text=fetch_text,
            fetch_post_form_text=lambda url, headers, form: (None, "SHOULD_NOT_POST"),
        )
        self.assertIsNone(error)
        self.assertEqual(len(streams), 1)
        self.assertEqual(streams[0]["season"], 2)
        self.assertEqual(streams[0]["episode"], 3)
        self.assertEqual(streams[0]["voice"], "LostFilm")
        self.assertEqual(streams[0]["quality"], "720p")

    def test_alloha_runtime_configs_are_cached_and_source_ref_is_strict(self):
        calls = []

        def fetch_text(url, headers):
            calls.append(url)
            if url.endswith("/static/ext8.txt"):
                return self._ext8(), None
            if url.endswith("/static/ext0.txt"):
                return self._ext0(), None
            return None, "NO_PAGE"

        bad, bad_error = zona_legacy_adapters.resolve_local_source(
            {"videoSourceTypeId": 8, "downloadLinkKey": "https://fabricated.example/stream.m3u8"},
            fetch_text=fetch_text,
            fetch_post_form_text=lambda url, headers, form: (None, "NO_POST"),
        )
        self.assertEqual(bad, [])
        self.assertEqual(bad_error, "SOURCE_REF_INCOMPLETE")
        self.assertEqual(calls, [])

        source = {"videoSourceTypeId": 8, "downloadLinkKey": "abcdef0123456789abcdef012345"}
        zona_legacy_adapters.resolve_local_source(source, fetch_text=fetch_text, fetch_post_form_text=lambda u,h,f:(None,"NO_POST"))
        zona_legacy_adapters.resolve_local_source(source, fetch_text=fetch_text, fetch_post_form_text=lambda u,h,f:(None,"NO_POST"))
        self.assertEqual(sum(url.endswith("/static/ext8.txt") for url in calls), 1)
        self.assertEqual(sum(url.endswith("/static/ext0.txt") for url in calls), 1)


class VideoCdnLegacyAdapterTests(unittest.TestCase):
    def setUp(self):
        with zona_legacy_adapters._VIDEOCDN_CONFIG_LOCK:
            zona_legacy_adapters._VIDEOCDN_CONFIG_CACHE.clear()
            zona_legacy_adapters._VIDEOCDN_CONFIG_EXPIRES_AT = 0.0

    @staticmethod
    def _encoded_config(**overrides):
        def x59(text):
            return base64.b64encode(bytes((b ^ 59) for b in text.encode("utf-8"))).decode("ascii")
        payload = {
            "ll": ["s", "l"],
            "ul": "VideoCDN-UA",
            "t2": x59(
                "https://lumex.example/stream?contentType={contentType}&contentId={contentId}&domain=lmd"
            ),
        }
        payload.update(overrides)
        return base64.b64encode(json.dumps(payload).encode("utf-8")).decode("ascii")

    def test_videocdn_dynamic_config_lumex_hls_contract(self):
        calls = []

        def fetch_text(url, headers):
            calls.append((url, dict(headers)))
            if url.endswith("/static/ext6.txt"):
                return self._encoded_config(), None
            if url.startswith("https://lumex.example/stream?"):
                parsed = urllib.parse.urlparse(url)
                query = urllib.parse.parse_qs(parsed.query)
                self.assertEqual(query["contentType"], ["movie"])
                self.assertEqual(query["contentId"], ["12345"])
                return json.dumps({"url": "https://cdn.example.test/master.m3u8"}), None
            if url == "https://cdn.example.test/master.m3u8":
                return (
                    "#EXTM3U\n"
                    "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\n"
                    "./1080/index.m3u8\n"
                    "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\n"
                    "./720/index.m3u8\n",
                    None,
                )
            return None, "UNEXPECTED_URL"

        streams, error = zona_legacy_adapters.resolve_local_source(
            {
                "videoSourceTypeId": 6,
                "downloadLinkKey": "movie/12345",
                "translation": "Дубляж",
            },
            fetch_text=fetch_text,
            season=2,
            episode=4,
        )
        self.assertIsNone(error)
        self.assertEqual(len(streams), 2)
        self.assertEqual({s["quality"] for s in streams}, {"1080p", "720p"})
        self.assertEqual({s["source_type_id"] for s in streams}, {6})
        self.assertTrue(all(s["voice"] == "Дубляж" for s in streams))
        self.assertTrue(all(s["season"] == 2 and s["episode"] == 4 for s in streams))
        self.assertTrue(all(s["url"].startswith("https://cdn.example.test/") for s in streams))
        self.assertEqual(sum(url.endswith("/static/ext6.txt") for url, _ in calls), 1)

    def test_videocdn_config_is_memory_cached_with_ttl(self):
        config_calls = 0

        def fetch_text(url, headers):
            nonlocal config_calls
            if url.endswith("/static/ext6.txt"):
                config_calls += 1
                return self._encoded_config(), None
            if url.startswith("https://lumex.example/stream?"):
                return json.dumps({"url": "https://cdn.example.test/master.m3u8"}), None
            if url == "https://cdn.example.test/master.m3u8":
                return "#EXTM3U\n./720/index.m3u8\n", None
            return None, "UNEXPECTED_URL"

        source = {"videoSourceTypeId": 6, "downloadLinkKey": "movie/12345"}
        first, error1 = zona_legacy_adapters.resolve_local_source(source, fetch_text=fetch_text)
        second, error2 = zona_legacy_adapters.resolve_local_source(source, fetch_text=fetch_text)
        self.assertIsNone(error1)
        self.assertIsNone(error2)
        self.assertEqual(len(first), 1)
        self.assertEqual(len(second), 1)
        self.assertEqual(config_calls, 1)

    def test_videocdn_missing_l_engine_is_explicit(self):
        def fetch_text(url, headers):
            if url.endswith("/static/ext6.txt"):
                return self._encoded_config(ll=["s"]), None
            return None, "UNEXPECTED_URL"

        streams, error = zona_legacy_adapters.resolve_local_source(
            {"videoSourceTypeId": 6, "downloadLinkKey": "movie/12345"},
            fetch_text=fetch_text,
        )
        self.assertEqual(streams, [])
        self.assertEqual(error, "videocdn:ACTIVE_ENGINE_UNSUPPORTED:s")

    def test_videocdn_requires_real_source_ref(self):
        calls = []
        streams, error = zona_legacy_adapters.resolve_local_source(
            {"videoSourceTypeId": 6, "downloadLinkKey": "not-enough"},
            fetch_text=lambda url, headers: (calls.append(url), None),
        )
        self.assertEqual(streams, [])
        self.assertEqual(error, "SOURCE_REF_INCOMPLETE")
        self.assertEqual(calls, [])


class FilmixLegacyAdapterTests(unittest.TestCase):
    def test_filmix_player_contract_and_direct_variants(self):
        gets = []
        posts = []

        def fetch_text(url, headers):
            gets.append((url, dict(headers)))
            return "<html><body>filmix page</body></html>", None

        def fetch_post_form(url, headers, form):
            posts.append((url, dict(headers), dict(form)))
            return json.dumps({
                "message": {
                    "translations": {
                        "video": {
                            "Дубляж": {
                                "1080p": "https://cdn.example.test/movie-1080.m3u8",
                                "720p": "https://cdn.example.test/movie-720.m3u8",
                            },
                            "Original": "https://cdn.example.test/movie-original-480.mp4",
                        },
                        "trailers": {"Trailer": "https://cdn.example.test/trailer.mp4"},
                    }
                }
            }), None

        source = {
            "videoSourceTypeId": 3,
            "downloadLinkKey": "12345-extra",
            "id": 77,
        }
        with patch.object(zona_legacy_adapters.time, "time", return_value=1700000000.123):
            streams, error = zona_legacy_adapters.resolve_local_source(
                source,
                fetch_text=fetch_text,
                fetch_post_form_text=fetch_post_form,
                request_user_agent="Filmix-UA",
            )

        self.assertIsNone(error)
        self.assertEqual(gets[0][0], "https://filmix.ac/play/12345")
        self.assertEqual(posts[0][0], "https://filmix.ac/api/movies/player-data?t=1700000000123")
        self.assertEqual(posts[0][2], {"post_id": "12345", "showfull": "true"})
        self.assertEqual(posts[0][1]["X-Requested-With"], "XMLHttpRequest")
        self.assertEqual(posts[0][1]["Referer"], "https://filmix.ac/play/12345")
        self.assertEqual(len(streams), 3)
        self.assertEqual({s["voice"] for s in streams}, {"Дубляж", "Original"})
        self.assertEqual({s["quality"] for s in streams}, {"1080p", "720p", "480p"})
        self.assertTrue(all(s["source_type_id"] == 3 for s in streams))
        self.assertFalse(any("trailer" in s["url"] for s in streams))

    def test_filmix_series_preserves_episode_and_url_key(self):
        def fetch_text(url, headers):
            self.assertEqual(url, "https://filmix.ac/play/555")
            return "page", None

        def fetch_post_form(url, headers, form):
            return json.dumps({
                "message": {"translations": {"video": {
                    "LostFilm": "https://cdn.example.test/show-s02e03-1080.m3u8"
                }}}
            }), None

        streams, error = zona_legacy_adapters.resolve_local_source(
            {
                "videoSourceTypeId": 3,
                "downloadLinkKey": "https://filmix.ac/play/555-some-title",
            },
            fetch_text=fetch_text,
            fetch_post_form_text=fetch_post_form,
            season=2,
            episode=3,
        )
        self.assertIsNone(error)
        self.assertEqual(len(streams), 1)
        self.assertEqual(streams[0]["season"], 2)
        self.assertEqual(streams[0]["episode"], 3)
        self.assertEqual(streams[0]["voice"], "LostFilm")

    def test_filmix_opaque_legacy_value_is_rejected_not_fabricated(self):
        def fetch_text(url, headers):
            return "page", None

        def fetch_post_form(url, headers, form):
            return json.dumps({
                "message": {"translations": {"video": {
                    "Dub": "#opaque-packed-filmix-value"
                }}}
            }), None

        streams, error = zona_legacy_adapters.resolve_local_source(
            {"videoSourceTypeId": 3, "downloadLinkKey": "777"},
            fetch_text=fetch_text,
            fetch_post_form_text=fetch_post_form,
        )
        self.assertEqual(streams, [])
        self.assertEqual(error, "filmix:OPAQUE_LINK_DECODER_REQUIRED")

    def test_filmix_page_mirror_fallback(self):
        gets = []
        posts = []

        def fetch_text(url, headers):
            gets.append(url)
            if url.startswith("https://filmix.ac/"):
                return None, "HTTP_ERROR:404"
            return "page", None

        def fetch_post_form(url, headers, form):
            posts.append(url)
            return json.dumps({
                "message": {"translations": {"video": {
                    "Dub": "https://cdn.example.test/movie.mp4"
                }}}
            }), None

        streams, error = zona_legacy_adapters.resolve_local_source(
            {"videoSourceTypeId": 3, "downloadLinkKey": "999"},
            fetch_text=fetch_text,
            fetch_post_form_text=fetch_post_form,
        )
        self.assertIsNone(error)
        self.assertEqual(gets[:2], [
            "https://filmix.ac/play/999",
            "http://filmixapp.cyou/play/999",
        ])
        self.assertTrue(posts[0].startswith("http://filmixapp.cyou/api/movies/player-data?t="))
        self.assertEqual(len(streams), 1)

if __name__ == "__main__":
    unittest.main()
