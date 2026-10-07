from pathlib import Path
import shutil,json,datetime
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo';D=R/'docs/audits/hour-20261007-2134';D.mkdir(parents=True,exist_ok=True)
shutil.copy2(R/'app/build/outputs/apk/debug/app-debug.apk',C/'hour-2134-rollback-3441.apk')
(D/'baseline.json').write_text(json.dumps({'checkpoint':3441,'commit':'1238a6d72b4e7b364ac05688492e70fb4d798435','apkSha256':'ec8cec0b9ec02942600bc8509af8eb4d2a8a423285a3574c8bfaccbaae928b58','startedAt':'2026-10-07T21:34:11Z'},indent=2))
p=R/'backend/runtime/native_variant_feedback.py';s=p.read_text()
s=s.replace('Authenticated native frame feedback','Authenticated native decoder feedback')
a=s.index('def record_native_variant_success(')
# The catalog validation is identical for successful and failed attempts.
v=s[a:];b=v.index('    candidate=matches[0]')
validation=v[:b].replace('def record_native_variant_success(index,payload,load_card,content_filter,sanitize_observation):','def _resolve_native_variant(payload,load_card,content_filter,sanitize_observation):')
validation=validation.replace('    candidate=matches[0]','')
validation+='    return media,season,episode,series,matches[0],facts\n\n'
success=v[b:].replace('    candidate=matches[0]','    media,season,episode,series,candidate,facts = _resolve_native_variant(payload,load_card,content_filter,sanitize_observation)')
success=success.replace('and str(x.get("provider")', 'and x.get("requestProfileHash")==payload["profileHash"] and str(x.get("provider")')
s=s[:a]+validation+'def record_native_variant_success(index,payload,load_card,content_filter,sanitize_observation):\n'+success
s+='''def record_native_variant_failure(index,payload,load_card,content_filter):
    from source_playback_evidence import sanitize_media3_failure_payload
    media,season,episode,series,candidate,facts = _resolve_native_variant(
        payload,load_card,content_filter,sanitize_media3_failure_payload)
    return index.record_candidate_failure(media,candidate,kind="EPISODE" if series else "MOVIE",
        season=season,episode=episode,reason=facts["reason"],cooldown=facts["cooldown"],
        observed_at=facts["observedAt"],expected_locator_hash=payload["locatorHash"],
        expected_profile_hash=payload["profileHash"])

'''
p.write_text(s)
p=R/'backend/runtime/playback_availability_index.py';s=p.read_text()
a=s.index('    def record_source_failure(');b=s.index('    def set_no_source(',a)
s=s[:a]+'''    def _record_source_failure_conn(self, conn, source_id, *, reason, cooldown, ts,
                                    observed_at, expected_locator_hash, expected_profile_hash):
        row = conn.execute("SELECT * FROM playback_sources WHERE source_id=?", (source_id,)).fetchone()
        if row is None:
            raise KeyError(source_id)
        if expected_locator_hash is not None and row["locator_hash"] != expected_locator_hash:
            raise ValueError("failure_locator_scope_mismatch")
        if expected_profile_hash is not None and row["request_profile_hash"] != expected_profile_hash:
            raise ValueError("failure_profile_scope_mismatch")
        watermark = max(float(row["last_success_at"] or 0), float(row["last_failure_at"] or 0))
        if observed_at is not None and ts < watermark:
            return str(row["media_key"]), watermark
        update = conn.execute(
            """
            UPDATE playback_sources SET verification_status=?,last_checked_at=?,last_failure_at=?,
                consecutive_failures=consecutive_failures+1,failure_reason=?,updated_at=?
            WHERE source_id=?
              AND (? IS NULL OR locator_hash=?)
              AND (? IS NULL OR request_profile_hash=?)
              AND (? IS NULL OR COALESCE(last_success_at,0)<=?)
              AND (? IS NULL OR COALESCE(last_failure_at,0)<=?)
            """,
            (STATUS_COOLDOWN if cooldown else STATUS_FAILED, ts, ts, _text(reason) or "UNKNOWN",
             ts, source_id, expected_locator_hash, expected_locator_hash,
             expected_profile_hash, expected_profile_hash, observed_at, ts, observed_at, ts))
        if update.rowcount != 1:
            raise ValueError("failure_scope_or_watermark_changed")
        return str(row["media_key"]), ts

    def record_source_failure(
        self, source_id: str, *, reason: str, cooldown: bool = False,
        now: Optional[float] = None, observed_at: Optional[float] = None,
        expected_locator_hash: Optional[str] = None, expected_profile_hash: Optional[str] = None,
    ) -> Dict[str, Any]:
        ts = float(observed_at) if observed_at is not None else (time.time() if now is None else float(now))
        with self.repository.connection() as conn:
            key, timestamp = self._record_source_failure_conn(conn,source_id,reason=reason,
                cooldown=cooldown,ts=ts,observed_at=observed_at,
                expected_locator_hash=expected_locator_hash,expected_profile_hash=expected_profile_hash)
        return self._recompute(key,now=timestamp)

    def record_candidate_failure(
        self, media_id: Any, candidate: Dict[str, Any], *, kind: str,
        season: Optional[int] = None, episode: Optional[int] = None,
        reason: str, cooldown: bool, observed_at: float,
        expected_locator_hash: str, expected_profile_hash: str,
    ) -> Dict[str, Any]:
        """Index a cached failed leaf without claiming successful content verification.
        Existing evidence is never reset by delayed failure feedback. The insert,
        scope comparison and failure watermark update share one write transaction.
        """
        from native_variant_feedback import feedback_fingerprints
        fp = feedback_fingerprints(candidate)
        if (fp["native_feedback_locator_hash"],fp["native_feedback_profile_hash"]) != (
                expected_locator_hash,expected_profile_hash):
            raise ValueError("failure_candidate_scope_mismatch")
        key = PlaybackMediaKey(_text(media_id),kind,season,episode)
        ts = float(observed_at)
        locator = _text(candidate.get("url") or candidate.get("playback_url"))
        provider = _text(candidate.get("provider") or candidate.get("source")) or "unknown"
        self.repository.ensure_media(key,now=ts)
        with self.repository.connection() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT source_id,request_profile_hash FROM playback_sources "
                "WHERE media_key=? AND provider=? AND locator_hash=?",
                (key.value,provider,expected_locator_hash)).fetchone()
            if row is not None and row["request_profile_hash"] != expected_profile_hash:
                raise ValueError("failure_profile_scope_mismatch")
            source_id = str(row["source_id"]) if row is not None else self._upsert_discovered_source_conn(
                conn,key,dict(candidate),locator,DISCOVERY_PROVIDER_SEARCH,ts)
            media_key,timestamp = self._record_source_failure_conn(conn,source_id,reason=reason,
                cooldown=cooldown,ts=ts,observed_at=observed_at,
                expected_locator_hash=expected_locator_hash,expected_profile_hash=expected_profile_hash)
        return dict(self._recompute(media_key,now=timestamp),sourceId=source_id)

'''+s[b:]
# Use the existing discovery constant rather than introducing a spelling-only alias.
s=s.replace('DISCOVERY_PROVIDER_SEARCH,ts)', '"PROVIDER_SEARCH",ts)')
p.write_text(s)
p=R/'backend/runtime/streamer.py';s=p.read_text()
s=s.replace('        if not variant_feedback and not legacy_feedback and not failure_feedback and parsed.path !=', '        variant_failure_feedback = parsed.path == "/internal/playback-availability/native-variant-failure"\n        if not variant_failure_feedback and not variant_feedback and not legacy_feedback and not failure_feedback and parsed.path !=')
s=s.replace('            if failure_feedback:\n', '''            if variant_failure_feedback:
                from native_variant_feedback import record_native_variant_failure
                from stream_identity import filter_streams_for_content
                result = record_native_variant_failure(index,payload,
                    catalog_api.get_movie_playback_card_scoped,filter_streams_for_content)
                clean = {"sourceId":result["sourceId"]}
            elif failure_feedback:
''')
s=s.replace('"MEDIA3_FAILURE" if failure_feedback else "MEDIA3_SUCCESS"', '"MEDIA3_FAILURE" if failure_feedback or variant_failure_feedback else "MEDIA3_SUCCESS"')
p.write_text(s)
p=R/'app/src/main/java/app/movia/android/domain/playback/NativeVariantFeedback.kt'
s=p.read_text()+'''
/** A delayed HTTP reply cannot attach an ID to a rotated source or another episode. */
internal fun StreamCandidate.withNativeFeedbackSourceId(prepared: StreamCandidate, sourceId: String): StreamCandidate {
    if (!sourceId.matches(Regex("src:[A-Za-z0-9:_-]{1,124}"))) return this
    if (stableStreamId != prepared.stableStreamId || catalogMediaId != prepared.catalogMediaId ||
        seasonNumber != prepared.seasonNumber || episodeNumber != prepared.episodeNumber ||
        nativeFeedbackScope(this) != nativeFeedbackScope(prepared)) return this
    return copy(sourceId = sourceId)
}
'''
p.write_text(s)
p=R/'app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt';s=p.read_text()
s=s.replace('import app.movia.android.domain.playback.nativeFeedbackScope','import app.movia.android.domain.playback.nativeFeedbackScope\nimport app.movia.android.domain.playback.withNativeFeedbackSourceId')
s=s.replace('        feedbackGeneration = playbackGeneration\n', '        feedbackGeneration = playbackGeneration\n        val generation = playbackGeneration\n',1)
old='                    connection.inputStream.use { it.readBytes() }'
new='''                    val response = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    if (nativeVariant) {
                        val returnedId = org.json.JSONObject(response).optString("sourceId")
                        withContext(Dispatchers.Main) {
                            if (isCurrentGeneration(generation)) {
                                candidates = candidates.map { it.withNativeFeedbackSourceId(candidate, returnedId) }
                                activeCandidate = activeCandidate?.withNativeFeedbackSourceId(candidate, returnedId)
                                publishSnapshot()
                            }
                        }
                    }'''
assert old in s;s=s.replace(old,new,1)
a=s.index('    private fun recordNativeFailure(');b=s.index('    private fun nextPlaybackGeneration(',a)
s=s[:a]+'''    private fun recordNativeFailure(candidate: StreamCandidate, failureClass: StreamFailureClass) {
        val request = playbackRequest ?: return
        if (isOffline) return
        val sourceId = candidate.sourceId?.takeIf { it.isNotBlank() }
        val nativeVariant = candidate.stableStreamId.startsWith("provider-item:v2:") &&
            candidate.catalogMediaId == request.mediaId &&
            candidate.seasonNumber == request.seasonNumber && candidate.episodeNumber == request.episodeNumber
        if (!nativeVariant && sourceId == null) return
        val observation = org.json.JSONObject().put("reason", failureClass.name)
            .put("observedAt", System.currentTimeMillis() / 1000.0)
        val endpoint = if (nativeVariant) "native-variant-failure" else "media3-failure"
        val payload = if (nativeVariant) {
            val preparedScope = nativeFeedbackScope(candidate)
            org.json.JSONObject().put("mediaId", request.mediaId)
                .put("season", request.seasonNumber ?: org.json.JSONObject.NULL)
                .put("episode", request.episodeNumber ?: org.json.JSONObject.NULL)
                .put("streamId", candidate.stableStreamId)
                .put("locatorHash", preparedScope.locatorHash).put("profileHash", preparedScope.profileHash)
                .put("observation", observation)
        } else observation.put("sourceId", sourceId)
        val encoded = payload.toString()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val token = java.io.File(appContext.filesDir, "agent/movia-agent.token").readText().trim()
                val connection = java.net.URL("http://127.0.0.1:8888/internal/playback-availability/$endpoint")
                    .openConnection() as java.net.HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 2000
                    connection.readTimeout = 3000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer " + token)
                    connection.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
                    connection.inputStream.use { it.readBytes() }
                } finally { connection.disconnect() }
            }
        }
    }

'''+s[b:]
p.write_text(s)
print('Native failure feedback and generation-scoped source ID attachment implemented')
