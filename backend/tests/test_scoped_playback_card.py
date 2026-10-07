import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import catalog_api

class ScopedPlaybackCardTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.db=Path(self.tmp.name)/"cards.db"
        with sqlite3.connect(self.db) as c:
            c.execute("CREATE TABLE movies(id INTEGER, title TEXT, original_title TEXT, year INTEGER, media_type TEXT, category TEXT, streams TEXT)")
            rows=[dict(source="Fixture",url="https://cdn.example/%d.mp4"%i,season=1,episode=i,
                       catalog_media_id="7",canonical_year=2020,canonical_media_type="tv") for i in range(1,601)]
            rows += [dict(rows[1],url="https://cdn.example/foreign.mp4",catalog_media_id="8"),
                     dict(source="Fixture",url="https://cdn.example/pack.mp4"),
                     dict(rows[1],url="https://cdn.example/fraction.mp4",episode=2.5)]
            c.execute("INSERT INTO movies VALUES(7,'Fixture','Fixture',2020,'tv','tv_series',?)",(json.dumps(rows),))
        def connect():
            c=sqlite3.connect(self.db);c.row_factory=sqlite3.Row;return c
        self.patch=patch.object(catalog_api,"get_db",connect);self.patch.start()
    def tearDown(self):self.patch.stop();self.tmp.cleanup()
    def test_prunes_other_episodes_before_normalization_but_still_rejects_foreign_identity(self):
        seen=[];original=catalog_api.map_row_to_media
        def observe(row,compact=True):seen.append(len(row['streams']));return original(row,compact)
        with patch.object(catalog_api,"map_row_to_media",observe):card=catalog_api.get_movie_playback_card_scoped('7',1,2)
        self.assertEqual([2],seen)
        self.assertEqual(1,len(card['streams']))
        self.assertEqual((1,2),(card['streams'][0]['season'],card['streams'][0]['episode']))
        self.assertEqual('7',str(card['streams'][0]['catalog_media_id']))
    def test_invalid_coordinates_never_turn_pack_into_episode(self):
        for season,episode in [(1,None),(1,True),(1,2.5),(1,'2x')]:
            self.assertEqual([],catalog_api.get_movie_playback_card_scoped('7',season,episode)['streams'])
    def test_malformed_stream_container_fails_closed(self):
        for raw in ['null','{}','not-json']:
            with sqlite3.connect(self.db) as c:c.execute("UPDATE movies SET streams=? WHERE id=7",(raw,))
            self.assertEqual([],catalog_api.get_movie_playback_card_scoped('7',1,2)['streams'])
    def test_unknown_or_invalid_catalog_id_remains_not_found(self):
        for ident in ['8','Fixture','7 OR 1=1']:self.assertIsNone(catalog_api.get_movie_playback_card_scoped(ident,1,2))
