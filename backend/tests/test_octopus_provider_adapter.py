import unittest

from octopus_provider_adapter import OctopusProviderAdapter


class OctopusProviderAdapterTests(unittest.TestCase):
    def setUp(self):
        self.adapter = OctopusProviderAdapter()

    def test_search_parses_lazy_marker_and_exact_identity(self):
        html = '''
        <div class="main"><div class="top__slider_div__item">
          <a href="/film/42-example-2024.html"><img src="/p.jpg"><span>Example (2024)</span></a>
        </div></div>
        '''
        rows, error = self.adapter.search(
            "Example",
            fetch_text=lambda url, headers: (html, None),
        )
        self.assertIsNone(error)
        self.assertEqual(1, len(rows))
        self.assertEqual("Example", rows[0].title)
        self.assertEqual(2024, rows[0].year)
        selected, status = self.adapter.exact_match(
            rows, title="Example", original_title=None, year=2024,
        )
        self.assertEqual("OK", status)
        self.assertIsNotNone(selected)

    def test_exact_identity_rejects_wrong_year(self):
        html = '''
        <div class="top__slider_div__item">
          <a href="/film/42-example-2019.html"><span>Example (2019)</span></a>
        </div>
        '''
        rows, _ = self.adapter.search("Example", fetch_text=lambda u, h: (html, None))
        selected, status = self.adapter.exact_match(
            rows, title="Example", original_title=None, year=2024,
        )
        self.assertIsNone(selected)
        self.assertEqual("NO_MATCH", status)

    def test_article_extracts_only_explicit_iframe(self):
        search_html = '''
        <div class="top__slider_div__item">
          <a href="/film/42-example-2024.html"><span>Example (2024)</span></a>
        </div>
        '''
        rows, _ = self.adapter.search("Example", fetch_text=lambda u, h: (search_html, None))
        article_html = '''
        <div class="full-story_iframe-block"><iframe src="https://player.example/movie/abc/iframe"></iframe></div>
        '''
        article, error = self.adapter.resolve_article(
            rows[0], fetch_text=lambda u, h: (article_html, None),
        )
        self.assertIsNone(error)
        self.assertEqual("player.example", __import__("urllib.parse").parse.urlsplit(article.iframe_url).hostname)


if __name__ == "__main__":
    unittest.main()
