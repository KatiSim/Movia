#!/usr/bin/env python3
"""Fetch TMDb cast profile photos into media_catalog.db without changing catalog size."""
from __future__ import annotations

import argparse
import json
import shutil
import sqlite3
import time
from pathlib import Path
from typing import Any

from tmdb_client import tmdb

DEFAULT_DB = Path("/data/data/com.termux/files/home/projects/media-parser/media_catalog.db")
BACKUP_SUFFIX = ".cast-before-20260824-cast-photos"


def parse_people(raw: Any) -> list[dict[str, Any]]:
    if isinstance(raw, list):
        values = raw
    else:
        try:
            values = json.loads(raw) if raw else []
        except Exception:
            values = []
    if not isinstance(values, list):
        values = []
    result = []
    for item in values:
        if isinstance(item, dict):
            name = str(item.get("name") or item.get("nameRu") or item.get("nameEn") or "").strip()
            photo = item.get("photo_url") or item.get("photoUrl") or item.get("profile_url")
            role = item.get("role") or item.get("character")
        else:
            name = str(item).strip()
            photo = None
            role = None
        if name:
            result.append({"name": name, "photo_url": photo, "role": role})
    return result


def fetch_people(tmdb_id: int, category: str) -> list[dict[str, Any]]:
    normalized = (category or "").lower()
    media_kind = "tv" if any(token in normalized for token in ("series", "serial", "tv", "show")) else "movie"
    for attempt in range(3):
        data = tmdb._get(
            f"/{media_kind}/{tmdb_id}",
            {"append_to_response": "credits"},
        )
        credits = data.get("credits", {}) if data else {}
        result = []
        for person in credits.get("cast", [])[:12]:
            name = str(person.get("name") or "").strip()
            if not name:
                continue
            profile_path = person.get("profile_path")
            result.append({
                "name": name,
                "photo_url": f"https://image.tmdb.org/t/p/w342{profile_path}" if profile_path else None,
                "role": person.get("character") or None,
            })
        if result:
            return result
        time.sleep(0.4 * (attempt + 1))
    return []


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--delay", type=float, default=0.12)
    args = parser.parse_args()

    backup = Path(str(args.db) + BACKUP_SUFFIX)
    if not backup.exists():
        shutil.copy2(args.db, backup)
        print(f"[BACKUP] {backup}")

    connection = sqlite3.connect(args.db)
    connection.row_factory = sqlite3.Row
    rows = connection.execute(
        'SELECT id, tmdb_id, category, "cast" FROM movies WHERE tmdb_id IS NOT NULL ORDER BY id'
    ).fetchall()
    if args.limit > 0:
        rows = rows[:args.limit]

    updated = 0
    fetched = 0
    photo_entries = 0
    fallback_rows = 0

    for index, row in enumerate(rows, start=1):
        people = fetch_people(int(row["tmdb_id"]), row["category"] or "")
        if people:
            fetched += 1
        else:
            people = parse_people(row["cast"])
            fallback_rows += 1
        photo_entries += sum(1 for person in people if person.get("photo_url"))
        encoded = json.dumps(people, ensure_ascii=False, separators=(",", ":"))
        old = row["cast"] or ""
        if encoded != old:
            connection.execute('UPDATE movies SET "cast" = ? WHERE id = ?', (encoded, row["id"]))
            updated += 1
        if index % 20 == 0:
            connection.commit()
            print(f"[PROGRESS] {index}/{len(rows)} updated={updated} photos={photo_entries}")
        time.sleep(max(0.0, args.delay))

    connection.commit()
    connection.close()
    print(f"[OK] processed={len(rows)} updated={updated} tmdb_rows={fetched} fallback={fallback_rows} photo_entries={photo_entries}")


if __name__ == "__main__":
    main()
