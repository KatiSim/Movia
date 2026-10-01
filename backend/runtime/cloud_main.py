"""One Movia service: read API plus bounded, resumable server-side workers."""
import os
os.environ["MOVIA_CLOUD_MODE"] = "1"
os.environ["MOVIA_P2P_ENABLED"] = "0"
os.environ["MOVIA_BACKGROUND_BULK"] = "0"
os.environ.setdefault("MOVIA_ENRICH_WORKERS", "2")
import json
import signal
import sqlite3
import subprocess
import sys
import threading
import time
from pathlib import Path
from contextlib import closing
from movia_paths import DATA_DIR

def cached_streams(movie_id, season, episode):
    import catalog_api, streamer
    card = catalog_api.get_movie_playback_card(movie_id)
    if not card:
        return 404, {"code": "NOT_FOUND"}
    from database import filter_streams_for_content
    context = dict(card, season=season, episode=episode)
    # A series-level URL never substitutes for the requested episode.
    rows = filter_streams_for_content(card.get("streams", []), context)
    rows = streamer.cloud_exposable_streams(rows)
    return 200, {"streams": rows, "status": "READY" if rows else "DISCOVERY_PENDING",
                 "mediaId": movie_id, "season": season, "episode": episode}

def worker(stop):
    import live_catalog_sync
    next_metadata = 0
    while not stop.is_set():
        start = time.monotonic()
        for task in ("catalog", "new-metadata", "streams", "metadata", "checkpoint"):
            if stop.is_set(): return
            try:
                if task == "catalog":
                    live_catalog_sync.sync_once(pages=1)
                elif task == "new-metadata":
                    with closing(sqlite3.connect(DATA_DIR / "catalog.db")) as conn:
                        ids=[str(row[0]) for row in conn.execute("SELECT id FROM movies WHERE year>=? AND (COALESCE(metadata_source,'')!='tmdb_detail' OR COALESCE(duration_minutes,0)=0) ORDER BY id DESC LIMIT 10", (time.gmtime().tm_year-1,))]
                    if ids:
                        subprocess.run([sys.executable,str(Path(__file__).with_name("metadata_repair.py")),"--ids",",".join(ids),"--workers","2"], stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=180,check=True)
                elif task == "streams":
                    # Runs as a separate, bounded process so all parser memory returns after each pass.
                    subprocess.run([sys.executable, str(Path(__file__).with_name("content_filler.py")),
                        "--limit", "30", "--resume"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                        timeout=240, check=True)
                elif task == "metadata" and time.monotonic() >= next_metadata:
                    subprocess.run([sys.executable, str(Path(__file__).with_name("metadata_repair.py")),
                        "--limit", "20", "--workers", "2", "--resume"], stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL, timeout=240, check=True)
                    next_metadata = time.monotonic() + 1800
                elif task == "checkpoint" and os.environ.get("MOVIA_SNAPSHOT_DSN"):
                    from snapshot_store import checkpoint
                    checkpoint(DATA_DIR / "catalog.db")
            except Exception as error:
                print(json.dumps({"task": task, "status": "RETRY", "error": type(error).__name__}), flush=True)
        stop.wait(max(5, 300 - (time.monotonic() - start)))

def main():
    database = DATA_DIR / "catalog.db"
    if not database.is_file() and os.environ.get("MOVIA_SNAPSHOT_DSN"):
        from snapshot_store import restore
        restore(database)
    if not database.is_file():
        raise RuntimeError("Initial Movia catalog must be seeded before enabling this deployment")
    # Verify the initial snapshot before exposing the API.
    with closing(sqlite3.connect(database)) as connection:
        if connection.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise RuntimeError("Catalog integrity check failed")
    import database, catalog_api, streamer
    from cloud_api import ReadService, handler
    stop = threading.Event()
    server = streamer.ThreadedHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))),
        handler(ReadService(catalog_api, cached_streams)))
    if os.environ.get("MOVIA_WORKERS_ENABLED", "1") == "1":
        threading.Thread(target=worker, args=(stop,), daemon=True, name="Movia-cloud-worker").start()
    def terminate(*_):
        stop.set()
        threading.Thread(target=server.shutdown, daemon=True).start()
    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    print('{"service":"movia-api","status":"READY","phoneParser":false}', flush=True)
    try: server.serve_forever()
    finally: stop.set();server.server_close()

if __name__ == "__main__":
    main()
