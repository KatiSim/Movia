package app.movia.android.domain.model

/** Unknown or empty seasons never produce invented episode identities. */
fun MediaRef.nextEpisode(seasonEpisodeCounts: List<Int>): MediaRef? {
    val currentSeason = season ?: return null
    val currentEpisode = episode ?: return null
    val count = seasonEpisodeCounts.getOrNull(currentSeason - 1) ?: return null
    if (count <= 0 || currentEpisode > count) return null
    if (currentEpisode < count) return copy(episode = currentEpisode + 1)
    val next = (currentSeason until seasonEpisodeCounts.size).firstOrNull { seasonEpisodeCounts[it] > 0 } ?: return null
    return copy(season = next + 1, episode = 1)
}

fun MediaRef.previousEpisode(seasonEpisodeCounts: List<Int>): MediaRef? {
    val currentSeason = season ?: return null
    val currentEpisode = episode ?: return null
    val count = seasonEpisodeCounts.getOrNull(currentSeason - 1) ?: return null
    if (count <= 0 || currentEpisode > count) return null
    if (currentEpisode > 1) return copy(episode = currentEpisode - 1)
    val previous = (currentSeason - 2 downTo 0).firstOrNull { seasonEpisodeCounts[it] > 0 } ?: return null
    return copy(season = previous + 1, episode = seasonEpisodeCounts[previous])
}
