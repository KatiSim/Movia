"""Regression tests for the shared HTTP/catalog filtering contract; no network."""
import json
import sqlite3
import unittest
from catalog_query_contract import parse_catalog_query, InvalidCatalogArgument, extra_conditions

class CatalogArgumentTests(unittest.TestCase):
    def test_default_bounds(self):
        p=parse_catalog_query({});self.assertEqual((40,0,'POPULAR'),(p['limit'],p['offset'],p['sort']))
    def test_100_items_and_multiple_genres_survive(self):
        p=parse_catalog_query({'limit':['100'],'offset':['60'],'genre':['Драма','Комедия']})
        self.assertEqual((100,60,['Драма','Комедия']),(p['limit'],p['offset'],p['genre']))
    def test_invalid_queries_fail_closed(self):
        cases=[{'limit':['hello']},{'limit':['0']},{'limit':['101']},{'offset':['-1']},
          {'offset':['nan']},{'offset':['10000001']},{'yearFrom':['0']},{'yearFrom':['2026'],'yearTo':['2020']},
          {'minRating':['NaN']},{'minRating':['inf']},{'minRating':['11']},{'minRating':['-1']},
          {'sort':['RANDOM']},{'durationMode':['FAST']},{'newOnly':['yes']},{'discover':['2']},
          {'maxAgeRating':['-1']},{'maxAgeRating':['31']},{'category':['oops']},{'mediaType':['audio']},
          {'limit':['1','2']},{'query':['a','b']},{'unknown':['x']},{'query':['x'*513]},
          {'genre':['x']*65},{'genre':['']},{'genre':['x'*129]}]
        for p in cases:
            with self.subTest(p=p):
                with self.assertRaises(InvalidCatalogArgument):parse_catalog_query(p)
    def test_boolean_inputs_are_explicit(self):
        for v,wanted in [('0',False),('false',False),('1',True),('true',True)]:
            with self.subTest(v=v):self.assertEqual(wanted,parse_catalog_query({'newOnly':[v]})['new_only'])

class CatalogSqlFilterTests(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(':memory:')
        self.db.execute('CREATE TABLE movies(id INTEGER, quality TEXT, duration_minutes INTEGER, year INTEGER, age_rating INTEGER, streams TEXT)')
        rows=[(1,'720p',100,2026,12,[{'quality':'1080p','language':'ru','subtitles':[{'language':'en'}]}]),
              (2,'Auto',101,2025,None,[{'quality':'360p','language':'en'}]),
              (3,'480p',110,2020,18,[]),(4,'Auto',0,0,None,[])]
        self.db.executemany('INSERT INTO movies VALUES(?,?,?,?,?,?)',[(*x[:5],json.dumps(x[5])) for x in rows])
    def tearDown(self):self.db.close()
    def selected(self,**kwargs):
        conditions,values=extra_conditions(**kwargs)
        return [x[0] for x in self.db.execute('SELECT id FROM movies WHERE '+(' AND '.join(conditions) or '1')+' ORDER BY id',values)]
    def test_resolution_checks_manifest_metadata_too(self):
        self.assertEqual([1],self.selected(resolution='1080p'));self.assertEqual([2],self.selected(resolution='360p'))
    def test_duration_boundaries_exclude_unknown(self):
        self.assertEqual([1],self.selected(duration_mode='SHORT'));self.assertEqual([2],self.selected(duration_mode='MEDIUM'));self.assertEqual([3],self.selected(duration_mode='LONG'))
    def test_new_only_excludes_unknown_and_future_year(self):self.assertEqual([1,2],self.selected(new_only=True))
    def test_unknown_certification_does_not_mean_zero_plus(self):
        self.assertEqual([1],self.selected(max_age_rating=12));self.assertEqual([1,3],self.selected(max_age_rating=18))
    def test_audio_language_is_reported_not_invented(self):
        self.assertEqual([1],self.selected(audio_language='Русский'));self.assertEqual([2],self.selected(audio_language='en'))
    def test_subtitles_are_filtered_independently(self):
        self.assertEqual([1],self.selected(subtitle_language='English'));self.assertEqual([],self.selected(subtitle_language='Русские'))
    def test_parameterized_filters_do_not_accept_sql_injection(self):self.assertEqual([],self.selected(resolution="720p' OR 1=1 --"))
    def test_combinations_share_one_contract(self):
        self.assertEqual([1],self.selected(resolution='1080p',audio_language='ru',new_only=True,max_age_rating=12,duration_mode='SHORT'))


    def test_original_is_not_assumed_to_be_english(self):
        self.db.execute('UPDATE movies SET streams=? WHERE id=2',(json.dumps([{'language':'original'}]),))
        self.assertEqual([],self.selected(audio_language='English'))
    def test_regional_audio_and_subtitle_codes_match_base_language(self):
        self.db.execute('UPDATE movies SET streams=? WHERE id=1',(json.dumps([{'language':'ru-RU','subtitles':[{'language':'en_US'}]}]),))
        self.assertEqual([1],self.selected(audio_language='Русский',subtitle_language='English'))
    def test_legacy_or_malformed_stream_shapes_do_not_crash_filters(self):
        for raw in ['not-json', json.dumps('legacy URL'),json.dumps(['https://example.test/x',None,4,{'subtitles':['en',3,None]}])]:
            with self.subTest(raw=raw):
                self.db.execute('UPDATE movies SET streams=? WHERE id=4',(raw,))
                self.assertNotIn(4,self.selected(resolution='1080p'))
                self.assertNotIn(4,self.selected(audio_language='ru'))
                self.assertNotIn(4,self.selected(subtitle_language='en'))

if __name__=='__main__':unittest.main()
