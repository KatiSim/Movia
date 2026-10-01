#!/usr/bin/env python3
"""Candidate-level Source Truth policy audit.

Pure/offline: consumes already-ranked legacy stream dictionaries enriched with
``sourceTruth``. It never opens locators, resolves providers, or changes the
legacy order. The first ELIGIBLE row is only a shadow recommendation.
"""
from __future__ import annotations

from typing import Any, Dict, Iterable, List, Optional, Tuple

from playback_resolver_shadow_audit import canonical_quality, normalize_voice

ELIGIBLE = "ELIGIBLE"
NO_EVIDENCE = "NO_EVIDENCE"
QUALITY_EVIDENCE_MISSING = "QUALITY_EVIDENCE_MISSING"
QUALITY_NOT_AVAILABLE = "QUALITY_NOT_AVAILABLE"
AUDIO_EVIDENCE_MISSING = "AUDIO_EVIDENCE_MISSING"
AUDIO_MAPPING_MISSING = "AUDIO_MAPPING_MISSING"
AUDIO_NOT_AVAILABLE = "AUDIO_NOT_AVAILABLE"
VOICE_UNPROVEN = "VOICE_UNPROVEN"
VOICE_NOT_MATCH = "VOICE_NOT_MATCH"

_ALLOWED_LANGUAGES = {"ru", "uk"}
_AUTO_VALUES = {"", "auto", "any", "не указано", "неуказано"}


def _text(value: Any) -> str:
    return str(value or "").strip()


def _stream_id(row: Dict[str, Any]) -> str:
    return _text(row.get("stream_id") or row.get("streamId"))


def _provider(row: Dict[str, Any]) -> str:
    return _text(row.get("provider") or row.get("source"))


def _audio_language(value: Any) -> str:
    raw = _text(value).casefold()
    if raw in {"ru", "rus", "russian", "русский", "русская"} or raw.startswith("ru-"):
        return "ru"
    if raw in {"uk", "ukr", "ua", "ukrainian", "украинский", "український", "українська"} or raw.startswith(("uk-", "ua-")):
        return "uk"
    if raw in {"en", "eng", "english", "английский", "английская"} or raw.startswith("en-"):
        return "en"
    if raw:
        return raw.split("-", 1)[0]
    return ""


def _track_summary(track: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if not isinstance(track, dict):
        return None
    return {
        "name": _text(track.get("name") or track.get("label") or track.get("title")) or None,
        "language": _audio_language(track.get("language") or track.get("lang")) or None,
        "groupId": _text(track.get("groupId")) or None,
        "default": bool(track.get("default", False)),
        "autoselect": bool(track.get("autoselect", False)),
    }


def _actual_tracks(truth: Dict[str, Any]) -> List[Dict[str, Any]]:
    return [dict(v) for v in (truth.get("actualAudioTracks") or []) if isinstance(v, dict)]


def _effective_track(truth: Dict[str, Any], requested_audio: Any) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    mapped = truth.get("mappedAudioTrack")
    if (
        isinstance(mapped, dict)
        and _text(truth.get("audioTrackMapping")) == "PROVIDER_INDEX_TO_MANIFEST_ORDER"
    ):
        return dict(mapped), "PROVIDER_INDEX_TO_MANIFEST_ORDER"

    tracks = _actual_tracks(truth)
    if len(tracks) == 1:
        return tracks[0], "SINGLE_PHYSICAL_TRACK"

    requested_key = normalize_voice(requested_audio)
    if requested_key in _AUTO_VALUES:
        defaults = [track for track in tracks if bool(track.get("default", False))]
        if len(defaults) == 1:
            return defaults[0], "MANIFEST_DEFAULT_TRACK"
    return None, None


def _actual_qualities(truth: Dict[str, Any]) -> List[str]:
    values = list(truth.get("actualQualities") or [])
    actual = _text(truth.get("actualQuality"))
    if actual:
        values.append(actual)
    out: List[str] = []
    for value in values:
        q = canonical_quality(value)
        if q and q not in out:
            out.append(q)
    return out


def candidate_verdict(
    row: Dict[str, Any],
    *,
    requested_quality: Any = "Auto",
    requested_audio: Any = "Auto",
) -> Dict[str, Any]:
    truth = row.get("sourceTruth")
    base = {
        "streamId": _stream_id(row) or None,
        "provider": _provider(row) or None,
        "providerQuality": _text(row.get("quality")) or None,
        "providerVoice": _text(row.get("voice") or row.get("translation")) or None,
        "sourceId": None,
        "status": NO_EVIDENCE,
        "proof": None,
        "actualQualities": [],
        "mappedAudioTrack": None,
    }
    if not isinstance(truth, dict) or _text(truth.get("verificationStatus")).upper() != "VERIFIED":
        return base

    base["sourceId"] = _text(truth.get("sourceId")) or None
    qualities = _actual_qualities(truth)
    base["actualQualities"] = qualities
    requested_q = canonical_quality(requested_quality)
    if requested_q:
        if not qualities:
            base["status"] = QUALITY_EVIDENCE_MISSING
            return base
        if requested_q not in qualities:
            base["status"] = QUALITY_NOT_AVAILABLE
            return base

    tracks = _actual_tracks(truth)
    effective_track, proof = _effective_track(truth, requested_audio)
    base["proof"] = proof
    base["mappedAudioTrack"] = _track_summary(effective_track)
    request_key = normalize_voice(requested_audio)
    request_lang = _audio_language(requested_audio)
    is_auto = request_key in _AUTO_VALUES

    if effective_track is None:
        base["status"] = AUDIO_EVIDENCE_MISSING if not tracks else (
            AUDIO_MAPPING_MISSING if is_auto or request_lang in _ALLOWED_LANGUAGES else VOICE_UNPROVEN
        )
        return base

    effective_lang = _audio_language(effective_track.get("language") or effective_track.get("lang"))
    if is_auto:
        base["status"] = ELIGIBLE if effective_lang in _ALLOWED_LANGUAGES else AUDIO_NOT_AVAILABLE
        return base

    if request_lang in _ALLOWED_LANGUAGES:
        base["status"] = ELIGIBLE if effective_lang == request_lang else AUDIO_NOT_AVAILABLE
        return base

    wanted = request_key
    candidate_voice = normalize_voice(row.get("voice") or row.get("translation"))
    track_label = normalize_voice(effective_track.get("name") or effective_track.get("label") or effective_track.get("title"))
    if wanted not in {candidate_voice, track_label}:
        base["status"] = VOICE_NOT_MATCH
        return base
    base["status"] = ELIGIBLE if effective_lang in _ALLOWED_LANGUAGES else AUDIO_NOT_AVAILABLE
    return base


def audit_candidate_policy(
    streams: Iterable[Dict[str, Any]],
    *,
    requested_quality: Any = "Auto",
    requested_audio: Any = "Auto",
) -> Dict[str, Any]:
    rows = [row for row in streams if isinstance(row, dict)]
    verdicts = [
        candidate_verdict(row, requested_quality=requested_quality, requested_audio=requested_audio)
        for row in rows
    ]
    shadow_index = next((i for i, verdict in enumerate(verdicts) if verdict["status"] == ELIGIBLE), None)
    shadow_top = verdicts[shadow_index] if shadow_index is not None else None
    legacy_top = verdicts[0] if verdicts else None
    unknown_statuses = {NO_EVIDENCE, QUALITY_EVIDENCE_MISSING, AUDIO_EVIDENCE_MISSING, AUDIO_MAPPING_MISSING, VOICE_UNPROVEN}
    if shadow_top is not None:
        decision = "AVAILABLE"
    elif any(v["status"] in unknown_statuses for v in verdicts):
        decision = "INDETERMINATE"
    else:
        decision = "NOT_AVAILABLE"
    counts: Dict[str, int] = {"totalCandidates": len(verdicts), "eligibleCandidates": 0}
    for verdict in verdicts:
        status = verdict["status"]
        counts[status] = counts.get(status, 0) + 1
        if status == ELIGIBLE:
            counts["eligibleCandidates"] += 1
    return {
        "decision": decision,
        "requestedQuality": _text(requested_quality) or "Auto",
        "requestedAudio": _text(requested_audio) or "Auto",
        "legacyTop": legacy_top,
        "shadowTop": shadow_top,
        "shadowTopOrdinal": shadow_index,
        "wouldChangeTop": bool(
            legacy_top
            and shadow_top
            and legacy_top.get("streamId")
            and legacy_top.get("streamId") != shadow_top.get("streamId")
        ),
        "counts": counts,
        "candidates": verdicts,
    }


def _policy_error_code(verdicts: List[Dict[str, Any]], decision: str) -> Optional[str]:
    if decision == "AVAILABLE":
        return None
    statuses = {str(v.get("status") or "") for v in verdicts}
    if decision == "INDETERMINATE":
        if QUALITY_EVIDENCE_MISSING in statuses:
            return "QUALITY_EVIDENCE_MISSING"
        if AUDIO_MAPPING_MISSING in statuses:
            return "AUDIO_MAPPING_MISSING"
        if AUDIO_EVIDENCE_MISSING in statuses:
            return "AUDIO_EVIDENCE_MISSING"
        if VOICE_UNPROVEN in statuses:
            return "VOICE_UNPROVEN"
        return "SOURCE_TRUTH_INCOMPLETE"
    if statuses and statuses <= {QUALITY_NOT_AVAILABLE}:
        return "QUALITY_NOT_AVAILABLE"
    if AUDIO_NOT_AVAILABLE in statuses:
        return "AUDIO_NOT_AVAILABLE"
    if VOICE_NOT_MATCH in statuses:
        return "VOICE_NOT_AVAILABLE"
    if QUALITY_NOT_AVAILABLE in statuses:
        return "QUALITY_NOT_AVAILABLE"
    return "NO_ELIGIBLE_CANDIDATE"


def select_candidate_policy(
    streams: Iterable[Dict[str, Any]],
    *,
    requested_quality: Any = "Auto",
    requested_audio: Any = "Auto",
) -> Dict[str, Any]:
    """Return a redacted dry-run selection contract for the future resolver.

    Input order is the already-ranked legacy order. The function never returns
    URL/locator data; selection identity is the stable streamId + sourceId.
    Explicit quality/audio requests never fall back to an ineligible candidate.
    """
    audit = audit_candidate_policy(
        streams, requested_quality=requested_quality, requested_audio=requested_audio
    )
    candidates = audit.get("candidates") or []
    decision = str(audit.get("decision") or "INDETERMINATE")
    selected = audit.get("shadowTop") if decision == "AVAILABLE" else None
    selected_ordinal = audit.get("shadowTopOrdinal") if selected is not None else None
    blocking = sorted({
        str(v.get("status") or "")
        for v in candidates
        if isinstance(v, dict) and str(v.get("status") or "") != ELIGIBLE
    })
    return {
        "status": decision,
        "errorCode": _policy_error_code(candidates, decision),
        "requestedQuality": audit.get("requestedQuality"),
        "requestedAudio": audit.get("requestedAudio"),
        "selectedStreamId": selected.get("streamId") if isinstance(selected, dict) else None,
        "selectedSourceId": selected.get("sourceId") if isinstance(selected, dict) else None,
        "selectedOrdinal": selected_ordinal,
        "selectedCandidate": selected,
        "wouldChangeTop": bool(audit.get("wouldChangeTop")),
        "blockingStatuses": blocking,
        "counts": audit.get("counts") or {},
    }
