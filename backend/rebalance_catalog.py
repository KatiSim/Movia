#!/usr/bin/env python3
import os
import sys
import sqlite3
from pathlib import Path

DIR = Path("/data/data/com.termux/files/home/projects/media-parser")
DATABASES = [DIR / "catalog.db", DIR / "media_catalog.db"]

COUNTRY_MAP = {
    # USA / North America
    "United States of America": "США",
    "USA": "США",
    "United States": "США",
    "Canada": "Канада",
    
    # Europe
    "United Kingdom": "Великобритания",
    "UK": "Великобритания",
    "France": "Франция",
    "Germany": "Германия",
    "West Germany": "Германия",
    "Italy": "Италия",
    "Spain": "Испания",
    "Sweden": "Швеция",
    "Denmark": "Дания",
    "Norway": "Норвегия",
    "Finland": "Финляндия",
    "Netherlands": "Нидерланды",
    "Belgium": "Бельгия",
    "Poland": "Польша",
    "Czech Republic": "Чехия",
    "Ireland": "Ирландия",
    "Austria": "Австрия",
    "Switzerland": "Швейцария",
    "Hungary": "Венгрия",
    "Greece": "Греция",
    "Portugal": "Португалия",
    "Iceland": "Исландия",
    
    # Russia & CIS
    "Russia": "Россия",
    "Russian Federation": "Россия",
    "Soviet Union": "СССР",
    "USSR": "СССР",
    "Belarus": "Беларусь",
    "Kazakhstan": "Казахстан",
    "Ukraine": "Украина",
    "Armenia": "Армения",
    "Georgia": "Грузия",
    "Azerbaijan": "Азербайджан",
    "Uzbekistan": "Узбекистан",
    
    # South Korea, Turkey, India, LatAm
    "South Korea": "Южная Корея",
    "Korea": "Южная Корея",
    "Turkey": "Турция",
    "India": "Индия",
    "Mexico": "Мексика",
    "Brazil": "Бразилия",
    "Argentina": "Аргентина",
    "Chile": "Чили",
    "Colombia": "Колумбия",
    "Peru": "Перу",
    "Australia": "Австралия",
    "New Zealand": "Новая Зеландия",
    
    # China, Japan, HK, Taiwan
    "Japan": "Япония",
    "China": "Китай",
    "Hong Kong": "Гонконг",
    "Taiwan": "Тайвань",
    "Thailand": "Таиланд",
    "Indonesia": "Индонезия",
    "Philippines": "Филиппины"
}

def rebalance_database(db_path: Path):
    if not db_path.exists():
        print(f"[SKIP] {db_path} does not exist")
        return

    print(f"\n==========================================")
    print(f"[*] Rebalancing database: {db_path}")
    print(f"==========================================")

    conn = sqlite3.connect(str(db_path))
    cur = conn.cursor()

    cur.execute("SELECT count(*) FROM movies;")
    initial_count = cur.fetchone()[0]
    print(f"[1] Initial movie count: {initial_count}")

    # 1. Normalize Country Names
    print("[2] Normalizing country names to Russian/canonical...")
    for eng_name, rus_name in COUNTRY_MAP.items():
        cur.execute("UPDATE movies SET country = ? WHERE country = ?;", (rus_name, eng_name))
    conn.commit()

    # 2. Delete entries with missing poster, missing synopsis, or rating < 5.0
    print("[3] Removing invalid/low-quality records (no poster, no synopsis, rating < 5.0)...")
    cur.execute("""
        DELETE FROM movies 
        WHERE poster_url IS NULL 
           OR poster_url = '' 
           OR synopsis IS NULL 
           OR LENGTH(synopsis) < 10 
           OR rating < 5.0 
           OR rating IS NULL;
    """)
    deleted_general = cur.rowcount
    print(f"    -> Deleted {deleted_general} general low-quality records.")

    # 3. Filter China / Japan / Hong Kong / Taiwan: Keep ONLY masterpieces with rating >= 7.0
    print("[4] Filtering China & Japan: removing entries with rating < 7.0 (keeping only masterpieces)...")
    cur.execute("""
        DELETE FROM movies 
        WHERE country IN ('Китай', 'Япония', 'Гонконг', 'Тайвань', 'China', 'Japan', 'Hong Kong', 'Taiwan') 
          AND (rating < 7.0 OR rating IS NULL);
    """)
    deleted_asian_slop = cur.rowcount
    print(f"    -> Deleted {deleted_asian_slop} low-rated China/Japan records.")

    conn.commit()

    # 4. Check Current Distribution
    cur.execute("SELECT count(*) FROM movies;")
    final_count = cur.fetchone()[0]
    print(f"\n[5] Final active movies count: {final_count} (Pruned {initial_count - final_count} total)")

    print("\n--- REGIONAL DISTRIBUTION ---")
    cur.execute("""
        SELECT 
            CASE 
                WHEN country IN ('США', 'Канада') THEN '1. США и Сев. Америка'
                WHEN country IN ('Великобритания', 'Франция', 'Германия', 'Италия', 'Испания', 'Швеция', 'Дания', 'Норвегия', 'Финляндия', 'Нидерланды', 'Бельгия', 'Польша', 'Чехия', 'Ирландия', 'Австрия', 'Швейцария', 'Венгрия', 'Греция', 'Португалия', 'Исландия') THEN '2. Европа'
                WHEN country IN ('Россия', 'СССР', 'Беларусь', 'Казахстан', 'Украина', 'Армения', 'Грузия') THEN '3. Россия и СНГ'
                WHEN country IN ('Южная Корея', 'Турция', 'Индия', 'Мексика', 'Бразилия', 'Аргентина', 'Чили', 'Колумбия', 'Австралия', 'Новая Зеландия') THEN '4. Корея, Турция, Индия, Латам'
                WHEN country IN ('Китай', 'Япония', 'Гонконг', 'Тайвань') THEN '5. Китай и Япония (только шедевры >= 7.0)'
                ELSE '6. Другие страны'
            END as region,
            count(*) as cnt,
            ROUND(count(*) * 100.0 / ?, 2) as pct
        FROM movies
        GROUP BY region
        ORDER BY region ASC;
    """, (final_count,))

    for row in cur.fetchall():
        print(f"  {row[0]:<45} | {row[1]:>6} тайтлов | {row[2]:>5.2f}%")

    print("\n[6] Optimizing SQLite database (VACUUM & ANALYZE)...")
    cur.execute("ANALYZE;")
    conn.commit()
    conn.close()

    # VACUUM in separate auto-commit connection
    v_conn = sqlite3.connect(str(db_path))
    v_conn.isolation_level = None
    v_conn.execute("VACUUM;")
    v_conn.close()
    print(f"[*] Done rebalancing {db_path.name}!")

if __name__ == "__main__":
    for db_file in DATABASES:
        rebalance_database(db_file)
