import threading
from flask import Flask, request, jsonify
from search_engine import VideoSearchEngine
from tmdb_client import tmdb
from streamer import stream_manager
from database import (
    get_catalog_items, 
    get_movie_by_id, 
    get_catalog_count
)

app = Flask(__name__)
engine = VideoSearchEngine()
is_syncing = False

def run_sync_task(pages: int = 1):
    global is_syncing
    is_syncing = True
    try:
        from catalog_sync import sync_popular_from_tmdb
        sync_popular_from_tmdb(pages=pages)
    finally:
        is_syncing = False

@app.route('/health', methods=['GET'])
def health():
    return jsonify({
        "status": "ok",
        "service": "media-parser-v3-clean",
        "total_unique_movies": get_catalog_count()
    })

@app.route('/catalog', methods=['GET'])
def get_catalog():
    limit = request.args.get('limit', default=50, type=int)
    offset = request.args.get('offset', default=0, type=int)
    items = get_catalog_items(limit=limit, offset=offset)
    return jsonify({
        "total": get_catalog_count(),
        "limit": limit,
        "offset": offset,
        "items": items
    })

@app.route('/playback/<int:content_id>', methods=['GET'])
@app.route('/content/<int:content_id>/playback', methods=['GET'])
def get_playback_info(content_id: int):
    movie = get_movie_by_id(content_id)
    if not movie:
        return jsonify({"error": "Фильм не найден"}), 404

    streams = movie.get("streams", [])
    if streams:
        primary_stream = streams[0]
        stream_info = stream_manager.resolve_stream_url(
            playback_url=(
                primary_stream.get("url")
                or primary_stream.get("playback_url")
                or ""
            ),
            title=movie["title"]
        )
    else:
        # Metadata/search pages are never playable media. Empty streams is NO_SOURCE.
        primary_stream = {}
        stream_info = {
            "direct_url": "",
            "stream_type": "none",
            "status": "no_source",
        }

    return jsonify({
        "id": movie["id"],
        "title": movie["title"],
        "year": movie["year"],
        "rating": movie["rating"],
        "poster_url": movie.get("poster_url"),
        "backdrop_url": movie.get("backdrop_url"),
        "synopsis": movie.get("synopsis"),
        "playback_url": stream_info.get("direct_url"),
        "stream_info": stream_info,
        "all_sources": streams,
        "is_direct_stream": stream_info.get("stream_type") == "direct_http"
    })

@app.route('/search', methods=['GET'])
def search_route():
    query = request.args.get('q', '').strip()
    year = request.args.get('year', type=int)
    if not query:
        return jsonify({"error": "Параметр 'q' обязателен"}), 400

    meta = tmdb.enrich_movie(query, year=year) or {
        "title": query, "original_title": query, "year": year, "poster_url": None, "synopsis": ""
    }
    results = engine.search(meta.get("original_title") or query, year=year)
    return jsonify({"meta": meta, "streams_count": len(results), "streams": results})

@app.route('/catalog/sync', methods=['POST'])
def sync_route():
    global is_syncing
    if is_syncing:
        return jsonify({"status": "busy"}), 409
    data = request.get_json(silent=True) or {}
    threading.Thread(target=run_sync_task, args=(data.get("pages", 1),), daemon=True).start()
    return jsonify({"status": "started"}), 202

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5001, debug=False)
