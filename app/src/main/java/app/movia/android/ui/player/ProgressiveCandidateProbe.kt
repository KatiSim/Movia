package app.movia.android.ui.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Delivers ready rows before slow metadata work and keeps callbacks on the caller's context.
 * Final rows retain provider order; cancellation owns both workers and queued results.
 */
internal suspend fun <T> probeCandidatesProgressively(
    candidates: List<T>,
    requiresProbe: (T) -> Boolean,
    probe: suspend (T) -> List<T>,
    onCandidates: suspend (List<T>) -> Unit,
    maxConcurrentProbes: Int = 2,
): List<T> = coroutineScope {
    require(maxConcurrentProbes > 0)
    val results = MutableList<List<T>>(candidates.size) { emptyList() }
    val pending = mutableListOf<Int>()
    candidates.forEachIndexed { index, candidate ->
        if (requiresProbe(candidate)) pending += index else results[index] = listOf(candidate)
    }
    val ready = results.flatten()
    if (ready.isNotEmpty()) onCandidates(ready)
    if (pending.isEmpty()) return@coroutineScope ready

    val work = Channel<Int>(Channel.UNLIMITED)
    pending.forEach { work.trySend(it).getOrThrow() }
    work.close()
    val completed = Channel<Pair<Int, List<T>>>(maxConcurrentProbes)
    repeat(minOf(maxConcurrentProbes, pending.size)) {
        launch(Dispatchers.IO) {
            for (index in work) {
                completed.send(index to probe(candidates[index]))
            }
        }
    }
    try {
        repeat(pending.size) {
            val (index, rows) = completed.receive()
            results[index] = rows
            if (rows.isNotEmpty()) onCandidates(rows)
        }
        results.flatten()
    } finally {
        work.cancel()
        completed.cancel()
    }
}
