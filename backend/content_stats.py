#!/usr/bin/env python3
"""
Movia Catalog Statistics & Monitoring Reporter (content_stats.py)
Analyzes media_catalog.db to report link coverage, stream types, qualities, and voice distributions.
"""

import os
import sys
import sqlite3
from pathlib import Path

DIR = Path(__file__).resolve().parent
DB_PATH = DIR / "media_catalog.db"

def generate_stats():
    if not DB_PATH.exists():
        print(f"❌ База данных {DB_PATH} не найдена.")
        return

    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()

    # 1. Total counts
    cur.execute("SELECT COUNT(*) FROM movies;")
    total_titles = cur.fetchone()[0]

    cur.execute("SELECT COUNT(*) FROM movies WHERE playback_url IS NOT NULL AND playback_url != '' AND link_verified = 1;")
    with_playback = cur.fetchone()[0]
    without_playback = total_titles - with_playback

    pct_with = (with_playback / total_titles * 100) if total_titles > 0 else 0.0
    pct_without = (without_playback / total_titles * 100) if total_titles > 0 else 0.0

    # 2. Source distribution
    cur.execute("""
        SELECT
            SUM(CASE WHEN playback_url LIKE 'magnet:%' THEN 1 ELSE 0 END) AS magnet_count,
            SUM(CASE WHEN playback_url LIKE 'http%' THEN 1 ELSE 0 END) AS hls_count
        FROM movies
        WHERE playback_url IS NOT NULL AND playback_url != '' AND link_verified = 1;
    """)
    src_row = cur.fetchone()
    magnet_cnt = src_row['magnet_count'] or 0
    hls_cnt = src_row['hls_count'] or 0
    magnet_pct = (magnet_cnt / with_playback * 100) if with_playback > 0 else 0.0
    hls_pct = (hls_cnt / with_playback * 100) if with_playback > 0 else 0.0

    # 3. Quality distribution
    cur.execute("""
        SELECT quality, COUNT(*) as cnt
        FROM movies
        WHERE playback_url IS NOT NULL AND playback_url != '' AND link_verified = 1
        GROUP BY quality
        ORDER BY cnt DESC;
    """)
    quality_rows = cur.fetchall()

    # 4. Voice distribution
    cur.execute("""
        SELECT voice, COUNT(*) as cnt
        FROM movies
        WHERE playback_url IS NOT NULL AND playback_url != '' AND link_verified = 1
        GROUP BY voice
        ORDER BY cnt DESC;
    """)
    voice_rows = cur.fetchall()

    conn.close()

    # Formatted output
    print("📊 Статистика каталога Movia")
    print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    print(f"Всего тайтлов:         {total_titles:,}")
    print(f"С playback_url:        {with_playback:,} ({pct_with:.1f}%)")
    print(f"Без playback_url:      {without_playback:,} ({pct_without:.1f}%)")
    print("")
    print("По источникам:")
    print(f"  Magnet (P2P):        {magnet_cnt:,} ({magnet_pct:.1f}%)")
    print(f"  HLS/MP4 (CDN):       {hls_cnt:,} ({hls_pct:.1f}%)")
    print("")
    print("По качеству:")
    if quality_rows:
        for q_row in quality_rows:
            q_name = q_row['quality'] or 'Unknown'
            q_cnt = q_row['cnt']
            q_pct = (q_cnt / with_playback * 100) if with_playback > 0 else 0.0
            print(f"  {q_name:<10}           {q_cnt:,} ({q_pct:.1f}%)")
    else:
        print("  (нет данных)")

    print("")
    print("По озвучкам:")
    if voice_rows:
        for v_row in voice_rows[:6]:
            v_name = v_row['voice'] or 'Unknown'
            v_cnt = v_row['cnt']
            v_pct = (v_cnt / with_playback * 100) if with_playback > 0 else 0.0
            print(f"  {v_name:<18}   {v_cnt:,} ({v_pct:.1f}%)")
    else:
        print("  (нет данных)")
    print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

if __name__ == '__main__':
    generate_stats()
