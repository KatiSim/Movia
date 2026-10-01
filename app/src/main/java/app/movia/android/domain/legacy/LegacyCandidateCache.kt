package app.movia.android.domain.legacy

import app.movia.android.domain.playback.PlaybackRequest
import app.movia.android.domain.playback.StreamCandidate

/** Short in-memory hint cache. Signed URLs are refreshed by the normal playback failure path. */
internal class LegacyCandidateCache(
    private val maximumEntries: Int = 32,
    private val ttlMs: Long = 120_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Key(val id: String, val title: String, val year: Int?, val season: Int?, val episode: Int?)
    private data class Entry(val createdMs: Long, val candidates: List<StreamCandidate>)
    private val entries = LinkedHashMap<Key, Entry>()
    private fun key(request: PlaybackRequest) = Key(request.mediaId, request.title, request.year,
        request.seasonNumber, request.episodeNumber)

    @Synchronized fun get(request: PlaybackRequest): List<StreamCandidate>? {
        val value = entries[key(request)] ?: return null
        if (clock() - value.createdMs >= ttlMs) {
            entries.remove(key(request))
            return null
        }
        return value.candidates
    }

    @Synchronized fun put(request: PlaybackRequest, candidates: List<StreamCandidate>) {
        if (candidates.isEmpty() || maximumEntries <= 0) return
        val key = key(request)
        entries.remove(key)
        entries[key] = Entry(clock(), candidates.toList())
        while (entries.size > maximumEntries) entries.remove(entries.keys.first())
    }
}
