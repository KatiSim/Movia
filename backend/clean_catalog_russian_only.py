import sqlite3
import re
import sys

DB_PATH = "/data/data/com.termux/files/home/projects/media-parser/media_catalog.db"

def has_cyrillic(text: str) -> bool:
    if not text:
        return False
    return bool(re.search(r'[а-яА-ЯёЁ]', str(text)))

print("=== [1/3] АНАЛИЗ БАЗЫ НА НАЛИЧИЕ НЕ-РУССКИХ ТАЙТЛОВ ===")
conn = sqlite3.connect(DB_PATH)
conn.row_factory = sqlite3.Row
cursor = conn.cursor()

cursor.execute("PRAGMA wal_checkpoint(TRUNCATE);")

rows = cursor.execute("SELECT id, tmdb_id, title, original_title, category, year FROM movies;").fetchall()
total_before = len(rows)
print(f"Всего записей в базе до очистки: {total_before}")

to_delete_ids = []
deleted_titles = []

for r in rows:
    title = r["title"]
    if not has_cyrillic(title):
        to_delete_ids.append(r["id"])
        deleted_titles.append(f"[{r['category'].upper()}] {title} ({r['year']}) / orig: {r['original_title']}")

print(f"\nНайдено записей без русского названия: {len(to_delete_ids)}")
if deleted_titles:
    print("Примеры удаляемых тайтлов:")
    for dt in deleted_titles[:15]:
        print(f"  ❌ Удаление: {dt}")
    if len(deleted_titles) > 15:
        print(f"  ... и ещё {len(deleted_titles) - 15} записей.")

print("\n=== [2/3] УДАЛЕНИЕ В ТРАНЗАКЦИИ ===")
if to_delete_ids:
    try:
        conn.execute("BEGIN TRANSACTION;")
        cursor.executemany("DELETE FROM movies WHERE id = ?;", [(i,) for i in to_delete_ids])
        conn.commit()
        print(f"✅ Успешно удалено {len(to_delete_ids)} нерусских записей.")
    except Exception as e:
        conn.rollback()
        print(f"❌ Ошибка отката: {e}")
        conn.close()
        sys.exit(1)
else:
    print("ℹ️ Нерусских записей не обнаружено.")

print("\n=== [3/3] ИТОГОВАЯ СТАТИСТИКА ЧИСТОЙ БАЗЫ ===")
total_after = cursor.execute("SELECT COUNT(*) FROM movies;").fetchone()[0]
movies_cnt = cursor.execute("SELECT COUNT(*) FROM movies WHERE category IN ('movies', 'movie');").fetchone()[0]
series_cnt = cursor.execute("SELECT COUNT(*) FROM movies WHERE category IN ('series', 'tv_series', 'limited_series');").fetchone()[0]
anime_cnt = cursor.execute("SELECT COUNT(*) FROM movies WHERE category IN ('anime', 'animation');").fetchone()[0]

conn.close()

print(f"Всего чистых русскоязычных тайтлов: {total_after}")
print(f"  • Фильмы: {movies_cnt}")
print(f"  • Сериалы: {series_cnt}")
print(f"  • Мультфильмы/Аниме: {anime_cnt}")
print("\n🎉 Очистка базы успешно завершена!")
