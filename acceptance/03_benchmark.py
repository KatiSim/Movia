#!/usr/bin/env python3
"""Benchmark 1,000 warm requests for each core read endpoint."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


ROOT = Path(__file__).resolve().parent.parent
CLI = Path(os.environ.get("MOVIA_CLI", str(Path.home() / "bin/movia-agent")))
BASE_URL = os.environ.get("MOVIA_BASE_URL", "http://127.0.0.1:8899/agent/v1").rstrip("/")
TOKEN_FILE = Path(os.environ.get("MOVIA_TOKEN_FILE", str(Path.home() / ".config/movia-agent/token")))
HTTP_TIMEOUT = float(os.environ.get("MOVIA_BENCHMARK_TIMEOUT", "5"))
ITERATIONS = int(os.environ.get("MOVIA_BENCHMARK_ITERATIONS", "1000"))


def compact_error(value: Any) -> str:
    return str(value).replace("\n", " ").strip()[:240]


def load_token() -> str:
    token = TOKEN_FILE.read_text(encoding="utf-8").strip()
    if len(token) != 64 or any(char not in "0123456789abcdefABCDEF" for char in token):
        raise ValueError(f"invalid token file: {TOKEN_FILE}")
    return token


def ensure_bridge() -> Tuple[bool, str]:
    try:
        completed = subprocess.run(
            ["bash", str(CLI), "health"],
            cwd=str(ROOT),
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            timeout=15,
            check=False,
        )
        payload = json.loads(completed.stdout) if completed.stdout else None
        ok = completed.returncode == 0 and isinstance(payload, dict) and payload.get("status") == "ok"
        return ok, "" if ok else compact_error(completed.stderr or completed.stdout)
    except Exception as exc:  # pragma: no cover - exercised on a missing runtime
        return False, compact_error(exc)


def one_request(path: str) -> Tuple[Optional[int], Optional[Any], str]:
    request = urllib.request.Request(
        BASE_URL + path,
        headers={"Accept": "application/json", "Authorization": f"Bearer {load_token()}"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT) as response:
            raw = response.read().decode("utf-8", errors="replace")
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError as exc:
                return response.status, None, f"invalid JSON: {exc}"
            return response.status, payload, ""
    except urllib.error.HTTPError as exc:
        return exc.code, None, compact_error(exc)
    except Exception as exc:
        return None, None, compact_error(exc)


def percentile(values: List[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    weight = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * weight


def main() -> int:
    if ITERATIONS != 1000:
        print(f"FAIL benchmark iteration count is {ITERATIONS}, expected 1000")
        result = {
            "script": "03_benchmark",
            "pass": False,
            "iterationsPerEndpoint": ITERATIONS,
            "error": "MOVIA_BENCHMARK_ITERATIONS must remain 1000 for acceptance",
        }
        print(json.dumps(result, separators=(",", ":")))
        return 1

    bridge_ok, bridge_error = ensure_bridge()
    print(f"{'PASS' if bridge_ok else 'FAIL'} warm bridge preflight")
    if not bridge_ok:
        result = {"script": "03_benchmark", "pass": False, "error": bridge_error}
        print(json.dumps(result, separators=(",", ":")))
        return 1

    try:
        load_token()
    except Exception as exc:
        print("FAIL benchmark token is present")
        result = {"script": "03_benchmark", "pass": False, "error": compact_error(exc)}
        print(json.dumps(result, separators=(",", ":")))
        return 1

    endpoint_stats: Dict[str, Dict[str, Any]] = {}
    for path in ("/health", "/snapshot", "/actions"):
        timings: List[float] = []
        failures = 0
        error_samples: List[str] = []
        for _ in range(ITERATIONS):
            started = time.perf_counter()
            status, payload, error = one_request(path)
            elapsed_ms = (time.perf_counter() - started) * 1000.0
            if status == 200 and isinstance(payload, dict) and "schemaVersion" in payload:
                timings.append(elapsed_ms)
            else:
                failures += 1
                if len(error_samples) < 3:
                    error_samples.append(error or f"HTTP {status}")
        stats = {
            "requests": ITERATIONS,
            "successes": len(timings),
            "failures": failures,
            "p50Ms": round(percentile(timings, 0.50), 3),
            "p95Ms": round(percentile(timings, 0.95), 3),
            "p99Ms": round(percentile(timings, 0.99), 3),
        }
        if error_samples:
            stats["errorSamples"] = error_samples
        endpoint_stats[path.lstrip("/")] = stats
        print(
            f"{'PASS' if failures == 0 else 'FAIL'} benchmark {path} "
            f"p50={stats['p50Ms']:.3f}ms p95={stats['p95Ms']:.3f}ms "
            f"p99={stats['p99Ms']:.3f}ms failures={failures}"
        )

    passed = all(stats["failures"] == 0 for stats in endpoint_stats.values())
    result = {
        "script": "03_benchmark",
        "pass": passed,
        "warm": True,
        "iterationsPerEndpoint": ITERATIONS,
        "totalRequests": ITERATIONS * 3,
        "endpoints": endpoint_stats,
    }
    print(json.dumps(result, separators=(",", ":")))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
