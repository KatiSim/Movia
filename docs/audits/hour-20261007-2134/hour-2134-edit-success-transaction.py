from pathlib import Path
R=Path.home()/'.cache/movia-architecture-20261002/repo'
p=R/'backend/runtime/playback_availability_index.py';s=p.read_text()
a=s.index('    def mark_source_verified(');b=s.index('    def record_playback_success(',a);v=s[a:b]
validation=v.index('        method = _text(verification_method)')
signature=v[:validation]
sql_start=v.index('        with self.repository.connection() as conn:')
sql_end=v.index('        return self._recompute(key_value, now=ts)')
helper_signature=signature.replace('def mark_source_verified(', 'def _mark_source_verified_conn(').replace('        self,\n','        self,\n        conn,\n',1).replace(') -> Dict[str, Any]:',') -> str:')
helper=helper_signature+v[validation:sql_start]+''.join(line[4:] if line.startswith('    ') else line for line in v[sql_start:].splitlines(True)[1:])
helper=helper[:helper.index('    return self._recompute')]+'        return key_value\n\n'
wrapper=signature+'''        ts = time.time() if now is None else float(now)
        with self.repository.connection() as conn:
            key_value = self._mark_source_verified_conn(conn,source_id,
                verification_method=verification_method,actual_quality=actual_quality,
                actual_qualities=actual_qualities,actual_audio_tracks=actual_audio_tracks,
                manifest_type=manifest_type,startup_latency_ms=startup_latency_ms,
                health_score=health_score,success=success,now=ts)
        return self._recompute(key_value,now=ts)

'''
s=s[:a]+wrapper+helper+s[b:]
a=s.index('    def verify_candidate(');b=s.index('    def mark_source_verified(',a);v=s[a:b]
v=v.replace('        success: bool = False,\n        now:', '        success: bool = False,\n        expected_locator_hash: Optional[str] = None,\n        expected_profile_hash: Optional[str] = None,\n        now:',1)
start=v.index('        with self.repository.connection() as conn:')
v=v[:start]+'''        from native_variant_feedback import feedback_fingerprints
        fp = feedback_fingerprints(candidate)
        if expected_locator_hash is not None and fp["native_feedback_locator_hash"] != expected_locator_hash:
            raise ValueError("success_locator_scope_mismatch")
        if expected_profile_hash is not None and fp["native_feedback_profile_hash"] != expected_profile_hash:
            raise ValueError("success_profile_scope_mismatch")
        with self.repository.connection() as conn:
            conn.execute("BEGIN IMMEDIATE")
            if expected_profile_hash is not None:
                provider = _text(candidate.get("provider") or candidate.get("source")) or "unknown"
                old = conn.execute("SELECT request_profile_hash FROM playback_sources "
                    "WHERE media_key=? AND provider=? AND locator_hash=?",
                    (key.value,provider,fp["native_feedback_locator_hash"])).fetchone()
                if old is not None and old["request_profile_hash"] not in {"",expected_profile_hash}:
                    raise ValueError("success_profile_scope_mismatch")
            source_id = self._upsert_discovered_source_conn(
                conn,key,dict(candidate or {}),locator,discovery_method,ts)
            key_value = self._mark_source_verified_conn(conn,source_id,
                verification_method=method,actual_quality=actual_quality,actual_qualities=actual_qualities,
                actual_audio_tracks=actual_audio_tracks,manifest_type=manifest_type,
                startup_latency_ms=startup_latency_ms,health_score=health_score,success=success,now=ts)
        return self._recompute(key_value,now=ts)

'''
s=s[:a]+v+s[b:]
s=s.replace('row["request_profile_hash"] != expected_profile_hash:\n                raise ValueError("failure_profile_scope_mismatch")\n            source_id = str(row["source_id"]) if row is not None else',
    'row["request_profile_hash"] not in {"",expected_profile_hash}:\n                raise ValueError("failure_profile_scope_mismatch")\n            source_id = str(row["source_id"]) if row is not None and row["request_profile_hash"] else')
p.write_text(s)
p=R/'backend/runtime/native_variant_feedback.py';s=p.read_text()
s=s.replace('actual_qualities=facts.get("actualQualities"),actual_audio_tracks=facts.get("actualAudioTracks"))',
    'actual_qualities=facts.get("actualQualities"),actual_audio_tracks=facts.get("actualAudioTracks"),\n        expected_locator_hash=payload["locatorHash"],expected_profile_hash=payload["profileHash"])')
p.write_text(s)
compile((R/'backend/runtime/playback_availability_index.py').read_text(),'index','exec')
print('Native success indexing and verification now share one SQLite write transaction')
