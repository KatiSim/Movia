import hashlib
import json
import unittest
from email.message import Message
from unittest.mock import patch

import zona_contract
from zona_legacy_adapters import (
    KINOPLAY_BASE_URL,
    KINOPLAY_CONFIG_MIRRORS,
    KINOPLAY_CONFIG_PATH,
    KINOPLAY_DEFAULT_USER_AGENT,
    resolve_local_source,
)


def _root_fixture(token: str) -> str:
    codes = ",".join(f"0x{ord(ch):x}" for ch in token)
    return f'<script>String["fromCharCode"]({codes});</script>'


class ZonaKinoplayAdapterTests(unittest.TestCase):
    def _run_adapter(
        self,
        player_body,
        *,
        season=None,
        episode=None,
        config_user_agent=KINOPLAY_DEFAULT_USER_AGENT,
        config_error=False,
    ):
        token = "movia-fixture-token"
        root_url = KINOPLAY_BASE_URL.rstrip("/") + "/"
        player_url = root_url.rstrip("/") + "/iplayer/videodb.php?kp=12345"
        root_calls = []
        text_calls = []
        post_calls = []

        def fetch_text_with_headers(url, headers):
            root_calls.append((url, dict(headers)))
            self.assertEqual(url, root_url)
            return (
                _root_fixture(token),
                {
                    "set-cookie": [
                        "session=movia-fixture; Path=/; HttpOnly",
                        "obsolete=deleted; Path=/",
                    ]
                },
                None,
            )

        def fetch_text(url, headers):
            text_calls.append((url, dict(headers)))
            config_urls = {
                mirror + KINOPLAY_CONFIG_PATH
                for mirror in KINOPLAY_CONFIG_MIRRORS
            }
            if url in config_urls:
                if config_error:
                    return None, "fixture-config-unavailable"
                return json.dumps({"u": config_user_agent}), None
            self.assertEqual(url, player_url)
            return player_body, None

        def fetch_post_form_text(url, headers, form):
            post_calls.append((url, dict(headers), dict(form)))
            return "ok", None

        streams, error = resolve_local_source(
            {
                "id": 26001,
                "videoSourceTypeId": 26,
                "downloadLinkKey": "12345",
                "name": "FreeKinoPlay fixture",
            },
            fetch_text=fetch_text,
            fetch_text_with_headers=fetch_text_with_headers,
            fetch_post_form_text=fetch_post_form_text,
            request_user_agent=KINOPLAY_DEFAULT_USER_AGENT,
            season=season,
            episode=episode,
        )
        return streams, error, token, root_url, player_url, root_calls, text_calls, post_calls

    def test_movie_handshake_carries_cookie_and_signed_hash(self):
        player_body = (
            'file: [{"title":"Dub One","file":'
            '"[720p]https://media.example.test/movie-720.m3u8,'
            '[1080p]https://media.example.test/movie-1080.m3u8"}], vast_'
        )
        (
            streams,
            error,
            token,
            root_url,
            player_url,
            root_calls,
            text_calls,
            post_calls,
        ) = self._run_adapter(player_body)

        self.assertIsNone(error)
        self.assertEqual(len(streams), 2)
        self.assertEqual([row["voice"] for row in streams], ["Dub One", "Dub One"])
        self.assertEqual([row["quality"] for row in streams], ["720p", "1080p"])
        self.assertEqual(
            [row["url"] for row in streams],
            [
                "https://media.example.test/movie-720.m3u8",
                "https://media.example.test/movie-1080.m3u8",
            ],
        )
        self.assertEqual(len(root_calls), 1)
        self.assertEqual(root_calls[0][1]["Referer"], root_url)
        self.assertEqual(len(post_calls), 1)

        expected_md5 = hashlib.md5(
            f"{token};21hd.freekinoplay4.online;{KINOPLAY_DEFAULT_USER_AGENT}".encode()
        ).hexdigest()
        expected_hash = hashlib.sha1(expected_md5.encode("utf-8")).hexdigest()
        post_url, post_headers, post_form = post_calls[0]
        self.assertEqual(post_url, root_url)
        self.assertEqual(post_form, {"hash": expected_hash})
        self.assertEqual(post_headers["Authorization"], f"Bearer {token}")
        self.assertEqual(post_headers["Cookie"], "session=movia-fixture")
        self.assertEqual(post_headers["Referer"], root_url)

        player_calls = [call for call in text_calls if call[0] == player_url]
        self.assertEqual(len(player_calls), 1)
        self.assertEqual(player_calls[0][1]["Cookie"], "session=movia-fixture")
        self.assertEqual(player_calls[0][1]["Referer"], root_url)
        self.assertTrue(all(row["source_type_id"] == 26 for row in streams))

    def test_dynamic_config_user_agent_drives_signed_handshake(self):
        dynamic_user_agent = "Movia fixture UA/26"
        player_body = (
            'file: [{"title":"Dub","file":'
            '"[720p]https://media.example.test/dynamic-ua.m3u8"}], vast_'
        )
        (
            streams,
            error,
            token,
            root_url,
            _player_url,
            root_calls,
            _text_calls,
            post_calls,
        ) = self._run_adapter(
            player_body,
            config_user_agent=dynamic_user_agent,
        )

        self.assertIsNone(error)
        self.assertEqual(len(streams), 1)
        self.assertEqual(root_calls[0][1]["User-Agent"], dynamic_user_agent)
        expected_md5 = hashlib.md5(
            f"{token};21hd.freekinoplay4.online;{dynamic_user_agent}".encode()
        ).hexdigest()
        expected_hash = hashlib.sha1(expected_md5.encode("utf-8")).hexdigest()
        self.assertEqual(post_calls[0][2], {"hash": expected_hash})
        self.assertEqual(post_calls[0][1]["User-Agent"], dynamic_user_agent)
        self.assertEqual(streams[0]["user_agent"], dynamic_user_agent)
        self.assertEqual(post_calls[0][1]["Referer"], root_url)

    def test_config_failure_uses_reference_fallback_user_agent(self):
        player_body = (
            'file: [{"title":"Dub","file":'
            '"[720p]https://media.example.test/fallback-ua.m3u8"}], vast_'
        )
        streams, error, _token, _root, _player, root_calls, text_calls, _post = (
            self._run_adapter(player_body, config_error=True)
        )

        self.assertIsNone(error)
        self.assertEqual(len(streams), 1)
        self.assertEqual(root_calls[0][1]["User-Agent"], KINOPLAY_DEFAULT_USER_AGENT)
        config_calls = [
            call for call in text_calls
            if call[0].endswith(KINOPLAY_CONFIG_PATH)
        ]
        self.assertEqual(len(config_calls), len(KINOPLAY_CONFIG_MIRRORS))

    def test_series_selects_exact_season_episode_and_comment_voice(self):
        player_body = (
            'file: [{"title":"1 сезон","folder":['
            '{"title":"1 серия","folder":['
            '{"comment":"Wrong voice","file":"[480p]https://media.example.test/wrong.m3u8"}]},'
            '{"title":"2 серия","folder":['
            '{"comment":"Voice A","file":"[720p]https://media.example.test/s1e2-a.m3u8"},'
            '{"comment":"Voice B","file":"[1080p]https://media.example.test/s1e2-b.m3u8"}]}'
            ']}], vast_'
        )
        streams, error, *_ = self._run_adapter(player_body, season=1, episode=2)

        self.assertIsNone(error)
        self.assertEqual(
            [(row["voice"], row["quality"], row["url"]) for row in streams],
            [
                ("Voice A", "720p", "https://media.example.test/s1e2-a.m3u8"),
                ("Voice B", "1080p", "https://media.example.test/s1e2-b.m3u8"),
            ],
        )

    def test_missing_exact_episode_is_not_substituted(self):
        player_body = (
            'file: [{"title":"1 сезон","folder":['
            '{"title":"1 серия","folder":['
            '{"comment":"Voice A","file":"[720p]https://media.example.test/s1e1.m3u8"}]}'
            ']}], vast_'
        )
        streams, error, *_ = self._run_adapter(player_body, season=1, episode=2)

        self.assertEqual(streams, [])
        self.assertEqual(error, "kinoplay:EPISODE_NOT_FOUND")


class ZonaProviderHeaderFetcherTests(unittest.TestCase):
    def test_header_fetcher_preserves_repeated_set_cookie_values(self):
        headers = Message()
        headers.add_header("Content-Type", "text/plain; charset=utf-8")
        headers.add_header("Set-Cookie", "a=1; Path=/")
        headers.add_header("Set-Cookie", "b=2; Path=/")

        class FakeResponse:
            status = 200

            def __init__(self):
                self.headers = headers

            def read(self, _limit):
                return b"fixture body"

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

        class FakeOpener:
            def open(self, _request, timeout):
                self.timeout = timeout
                return FakeResponse()

        with patch.object(zona_contract, "_OPENER", FakeOpener()):
            body, response_headers, error = zona_contract._fetch_provider_text_with_headers(
                "https://provider.example.test/root",
                {"User-Agent": "fixture-agent"},
            )

        self.assertIsNone(error)
        self.assertEqual(body, "fixture body")
        self.assertEqual(response_headers["set-cookie"], ["a=1; Path=/", "b=2; Path=/"])

    def test_legacy_text_fetcher_keeps_two_value_contract(self):
        headers = Message()
        headers.add_header("Content-Type", "text/plain; charset=utf-8")

        class FakeResponse:
            status = 200

            def __init__(self):
                self.headers = headers

            def read(self, _limit):
                return b"legacy body"

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

        class FakeOpener:
            def open(self, _request, timeout):
                return FakeResponse()

        with patch.object(zona_contract, "_OPENER", FakeOpener()):
            result = zona_contract._fetch_provider_text(
                "https://provider.example.test/root",
                {"User-Agent": "fixture-agent"},
            )

        self.assertEqual(result, ("legacy body", None))


if __name__ == "__main__":
    unittest.main()
