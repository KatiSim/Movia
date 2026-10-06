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


class HdrezkaExactEpisodeTests(unittest.TestCase):
    def page(self,episode,link=True):
        target='<a class="b-simple_episode__item" data-season_id="1" data-episode_id="2" href="https://rezka.ag/series/drama/123-example/10-dub/1-season/2-episode.html">2</a>' if link else ''
        return f"""<a class="b-simple_episode__item active" data-id="123" data-season_id="1" data-episode_id="{episode}">active</a>
        {target}<li class="b-translator__item active" data-id="123" data-translator_id="10" title="Dub">Dub</li>
        <script>playerInit({{"id":"cdnplayer","url":"[720p]https://cdn.example/episode{episode}.mp4"}});</script>"""

    def resolve(self,page_fetch,post=None):
        config={"u":"Fixture-UA","q":{},"_headers":{},"_account":{}}
        with patch.object(a,"_hdrezka_get_config",return_value=config):
            return a._resolve_hdrezka({"downloadLinkKey":"series/drama/123-example"},fetch_text=page_fetch,fetch_post_form_text=post or (lambda *_:(None,"HTTP_ERROR:403")),request_user_agent="Fixture-UA",season=1,episode=2)

    def test_default_pilot_fallback_cannot_be_labeled_as_second_episode(self):
        rows,error=self.resolve(lambda *_:(self.page(1,False),None))
        self.assertEqual([],rows);self.assertIn('PROVIDER_ERROR',error)

    def test_exact_public_episode_page_establishes_embedded_player_identity(self):
        visited=[]
        def fetch(url,headers):
            visited.append(url)
            return self.page(2 if url.endswith('/2-episode.html') else 1),None
        rows,error=self.resolve(fetch)
        self.assertIsNone(error);self.assertEqual(1,len(rows));self.assertTrue(visited[-1].endswith('/2-episode.html'))
        self.assertTrue(rows[0]['url'].endswith('episode2.mp4'))
        self.assertEqual((1,2),(rows[0]['season'],rows[0]['episode']))
        self.assertEqual('embedded-page',rows[0]['transport_metadata']['hdrezka_episode_evidence'])

    def test_episode_link_redirecting_back_to_pilot_fails_closed(self):
        rows,error=self.resolve(lambda *_:(self.page(1),None))
        self.assertEqual([],rows)

    def test_episode_page_from_another_article_is_rejected_even_with_matching_numbers(self):
        def fetch(url,headers):
            page=self.page(2).replace('data-id="123"','data-id="999"') if url.endswith('/2-episode.html') else self.page(1)
            return page,None
        rows,error=self.resolve(fetch)
        self.assertEqual([],rows)

    def test_successful_exact_episode_ajax_is_valid_without_page_fallback(self):
        forms=[]
        def post(url,headers,form):
            forms.append(form);return json.dumps({'success':True,'url':'[720p]https://cdn.example/episode2.mp4'}),None
        rows,error=self.resolve(lambda *_:(self.page(1,False),None),post)
        self.assertIsNone(error);self.assertEqual(('1','2'),(forms[0]['season'],forms[0]['episode']))
        self.assertEqual('episode-ajax',rows[0]['transport_metadata']['hdrezka_episode_evidence'])

    def test_same_article_link_and_unique_active_coordinates_are_required(self):
        from hdrezka_episode_identity import selected_episode,exact_episode_page
        page=self.page(1)
        self.assertEqual((1,1),selected_episode(page))
        self.assertIsNone(selected_episode(page+self.page(2)))
        wrong=page.replace('/series/drama/123-example/10-dub/','/series/drama/999-another/10-dub/')
        self.assertIsNone(exact_episode_page(wrong,'https://rezka.ag/series/drama/123-example.html',1,2))

    def test_old_cached_hdrezka_episode_without_provider_evidence_is_rejected(self):
        from stream_identity import filter_streams_for_content
        card={'id':159,'title':'Во все тяжкие','year':2008,'media_type':'tv','season':1,'episode':2}
        row={'source':'HDRezka','provider_id':'movia:hdrezka','url':'https://cdn.example/pilot.mp4','voice':'Dub','quality':'720p','season':1,'episode':2}
        self.assertEqual([],filter_streams_for_content([row],card))
        row['transport_metadata']={'hdrezka_episode_verified':True,'hdrezka_episode_evidence':'episode-ajax','expected_episode_duration_ms':2880000}
        self.assertEqual(1,len(filter_streams_for_content([row],card)))


if __name__ == "__main__":
    unittest.main()
