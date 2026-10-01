from contextlib import closing
import tempfile,unittest,json,sqlite3,time
from pathlib import Path
from unittest.mock import patch
import catalog_api,database
from catalog_schema_v2 import normalize_ru_text,ensure_schema

class CatalogPagingContractTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.path=Path(self.temp.name)/'catalog.db'
  with patch.object(database,'DB_PATH',self.path):database.init_db()
  self.catalog=patch.object(catalog_api,'DB_PATH',self.path);self.catalog.start()
  catalog_api._POPULAR_ORDER_CACHE.clear()
  with closing(sqlite3.connect(self.path)) as c, c:
   for i in range(1,121):
    title='Тестовая карточка '+str(i)
    c.execute('INSERT INTO movies(id,tmdb_id,media_type,title,localized_ru_title,normalized_ru_title,original_title,normalized_original_title,year,rating,vote_count,poster_url,country,category,genres,duration_minutes,age_rating,streams) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
     (i,1000+i,'tv' if i%2 else 'movie',title,title,normalize_ru_text(title),title,normalize_ru_text(title),2026 if i%3==0 else 2020,8.0,100+i,'https://image.test/a.jpg',['Россия','США','Южная Корея'][i%3],'tv_series' if i%2 else 'movies',json.dumps(['Драма' if i%2 else 'Комедия'],ensure_ascii=False),90 if i%2 else 120,None if i%2 else 12,json.dumps([{'quality':'720p','language':'ru'}])))
 def tearDown(self):self.catalog.stop();self.temp.cleanup()
 def page(self,**kw):return catalog_api.get_catalog_paged(discover=False,**kw)
 def test_search_pages_past_sixty_without_duplicates(self):
  a=self.page(query_text='тестовая',limit=60);b=self.page(query_text='тестовая',limit=60,offset=60)
  self.assertEqual(120,a['total']);self.assertEqual(60,len(a['items']));self.assertEqual(60,len(b['items']))
  self.assertEqual(120,len({x['id'] for x in a['items']+b['items']}))
 def test_search_keeps_type_sort_and_all_genres(self):
  p=self.page(query_text='тестовая',media_type='movie',genre=['Драма','Комедия'],sort='TITLE',limit=100)
  self.assertEqual(60,p['total']);self.assertTrue(all(x['mediaType']=='movie' for x in p['items']))
  titles=[normalize_ru_text(x['title']) for x in p['items']];self.assertEqual(sorted(titles),titles)
 def test_filters_are_applied_before_count_and_paging(self):
  p=self.page(query_text='тестовая',duration_mode='LONG',max_age_rating=12,resolution='720p',audio_language='ru',limit=100)
  self.assertEqual(60,p['total']);self.assertEqual(60,len(p['items']))
 def test_popular_has_full_stable_coverage(self):
  pages=[self.page(limit=40,offset=o) for o in [0,40,80]]
  ids=[x['id'] for p in pages for x in p['items']]
  self.assertEqual(120,len(ids));self.assertEqual(120,len(set(ids)))
  self.assertEqual(ids[:40],[x['id'] for x in self.page(limit=40)['items']])
 def test_out_of_range_is_fast_and_does_not_build_popular_order(self):
  with patch.object(catalog_api,'_popular_order',side_effect=AssertionError('Unnecessary scan')):
   p=self.page(limit=20,offset=1000000)
  self.assertEqual([],p['items']);self.assertEqual(120,p['total'])
 def test_unknown_age_is_nullable_and_languages_are_not_invented(self):
  a=self.page(media_type='tv',sort='RATING',limit=1)['items'][0]
  self.assertIsNone(a['ageRating']);self.assertEqual(['ru'],a['audioLanguages']);self.assertEqual([],a['subtitleLanguages'])
 def test_delete_and_update_keep_fts_consistent_transactionally(self):
  with closing(sqlite3.connect(self.path)) as c, c:
   c.execute('DELETE FROM movies WHERE id=1')
   self.assertEqual(0,c.execute('SELECT COUNT(*) FROM movies_search_trigram WHERE rowid=1').fetchone()[0])
   c.execute('UPDATE movies SET normalized_ru_title=? WHERE id=2',('другое имя',))
   self.assertEqual('другое имя',c.execute('SELECT normalized_ru_title FROM movies_search_trigram WHERE rowid=2').fetchone()[0])
 def test_schema_migration_is_idempotent(self):
  first=ensure_schema(self.path);second=ensure_schema(self.path)
  self.assertEqual([],second['added_columns']);self.assertEqual(first['revision'],second['revision'])

 def test_visible_catalog_counts_and_sort_use_covering_indexes(self):
  from catalog_sql import USER_VISIBLE_SQL
  with closing(sqlite3.connect(self.path)) as c, c:
   plan=' '.join(r[3] for r in c.execute('EXPLAIN QUERY PLAN SELECT COUNT(*) FROM movies INDEXED BY idx_movies_visible_feed WHERE '+USER_VISIBLE_SQL))
   self.assertIn('idx_movies_visible_feed',plan)
   self.assertNotIn('sqlite_autoindex_movies_1',plan)
   plan=' '.join(r[3] for r in c.execute('EXPLAIN QUERY PLAN SELECT id FROM movies INDEXED BY idx_movies_visible_rating WHERE '+USER_VISIBLE_SQL+' ORDER BY rating DESC,vote_count DESC,seeders DESC,id ASC LIMIT 20'))
   self.assertNotIn('TEMP B-TREE',plan)
 def test_search_probes_titles_then_looks_up_only_matching_rows(self):
  from search_service import _candidate_predicate
  predicate,params=_candidate_predicate('тестовая','тестовб')
  with closing(sqlite3.connect(self.path)) as c, c:
   plan=' '.join(r[3] for r in c.execute('EXPLAIN QUERY PLAN SELECT id FROM movies NOT INDEXED WHERE '+predicate,params))
   self.assertIn('idx_movies_normalized_ru_title',plan)
   self.assertIn('idx_movies_normalized_original_title',plan)
   self.assertIn('INTEGER PRIMARY KEY',plan)

 def test_genre_and_country_filter_do_not_match_substrings(self):
  self.assertEqual(0,self.page(genre=['рама'])['total'])
  self.assertEqual(0,self.page(country='С') ['total'])
  self.assertEqual(40,self.page(country='США')['total'])
