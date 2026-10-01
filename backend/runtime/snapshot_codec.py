"""Stream a consistent SQLite catalog as gzip JSON-lines without a second full database copy."""
import base64
import gzip
import json
import os
import sqlite3
import uuid
from contextlib import closing

MAX_LINE = 8 * 1024 * 1024
def identifier(value):
    if not isinstance(value, str) or not value or len(value) > 128 or "\0" in value:
        raise ValueError("invalid_identifier")
    return '"' + value.replace('"', '""') + '"'

def emit(sink, value):
    data = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode() + b"\n"
    if len(data) > MAX_LINE: raise ValueError("snapshot_row_too_large")
    sink.write(data)

def export_database(database, sink):
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as conn:
        conn.execute("BEGIN")
        ordinary = {row[1] for row in conn.execute("PRAGMA table_list")
                    if row[0] == "main" and row[2] == "table" and not row[1].startswith("sqlite_")}
        with gzip.GzipFile(fileobj=sink, mode="wb", compresslevel=5, mtime=0) as compressed:
            emit(compressed, {"format": "movia-catalog-v2"})
            tables = conn.execute("SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
            for name, sql in tables:
                if name not in ordinary: continue
                columns = [row[1] for row in conn.execute("PRAGMA table_info(" + identifier(name) + ")")]
                emit(compressed, {"table": name, "sql": sql, "columns": columns})
                for row in conn.execute("SELECT * FROM " + identifier(name)):
                    emit(compressed, {"row": [{"blob": base64.b64encode(v).decode()} if isinstance(v, bytes) else v for v in row]})
            # Explicit restored IDs only advance SQLite's counter to the last
            # surviving row. Preserve its high-water mark after deletions too,
            # so a later new film cannot reuse a retired favorite/history ID.
            has_sequence = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='sqlite_sequence'").fetchone()
            sequences = list(conn.execute("SELECT name,seq FROM sqlite_sequence ORDER BY name")) if has_sequence else []
            emit(compressed, {"sequences": sequences})
            emit(compressed, {"end": True})
    return len(ordinary)

def restore_database(source, target):
    temporary = target.with_name(target.name + ".restore-" + uuid.uuid4().hex)
    table, sql, finished, total = None, None, False, 0
    auto_tables, sequences_seen = set(), False
    try:
        with closing(sqlite3.connect(temporary)) as conn, gzip.GzipFile(fileobj=source, mode="rb") as compressed:
            conn.execute("PRAGMA journal_mode=DELETE")
            header = json.loads(compressed.readline(MAX_LINE + 1))
            if header not in ({"format": "movia-catalog-v1"}, {"format": "movia-catalog-v2"}):
                raise ValueError("invalid_snapshot_format")
            while True:
                line = compressed.readline(MAX_LINE + 1)
                if not line: break
                total += len(line)
                if len(line) > MAX_LINE or total > 2 * 1024**3: raise ValueError("snapshot_limit")
                value = json.loads(line)
                if "table" in value:
                    table = value["table"]
                    identifier(table)
                    definition = value["sql"]
                    if not isinstance(definition, str) or not definition.upper().lstrip().startswith("CREATE TABLE "):
                        raise ValueError("invalid_table_definition")
                    conn.execute(definition)
                    if "AUTOINCREMENT" in definition.upper(): auto_tables.add(table)
                    columns = value["columns"]
                    if not isinstance(columns, list) or not 1 <= len(columns) <= 512:
                        raise ValueError("invalid_columns")
                    sql = "INSERT INTO " + identifier(table) + " (" + ",".join(identifier(c) for c in columns) + ") VALUES (" + ",".join("?" for _ in columns) + ")"
                elif "row" in value and table and sql:
                    row = value["row"]
                    if not isinstance(row, list) or len(row) != len(columns): raise ValueError("invalid_row")
                    row = [base64.b64decode(v["blob"], validate=True) if isinstance(v, dict) and set(v) == {"blob"} else v for v in row]
                    conn.execute(sql, row)
                elif set(value) == {"sequences"}:
                    sequences = value["sequences"]
                    if sequences_seen or not isinstance(sequences, list) or len(sequences) > len(auto_tables):
                        raise ValueError("invalid_snapshot_sequences")
                    names = set()
                    for entry in sequences:
                        if not isinstance(entry, list) or len(entry) != 2:
                            raise ValueError("invalid_snapshot_sequence")
                        name, sequence = entry
                        if name not in auto_tables or name in names or type(sequence) is not int or not 0 <= sequence <= 2**63-1:
                            raise ValueError("invalid_snapshot_sequence")
                        names.add(name)
                        previous = conn.execute("SELECT seq FROM sqlite_sequence WHERE name=?", (name,)).fetchone()
                        if previous and sequence < previous[0]: raise ValueError("snapshot_sequence_reuses_ids")
                        if previous: conn.execute("UPDATE sqlite_sequence SET seq=? WHERE name=?", (sequence, name))
                        else: conn.execute("INSERT INTO sqlite_sequence(name,seq) VALUES (?,?)", (name, sequence))
                    sequences_seen = True
                    table, sql = None, None
                elif value == {"end": True}:
                    if header["format"] == "movia-catalog-v2" and not sequences_seen:
                        raise ValueError("snapshot_sequences_missing")
                    finished = True
                    if compressed.read(1): raise ValueError("trailing_snapshot_data")
                    break
                else: raise ValueError("invalid_snapshot_record")
            if not finished: raise ValueError("incomplete_snapshot")
            conn.commit()
            if conn.execute("PRAGMA quick_check").fetchone()[0] != "ok": raise ValueError("invalid_catalog")
        os.chmod(temporary, 0o600)
        os.replace(temporary, target)
    finally:
        if temporary.exists(): temporary.unlink()
