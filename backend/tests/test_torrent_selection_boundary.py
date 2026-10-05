import unittest
from provider_contract import ProviderRequest, VariantStream
from torrent_provider_adapter import rewrite_torrent_rows_as_variant_tree as rewrite
from stream_validation import sanitize_streams

class TorrentSelectionBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.request = ProviderRequest(media_id="77", title="Example", year=2024)
        self.row = {"source":"Rutor", "url":"magnet:?xt=urn:btih:"+"a"*40,
                    "voice":"Studio", "quality":"1080p"}

    def one(self, **kwargs):
        return rewrite([{**self.row, **kwargs}], self.request)[0]

    def test_zero_indexes_and_actual_language_survive(self):
        row = self.one(audio_track_index=0, video_track_index=0, file_index=0,
                       file_path="movie.mkv", language="en")
        self.assertEqual((0,0,0,"movie.mkv","en"), tuple(row[x] for x in
                         ("audio_track_index","video_track_index","file_index","file_path","language")))

    def test_camelcase_file_selector_survives(self):
        row = self.one(fileIndex="2", filePath="movie.mkv")
        self.assertEqual((2,"movie.mkv"), (row["file_index"],row["file_path"]))

    def test_named_track_metadata_and_playback_profile_survive(self):
        row = self.one(audio_track_index=2, transport_metadata={"movia_audio_label":"Studio"},
                       codec="aac", headers={"Referer":"https://provider.example","Cookie":"secret"},
                       user_agent="Movia", is_use_internal_subtitles=True)
        self.assertEqual("Studio", row["transport_metadata"]["movia_audio_label"])
        self.assertEqual({"Referer":"https://provider.example"},row["headers"])
        self.assertEqual("Movia",row["user_agent"])
        self.assertTrue(row["is_use_internal_subtitles"])

    def test_unknown_indexes_are_not_created(self):
        row = self.one(title="P, P2, A")
        for key in ("audio_track_index","video_track_index","file_index"):
            self.assertNotIn(key,row)

    def test_distinct_files_keep_distinct_leaf_ids(self):
        rows = rewrite([{**self.row,"file_index":i} for i in (0,1)],self.request)
        self.assertEqual(2,len(rows))
        self.assertEqual(2,len({x["logical_source_id"] for x in rows}))
        self.assertEqual(2,len({x["provider_item_id"] for x in rows}))

    def test_absent_audio_index_is_not_zero_identity(self):
        unknown = self.one()
        first = self.one(audio_track_index=0)
        self.assertNotEqual(unknown["provider_item_id"],first["provider_item_id"])
        self.assertNotEqual(unknown["logical_source_id"],first["logical_source_id"])

    def test_magnet_file_selection_is_part_of_identity(self):
        a = self.one(url=self.row["url"]+"&so=0")
        b = self.one(url=self.row["url"]+"&so=1")
        self.assertNotEqual(a["logical_source_id"],b["logical_source_id"])

    def test_rewrite_is_idempotent_for_selection(self):
        row=self.one(file_index=0,audio_track_index=0,language="en",
                     transport_metadata={"movia_audio_label":"Studio"})
        self.assertEqual(row,rewrite([row],self.request)[0])

    def test_foreign_catalog_binding_is_rejected(self):
        for fields in ({"catalog_media_id":"78"},{"catalogMediaId":"78"},
                       {"canonical_year":1977},{"canonicalYear":"invalid"}):
            self.assertEqual([],rewrite([{**self.row,**fields}],self.request))

    def test_invalid_file_indexes_never_become_selectable(self):
        for value in (-1,True,1.5,"bad",2**35):
            self.assertNotIn("file_index",self.one(file_index=value))

    def test_contract_rejects_invalid_file_index(self):
        with self.assertRaises(ValueError):
            VariantStream(url=self.row["url"],file_index=-1)

if __name__ == "__main__":
    unittest.main()
