import json
import unittest
from unittest.mock import patch

import zona_legacy_adapters as a


class HdrezkaTranslationExpansionTests(unittest.TestCase):
    def test_embedded_player_uses_initial_translator_id_when_no_active_class(self):
        page = """
        <li class="b-translator__item" data-id="123" data-translator_id="56" title="Дубляж">Дубляж</li>
        <li class="b-translator__item" data-id="123" data-translator_id="20" title="Оригинал">Оригинал</li>
        <script>sof.tv.initCDNMoviesEvents(123, 56, 0, 0, 0, 'rezka.example', false, false, {"id":"cdnplayer","url":"[720p]https://cdn.example/default.mp4"});</script>
        """
        config = {"u": "Fixture-UA", "q": {}, "_headers": {}, "_account": {}}
        with patch.object(a, "_hdrezka_get_config", return_value=config):
            streams, error = a._resolve_hdrezka(
                {"extractor": 2, "downloadLinkKey": "films/drama/123-example"},
                fetch_text=lambda url, headers: (page, None),
                fetch_post_form_text=lambda url, headers, form: (None, "HTTP_ERROR:403"),
                request_user_agent="Fixture-UA",
                season=None,
                episode=None,
            )
        self.assertIsNone(error)
        self.assertEqual(1, len(streams))
        self.assertEqual("Дубляж", streams[0]["voice"])
        self.assertEqual("56", a._hdrezka_initial_translator_id(page))

    def test_embedded_player_inherits_active_translator_voice(self):
        page = """
        <li class="b-translator__item active" data-id="123" data-translator_id="10" title="Дубляж">Дубляж</li>
        <li class="b-translator__item" data-id="123" data-translator_id="20" title="Оригинал">Оригинал</li>
        <script>playerInit({"id":"cdnplayer","url":"[480p]https://cdn.example/default.mp4"});</script>
        """
        config = {"u": "Fixture-UA", "q": {}, "_headers": {}, "_account": {}}
        with patch.object(a, "_hdrezka_get_config", return_value=config):
            streams, error = a._resolve_hdrezka(
                {"extractor": 2, "downloadLinkKey": "films/drama/123-example"},
                fetch_text=lambda url, headers: (page, None),
                fetch_post_form_text=lambda url, headers, form: (None, "HTTP_ERROR:403"),
                request_user_agent="Fixture-UA",
                season=None,
                episode=None,
            )
        self.assertIsNone(error)
        self.assertEqual(1, len(streams))
        self.assertEqual("Дубляж", streams[0]["voice"])

    def test_embedded_player_does_not_hide_translator_branches(self):
        page = '''
        <html><body>
          <li data-id="123" data-translator_id="10" title="Дубляж">Дубляж</li>
          <li data-id="123" data-translator_id="20" title="Оригинал">Оригинал</li>
          <script>playerInit({"id":"cdnplayer","url":"[720p]https://cdn.example/default.mp4"});</script>
        </body></html>
        '''
        posts = []

        def fetch_text(url, headers):
            return page, None

        def fetch_post(url, headers, form):
            posts.append(dict(form))
            voice_id = form["translator_id"]
            return json.dumps({
                "url": f"[720p]https://cdn.example/{voice_id}.mp4"
            }), None

        config = {"u": "Fixture-UA", "q": {}, "_headers": {}, "_account": {}}
        with patch.object(a, "_hdrezka_get_config", return_value=config):
            streams, error = a._resolve_hdrezka(
                {"extractor": 2, "downloadLinkKey": "films/drama/123-example"},
                fetch_text=fetch_text,
                fetch_post_form_text=fetch_post,
                request_user_agent="Fixture-UA",
                season=None,
                episode=None,
            )

        self.assertIsNone(error)
        self.assertEqual(2, len(posts))
        self.assertEqual({"10", "20"}, {row["translator_id"] for row in posts})
        self.assertEqual({"Дубляж", "Оригинал"}, {row["voice"] for row in streams})
        self.assertEqual({"720p"}, {row["quality"] for row in streams})
        self.assertNotIn("Не указано", {row["voice"] for row in streams})

    def test_data_cdn_url_is_resolved_without_ajax_post(self):
        page = """
        <li data-id="123" data-translator_id="10" title="Дубляж"
            data-cdn_url="[720p]https://cdn.example/dub-720.mp4,[1080p]https://cdn.example/dub-1080.mp4">Дубляж</li>
        <script>playerInit({"id":"cdnplayer","url":"[480p]https://cdn.example/default.mp4"});</script>
        """
        config = {"u": "Fixture-UA", "q": {}, "_headers": {}, "_account": {}}
        with patch.object(a, "_hdrezka_get_config", return_value=config):
            streams, error = a._resolve_hdrezka(
                {"extractor": 2, "downloadLinkKey": "films/drama/123-example"},
                fetch_text=lambda url, headers: (page, None),
                fetch_post_form_text=lambda url, headers, form: self.fail("data-cdn_url must bypass AJAX"),
                request_user_agent="Fixture-UA",
                season=None,
                episode=None,
            )
        self.assertIsNone(error)
        self.assertEqual({"Дубляж"}, {row["voice"] for row in streams})
        self.assertEqual({"720p", "1080p"}, {row["quality"] for row in streams})

    def test_movie_ajax_form_keeps_lazy_zero_flags(self):
        page = """
        <li data-id="123" data-translator_id="10" title="Дубляж">Дубляж</li>
        """
        forms = []
        def post(url, headers, form):
            forms.append(dict(form))
            return json.dumps({"url": "[720p]https://cdn.example/dub.mp4"}), None
        config = {"u": "Fixture-UA", "q": {}, "_headers": {}, "_account": {}}
        with patch.object(a, "_hdrezka_get_config", return_value=config):
            streams, error = a._resolve_hdrezka(
                {"extractor": 2, "downloadLinkKey": "films/drama/123-example"},
                fetch_text=lambda url, headers: (page, None),
                fetch_post_form_text=post,
                request_user_agent="Fixture-UA",
                season=None,
                episode=None,
            )
        self.assertIsNone(error)
        self.assertEqual(1, len(streams))
        self.assertEqual("0", forms[0]["is_camrip"])
        self.assertEqual("0", forms[0]["is_ads"])
        self.assertEqual("0", forms[0]["is_director"])

    def test_page_set_cookie_is_scoped_to_translator_posts(self):
        page = """
        <li data-id="123" data-translator_id="10" title="Дубляж">Дубляж</li>
        <script>playerInit({"id":"cdnplayer","url":"[480p]https://cdn.example/default.mp4"});</script>
        """
        seen_headers = []

        def fetch_with_headers(url, headers):
            return page, {
                "set-cookie": [
                    "PHPSESSID=fixture-session; Path=/; HttpOnly",
                    "gone=deleted; Max-Age=0",
                ]
            }, None

        def post(url, headers, form):
            seen_headers.append(dict(headers))
            return json.dumps({"url": "[720p]https://cdn.example/dub.mp4"}), None

        config = {"u": "Fixture-UA", "q": {}, "_headers": {}, "_account": {}}
        with patch.object(a, "_hdrezka_get_config", return_value=config):
            streams, error = a._resolve_hdrezka(
                {"extractor": 2, "downloadLinkKey": "films/drama/123-example"},
                fetch_text=lambda url, headers: self.fail("header-aware fetcher must be used"),
                fetch_text_with_headers=fetch_with_headers,
                fetch_post_form_text=post,
                request_user_agent="Fixture-UA",
                season=None,
                episode=None,
            )
        self.assertIsNone(error)
        self.assertEqual(1, len(streams))
        self.assertEqual("Дубляж", streams[0]["voice"])
        self.assertEqual("PHPSESSID=fixture-session", seen_headers[0]["Cookie"])

    def test_legacy_ajax_403_fails_fast_to_embedded_player(self):
        page = """
        <li data-id="123" data-translator_id="10" title="Дубляж">Дубляж</li>
        <li data-id="123" data-translator_id="20" title="Оригинал">Оригинал</li>
        <script>playerInit({"id":"cdnplayer","url":"[480p]https://cdn.example/default.mp4"});</script>
        """
        calls = []
        def post(url, headers, form):
            calls.append(dict(form))
            return None, "HTTP_ERROR:403"
        config = {"u": "Fixture-UA", "q": {}, "_headers": {}, "_account": {}}
        with patch.object(a, "_hdrezka_get_config", return_value=config):
            streams, error = a._resolve_hdrezka(
                {"extractor": 2, "downloadLinkKey": "films/drama/123-example"},
                fetch_text=lambda url, headers: (page, None),
                fetch_post_form_text=post,
                request_user_agent="Fixture-UA",
                season=None,
                episode=None,
            )
        self.assertIsNone(error)
        self.assertEqual(1, len(calls))
        self.assertEqual(1, len(streams))
        self.assertEqual("480p", streams[0]["quality"])

    def test_embedded_player_remains_fallback_when_translators_fail(self):
        page = '''
        <li data-id="123" data-translator_id="10" title="Дубляж">Дубляж</li>
        <script>playerInit({"id":"cdnplayer","url":"[480p]https://cdn.example/default.mp4"});</script>
        '''
        config = {"u": "Fixture-UA", "q": {}, "_headers": {}, "_account": {}}
        with patch.object(a, "_hdrezka_get_config", return_value=config):
            streams, error = a._resolve_hdrezka(
                {"extractor": 2, "downloadLinkKey": "films/drama/123-example"},
                fetch_text=lambda url, headers: (page, None),
                fetch_post_form_text=lambda url, headers, form: (None, "HTTP_ERROR:503"),
                request_user_agent="Fixture-UA",
                season=None,
                episode=None,
            )
        self.assertIsNone(error)
        self.assertEqual(1, len(streams))
        self.assertEqual("480p", streams[0]["quality"])
        self.assertEqual("Не указано", streams[0]["voice"])


if __name__ == "__main__":
    unittest.main()
