#!/usr/bin/env python3
"""Safe, idempotent Zona cache -> Movia catalog migration."""
from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
import subprocess
import sys
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

DIR = Path(__file__).resolve().parent
CATALOG_DB = DIR / "catalog.db"
CACHE_DB = DIR / "stream_cache" / "streams_cache.db"
STATE_FILE = DIR / "zona_cache_migrator.state.json"
REPORT_DIR = DIR / "reports"
BACKUP_DIR = DIR / "backups"
sys.path.insert(0, str(DIR))
from database import filter_streams_for_content
from stream_validation import sanitize_streams, stream_variant_key

CACHE_KEY_RE = re.compile(
    r"^(?P<title>.+)_(?P<year>\d{4})_(?P<category>[a-z_]+?)"
    r"(?:_s(?P<season>[^_]+)_e(?P<episode>[^_]+))?$",
    re.IGNORECASE,
)
WRITER_PATTERNS = (
    "content_filler.py",
    "content_updater.py",
    "update_streams.py",
    "zona_cache_migrator.py",
)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def normalize_title(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).casefold().replace("ё", "е")
    text = re.sub(r"[\u2010-\u2015−–—-]+", " ", text)
    text = re.sub(r"[^\w\s]+", " ", text, flags=re.UNICODE).replace("_", " ")
    return re.sub(r"\s+", " ", text).strip()


def parse_cache_key(cache_key: str) -> Optional[Dict[str, Any]]:
    match = CACHE_KEY_RE.match(str(cache_key or ""))
    if not match:
        return None
    raw = match.groupdict()
    parsed: Dict[str, Any] = {
        "title": raw["title"],
        "year": int(raw["year"]),
        "category": raw["category"].casefold(),
        "season": None,
        "episode": None,
    }
    for field in ("season", "episode"):
        value = raw.get(field)
        if value and value.casefold() != "none":
            try:
                parsed[field] = int(value)
            except ValueError:
                pass
    return parsed


def category_matches(cache_category: str, card: Dict[str, Any]) -> bool:
    wanted = str(cache_category or "").casefold()
    category = str(card.get("category") or "").casefold()
    media_type = str(card.get("media_type") or "").casefold()
    if wanted == "movies":
        return media_type in {"movie", "movies", "film"} or category == "movies"
    if wanted == "tv_series":
        return media_type in {"tv", "series", "tv_series"} or category in {"series", "tv_series"}
    if wanted == "animation":
        return category == "animation"
    if wanted == "documentaries":
        return category in {"documentaries", "documentary"}
    if wanted == "limited_series":
        return category in {"limited_series", "mini_series", "miniseries"}
    return category == wanted or media_type == wanted


def ro_connect(path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(f"file:{path.resolve()}?mode=ro", uri=True, timeout=30.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=30000")
    return conn


def integrity(path: Path) -> str:
    with ro_connect(path) as conn:
        return str(conn.execute("PRAGMA integrity_check").fetchone()[0])


def load_state() -> Dict[str, Any]:
    try:
        raw = json.loads(STATE_FILE.read_text(encoding="utf-8"))
        return raw if isinstance(raw, dict) else {}
    except Exception:
        return {}


def save_state(last_cache_key: str) -> None:
    payload = {"last_cache_key": last_cache_key, "updated_at": now_iso()}
    tmp = STATE_FILE.with_suffix(STATE_FILE.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, STATE_FILE)


def load_zona_entries(resume: bool = False, limit: Optional[int] = None) -> Tuple[List[Dict[str, Any]], int]:
    last_key = str(load_state().get("last_cache_key") or "") if resume else ""
    entries: List[Dict[str, Any]] = []
    total_cache_rows = 0
    with ro_connect(CACHE_DB) as conn:
        rows = conn.execute(
            "SELECT cache_key, streams_json, expires_at, updated_at FROM streams_cache ORDER BY cache_key"
        ).fetchall()
    for row in rows:
        total_cache_rows += 1
        cache_key = str(row["cache_key"])
        if last_key and cache_key <= last_key:
            continue
        try:
            streams = json.loads(row["streams_json"] or "[]")
        except Exception:
            continue
        if not isinstance(streams, list):
            continue
        zona_streams = [
            item for item in streams
            if isinstance(item, dict)
            and (
                "zona" in str(item.get("source") or "").casefold()
                or "zona" in str(item.get("provider") or "").casefold()
            )
        ]
        if not zona_streams:
            continue
        entries.append({
            "cache_key": cache_key,
            "parsed": parse_cache_key(cache_key),
            "streams": zona_streams,
        })
        if limit is not None and len(entries) >= limit:
            break
    return entries, total_cache_rows


def load_cards() -> Tuple[List[Dict[str, Any]], Dict[Tuple[str, int], List[Dict[str, Any]]]]:
    with ro_connect(CATALOG_DB) as conn:
        cards = [dict(row) for row in conn.execute(
            "SELECT id, tmdb_id, media_type, title, original_title, year, category, streams, "
            "playback_url, link_verified, voice, quality, seeders FROM movies"
        )]
    index: Dict[Tuple[str, int], List[Dict[str, Any]]] = defaultdict(list)
    for card in cards:
        year = int(card.get("year") or 0)
        names = {normalize_title(card.get("title")), normalize_title(card.get("original_title"))}
        for name in names:
            if name:
                index[(name, year)].append(card)
    return cards, index


def match_card(parsed: Dict[str, Any], index: Dict[Tuple[str, int], List[Dict[str, Any]]]) -> List[Dict[str, Any]]:
    candidates = index.get((normalize_title(parsed["title"]), int(parsed["year"])), [])
    return [card for card in candidates if category_matches(parsed["category"], card)]


def existing_streams(card: Dict[str, Any]) -> List[Dict[str, Any]]:
    try:
        raw = json.loads(card.get("streams") or "[]")
        return raw if isinstance(raw, list) else []
    except Exception:
        return []


def analyze(entries: List[Dict[str, Any]], index: Dict[Tuple[str, int], List[Dict[str, Any]]]):
    stats = Counter({
        "cache_entries_found": 0,
        "zona_streams_found": 0,
        "matched_entries": 0,
        "ambiguous_entries": 0,
        "unmatched_entries": 0,
        "valid_structural": 0,
        "rejected_by_identity": 0,
        "duplicate_streams": 0,
        "new_streams": 0,
        "cards_to_change": 0,
    })
    stats["cache_entries_found"] = len(entries)
    stats["zona_streams_found"] = sum(len(entry["streams"]) for entry in entries)
    ambiguous: List[Dict[str, Any]] = []
    unmatched: List[Dict[str, Any]] = []
    rejected: List[Dict[str, Any]] = []
    additions: Dict[int, List[Dict[str, Any]]] = defaultdict(list)
    seen_by_card: Dict[int, set] = {}
    changed: Dict[int, Dict[str, Any]] = {}

    for entry in entries:
        parsed = entry.get("parsed")
        if not parsed:
            stats["unmatched_entries"] += 1
            unmatched.append({"cache_key": entry["cache_key"], "reason": "unparseable_cache_key"})
            continue
        candidates = match_card(parsed, index)
        if len(candidates) > 1:
            stats["ambiguous_entries"] += 1
            ambiguous.append({
                "cache_key": entry["cache_key"],
                "candidates": [
                    {key: card[key] for key in ("id", "title", "original_title", "year", "category", "media_type")}
                    for card in candidates
                ],
            })
            continue
        if not candidates:
            stats["unmatched_entries"] += 1
            unmatched.append({"cache_key": entry["cache_key"], "parsed": parsed})
            continue

        card = candidates[0]
        card_id = int(card["id"])
        stats["matched_entries"] += 1
        clean = sanitize_streams(entry["streams"], require_source=True)
        stats["valid_structural"] += len(clean)
        filtered = filter_streams_for_content(clean, card)
        rejected_count = len(clean) - len(filtered)
        stats["rejected_by_identity"] += rejected_count
        if rejected_count:
            rejected.append({"cache_key": entry["cache_key"], "card_id": card_id, "rejected": rejected_count})

        if card_id not in seen_by_card:
            seen_by_card[card_id] = {
                stream_variant_key(item)
                for item in sanitize_streams(existing_streams(card), require_source=True)
            }
        for stream in filtered:
            key = stream_variant_key(stream)
            if key in seen_by_card[card_id]:
                stats["duplicate_streams"] += 1
                continue
            seen_by_card[card_id].add(key)
            additions[card_id].append(stream)
            stats["new_streams"] += 1
        if additions[card_id]:
            changed[card_id] = {
                "id": card_id,
                "title": card["title"],
                "year": card["year"],
                "category": card["category"],
                "new_streams": len(additions[card_id]),
            }

    stats["cards_to_change"] = len(changed)
    report = {
        "generated_at": now_iso(),
        "mode": "dry-run",
        "status": "ok",
        "stats": dict(stats),
        "changed_cards": sorted(changed.values(), key=lambda item: item["id"]),
        "ambiguous": ambiguous,
        "unmatched": unmatched,
        "rejected": rejected,
    }
    return report, {card_id: items for card_id, items in additions.items() if items}


def conflicting_writers() -> List[str]:
    try:
        output = subprocess.check_output(
            ["pgrep", "-af", "python"], text=True, stderr=subprocess.DEVNULL
        )
    except Exception:
        return []
    current_pid = os.getpid()
    conflicts: List[str] = []
    for line in output.splitlines():
        try:
            pid = int(line.split(None, 1)[0])
        except Exception:
            pid = -1
        if pid == current_pid:
            continue
        if any(pattern in line for pattern in WRITER_PATTERNS):
            conflicts.append(line.strip())
    return conflicts


def create_backup() -> Path:
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    backup = BACKUP_DIR / f"catalog.pre-zona-cache-{datetime.now().strftime('%Y%m%d-%H%M%S')}.db"
    source = sqlite3.connect(str(CATALOG_DB), timeout=30.0)
    target = sqlite3.connect(str(backup), timeout=30.0)
    try:
        source.execute("PRAGMA busy_timeout=30000")
        source.backup(target)
        target.commit()
    finally:
        target.close()
        source.close()
    return backup


def apply_additions(additions: Dict[int, List[Dict[str, Any]]]) -> Dict[str, Any]:
    conflicts = conflicting_writers()
    if conflicts:
        raise RuntimeError("conflicting writers detected: " + " | ".join(conflicts))
    if integrity(CATALOG_DB).casefold() != "ok":
        raise RuntimeError("catalog.db integrity_check failed before apply")

    backup = create_backup()
    conn = sqlite3.connect(str(CATALOG_DB), timeout=30.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=30000")
    changed_cards = 0
    inserted_streams = 0
    before_count = 0
    after_count = 0
    try:
        before_count = int(conn.execute("SELECT COUNT(*) FROM movies").fetchone()[0])
        conn.execute("BEGIN IMMEDIATE")
        for card_id in sorted(additions):
            row = conn.execute(
                "SELECT id, streams, playback_url, voice, quality, seeders FROM movies WHERE id = ?",
                (card_id,),
            ).fetchone()
            if row is None:
                continue
            try:
                old_streams = json.loads(row["streams"] or "[]")
            except Exception:
                old_streams = []
            if not isinstance(old_streams, list):
                old_streams = []

            seen = {
                stream_variant_key(item)
                for item in sanitize_streams(old_streams, require_source=True)
            }
            actual_new: List[Dict[str, Any]] = []
            for stream in additions[card_id]:
                key = stream_variant_key(stream)
                if key in seen:
                    continue
                seen.add(key)
                actual_new.append(stream)
            if not actual_new:
                continue

            first = actual_new[0]
            merged = old_streams + actual_new
            playback_url = str(row["playback_url"] or "").strip() or str(first["url"])
            voice = str(row["voice"] or "").strip() or str(first.get("voice") or "Не указано")
            quality = str(row["quality"] or "").strip() or str(first.get("quality") or "Не указано")
            seeders = int(row["seeders"] or 0) or int(first.get("seeders") or 0)
            conn.execute(
                "UPDATE movies SET streams = ?, playback_url = ?, link_verified = 1, "
                "voice = ?, quality = ?, seeders = ?, link_updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                (
                    json.dumps(merged, ensure_ascii=False),
                    playback_url,
                    voice,
                    quality,
                    seeders,
                    card_id,
                ),
            )
            changed_cards += 1
            inserted_streams += len(actual_new)

        after_count = int(conn.execute("SELECT COUNT(*) FROM movies").fetchone()[0])
        if before_count != after_count:
            raise RuntimeError(f"card count changed during migration: {before_count} -> {after_count}")
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    if integrity(CATALOG_DB).casefold() != "ok":
        raise RuntimeError("catalog.db integrity_check failed after apply")
    return {
        "backup": str(backup),
        "cards_changed": changed_cards,
        "streams_inserted": inserted_streams,
        "card_count_before": before_count,
        "card_count_after": after_count,
    }


def write_report(report: Dict[str, Any], requested: Optional[str]) -> Path:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    if requested:
        path = Path(requested)
        if not path.is_absolute():
            path = DIR / path
    else:
        path = REPORT_DIR / f"zona_cache_migration-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description="Zona cache -> Movia catalog migrator")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--apply", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--report")
    parser.add_argument(
        "--only-cache",
        action="store_true",
        help="Compatibility flag; this tool always processes cache only",
    )
    args = parser.parse_args()

    if args.limit is not None and args.limit <= 0:
        parser.error("--limit must be > 0")
    if not CATALOG_DB.exists() or not CACHE_DB.exists():
        print(json.dumps({"status": "error", "error": "required database missing"}))
        return 2

    entries, total_cache_rows = load_zona_entries(args.resume, args.limit)
    _cards, index = load_cards()
    report, additions = analyze(entries, index)
    report["stats"]["cache_rows_total"] = total_cache_rows
    report["catalog_integrity_before"] = integrity(CATALOG_DB)
    report["catalog_path"] = str(CATALOG_DB)
    report["cache_path"] = str(CACHE_DB)

    if args.apply:
        report["mode"] = "apply"
        try:
            report["apply"] = apply_additions(additions)
            report["status"] = "ok"
            if entries:
                save_state(entries[-1]["cache_key"])
        except Exception as exc:
            report["status"] = "error"
            report["error"] = str(exc)
            path = write_report(report, args.report)
            print(json.dumps({"status": "error", "error": str(exc), "report": str(path)}, ensure_ascii=False))
            return 3

    path = write_report(report, args.report)
    print(json.dumps({
        "status": report["status"],
        "mode": report["mode"],
        "stats": report["stats"],
        "apply": report.get("apply"),
        "report": str(path),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
