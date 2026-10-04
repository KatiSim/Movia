import unittest

from provider_contract import ProviderRequest
from torrent_provider_adapter import rewrite_torrent_rows_as_variant_tree


class TorrentProviderAdapterTests(unittest.TestCase):
    def request(self, **kwargs):
        values = dict(media_id="77", title="Example", year=2024, media_type="movie")
        values.update(kwargs)
        return ProviderRequest(**values)

    def test_preserves_provider_voice_quality_seeders_and_infohash(self):
        rows = [
            {"source":"Rutor","url":"magnet:?xt=urn:btih:" + "a"*40 + "&tr=https%3A%2F%2Ft1", "voice":"Дубляж","quality":"1080p","seeders":42,"title":"Example 2024 1080p | D"},
            {"source":"YTS","url":"magnet:?xt=urn:btih:" + "b"*40, "voice":"Original (с субтитрами)","quality":"720p","seeders":7,"title":"Example 2024 720p"},
        ]
        out = rewrite_torrent_rows_as_variant_tree(rows, self.request())
        self.assertEqual(2, len(out))
        self.assertEqual({"Rutor","YTS"}, {r["provider"] for r in out})
        self.assertEqual({42,7}, {r["seeders"] for r in out})
        self.assertEqual({"a"*40,"b"*40}, {r["info_hash"] for r in out})
        self.assertEqual({"77"}, {r["catalog_media_id"] for r in out})
        self.assertTrue(all(r.get("logical_source_id") for r in out))
        self.assertTrue(all(r.get("provider_item_id") for r in out))

    def test_tracker_churn_keeps_logical_source_identity(self):
        base = "magnet:?xt=urn:btih:" + "c"*40
        row = {"source":"Rutor","voice":"Дубляж","quality":"1080p","seeders":10,"title":"Example 2024"}
        a = rewrite_torrent_rows_as_variant_tree([{**row,"url":base+"&tr=https%3A%2F%2Fa"}], self.request())[0]
        b = rewrite_torrent_rows_as_variant_tree([{**row,"url":base+"&tr=https%3A%2F%2Fb"}], self.request())[0]
        self.assertEqual(a["logical_source_id"], b["logical_source_id"])
        self.assertEqual(a["provider_item_id"], b["provider_item_id"])
        self.assertNotEqual(a["url"], b["url"])

    def test_exact_episode_rejects_other_episode_and_card_level_rows(self):
        req = self.request(media_type="tv", season=2, episode=3)
        rows = [
            {"source":"Rutor","url":"magnet:?xt=urn:btih:"+"d"*40,"voice":"Dub","quality":"1080p","seeders":5,"season":2,"episode":3,"title":"S02E03"},
            {"source":"Rutor","url":"magnet:?xt=urn:btih:"+"e"*40,"voice":"Dub","quality":"1080p","seeders":5,"season":2,"episode":4,"title":"S02E04"},
            {"source":"Rutor","url":"magnet:?xt=urn:btih:"+"f"*40,"voice":"Dub","quality":"1080p","seeders":5,"title":"Season pack"},
        ]
        out = rewrite_torrent_rows_as_variant_tree(rows, req)
        self.assertEqual(1, len(out))
        self.assertEqual(2, out[0]["season"])
        self.assertEqual(3, out[0]["episode"])
        self.assertEqual("d"*40, out[0]["info_hash"])

    def test_non_magnet_rows_are_not_mislabeled_as_torrent_leaves(self):
        rows = [{"source":"Archive","url":"https://cdn.example/file.mp4","voice":"Original","quality":"1080p","seeders":0}]
        self.assertEqual([], rewrite_torrent_rows_as_variant_tree(rows, self.request()))


if __name__ == '__main__': unittest.main()
