#!/usr/bin/env python3
import time
from tmdb_client import tmdb
from database import save_or_update_movie, get_catalog_count

def append_popular_movies(start_page=5, end_page=9):
    initial_count = get_catalog_count()
    print(f"Текущее количество фильмов в базе: {initial_count}")
    print(f"Догрузка страниц {start_page}–{end_page} из TMDb...")

    added = 0
    for page in range(start_page, end_page + 1):
        movies = tmdb.get_popular_movies(page=page)
        for m in movies:
            meta = tmdb.get_movie_details(m.get("id"))
            if not meta or not meta.get("poster_url"):
                continue

            # Добавляем фильм в базу через проверенную функцию
            if save_or_update_movie(meta, []):
                added += 1
        print(f" -> Страница {page} обработана.")
        time.sleep(0.05)

    print(f"Успешно добавлено новых фильмов: {added}")
    print(f"Итого в каталоге: {get_catalog_count()}")

if __name__ == "__main__":
    append_popular_movies()
