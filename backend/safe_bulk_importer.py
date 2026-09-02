#!/usr/bin/env python3
import sys
import time
import sqlite3
from tmdb_client import tmdb
from database import save_or_update_movies_bulk, get_catalog_count, DB_PATH

def cleanup_old_movies():
    """Удаляет из базы любые случайные записи старше 1980 года."""
    with sqlite3.connect(DB_PATH) as conn:
        cur = conn.execute("DELETE FROM movies WHERE year < 1980 AND year IS NOT NULL;")
        if cur.rowcount > 0:
            print(f"[Очистка] Удалено {cur.rowcount} записей старше 1980 года.")
        conn.commit()

def run_safe_import(target_records: int = 60000):
    cleanup_old_movies()
    print(f"=== Запуск импорта до {target_records} записей (Года: 1980–2026) ===")
    start_time = time.time()
    
    start_year = 2026
    min_year = 1980  # Жесткая граница: не старше 1980 года
    
    # 35 страниц фильмов (700 шт) + 25 страниц сериалов (500 шт) на каждый год
    pages_movies_per_year = 35
    pages_tv_per_year = 25

    for year in range(start_year, min_year - 1, -1):
        current_total = get_catalog_count()
        if current_total >= target_records:
            print(f"\nЦель в {target_records} записей достигнута!")
            break

        print(f"[{year}] Выгрузка...", end=" ", flush=True)
        batch = []

        # Фильмы за выбранный год
        for p in range(1, pages_movies_per_year + 1):
            items = tmdb.fetch_year_page(media_type="movie", year=year, page=p)
            if not items:
                break
            batch.extend(items)
            time.sleep(0.03)

        # Сериалы за выбранный год
        for p in range(1, pages_tv_per_year + 1):
            items = tmdb.fetch_year_page(media_type="tv", year=year, page=p)
            if not items:
                break
            batch.extend(items)
            time.sleep(0.03)

        # Пакетное сохранение в SQLite
        if batch:
            saved = save_or_update_movies_bulk(batch)
            print(f"сохранено: +{saved} | Всего в базе: {get_catalog_count()}")

    cleanup_old_movies()
    elapsed = round(time.time() - start_time, 1)
    print("=" * 60)
    print(f"Импорт завершён за {elapsed} сек.")
    print(f"Итоговое количество фильмов и сериалов (>= 1980): {get_catalog_count()}")

if __name__ == "__main__":
    count = int(sys.argv[1]) if len(sys.argv) > 1 else 60000
    run_safe_import(count)
