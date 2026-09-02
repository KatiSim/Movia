#!/usr/bin/env python3
from tmdb_client import tmdb
from search_engine import VideoSearchEngine
from database import save_or_update_movie, get_catalog_count

def sync_popular_from_tmdb(pages: int = 1):
    engine = VideoSearchEngine()
    print(f"=== Синхронизация каталога без дублей ({pages} стр. TMDb) ===")
    
    total_saved = 0
    for page in range(1, pages + 1):
        movies = tmdb.get_popular_movies(page=page)
        for i, m in enumerate(movies, 1):
            tmdb_id = m.get("id")
            meta = tmdb.get_movie_details(tmdb_id)
            if not meta:
                continue

            query_title = meta.get("original_title") or meta.get("title")
            year = meta.get("year")
            print(f"[{i}/{len(movies)}] {meta['title']} ({year})...", end=" ", flush=True)

            # Поиск всех доступных потоков
            streams = engine.search(query_title, year=year)

            # Сохраняем фильм с привязанными потоками
            if save_or_update_movie(meta, streams):
                total_saved += 1
            print(f"найдено источников: {len(streams)}")

    print("=" * 55)
    print(f"Синхронизация завершена. Уникальных фильмов в каталоге: {get_catalog_count()}")

if __name__ == "__main__":
    sync_popular_from_tmdb(pages=1)
