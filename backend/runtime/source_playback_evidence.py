"""Non-secret decoder evidence at the scoped catalog read boundary."""
import math
import re
import time

def finite_number(value, default=None):
    try:
        number = float(value)
        return number if math.isfinite(number) else default
    except (TypeError, ValueError, OverflowError):
        return default

def source_runtime_evidence(source, now, expiry_margin=15):
    status = str(source.get("verificationStatus") or "DISCOVERED").upper()
    failure_at = finite_number(source.get("lastFailureAt"))
    # A transient failure is eligible for a fresh trial after 30 seconds.
    # Historical first-frame evidence does not certify that new trial.
    if status == "COOLDOWN" and failure_at is not None and now >= failure_at + 30:
        status = "DISCOVERED"
    expiry = source.get("expiresAt")
    if expiry is not None:
        parsed = finite_number(expiry)
        if parsed is None or parsed <= now + expiry_margin:
            status = "EXPIRED"
    method = str(source.get("verificationMethod") or "NONE").upper()
    decoded = status == "VERIFIED" and method == "MEDIA3_SUCCESS"
    health = finite_number(source.get("healthScore"), 0.5)
    failures = max(0, int(finite_number(source.get("consecutiveFailures"), 0)))
    startup = finite_number(source.get("startupLatencyMs"))
    return {
        "sourceId": source.get("sourceId"),
        "verificationStatus": status,
        "verificationMethod": method,
        "decodedPlayback": decoded,
        "lastSuccessAt": source.get("lastSuccessAt"),
        "lastFailureAt": source.get("lastFailureAt"),
        "lastCheckedAt": source.get("lastCheckedAt"),
        "healthScore": min(1.0, max(0.0, health)),
        "consecutiveFailures": failures,
        "startupLatencyMs": max(0, startup) if decoded and startup is not None else None,
        "expiresAt": expiry,
    }

def map_manifest_audio_identity(candidate, actual_tracks):
    """An ordinal in a provider list is not an ordinal in decoder track groups."""
    metadata = candidate.get("transport_metadata") or candidate.get("transportMetadata") or {}
    if not isinstance(metadata, dict) or not metadata.get("manifest_verified"):
        return None
    expected = metadata.get("manifest_audio_track")
    if not isinstance(expected, dict):
        return None
    label = str(expected.get("name") or expected.get("label") or "").strip().casefold()
    language = str(expected.get("language") or "").strip().casefold()
    if not label or label in {"audio", "unknown", "не указано"}:
        return None
    matches = []
    for track in actual_tracks:
        if not isinstance(track, dict):
            continue
        actual_label = str(track.get("name") or track.get("label") or "").strip().casefold()
        actual_language = str(track.get("language") or "").strip().casefold()
        if actual_label != label or (language and actual_language != language):
            continue
        expected_group = str(expected.get("groupId") or "").strip()
        actual_group = str(track.get("groupId") or "").strip()
        if expected_group and actual_group and expected_group != actual_group:
            continue
        matches.append(track)
    return dict(matches[0]) if len(matches) == 1 else None


def sanitize_media3_failure_payload(payload, now=None):
    if not isinstance(payload, dict) or set(payload) != {"sourceId", "reason", "observedAt"}:
        raise ValueError("invalid_failure_fields")
    source_id = payload["sourceId"]
    if not isinstance(source_id, str) or not re.fullmatch(r"src:[A-Za-z0-9:_-]{1,124}", source_id):
        raise ValueError("invalid_source_id")
    reason = payload["reason"]
    if not isinstance(reason, str) or reason not in {"NETWORK", "NON_NETWORK"}:
        raise ValueError("invalid_failure_reason")
    observed = payload["observedAt"]
    clock = time.time() if now is None else now
    if isinstance(observed, bool) or not isinstance(observed, (float, int)) or not math.isfinite(observed) or not clock - 300 <= observed <= clock + 5:
        raise ValueError("invalid_observation_time")
    return {"sourceId": source_id, "reason": reason, "cooldown": reason == "NETWORK", "observedAt": observed}
