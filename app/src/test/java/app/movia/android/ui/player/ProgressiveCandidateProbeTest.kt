package app.movia.android.ui.player

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ProgressiveCandidateProbeTest {
    @Test fun readySourceAndFastProbeArriveBeforeSlowSourceFinishes() = runBlocking {
        val slowGate = CompletableDeferred<Unit>()
        val readySeen = CompletableDeferred<Unit>()
        val fastSeen = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val result = async {
            probeCandidatesProgressively(
                candidates = listOf("slow", "ready", "fast"),
                requiresProbe = { it != "ready" },
                probe = { if (it == "slow") slowGate.await(); listOf(it + ".track") },
                onCandidates = { rows ->
                    published += rows
                    if ("ready" in rows) readySeen.complete(Unit)
                    if ("fast.track" in rows) fastSeen.complete(Unit)
                },
            )
        }
        withTimeout(3000) { readySeen.await(); fastSeen.await() }
        assertFalse("Slow provider blocked ready playback", result.isCompleted)
        assertEquals(listOf("ready", "fast.track"), published)
        slowGate.complete(Unit)
        assertEquals(listOf("slow.track", "ready", "fast.track"), result.await())
    }

    @Test fun largeProviderListNeverRunsMoreThanTwoProbes() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val twoStarted = CompletableDeferred<Unit>()
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val result = async {
            probeCandidatesProgressively(
                candidates = (0 until 24).toList(),
                requiresProbe = { true },
                probe = {
                    val count = running.incrementAndGet()
                    peak.updateAndGet { old -> maxOf(old, count) }
                    if (count == 2) twoStarted.complete(Unit)
                    try { gate.await(); listOf(it) } finally { running.decrementAndGet() }
                },
                onCandidates = {},
            )
        }
        withTimeout(3000) { twoStarted.await() }
        delay(40)
        assertEquals(2, peak.get())
        gate.complete(Unit)
        assertEquals((0 until 24).toList(), result.await())
        assertTrue(peak.get() <= 2)
    }

    @Test fun cancelledRequestStopsWorkersAndPublishesNoLateSource() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val running = AtomicInteger()
        val updates = AtomicInteger()
        val result = launch {
            probeCandidatesProgressively(
                candidates = listOf("pending"),
                requiresProbe = { true },
                probe = {
                    running.incrementAndGet()
                    try { started.complete(Unit); awaitCancellation() }
                    finally { running.decrementAndGet() }
                },
                onCandidates = { updates.incrementAndGet() },
            )
        }
        withTimeout(3000) { started.await() }
        result.cancelAndJoin()
        assertEquals(0, running.get())
        assertEquals(0, updates.get())
    }
}
