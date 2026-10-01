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
            emit(compressed, {"format": "movia-catalog-v1"})
            tables = conn.execute("SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
            for name, sql in tables:
                if name not in ordinary: continue
                columns = [row[1] for row in conn.execute("PRAGMA table_info(" + identifier(name) + ")")]
                emit(compressed, {"table": name, "sql": sql, "columns": columns})
                for row in conn.execute("SELECT * FROM " + identifier(name)):
                    emit(compressed, {"row": [{"blob": base64.b64encode(v).decode()} if isinstance(v, bytes) else v for v in row]})
            emit(compressed, {"end": True})
    return len(ordinary)

def restore_database(source, target):
    temporary = target.with_name(target.name + ".restore-" + uuid.uuid4().hex)
    table, sql, finished, total = None, None, False, 0
    try:
        with closing(sqlite3.connect(temporary)) as conn, gzip.GzipFile(fileobj=source, mode="rb") as compressed:
            conn.execute("PRAGMA journal_mode=DELETE")
            if json.loads(compressed.readline(MAX_LINE + 1)) != {"format": "movia-catalog-v1"}:
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
                    columns = value["columns"]
                    if not isinstance(columns, list) or not 1 <= len(columns) <= 512:
                        raise ValueError("invalid_columns")
                    sql = "INSERT INTO " + identifier(table) + " (" + ",".join(identifier(c) for c in columns) + ") VALUES (" + ",".join("?" for _ in columns) + ")"
                elif "row" in value and table and sql:
                    row = value["row"]
                    if not isinstance(row, list) or len(row) != len(columns): raise ValueError("invalid_row")
                    row = [base64.b64decode(v["blob"], validate=True) if isinstance(v, dict) and set(v) == {"blob"} else v for v in row]
                    conn.execute(sql, row)
                elif value == {"end": True}:
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
