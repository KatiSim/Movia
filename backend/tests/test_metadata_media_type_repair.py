import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import metadata_repair
import database


def detail(media_type='tv', title='Сериал', original='Series', year=2020, tmdb_id=77):
    return {
        'tmdb_id': tmdb_id, 'media_type': media_type, 'title': title,
        'localized_ru_title': title, 'original_title': original, 'year': year,
        'alternative_titles': [], 'imdb_id': 'tt123', 'rating': 8.0,
        'vote_average': 8.0, 'vote_count': 100, 'duration_minutes': 45,
        'synopsis': 'x', 'poster_url': 'https://image.test/a.jpg',
        'backdrop_url': 'https://image.test/b.jpg', 'genres': ['Мультфильм'],
        'cast': [], 'director': '', 'creators': [], 'country': 'США',
        'category': 'animation', 'collection_id': 0,
        'seasons_count': 2 if media_type=='tv' else 0,
        'episodes_count': 20 if media_type=='tv' else 0,
        'season_episode_counts': [10,10] if media_type=='tv' else [],
    }


class MetadataMediaTypeRepairTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.path=Path(self.tmp.name)/'catalog.db'
        with patch.object(database,'DB_PATH',self.path): database.init_db()
        self.conn=sqlite3.connect(self.path); self.conn.row_factory=sqlite3.Row

    def tearDown(self): self.conn.close(); self.tmp.cleanup()

    def row(self, **changes):
        row={'id':1,'tmdb_id':77,'media_type':'movie','title':'Сериал','original_title':'Series','year':2020,'streams':'[]'}
        row.update(changes); return row

    def test_fetch_checks_opposite_namespace_only_when_current_missing(self):
        client=unittest.mock.Mock()
        client.get_movie_details.return_value=None
        client.get_tv_details.return_value=detail()
        with patch.object(metadata_repair,'_client',return_value=client):
            row_id,data,error=metadata_repair._fetch(self.row())
        self.assertEqual(1,row_id); self.assertEqual('wrong_media_type',error); self.assertEqual('tv',data['media_type'])
        client.get_movie_details.assert_called_once_with(77); client.get_tv_details.assert_called_once_with(77)

    def test_fetch_ignores_numeric_collision_and_uses_matching_opposite_namespace(self):
        client=unittest.mock.Mock()
        client.get_movie_details.return_value=detail(
            media_type='movie', title='Другой фильм', original='Other Movie', year=2020
        )
        client.get_tv_details.return_value=detail(media_type='tv')
        with patch.object(metadata_repair,'_client',return_value=client):
            _,data,error=metadata_repair._fetch(self.row())
        self.assertEqual('wrong_media_type',error)
        self.assertEqual('tv',data['media_type'])

    def test_fetch_rejects_opposite_namespace_when_identity_does_not_match(self):
        client=unittest.mock.Mock(); client.get_movie_details.return_value=None
        client.get_tv_details.return_value=detail(title='Другое', original='Other', year=2020)
        with patch.object(metadata_repair,'_client',return_value=client):
            _,data,error=metadata_repair._fetch(self.row())
        self.assertIsNone(data); self.assertEqual('tmdb_not_found',error)

    def test_correction_preserves_existing_canonical_tv_and_redirects_old_id(self):
        self.conn.execute("INSERT INTO movies(id,tmdb_id,media_type,title,original_title,year,localized_ru_title,normalized_ru_title,poster_url,streams) VALUES(1,77,'movie','Сериал','Series',2020,'Сериал','сериал','x','[]')")
        self.conn.execute("INSERT INTO movies(id,tmdb_id,media_type,title,original_title,year,localized_ru_title,normalized_ru_title,poster_url,streams) VALUES(2,77,'tv','Сериал','Series',2020,'Сериал','сериал','x',?)",(json.dumps([{'url':'magnet:?xt=urn:btih:'+'a'*40}]),))
        self.conn.commit()
        canonical=metadata_repair.apply_media_type_correction(self.conn,self.row(),detail())
        self.conn.commit(); self.assertEqual(2,canonical)
        self.assertEqual('2',self.conn.execute("SELECT value FROM catalog_meta WHERE key='catalog_redirect:1'").fetchone()[0])
        self.assertEqual('',self.conn.execute('SELECT localized_ru_title FROM movies WHERE id=1').fetchone()[0])
        self.assertEqual(1,len(json.loads(self.conn.execute('SELECT streams FROM movies WHERE id=2').fetchone()[0])))

    def test_correction_retypes_same_id_when_no_canonical_duplicate(self):
        self.conn.execute("INSERT INTO movies(id,tmdb_id,media_type,title,original_title,year,localized_ru_title,normalized_ru_title,poster_url,streams) VALUES(1,77,'movie','Сериал','Series',2020,'Сериал','сериал','x','[]')")
        self.conn.commit()
        canonical=metadata_repair.apply_media_type_correction(self.conn,self.row(),detail())
        self.conn.commit(); self.assertEqual(1,canonical)
        fixed=self.conn.execute('SELECT media_type,seasons_count,episodes_count,metadata_source FROM movies WHERE id=1').fetchone()
        self.assertEqual(('tv',2,20,'tmdb_detail'),tuple(fixed))

    def test_correction_redirects_stream_bearing_wrong_row_when_canonical_duplicate_exists(self):
        self.conn.execute("INSERT INTO movies(id,tmdb_id,media_type,title,original_title,year,localized_ru_title,normalized_ru_title,poster_url,streams) VALUES(1,77,'movie','Сериал','Series',2020,'Сериал','сериал','x',?)",(json.dumps([{'url':'https://legacy.example/x.mp4'}]),))
        self.conn.execute("INSERT INTO movies(id,tmdb_id,media_type,title,original_title,year,localized_ru_title,normalized_ru_title,poster_url,streams) VALUES(2,77,'tv','Сериал','Series',2020,'Сериал','сериал','x',?)",(json.dumps([{'url':'https://canonical.example/ep.m3u8'}]),))
        self.conn.commit()
        row=self.row(streams=json.dumps([{'url':'https://legacy.example/x.mp4'}]))
        canonical=metadata_repair.apply_media_type_correction(self.conn,row,detail())
        self.conn.commit(); self.assertEqual(2,canonical)
        self.assertEqual('2',self.conn.execute("SELECT value FROM catalog_meta WHERE key='catalog_redirect:1'").fetchone()[0])
        self.assertEqual('tmdb_wrong_media_type',self.conn.execute('SELECT metadata_source FROM movies WHERE id=1').fetchone()[0])
        self.assertEqual('https://legacy.example/x.mp4',json.loads(self.conn.execute('SELECT streams FROM movies WHERE id=1').fetchone()[0])[0]['url'])
        self.assertEqual('https://canonical.example/ep.m3u8',json.loads(self.conn.execute('SELECT streams FROM movies WHERE id=2').fetchone()[0])[0]['url'])

    def test_correction_creates_canonical_row_for_stream_bearing_wrong_type_without_duplicate(self):
        self.conn.execute("INSERT INTO movies(id,tmdb_id,media_type,title,original_title,year,localized_ru_title,normalized_ru_title,poster_url,streams) VALUES(1,77,'movie','Сериал','Series',2020,'Сериал','сериал','x',?)",(json.dumps([{'url':'https://legacy.example/x.mp4'}]),))
        self.conn.commit()
        row=self.row(streams=json.dumps([{'url':'https://legacy.example/x.mp4'}]))
        canonical=metadata_repair.apply_media_type_correction(self.conn,row,detail())
        self.conn.commit(); self.assertNotEqual(1,canonical)
        fixed=self.conn.execute('SELECT media_type,seasons_count,episodes_count,streams FROM movies WHERE id=?',(canonical,)).fetchone()
        self.assertEqual('tv',fixed['media_type']); self.assertEqual(2,fixed['seasons_count']); self.assertEqual(20,fixed['episodes_count']); self.assertEqual([],json.loads(fixed['streams']))
        self.assertEqual(str(canonical),self.conn.execute("SELECT value FROM catalog_meta WHERE key='catalog_redirect:1'").fetchone()[0])
        self.assertEqual('https://legacy.example/x.mp4',json.loads(self.conn.execute('SELECT streams FROM movies WHERE id=1').fetchone()[0])[0]['url'])

if __name__=='__main__': unittest.main()
