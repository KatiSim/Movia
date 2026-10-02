from contextlib import closing
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import database
from catalog_schema_v2 import ensure_schema, get_revision

class SchemaIdempotenceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.path=Path(self.temp.name)/'catalog.db'
        with patch.object(database,'DB_PATH',self.path):database.init_db()
        with sqlite3.connect(self.path) as connection:
            connection.executemany('INSERT INTO movies(id,tmdb_id,title,original_title,year) VALUES(?,?,?,?,?)',
                [(1,1,'Foreign Film','Foreign Film',2020),(2,2,'No original','',0)])
    def tearDown(self):self.temp.cleanup()
    def test_unknown_titles_are_processed_once_and_do_not_bump_revision_again(self):
        first=ensure_schema(self.path);self.assertEqual(2,first['backfilled_rows'])
        second=ensure_schema(self.path);self.assertEqual(0,second['backfilled_rows'])
        self.assertEqual(first['revision'],second['revision'])
        with sqlite3.connect(self.path) as connection:
            rows=connection.execute('SELECT localized_ru_title,original_title,year,duration_minutes FROM movies ORDER BY id').fetchall()
        self.assertEqual([('', 'Foreign Film',2020,0),('', '',0,0)],rows)
    def test_new_unprocessed_rows_are_backfilled_without_rewriting_existing_unknowns(self):
        ensure_schema(self.path)
        with sqlite3.connect(self.path) as connection:
            connection.execute("INSERT INTO movies(id,tmdb_id,title,original_title) VALUES(3,3,'New unknown','')")
        self.assertEqual(1,ensure_schema(self.path)['backfilled_rows'])
        self.assertEqual(0,ensure_schema(self.path)['backfilled_rows'])
    def test_normalization_version_change_reprocesses_existing_rows_once(self):
        ensure_schema(self.path)
        with sqlite3.connect(self.path) as connection:
            connection.execute("UPDATE catalog_meta SET value='0' WHERE key='normalization_version'")
        self.assertEqual(2,ensure_schema(self.path)['backfilled_rows'])
        self.assertEqual(0,ensure_schema(self.path)['backfilled_rows'])
