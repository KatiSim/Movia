#!/usr/bin/env python3
"""Read-only smoke acceptance for the Movia loopback agent."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


ROOT = Path(__file__).resolve().parent.parent
CLI = Path(os.environ.get("MOVIA_CLI", str(Path.home() / "bin/movia-agent")))
BASE_URL = os.environ.get("MOVIA_BASE_URL", "http://127.0.0.1:8899/agent/v1").rstrip("/")
TOKEN_FILE = Path(os.environ.get("MOVIA_TOKEN_FILE", str(Path.home() / ".config/movia-agent/token")))
HTTP_TIMEOUT = float(os.environ.get("MOVIA_HTTP_TIMEOUT", "5"))

Check = Dict[str, Any]
checks: List[Check] = []


def emit(name: str, passed: bool, detail: Optional[Any] = None) -> None:
    record: Check = {"name": name, "pass": bool(passed)}
    if detail is not None:
        record["detail"] = detail
    checks.append(record)
    print(f"{'PASS' if passed else 'FAIL'} {name}")


def compact_error(value: Any) -> str:
    text = str(value).replace("\n", " ").strip()
    return text[:240]


def run_cli(command: str) -> Tuple[int, str, str]:
    try:
        completed = subprocess.run(
            ["bash", str(CLI), command],
            cwd=str(ROOT),
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            timeout=15,
            check=False,
        )
        return completed.returncode, completed.stdout, completed.stderr
    except Exception as exc:  # pragma: no cover - exercised on a missing runtime
        return 127, "", compact_error(exc)


def load_token() -> str:
    token = TOKEN_FILE.read_text(encoding="utf-8").strip()
    if len(token) != 64 or any(char not in "0123456789abcdefABCDEF" for char in token):
        raise ValueError(f"invalid token file: {TOKEN_FILE}")
    return token


def http_request(
    path: str,
    method: str = "GET",
    payload: Optional[Dict[str, Any]] = None,
    authenticated: bool = True,
) -> Tuple[Optional[int], Optional[Any], str]:
    body = None
    headers = {"Accept": "application/json"}
    if authenticated:
        headers["Authorization"] = f"Bearer {load_token()}"
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(BASE_URL + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT) as response:
            raw = response.read().decode("utf-8", errors="replace")
            try:
                decoded = json.loads(raw)
            except json.JSONDecodeError as exc:
                return response.status, None, f"invalid JSON: {exc}"
            return response.status, decoded, ""
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            decoded = json.loads(raw)
        except json.JSONDecodeError:
            decoded = None
        return exc.code, decoded, raw[:240]
    except Exception as exc:
        return None, None, compact_error(exc)


def schema_object(status: Optional[int], payload: Any) -> bool:
    return status == 200 and isinstance(payload, dict) and "schemaVersion" in payload


def main() -> int:
    health_rc, health_raw, health_err = run_cli("health")
    try:
        health_cli = json.loads(health_raw) if health_raw else None
    except json.JSONDecodeError:
        health_cli = None
    bridge_ok = health_rc == 0 and isinstance(health_cli, dict) and health_cli.get("status") == "ok"
    emit("headless bridge available", bridge_ok, None if bridge_ok else compact_error(health_err or health_raw))
    if not bridge_ok:
        result = {"script": "02_smoke", "pass": False, "checks": checks}
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        return 1

    try:
        load_token()
        token_ok = True
        token_detail = None
    except Exception as exc:
        token_ok = False
        token_detail = compact_error(exc)
    emit("CLI token is present", token_ok, token_detail)
    if not token_ok:
        result = {"script": "02_smoke", "pass": False, "checks": checks}
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        return 1

    endpoint_paths = [
        "/health",
        "/snapshot",
        "/actions",
        "/ui",
        "/ui/controls",
        "/events?limit=5",
        "/settings",
        "/streams",
        "/diagnostics",
        "/capabilities",
        "/manifest",
    ]
    responses: Dict[str, Tuple[Optional[int], Optional[Any], str]] = {}
    for path in endpoint_paths:
        try:
            response = http_request(path)
            responses[path] = response
            status, payload, error = response
            emit(f"GET {path}", schema_object(status, payload), error or f"HTTP {status}")
        except Exception as exc:
            responses[path] = (None, None, compact_error(exc))
            emit(f"GET {path}", False, compact_error(exc))

    health_status, health_payload, health_error = responses["/health"]
    health_shape_ok = (
        schema_object(health_status, health_payload)
        and health_payload.get("status") == "ok"
        and health_payload.get("bindAddress") == "127.0.0.1"
        and health_payload.get("headlessBootstrap") is True
        and health_payload.get("uiRequiredForRead") is False
        and health_payload.get("uiRequiredForDomainActions") is False
        and health_payload.get("shizukuRequired") is False
    )
    emit("health declares headless, loopback, non-Shizuku operation", health_shape_ok, health_error)

    actions_status, actions_payload, actions_error = responses["/actions"]
    action_entries = actions_payload.get("actions") if isinstance(actions_payload, dict) else None
    action_ids = {
        item.get("id")
        for item in action_entries or []
        if isinstance(item, dict) and isinstance(item.get("id"), str)
    }
    expected_actions = {"app.snapshot", "app.health", "media.play", "player.selectQuality", "player.enterPip"}
    emit(
        "actions expose stable machine action IDs",
        schema_object(actions_status, actions_payload)
        and isinstance(action_entries, list)
        and expected_actions.issubset(action_ids),
        actions_error or {"missing": sorted(expected_actions - action_ids)},
    )

    events_payload = responses["/events?limit=5"][1]
    emit("events response contains an event array", isinstance(events_payload, dict) and isinstance(events_payload.get("events"), list))
    settings_payload = responses["/settings"][1]
    emit("settings response contains a settings array", isinstance(settings_payload, dict) and isinstance(settings_payload.get("settings"), list))
    streams_payload = responses["/streams"][1]
    emit("streams response contains quality groups", isinstance(streams_payload, dict) and isinstance(streams_payload.get("qualities"), list))
    diagnostics_payload = responses["/diagnostics"][1]
    emit(
        "diagnostics response contains player and selection sections",
        isinstance(diagnostics_payload, dict)
        and isinstance(diagnostics_payload.get("media3"), dict)
        and isinstance(diagnostics_payload.get("streamSelection"), dict)
        and isinstance(diagnostics_payload.get("backend"), dict),
    )
    capabilities_payload = responses["/capabilities"][1]
    emit(
        "capabilities advertise domain operation without UI or Shizuku",
        isinstance(capabilities_payload, dict)
        and isinstance(capabilities_payload.get("capabilities"), list)
        and capabilities_payload.get("normalActionsRequireUi") is False
        and capabilities_payload.get("normalActionsRequireShizuku") is False,
    )
    manifest_payload = responses["/manifest"][1]
    manifest_bootstrap = manifest_payload.get("bootstrap") if isinstance(manifest_payload, dict) else None
    manifest_endpoints = manifest_payload.get("endpoints") if isinstance(manifest_payload, dict) else None
    emit(
        "manifest describes explicit headless bootstrap and action endpoint",
        isinstance(manifest_payload, dict)
        and manifest_payload.get("transport") == "http-loopback"
        and isinstance(manifest_bootstrap, dict)
        and manifest_bootstrap.get("visibleActivityRequired") is False
        and isinstance(manifest_endpoints, list)
        and "/agent/v1/action" in manifest_endpoints,
    )

    unauth_status, unauth_payload, unauth_error = http_request("/health", authenticated=False)
    emit(
        "unauthenticated request is rejected with HTTP 401",
        unauth_status == 401
        and isinstance(unauth_payload, dict)
        and unauth_payload.get("code") == "UNAUTHORIZED",
        unauth_error,
    )

    action_health_status, action_health_payload, action_health_error = http_request(
        "/action",
        method="POST",
        payload={"action": "app.health", "arguments": {}, "requestId": "acceptance-smoke-health"},
    )
    nested_health = action_health_payload.get("health") if isinstance(action_health_payload, dict) else None
    emit(
        "POST app.health completes through the ordinary API",
        action_health_status == 200
        and isinstance(action_health_payload, dict)
        and action_health_payload.get("status") == "completed"
        and isinstance(nested_health, dict)
        and nested_health.get("status") == "ok",
        action_health_error,
    )

    action_cap_status, action_cap_payload, action_cap_error = http_request(
        "/action",
        method="POST",
        payload={"action": "app.capabilities", "arguments": {}, "requestId": "acceptance-smoke-capabilities"},
    )
    emit(
        "POST app.capabilities completes through the ordinary API",
        action_cap_status == 200
        and isinstance(action_cap_payload, dict)
        and action_cap_payload.get("status") == "completed"
        and isinstance(action_cap_payload.get("capabilities"), dict),
        action_cap_error,
    )

    search_status, search_payload, search_error = http_request(
        "/action",
        method="POST",
        payload={
            "action": "catalog.search",
            "arguments": {"query": "Breaking Bad", "limit": 5},
            "requestId": "acceptance-smoke-catalog",
        },
    )
    emit(
        "POST catalog.search returns machine-readable items",
        search_status == 200
        and isinstance(search_payload, dict)
        and search_payload.get("status") == "completed"
        and isinstance(search_payload.get("items"), list),
        search_error,
    )

    passed = all(item["pass"] for item in checks)
    result = {"script": "02_smoke", "pass": passed, "checks": checks}
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
