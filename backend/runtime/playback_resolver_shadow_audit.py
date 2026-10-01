#!/usr/bin/env python3
"""Offline resolver audit over response streams enriched with Source Truth.

This module never resolves providers or probes media URLs. It only compares
legacy stream claims with verified Source Truth facts already attached by the
backend response overlay.
"""
from __future__ import annotations

import json
import re
from typing import Any, Dict, Iterable, List


def _text(value: Any) -> str:
    return str(value or "").strip()


def canonical_quality(value: Any) -> str:
    text = _text(value).casefold().replace(" ", "")
    if not text or text in {"auto", "any", "unknown", "n/a", "неуказано"}:
        return ""
    if "2160" in text or "4k" in text or "uhd" in text:
        return "2160p"
    if "1440" in text or "2k" in text:
        return "1440p"
    match = re.search(r"(?<!\d)(1080|720|576|540|534|480|360|306|270|266|240|144)(?!\d)", text)
    return f"{match.group(1)}p" if match else text


def normalize_voice(value: Any) -> str:
    text = _text(value).casefold().replace("ё", "е")
    return " ".join(re.sub(r"[^0-9a-zа-яіїєґ]+", " ", text).split())


def _physical_track_labels(truth: Dict[str, Any]) -> set[str]:
    labels: set[str] = set()
    for track in truth.get("actualAudioTracks") or []:
        if not isinstance(track, dict):
            continue
        label = normalize_voice(track.get("name") or track.get("label") or track.get("title"))
        if label:
            labels.add(label)
    return labels


def audit_streams(streams: Iterable[Dict[str, Any]]) -> Dict[str, Any]:
    rows = [row for row in streams if isinstance(row, dict)]
    counts = {
        "totalStreams": len(rows),
        "verifiedOverlayStreams": 0,
        "qualityEvidenceStreams": 0,
        "nonConcreteQualityClaims": 0,
        "concreteQualityClaims": 0,
        "concreteQualityClaimsConsistent": 0,
        "concreteQualityClaimsMismatch": 0,
        "allowedAudioEvidenceStreams": 0,
        "providerVoiceClaims": 0,
        "providerVoicePhysicallyProvenExact": 0,
        "providerVoiceMappedToPhysicalTrack": 0,
        "providerVoiceMappedToAllowedTrack": 0,
        "providerVoiceMappedToDisallowedTrack": 0,
        "providerVoiceUnproven": 0,
    }
    quality_mismatches: List[Dict[str, Any]] = []
    voice_unproven: List[Dict[str, Any]] = []

    for ordinal, row in enumerate(rows):
        truth = row.get("sourceTruth")
        if not isinstance(truth, dict) or truth.get("verificationStatus") != "VERIFIED":
            continue
        counts["verifiedOverlayStreams"] += 1

        actual_qualities = sorted({
            canonical_quality(value)
            for value in (truth.get("actualQualities") or [])
            if canonical_quality(value)
        })
        provider_quality = canonical_quality(row.get("quality"))
        if actual_qualities:
            counts["qualityEvidenceStreams"] += 1
            if not provider_quality:
                counts["nonConcreteQualityClaims"] += 1
            else:
                counts["concreteQualityClaims"] += 1
                if provider_quality in actual_qualities:
                    counts["concreteQualityClaimsConsistent"] += 1
                else:
                    counts["concreteQualityClaimsMismatch"] += 1
                    quality_mismatches.append({
                        "ordinal": ordinal,
                        "provider": _text(row.get("provider") or row.get("source")),
                        "streamId": _text(row.get("stream_id") or row.get("streamId")),
                        "providerQuality": _text(row.get("quality")),
                        "actualQualities": actual_qualities,
                    })

        allowed_languages = sorted({
            _text(value).casefold()
            for value in (truth.get("allowedAudioLanguages") or [])
            if _text(value).casefold() in {"ru", "uk"}
        })
        if allowed_languages:
            counts["allowedAudioEvidenceStreams"] += 1

        provider_voice = normalize_voice(row.get("voice") or row.get("translation"))
        if provider_voice and provider_voice not in {"auto", "any", "не указано", "неуказано"}:
            counts["providerVoiceClaims"] += 1
            physical_labels = _physical_track_labels(truth)
            mapped_track = truth.get("mappedAudioTrack")
            mapped_track = mapped_track if isinstance(mapped_track, dict) else None
            mapping_method = _text(truth.get("audioTrackMapping"))
            if provider_voice in physical_labels:
                counts["providerVoicePhysicallyProvenExact"] += 1
            elif mapped_track is not None and mapping_method == "PROVIDER_INDEX_TO_MANIFEST_ORDER":
                counts["providerVoiceMappedToPhysicalTrack"] += 1
                mapped_language = _text(mapped_track.get("language")).casefold()
                if mapped_language.startswith("ru") or mapped_language.startswith(("uk", "ua")):
                    counts["providerVoiceMappedToAllowedTrack"] += 1
                else:
                    counts["providerVoiceMappedToDisallowedTrack"] += 1
            else:
                counts["providerVoiceUnproven"] += 1
                voice_unproven.append({
                    "ordinal": ordinal,
                    "provider": _text(row.get("provider") or row.get("source")),
                    "streamId": _text(row.get("stream_id") or row.get("streamId")),
                    "providerVoice": _text(row.get("voice") or row.get("translation")),
                    "allowedAudioLanguages": allowed_languages,
                    "physicalTrackLabels": sorted(physical_labels),
                })

    verified = counts["verifiedOverlayStreams"]
    counts["verifiedOverlayCoveragePct"] = round(100.0 * verified / len(rows), 2) if rows else 0.0
    counts["qualityEvidencePctOfVerified"] = round(
        100.0 * counts["qualityEvidenceStreams"] / verified, 2
    ) if verified else 0.0
    counts["allowedAudioEvidencePctOfVerified"] = round(
        100.0 * counts["allowedAudioEvidenceStreams"] / verified, 2
    ) if verified else 0.0
    return {
        "counts": counts,
        "qualityMismatches": quality_mismatches,
        "providerVoiceUnproven": voice_unproven,
    }


def main() -> int:
    import sys
    payload = json.load(sys.stdin)
    streams = payload.get("streams") if isinstance(payload, dict) else payload
    result = audit_streams(streams or [])
    json.dump(result, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
