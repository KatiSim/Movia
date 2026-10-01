"""Owned, bounded data profile for the imported client's request contract.

The legacy byte scan is a weighted sum, so its exact result can be evaluated
from 256 counts and position weights. No installed app, APK lookup, Android
runtime or neighbouring project is consulted at runtime. This does not grant
access to providers that reject a request or require an account.
"""
from __future__ import annotations

import json
import re
from functools import lru_cache
from pathlib import Path
from typing import Any

MODULUS = 65_521
PROFILE_PATH = Path(__file__).with_name("zona-client-profile.json")
MAX_PROFILE_BYTES = 20_000
MAX_ARTIFACT_BYTES = 128 * 1024 * 1024


def validate_profile(value: Any) -> dict:
    if not isinstance(value, dict) or set(value) != {
        "format", "artifact_sha256", "artifact_bytes", "counts", "position_weights"
    }:
        raise ValueError("Invalid client profile fields")
    if value["format"] != "movia-zona-byte-profile-v1":
        raise ValueError("Unsupported client profile format")
    if not isinstance(value["artifact_sha256"], str) or not re.fullmatch(
        r"[0-9a-f]{64}", value["artifact_sha256"]
    ):
        raise ValueError("Invalid artifact digest")
    size = value["artifact_bytes"]
    if type(size) is not int or not 0 < size <= MAX_ARTIFACT_BYTES:
        raise ValueError("Invalid artifact size")
    for key, upper in (("counts", size + 1), ("position_weights", MODULUS)):
        items = value[key]
        if not isinstance(items, list) or len(items) != 256 or any(
            type(item) is not int or not 0 <= item < upper for item in items
        ):
            raise ValueError("Invalid profile vector")
    if sum(value["counts"]) != size:
        raise ValueError("Invalid profile count total")
    if sum(value["position_weights"]) % MODULUS != size * (size + 1) // 2 % MODULUS:
        raise ValueError("Invalid profile weight total")
    return value


@lru_cache(maxsize=1)
def load_profile() -> dict:
    if not 0 < PROFILE_PATH.stat().st_size <= MAX_PROFILE_BYTES:
        raise ValueError("Client profile exceeds size limit")
    with PROFILE_PATH.open("rb") as source:
        raw = source.read(MAX_PROFILE_BYTES + 1)
    if len(raw) > MAX_PROFILE_BYTES:
        raise ValueError("Client profile exceeds size limit")
    return validate_profile(json.loads(raw))


def checksum_for_day(profile: dict, day: int) -> int:
    counts, weights = profile["counts"], profile["position_weights"]
    offset = int(day) % 256
    sum_b = (1 + sum(counts[b] * ((b + offset) % 256) for b in range(256))) % MODULUS
    sum_a = (profile["artifact_bytes"] + sum(
        weights[b] * ((b + offset) % 256) for b in range(256)
    )) % MODULUS
    return (sum_a << 16) + sum_b


def profile_for_bytes(raw: bytes) -> dict:
    """Small fixtures only; production profiles use the streaming build tool."""
    counts, weights = [0] * 256, [0] * 256
    for index, byte in enumerate(raw):
        counts[byte] += 1
        weights[byte] = (weights[byte] + len(raw) - index) % MODULUS
    import hashlib
    return validate_profile({
        "format": "movia-zona-byte-profile-v1",
        "artifact_sha256": hashlib.sha256(raw).hexdigest(),
        "artifact_bytes": len(raw), "counts": counts, "position_weights": weights,
    })
