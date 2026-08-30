#!/usr/bin/env python3
"""Update the bundled Kotlin catalog cast from media_catalog.db.

This generator preserves the existing catalog entry structure and only replaces
cast expressions. It accepts both legacy string arrays and object arrays with
name/photo_url/role, so old imports remain compatible.
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
from pathlib import Path
from typing import Any


def kotlin_string(value: Any) -> str:
    if value is None:
        return "null"
    return json.dumps(str(value), ensure_ascii=False).replace("$", "\\$")


def parse_json_list(raw: Any) -> list[Any]:
    if isinstance(raw, list):
        return raw
    if raw is None:
        return []
    try:
        value = json.loads(str(raw))
        return value if isinstance(value, list) else []
    except Exception:
        return []


def people_from_raw(raw: Any, photo_by_name: dict[str, str]) -> list[dict[str, Any]]:
    parsed = parse_json_list(raw)
    if not parsed:
        parsed = [part.strip() for part in str(raw or "").split(",") if part.strip()]

    result: list[dict[str, Any]] = []
    for item in parsed:
        if isinstance(item, dict):
            name = str(item.get("name") or item.get("nameRu") or item.get("nameEn") or "").strip()
            photo = item.get("photo_url") or item.get("photoUrl") or item.get("profile_url")
            role = item.get("role") or item.get("character")
        else:
            name = str(item).strip()
            photo = photo_by_name.get(name)
            role = None
        if name:
            result.append({"name": name, "photo_url": photo, "role": role})
    return result


def person_expression(person: dict[str, Any]) -> str:
    fields = [f"name = {kotlin_string(person['name'])}"]
    if person.get("photo_url"):
        fields.append(f"photoUrl = {kotlin_string(person['photo_url'])}")
    if person.get("role"):
        fields.append(f"role = {kotlin_string(person['role'])}")
    return "Person(" + ", ".join(fields) + ")"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", default="/data/data/com.termux/files/home/projects/media-parser/media_catalog.db")
    parser.add_argument("--source", default="/data/data/com.termux/files/home/projects/viora/app/src/main/java/app/movia/android/data/catalog/CatalogRepository.kt")
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    source = Path(args.source)
    output = Path(args.output) if args.output else source
    connection = sqlite3.connect(args.db)
    rows = connection.execute('SELECT id, "cast" FROM movies ORDER BY id').fetchall()
    connection.close()
    by_id = {int(row[0]): row[1] for row in rows}
    photo_by_name: dict[str, str] = {}
    for raw in by_id.values():
        for person in people_from_raw(raw, {}):
            if person.get("name") and person.get("photo_url"):
                photo_by_name.setdefault(person["name"], str(person["photo_url"]))

    # The final catalog entry may close with ")" instead of ")," because it is
    # the last element of listOf(...). Both forms are valid Kotlin.
    block_pattern = re.compile(r"(?ms)^        MediaContent\(.*?^        \),?")
    cast_line_pattern = re.compile(r"(?m)^(\s*cast\s*=\s*).*,\s*$")
    replaced = 0

    def replace_block(match: re.Match[str]) -> str:
        nonlocal replaced
        block = match.group(0)
        id_match = re.search(r'\bid\s*=\s*"m_(\d+)"', block)
        if not id_match:
            return block
        row_id = int(id_match.group(1))
        if row_id not in by_id:
            raise RuntimeError(f"Catalog id m_{row_id} is absent from SQLite")
        cast_match = cast_line_pattern.search(block)
        if not cast_match:
            raise RuntimeError(f"Cast line is absent for catalog id m_{row_id}")
        people = people_from_raw(by_id[row_id], photo_by_name)
        expression = "emptyList()" if not people else "listOf(" + ", ".join(person_expression(p) for p in people) + ")"
        replacement = cast_match.group(1) + expression + ","
        replaced += 1
        return block[:cast_match.start()] + replacement + block[cast_match.end():]

    original = source.read_text(encoding="utf-8")
    generated = block_pattern.sub(replace_block, original)
    if replaced != len(by_id):
        raise RuntimeError(f"Expected {len(by_id)} catalog entries, replaced {replaced}")
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(generated, encoding="utf-8")
    temporary.replace(output)
    print(f"[OK] обновлён cast для {replaced} тайтлов: {output}")


if __name__ == "__main__":
    main()
