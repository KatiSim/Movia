#!/usr/bin/env python3
"""Final additive acceptance for Movia UI contracts, security, and playback.

This script deliberately complements acceptance/01..06.  It does not wake the
app, issue Android shell commands, click UI, change settings, clear data, or
trigger catalog synchronization.  In full mode it uses the established
loopback agent and performs one ordinary ``media.play`` probe with
``resume=false`` and ``persist=false`` so resolver/operation timings and
provider coverage can be measured end to end.

The final line is one orchestration-friendly JSON object.  ``--source-only``
is useful on a build host before the loopback agent is running.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple


ROOT = Path(__file__).resolve().parent.parent
SCRIPT = "07_final_acceptance"
JSON_SCHEMA_VERSION = 1

AGENT_DEFAULT_URL = "http://127.0.0.1:8899/agent/v1"
CATALOG_DEFAULT_URL = "http://127.0.0.1:8888"
CATALOG_SYNC_STATUS_PATH = "/api/catalog/sync-status"
CATALOG_CADENCE_SECONDS = 300
CATALOG_OVERDUE_SECONDS = 900
MAX_CLOCK_SKEW_SECONDS = 60

PLAYER_SOURCE = ROOT / "app/src/main/java/app/movia/android/ui/player/PlayerScreen.kt"
DETAILS_SOURCE = ROOT / "app/src/main/java/app/movia/android/ui/details/DetailsScreen.kt"
RECEIVER_SOURCE = ROOT / "app/src/main/java/app/movia/android/agent/AgentBootstrapReceiver.kt"
SERVICE_SOURCE = ROOT / "app/src/main/java/app/movia/android/agent/AgentControlService.kt"
RUNTIME_SOURCE = ROOT / "app/src/main/java/app/movia/android/agent/AgentControlRuntime.kt"
MANIFEST_SOURCE = ROOT / "app/src/main/AndroidManifest.xml"

LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}
PLACEHOLDER_VALUES = {
    "",
    "auto",
    "n/a",
    "na",
    "none",
    "null",
    "unknown",
    "не указано",
    "неизвестно",
    "-",
}

Check = Dict[str, Any]


def compact_error(value: Any) -> str:
    """Return a short diagnostic with bearer-like values removed."""

    text = str(value).replace("\n", " ").strip()
    text = re.sub(r"(?i)(bearer\s+)?[0-9a-f]{64}", "[REDACTED]", text)
    text = re.sub(r"(?i)(token|authorization)(\s*[:=]\s*)\S+", r"\1\2[REDACTED]", text)
    return text[:240]


def redact(value: Any) -> Any:
    """Redact sensitive-looking strings before they enter the JSON summary."""

    if isinstance(value, str):
        return compact_error(value)
    if isinstance(value, dict):
        return {str(key): redact(item) for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item) for item in value]
    if isinstance(value, tuple):
        return [redact(item) for item in value]
    return value


def emit(
    checks: List[Check],
    check_id: str,
    name: str,
    passed: bool,
    detail: Optional[Any] = None,
    *,
    required: bool = True,
    status: Optional[str] = None,
) -> Check:
    record: Check = {
        "id": check_id,
        "name": name,
        "pass": bool(passed),
        "required": bool(required),
    }
    if status is not None:
        record["status"] = status
    if detail is not None:
        record["detail"] = redact(detail)
    checks.append(record)
    label = "PASS" if passed else "FAIL"
    suffix = f" [{status}]" if status else ""
    print(f"{label} {check_id} {name}{suffix}")
    return record


def load_text(path: Path) -> Tuple[str, Optional[str]]:
    try:
        return path.read_text(encoding="utf-8"), None
    except Exception as exc:
        return "", compact_error(f"{path}: {exc}")


def strip_kotlin_comments(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", source)


def function_block(source: str, marker: str, end_markers: Sequence[str]) -> str:
    start = source.find(marker)
    if start < 0:
        return ""
    candidates = [source.find(marker, start + len(marker)) for marker in end_markers]
    ends = [candidate for candidate in candidates if candidate >= 0]
    end = min(ends) if ends else len(source)
    return source[start:end]


def line_number(source: str, needle: str) -> Optional[int]:
    index = source.find(needle)
    return None if index < 0 else source[:index].count("\n") + 1


def is_loopback_url(raw_url: str) -> bool:
    try:
        parsed = urllib.parse.urlsplit(raw_url)
        return parsed.scheme in {"http", "https"} and (parsed.hostname or "").lower() in LOOPBACK_HOSTS
    except ValueError:
        return False


def safe_url(raw_url: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(raw_url)
        host = parsed.hostname or ""
        if ":" in host and not host.startswith("["):
            host = f"[{host}]"
        netloc = host
        if parsed.port:
            netloc += f":{parsed.port}"
        return urllib.parse.urlunsplit((parsed.scheme, netloc, parsed.path, "", ""))
    except ValueError:
        return "[invalid-url]"


class HttpClient:
    def __init__(self, base_url: str, token: Optional[str], timeout: float) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(
        self,
        path: str,
        *,
        method: str = "GET",
        payload: Optional[Dict[str, Any]] = None,
        authenticated: bool = True,
    ) -> Dict[str, Any]:
        started = time.perf_counter()
        if authenticated and not self.token:
            return {
                "status": None,
                "payload": None,
                "error": "token unavailable",
                "elapsedMs": round((time.perf_counter() - started) * 1000.0, 3),
            }

        headers = {"Accept": "application/json"}
        if authenticated and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        body = None
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"

        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            headers=headers,
            method=method,
        )
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8", errors="replace")
                try:
                    decoded: Any = json.loads(raw)
                    error = ""
                except json.JSONDecodeError as exc:
                    decoded = None
                    error = compact_error(f"invalid JSON: {exc}")
                return {
                    "status": response.status,
                    "payload": decoded,
                    "error": error,
                    "elapsedMs": round((time.perf_counter() - started) * 1000.0, 3),
                }
        except urllib.error.HTTPError as exc:
            # Do not copy an error body into the acceptance report: it could
            # contain a credential or provider URL query string.
            payload = None
            try:
                raw_error = exc.read().decode("utf-8", errors="replace")
                payload = json.loads(raw_error)
            except Exception:
                payload = None
            return {
                "status": exc.code,
                "payload": payload,
                "error": f"HTTP {exc.code}",
                "elapsedMs": round((time.perf_counter() - started) * 1000.0, 3),
            }
        except Exception as exc:
            return {
                "status": None,
                "payload": None,
                "error": compact_error(exc),
                "elapsedMs": round((time.perf_counter() - started) * 1000.0, 3),
            }


def load_token(path: Path) -> Tuple[Optional[str], Optional[str]]:
    try:
        token = path.read_text(encoding="utf-8").strip()
    except Exception as exc:
        return None, compact_error(f"token file unavailable: {exc}")
    if len(token) != 64 or any(char not in "0123456789abcdefABCDEF" for char in token):
        return None, "token file does not contain a 64-hex bearer token"
    return token, None


def parse_positive_float(
    name: str,
    default: float,
    maximum: float,
    aliases: Sequence[str] = (),
) -> Tuple[float, Optional[str]]:
    raw = next((os.environ[key] for key in (name, *aliases) if key in os.environ), str(default))
    try:
        value = float(raw)
    except ValueError:
        return default, f"{name} must be numeric"
    if value <= 0 or value > maximum:
        return default, f"{name} must be > 0 and <= {maximum}"
    return value, None


def parse_positive_int(
    name: str,
    default: int,
    maximum: int,
    aliases: Sequence[str] = (),
) -> Tuple[int, Optional[str]]:
    raw = next((os.environ[key] for key in (name, *aliases) if key in os.environ), str(default))
    try:
        value = int(raw)
    except ValueError:
        return default, f"{name} must be an integer"
    if value <= 0 or value > maximum:
        return default, f"{name} must be > 0 and <= {maximum}"
    return value, None


def source_contracts(checks: List[Check]) -> Dict[str, Any]:
    player, player_error = load_text(PLAYER_SOURCE)
    details, details_error = load_text(DETAILS_SOURCE)
    receiver, receiver_error = load_text(RECEIVER_SOURCE)
    service, service_error = load_text(SERVICE_SOURCE)
    runtime, runtime_error = load_text(RUNTIME_SOURCE)
    manifest, manifest_error = load_text(MANIFEST_SOURCE)

    source_errors = {
        str(path.relative_to(ROOT)): error
        for path, error in (
            (PLAYER_SOURCE, player_error),
            (DETAILS_SOURCE, details_error),
            (RECEIVER_SOURCE, receiver_error),
            (SERVICE_SOURCE, service_error),
            (RUNTIME_SOURCE, runtime_error),
            (MANIFEST_SOURCE, manifest_error),
        )
        if error
    }
    emit(
        checks,
        "S-SOURCE-01",
        "required source files are readable",
        not source_errors,
        {"errors": source_errors} if source_errors else None,
    )

    spinner_marker = "private fun MoviaCenterLoadingSpinner"
    spinner_block = function_block(player, spinner_marker, ["private val PLAYER_CENTER_CONTROL_SIZE"])
    spinner_contract = (
        bool(spinner_block)
        and "rememberInfiniteTransition(" in spinner_block
        and "animateFloat(" in spinner_block
        and re.search(r"targetValue\s*=\s*360f", spinner_block) is not None
        and re.search(r"graphicsLayer\s*\{\s*rotationZ\s*=\s*rotation", spinner_block, re.DOTALL) is not None
        and "Canvas(" in spinner_block
        and "drawArc(" in spinner_block
    )
    emit(
        checks,
        "S-UI-01",
        "PlayerScreen defines the custom rotating Movia center spinner",
        spinner_contract,
        {
            "file": str(PLAYER_SOURCE.relative_to(ROOT)),
            "line": line_number(player, spinner_marker),
            "requiredElements": [
                "rememberInfiniteTransition",
                "animateFloat targetValue=360f",
                "graphicsLayer rotationZ",
                "Canvas/drawArc",
            ],
        },
    )
    buffering_use = re.search(
        r"if\s*\(\s*playback\.status\s*==[^\n)]*BUFFERING\s*\)\s*\{[\s\S]{0,700}?MoviaCenterLoadingSpinner\s*\(",
        player,
    )
    center_use = False
    spinner_call_index = player.find("MoviaCenterLoadingSpinner(", buffering_use.start() if buffering_use else 0)
    if buffering_use and spinner_call_index >= 0:
        # The alignment belongs to the enclosing center-control Box, just
        # before the buffering branch, so inspect that small enclosing region.
        center_context = player[max(0, spinner_call_index - 1200) : spinner_call_index]
        center_use = (
            "contentAlignment = Alignment.Center" in center_context
            and "PLAYER_CENTER_CONTROL_SIZE" in center_context
        )
    emit(
        checks,
        "S-UI-02",
        "PlayerScreen uses the custom spinner specifically for buffering in the centered control",
        buffering_use is not None and center_use,
        {
            "bufferingBranchFound": buffering_use is not None,
            "centerAlignmentFound": center_use,
            "line": None if spinner_call_index < 0 else player[:spinner_call_index].count("\n") + 1,
        },
    )

    sheet_marker = "private fun SeasonEpisodesSheet"
    sheet_block = function_block(details, sheet_marker, ["\n@Composable"])
    details_sheet_contract = (
        "import androidx.compose.material3.ModalBottomSheet" in details
        and bool(sheet_block)
        and "ModalBottomSheet(" in sheet_block
        and "rememberModalBottomSheetState" in sheet_block
        and "onDismissRequest" in sheet_block
        and "HorizontalPager(" in sheet_block
        and "EpisodeRow(" in sheet_block
    )
    emit(
        checks,
        "S-UI-03",
        "DetailsScreen implements seasons and episodes with ModalBottomSheet",
        details_sheet_contract,
        {
            "file": str(DETAILS_SOURCE.relative_to(ROOT)),
            "line": line_number(details, sheet_marker),
            "sheetHeightFraction": "0.88" if ("fillMaxHeight(0.88f)" in sheet_block or "screenHeightDp * 0.88f" in sheet_block) else None,
        },
    )
    no_fullscreen_takeover = (
        re.search(r"\bSeasonEpisodesScreen\b", details) is None
        and "BackHandler" not in sheet_block
        and "ArrowBack" not in sheet_block
        and "TopAppBar" not in sheet_block
        and ("fillMaxHeight(0.88f)" in sheet_block or "screenHeightDp * 0.88f" in sheet_block)
        and "SeasonEpisodesSheet(" in details
    )
    emit(
        checks,
        "S-UI-04",
        "DetailsScreen has no full-screen SeasonEpisodesScreen or back-arrow takeover",
        no_fullscreen_takeover,
        {
            "seasonEpisodesScreenIdentifierPresent": re.search(r"\bSeasonEpisodesScreen\b", details) is not None,
            "sheetContainsBackHandler": "BackHandler" in sheet_block,
            "sheetContainsArrowBack": "ArrowBack" in sheet_block,
            "sheetContainsTopAppBar": "TopAppBar" in sheet_block,
        },
    )

    receiver_code = strip_kotlin_comments(receiver)
    bootstrap_credential_reads = re.findall(
        r"\b(?:get[A-Za-z]*Extra|putExtra|extras|Authorization|Bearer|token|credential)\b",
        receiver_code,
        flags=re.IGNORECASE,
    )
    bootstrap_source_safe = (
        "if (intent.action != ACTION_BOOTSTRAP) return" in receiver_code
        and "AgentControlRuntime.start(context.applicationContext)" in receiver_code
        and not bootstrap_credential_reads
        and not re.search(r"\bstartActivity\s*\(", receiver_code)
        and not re.search(r"[0-9a-fA-F]{64}", receiver_code)
    )
    emit(
        checks,
        "S-SEC-01",
        "bootstrap receiver is wake-only and carries no token or credential extras",
        bootstrap_source_safe,
        {
            "file": str(RECEIVER_SOURCE.relative_to(ROOT)),
            "credentialReadTokens": bootstrap_credential_reads,
            "manifestActionPresent": "app.movia.android.agent.BOOTSTRAP" in manifest,
        },
    )

    service_code = strip_kotlin_comments(service)
    loopback_bind = re.search(
        r"ServerSocket\s*\([\s\S]{0,260}InetAddress\.getByName\(\s*\"127\.0\.0\.1\"\s*\)",
        service_code,
    ) is not None
    no_wildcard_bind = "0.0.0.0" not in service_code and "::/0" not in service_code
    emit(
        checks,
        "S-SEC-02",
        "agent service binds its control socket to loopback only",
        loopback_bind and no_wildcard_bind,
        {"loopbackBindFound": loopback_bind, "wildcardBindFound": not no_wildcard_bind},
    )

    agent_code = "\n".join(strip_kotlin_comments(value) for value in (receiver, service, runtime))
    forbidden_android_shell = [
        pattern
        for pattern in (
            r"\bShizuku\s*\.\s*[A-Za-z_]\w*\s*\(",
            r"import\s+[^;\n]*\bshizuku\b",
            r"require\s*\([^)]*\bshizuku\b",
            r"from\s+[\"'][^\"']*\bshizuku\b",
            r"rikka\.shizuku",
            r"com\.topjohnwu",
            r"\brish\b",
            r"\badb\b",
        )
        if re.search(pattern, agent_code, flags=re.IGNORECASE)
    ]
    emit(
        checks,
        "S-SEC-03",
        "ordinary app-agent path contains no Shizuku or Android-shell dependency",
        not forbidden_android_shell,
        {"forbiddenReferences": forbidden_android_shell},
    )

    mcp_file_raw = os.environ.get("MOVIA_MCP_TOOLS_FILE", "").strip()
    mcp_path = Path(mcp_file_raw) if mcp_file_raw else Path.home() / "termux-mcp/src/movia-tools.ts"
    mcp_source, mcp_error = load_text(mcp_path)
    if mcp_error:
        emit(
            checks,
            "S-SEC-04",
            "MCP tool source contains no Shizuku or Android-shell invocation",
            True,
            {
                "status": "NOT_AVAILABLE",
                "path": str(mcp_path),
                "reason": "acceptance/06_mcp_inventory.sh remains the required MCP availability gate",
            },
            required=False,
            status="NOT_AVAILABLE",
        )
        mcp_detail: Dict[str, Any] = {"status": "NOT_AVAILABLE", "path": str(mcp_path), "error": mcp_error}
    else:
        mcp_forbidden = [
            pattern
            for pattern in (
                r"\bShizuku\s*\.\s*[A-Za-z_]\w*\s*\(",
                r"import\s+[^;\n]*\bshizuku\b",
                r"require\s*\([^)]*\bshizuku\b",
                r"from\s+[\"'][^\"']*\bshizuku\b",
                r"rikka\.shizuku",
                r"\brish\b",
                r"\badb\b",
                r"settings\s+put",
                r"am\s+start",
            )
            if re.search(pattern, mcp_source, flags=re.IGNORECASE)
        ]
        mcp_ok = not mcp_forbidden
        emit(
            checks,
            "S-SEC-04",
            "MCP tool source contains no Shizuku or Android-shell invocation",
            mcp_ok,
            {"path": str(mcp_path), "forbiddenReferences": mcp_forbidden},
            required=True,
        )
        mcp_detail = {"status": "CHECKED", "path": str(mcp_path), "forbiddenReferences": mcp_forbidden}

    result = {
        "pass": all(item["pass"] for item in checks if item.get("required", True)),
        "files": {
            "player": str(PLAYER_SOURCE.relative_to(ROOT)),
            "details": str(DETAILS_SOURCE.relative_to(ROOT)),
            "bootstrapReceiver": str(RECEIVER_SOURCE.relative_to(ROOT)),
            "controlService": str(SERVICE_SOURCE.relative_to(ROOT)),
            "controlRuntime": str(RUNTIME_SOURCE.relative_to(ROOT)),
            "manifest": str(MANIFEST_SOURCE.relative_to(ROOT)),
        },
        "mcp": mcp_detail,
    }
    return result


def find_field(value: Any, names: Iterable[str], path: str = "") -> Tuple[Optional[str], Any, Optional[str]]:
    ordered_names = tuple(names)
    if isinstance(value, dict):
        for name in ordered_names:
            if name in value:
                child_path = f"{path}.{name}" if path else name
                return name, value[name], child_path
        for key, child in value.items():
            child_path = f"{path}.{key}" if path else str(key)
            found = find_field(child, ordered_names, child_path)
            if found[0] is not None:
                return found
    elif isinstance(value, list):
        for index, child in enumerate(value):
            found = find_field(child, ordered_names, f"{path}[{index}]")
            if found[0] is not None:
                return found
    return None, None, None


def nonempty_error(value: Any) -> bool:
    if value is None or value is False:
        return False
    if isinstance(value, str):
        return value.strip().lower() not in {"", "null", "none"}
    if isinstance(value, (dict, list, tuple, set)):
        return len(value) > 0
    return True


def parse_timestamp(value: Any) -> Optional[float]:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        numeric = float(value)
        if numeric > 100_000_000_000:
            numeric /= 1000.0
        return numeric if numeric > 0 else None
    if not isinstance(value, str):
        return None
    stripped = value.strip()
    if not stripped:
        return None
    try:
        numeric = float(stripped)
        if numeric > 100_000_000_000:
            numeric /= 1000.0
        return numeric if numeric > 0 else None
    except ValueError:
        pass
    normalized = stripped[:-1] + "+00:00" if stripped.endswith("Z") else stripped
    try:
        parsed = dt.datetime.fromisoformat(normalized)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.timestamp()


def catalog_freshness(
    checks: List[Check],
    *,
    timeout: float,
) -> Dict[str, Any]:
    raw_url = os.environ.get("MOVIA_CATALOG_SYNC_STATUS_URL", "").strip()
    if raw_url:
        endpoint = raw_url
    else:
        base = os.environ.get("MOVIA_CATALOG_BASE_URL", CATALOG_DEFAULT_URL).rstrip("/")
        endpoint = base + CATALOG_SYNC_STATUS_PATH

    cadence = CATALOG_CADENCE_SECONDS
    overdue = CATALOG_OVERDUE_SECONDS
    result: Dict[str, Any] = {
        "endpoint": safe_url(endpoint),
        "method": "GET",
        "syncTriggered": False,
        "intendedCadenceSeconds": cadence,
        "overdueThresholdSeconds": overdue,
        "thresholdCadences": round(overdue / cadence, 3),
        "available": False,
    }
    if not is_loopback_url(endpoint):
        emit(
            checks,
            "C-FRESH-01",
            "catalog sync-status is queried only on loopback",
            False,
            {"endpoint": safe_url(endpoint)},
        )
        result.update({"status": "CONFIGURATION_FAILURE", "pass": False, "reason": "catalog endpoint is not loopback"})
        return result

    parsed = urllib.parse.urlsplit(endpoint)
    if "/api/" in parsed.path:
        catalog_base_path = parsed.path.rsplit("/api/", 1)[0]
        path = "/api/" + parsed.path.split("/api/", 1)[1]
    else:
        catalog_base_path = parsed.path.rsplit("/", 1)[0]
        path = parsed.path or "/"
    client = HttpClient(
        urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, catalog_base_path, "", "")),
        None,
        timeout,
    )
    response = client.request(path, authenticated=False)
    result["httpStatus"] = response["status"]
    result["requestElapsedMs"] = response["elapsedMs"]

    if response["status"] in (None, 404, 405):
        result.update(
            {
                "status": "NOT_AVAILABLE",
                "pass": True,
                "reason": response["error"] or "endpoint is not exposed",
            }
        )
        emit(
            checks,
            "C-FRESH-01",
            "catalog sync-status is optional when the endpoint is unavailable",
            True,
            result,
            required=False,
            status="NOT_AVAILABLE",
        )
        return result

    payload = response["payload"]
    if response["status"] != 200 or not isinstance(payload, dict):
        result.update({"status": "ENDPOINT_FAILURE", "pass": False, "reason": response["error"] or "invalid response"})
        emit(
            checks,
            "C-FRESH-01",
            "catalog sync-status endpoint responds with JSON",
            False,
            result,
        )
        return result

    result["available"] = True
    emit(
        checks,
        "C-FRESH-01",
        "available catalog sync-status endpoint responds with JSON",
        True,
        {"httpStatus": response["status"], "endpoint": safe_url(endpoint)},
        status="AVAILABLE",
    )
    error_key, error_value, error_path = find_field(payload, ("last_error", "lastError", "last_sync_error", "lastSyncError"))
    no_error = not nonempty_error(error_value)
    result["lastErrorField"] = error_path
    result["lastErrorPresent"] = not no_error
    emit(
        checks,
        "C-FRESH-02",
        "available catalog sync-status has no last_error",
        no_error,
        {"field": error_path, "present": not no_error, "valueType": type(error_value).__name__ if error_key else None},
    )

    timestamp_names = (
        "last_finished_at",
        "lastFinishedAt",
        "last_success_at",
        "lastSuccessAt",
        "last_synced_at",
        "lastSyncedAt",
        "last_sync_at",
        "lastSyncAt",
        "last_completed_at",
        "lastCompletedAt",
        "updated_at",
        "updatedAt",
    )
    timestamp_key, timestamp_value, timestamp_path = find_field(payload, timestamp_names)
    timestamp = parse_timestamp(timestamp_value)
    age = None if timestamp is None else time.time() - timestamp
    result.update(
        {
            "lastSyncField": timestamp_path,
            "lastSyncValue": timestamp_value if isinstance(timestamp_value, (int, float, str)) else None,
            "lastSyncAgeSeconds": None if age is None else round(age, 3),
        }
    )
    if timestamp is None:
        freshness_status = "TIMESTAMP_MISSING"
        freshness_pass = False
    elif age is not None and age < -MAX_CLOCK_SKEW_SECONDS:
        freshness_status = "FUTURE_TIMESTAMP"
        freshness_pass = False
    else:
        freshness_status = "FRESH" if age is not None and age <= overdue else "OVERDUE"
        freshness_pass = age is not None and age <= overdue
    emit(
        checks,
        "C-FRESH-03",
        "available catalog sync is not overdue",
        freshness_pass,
        {
            "field": timestamp_path,
            "ageSeconds": None if age is None else round(age, 3),
            "overdueThresholdSeconds": overdue,
            "intendedCadenceSeconds": cadence,
        },
        status=freshness_status,
    )
    result.update({"status": freshness_status, "pass": no_error and freshness_pass})
    return result


def normalized_status(operation: Any) -> str:
    if not isinstance(operation, dict):
        return ""
    return str(operation.get("status") or operation.get("state") or "").upper()


def operation_result(operation: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    if not isinstance(operation, dict):
        return {}
    for key in ("result", "output", "data"):
        if isinstance(operation.get(key), dict):
            return operation[key]
    return operation


def walk_dicts(value: Any) -> Iterable[Dict[str, Any]]:
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk_dicts(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_dicts(child)


def text_value(value: Any) -> str:
    return "" if value is None else str(value).strip()


def clean_dimension(value: Any) -> Optional[str]:
    text = text_value(value)
    if text.casefold() in PLACEHOLDER_VALUES:
        return None
    return text


def stream_records(payload: Any) -> Tuple[bool, List[Dict[str, Any]]]:
    """Normalize the agent's quality groups or a compatible streams list."""

    if not isinstance(payload, dict):
        return False, []
    records: List[Dict[str, Any]] = []
    qualities = payload.get("qualities")
    if isinstance(qualities, list):
        for group in qualities:
            if not isinstance(group, dict):
                continue
            group_quality = group.get("quality")
            voices = group.get("voices")
            if isinstance(voices, list):
                for voice in voices:
                    if isinstance(voice, dict):
                        item = dict(voice)
                        item.setdefault("quality", group_quality)
                    else:
                        item = {"voice": voice, "quality": group_quality}
                    records.append(item)
            elif isinstance(group.get("stream"), dict):
                item = dict(group["stream"])
                item.setdefault("quality", group_quality)
                records.append(item)
    elif isinstance(qualities, dict):
        for group_quality, voices in qualities.items():
            if isinstance(voices, list):
                for voice in voices:
                    item = dict(voice) if isinstance(voice, dict) else {"voice": voice}
                    item.setdefault("quality", group_quality)
                    records.append(item)

    for key in ("streams", "options", "streamOptions"):
        value = payload.get(key)
        if isinstance(value, list):
            records.extend(item for item in value if isinstance(item, dict))
            return True, records
    return isinstance(qualities, (list, dict)), records


def stream_metrics(payload: Any) -> Dict[str, Any]:
    shape_ok, records = stream_records(payload)
    unique_records: List[Dict[str, Any]] = []
    seen = set()
    for index, record in enumerate(records):
        stream_id = text_value(record.get("streamId") or record.get("stream_id") or record.get("id"))
        url = text_value(record.get("url") or record.get("playback_url"))
        voice = clean_dimension(record.get("voice") or record.get("audio") or record.get("audioLanguage"))
        quality = clean_dimension(record.get("quality") or record.get("resolution"))
        identity = stream_id or (url, quality or "", voice or "", text_value(record.get("source")))
        if identity in seen:
            continue
        seen.add(identity)
        unique_records.append({"voice": voice, "quality": quality})

    voices: List[str] = []
    qualities: List[str] = []
    for record in unique_records:
        if record["voice"] and record["voice"].casefold() not in {item.casefold() for item in voices}:
            voices.append(record["voice"])
        if record["quality"] and record["quality"].casefold() not in {item.casefold() for item in qualities}:
            qualities.append(record["quality"])
    return {
        "shapeValid": shape_ok and bool(unique_records),
        "streamCount": len(unique_records),
        "uniqueVoices": voices,
        "uniqueQualities": qualities,
        "voiceCount": len(voices),
        "qualityCount": len(qualities),
    }


def playback_probe(
    client: HttpClient,
    checks: List[Check],
    *,
    timeout_seconds: float,
    poll_interval_seconds: float,
    settle_seconds: float,
) -> Dict[str, Any]:
    title = os.environ.get(
        "MOVIA_FINAL_TITLE",
        os.environ.get("MOVIA_OPERATION_TITLE", os.environ.get("MOVIA_BREAKING_BAD_TITLE", "Breaking Bad")),
    ).strip() or "Breaking Bad"
    season, season_error = parse_positive_int(
        "MOVIA_FINAL_SEASON",
        1,
        100,
        aliases=("MOVIA_OPERATION_SEASON",),
    )
    episode, episode_error = parse_positive_int(
        "MOVIA_FINAL_EPISODE",
        1,
        1000,
        aliases=("MOVIA_OPERATION_EPISODE",),
    )
    media_id = os.environ.get("MOVIA_FINAL_MEDIA_ID", os.environ.get("MOVIA_OPERATION_MEDIA_ID", "")).strip()
    quality = os.environ.get("MOVIA_FINAL_QUALITY", "").strip()
    voice = os.environ.get("MOVIA_FINAL_VOICE", "").strip()
    config_errors = [error for error in (season_error, episode_error) if error]

    arguments: Dict[str, Any] = {
        "title": title,
        "season": season,
        "episode": episode,
        "resume": False,
        "persist": False,
    }
    if media_id:
        arguments["mediaId"] = media_id
    if quality:
        arguments["quality"] = quality
    if voice:
        arguments["voice"] = voice

    request_id = "acceptance-final-" + uuid.uuid4().hex
    started = time.perf_counter()
    accepted = client.request(
        "/action",
        method="POST",
        payload={"action": "media.play", "arguments": arguments, "requestId": request_id},
    )
    accepted_at = time.perf_counter()
    accepted_payload = accepted.get("payload")
    accepted_ok = (
        accepted.get("status") == 200
        and isinstance(accepted_payload, dict)
        and accepted_payload.get("status") == "accepted"
        and isinstance(accepted_payload.get("operationId"), str)
        and bool(accepted_payload.get("operationId"))
    )
    emit(
        checks,
        "P-PERF-01",
        "final media.play probe is accepted with an operation ID",
        accepted_ok and not config_errors,
        {
            "title": title,
            "season": season,
            "episode": episode,
            "requestId": request_id,
            "requestElapsedMs": accepted.get("elapsedMs"),
            "error": accepted.get("error") or None,
            "configErrors": config_errors,
        },
    )
    if not accepted_ok:
        result = {
            "status": "pipeline_failure",
            "failureClass": "operation_acceptance_failure",
            "requestId": request_id,
            "request": arguments,
            "timeToResolveMs": None,
            "timeToOperationCompletionMs": None,
            "streamCount": 0,
            "uniqueVoices": [],
            "uniqueQualities": [],
            "coverageStatus": "pipeline_failure",
            "accepted": False,
            "operation": None,
        }
        emit(checks, "P-PERF-02", "final media.play probe reaches completed operation", False, result)
        emit(checks, "P-PERF-03", "resolve and operation-completion timings are reported", False, result)
        emit(checks, "P-COV-01", "stream count, voices, and qualities are reported from the agent", False, result)
        emit(
            checks,
            "P-COV-02",
            "voice coverage target is met or explicitly classified as provider limitation",
            False,
            result,
            status="pipeline_failure",
        )
        return result

    operation_id = accepted_payload["operationId"]
    observed_statuses: List[str] = []
    poll_errors: List[str] = []
    final_operation: Optional[Dict[str, Any]] = None
    final_stream_payload: Any = None
    final_stream_response: Optional[Dict[str, Any]] = None
    resolve_at: Optional[float] = None
    resolve_source: Optional[str] = None
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        encoded_id = urllib.parse.quote(operation_id, safe="")
        operation_response = client.request(f"/operations?operationId={encoded_id}")
        operation_payload = operation_response.get("payload")
        operation = operation_payload.get("operation") if isinstance(operation_payload, dict) else None
        state = normalized_status(operation)
        if state:
            observed_statuses.append(state)
        if operation_response.get("status") == 200 and isinstance(operation, dict):
            final_operation = operation
        elif len(poll_errors) < 3:
            poll_errors.append(operation_response.get("error") or f"HTTP {operation_response.get('status')}")

        stream_response = client.request("/streams")
        if stream_response.get("status") == 200 and isinstance(stream_response.get("payload"), dict):
            metrics = stream_metrics(stream_response["payload"])
            final_stream_response = stream_response
            final_stream_payload = stream_response["payload"]
            if metrics["streamCount"] > 0 and resolve_at is None:
                resolve_at = time.perf_counter()
                resolve_source = "streams_endpoint"

        if state in {"COMPLETED", "FAILED"}:
            break
        time.sleep(poll_interval_seconds)

    terminal_state = normalized_status(final_operation)
    terminal_at = time.perf_counter() if terminal_state in {"COMPLETED", "FAILED"} else None

    if terminal_state == "COMPLETED" and resolve_at is None:
        terminal_result = operation_result(final_operation)
        if any(
            text_value(terminal_result.get(key))
            for key in ("activeStreamId", "requestedStreamId", "quality", "voice", "activeQuality", "activeVoice")
        ):
            resolve_at = terminal_at or time.perf_counter()
            resolve_source = "operation_terminal_result"

    # Give a successfully completed operation a short, bounded settle window
    # for the stream list without extending operation timeout or starting sync.
    if terminal_state == "COMPLETED" and settle_seconds > 0:
        settle_deadline = time.monotonic() + settle_seconds
        while time.monotonic() < settle_deadline:
            stream_response = client.request("/streams")
            if stream_response.get("status") == 200 and isinstance(stream_response.get("payload"), dict):
                metrics = stream_metrics(stream_response["payload"])
                final_stream_response = stream_response
                final_stream_payload = stream_response["payload"]
                if metrics["streamCount"] > 0 and resolve_at is None:
                    resolve_at = time.perf_counter()
                    resolve_source = "streams_endpoint_settle"
                if metrics["streamCount"] > 0:
                    break
            time.sleep(min(poll_interval_seconds, 0.5))

    metrics = stream_metrics(final_stream_payload) if final_stream_response else {
        "shapeValid": False,
        "streamCount": 0,
        "uniqueVoices": [],
        "uniqueQualities": [],
        "voiceCount": 0,
        "qualityCount": 0,
    }
    completion_ms = None if terminal_at is None else round((terminal_at - started) * 1000.0, 3)
    resolve_ms = None if resolve_at is None else round((resolve_at - started) * 1000.0, 3)
    server_operation_ms = None
    if isinstance(final_operation, dict):
        created = parse_timestamp(final_operation.get("createdAt"))
        updated = parse_timestamp(final_operation.get("updatedAt"))
        if created is not None and updated is not None and updated >= created:
            server_operation_ms = round((updated - created) * 1000.0, 3)

    pipeline_ok = terminal_state == "COMPLETED"
    if not pipeline_ok:
        pipeline_status = "pipeline_failure"
        coverage_status = "pipeline_failure"
        coverage_pass = False
    elif not metrics["shapeValid"]:
        pipeline_status = "diagnostics_pipeline_failure"
        coverage_status = "pipeline_failure"
        coverage_pass = False
    elif metrics["voiceCount"] >= 2:
        pipeline_status = "ok"
        coverage_status = "target_met"
        coverage_pass = True
    else:
        pipeline_status = "ok"
        coverage_status = "provider_coverage_limitation"
        # A completed operation and a valid stream response prove the pipeline
        # worked.  Fewer than two voices is recorded as upstream limitation,
        # not converted into a global acceptance failure.
        coverage_pass = True

    error_code = final_operation.get("errorCode") if isinstance(final_operation, dict) else None
    error_message = final_operation.get("errorMessage") if isinstance(final_operation, dict) else None
    result: Dict[str, Any] = {
        "status": pipeline_status,
        "failureClass": None if pipeline_ok else "playback_pipeline_failure",
        "requestId": request_id,
        "request": arguments,
        "operationId": operation_id,
        "accepted": True,
        "observedStatuses": observed_statuses,
        "pollErrors": poll_errors,
        "finalStatus": terminal_state,
        "operationErrorCode": error_code,
        "operationErrorMessage": error_message,
        "timeToResolveMs": resolve_ms,
        "timeToResolveSource": resolve_source,
        "timeToOperationCompletionMs": completion_ms,
        "serverOperationMs": server_operation_ms,
        "streamEndpointHttpStatus": final_stream_response.get("status") if final_stream_response else None,
        "streamMetricsShapeValid": metrics["shapeValid"],
        "streamCount": metrics["streamCount"],
        "uniqueVoices": metrics["uniqueVoices"],
        "uniqueQualities": metrics["uniqueQualities"],
        "voiceCount": metrics["voiceCount"],
        "qualityCount": metrics["qualityCount"],
        "coverageStatus": coverage_status,
        "coverageTargetVoices": 2,
        "preferredVoices": 3,
        "preferredVoicesMet": metrics["voiceCount"] >= 3,
        "providerCoverageLimited": pipeline_ok and metrics["shapeValid"] and metrics["voiceCount"] < 2,
    }
    emit(
        checks,
        "P-PERF-02",
        "final media.play probe reaches completed operation",
        pipeline_ok,
        {
            "operationId": operation_id,
            "finalStatus": terminal_state,
            "errorCode": error_code,
            "errorMessage": error_message,
            "timeToOperationCompletionMs": completion_ms,
            "serverOperationMs": server_operation_ms,
        },
    )
    emit(
        checks,
        "P-PERF-03",
        "resolve and operation-completion timings are reported",
        (not pipeline_ok and completion_ms is not None) or (pipeline_ok and completion_ms is not None and resolve_ms is not None),
        {
            "timeToResolveMs": resolve_ms,
            "timeToResolveSource": resolve_source,
            "timeToOperationCompletionMs": completion_ms,
        },
        required=True,
    )
    emit(
        checks,
        "P-COV-01",
        "stream count, voices, and qualities are reported from the agent",
        metrics["shapeValid"],
        {
            "streamCount": metrics["streamCount"],
            "uniqueVoices": metrics["uniqueVoices"],
            "uniqueQualities": metrics["uniqueQualities"],
            "httpStatus": final_stream_response.get("status") if final_stream_response else None,
        },
    )
    emit(
        checks,
        "P-COV-02",
        "voice coverage target is met or explicitly classified as provider limitation",
        coverage_pass,
        {
            "coverageStatus": coverage_status,
            "voiceCount": metrics["voiceCount"],
            "targetVoices": 2,
            "preferredVoices": 3,
            "preferredVoicesMet": metrics["voiceCount"] >= 3,
            "globalFailure": coverage_status == "pipeline_failure",
        },
        status=coverage_status,
    )
    return result


def runtime_security(
    checks: List[Check],
    *,
    base_url: str,
    token: Optional[str],
    timeout: float,
) -> Tuple[Dict[str, Any], Optional[HttpClient]]:
    base_loopback = is_loopback_url(base_url)
    emit(
        checks,
        "R-SEC-01",
        "configured agent endpoint is loopback-only",
        base_loopback,
        {"baseUrl": safe_url(base_url), "allowedHosts": sorted(LOOPBACK_HOSTS)},
    )
    if not base_loopback:
        detail = {"status": "NOT_RUN", "reason": "refusing network access to non-loopback agent URL"}
        emit(checks, "R-SEC-02", "unauthenticated agent request returns HTTP 401", False, detail)
        emit(checks, "R-SEC-03", "authenticated headless health is available", False, detail)
        emit(checks, "R-SEC-04", "ordinary API requires no Shizuku", False, detail)
        return {"baseLoopback": False, "status": "CONFIGURATION_FAILURE"}, None

    client = HttpClient(base_url, token, timeout)
    unauthenticated = client.request("/health", authenticated=False)
    unauth_ok = (
        unauthenticated.get("status") == 401
        and isinstance(unauthenticated.get("payload"), dict)
        and unauthenticated["payload"].get("code") == "UNAUTHORIZED"
    )
    emit(
        checks,
        "R-SEC-02",
        "unauthenticated agent request returns HTTP 401",
        unauth_ok,
        {"httpStatus": unauthenticated.get("status"), "error": unauthenticated.get("error") or None},
    )

    token_ok = token is not None
    emit(
        checks,
        "R-AUTH-01",
        "agent bearer token is available from the established private token file",
        token_ok,
        None if token_ok else {"reason": "token unavailable or invalid; value is never printed"},
    )
    if not token_ok:
        detail = {"status": "NOT_RUN", "reason": "authenticated checks require the established token file"}
        emit(checks, "R-SEC-03", "authenticated headless health is available", False, detail)
        emit(checks, "R-SEC-04", "ordinary API requires no Shizuku", False, detail)
        return {"baseLoopback": True, "status": "TOKEN_FAILURE", "unauthenticated401": unauth_ok}, client

    health = client.request("/health")
    capabilities = client.request("/capabilities")
    manifest = client.request("/manifest")
    health_payload = health.get("payload")
    capabilities_payload = capabilities.get("payload")
    manifest_payload = manifest.get("payload")
    health_ok = (
        health.get("status") == 200
        and isinstance(health_payload, dict)
        and health_payload.get("status") == "ok"
    )
    emit(
        checks,
        "R-SEC-03",
        "authenticated headless health is available",
        health_ok,
        {"httpStatus": health.get("status"), "error": health.get("error") or None},
    )

    health_loopback = isinstance(health_payload, dict) and health_payload.get("bindAddress") == "127.0.0.1"
    manifest_base = manifest_payload.get("baseUrl") if isinstance(manifest_payload, dict) else None
    manifest_loopback = isinstance(manifest_base, str) and is_loopback_url(manifest_base)
    emit(
        checks,
        "R-SEC-05",
        "live health and manifest advertise loopback-only control plane",
        health_loopback and manifest_loopback,
        {
            "healthBindAddress": health_payload.get("bindAddress") if isinstance(health_payload, dict) else None,
            "manifestBaseUrl": safe_url(manifest_base) if isinstance(manifest_base, str) else None,
            "manifestTransport": manifest_payload.get("transport") if isinstance(manifest_payload, dict) else None,
        },
    )

    bootstrap = manifest_payload.get("bootstrap") if isinstance(manifest_payload, dict) else None
    bootstrap_ok = (
        isinstance(bootstrap, dict)
        and bootstrap.get("wakeOnly") is True
        and bootstrap.get("credentialTransfer") is False
        and bootstrap.get("visibleActivityRequired") is False
    )
    emit(
        checks,
        "R-SEC-06",
        "live manifest confirms wake-only bootstrap with no credential transfer",
        bootstrap_ok,
        {
            "wakeOnly": bootstrap.get("wakeOnly") if isinstance(bootstrap, dict) else None,
            "credentialTransfer": bootstrap.get("credentialTransfer") if isinstance(bootstrap, dict) else None,
            "visibleActivityRequired": bootstrap.get("visibleActivityRequired") if isinstance(bootstrap, dict) else None,
        },
    )

    api_no_shizuku = (
        isinstance(health_payload, dict)
        and health_payload.get("shizukuRequired") is False
        and isinstance(capabilities_payload, dict)
        and capabilities_payload.get("normalActionsRequireShizuku") is False
        and capabilities_payload.get("normalActionsRequireUi") is False
    )
    emit(
        checks,
        "R-SEC-04",
        "ordinary API actions advertise headless operation without Shizuku",
        api_no_shizuku,
        {
            "healthShizukuRequired": health_payload.get("shizukuRequired") if isinstance(health_payload, dict) else None,
            "normalActionsRequireShizuku": capabilities_payload.get("normalActionsRequireShizuku") if isinstance(capabilities_payload, dict) else None,
            "normalActionsRequireUi": capabilities_payload.get("normalActionsRequireUi") if isinstance(capabilities_payload, dict) else None,
        },
    )
    return {
        "baseLoopback": base_loopback,
        "status": "OK" if health_ok else "BRIDGE_FAILURE",
        "health": {"httpStatus": health.get("status"), "elapsedMs": health.get("elapsedMs")},
        "capabilities": {"httpStatus": capabilities.get("status"), "elapsedMs": capabilities.get("elapsedMs")},
        "manifest": {"httpStatus": manifest.get("status"), "elapsedMs": manifest.get("elapsedMs")},
        "unauthenticated401": unauth_ok,
    }, client


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-only",
        action="store_true",
        help="run source/security contract checks without contacting localhost services",
    )
    args = parser.parse_args()

    checks: List[Check] = []
    source_result = source_contracts(checks)
    mode = "source-only" if args.source_only else "full"

    token_file = Path(os.environ.get("MOVIA_TOKEN_FILE", str(Path.home() / ".config/movia-agent/token")))
    if args.source_only:
        token, token_error = None, None
    else:
        token, token_error = load_token(token_file)
        if token_error:
            # runtime_security emits the required, non-secret failure detail;
            # keep this field only for the machine summary and never include
            # token data.
            token = None

    agent_url = os.environ.get("MOVIA_BASE_URL", AGENT_DEFAULT_URL).rstrip("/")
    http_timeout, timeout_error = parse_positive_float("MOVIA_HTTP_TIMEOUT", 5.0, 60.0)
    operation_timeout, operation_timeout_error = parse_positive_float(
        "MOVIA_FINAL_OPERATION_TIMEOUT",
        75.0,
        300.0,
        aliases=("MOVIA_OPERATION_TIMEOUT",),
    )
    poll_interval, poll_interval_error = parse_positive_float("MOVIA_FINAL_POLL_INTERVAL", 0.25, 10.0)
    settle_seconds, settle_error = parse_positive_float("MOVIA_FINAL_STREAM_SETTLE_SECONDS", 3.0, 30.0)
    config_errors = [
        error
        for error in (token_error if not args.source_only else None, timeout_error, operation_timeout_error, poll_interval_error, settle_error)
        if error
    ]
    if config_errors and not args.source_only:
        emit(checks, "R-CONFIG-01", "final acceptance runtime configuration is valid", False, {"errors": config_errors})
    elif args.source_only:
        emit(
            checks,
            "R-CONFIG-01",
            "runtime checks are intentionally skipped in source-only mode",
            True,
            {"status": "NOT_RUN", "reason": "--source-only"},
            required=False,
            status="NOT_RUN",
        )

    security_result: Dict[str, Any]
    client: Optional[HttpClient]
    catalog_result: Dict[str, Any]
    performance_result: Dict[str, Any]
    if args.source_only:
        security_result = {"status": "NOT_RUN", "reason": "--source-only"}
        catalog_result = {
            "status": "NOT_RUN",
            "pass": True,
            "available": False,
            "syncTriggered": False,
            "reason": "--source-only",
        }
        performance_result = {"status": "NOT_RUN", "coverageStatus": "NOT_RUN", "timeToResolveMs": None, "timeToOperationCompletionMs": None}
        for check_id, name in (
            ("R-SEC-01", "runtime agent loopback/authentication checks"),
            ("C-FRESH-01", "catalog freshness query"),
            ("P-PERF-01", "headless playback performance/coverage probe"),
        ):
            emit(
                checks,
                check_id,
                name,
                True,
                {"status": "NOT_RUN", "reason": "--source-only"},
                required=False,
                status="NOT_RUN",
            )
    else:
        security_result, client = runtime_security(
            checks,
            base_url=agent_url,
            token=token,
            timeout=http_timeout,
        )
        catalog_result = catalog_freshness(checks, timeout=http_timeout)
        if client is not None and token is not None and security_result.get("status") == "OK":
            performance_result = playback_probe(
                client,
                checks,
                timeout_seconds=operation_timeout,
                poll_interval_seconds=poll_interval,
                settle_seconds=settle_seconds,
            )
        else:
            performance_result = {
                "status": "NOT_RUN",
                "failureClass": "runtime_preflight_failure",
                "coverageStatus": "pipeline_failure",
                "timeToResolveMs": None,
                "timeToOperationCompletionMs": None,
                "streamCount": 0,
                "uniqueVoices": [],
                "uniqueQualities": [],
            }
            emit(
                checks,
                "P-PERF-01",
                "headless playback performance/coverage probe can run after runtime preflight",
                False,
                performance_result,
            )

    required_checks = [item for item in checks if item.get("required", True)]
    passed = all(item.get("pass") is True for item in required_checks)
    result = {
        "schemaVersion": JSON_SCHEMA_VERSION,
        "script": SCRIPT,
        "pass": passed,
        "mode": mode,
        "summary": {
            "requiredChecks": len(required_checks),
            "requiredPassed": sum(1 for item in required_checks if item.get("pass") is True),
            "requiredFailed": sum(1 for item in required_checks if item.get("pass") is not True),
            "optionalChecks": sum(1 for item in checks if not item.get("required", True)),
        },
        "sourceContracts": source_result,
        "security": security_result,
        "catalogFreshness": catalog_result,
        "performance": performance_result,
        "compatibility": {
            "priorGates": [
                "01_headless_cold",
                "02_smoke",
                "03_benchmark",
                "04_operation",
                "05_breaking_bad",
                "06_mcp_inventory",
            ],
            "additive": True,
            "doesNotInvoke": [
                "rish",
                "adb",
                "am force-stop",
                "UI clicks",
                "Android settings changes",
                "catalog sync trigger",
            ],
        },
        "checks": redact(checks),
    }
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
