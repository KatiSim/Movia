#!/usr/bin/env python3
from __future__ import annotations

import json
import random
import re
import secrets
import sqlite3
import statistics
import time
from pathlib import Path

import catalog_schema_v2
from catalog_schema_v2 import normalize_ru_text, prefix_successor
from search_service import search_page
import live_catalog_sync
from tmdb_client import tmdb

DB_PATH = Path(__file__).resolve().parent / "catalog.db"
CYRILLIC = re.compile(r"[А-Яа-яЁё]")


def open_db():
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    return conn


def sampled_rows(seed: int, size: int):
    conn = open_db()
    rows = [
        row for row in conn.execute(
            "SELECT id,title,normalized_ru_title,media_type,category,year,"
            "seeders,rating FROM movies WHERE title!='' "
            "AND normalized_ru_title!='' ORDER BY id"
        )
        if CYRILLIC.search(str(row["title"] or ""))
        and len(str(row["normalized_ru_title"] or "")) >= 2
    ]
    conn.close()
    rng = random.Random(seed)
    rng.shuffle(rows)
    return rows[: min(size, len(rows))]


def page_for(conn, query, limit=100, offset=0, **filters):
    return search_page(conn, query, limit=limit, offset=offset, **filters)


def benchmark(conn, queries):
    durations = []
    failures = 0
    for query in queries:
        started = time.perf_counter()
        try:
            page_for(conn, query, limit=20)
        except Exception:
            failures += 1
        durations.append((time.perf_counter() - started) * 1000)
    ordered = sorted(durations)
    quantile = lambda p: ordered[min(len(ordered) - 1, int(len(ordered) * p))]
    return {
        "requests": len(queries),
        "failures": failures,
        "p50_ms": round(statistics.median(durations), 3),
        "p95_ms": round(quantile(0.95), 3),
        "p99_ms": round(quantile(0.99), 3),
        "max_ms": round(max(durations), 3),
    }


def data_driven_search(conn):
    fixed = sampled_rows(20260829, 300)
    random_rows = sampled_rows(secrets.randbits(64), 100)
    full_found = 0
    full_total = 0
    prefix_checks = 0
    prefix_pass = 0
    monotone_pass = 0
    normalization_pass = 0
    rank_pass = 0
    category_buckets = {}
    prefix_lengths = (1, 2, 3)

    for row in fixed:
        target_id = int(row["id"])
        title = str(row["normalized_ru_title"])
        full_total += 1
        exact = page_for(conn, title, limit=100)
        if any(int(item["id"]) == target_id for item in exact.rows):
            full_found += 1
        lengths = sorted({
            length for length in (*prefix_lengths, max(1, len(title) // 2), len(title))
            if 1 <= length <= len(title)
        })
        totals = []
        for length in lengths:
            prefix = title[:length]
            page = page_for(conn, prefix, limit=100)
            if length in prefix_lengths or length == max(1, len(title) // 2):
                prefix_checks += 1
                if page.prefix_count > 0:
                    prefix_pass += 1
            totals.append(page.total)
        if all(left >= right for left, right in zip(totals, totals[1:])):
            monotone_pass += 1

        variants = (
            title.upper(),
            "  " + title.replace(" ", "  ") + "  ",
            title.replace("е", "ё"),
            title.replace("ё", "е"),
        )
        if all(
            any(int(item["id"]) == target_id
                for item in page_for(conn, variant, limit=100).rows)
            for variant in variants
        ):
            normalization_pass += 1

        if exact.rows and int(exact.rows[0]["search_score"]) >= 1000:
            rank_pass += 1
        bucket = (
            str(row["category"]),
            str(row["media_type"]),
            (int(row["year"] or 0) // 10) * 10,
            "low" if int(row["seeders"] or 0) < 100 else
            "mid" if int(row["seeders"] or 0) < 1000 else "high",
        )
        category_buckets.setdefault("|".join(map(str, bucket)), 0)
        category_buckets["|".join(map(str, bucket))] += 1

    queries = []
    for row in fixed[:250]:
        title = str(row["normalized_ru_title"])
        queries.append(title[:1])
    for row in fixed[50:300]:
        title = str(row["normalized_ru_title"])
        queries.append(title[:2] if len(title) >= 2 else title)
    for row in fixed[:200]:
        title = str(row["normalized_ru_title"])
        queries.append(title[:3] if len(title) >= 3 else title)
    for row in fixed[50:200]:
        title = str(row["normalized_ru_title"])
        queries.append(title[: max(1, len(title) // 2)])
    for row in fixed[:100]:
        queries.append(str(row["normalized_ru_title"]))
    for row in fixed[100:150]:
        queries.append(str(row["normalized_ru_title"])[:1])
    perf = benchmark(conn, queries[:1000])

    unseen = 0
    for row in random_rows:
        page = page_for(conn, str(row["normalized_ru_title"]), limit=100)
        if any(int(item["id"]) == int(row["id"]) for item in page.rows):
            unseen += 1

    return {
        "fixed_seed": 20260829,
        "fixed_sample_size": len(fixed),
        "unseeded_sample_size": len(random_rows),
        "full_title_found": full_found,
        "full_title_total": full_total,
        "full_title_rate": round(full_found / full_total, 6) if full_total else 0,
        "prefix_checks": prefix_checks,
        "prefix_pass": prefix_pass,
        "prefix_rate": round(prefix_pass / prefix_checks, 6) if prefix_checks else 0,
        "monotone_total_pass": monotone_pass,
        "monotone_rate": round(monotone_pass / len(fixed), 6) if fixed else 0,
        "normalization_pass": normalization_pass,
        "normalization_rate": round(normalization_pass / len(fixed), 6) if fixed else 0,
        "exact_rank_pass": rank_pass,
        "unseeded_full_retrieval": unseen,
        "unseeded_full_rate": round(unseen / len(random_rows), 6) if random_rows else 0,
        "strata_count": len(category_buckets),
        "performance": perf,
    }


def on_demand_acceptance(conn):
    candidates = []
    for media_type, endpoint in (
        ("movie", "/discover/movie"),
        ("tv", "/discover/tv"),
    ):
        for page in range(3, 16):
            data = tmdb._get(endpoint, {"page": page})
            for item in (data or {}).get("results", []) if isinstance(data, dict) else []:
                provider_id = int(item.get("id") or 0)
                title = item.get("title") if media_type == "movie" else item.get("name")
                if not provider_id or not title:
                    continue
                exists = conn.execute(
                    "SELECT id FROM movies WHERE media_type=? AND tmdb_id=?",
                    (media_type, provider_id),
                ).fetchone()
                if not exists:
                    candidates.append((media_type, provider_id, str(title)))
                    break
            if any(x[0] == media_type for x in candidates):
                break

    records = []
    for media_type, provider_id, title in candidates[:4]:
        before = conn.execute(
            "SELECT id FROM movies WHERE media_type=? AND tmdb_id=?",
            (media_type, provider_id),
        ).fetchone()
        result = live_catalog_sync.discover_query(title, limit=20)
        conn.commit()
        after = conn.execute(
            "SELECT id,title,normalized_ru_title FROM movies "
            "WHERE media_type=? AND tmdb_id=?",
            (media_type, provider_id),
        ).fetchone()
        local = page_for(conn, normalize_ru_text(title), limit=100)
        records.append({
            "media_type": media_type,
            "provider_id": provider_id,
            "before_missing": before is None,
            "discovery_error": result.get("error"),
            "inserted": result.get("inserted", 0),
            "persisted_after": after is not None,
            "local_hit_after": any(
                after is not None and int(item["id"]) == int(after["id"])
                for item in local.rows
            ),
        })
    return {
        "candidate_count": len(candidates),
        "records": records,
        "pass": bool(records) and all(
            r["before_missing"] and r["persisted_after"] and r["local_hit_after"]
            for r in records
        ),
    }


def continuous_acceptance(conn):
    before = int(conn.execute("SELECT COUNT(*) FROM movies").fetchone()[0])
    max_before = conn.execute(
        "SELECT MAX(updated_at) FROM movies"
    ).fetchone()[0]
    revision_before = catalog_schema_v2.get_revision(conn)
    cursors_before = [
        tuple(row) for row in conn.execute(
            "SELECT feed_key,next_page,last_total_pages FROM discovery_state "
            "ORDER BY feed_key"
        )
    ]
    result = live_catalog_sync.sync_once(pages=1)
    conn.commit()
    after = int(conn.execute("SELECT COUNT(*) FROM movies").fetchone()[0])
    max_after = conn.execute("SELECT MAX(updated_at) FROM movies").fetchone()[0]
    revision_after = catalog_schema_v2.get_revision(conn)
    cursors_after = [
        tuple(row) for row in conn.execute(
            "SELECT feed_key,next_page,last_total_pages FROM discovery_state "
            "ORDER BY feed_key"
        )
    ]
    return {
        "rows_before": before,
        "rows_after": after,
        "max_updated_before": max_before,
        "max_updated_after": max_after,
        "revision_before": revision_before,
        "revision_after": revision_after,
        "sync_status": {
            key: result.get(key)
            for key in ("last_run_ok", "last_seen", "last_inserted",
                        "last_updated", "last_error")
        },
        "cursor_rows_before": len(cursors_before),
        "cursor_rows_after": len(cursors_after),
        "cursor_changed_or_initialized": cursors_after != cursors_before,
        "pass": bool(result.get("last_run_ok")) and len(cursors_after) > 0,
    }


def main():
    catalog_schema_v2.ensure_schema(DB_PATH)
    conn = open_db()
    integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
    foreign = [tuple(row) for row in conn.execute("PRAGMA foreign_key_check")]
    count = int(conn.execute("SELECT COUNT(*) FROM movies").fetchone()[0])
    schema_columns = [
        row[1] for row in conn.execute("PRAGMA table_info(movies)")
    ]
    plan_rows = []
    for query in ("а", "ав", "ком", "одис"):
        normalized = normalize_ru_text(query)
        plan_rows.append({
            "query": query,
            "plan": [
                tuple(row) for row in conn.execute(
                    "EXPLAIN QUERY PLAN SELECT id FROM movies "
                    "WHERE normalized_ru_title>=? AND normalized_ru_title<?",
                    (normalized, prefix_successor(normalized)),
                )
            ],
        })
    search = data_driven_search(conn)
    on_demand = on_demand_acceptance(conn)
    continuous = continuous_acceptance(conn)
    trigram_count = int(conn.execute(
        "SELECT COUNT(*) FROM movies_search_trigram"
    ).fetchone()[0])
    conn.close()
    hardcoded = []
    production_paths = [
        Path("/data/data/com.termux/files/home/projects/movia/app/src/main/java/app/movia/android/data/catalog"),
        Path("/data/data/com.termux/files/home/projects/movia/app/src/main/java/app/movia/android/ui/search"),
        Path("/data/data/com.termux/files/home/projects/movia/app/src/main/java/app/movia/android/ui/catalog"),
        Path("/data/data/com.termux/files/home/projects/media-parser/catalog_api.py"),
        Path("/data/data/com.termux/files/home/projects/media-parser/search_service.py"),
        Path("/data/data/com.termux/files/home/projects/media-parser/live_catalog_sync.py"),
        Path("/data/data/com.termux/files/home/projects/media-parser/catalog_schema_v2.py"),
    ]
    for root in production_paths:
        candidates = root.rglob("*") if root.is_dir() else [root]
        for path in candidates:
            if path.suffix not in {".kt", ".py"}:
                continue
            text = path.read_text(encoding="utf-8", errors="ignore").lower()
            if any(token in text for token in (
                "breaking bad", "во все тяжкие", "spider-man",
                "человек-паук", "lostfilm", "кубик в кубе",
            )):
                hardcoded.append(str(path))
    report = {
        "database": {
            "rows": count,
            "integrity_check": integrity,
            "foreign_key_violations": foreign,
            "required_columns": {
                name: name in schema_columns
                for name in (
                    "normalized_ru_title",
                    "normalized_original_title",
                    "updated_at",
                )
            },
            "trigram_rows": trigram_count,
        },
        "query_plans": plan_rows,
        "search": search,
        "on_demand": on_demand,
        "continuous": continuous,
        "hardcoded_test_titles_in_production": hardcoded,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
