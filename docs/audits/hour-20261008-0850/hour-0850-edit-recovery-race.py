from pathlib import Path
import json,shutil
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo'
assert json.loads((C/'hour-0850-android-build-result.json').read_text())['exitCode']==0
for name in ['android-build.txt','android-build-result.json']:
 shutil.copy2(C/('hour-0850-'+name),C/('hour-0850-before-recovery-race-'+name))
def change(p,old,new,count=1):
 p=R/p;s=p.read_text();assert s.count(old)==count,(p,s.count(old),old[:80]);p.write_text(s.replace(old,new))
gate='app/src/main/java/app/movia/android/domain/playback/DecoderFeedbackGate.kt'
change(gate,'    private var renderedFrame = false','    private var renderedFrame = false\n    private var frameVersion = 0L')
change(gate,'    fun onRenderedFrame() { if (preparation > 0L) renderedFrame = true }','    fun onRenderedFrame() { if (preparation > 0L) { renderedFrame = true; frameVersion += 1 } }\n    fun renderedFrameVersion(): Long = frameVersion\n    fun recoveryIsCurrent(attemptId: Long, version: Long): Boolean = preparation > 0L && isCurrent(attemptId) && frameVersion == version')
f='app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt'
change(f,'                decoderFeedbackGate.onRenderedFrame()\n                watchdogJob?.cancel()', '                decoderFeedbackGate.onRenderedFrame()\n                // A real frame supersedes a reload still waiting on provider I/O.\n                recoveryJob?.cancel()\n                watchdogJob?.cancel()')
change(f,'    private fun recordNativeFailure(candidate: StreamCandidate, failureClass: StreamFailureClass) {','    private fun recordNativeFailure(candidate: StreamCandidate, failureClass: StreamFailureClass, observedAtMs: Long) {')
change(f,'.put("observedAt", System.currentTimeMillis() / 1000.0)', '.put("observedAt", observedAtMs / 1000.0)')
change(f,'    private fun recordFailure(candidate: StreamCandidate?, failureClass: StreamFailureClass) {\n        candidate ?: return\n        recordNativeFailure(candidate, failureClass)', '    private fun recordFailure(candidate: StreamCandidate?, failureClass: StreamFailureClass, observedAtMs: Long = System.currentTimeMillis()) {\n        candidate ?: return\n        recordNativeFailure(candidate, failureClass, observedAtMs)')
old='''        generation: Long,
        failureClass: StreamFailureClass,
    ) {
        if (!isCurrentGeneration(generation)) return
        val failed = activeCandidate'''
new='''        generation: Long,
        failureClass: StreamFailureClass,
        observedAtMs: Long,
    ) {
        if (!isCurrentGeneration(generation)) return
        val failedPreparation = decoderFeedbackGate.attemptId()
        val failedFrameVersion = decoderFeedbackGate.renderedFrameVersion()
        val failed = activeCandidate'''
change(f,old,new)
change(f,'recordFailure(failed, failureClass)', 'recordFailure(failed, failureClass, observedAtMs)',3)
change(f,'            if (isCurrentGeneration(generation) && refreshed != null) {', '''            if (!isCurrentGeneration(generation) ||
                !decoderFeedbackGate.recoveryIsCurrent(failedPreparation, failedFrameVersion)) return
            if (refreshed != null) {''')
change(f,'        recoveryJob = scope.launch {\n            try {\n                recoverFromFailure(reason, resumePositionMs, generation, failureClass)\n            } finally {\n                recoveryJob = null\n            }\n        }','''        val observedAtMs = System.currentTimeMillis()
        recoveryJob = scope.launch {
            try {
                recoverFromFailure(reason, resumePositionMs, generation, failureClass, observedAtMs)
            } finally {
                // A cancelled older job must not erase a newer recovery owner.
                if (recoveryJob === kotlinx.coroutines.currentCoroutineContext()[Job]) recoveryJob = null
            }
        }''')
p=R/'app/src/test/java/app/movia/android/domain/playback/DecoderFeedbackGateTest.kt';s=p.read_text();i=s.rfind('}');s=s[:i]+'''
    @Test fun frameDuringPendingReloadSupersedesTheOriginalFailure() {
        val gate=DecoderFeedbackGate();gate.prepare(100)
        val attempt=gate.attemptId();val version=gate.renderedFrameVersion()
        assertTrue(gate.recoveryIsCurrent(attempt,version))
        gate.onRenderedFrame()
        assertFalse(gate.recoveryIsCurrent(attempt,version))
    }
    @Test fun segmentFailureAfterAnEarlierFrameMayRecoverUntilAnotherFrameOrPreparation() {
        val gate=DecoderFeedbackGate();gate.prepare(100);gate.onRenderedFrame()
        val attempt=gate.attemptId();val version=gate.renderedFrameVersion()
        assertTrue(gate.recoveryIsCurrent(attempt,version))
        gate.prepare(200)
        assertFalse(gate.recoveryIsCurrent(attempt,version))
    }
''' +s[i:];p.write_text(s)
print('Late-frame recovery race, recovery ownership and failure event timestamps fixed; two additional tests')
