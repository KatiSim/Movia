#!/usr/bin/env python3
import sys
import time
from tmdb_client import tmdb
from database import save_or_update_movies_bulk, get_catalog_count

def run_scaling(target_total: int = 60000):
    print(f"=== Плавное масштабирование базы данных до {target_total} позиций ===")
    print("Диапазон: 1980–2026 гг. (Зарубежные + Российские, Фильмы + Сериалы)")
    start_time = time.time()

    # Идём от 2026 года назад до 1980
    for year in range(2026, 1979, -1):
        current_count = get_catalog_count()
        if current_count >= target_total:
            print(f"\nЦель в {target_total} материалов достигнута!")
            break

        print(f"\n[{year} год] Сбор материалов...", flush=True)

        # 1. Зарубежные фильмы (по 10 страниц = 200 фильмов)
        for page in range(1, 11):
            items = tmdb.fetch_year_slice(media_type="movie", year=year, page=page, is_russian=False)
            if items:
                save_or_update_movies_bulk(items)
            time.sleep(0.04)

        # 2. Российские фильмы (по 5 страниц)
        for page in range(1, 6):
            items = tmdb.fetch_year_slice(media_type="movie", year=year, page=page, is_russian=True)
            if items:
                save_or_update_movies_bulk(items)
            time.sleep(0.04)

        # 3. Сериалы (по 8 страниц = 160 сериалов)
        for page in range(1, 9):
            items = tmdb.fetch_year_slice(media_type="tv", year=year, page=page, is_russian=False)
            if items:
                save_or_update_movies_bulk(items)
            time.sleep(0.04)

        # 4. Российские сериалы (по 4 страницы)
        for page in range(1, 5):
            items = tmdb.fetch_year_slice(media_type="tv", year=year, page=page, is_russian=True)
            if items:
                save_or_update_movies_bulk(items)
            time.sleep(0.04)

        print(f" -> Год {year} обработан. Текущий объём базы: {get_catalog_count()} карточек.", flush=True)

    elapsed = round(time.time() - start_time, 1)
    print("=" * 60)
    print(f"Масштабирование завершено за {elapsed} сек. Итого в каталоге: {get_catalog_count()}")

if __name__ == "__main__":
    target = int(sys.argv[1]) if len(sys.argv) > 1 else 60000
    run_scaling(target)
