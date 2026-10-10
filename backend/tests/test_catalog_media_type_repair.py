from contextlib import closing
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import catalog_api
import database
from catalog_schema_v2 import normalize_ru_text


def tv_detail(tmdb_id=77, title='Сериал', original='Series', year=2020):
    return {
        'tmdb_id': tmdb_id, 'media_type': 'tv', 'title': title,
        'localized_ru_title': title, 'original_title': original, 'year': year,
        'alternative_titles': [], 'imdb_id': 'tt123', 'rating': 8.0,
        'vote_average': 8.0, 'vote_count': 100, 'duration_minutes': 45,
        'synopsis': 'TV', 'poster_url': 'https://image.test/tv.jpg',
        'backdrop_url': 'https://image.test/bg.jpg', 'genres': ['Мультфильм'],
        'cast': [], 'director': '', 'creators': ['Creator'], 'country': 'США',
        'category': 'animation', 'collection_id': 0, 'seasons_count': 2,
        'episodes_count': 20, 'season_episode_counts': [10,10],
    }


class CatalogMediaTypeRepairTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.path=Path(self.tmp.name)/'catalog.db'
        with patch.object(database,'DB_PATH',self.path): database.init_db()
        self.dbpatch=patch.object(catalog_api,'DB_PATH',self.path); self.dbpatch.start()

    def tearDown(self):
        self.dbpatch.stop(); self.tmp.cleanup()

    def insert(self, *, row_id, tmdb_id=77, media_type='movie', title='Сериал', original='Series', year=2020, streams=None):
        streams=[] if streams is None else streams
        with closing(sqlite3.connect(self.path)) as c, c:
            c.execute('''INSERT INTO movies(
                id,tmdb_id,media_type,title,localized_ru_title,normalized_ru_title,
                original_title,normalized_original_title,year,category,poster_url,
                streams,seasons_count,episodes_count,metadata_source
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',(
                row_id,tmdb_id,media_type,title,title,normalize_ru_text(title),
                original,normalize_ru_text(original),year,'animation','https://image.test/a.jpg',
                json.dumps(streams),2 if media_type=='tv' else 0,20 if media_type=='tv' else 0,'legacy'
            ))

    def test_existing_tv_duplicate_is_kept_and_old_movie_id_redirects(self):
        stream={'source':'Fixture','provider':'Fixture','voice':'Dub','quality':'720p',
                'url':'https://media.example/series.m3u8','catalog_media_id':'2',
                'canonical_title':'Сериал','canonical_original_title':'Series',
                'canonical_year':2020,'canonical_media_type':'tv','season':1,'episode':1}
        self.insert(row_id=1,media_type='movie')
        self.insert(row_id=2,media_type='tv',streams=[stream])
        with closing(catalog_api.get_db()) as conn, conn, \
             patch.object(catalog_api.tmdb,'get_movie_details',return_value=None), \
             patch.object(catalog_api.tmdb,'get_tv_details',return_value=tv_detail()):
            wrong=conn.execute('SELECT * FROM movies WHERE id=1').fetchone()
            repaired=catalog_api._repair_media_type_if_ambiguous(conn,wrong)
            self.assertEqual(2,repaired['id'])
        with closing(sqlite3.connect(self.path)) as c:
            self.assertEqual('2',c.execute("SELECT value FROM catalog_meta WHERE key='catalog_redirect:1'").fetchone()[0])
            self.assertEqual('',c.execute('SELECT localized_ru_title FROM movies WHERE id=1').fetchone()[0])
            self.assertEqual(1,c.execute('SELECT COUNT(*) FROM movies WHERE id=2').fetchone()[0])
        card=catalog_api.get_movie_playback_card('1')
        self.assertEqual('2',card['id'])
        self.assertEqual(1,len(card['streams']))

    def test_missing_tv_duplicate_repairs_same_id_in_place(self):
        self.insert(row_id=1,media_type='movie')
        with closing(catalog_api.get_db()) as conn, conn, \
             patch.object(catalog_api.tmdb,'get_movie_details',return_value=None), \
             patch.object(catalog_api.tmdb,'get_tv_details',return_value=tv_detail()):
            wrong=conn.execute('SELECT * FROM movies WHERE id=1').fetchone()
            repaired=catalog_api._repair_media_type_if_ambiguous(conn,wrong)
            self.assertEqual(1,repaired['id']); self.assertEqual('tv',repaired['media_type'])
            self.assertEqual(2,repaired['seasons_count']); self.assertEqual(20,repaired['episodes_count'])
            self.assertEqual('tmdb_detail',repaired['metadata_source'])
        self.assertEqual('series',catalog_api.get_movie_playback_card('1')['type'])

    def test_legitimate_movie_is_not_retyped_when_movie_identity_matches(self):
        self.insert(row_id=1,media_type='movie',title='Мультфильм',original='Animation Movie')
        movie={'tmdb_id':77,'media_type':'movie','title':'Мультфильм','original_title':'Animation Movie','year':2020}
        with closing(catalog_api.get_db()) as conn, conn, \
             patch.object(catalog_api.tmdb,'get_movie_details',return_value=movie), \
             patch.object(catalog_api.tmdb,'get_tv_details') as tv:
            row=conn.execute('SELECT * FROM movies WHERE id=1').fetchone()
            kept=catalog_api._repair_media_type_if_ambiguous(conn,row)
            self.assertEqual('movie',kept['media_type']); tv.assert_not_called()

if __name__=='__main__': unittest.main()
