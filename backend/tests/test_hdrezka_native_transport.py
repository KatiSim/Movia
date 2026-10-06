import base64
import json
import unittest
from unittest.mock import patch

import hdrezka_transport as n

MOVIE = '<script>sof.tv.initCDNMoviesEvents(123, 10, 0);</script>'
PLAYER = '<script>playerInit(%s);</script>'


def player(url='[1080p]https://cdn.example/video.m3u8'):
    return PLAYER % json.dumps({"id": "cdnplayer", "url": url})


def branch(tid=10, voice="Студия", active=True, playlist="", article=123):
    return '<li class="%s" data-id="%s" data-translator_id="%s" title="%s" data-cdn_url="%s">%s</li>' % (
        "active" if active else "", article, tid, voice, playlist, voice)


def episode(number=1, active=True, article=123):
    return '<a class="b-simple_episode__item %s" data-id="%s" data-season_id="1" data-episode_id="%s" href="/series/drama/123-example/1-season-%s-episode.html"></a>' % (
        "active" if active else "", article, number, number)


class NativeHDRezkaTransportTests(unittest.TestCase):
    def resolve(self, page, *, season=None, ep=None, post=None, getter=None, header_getter=None):
        return n.resolve_hdrezka(
            {"downloadLinkKey": "series/drama/123-example" if season else "films/drama/123-example"},
            fetch_text=getter or (lambda url, headers: (page, None)),
            fetch_text_with_headers=header_getter,
            fetch_post_form_text=post, season=season, episode=ep,
        )

    def test_embedded_voice_uses_unique_actual_translator(self):
        rows, error = self.resolve(MOVIE + branch() + player())
        self.assertIsNone(error)
        self.assertEqual("Студия", rows[0]["voice"])
        self.assertEqual("Не указано", rows[0]["quality"])
        self.assertEqual("1080p", rows[0]["advertised_quality"])

    def test_unknown_voice_and_quality_are_not_invented(self):
        rows, error = self.resolve(player("https://cdn.example/movie.mp4"))
        self.assertIsNone(error)
        self.assertEqual("Не указано", rows[0]["voice"])
        self.assertEqual("Не указано", rows[0]["quality"])

    def test_changed_article_id_is_rejected(self):
        rows, error = self.resolve(MOVIE.replace("123,", "999,") + player())
        self.assertEqual([], rows)
        self.assertEqual("HDREZKA_ARTICLE_ID_MISMATCH", error)

    def test_http_200_access_challenge_is_not_content(self):
        rows, error = self.resolve('<title>Проверяем, что вы не бот!</title>' + player())
        self.assertEqual([], rows)
        self.assertEqual("HDREZKA_ACCESS_CHALLENGE", error)

    def test_failed_ajax_payload_never_creates_leaves(self):
        page = MOVIE + branch()
        rows, _ = self.resolve(page, post=lambda *a: (json.dumps({"success": False, "url": "https://cdn.example/fake.mp4"}), None))
        self.assertEqual([], rows)

    def test_all_published_branches_are_kept_after_ajax_403(self):
        page = MOVIE + branch(10, "Студия", playlist="[720p]https://cdn.example/a.mp4") + branch(20, "Original", active=False)
        page += branch(30, "Third", active=False, playlist="[720p]https://cdn.example/c.mp4")
        posts = []
        def post(url, headers, form):
            posts.append(form)
            return None, "HTTP_ERROR:403"
        rows, error = self.resolve(page, post=post)
        self.assertIsNone(error)
        self.assertEqual({"Студия", "Third"}, {x["voice"] for x in rows})
        self.assertEqual(1, len(posts))

    def test_exact_public_episode_page_is_used_instead_of_pilot(self):
        initial = MOVIE + branch() + episode(1) + episode(2, False) + player()
        exact = MOVIE + branch() + episode(2) + player()
        urls = []
        def get(url, headers):
            urls.append(url)
            return (exact if "1-season-2-episode" in url else initial), None
        rows, error = self.resolve(initial, season=1, ep=2, getter=get)
        self.assertIsNone(error)
        self.assertEqual(2, len(urls))
        self.assertEqual((1, 2), (rows[0]["season"], rows[0]["episode"]))
        self.assertTrue(rows[0]["transport_metadata"]["hdrezka_episode_verified"])

    def test_episode_redirect_to_pilot_or_other_article_is_rejected(self):
        initial = MOVIE + episode(1) + episode(2, False) + player()
        for exact in (initial, MOVIE + episode(2, article=999) + player()):
            rows, error = self.resolve(initial, season=1, ep=2, getter=lambda url, h: (exact if "1-season-2-episode" in url else initial, None))
            self.assertEqual([], rows)
            self.assertEqual("HDREZKA_EPISODE_UNVERIFIED", error)

    def test_series_does_not_accept_inactive_translator_page_playlist(self):
        page = MOVIE + branch(10, "A", playlist="[720p]https://cdn.example/a.mp4")
        page += branch(20, "B", False, "[720p]https://cdn.example/pilot.mp4") + episode(2) + player()
        rows, _ = self.resolve(page, season=1, ep=2)
        self.assertNotIn("https://cdn.example/pilot.mp4", {r["url"] for r in rows})

    def test_invalid_coordinates_do_not_trigger_network(self):
        for season, ep in ((True, 2), (1, 2.5), (0, 2), (1, None)):
            rows, error = self.resolve("", season=season, ep=ep, getter=lambda *a: self.fail("must not fetch"))
            self.assertEqual([], rows)
            self.assertEqual("HDREZKA_EPISODE_COORDINATES_INVALID", error)

    def test_ajax_form_uses_exact_id_and_coordinates_without_account_headers(self):
        calls = []
        page = MOVIE + branch() + episode(2)
        def post(url, headers, form):
            calls.append((headers, form))
            return json.dumps({"success": True, "url": "[720p]https://cdn.example/s1e2.m3u8"}), None
        rows, error = self.resolve(page, season=1, ep=2, post=post)
        self.assertIsNone(error)
        self.assertEqual({"id": "123", "translator_id": "10", "action": "get_stream", "season": "1", "episode": "2"}, calls[0][1])
        self.assertNotIn("X-Hdrezka-Android-App", calls[0][0])
        self.assertEqual("episode-ajax", rows[0]["transport_metadata"]["hdrezka_episode_evidence"])

    def test_page_cookie_is_case_insensitive_and_scoped_to_posts(self):
        sent = []
        def get(url, headers):
            return MOVIE + branch(), {"Set-Cookie": "sid=own; Path=/; HttpOnly"}, None
        def post(url, headers, form):
            sent.append(dict(headers))
            return json.dumps({"url": "[720p]https://cdn.example/full.mp4"}), None
        rows, error = self.resolve("", header_getter=get, post=post)
        self.assertIsNone(error)
        self.assertEqual("sid=own", sent[0]["Cookie"])
        self.assertNotIn("Cookie", rows[0]["headers"])

    def test_ambiguous_active_translator_does_not_guess_voice(self):
        rows, error = self.resolve(MOVIE + branch(10, "A") + branch(20, "B") + player())
        self.assertIsNone(error)
        self.assertEqual("Не указано", rows[0]["voice"])

    def test_translator_from_other_article_is_not_selected(self):
        rows, error = self.resolve(MOVIE + branch(20, "Foreign", article=999) + player())
        self.assertIsNone(error)
        self.assertNotIn("Foreign", {x["voice"] for x in rows})

    def test_no_three_voice_or_512_leaf_limit(self):
        page = MOVIE + "".join(branch(i, "Voice %s" % i, i == 10, "[720p]https://cdn.example/%s.mp4" % i) for i in range(1, 601))
        rows, error = self.resolve(page)
        self.assertIsNone(error)
        self.assertEqual(600, len(rows))

    def test_static_playlist_envelope_and_alternatives_are_decoded(self):
        plain = "[720p]https://cdn.example/a.mp4 or https://cdn.example/b.m3u8"
        encoded = base64.b64encode(plain.encode()).decode()
        envelope = "#h" + encoded[:8] + "//_//" + "A" * 16 + encoded[8:]
        self.assertEqual(plain, n.decode_playlist(envelope))
        self.assertEqual(2, len(n.playlist_leaves(envelope)))

    def test_malformed_envelope_and_unsafe_urls_are_rejected(self):
        for value in ("#hA//_//bad", "#xabc", "#2!!!!!"):
            self.assertEqual([], n.playlist_leaves(value))
        self.assertEqual([], n.playlist_leaves("[720p]javascript:alert(1)"))

    def test_source_path_cannot_escape_article(self):
        for path in ("../other", "films/x?query=1", "films/x\\other"):
            rows, error = n.resolve_hdrezka({"downloadLinkKey": path}, fetch_text=lambda *a: self.fail("must not fetch"), fetch_post_form_text=None)
            self.assertEqual([], rows)
            self.assertEqual("SOURCE_REF_INCOMPLETE", error)

    def test_translator_queue_is_bounded_across_a_large_article(self):
        from concurrent.futures import ThreadPoolExecutor
        from threading import BoundedSemaphore, Event
        from time import monotonic
        gate = Event()
        executor = ThreadPoolExecutor(max_workers=1)
        slots = BoundedSemaphore(3)
        futures = []
        original_submit = executor.submit
        def submit(*args, **kwargs):
            future = original_submit(*args, **kwargs)
            futures.append(future)
            return future
        def post(url, headers, form):
            if form["translator_id"] != "1":
                gate.wait(8)
            return json.dumps({"url": "[720p]https://cdn.example/%s.mp4" % form["translator_id"]}), None
        page = MOVIE + "".join(branch(i, "Voice %s" % i, i == 10) for i in range(1, 601))
        try:
            with patch.object(n, "_TRANSLATOR_WORKERS", executor), patch.object(n, "_TRANSLATOR_SLOTS", slots), patch.object(executor, "submit", side_effect=submit):
                start = monotonic()
                rows, error = self.resolve(page, post=post)
                elapsed = monotonic() - start
            self.assertIsNone(error)
            self.assertEqual(1, len(rows))
            self.assertEqual(3, len(futures))
            self.assertEqual(2, sum(f.cancelled() for f in futures))
            self.assertLess(elapsed, 3)
        finally:
            gate.set()
            executor.shutdown(wait=True, cancel_futures=True)
        acquired = [slots.acquire(blocking=False) for _ in range(4)]
        self.assertEqual([True, True, True, False], acquired)
        for ok in acquired:
            if ok:
                slots.release()
