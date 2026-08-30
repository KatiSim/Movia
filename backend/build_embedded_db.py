#!/usr/bin/env python3
import sqlite3
import json
import os
import re
import sys
from pathlib import Path

SRC_DB = "/data/data/com.termux/files/home/projects/media-parser/media_catalog.db"
DEST_ASSET_DIR = "/data/data/com.termux/files/home/projects/movia/app/src/main/assets"
DEST_DB = os.path.join(DEST_ASSET_DIR, "catalog.db")

WESTERN_COUNTRIES = {
    "united states of america", "united states", "usa", "us",
    "united kingdom", "uk", "great britain", "england", "scotland",
    "france", "germany", "spain", "italy", "canada", "australia",
    "new zealand", "ireland", "sweden", "norway", "denmark", "finland",
    "netherlands", "belgium", "austria", "switzerland", "poland",
    "czech republic", "зарубежный", "сша", "великобритания", "франция",
    "германия", "испания", "италия", "канада", "австралия"
}

ASIAN_DRAMA_COUNTRIES = {
    "south korea", "korea", "china", "thailand", "taiwan",
    "hong kong", "philippines", "корея", "южная корея", "китай", "таиланд"
}

BLOCKBUSTER_PATTERNS = [
    r"человек-паук", r"spider-man", r"укрытие", r"\bsilo\b",
    r"очень странные дела", r"stranger things", r"мандалорец", r"mandalorian",
    r"гарри поттер", r"harry potter", r"аватар", r"avatar",
    r"бэтмен", r"batman", r"мстители", r"avengers",
    r"интерстеллар", r"interstellar", r"одни из нас", r"the last of us",
    r"игра престолов", r"game of thrones", r"во все тяжкие", r"breaking bad",
    r"властелин колец", r"lord of the rings", r"дюна", r"\bdune\b",
    r"оппенгеймер", r"oppenheimer", r"зв[её]здные войны", r"star wars",
    r"матрица", r"\bmatrix\b", r"джон уик", r"john wick",
    r"гладиатор", r"gladiator", r"бойцовский клуб", r"fight club",
    r"т[её]мный рыцарь", r"dark knight", r"криминальное чтиво", r"pulp fiction",
    r"начало", r"\binception\b", r"шрек", r"shrek",
    r"кр[её]стный отец", r"godfather", r"форрест гамп", r"forrest gump",
    r"побег из шоушенка", r"shawshank", r"аркейн", r"arcane",
    r"фоллаут", r"fallout", r"пацаны", r"the boys",
    r"острые козырьки", r"peaky blinders", r"рик и морти", r"rick and morty",
    r"киберпанк", r"cyberpunk", r"д[еэ]дпул", r"deadpool",
    r"стражи галактики", r"guardians of the galaxy", r"железный человек", r"iron man",
    r"дом дракона", r"house of the dragon", r"лучше звоните солу", r"better call saul",
    r"чернобыль", r"chernobyl", r"с[её]гун", r"shogun",
    r"разделение", r"severance", r"тед лассо", r"ted lasso",
    r"наследники", r"succession", r"настоящий детектив", r"true detective",
    r"фарго", r"fargo", r"клан сопрано", r"sopranos",
    r"ч[её]рное зеркало", r"black mirror", r"шерлок", r"sherlock",
    r"ведьмак", r"the witcher", r"ход королевы", r"the queen's gambit",
    r"игра в кальмара", r"squid game", r"бумажный дом", r"money heist",
    r"пингвин", r"\bthe penguin\b", r"форсаж", r"fast & furious", r"fast and furious",
    r"миссия невыполнима", r"mission:? impossible", r"парк юрского периода", r"jurassic",
    r"трансформеры", r"transformers", r"пираты карибского моря", r"pirates of the caribbean",
    r"чужой", r"\balien\b", r"хищник", r"predator",
    r"терминатор", r"terminator", r"бегущий по лезвию", r"blade runner",
    r"безумный макс", r"mad max", r"голодные игры", r"hunger games"
]

BLOCKBUSTER_REGEX = re.compile("|".join(BLOCKBUSTER_PATTERNS), re.IGNORECASE)

def has_cyrillic(text: str) -> bool:
    if not text:
        return False
    return bool(re.search(r'[а-яА-ЯёЁ]', str(text)))

def is_blockbuster(title: str, orig_title: str) -> bool:
    full = f"{title} {orig_title}"
    return bool(BLOCKBUSTER_REGEX.search(full))

def is_western_country(country: str, orig_title: str) -> bool:
    c = (country or "").strip().lower()
    if any(wc in c for wc in WESTERN_COUNTRIES):
        return True
    if orig_title and re.search(r'^[A-Za-z0-9\s:,\.\-\'!\?]+$', orig_title):
        # Латинское название без азиатских иероглифов
        return True
    return False

def is_asian_drama_content(country: str, orig_title: str, genres: str, category: str) -> bool:
    c = (country or "").strip().lower()
    g = (genres or "").strip().lower()
    if any(ac in c for ac in ASIAN_DRAMA_COUNTRIES):
        if "anime" not in category and "аниме" not in g:
            return True
    if "дорама" in g or "dorama" in g:
        return True
    # Проверка на восточноазиатские символы (CJK / Hangul)
    if orig_title and re.search(r'[\u1100-\u11FF\u3130-\u318F\uA960-\uA97F\uAC00-\uD7AF\u4E00-\u9FFF]', orig_title):
        if "anime" not in category:
            return True
    return False

def calculate_smart_popularity(
    title: str,
    orig_title: str,
    rating: float,
    country: str,
    genres: str,
    category: str,
    year: int,
    has_poster: bool
) -> tuple[int, float]:
    blockbuster = is_blockbuster(title, orig_title)
    western = is_western_country(country, orig_title)
    asian_drama = is_asian_drama_content(country, orig_title, genres, category)

    # Bayesian rating: сглаживание фейковых 10.0
    effective_rating = rating
    if rating >= 9.8 and not blockbuster and not (western and year < 2020):
        effective_rating = 7.0
    elif rating > 0:
        effective_rating = round((rating * 5.0 + 7.0 * 2.0) / 7.0, 1)

    if blockbuster:
        pop = 95 + min(5, int(effective_rating / 2.0))
    elif western:
        if effective_rating >= 7.8:
            pop = 88 + min(6, int((effective_rating - 7.8) * 3))
        elif effective_rating >= 6.5:
            pop = 78 + min(9, int((effective_rating - 6.5) * 8))
        elif effective_rating >= 5.0:
            pop = 68 + min(9, int((effective_rating - 5.0) * 6))
        else:
            pop = 50 + min(15, int(effective_rating * 3))
    elif asian_drama:
        if effective_rating >= 8.5:
            pop = 72 + min(3, int((effective_rating - 8.5) * 3))
        elif effective_rating >= 7.0:
            pop = 62 + min(9, int((effective_rating - 7.0) * 5))
        else:
            pop = 45 + min(15, int(effective_rating * 2.5))
    else:
        if category == "anime" and effective_rating >= 8.0:
            pop = 82 + min(8, int((effective_rating - 8.0) * 4))
        elif category == "animation" and effective_rating >= 7.5:
            pop = 82 + min(8, int((effective_rating - 7.5) * 4))
        elif effective_rating >= 7.5:
            pop = 75 + min(10, int((effective_rating - 7.5) * 5))
        else:
            pop = 50 + min(20, int(effective_rating * 3))

    # Штраф за отсутствие постера
    if not has_poster:
        pop = max(10, pop - 35)

    # Буст за свежесть (2025-2026) для западных тайтлов
    if year >= 2025 and western and pop < 95:
        pop = min(94, pop + 3)

    return pop, effective_rating

def build_embedded_catalog(src_path: str = SRC_DB, dest_path: str = DEST_DB):
    print("=" * 60)
    print("    СБОРКА ВСТРОЕННОЙ ОПТИМИЗИРОВАННОЙ БАЗЫ КАТАЛОГА (V200)")
    print("=" * 60)

    if not os.path.exists(src_path):
        print(f"❌ Исходная база не найдена: {src_path}")
        sys.exit(1)

    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    if os.path.exists(dest_path):
        try:
            os.remove(dest_path)
        except OSError:
            pass

    # 1. Читаем исходные записи
    src_conn = sqlite3.connect(src_path)
    src_conn.row_factory = sqlite3.Row
    src_cursor = src_conn.cursor()
    src_cursor.execute("PRAGMA wal_checkpoint(TRUNCATE);")

    rows = src_cursor.execute("""
        SELECT id, tmdb_id, title, original_title, year, rating, duration_minutes,
               synopsis, poster_url, backdrop_url, genres, [cast], director,
               country, category, streams, playback_url, voice, quality, seeders
        FROM movies
        ORDER BY id ASC;
    """).fetchall()

    print(f"📦 Прочитано записей из исходной базы: {len(rows)}")

    # 2. Создаем чистую целевую базу
    dest_conn = sqlite3.connect(dest_path)
    dest_cursor = dest_conn.cursor()

    dest_cursor.execute("PRAGMA page_size = 4096;")
    dest_cursor.execute("PRAGMA synchronous = OFF;")
    dest_cursor.execute("PRAGMA journal_mode = MEMORY;")

    dest_cursor.execute("""
        CREATE TABLE movies (
            id TEXT PRIMARY KEY,
            tmdb_id INTEGER,
            title TEXT NOT NULL,
            original_title TEXT,
            year INTEGER,
            rating REAL DEFAULT 0.0,
            duration_minutes INTEGER DEFAULT 0,
            synopsis TEXT,
            poster_url TEXT,
            backdrop_url TEXT,
            genres TEXT DEFAULT '[]',
            [cast] TEXT DEFAULT '[]',
            director TEXT DEFAULT '',
            country TEXT DEFAULT '',
            category TEXT DEFAULT 'movies',
            streams TEXT DEFAULT '[]',
            playback_url TEXT DEFAULT '',
            voice TEXT DEFAULT '',
            quality TEXT DEFAULT '',
            seeders INTEGER DEFAULT 0,
            popularity INTEGER DEFAULT 80,
            is_new INTEGER DEFAULT 0
        );
    """)

    dest_conn.commit()

    inserted_count = 0
    skipped_count = 0
    categories_stat = {}

    dest_conn.execute("BEGIN TRANSACTION;")

    current_year = 2026

    for r in rows:
        title = (r["title"] or "").strip()
        if not has_cyrillic(title):
            skipped_count += 1
            continue

        raw_id = r["id"]
        movie_id = f"m_{raw_id}"
        tmdb_id = r["tmdb_id"]
        original_title = (r["original_title"] or "").strip()
        year = int(r["year"] or 0)
        raw_rating = round(float(r["rating"] or 0.0), 1)
        duration_minutes = int(r["duration_minutes"] or 0)
        synopsis = (r["synopsis"] or "").strip()
        if len(synopsis) > 220:
            synopsis = synopsis[:217] + "..."

        poster_url = r["poster_url"] or ""
        if poster_url.startswith("https://image.tmdb.org/t/p/w500"):
            poster_url = poster_url[len("https://image.tmdb.org/t/p/w500"):]
        backdrop_url = r["backdrop_url"] or ""
        if backdrop_url.startswith("https://image.tmdb.org/t/p/original"):
            backdrop_url = backdrop_url[len("https://image.tmdb.org/t/p/original"):]

        director = (r["director"] or "").strip()
        country = (r["country"] or "").strip()

        # Нормализация категории
        cat_raw = (r["category"] or "movies").lower()
        if cat_raw in ["anime", "аниме"]:
            category = "anime"
        elif cat_raw in ["animation", "мультфильм", "анимация"]:
            category = "animation"
        elif cat_raw in ["tv_series", "series", "tv", "limited_series"]:
            category = "tv_series"
        else:
            category = "movies"

        categories_stat[category] = categories_stat.get(category, 0) + 1

        genres_raw = r["genres"] or "[]"
        try:
            g_list = json.loads(genres_raw)
            genres_str = ",".join(g_list) if isinstance(g_list, list) else ""
        except Exception:
            genres_str = ""

        cast_raw = r["cast"] or "[]"
        try:
            c_list = json.loads(cast_raw)
            compact_cast = []
            if isinstance(c_list, list):
                for actor in c_list[:6]:
                    if isinstance(actor, dict):
                        name = (actor.get("name") or actor.get("n") or "").strip()
                        if name:
                            role = (actor.get("role") or actor.get("character") or actor.get("r") or "").strip()
                            p_url = actor.get("photo_url") or actor.get("photoUrl") or actor.get("p") or ""
                            if p_url.startswith("https://image.tmdb.org/t/p/w342"):
                                p_url = p_url[len("https://image.tmdb.org/t/p/w342"):]
                            elif p_url.startswith("https://image.tmdb.org/t/p/w185"):
                                p_url = p_url[len("https://image.tmdb.org/t/p/w185"):]
                            elif p_url.startswith("https://image.tmdb.org/t/p/w500"):
                                p_url = p_url[len("https://image.tmdb.org/t/p/w500"):]
                            item = {"n": name}
                            if role: item["r"] = role
                            if p_url: item["p"] = p_url
                            compact_cast.append(item)
            cast_str = json.dumps(compact_cast, ensure_ascii=False)
        except Exception:
            cast_str = "[]"

        streams_raw = r["streams"] or "[]"
        playback_url = r["playback_url"] or ""
        voice = r["voice"] or ""
        quality = r["quality"] or ""
        seeders = int(r["seeders"] or 0)

        # Признаки популярности, новизны и умное квотирование
        is_new = 1 if year >= (current_year - 1) else 0
        has_poster = bool(poster_url.strip())

        popularity, rating = calculate_smart_popularity(
            title=title,
            orig_title=original_title,
            rating=raw_rating,
            country=country,
            genres=genres_str,
            category=category,
            year=year,
            has_poster=has_poster
        )

        dest_cursor.execute("""
            INSERT INTO movies (
                id, tmdb_id, title, original_title, year, rating, duration_minutes,
                synopsis, poster_url, backdrop_url, genres, [cast], director,
                country, category, streams, playback_url, voice, quality, seeders, popularity, is_new
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            movie_id, tmdb_id, title, original_title, year, rating, duration_minutes,
            synopsis, poster_url, backdrop_url, genres_str, cast_str, director,
            country, category, streams_raw, playback_url, voice, quality, seeders, popularity, is_new
        ))

        inserted_count += 1

    dest_conn.commit()

    print(f"✅ Вставлено валидных русскоязычных записей: {inserted_count}")
    print(f"⚠️ Пропущено записей без кириллицы: {skipped_count}")
    print("📊 Статистика по категориям:")
    for cat, cnt in categories_stat.items():
        print(f"   • {cat}: {cnt}")

    # Индексы
    print("⚡ Создание B-Tree индексов...")
    dest_cursor.execute("CREATE INDEX IF NOT EXISTS idx_movies_title ON movies(title);")
    dest_cursor.execute("CREATE INDEX IF NOT EXISTS idx_movies_category ON movies(category);")
    dest_cursor.execute("CREATE INDEX IF NOT EXISTS idx_movies_rating ON movies(rating DESC);")
    dest_cursor.execute("CREATE INDEX IF NOT EXISTS idx_movies_popularity ON movies(popularity DESC);")
    dest_cursor.execute("CREATE INDEX IF NOT EXISTS idx_movies_year ON movies(year DESC);")
    dest_cursor.execute("CREATE INDEX IF NOT EXISTS idx_movies_is_new ON movies(is_new DESC, popularity DESC);")
    dest_cursor.execute("CREATE INDEX IF NOT EXISTS idx_movies_cat_pop ON movies(category, popularity DESC);")
    dest_cursor.execute("CREATE INDEX IF NOT EXISTS idx_movies_cat_rating ON movies(category, rating DESC);")
    dest_cursor.execute("CREATE INDEX IF NOT EXISTS idx_movies_cat_year ON movies(category, year DESC);")
    dest_cursor.execute("CREATE INDEX IF NOT EXISTS idx_movies_director ON movies(director);")
    dest_conn.commit()

    # FTS4 полнотекстовый индекс (оптимизированный по размеру: только title и original_title)
    print("⚡ Создание компактного полнотекстового индекса FTS4 (movies_fts)...")
    dest_cursor.execute("DROP TABLE IF EXISTS movies_fts;")
    dest_cursor.execute("""
        CREATE VIRTUAL TABLE movies_fts USING fts4(
            movie_id,
            title,
            original_title,
            tokenize=unicode61
        );
    """)
    dest_cursor.execute("""
        INSERT INTO movies_fts(movie_id, title, original_title)
        SELECT id, title, original_title FROM movies;
    """)
    dest_conn.commit()

    print("🧹 Выполнение VACUUM оптимизации и дефрагментации...")
    dest_cursor.execute("VACUUM;")
    dest_cursor.execute("PRAGMA optimize;")
    dest_conn.commit()

    # Проверка целостности
    check = dest_cursor.execute("PRAGMA integrity_check;").fetchone()[0]
    print(f"🛡️ Проверка целостности SQLite: {check}")

    dest_conn.close()
    src_conn.close()

    db_size = os.path.getsize(dest_path) / (1024 * 1024)
    print(f"🎉 Готовая база сохранена в {dest_path} (Размер: {db_size:.2f} МБ)")

if __name__ == "__main__":
    build_embedded_catalog()
