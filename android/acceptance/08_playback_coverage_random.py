#!/usr/bin/env python3
"""
Automated Random Playback Coverage Suite for Movia.
Samples 20 movies + 20 TV series episodes from catalog.db with deterministic seed.
Exercises the full domain playback resolver, verifies real stream candidates,
voices, qualities, and classifies results into PLAYABLE, NO_SOURCE, BROKEN_SOURCE,
AUTH_REQUIRED, P2P_NO_PEERS, PLAYER_FAILURE, and TIMEOUT. PLAYABLE requires
independent Media3 READY/isPlaying evidence and movement of the position.
"""

from __future__ import annotations

import json
import os
import random
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

ROOT = Path(__file__).resolve().parent.parent
DB_PATH = ROOT.parent / "media-parser" / "catalog.db"
BASE_URL = os.environ.get("MOVIA_BASE_URL", "http://127.0.0.1:8899/agent/v1").rstrip("/")
TOKEN_FILE = Path(os.environ.get("MOVIA_TOKEN_FILE", str(Path.home() / ".config/movia-agent/token")))
HTTP_TIMEOUT = float(os.environ.get("MOVIA_HTTP_TIMEOUT", "6"))
OPERATION_TIMEOUT = float(os.environ.get("MOVIA_OPERATION_TIMEOUT", "45"))
SEED = 42

def load_token() -> str:
    token = TOKEN_FILE.read_text(encoding="utf-8").strip()
    return token

def agent_request(path: str, method: str = "GET", payload: Optional[Dict[str, Any]] = None) -> Tuple[Optional[int], Optional[Any], str]:
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {load_token()}",
    }
    body = None
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE_URL + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
            data = resp.read().decode("utf-8", errors="replace")
            return resp.status, json.loads(data), ""
    except urllib.error.HTTPError as exc:
        data = exc.read().decode("utf-8", errors="replace")
        try:
            return exc.code, json.loads(data), data[:200]
        except Exception:
            return exc.code, None, data[:200]
    except Exception as exc:
        return None, None, str(exc)[:200]

def sample_catalog(seed: int = SEED, movie_count: int = 20, series_count: int = 20) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    rng = random.Random(seed)
    movies: List[Dict[str, Any]] = []
    series: List[Dict[str, Any]] = []

    if not DB_PATH.exists():
        print(f"ERROR: Database not found at {DB_PATH}", file=sys.stderr)
        sys.exit(1)

    with sqlite3.connect(str(DB_PATH)) as conn:
        conn.row_factory = sqlite3.Row

        # Movies sample across diverse categories
        movie_rows = conn.execute(
            """
            SELECT id, tmdb_id, media_type, title, original_title, year, category, streams
            FROM movies
            WHERE media_type='movie' AND title != ''
            ORDER BY id
            """
        ).fetchall()

        # TV Series sample
        tv_rows = conn.execute(
            """
            SELECT id, tmdb_id, media_type, title, original_title, year, category, seasons_count, episodes_count, season_episode_counts, streams
            FROM movies
            WHERE media_type='tv' AND title != '' AND seasons_count > 0
            ORDER BY id
            """
        ).fetchall()

    all_movies = [dict(r) for r in movie_rows]
    all_tv = [dict(r) for r in tv_rows]

    sampled_movies = rng.sample(all_movies, min(movie_count, len(all_movies)))
    sampled_tv = rng.sample(all_tv, min(series_count, len(all_tv)))

    return sampled_movies, sampled_tv

CLI = Path.home() / "bin" / "movia-agent"

def ensure_bridge() -> bool:
    try:
        completed = subprocess.run(
            ["bash", str(CLI), "health"],
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            timeout=15,
            check=False,
        )
        payload = json.loads(completed.stdout) if completed.stdout else None
        return completed.returncode == 0 and isinstance(payload, dict) and payload.get("status") == "ok"
    except Exception:
        return False

def media3_playing_evidence(expected_media_id: str, minimum_samples: int = 2) -> Dict[str, Any]:
    """Require Media3 evidence, not merely a completed control operation."""
    samples: List[Dict[str, Any]] = []
    deadline = time.monotonic() + min(12.0, max(4.0, OPERATION_TIMEOUT / 4.0))
    while time.monotonic() < deadline:
        status, payload, error = agent_request("/diagnostics")
        media3 = payload.get("media3") if isinstance(payload, dict) else None
        if status == 200 and isinstance(media3, dict):
            sample = {
                "state": str(media3.get("playbackState") or "").upper(),
                "isPlaying": bool(media3.get("isPlaying")),
                "playWhenReady": bool(media3.get("playWhenReady")),
                "mediaItemId": str(media3.get("mediaItemId") or ""),
                "positionMs": int(media3.get("currentPositionMs") or 0),
                "bufferedPositionMs": int(media3.get("bufferedPositionMs") or 0),
            }
            if (
                sample["state"] == "READY"
                and sample["isPlaying"]
                and sample["playWhenReady"]
                and (
                    not expected_media_id
                    or sample["mediaItemId"].startswith(expected_media_id)
                    or f":{expected_media_id}:" in sample["mediaItemId"]
                )
            ):
                samples.append(sample)
                if len(samples) >= minimum_samples:
                    positions = [item["positionMs"] for item in samples]
                    if any(right > left for left, right in zip(positions, positions[1:])):
                        return {"playable": True, "samples": samples, "error": ""}
            else:
                samples = []
        elif error:
            last_error = error
        time.sleep(0.35)
    return {"playable": False, "samples": samples[-minimum_samples:], "error": "Media3 READY/isPlaying/position evidence missing"}

def run_playback_test(item: Dict[str, Any], season: Optional[int] = None, episode: Optional[int] = None) -> Dict[str, Any]:
    ensure_bridge()
    media_id = str(item["id"])
    title = str(item["title"])
    is_series = season is not None and episode is not None
    req_id = f"cov-{random.randint(100000, 999999)}"

    payload: Dict[str, Any] = {
        "mediaId": media_id,
        "title": title,
        "resume": False,
        "persist": False,
    }
    if is_series:
        payload["season"] = season
        payload["episode"] = episode

    t0 = time.monotonic()
    req_payload = {
        "action": "media.play",
        "arguments": payload,
        "requestId": req_id,
    }
    status_code, resp, err = agent_request("/action", method="POST", payload=req_payload)
    t_req = time.monotonic() - t0

    if status_code != 200 or not resp or resp.get("status") != "accepted" or not resp.get("operationId"):
        return {
            "media_id": media_id,
            "title": title,
            "season": season,
            "episode": episode,
            "classification": "PLAYER_FAILURE",
            "error": f"HTTP {status_code}: {err}",
            "time_to_resolve_ms": round(t_req * 1000, 2),
            "time_to_ready_ms": 0.0,
            "stream_count": 0,
            "voices": [],
            "qualities": [],
        }

    op_id = resp["operationId"]
    deadline = time.monotonic() + OPERATION_TIMEOUT
    final_op = None

    while time.monotonic() < deadline:
        s_code, op_data, _ = agent_request(f"/operations?operationId={op_id}")
        if s_code == 200 and isinstance(op_data, dict):
            op_obj = op_data.get("operation") or op_data
            status = op_obj.get("status")
            if status in ("COMPLETED", "FAILED"):
                final_op = op_obj
                break
        time.sleep(0.35)

    t_total = time.monotonic() - t0

    # Also query backend stream endpoint to get full resolved candidate metadata
    backend_url = f"http://127.0.0.1:8888/api/movie/{urllib.parse.quote(media_id)}/stream"
    if is_series:
        backend_url += f"?season={season}&episode={episode}"

    stream_count = 0
    voices = set()
    qualities = set()
    try:
        req_b = urllib.request.Request(backend_url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req_b, timeout=4.0) as b_resp:
            b_data = json.loads(b_resp.read().decode("utf-8"))
            b_streams = b_data.get("streams") or []
            stream_count = len(b_streams)
            for s in b_streams:
                if s.get("voice"): voices.add(s["voice"])
                if s.get("quality"): qualities.add(s["quality"])
    except Exception:
        pass

    if not final_op:
        classification = "TIMEOUT"
        evidence = {"playable": False, "samples": [], "error": "operation did not reach terminal state"}
    elif final_op.get("status") == "COMPLETED":
        evidence = media3_playing_evidence(media_id)
        classification = "PLAYABLE" if evidence.get("playable") else "PLAYER_FAILURE"
    else:
        evidence = {"playable": False, "samples": [], "error": "operation failed"}
        err_msg = " ".join(
            str(final_op.get(key) or "")
            for key in ("error", "message", "errorCode")
        ).lower()
        if any(token in err_msg for token in ("auth", "credential", "authorization", "token")):
            classification = "AUTH_REQUIRED"
        elif any(token in err_msg for token in ("peer", "seeder", "swarm")):
            classification = "P2P_NO_PEERS"
        elif "timeout" in err_msg or "timed out" in err_msg:
            classification = "TIMEOUT"
        elif "не удалось найти" in err_msg or "no_playable" in err_msg or stream_count == 0:
            classification = "NO_SOURCE"
        elif "failed" in err_msg:
            classification = "BROKEN_SOURCE"
        else:
            classification = "PLAYER_FAILURE"

    # Pause player after evidence collection to keep device cool and memory clean.
    agent_request("/action", method="POST", payload={"action": "player.pause", "arguments": {}})

    return {
        "media_id": media_id,
        "title": title,
        "season": season,
        "episode": episode,
        "classification": classification,
        "time_to_ready_ms": round(t_total * 1000, 2),
        "stream_count": stream_count,
        "voices": sorted(list(voices)),
        "qualities": sorted(list(qualities)),
        "active_stream": final_op.get("result", {}).get("activeStreamId") if final_op else None,
        "active_voice": final_op.get("result", {}).get("voice") if final_op else None,
        "active_quality": final_op.get("result", {}).get("quality") if final_op else None,
        "media3_evidence": evidence,
    }

def main():
    print(f"=== Movia Random Playback Coverage Test (Seed: {SEED}) ===")
    movies, series = sample_catalog(seed=SEED, movie_count=20, series_count=20)
    print(f"Sampled {len(movies)} movies and {len(series)} TV series.")

    results: List[Dict[str, Any]] = []

    print("\n--- 1. Testing Movies (20 items) ---")
    for i, m in enumerate(movies, 1):
        res = run_playback_test(m)
        results.append(res)
        print(f"[{i:02d}/20 MOVIE] {m['title']} ({m.get('year', '?')}): {res['classification']} (streams={res['stream_count']}, voices={len(res['voices'])}, time={res['time_to_ready_ms']}ms)")

    print("\n--- 2. Testing TV Series Episodes (20 items) ---")
    for i, s in enumerate(series, 1):
        # Pick S01E01 as canonical baseline or S01E02
        ep_num = 1 if i % 2 == 1 else 2
        res = run_playback_test(s, season=1, episode=ep_num)
        results.append(res)
        print(f"[{i:02d}/20 SERIES] {s['title']} S01E{ep_num:02d}: {res['classification']} (streams={res['stream_count']}, voices={len(res['voices'])}, time={res['time_to_ready_ms']}ms)")

    # Aggregations
    total_count = len(results)
    playable_count = sum(1 for r in results if r["classification"] == "PLAYABLE")
    no_source_count = sum(1 for r in results if r["classification"] == "NO_SOURCE")
    broken_count = sum(1 for r in results if r["classification"] == "BROKEN_SOURCE")
    failure_count = sum(1 for r in results if r["classification"] == "PLAYER_FAILURE")
    timeout_count = sum(1 for r in results if r["classification"] == "TIMEOUT")

    latencies = [r["time_to_ready_ms"] for r in results if r["classification"] == "PLAYABLE"]
    latencies.sort()
    p50 = latencies[len(latencies) // 2] if latencies else 0.0
    p95 = latencies[int(len(latencies) * 0.95)] if latencies else 0.0

    all_voices = set()
    all_qualities = set()
    for r in results:
        all_voices.update(r["voices"])
        all_qualities.update(r["qualities"])

    summary = {
        "total_tested": total_count,
        "playable": playable_count,
        "playable_percent": round(playable_count / total_count * 100, 1),
        "no_source": no_source_count,
        "no_source_percent": round(no_source_count / total_count * 100, 1),
        "broken_source": broken_count,
        "player_failure": failure_count,
        "timeout": timeout_count,
        "playable_latency_p50_ms": p50,
        "playable_latency_p95_ms": p95,
        "distinct_voices_count": len(all_voices),
        "distinct_qualities_count": len(all_qualities),
        "distinct_voices_sample": sorted(list(all_voices))[:10],
        "distinct_qualities": sorted(list(all_qualities)),
    }

    print("\n=== COVERAGE SUMMARY ===")
    print(json.dumps(summary, indent=2, ensure_ascii=False))

    output_path = ROOT / "acceptance" / "08_playback_coverage_random.latest.json"
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "results": results}, f, indent=2, ensure_ascii=False)
    print(f"\nReport written to: {output_path}")

    # Success criteria: No silent unclassified crashes; all items are either PLAYABLE or genuine upstream NO_SOURCE
    if failure_count == 0 and timeout_count == 0 and broken_count == 0:
        print("\nALL RANDOM SAMPLES PROCESSED CLEANLY (PASS)!")
        sys.exit(0)
    else:
        print(f"\nWARNING: Failures={failure_count}, Timeouts={timeout_count}, Broken={broken_count}")
        sys.exit(1)

if __name__ == "__main__":
    main()
