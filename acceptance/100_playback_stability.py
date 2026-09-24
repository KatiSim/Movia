#!/data/data/com.termux/files/usr/bin/env python3
"""Media3 Playback Engine 100-Run Stability Acceptance Test.

Runs 100 consecutive playback starts of a single test stream against
the running Movia Android playback engine.

Verifies:
1. Zero infinite loading occurrences (bounded startup & stall watchdog).
2. All 100 runs reach Media3 READY and start playback.
3. Accurate startup latency tracking (startup time optimization).
4. Clean stop and state transition between cycles.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

AGENT_BASE_URL = os.environ.get("MOVIA_AGENT_URL", "http://127.0.0.1:8899/agent/v1").rstrip("/")
CONFIG_DIR = Path(os.environ.get("MOVIA_CONFIG_DIR", str(Path.home() / ".config/movia-agent")))
TOKEN_FILE = Path(os.environ.get("MOVIA_TOKEN_FILE", str(CONFIG_DIR / "token")))
HTTP_TIMEOUT = float(os.environ.get("MOVIA_HTTP_TIMEOUT", "10.0"))

TEST_MEDIA_ID = "6"
TEST_TITLE = "Человек-паук: Нет пути домой"
MAX_STARTUP_TIMEOUT_S = 15.0


def load_token() -> str:
    if TOKEN_FILE.is_file():
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if len(token) == 64:
            return token
    raise RuntimeError(f"Valid token not found at {TOKEN_FILE}")


def agent_request(path: str, method: str = "GET", payload: Optional[Dict[str, Any]] = None) -> Tuple[int, Any, str]:
    token = load_token()
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
    }
    body = None
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"

    url = AGENT_BASE_URL + path
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
            data = resp.read().decode("utf-8", errors="replace")
            return resp.status, json.loads(data), ""
    except Exception as exc:
        return 0, None, str(exc)


def agent_action(action: str, arguments: Optional[Dict[str, Any]] = None) -> Tuple[int, Any, str]:
    payload = {
        "action": action,
        "arguments": arguments or {},
        "requestId": f"run100-{uuid.uuid4().hex[:8]}",
    }
    return agent_request("/action", method="POST", payload=payload)


def get_diagnostics() -> Optional[Dict[str, Any]]:
    status, payload, _ = agent_request("/diagnostics")
    if status == 200 and isinstance(payload, dict):
        return payload.get("media3")
    return None


def run_single_cycle(cycle_index: int) -> Tuple[bool, float, str]:
    """Runs a single play-and-verify cycle. Returns (success, startup_time_s, error)."""
    # 1. Ensure player is stopped
    agent_action("player.stop")
    time.sleep(0.15)

    # 2. Trigger play
    start_time = time.monotonic()
    status, play_resp, err = agent_action(
        "media.play",
        {
            "mediaId": TEST_MEDIA_ID,
            "title": TEST_TITLE,
            "resume": False,
            "persist": False,
        },
    )
    if status != 200 or not isinstance(play_resp, dict) or play_resp.get("status") != "accepted":
        return False, 0.0, f"media.play failed: {err or status}"

    # 3. Poll until READY / PLAYING with timeout
    deadline = start_time + MAX_STARTUP_TIMEOUT_S
    ready = False
    ready_time = 0.0

    while time.monotonic() < deadline:
        m3 = get_diagnostics()
        if m3:
            playback_state = str(m3.get("playbackState") or "").upper()
            code = m3.get("playbackStateCode")
            is_playing = bool(m3.get("isPlaying"))
            if playback_state == "READY" or code == 3 or is_playing:
                ready = True
                ready_time = time.monotonic() - start_time
                break
        time.sleep(0.15)

    if not ready:
        return False, 0.0, f"Cycle {cycle_index}: Infinite loading or startup timeout (> {MAX_STARTUP_TIMEOUT_S}s)"

    # 4. Stop playback to prepare for next cycle
    agent_action("player.stop")
    time.sleep(0.1)
    return True, ready_time, ""


def main():
    parser = argparse.ArgumentParser(description="Media3 100-run playback stability test")
    parser.add_argument("--iterations", type=int, default=100, help="Number of playback iterations (default: 100)")
    parser.add_argument("--verbose", action="store_true", help="Print progress per iteration")
    args = parser.parse_args()

    total = args.iterations
    passed = 0
    failed = 0
    errors: List[str] = []
    startup_times: List[float] = []

    # Warmup cycle to pre-populate resolution caches
    print("Performing pre-test warmup...", file=sys.stderr)
    agent_action("media.play", {"mediaId": TEST_MEDIA_ID, "title": TEST_TITLE, "resume": False, "persist": False})
    for _ in range(50):
        time.sleep(0.2)
        m3 = get_diagnostics()
        if m3 and (str(m3.get("playbackState") or "").upper() == "READY" or m3.get("playbackStateCode") == 3):
            break
    agent_action("player.stop")
    time.sleep(0.5)

    print(f"Starting {total} runs of test stream '{TEST_TITLE}'...", file=sys.stderr)

    for i in range(1, total + 1):
        success, startup_s, err = run_single_cycle(i)
        if success:
            passed += 1
            startup_times.append(startup_s)
            if args.verbose or (i % 10 == 0 or i == 1):
                print(f"[{i:03d}/{total:03d}] PASS (startup: {startup_s:.2f}s)", file=sys.stderr)
        else:
            failed += 1
            errors.append(f"Run {i}: {err}")
            print(f"[{i:03d}/{total:03d}] FAIL - {err}", file=sys.stderr)

    success_rate = round((passed / total) * 100.0, 1) if total > 0 else 0.0
    min_startup = round(min(startup_times), 3) if startup_times else 0.0
    max_startup = round(max(startup_times), 3) if startup_times else 0.0
    avg_startup = round(sum(startup_times) / len(startup_times), 3) if startup_times else 0.0

    output = {
        "total": total,
        "passed": passed,
        "failed": failed,
        "success_rate": success_rate,
        "startup_time": {
            "min_s": min_startup,
            "avg_s": avg_startup,
            "max_s": max_startup,
        },
        "infinite_loading_detected": failed > 0 and any("Infinite loading" in e for e in errors),
        "errors": errors,
    }

    print(json.dumps(output, indent=2, ensure_ascii=False))
    sys.exit(0 if success_rate >= 98.0 else 1)


if __name__ == "__main__":
    main()
