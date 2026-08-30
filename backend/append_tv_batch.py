#!/usr/bin/env python3
import time
from tmdb_client import tmdb
from database import save_or_update_movie, get_catalog_count

def append_popular_tv(start_page=1, end_page=2):
    print(f"Догрузка сериалов (страницы {start_page}–{end_page})...")
    added = 0
    for page in range(start_page, end_page + 1):
        series = tmdb.get_popular_tv(page=page)
        for s in series:
            meta = tmdb.get_tv_details(s.get("id"))
            if not meta or not meta.get("poster_url"):
                continue
            if save_or_update_movie(meta, []):
                added += 1
        print(f" -> Страница {page} сериалов обработана.")
        time.sleep(0.05)

    print(f"Успешно добавлено сериалов: {added}")
    print(f"Всего в каталоге: {get_catalog_count()}")

if __name__ == "__main__":
    append_popular_tv()
