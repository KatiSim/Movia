from pathlib import Path
import shutil
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo'
p=R/'app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt';s=p.read_text()
s=s.replace('import app.movia.android.domain.playback.PlaybackRecoveryBudget','import app.movia.android.domain.playback.PlaybackRecoveryBudget\nimport app.movia.android.domain.playback.DecoderFeedbackGate')
s=s.replace('    private var feedbackGeneration = -1L','    private val decoderFeedbackGate = DecoderFeedbackGate()')
s=s.replace('        if (feedbackGeneration == playbackGeneration || isOffline) return\n        feedbackGeneration = playbackGeneration',
    '        if (isOffline || !decoderFeedbackGate.claimFirstFrame()) return\n        val preparation = decoderFeedbackGate.attemptId()\n        val observedAt = System.currentTimeMillis() / 1000.0')
s=s.replace('.put("startupLatencyMs", firstFrameLatencyMs ?: readyLatencyMs ?: 0L)',
    '.put("startupLatencyMs", decoderFeedbackGate.latencyMs(SystemClock.elapsedRealtime()))')
s=s.replace('} else if (nativeVariant) {\n                    val facts = org.json.JSONObject(observation.toString()).apply { remove("sourceId") }',
    '} else if (nativeVariant) {\n                    val facts = org.json.JSONObject(observation.toString()).apply {\n                        remove("sourceId"); put("observedAt", observedAt)\n                    }')
s=s.replace('if (isCurrentGeneration(generation)) {\n                                candidates = candidates.map',
    'if (isCurrentGeneration(generation) && decoderFeedbackGate.isCurrent(preparation)) {\n                                candidates = candidates.map')
needle='        return try {\n            player.stop()\n            player.clearMediaItems()\n            clearCandidateTrackOverrides()'
assert s.count(needle)==1
s=s.replace(needle,'        decoderFeedbackGate.prepare(SystemClock.elapsedRealtime())\n'+needle)
p.write_text(s)
p=R/'backend/runtime/native_variant_feedback.py';s=p.read_text()
needle='    media,season,episode,series,candidate,facts = _resolve_native_variant(payload,load_card,content_filter,sanitize_observation)'
replacement='''    from source_playback_evidence import sanitize_media3_failure_payload
    import time
    def frame_facts(observation):
        fields = dict(observation)
        stamp = fields.pop("observedAt",time.time())
        checked = sanitize_media3_failure_payload({"sourceId":"src:native-variant",
            "reason":"NETWORK","observedAt":stamp})
        facts = sanitize_observation(fields)
        return dict(facts,observedAt=checked["observedAt"])
    media,season,episode,series,candidate,facts = _resolve_native_variant(payload,load_card,content_filter,frame_facts)'''
assert needle in s;s=s.replace(needle,replacement)
s=s.replace('expected_locator_hash=payload["locatorHash"],expected_profile_hash=payload["profileHash"])',
    'expected_locator_hash=payload["locatorHash"],expected_profile_hash=payload["profileHash"],\n        now=facts["observedAt"])',1)
p.write_text(s)
p=R/'backend/runtime/playback_availability_index.py';s=p.read_text()
s=s.replace('old = conn.execute("SELECT request_profile_hash FROM playback_sources "', 'old = conn.execute("SELECT source_id,request_profile_hash FROM playback_sources "',1)
s=s.replace('        with self.repository.connection() as conn:\n            conn.execute("BEGIN IMMEDIATE")\n            if expected_profile_hash is not None:',
    '        with self.repository.connection() as conn:\n            conn.execute("BEGIN IMMEDIATE")\n            old = None\n            if expected_profile_hash is not None:',1)
s=s.replace('''            source_id = self._upsert_discovered_source_conn(
                conn,key,dict(candidate or {}),locator,discovery_method,ts)
            key_value = self._mark_source_verified_conn''','''            source_id = str(old["source_id"]) if old is not None and old["request_profile_hash"] == expected_profile_hash else self._upsert_discovered_source_conn(
                conn,key,dict(candidate or {}),locator,discovery_method,ts)
            key_value,applied = self._mark_source_verified_conn''',1)
s=s.replace('        return self._recompute(key_value,now=ts)\n\n    def mark_source_verified(',
    '        return dict(self._recompute(key_value,now=ts),observationApplied=applied)\n\n    def mark_source_verified(',1)
s=s.replace('key_value = self._mark_source_verified_conn(conn,source_id,', 'key_value,applied = self._mark_source_verified_conn(conn,source_id,',1)
s=s.replace('        return self._recompute(key_value,now=ts)\n\n    def _mark_source_verified_conn(',
    '        return dict(self._recompute(key_value,now=ts),observationApplied=applied)\n\n    def _mark_source_verified_conn(',1)
a=s.index('    def _mark_source_verified_conn(');b=s.index('    def record_playback_success(',a);v=s[a:b]
v=v.replace(') -> str:', ') -> tuple[str,bool]:',1)
v=v.replace('        expiry = _finite_float(row["expires_at"])', '''        if success and ts < max(float(row["last_success_at"] or 0),float(row["last_failure_at"] or 0)):
            return str(row["media_key"]),False
        expiry = _finite_float(row["expires_at"])''',1)
v=v.replace('        return key_value','        return key_value,True')
s=s[:a]+v+s[b:]
p.write_text(s)
p=R/'backend/runtime/streamer.py';s=p.read_text()
s=s.replace('            "mediaKey": result.get("mediaKey") if isinstance(result, dict) else None,',
    '            "mediaKey": result.get("mediaKey") if isinstance(result, dict) else None,\n            "observationApplied": result.get("observationApplied",True) if isinstance(result,dict) else True,',1)
p.write_text(s)
for n in ['backend/runtime/native_variant_feedback.py','backend/runtime/playback_availability_index.py','backend/runtime/streamer.py']:
 compile((R/n).read_text(),n,'exec')
print('Per-preparation frame reporting and timestamp-ordered native success implemented')
