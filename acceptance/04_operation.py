#!/usr/bin/env python3
"""Acceptance for accepted -> polled -> completed asynchronous media.play."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


ROOT = Path(__file__).resolve().parent.parent
CLI = Path(os.environ.get("MOVIA_CLI", str(Path.home() / "bin/movia-agent")))
BASE_URL = os.environ.get("MOVIA_BASE_URL", "http://127.0.0.1:8899/agent/v1").rstrip("/")
TOKEN_FILE = Path(os.environ.get("MOVIA_TOKEN_FILE", str(Path.home() / ".config/movia-agent/token")))
HTTP_TIMEOUT = float(os.environ.get("MOVIA_HTTP_TIMEOUT", "5"))
POLL_TIMEOUT = float(os.environ.get("MOVIA_OPERATION_TIMEOUT", "75"))


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


def request(
    path: str,
    method: str = "GET",
    payload: Optional[Dict[str, Any]] = None,
) -> Tuple[Optional[int], Optional[Any], str]:
    body = None
    headers = {"Accept": "application/json", "Authorization": f"Bearer {load_token()}"}
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE_URL + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as response:
            raw = response.read().decode("utf-8", errors="replace")
            try:
                return response.status, json.loads(raw), ""
            except json.JSONDecodeError as exc:
                return response.status, None, f"invalid JSON: {exc}"
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            return exc.code, json.loads(raw), raw[:240]
        except json.JSONDecodeError:
            return exc.code, None, raw[:240]
    except Exception as exc:
        return None, None, compact_error(exc)


def normalized_status(operation: Any) -> str:
    if not isinstance(operation, dict):
        return ""
    return str(operation.get("status") or operation.get("state") or "").upper()


def main() -> int:
    bridge_ok, bridge_error = ensure_bridge()
    checks: List[Dict[str, Any]] = []

    def emit(name: str, passed: bool, detail: Optional[Any] = None) -> None:
        record: Dict[str, Any] = {"name": name, "pass": bool(passed)}
        if detail is not None:
            record["detail"] = detail
        checks.append(record)
        print(f"{'PASS' if passed else 'FAIL'} {name}")

    emit("headless bridge available", bridge_ok, bridge_error or None)
    if not bridge_ok:
        print(json.dumps({"script": "04_operation", "pass": False, "checks": checks}, separators=(",", ":")))
        return 1

    try:
        load_token()
        token_ok = True
        token_error = None
    except Exception as exc:
        token_ok = False
        token_error = compact_error(exc)
    emit("CLI token is present", token_ok, token_error)
    if not token_ok:
        print(json.dumps({"script": "04_operation", "pass": False, "checks": checks}, separators=(",", ":")))
        return 1

    title = os.environ.get("MOVIA_OPERATION_TITLE", "Breaking Bad")
    media_id = os.environ.get("MOVIA_OPERATION_MEDIA_ID", "").strip()
    try:
        season = int(os.environ.get("MOVIA_OPERATION_SEASON", "1"))
        episode = int(os.environ.get("MOVIA_OPERATION_EPISODE", "1"))
    except ValueError:
        season, episode = 1, 1
    arguments: Dict[str, Any] = {
        "title": title,
        "season": season,
        "episode": episode,
        "resume": False,
        "persist": False,
    }
    if media_id:
        arguments["mediaId"] = media_id

    request_id = "acceptance-operation-" + uuid.uuid4().hex
    status, accepted_payload, request_error = request(
        "/action",
        method="POST",
        payload={"action": "media.play", "arguments": arguments, "requestId": request_id},
    )
    accepted_ok = (
        status == 200
        and isinstance(accepted_payload, dict)
        and accepted_payload.get("status") == "accepted"
        and isinstance(accepted_payload.get("operationId"), str)
        and bool(accepted_payload.get("operationId"))
    )
    emit("media.play is accepted with an operation ID", accepted_ok, request_error)
    if not accepted_ok:
        result = {
            "script": "04_operation",
            "pass": False,
            "checks": checks,
            "request": arguments,
        }
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        return 1

    operation_id = accepted_payload["operationId"]
    observed_statuses: List[str] = []
    final_operation: Optional[Dict[str, Any]] = None
    poll_errors: List[str] = []
    deadline = time.monotonic() + POLL_TIMEOUT
    while time.monotonic() < deadline:
        encoded_id = urllib.parse.quote(operation_id, safe="")
        poll_status, poll_payload, poll_error = request(f"/operations?operationId={encoded_id}")
        operation = poll_payload.get("operation") if isinstance(poll_payload, dict) else None
        state = normalized_status(operation)
        if state:
            observed_statuses.append(state)
        if poll_status != 200 or not isinstance(operation, dict):
            if poll_error:
                poll_errors.append(poll_error)
        else:
            final_operation = operation
            if state in {"COMPLETED", "FAILED"}:
                break
        time.sleep(0.2)

    final_state = normalized_status(final_operation)
    emit(
        "operation can be polled until a terminal state",
        bool(final_operation) and final_state in {"COMPLETED", "FAILED"},
        {"observed": observed_statuses, "errors": poll_errors[:3]},
    )
    emit(
        "media.play operation completes successfully",
        final_state == "COMPLETED",
        {"finalStatus": final_state, "operationId": operation_id},
    )
    emit(
        "terminal operation identity is stable",
        isinstance(final_operation, dict)
        and final_operation.get("operationId", operation_id) == operation_id
        and final_operation.get("action", "media.play") == "media.play",
    )

    passed = all(item["pass"] for item in checks)
    result = {
        "script": "04_operation",
        "pass": passed,
        "requestId": request_id,
        "request": arguments,
        "operationId": operation_id,
        "observedStatuses": observed_statuses,
        "finalStatus": final_state,
        "pollErrors": poll_errors[:3],
        "checks": checks,
    }
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
