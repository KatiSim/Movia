#!/usr/bin/env python3
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from tmdb_client import tmdb
from database import save_or_update_movies_bulk, get_catalog_count

def fetch_and_save_chunk(media_type: str, start_page: int, end_page: int) -> int:
    all_items = []
    for p in range(start_page, end_page + 1):
        items = tmdb.fetch_discover_page(media_type=media_type, page=p)
        all_items.extend(items)
    
    if all_items:
        return save_or_update_movies_bulk(all_items)
    return 0

def run_bulk_import(target_total: int = 2000):
    """
    Масштабирует базу до заданного числа:
    2 000 позиций  = ~50 страниц фильмов + 50 страниц сериалов
    10 000 позиций = ~250 страниц фильмов + 250 страниц сериалов
    60 000 позиций = ~1500 страниц фильмов + 1500 страниц сериалов
    """
    print(f"=== Запуск массового импорта ({target_total} фильмов и сериалов) ===")
    start_time = time.time()

    # Каждая страница TMDb = 20 карточек. Делим пополам между фильмами и сериалами
    items_per_category = target_total // 2
    total_pages_per_cat = max(1, items_per_category // 20)

    # За один чанк берем по 10 страниц (200 записей)
    chunk_size = 10
    tasks = []

    with ThreadPoolExecutor(max_workers=8) as executor:
        for m_type in ["movie", "tv"]:
            for start_p in range(1, total_pages_per_cat + 1, chunk_size):
                end_p = min(start_p + chunk_size - 1, total_pages_per_cat)
                tasks.append(executor.submit(fetch_and_save_chunk, m_type, start_p, end_p))

        completed = 0
        total_saved = 0
        for future in as_completed(tasks):
            saved = future.result()
            total_saved += saved
            completed += 1
            if completed % 5 == 0 or completed == len(tasks):
                print(f"Прогресс: обработано чанков {completed}/{len(tasks)} | Сохранено записей: {total_saved}")

    elapsed = round(time.time() - start_time, 2)
    print("=" * 60)
    print(f"Импорт завершён за {elapsed} сек.")
    print(f"Всего карточек в базе данных: {get_catalog_count()}")

if __name__ == "__main__":
    count = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
    run_bulk_import(count)
