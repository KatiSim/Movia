#!/usr/bin/env python3
"""Fail-closed network/battery guard for Movia background enrichment.

Bulk enrichment must never run on a metered default network.  The optional
mobile quota is intentionally conservative and uses Android's UID-level
physical cellular accounting for Termux.  On-demand playback is not governed
by this module.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Optional

TERMUX_PACKAGE = "com.termux"
DEFAULT_MONTHLY_GIB = 4.0
DEFAULT_DAILY_MIB = 128.0
TEMPORARY_BLOCK_EXIT = 75

_UID_RE = re.compile(r"package:com\.termux\s+uid:(\d+)")
_BUCKET_RE = re.compile(r"\bst=(\d+)\b.*?\brb=(\d+)\b.*?\btb=(\d+)\b")


@dataclass(frozen=True)
class BudgetDecision:
    allowed: bool
    reason: str
    unmetered_default: bool
    charging: bool
    uid: Optional[int] = None
    mobile_month_bytes: Optional[int] = None
    mobile_day_bytes: Optional[int] = None
    month_limit_bytes: int = 0
    day_limit_bytes: int = 0


def _run_shell(command: str, timeout: float = 8.0) -> str:
    completed = subprocess.run(
        ["rish", "-c", command],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        timeout=timeout,
    )
    return completed.stdout


def parse_termux_uid(package_output: str) -> Optional[int]:
    match = _UID_RE.search(package_output or "")
    return int(match.group(1)) if match else None


def parse_default_unmetered(netstats_output: str) -> bool:
    in_active_uid = False
    for raw in (netstats_output or "").splitlines():
        line = raw.strip()
        if line == "Active UID interfaces:":
            in_active_uid = True
            continue
        if in_active_uid and line == "All wifi interfaces:":
            break
        if not in_active_uid or not line.startswith("iface="):
            continue
        if (
            "defaultNetwork=true" in line
            and "metered=false" in line
            and re.search(r"transports=\{[^}]*\b1\b", line)
        ):
            return True
    return False


def parse_charging(battery_output: str) -> bool:
    text = (battery_output or "").lower()
    powered = any(
        f"{kind} powered: true" in text
        for kind in ("ac", "usb", "wireless", "dock")
    )
    status = re.search(r"(?m)^\s*status:\s*(\d+)\s*$", text)
    # BatteryManager: 2=CHARGING, 5=FULL.
    return powered or (status is not None and int(status.group(1)) in {2, 5})


def parse_mobile_uid_bytes(detail_output: str, uid: int, since_epoch: int) -> int:
    total = 0
    active = False
    uid_marker = f"uid={int(uid)}"
    for raw in (detail_output or "").splitlines():
        line = raw.strip()
        if line.startswith("ident=["):
            # Count only the physical cellular network class (type=0).  This
            # avoids double-counting VPN/VCN stacked transports (type=17).
            active = (
                uid_marker in line
                and "type=0" in line
                and "metered=true" in line
            )
            continue
        if not active:
            continue
        match = _BUCKET_RE.search(line)
        if not match:
            continue
        started, rx, tx = map(int, match.groups())
        # Buckets are two hours on this device.  Counting the whole bucket that
        # starts inside the period is deterministic and slightly conservative.
        if started >= int(since_epoch):
            total += rx + tx
    return total


def _period_starts(now: Optional[float] = None) -> tuple[int, int]:
    dt = datetime.fromtimestamp(time.time() if now is None else now, timezone.utc)
    month = datetime(dt.year, dt.month, 1, tzinfo=timezone.utc)
    day = datetime(dt.year, dt.month, dt.day, tzinfo=timezone.utc)
    return int(month.timestamp()), int(day.timestamp())


def evaluate_live(
    *,
    require_charging: bool = True,
    allow_metered: bool = False,
    monthly_gib: float = DEFAULT_MONTHLY_GIB,
    daily_mib: float = DEFAULT_DAILY_MIB,
) -> BudgetDecision:
    month_limit = max(1, int(float(monthly_gib) * 1024**3))
    day_limit = max(1, int(float(daily_mib) * 1024**2))
    try:
        netstats = _run_shell("dumpsys netstats")
        battery = _run_shell("dumpsys battery")
    except Exception as exc:
        return BudgetDecision(False, f"probe_failed:{type(exc).__name__}", False, False,
                              month_limit_bytes=month_limit, day_limit_bytes=day_limit)

    unmetered = parse_default_unmetered(netstats)
    charging = parse_charging(battery)
    if require_charging and not charging:
        return BudgetDecision(False, "not_charging", unmetered, charging,
                              month_limit_bytes=month_limit, day_limit_bytes=day_limit)
    if unmetered:
        return BudgetDecision(True, "unmetered", True, charging,
                              month_limit_bytes=month_limit, day_limit_bytes=day_limit)
    if not allow_metered:
        return BudgetDecision(False, "metered_default", False, charging,
                              month_limit_bytes=month_limit, day_limit_bytes=day_limit)

    try:
        package_output = _run_shell("cmd package list packages -U | grep -E 'package:com.termux([[:space:]]|$)'")
        uid = parse_termux_uid(package_output)
        if uid is None:
            raise RuntimeError("termux_uid_not_found")
        detail_a = _run_shell("dumpsys netstats detail", timeout=15.0)
        detail_b = _run_shell("dumpsys netstats detail", timeout=15.0)
        month_start, day_start = _period_starts()
        month_bytes = max(
            parse_mobile_uid_bytes(detail_a, uid, month_start),
            parse_mobile_uid_bytes(detail_b, uid, month_start),
        )
        day_bytes = max(
            parse_mobile_uid_bytes(detail_a, uid, day_start),
            parse_mobile_uid_bytes(detail_b, uid, day_start),
        )
    except Exception as exc:
        return BudgetDecision(False, f"quota_probe_failed:{type(exc).__name__}", False, charging,
                              month_limit_bytes=month_limit, day_limit_bytes=day_limit)

    if month_bytes >= month_limit:
        reason = "monthly_mobile_budget_exhausted"
        allowed = False
    elif day_bytes >= day_limit:
        reason = "daily_mobile_budget_exhausted"
        allowed = False
    else:
        reason = "metered_within_budget"
        allowed = True
    return BudgetDecision(
        allowed, reason, False, charging, uid=uid,
        mobile_month_bytes=month_bytes, mobile_day_bytes=day_bytes,
        month_limit_bytes=month_limit, day_limit_bytes=day_limit,
    )


def background_bulk_allowed() -> BudgetDecision:
    return evaluate_live(
        require_charging=os.environ.get("MOVIA_BACKGROUND_REQUIRE_CHARGING", "1") != "0",
        allow_metered=os.environ.get("MOVIA_BACKGROUND_ALLOW_METERED", "0") == "1",
        monthly_gib=float(os.environ.get("MOVIA_BACKGROUND_MONTHLY_GIB", str(DEFAULT_MONTHLY_GIB))),
        daily_mib=float(os.environ.get("MOVIA_BACKGROUND_DAILY_MIB", str(DEFAULT_DAILY_MIB))),
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--allow-metered", action="store_true")
    ap.add_argument("--no-charging-required", action="store_true")
    ap.add_argument("--monthly-gib", type=float, default=DEFAULT_MONTHLY_GIB)
    ap.add_argument("--daily-mib", type=float, default=DEFAULT_DAILY_MIB)
    args = ap.parse_args()
    decision = evaluate_live(
        require_charging=not args.no_charging_required,
        allow_metered=args.allow_metered,
        monthly_gib=args.monthly_gib,
        daily_mib=args.daily_mib,
    )
    print(json.dumps(asdict(decision), ensure_ascii=False, separators=(",", ":")))
    return 0 if decision.allowed else TEMPORARY_BLOCK_EXIT


if __name__ == "__main__":
    raise SystemExit(main())
