from pathlib import Path
import shutil
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo'
for name in ['hour-2134-android-build-result.json','hour-2134-android-build.txt']:
    shutil.copy2(C/name,C/name.replace('hour-2134','hour-2134-feedback-only'))
shutil.copy2(R/'app/build/outputs/apk/debug/app-debug.apk',C/'hour-2134-feedback-only.apk')
p=R/'app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt';s=p.read_text()
s=s.replace('import app.movia.android.domain.playback.withNativeFeedbackSourceId','import app.movia.android.domain.playback.withNativeFeedbackSourceId\nimport app.movia.android.domain.playback.PlaybackRecoveryBudget')
s=s.replace('    private var recoveryAttemptCount = 0\n    private var recoveryAttemptBudget = 1','    private val recoveryBudget = PlaybackRecoveryBudget()')
s=s.replace('recoveryAttemptCount = 0','recoveryBudget.reset()')
s=s.replace('        recoveryAttemptCount += 1\n        if (recoveryAttemptCount > recoveryAttemptBudget) {','        if (recoveryBudget.isExhausted(SystemClock.elapsedRealtime())) {')
s=s.replace('        recoveryAttemptBudget = candidates.size.coerceAtLeast(1) * 2 + 1\n','')
s=s.replace('        recoveryAttemptBudget = (candidates.size.coerceAtLeast(1) * 2) + 1\n','')
needle='            val refreshed = withTimeoutOrNull(RELOAD_TIMEOUT_MS) {'
assert s.count(needle)==1
s=s.replace(needle,'''            if (!recoveryBudget.tryAcquire(SystemClock.elapsedRealtime())) {
                recordFailure(failed, failureClass)
                failPlayback("EXHAUSTED_$reason")
                return
            }
'''+needle)
needle='        for (candidate in next) {\n            if (!isCurrentGeneration(generation)) return'
assert needle in s
s=s.replace(needle,needle+'''
            if (!recoveryBudget.tryAcquire(SystemClock.elapsedRealtime())) {
                failPlayback("EXHAUSTED_$reason")
                return
            }''')
s=s.replace('        publishCandidateOptions()\n        val desired = selectInitialCandidate(current)', '        publishCandidateOptions()\n        if (needsRecovery && recoveryBudget.isExhausted(SystemClock.elapsedRealtime())) return\n        val desired = selectInitialCandidate(current)')
s=s.replace('switchToStream(desired.toStreamOption(), position.coerceAtLeast(0))','switchToStream(desired.toStreamOption(), position.coerceAtLeast(0), resetRecoveryBudget = false)')
s=s.replace('if (activeCandidate?.stableStreamId != option.streamId) switchToStream(option)','if (activeCandidate?.stableStreamId != option.streamId) switchToStream(option, resetRecoveryBudget = false)')
s=s.replace('fun switchToStream(stream: StreamOption, resumePositionMs: Long = -1L, adoptVariantQuality: Boolean = false) {',
    'fun switchToStream(stream: StreamOption, resumePositionMs: Long = -1L, adoptVariantQuality: Boolean = false, resetRecoveryBudget: Boolean = true) {')
needle='        if (stream.url.isBlank() || !_state.value.hasMedia) return'
assert s.count(needle)==1
s=s.replace(needle,needle+'''
        if (!resetRecoveryBudget && !recoveryBudget.tryAcquire(SystemClock.elapsedRealtime())) {
            failPlayback("EXHAUSTED_AUTOMATIC_SWITCH")
            return
        }''')
# A late automatic switch inherits its failure episode; an explicit user choice resets it.
a=s.index('    fun switchToStream(stream: StreamOption');b=s.index('        val position =',a)
v=s[a:b];assert '        recoveryBudget.reset()' in v
v=v.replace('        recoveryBudget.reset()', '        if (resetRecoveryBudget) recoveryBudget.reset()')
s=s[:a]+v+s[b:]
# Re-publish options when a successfully resolved native source ID is attached.
s=s.replace('                                activeCandidate = activeCandidate?.withNativeFeedbackSourceId(candidate, returnedId)\n                                publishSnapshot()',
    '                                activeCandidate = activeCandidate?.withNativeFeedbackSourceId(candidate, returnedId)\n                                publishCandidateOptions()\n                                publishSnapshot()')
p.write_text(s)
assert 'recoveryAttempt' not in s
print('Automatic retries bounded to six preparations / 60-second start window; inventory retained')
