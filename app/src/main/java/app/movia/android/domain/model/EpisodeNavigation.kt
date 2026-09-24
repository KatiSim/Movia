package app.movia.android.domain.model

/** Returns the next episode identity without parsing a presentation title. */
fun MediaRef.nextEpisode(seasonEpisodeCounts: List<Int>): MediaRef? {
    val currentSeason = season ?: return null
    val currentEpisode = episode ?: return null
    val episodeCount = seasonEpisodeCounts.getOrNull(currentSeason - 1) ?: 10
    return when {
        currentEpisode < episodeCount -> copy(episode = currentEpisode + 1)
        currentSeason < seasonEpisodeCounts.size -> copy(season = currentSeason + 1, episode = 1)
        else -> null
    }
}

/** Returns the previous episode identity without parsing a presentation title. */
fun MediaRef.previousEpisode(seasonEpisodeCounts: List<Int>): MediaRef? {
    val currentSeason = season ?: return null
    val currentEpisode = episode ?: return null
    return when {
        currentEpisode > 1 -> copy(episode = currentEpisode - 1)
        currentSeason > 1 -> {
            val previousSeason = currentSeason - 1
            val previousEpisode = seasonEpisodeCounts.getOrNull(previousSeason - 1) ?: 10
            copy(season = previousSeason, episode = previousEpisode)
        }
        else -> null
    }
}
