#!/usr/bin/env python3
"""
Stream Extractor & Voice Classifier (Zona Model)
Extracts, recognizes, and aggregates torrent streams with dubbing/studio voice recognition and video quality levels.
"""

import re
import json
import sqlite3
import hashlib
from pathlib import Path
from typing import List, Dict, Any, Optional

DB_PATH = Path('/data/data/com.termux/files/home/projects/media-parser/media_catalog.db')

VOICE_PATTERNS = [
    (re.compile(r'(дубляж|лицензи|dub|полное дублирование|профессиональн|дублированный)', re.I), 'Дубляж'),
    (re.compile(r'(lostfilm|лостфильм)', re.I), 'LostFilm'),
    (re.compile(r'(hdrezka|rezka|резка)', re.I), 'HDRezka'),
    (re.compile(r'(red\s*head\s*sound|rhs|ред\s*хед)', re.I), 'Red Head Sound'),
    (re.compile(r'(кубик\s*в\s*кубе|kubik\s*v\s*kube)', re.I), 'Кубик в Кубе'),
    (re.compile(r'(кураж[- ]бамбей|kuraj[- ]bambey)', re.I), 'Кураж-Бамбей'),
    (re.compile(r'(tvshows|твшоуз)', re.I), 'TVShows'),
    (re.compile(r'(newstudio|ньюстудио)', re.I), 'NewStudio'),
    (re.compile(r'(alexfilm|алексфильм)', re.I), 'AlexFilm'),
    (re.compile(r'(пифагор|pifagor)', re.I), 'Пифагор'),
    (re.compile(r'(original|english|английский|eng)', re.I), 'Original'),
]

QUALITY_PATTERNS = [
    (re.compile(r'(2160p|4k|uhd)', re.I), '4K'),
    (re.compile(r'(1080p|fhd|full\s*hd)', re.I), '1080p'),
    (re.compile(r'(720p|hd)', re.I), '720p'),
    (re.compile(r'(480p|sd|dvd)', re.I), '480p'),
]

def classify_voice(text: str) -> str:
    for pattern, voice_name in VOICE_PATTERNS:
        if pattern.search(text):
            return voice_name
    return 'Дубляж'

def classify_quality(text: str) -> str:
    for pattern, quality_name in QUALITY_PATTERNS:
        if pattern.search(text):
            return quality_name
    return '1080p'

def generate_default_streams_for_title(title: str, year: int, category: str, rating: float) -> List[Dict[str, Any]]:
    # Synthetic stream generation is disabled: availability must come from a real provider.
    return []
    streams = []
    title_hash = hashlib.md5(f'{title}_{year}'.encode('utf-8')).hexdigest()[:16]
    is_series = category in ['tv_series', 'series', 'dramas_asian', 'anime']

    if is_series:
        voices = ['Дубляж', 'LostFilm', 'HDRezka', 'Кубик в Кубе', 'Кураж-Бамбей', 'Original']
    else:
        voices = ['Дубляж', 'Red Head Sound', 'HDRezka', 'Original']

    qualities = ['4K', '1080p', '720p', '480p'] if rating >= 7.5 else ['1080p', '720p', '480p']
    base_seeders = int(max(40, min(950, rating * 75 + (50 if year >= 2024 else 0))))

    for v_idx, voice in enumerate(voices):
        for q_idx, quality in enumerate(qualities):
            seeders = max(15, base_seeders - (v_idx * 45) - (q_idx * 30))
            magnet = f'magnet:?xt=urn:btih:{title_hash}{v_idx}{q_idx}&dn={title}+{year}+{voice}+{quality}'
            streams.append({
                'voice': voice,
                'quality': quality,
                'seeders': seeders,
                'url': magnet
            })

    return streams

def batch_populate_database():
    # Mass synthetic population is disabled to protect catalog integrity.
    print("Synthetic stream population is disabled; no database changes were made.")
    return
    if not DB_PATH.exists():
        print(f'Error: {DB_PATH} not found')
        return

    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()

    cols = [col[1] for col in cur.execute('PRAGMA table_info(movies);').fetchall()]
    if 'streams' not in cols:
        print('Adding streams column to movies table...')
        cur.execute("ALTER TABLE movies ADD COLUMN streams TEXT DEFAULT '[]';")
        conn.commit()

    rows = cur.execute('SELECT id, title, year, category, rating, streams FROM movies;').fetchall()
    total = len(rows)
    print(f'📦 Обработка {total} записей каталога для генерации пирамиды озвучек...')

    updated = 0
    batch = []

    for r in rows:
        m_id = r['id']
        title = r['title'] or ''
        year = int(r['year'] or 2024)
        category = r['category'] or 'movies'
        rating = float(r['rating'] or 7.0)

        streams = generate_default_streams_for_title(title, year, category, rating)
        streams_json = json.dumps(streams, ensure_ascii=False)
        batch.append((streams_json, m_id))

        if len(batch) >= 2000:
            cur.executemany('UPDATE movies SET streams = ? WHERE id = ?;', batch)
            conn.commit()
            updated += len(batch)
            print(f'  • Обработано: {updated}/{total} ({(updated/total)*100:.1f}%)')
            batch = []

    if batch:
        cur.executemany('UPDATE movies SET streams = ? WHERE id = ?;', batch)
        conn.commit()
        updated += len(batch)

    print(f'✅ Успешно обновлено {updated} записей в {DB_PATH}')
    conn.close()

if __name__ == '__main__':
    batch_populate_database()
