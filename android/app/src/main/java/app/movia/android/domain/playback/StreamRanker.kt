package app.movia.android.domain.playback

/** Runtime facts which may change during a playback session. */
data class StreamRankingContext(
    val requestedVoice: String? = null,
    val requestedQuality: String? = null,
    val preferredLanguage: String = "ru",
    val failedStreamIds: Set<String> = emptySet(),
    val supportedCodecs: Set<String> = emptySet(),
)

object StreamRanker {
    private fun normalized(value: String?): String = value?.trim()?.lowercase().orEmpty()

    private fun voiceLanguageRank(candidate: StreamCandidate, preferredLanguage: String): Int {
        val language = normalized(candidate.language)
        val voice = normalized(candidate.voice)
        val preferred = normalized(preferredLanguage)
        if (preferred.isNotBlank() && (language == preferred || voice == preferred || voice.startsWith("$preferred-"))) return 0
        if (language.startsWith("ru") || voice.contains("рус")) return 1
        if (voice.contains("original") || voice.contains("оригинал") || language.startsWith("en")) return 2
        return 3
    }

    private fun qualityPixels(quality: String): Int {
        val q = normalized(quality)
        return when {
            q.contains("2160") || q.contains("4k") || q.contains("uhd") -> 2160
            q.contains("1440") -> 1440
            q.contains("1080") || q.contains("fullhd") || q.contains("fhd") -> 1080
            q.contains("720") || q == "hd" -> 720
            q.contains("480") || q == "sd" -> 480
            q.contains("360") -> 360
            else -> 0
        }
    }

    private fun defaultQualityRank(quality: String): Int = when (qualityPixels(quality)) {
        1080 -> 0
        720 -> 1
        2160 -> 2
        1440 -> 3
        480 -> 4
        360 -> 5
        else -> 6
    }

    private fun requestedVoicePenalty(candidate: StreamCandidate, requestedVoice: String?): Int {
        val requested = normalized(requestedVoice)
        if (requested.isBlank() || requested == "auto" || requested == "any") return 0
        return if (normalized(candidate.voice) == requested) 0 else 1
    }

    private fun requestedQualityPenalty(candidate: StreamCandidate, requestedQuality: String?): Int {
        val requested = normalized(requestedQuality)
        if (requested.isBlank() || requested == "auto" || requested == "any") return 0
        val requestedPixels = qualityPixels(requested)
        return if (normalized(candidate.quality) == requested ||
            (requestedPixels > 0 && qualityPixels(candidate.quality) == requestedPixels)
        ) 0 else 1
    }

    private fun codecPenalty(candidate: StreamCandidate, context: StreamRankingContext): Int {
        if (context.supportedCodecs.isEmpty()) return 0
        val codec = normalized(candidate.codec)
        if (codec.isBlank()) return 0
        return if (context.supportedCodecs.none { codec.contains(normalized(it)) }) 2 else 0
    }

    private fun healthPenalty(candidate: StreamCandidate): Double {
        val providerHealth = candidate.providerReliability?.coerceIn(0.0, 1.0)
        val health = if (providerHealth == null) {
            candidate.healthScore.coerceIn(0.0, 1.0)
        } else {
            (candidate.healthScore.coerceIn(0.0, 1.0) + providerHealth) / 2.0
        }
        return (1.0 - health) * 10.0 + candidate.recentFailureCount.coerceAtLeast(0) * 2.0
    }

    private fun hasPeers(candidate: StreamCandidate): Boolean {
        val transport = normalized(candidate.transport)
        val isP2p = transport in setOf("torrent", "p2p", "torrent_p2p", "magnet") ||
            candidate.url.startsWith("magnet:", ignoreCase = true)
        return !isP2p || candidate.seeders > 0
    }

    /**
     * Zona-style ranking: strict requested dimensions, compatibility and
     * observed health/startup. Source/provider is only a final stable tie-break.
     * A direct URL has no unconditional priority over a healthy swarm.
     */
    fun rankCandidates(
        candidates: List<StreamCandidate>,
        failedStreamIds: Set<String> = emptySet(),
        context: StreamRankingContext = StreamRankingContext(failedStreamIds = failedStreamIds),
    ): List<StreamCandidate> {
        val effectiveContext = context.copy(failedStreamIds = context.failedStreamIds + failedStreamIds)
        return candidates.sortedWith(
            compareBy<StreamCandidate> {
                if (effectiveContext.failedStreamIds.contains(it.stableStreamId) || it.isProblematic) 1 else 0
            }
                .thenBy { if (it.unavailableQuality) 1 else 0 }
                .thenBy { requestedVoicePenalty(it, effectiveContext.requestedVoice) }
                .thenBy { requestedQualityPenalty(it, effectiveContext.requestedQuality) }
                .thenBy { voiceLanguageRank(it, effectiveContext.preferredLanguage) }
                .thenBy { codecPenalty(it, effectiveContext) }
                .thenBy { healthPenalty(it) }
                .thenBy { it.startupLatencyMs?.coerceAtLeast(0L) ?: Long.MAX_VALUE }
                .thenBy { if (hasPeers(it)) 0 else 1 }
                .thenByDescending { it.seeders }
                .thenBy { defaultQualityRank(it.quality) }
                .thenBy { normalized(it.transport) }
                .thenBy { normalized(it.provider) }
                .thenBy { it.stableStreamId },
        )
    }

    fun selectBest(
        candidates: List<StreamCandidate>,
        requestedVoice: String?,
        requestedQuality: String?,
        failedStreamIds: Set<String> = emptySet(),
        context: StreamRankingContext = StreamRankingContext(),
    ): StreamCandidate? {
        val healthy = candidates.filter {
            !failedStreamIds.contains(it.stableStreamId) && !it.isProblematic
        }
        val pool = if (healthy.isNotEmpty()) healthy else candidates
        if (pool.isEmpty()) return null

        val reqVoice = requestedVoice?.trim()?.takeUnless { it.isBlank() || it.equals("Auto", ignoreCase = true) }
        val reqQuality = requestedQuality?.trim()?.takeUnless { it.isBlank() || it.equals("Auto", ignoreCase = true) }
        val requestContext = context.copy(
            requestedVoice = reqVoice,
            requestedQuality = reqQuality,
            failedStreamIds = context.failedStreamIds + failedStreamIds,
        )
        val exactBoth = if (reqVoice != null && reqQuality != null) {
            pool.filter {
                normalized(it.voice) == normalized(reqVoice) &&
                    (normalized(it.quality) == normalized(reqQuality) ||
                        qualityPixels(it.quality) == qualityPixels(reqQuality))
            }
        } else emptyList()
        if (exactBoth.isNotEmpty()) return rankCandidates(exactBoth, context = requestContext).first()

        val exactVoice = if (reqVoice != null) pool.filter { normalized(it.voice) == normalized(reqVoice) } else emptyList()
        if (exactVoice.isNotEmpty()) return rankCandidates(exactVoice, context = requestContext).first()

        val exactQuality = if (reqQuality != null) pool.filter {
            normalized(it.quality) == normalized(reqQuality) ||
                (qualityPixels(reqQuality) > 0 && qualityPixels(it.quality) == qualityPixels(reqQuality))
        } else emptyList()
        if (exactQuality.isNotEmpty()) return rankCandidates(exactQuality, context = requestContext).first()

        return rankCandidates(pool, context = requestContext).firstOrNull()
    }
}
