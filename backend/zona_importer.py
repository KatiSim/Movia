#!/usr/bin/env python3
import time
from tmdb_client import tmdb
from database import save_or_update_movies_bulk, get_catalog_count

def run_zona_import():
    print("=== Наполнение эталонного каталога Zona ===")
    start_time = time.time()

    # 1. Загрузка зарубежных новинок и хитов (2024–2026)
    print("\n1. Импорт: Популярные зарубежные фильмы и сериалы...")
    foreign_items = []
    for page in range(1, 15):
        foreign_items.extend(tmdb.fetch_feed(media_type="movie", is_russian=False, page=page))
        foreign_items.extend(tmdb.fetch_feed(media_type="tv", is_russian=False, page=page))
        time.sleep(0.04)
    saved_foreign = save_or_update_movies_bulk(foreign_items)
    print(f"Зарубежных карточек сохранено: {saved_foreign}")

    # 2. Загрузка новинок российского кинопроката
    print("\n2. Импорт: Популярные российские фильмы и сериалы...")
    ru_items = []
    for page in range(1, 15):
        ru_items.extend(tmdb.fetch_feed(media_type="movie", is_russian=True, page=page))
        ru_items.extend(tmdb.fetch_feed(media_type="tv", is_russian=True, page=page))
        time.sleep(0.04)
    saved_ru = save_or_update_movies_bulk(ru_items)
    print(f"Российских карточек сохранено: {saved_ru}")

    # 3. Догрузка классики и хитов (1980–2023)
    print("\n3. Догрузка топ-релизов по годам (1980–2023)...")
    for y in range(2023, 1980, -2):
        year_items = []
        for p in range(1, 4):
            year_items.extend(tmdb.fetch_feed(media_type="movie", is_russian=False, year=y, page=p))
        if year_items:
            save_or_update_movies_bulk(year_items)

    elapsed = round(time.time() - start_time, 1)
    print("\n" + "=" * 60)
    print(f"Импорт завершён за {elapsed} сек.")
    print(f"Итого фильмов и сериалов в каталоге: {get_catalog_count()}")

if __name__ == "__main__":
    run_zona_import()
