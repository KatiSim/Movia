import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

class IdentityReaderTest(unittest.TestCase):
    def test_catalog_playback_read_does_not_import_or_run_writer_schema_migrations(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "catalog.db"
            with sqlite3.connect(path) as conn:
                conn.execute("CREATE TABLE movies(id INTEGER,title TEXT,localized_ru_title TEXT,original_title TEXT,year INTEGER,media_type TEXT,tmdb_id INTEGER,poster_url TEXT,streams TEXT)")
                conn.execute("INSERT INTO movies VALUES(7,'Фильм','Фильм','Fixture',2020,'movie',77,'https://poster.example/x',?)",(json.dumps([{"source":"Provider","voice":"Studio B","quality":"720p","url":"https://media.example/x.mp4"}]),))
                schema = conn.execute("PRAGMA schema_version").fetchone()[0]
            code = """
import sys,json,sqlite3
from pathlib import Path
import catalog_api
card=catalog_api.get_movie_playback_card('7')
assert card['streams'][0]['voice']=='Studio B'
assert 'database' not in sys.modules, 'Read path imported writer initialization'
with sqlite3.connect(catalog_api.DB_PATH) as conn:
 assert conn.execute('PRAGMA schema_version').fetchone()[0] == 1
print('READ_ONLY_IDENTITY_PASS')
"""
            result = subprocess.run([sys.executable,'-c',code],env=dict(os.environ,MOVIA_DATA_DIR=folder),capture_output=True,text=True,timeout=15)
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertIn('READ_ONLY_IDENTITY_PASS',result.stdout)

    def test_magnet_display_name_survives_reader_extraction_and_wrong_film_is_rejected(self):
        from stream_identity import _stream_identity_title, filter_streams_for_content
        from urllib.parse import quote
        def source(title):
            return {"source":"Fixture","voice":"Studio A","quality":"1080p",
                "url":"magnet:?xt=urn:btih:"+"a"*40+"&dn="+quote(title)}
        exact=source("The.Matrix.1999.1080p.BluRay")
        wrong=source("Alien.1979.1080p.BluRay")
        self.assertEqual("The.Matrix.1999.1080p.BluRay",_stream_identity_title(exact))
        kept=filter_streams_for_content([exact,wrong],{"id":7,"title":"Матрица","original_title":"The Matrix","year":1999,"media_type":"movie"})
        self.assertEqual([exact["url"]],[item["url"] for item in kept])

    def test_movie_without_release_name_rejects_episode_bound_cached_sources(self):
        from stream_identity import filter_streams_for_content
        base={"source":"Collaps","voice":"Studio A","quality":"720p","url":"https://media.example/x.m3u8"}
        rows=[base,dict(base,season=0,episode=0),dict(base,season=1,episode=1),
              dict(base,season=1),dict(base,episode=2)]
        kept=filter_streams_for_content(rows,{"id":7,"title":"Fixture","year":2020,"media_type":"movie"})
        self.assertEqual(2,len(kept))
        self.assertTrue(all(row.get("season",0)==0 and row.get("episode",0)==0 for row in kept))

    def test_exact_series_episode_remains_available_after_movie_scope_filter(self):
        from stream_identity import filter_streams_for_content
        base={"source":"Collaps","voice":"Studio B","quality":"720p","url":"https://media.example/series.m3u8"}
        rows=[dict(base,season=1,episode=2),dict(base,season=1,episode=1),base]
        kept=filter_streams_for_content(rows,{"id":8,"title":"Fixture","year":2020,"media_type":"tv","season":1,"episode":2})
        self.assertEqual(1,len(kept));self.assertEqual(2,kept[0]["episode"])
