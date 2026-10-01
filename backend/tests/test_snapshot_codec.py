from contextlib import closing
import gzip
import io
import json
import pathlib
import sqlite3
import tempfile
import unittest
from snapshot_codec import export_database, restore_database

class SnapshotCodecTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.temp.name);self.source=self.root/"source.db";self.target=self.root/"target.db"
        with closing(sqlite3.connect(self.source)) as db, db:
            db.execute('CREATE TABLE movies(id INTEGER PRIMARY KEY,title TEXT,payload BLOB,rating REAL)')
            db.execute('INSERT INTO movies VALUES (1,?,?,?)',('Фильм',b'\x00\xff',8.5))
    def tearDown(self):self.temp.cleanup()
    def export(self):
        sink=io.BytesIO();export_database(self.source,sink);return sink.getvalue()
    def test_streaming_roundtrip_preserves_id_unicode_blob_and_rating(self):
        restore_database(io.BytesIO(self.export()),self.target)
        with closing(sqlite3.connect(self.target)) as db, db:self.assertEqual((1,'Фильм',b'\x00\xff',8.5),db.execute('SELECT * FROM movies').fetchone())
    def test_incomplete_snapshot_cannot_replace_existing_catalog(self):
        self.target.write_bytes(b'previous-data');raw=gzip.decompress(self.export()).splitlines();bad=gzip.compress(b'\n'.join(raw[:-1])+b'\n')
        with self.assertRaises(ValueError):restore_database(io.BytesIO(bad),self.target)
        self.assertEqual(b'previous-data',self.target.read_bytes());self.assertEqual([],list(self.root.glob('target.db.restore-*')))
    def test_corrupt_gzip_cannot_replace_existing_catalog(self):
        self.target.write_bytes(b'previous-data');bad=self.export()[:-5]
        with self.assertRaises((EOFError,OSError)):restore_database(io.BytesIO(bad),self.target)
        self.assertEqual(b'previous-data',self.target.read_bytes())
    def test_non_table_sql_is_rejected(self):
        values=[{'format':'movia-catalog-v1'},{'table':'movies','sql':'ATTACH DATABASE "private.db" AS private','columns':['id']},{'end':True}]
        bad=gzip.compress(b'\n'.join(json.dumps(v).encode() for v in values)+b'\n')
        with self.assertRaises(ValueError):restore_database(io.BytesIO(bad),self.target)
        self.assertFalse((self.root/'private.db').exists())
    def test_unicode_and_quoted_column_names_are_preserved(self):
        with closing(sqlite3.connect(self.source)) as db, db:db.execute('CREATE TABLE "extra"("a""b" TEXT)');db.execute('INSERT INTO extra VALUES (?)',('text',))
        restore_database(io.BytesIO(self.export()),self.target)
        with closing(sqlite3.connect(self.target)) as db, db:self.assertEqual('text',db.execute('SELECT * FROM extra').fetchone()[0])
    def test_fts_shadow_data_is_rebuilt_instead_of_being_copied(self):
        with closing(sqlite3.connect(self.source)) as db, db:db.execute('CREATE VIRTUAL TABLE search USING fts5(title)');db.execute('INSERT INTO search VALUES (?)',('title',))
        raw=gzip.decompress(self.export());self.assertNotIn(b'CREATE VIRTUAL TABLE',raw);self.assertNotIn(b'search_data',raw)

if __name__ == '__main__':unittest.main()
