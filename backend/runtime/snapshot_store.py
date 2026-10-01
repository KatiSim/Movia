"""Private PostgreSQL checkpoints let the free service use ephemeral disk safely."""
from contextlib import closing
import hashlib
import io
import os
import sqlite3
import uuid
from snapshot_codec import export_database, restore_database

CHUNK = 256 * 1024
def connection():
    import psycopg
    return psycopg.connect(os.environ["MOVIA_SNAPSHOT_DSN"], connect_timeout=10,
        sslmode="verify-full", sslrootcert=os.environ.get("PGSSLROOTCERT", "/etc/ssl/certs/ca-certificates.crt"))

def schema(conn):
    conn.execute("CREATE TABLE IF NOT EXISTS movia_snapshot_manifest(name TEXT PRIMARY KEY,generation TEXT NOT NULL,sha256 TEXT NOT NULL,revision BIGINT NOT NULL)")
    conn.execute("CREATE TABLE IF NOT EXISTS movia_snapshot_chunks(name TEXT NOT NULL,generation TEXT NOT NULL,ordinal INTEGER NOT NULL,payload BYTEA NOT NULL,PRIMARY KEY(name,generation,ordinal))")

class Writer:
    def __init__(self, conn, generation):
        self.conn, self.generation, self.buffer, self.ordinal = conn, generation, bytearray(), 0
        self.digest = hashlib.sha256()
    def write(self, data):
        self.digest.update(data)
        self.buffer.extend(data)
        while len(self.buffer) >= CHUNK:
            self.flush_chunk(bytes(self.buffer[:CHUNK])); del self.buffer[:CHUNK]
        return len(data)
    def flush_chunk(self, data):
        self.conn.execute("INSERT INTO movia_snapshot_chunks VALUES (%s,%s,%s,%s)",
            ("catalog", self.generation, self.ordinal, data))
        self.ordinal += 1
    def flush(self): pass
    def finish(self):
        if self.buffer: self.flush_chunk(bytes(self.buffer)); self.buffer.clear()

def checkpoint(database):
    from catalog_schema_v2 import get_revision
    with closing(sqlite3.connect(database)) as db, db: revision = get_revision(db)
    with connection() as conn:
        schema(conn)
        previous = conn.execute("SELECT revision FROM movia_snapshot_manifest WHERE name=%s", ("catalog",)).fetchone()
        if previous and previous[0] == revision: return False
        generation = uuid.uuid4().hex
        writer = Writer(conn, generation)
        export_database(database, writer);writer.finish()
        conn.execute("INSERT INTO movia_snapshot_manifest VALUES (%s,%s,%s,%s) ON CONFLICT(name) DO UPDATE SET generation=excluded.generation,sha256=excluded.sha256,revision=excluded.revision",
            ("catalog", generation, writer.digest.hexdigest(), revision))
        conn.execute("DELETE FROM movia_snapshot_chunks WHERE name=%s AND generation<>%s", ("catalog", generation))
    return True

def restore(database):
    with connection() as conn:
        schema(conn)
        meta = conn.execute("SELECT generation,sha256 FROM movia_snapshot_manifest WHERE name=%s", ("catalog",)).fetchone()
        if not meta: raise ValueError("initial_snapshot_missing")
        cursor = conn.cursor(name="movia_snapshot_restore")
        cursor.execute("SELECT payload FROM movia_snapshot_chunks WHERE name=%s AND generation=%s ORDER BY ordinal", ("catalog",meta[0]))
        class Reader(io.RawIOBase):
            def __init__(self): self.current = memoryview(b"");self.digest = hashlib.sha256();self.done = False
            def readable(self): return True
            def readinto(self, target):
                if not self.current:
                    row = cursor.fetchone()
                    if row is None:
                        if not self.done and self.digest.hexdigest() != meta[1]: raise ValueError("snapshot_checksum_failed")
                        self.done = True;return 0
                    self.current = memoryview(bytes(row[0]));self.digest.update(self.current)
                count = min(len(target), len(self.current));target[:count] = self.current[:count];self.current = self.current[count:];return count
        with io.BufferedReader(Reader(), buffer_size=CHUNK) as reader: restore_database(reader,database)
        cursor.close()

if __name__ == "__main__":
    from movia_paths import DATA_DIR
    checkpoint(DATA_DIR / "catalog.db")
