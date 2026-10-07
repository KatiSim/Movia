"""Authenticated native decoder feedback for cached, not-yet-indexed leaves.

No locator is accepted from the caller. Resolve the exact catalog variant and
match both locator and request-profile fingerprints before recording evidence.
"""
import hashlib,json,re
from stream_validation import bind_stream_identity,episode_coordinate

def feedback_fingerprints(row):
    locator=str(row.get("url") or row.get("playback_url") or "").strip()
    profile={
        "headers":row.get("headers") or {},
        "userAgent":row.get("user_agent") or row.get("userAgent"),
        "audio":row.get("audio_track_index",row.get("audioTrackIndex")),
        "video":row.get("video_track_index",row.get("videoTrackIndex")),
        "file":row.get("file_index",row.get("fileIndex")),
        "path":row.get("file_path",row.get("filePath")),
        "drm":row.get("drm_license_url",row.get("drmLicenseUrl")),
        "transport":row.get("transport"),
    }
    return {
        "native_feedback_locator_hash":hashlib.sha256(locator.encode()).hexdigest(),
        "native_feedback_profile_hash":hashlib.sha256(json.dumps(profile,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode()).hexdigest(),
    }

def attach_feedback_scope(row):
    result=dict(row)
    if str(row.get("stream_id") or row.get("streamId") or "").startswith("provider-item:v2:"):
        result["transport_metadata"]={**(row.get("transport_metadata") or row.get("transportMetadata") or {}),**feedback_fingerprints(row)}
    return result

def _resolve_native_variant(payload,load_card,content_filter,sanitize_observation):
    allowed={"mediaId","season","episode","streamId","locatorHash","profileHash","observation"}
    if not isinstance(payload,dict) or set(payload)!=allowed:raise ValueError("invalid_native_feedback")
    media=payload["mediaId"];sid=payload["streamId"]
    if not isinstance(media,str) or not re.fullmatch(r"[0-9]{1,12}",media):raise ValueError("invalid_media")
    if not isinstance(sid,str) or not re.fullmatch(r"provider-item:v2:[a-z0-9:_-]{1,160}",sid):raise ValueError("invalid_variant")
    season,episode=payload["season"],payload["episode"]
    if season is not None or episode is not None:
        if any(isinstance(x,bool) or not isinstance(x,int) or episode_coordinate(x)!=x for x in (season,episode)):raise ValueError("invalid_episode")
    for k in ("locatorHash","profileHash"):
        if not isinstance(payload[k],str) or not re.fullmatch(r"[0-9a-f]{64}",payload[k]):raise ValueError("invalid_fingerprint")
    observation=payload["observation"]
    if not isinstance(observation,dict) or "sourceId" in observation:raise ValueError("invalid_observation")
    facts=sanitize_observation(dict(observation,sourceId="src:native-variant"));facts.pop("sourceId")
    card=load_card(media,season,episode)
    if not card or str(card.get("id"))!=media:raise ValueError("catalog_identity_mismatch")
    kind=str(card.get("media_type") or card.get("mediaType") or card.get("type") or "").casefold()
    series=kind in {"tv","series","tv_series","serial","limited_series"}
    if series!=(season is not None):raise ValueError("content_kind_mismatch")
    rows=content_filter(card.get("streams") or [],dict(card,season=season,episode=episode))
    rows=bind_stream_identity(rows,catalog_media_id=media,title=card.get("title"),original_title=card.get("original_title") or card.get("originalTitle"),year=card.get("year"),media_type="tv" if series else "movie",season=season,episode=episode)
    matches=[]
    for row in rows:
        if row.get("stream_id")!=sid:continue
        if series and (row.get("season"),row.get("episode"))!=(season,episode):continue
        fp=feedback_fingerprints(row)
        if fp["native_feedback_locator_hash"]==payload["locatorHash"] and fp["native_feedback_profile_hash"]==payload["profileHash"]:matches.append(row)
    if len(matches)!=1:raise ValueError("variant_scope_mismatch")
    return media,season,episode,series,matches[0],facts

def record_native_variant_success(index,payload,load_card,content_filter,sanitize_observation):
    from source_playback_evidence import sanitize_media3_failure_payload
    import time
    def frame_facts(observation):
        fields = dict(observation)
        stamp = fields.pop("observedAt",time.time())
        checked = sanitize_media3_failure_payload({"sourceId":"src:native-variant",
            "reason":"NETWORK","observedAt":stamp})
        facts = sanitize_observation(fields)
        return dict(facts,observedAt=checked["observedAt"])
    media,season,episode,series,candidate,facts = _resolve_native_variant(payload,load_card,content_filter,frame_facts)
    result=index.verify_candidate(media,candidate,kind="EPISODE" if series else "MOVIE",season=season,episode=episode,
        discovery_method="PROVIDER_SEARCH",verification_method="MEDIA3_SUCCESS",success=True,
        startup_latency_ms=facts.get("startupLatencyMs"),actual_quality=facts.get("actualQuality"),
        actual_qualities=facts.get("actualQualities"),actual_audio_tracks=facts.get("actualAudioTracks"),
        expected_locator_hash=payload["locatorHash"],expected_profile_hash=payload["profileHash"],
        now=facts["observedAt"])
    source=next((x for x in result.get("sources",[]) if x.get("locatorHash")==payload["locatorHash"] and x.get("requestProfileHash")==payload["profileHash"] and str(x.get("provider") or "").casefold()==str(candidate.get("provider") or candidate.get("source") or "").casefold()),None)
    if not source:raise ValueError("missing_native_source")
    return dict(result,sourceId=source["sourceId"])
def record_native_variant_failure(index,payload,load_card,content_filter):
    from source_playback_evidence import sanitize_media3_failure_payload
    media,season,episode,series,candidate,facts = _resolve_native_variant(
        payload,load_card,content_filter,sanitize_media3_failure_payload)
    return index.record_candidate_failure(media,candidate,kind="EPISODE" if series else "MOVIE",
        season=season,episode=episode,reason=facts["reason"],cooldown=facts["cooldown"],
        observed_at=facts["observedAt"],expected_locator_hash=payload["locatorHash"],
        expected_profile_hash=payload["profileHash"])
