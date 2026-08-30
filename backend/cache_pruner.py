#!/usr/bin/env python3
"""Bounded, fail-closed cleanup for Movia's disposable torrent cache."""
from __future__ import annotations

import json
import os
import shutil
import time
import urllib.request
from pathlib import Path
from typing import Any

BASE_DIR = Path(__file__).resolve().parent
CACHE_DIR = (BASE_DIR / "torrent_cache").resolve()
ARIA2_RPC_URL = "http://127.0.0.1:6800/jsonrpc"
ARIA2_RPC_TOKEN = "token:movia_secret"
MAX_CACHE_BYTES = 64 * 1024 * 1024 * 1024
MAX_ENTRY_AGE_SECONDS = 48 * 60 * 60
RPC_TIMEOUT_SECONDS = 2.5


def _within(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _top_level(path: Path) -> Path | None:
    try:
        rel = path.relative_to(CACHE_DIR)
    except ValueError:
        return None
    if not rel.parts:
        return CACHE_DIR
    return CACHE_DIR / rel.parts[0]


def _rpc(method: str, params: list[Any]) -> Any:
    payload = json.dumps({
        "jsonrpc": "2.0",
        "id": "movia-cache-pruner",
        "method": method,
        "params": [ARIA2_RPC_TOKEN, *params],
    }).encode("utf-8")
    request = urllib.request.Request(
        ARIA2_RPC_URL,
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=RPC_TIMEOUT_SECONDS) as response:
        data = json.loads(response.read().decode("utf-8"))
    if data.get("error"):
        raise RuntimeError(str(data["error"]))
    return data.get("result")


def _aria2_protected_entries() -> set[Path]:
    keys = ["gid", "status", "dir", "files", "infoHash"]
    protected: set[Path] = set()
    for method, params in (
        ("aria2.tellActive", [keys]),
        ("aria2.tellWaiting", [0, 1000, keys]),
    ):
        tasks = _rpc(method, params)
        if not isinstance(tasks, list):
            raise RuntimeError(f"{method} returned non-list")
        for task in tasks:
            if not isinstance(task, dict):
                continue
            candidates = [task.get("dir")]
            candidates.extend(
                item.get("path")
                for item in (task.get("files") or [])
                if isinstance(item, dict)
            )
            for raw_path in candidates:
                if not raw_path:
                    continue
                candidate = Path(os.path.realpath(str(raw_path)))
                if _within(candidate, CACHE_DIR):
                    top = _top_level(candidate)
                    if top is not None:
                        protected.add(top)
    return protected


def _open_file_protected_entries() -> set[Path]:
    protected: set[Path] = set()
    proc_dir = Path("/proc")
    try:
        proc_entries = list(proc_dir.iterdir())
    except OSError as exc:
        raise RuntimeError(f"cannot inspect /proc: {exc}") from exc

    for proc_entry in proc_entries:
        if not proc_entry.name.isdigit():
            continue
        fd_dir = proc_entry / "fd"
        try:
            fd_entries = list(fd_dir.iterdir())
        except OSError:
            continue
        for fd_entry in fd_entries:
            try:
                target = Path(os.path.realpath(os.readlink(fd_entry)))
            except (OSError, ValueError):
                continue
            if _within(target, CACHE_DIR):
                top = _top_level(target)
                if top is not None:
                    protected.add(top)
    return protected


def _allocated_size(path: Path) -> int:
    total = 0
    try:
        if path.is_symlink():
            return 0
        if path.is_file():
            stat = path.stat()
            return max(int(getattr(stat, "st_blocks", 0) or 0) * 512, stat.st_size)
        for root, dirs, files in os.walk(path, followlinks=False):
            root_path = Path(root)
            for name in files:
                file_path = root_path / name
                try:
                    if file_path.is_symlink():
                        continue
                    stat = file_path.stat()
                    total += max(
                        int(getattr(stat, "st_blocks", 0) or 0) * 512,
                        stat.st_size,
                    )
                except OSError:
                    continue
            for name in dirs:
                dir_path = root_path / name
                try:
                    if dir_path.is_symlink():
                        continue
                    stat = dir_path.stat()
                    total += int(getattr(stat, "st_blocks", 0) or 0) * 512
                except OSError:
                    continue
    except OSError:
        return 0
    return total


def _scan_entries() -> list[tuple[Path, int, float]]:
    if not CACHE_DIR.exists():
        return []
    result: list[tuple[Path, int, float]] = []
    for entry in CACHE_DIR.iterdir():
        try:
            stat = entry.stat()
            result.append((entry, _allocated_size(entry), stat.st_mtime))
        except OSError:
            continue
    return result


def _remove_entry(entry: Path) -> None:
    if entry.parent != CACHE_DIR:
        raise RuntimeError(f"refusing non-cache entry: {entry}")
    resolved = Path(os.path.realpath(str(entry)))
    if not _within(resolved, CACHE_DIR) or resolved == CACHE_DIR:
        raise RuntimeError(f"refusing unsafe cache path: {entry}")
    if entry.is_dir() and not entry.is_symlink():
        shutil.rmtree(entry)
    elif entry.exists() or entry.is_symlink():
        entry.unlink()


def main() -> int:
    if CACHE_DIR != Path(os.path.realpath(str(BASE_DIR / "torrent_cache"))):
        raise RuntimeError("cache path resolution mismatch")
    CACHE_DIR.mkdir(parents=True, exist_ok=True)

    try:
        aria2_protected = _aria2_protected_entries()
        open_protected = _open_file_protected_entries()
    except Exception as exc:
        print(json.dumps({
            "cache": str(CACHE_DIR),
            "action": "skip",
            "reason": "safety_probe_failed",
            "detail": str(exc),
        }, ensure_ascii=False), flush=True)
        return 0

    protected = aria2_protected | open_protected
    entries = _scan_entries()
    before_bytes = sum(size for _, size, _ in entries)
    now = time.time()
    removed: list[dict[str, Any]] = []
    current_bytes = before_bytes

    # Age is the primary lifecycle policy. If the cache exceeds its budget,
    # remove the oldest eligible entries first. Fresh or protected playback
    # directories are retained for later passes.
    eligible = sorted(
        (
            (entry, size, mtime)
            for entry, size, mtime in entries
            if entry not in protected
            and now - mtime >= MAX_ENTRY_AGE_SECONDS
        ),
        key=lambda item: (item[2], str(item[0])),
    )
    for entry, size, mtime in eligible:
        if current_bytes <= MAX_CACHE_BYTES:
            break
        if entry in protected or not entry.exists():
            continue
        try:
            _remove_entry(entry)
            removed.append({
                "path": entry.name,
                "bytes": size,
                "age_hours": round(max(0.0, now - mtime) / 3600.0, 1),
            })
            current_bytes = max(0, current_bytes - size)
        except OSError as exc:
            print(json.dumps({
                "cache": str(CACHE_DIR),
                "action": "skip_entry",
                "entry": entry.name,
                "detail": str(exc),
            }, ensure_ascii=False), flush=True)

    after_entries = _scan_entries()
    after_bytes = sum(size for _, size, _ in after_entries)
    print(json.dumps({
        "cache": str(CACHE_DIR),
        "action": "prune",
        "before_bytes": before_bytes,
        "after_bytes": after_bytes,
        "removed_bytes": before_bytes - after_bytes,
        "removed_entries": removed,
        "protected_entries": len(protected),
        "max_cache_bytes": MAX_CACHE_BYTES,
        "max_entry_age_hours": MAX_ENTRY_AGE_SECONDS / 3600,
    }, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
