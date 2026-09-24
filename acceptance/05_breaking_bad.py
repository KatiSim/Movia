#!/usr/bin/env python3
"""Headless Breaking Bad stream-selection acceptance.

The domain path is exercised through the loopback agent. Screenshot, fullscreen,
and PiP checks are reported separately because they require a visible Activity.
"""

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
from typing import Any, Dict, Iterable, List, Optional, Tuple


ROOT = Path(__file__).resolve().parent.parent
CLI = Path(os.environ.get("MOVIA_CLI", str(Path.home() / "bin/movia-agent")))
BASE_URL = os.environ.get("MOVIA_BASE_URL", "http://127.0.0.1:8899/agent/v1").rstrip("/")
TOKEN_FILE = Path(os.environ.get("MOVIA_TOKEN_FILE", str(Path.home() / ".config/movia-agent/token")))
HTTP_TIMEOUT = float(os.environ.get("MOVIA_HTTP_TIMEOUT", "5"))
POLL_TIMEOUT = float(os.environ.get("MOVIA_OPERATION_TIMEOUT", "75"))
SNAPSHOT_COUNT = int(os.environ.get("MOVIA_SNAPSHOT_COUNT", "8"))
SNAPSHOT_INTERVAL = float(os.environ.get("MOVIA_SNAPSHOT_INTERVAL", "0.4"))
TITLE = os.environ.get("MOVIA_BREAKING_BAD_TITLE", "Breaking Bad")
QUALITY = "720p"
VOICE = "Кубик в Кубе"


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
        if not ok:
            return False, compact_error(completed.stderr or completed.stdout)
        if payload.get("shizukuRequired") is True:
            return False, "health unexpectedly requires Shizuku"
        return True, ""
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


SELECTION_KEYS = {
    "requestedStreamId",
    "activeStreamId",
    "requestedQuality",
    "activeQuality",
    "requestedVoice",
    "activeVoice",
    "fallbackReason",
}


def find_selection(value: Any) -> Optional[Dict[str, Any]]:
    """Find the runtime stream-selection object without depending on UI layout."""
    if isinstance(value, dict):
        preferred = ("streamSelection", "activeStreamSelection", "selection", "player", "playback", "state", "app")
        for key in preferred:
            if key in value:
                found = find_selection(value[key])
                if found is not None:
                    return found
        if len(SELECTION_KEYS.intersection(value.keys())) >= 2:
            return value
        for child in value.values():
            found = find_selection(child)
            if found is not None:
                return found
    elif isinstance(value, list):
        for child in value:
            found = find_selection(child)
            if found is not None:
                return found
    return None


def text_value(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def pair(selection: Dict[str, Any]) -> Dict[str, str]:
    return {
        "requestedQuality": text_value(selection.get("requestedQuality")),
        "requestedVoice": text_value(selection.get("requestedVoice")),
        "activeQuality": text_value(selection.get("activeQuality")),
        "activeVoice": text_value(selection.get("activeVoice")),
        "requestedStreamId": text_value(selection.get("requestedStreamId")),
        "activeStreamId": text_value(selection.get("activeStreamId")),
        "fallbackReason": text_value(selection.get("fallbackReason")),
    }


def equal_text(left: str, right: str) -> bool:
    return bool(left) and left.casefold() == right.casefold()


def desired_pair_matches(values: Dict[str, str]) -> bool:
    return (
        equal_text(values["requestedQuality"], QUALITY)
        and equal_text(values["requestedVoice"], VOICE)
        and equal_text(values["activeQuality"], QUALITY)
        and equal_text(values["activeVoice"], VOICE)
    )


def media3_playing_evidence(expected_media_id: str) -> Dict[str, Any]:
    """The operation is playable only when Media3 is actually advancing."""
    records: List[Dict[str, Any]] = []
    deadline = time.monotonic() + min(12.0, max(4.0, POLL_TIMEOUT / 4.0))
    while time.monotonic() < deadline:
        status, payload, error = request("/diagnostics")
        media3 = payload.get("media3") if isinstance(payload, dict) else None
        if status == 200 and isinstance(media3, dict):
            record = {
                "state": str(media3.get("playbackState") or "").upper(),
                "isPlaying": bool(media3.get("isPlaying")),
                "playWhenReady": bool(media3.get("playWhenReady")),
                "mediaItemId": str(media3.get("mediaItemId") or ""),
                "positionMs": int(media3.get("currentPositionMs") or 0),
            }
            if (
                record["state"] == "READY"
                and record["isPlaying"]
                and record["playWhenReady"]
                and (
                    not expected_media_id
                    or record["mediaItemId"].startswith(expected_media_id)
                    or f":{expected_media_id}:" in record["mediaItemId"]
                )
            ):
                records.append(record)
                if len(records) >= 2 and any(
                    right["positionMs"] > left["positionMs"]
                    for left, right in zip(records, records[1:])
                ):
                    return {"playable": True, "samples": records}
            else:
                records = []
        time.sleep(0.35)
    return {
        "playable": False,
        "samples": records[-2:],
        "error": "Media3 READY/isPlaying/position evidence missing",
    }


def walk_dicts(value: Any) -> Iterable[Dict[str, Any]]:
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk_dicts(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_dicts(child)


def operation_result(operation: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    if not isinstance(operation, dict):
        return {}
    for key in ("result", "output", "data"):
        if isinstance(operation.get(key), dict):
            return operation[key]
    return operation


def result_has_episode(result: Dict[str, Any]) -> bool:
    for candidate in walk_dicts(result):
        season = candidate.get("season", candidate.get("seasonNumber"))
        episode = candidate.get("episode", candidate.get("episodeNumber"))
        if season == 1 and episode == 1:
            return True
    title = text_value(result.get("title"))
    return "S01E01" in title.upper()


def main() -> int:
    checks: List[Dict[str, Any]] = []

    def emit(name: str, passed: bool, detail: Optional[Any] = None) -> None:
        record: Dict[str, Any] = {"name": name, "pass": bool(passed)}
        if detail is not None:
            record["detail"] = detail
        checks.append(record)
        print(f"{'PASS' if passed else 'FAIL'} {name}")

    visual_only = [
        {
            "name": "screenshot and visual-artifact review",
            "status": "NOT_RUN",
            "reason": "requires a visible Activity and pixel/screenshot observation",
        },
        {
            "name": "fullscreen enter/exit and player chrome",
            "status": "NOT_RUN",
            "actions": ["player.enterFullscreen", "player.exitFullscreen"],
            "reason": "presentation-only UI action; excluded from headless domain acceptance",
        },
        {
            "name": "Android PiP enter/return and window controls",
            "status": "NOT_RUN",
            "actions": ["player.enterPip"],
            "reason": "presentation-only UI action; excluded from headless domain acceptance",
        },
    ]

    bridge_ok, bridge_error = ensure_bridge()
    emit("headless bridge available without Shizuku", bridge_ok, bridge_error or None)
    if not bridge_ok:
        print(json.dumps({"script": "05_breaking_bad", "pass": False, "checks": checks, "visualOnly": visual_only}, ensure_ascii=False, separators=(",", ":")))
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
        print(json.dumps({"script": "05_breaking_bad", "pass": False, "checks": checks, "visualOnly": visual_only}, ensure_ascii=False, separators=(",", ":")))
        return 1

    arguments: Dict[str, Any] = {
        "title": TITLE,
        "season": 1,
        "episode": 1,
        "quality": QUALITY,
        "voice": VOICE,
        "resume": False,
        "persist": False,
    }
    request_id = "acceptance-breaking-bad-" + uuid.uuid4().hex
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
    emit("media.play requests Breaking Bad S01E01 at 720p + Кубик в Кубе", accepted_ok, request_error)
    if not accepted_ok:
        result = {
            "script": "05_breaking_bad",
            "pass": False,
            "request": arguments,
            "checks": checks,
            "visualOnly": visual_only,
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
        if poll_status == 200 and isinstance(operation, dict):
            final_operation = operation
            if state in {"COMPLETED", "FAILED"}:
                break
        elif poll_error and len(poll_errors) < 3:
            poll_errors.append(poll_error)
        time.sleep(0.2)
    final_state = normalized_status(final_operation)
    emit(
        "media.play operation reaches a terminal state",
        bool(final_operation) and final_state in {"COMPLETED", "FAILED"},
        {"observed": observed_statuses, "errors": poll_errors},
    )
    operation_values = operation_result(final_operation)
    emit(
        "Breaking Bad operation completes",
        final_state == "COMPLETED",
        {"finalStatus": final_state, "operationId": operation_id},
    )
    media3_evidence = media3_playing_evidence("159") if final_state == "COMPLETED" else {
        "playable": False,
        "samples": [],
        "error": "operation did not complete",
    }
    emit(
        "Media3 reaches READY/isPlaying and position moves",
        final_state == "COMPLETED" and bool(media3_evidence.get("playable")),
        media3_evidence,
    )
    emit(
        "operation result identifies S01E01",
        result_has_episode(operation_values),
        {"result": {key: operation_values.get(key) for key in ("mediaId", "title", "season", "episode", "quality", "voice") if key in operation_values}},
    )

    snapshot_records: List[Dict[str, Any]] = []
    fallback_events: List[Dict[str, Any]] = []
    reset_events: List[Dict[str, Any]] = []
    previous: Optional[Dict[str, str]] = None
    stable_seen = False
    pair_verified = False
    for index in range(max(1, SNAPSHOT_COUNT)):
        snapshot_status, snapshot_payload, snapshot_error = request("/snapshot")
        selection = find_selection(snapshot_payload) if snapshot_status == 200 else None
        values = pair(selection) if selection is not None else None
        record: Dict[str, Any] = {"index": index, "httpStatus": snapshot_status}
        if snapshot_error:
            record["error"] = snapshot_error
        if values is None:
            record["selection"] = None
            if stable_seen:
                reset_events.append({"index": index, "reason": "selection disappeared"})
        else:
            record["selection"] = values
            requested_active_mismatch = (
                not equal_text(values["requestedQuality"], values["activeQuality"])
                or not equal_text(values["requestedVoice"], values["activeVoice"])
                or (
                    bool(values["requestedStreamId"])
                    and bool(values["activeStreamId"])
                    and values["requestedStreamId"] != values["activeStreamId"]
                )
            )
            if values["fallbackReason"] or requested_active_mismatch or not desired_pair_matches(values):
                fallback_events.append(
                    {
                        "index": index,
                        "reason": values["fallbackReason"] or "requested and active pair differ or is not requested pair",
                        "selection": values,
                    }
                )
            if previous is not None and values != previous:
                reset_events.append({"index": index, "reason": "selection changed across repeated snapshots", "from": previous, "to": values})
            if desired_pair_matches(values):
                pair_verified = True
                stable_seen = True
            previous = values
        snapshot_records.append(record)
        if index + 1 < max(1, SNAPSHOT_COUNT):
            time.sleep(max(0.0, SNAPSHOT_INTERVAL))

    snapshots_ok = (
        len(snapshot_records) == max(1, SNAPSHOT_COUNT)
        and all(record.get("httpStatus") == 200 for record in snapshot_records)
        and pair_verified
        and not fallback_events
        and not reset_events
    )
    emit(
        "repeated snapshots preserve requested and active 720p + Кубик в Кубе pair",
        snapshots_ok,
        {
            "snapshots": snapshot_records,
            "fallbackEvents": fallback_events,
            "resetEvents": reset_events,
        },
    )

    passed = all(item["pass"] for item in checks)
    result = {
        "script": "05_breaking_bad",
        "pass": passed,
        "requestId": request_id,
        "request": arguments,
        "operationId": operation_id,
        "observedStatuses": observed_statuses,
        "finalStatus": final_state,
        "pollErrors": poll_errors,
        "fallbackEvents": fallback_events,
        "resetEvents": reset_events,
        "snapshotCount": len(snapshot_records),
        "media3Evidence": media3_evidence,
        "visualOnly": visual_only,
        "checks": checks,
    }
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
