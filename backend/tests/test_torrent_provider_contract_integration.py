import os
import unittest
from unittest.mock import patch

import streamer


class TorrentProviderContractIntegrationTests(unittest.TestCase):
    def test_read_path_rewrites_legacy_magnet_and_preserves_direct_row(self):
        rows = [
            {"source":"Rutor","url":"magnet:?xt=urn:btih:"+"a"*40+"&tr=https%3A%2F%2Fold","voice":"Дубляж","quality":"1080p","seeders":11,"title":"Example 2024"},
            {"source":"Archive","url":"https://cdn.example/file.mp4","voice":"Original","quality":"1080p","seeders":0},
        ]
        identity = {"id":77,"title":"Example","year":2024,"media_type":"movie"}
        with patch.dict(os.environ,{"MOVIA_ENABLE_TORRENT_PROVIDER_CONTRACT":"1"},clear=False):
            out = streamer._rewrite_torrent_candidates_for_identity(rows,identity,None,None)
        self.assertEqual(2,len(out))
        direct=[r for r in out if r["url"].startswith("https://")]
        magnets=[r for r in out if r["url"].startswith("magnet:?")]
        self.assertEqual(1,len(direct));self.assertEqual("Archive",direct[0]["source"])
        self.assertEqual(1,len(magnets));self.assertTrue(magnets[0].get("logical_source_id"));self.assertTrue(magnets[0].get("provider_item_id"));self.assertEqual("a"*40,magnets[0]["info_hash"]);self.assertEqual(11,magnets[0]["seeders"])

    def test_read_path_is_noop_when_feature_is_disabled(self):
        rows=[{"source":"Rutor","url":"magnet:?xt=urn:btih:"+"b"*40,"voice":"Dub","quality":"720p"}]
        identity={"id":77,"title":"Example","year":2024,"media_type":"movie"}
        with patch.dict(os.environ,{"MOVIA_ENABLE_TORRENT_PROVIDER_CONTRACT":"0"},clear=False):
            out=streamer._rewrite_torrent_candidates_for_identity(rows,identity,None,None)
        self.assertIs(out,rows)


if __name__ == '__main__': unittest.main()
