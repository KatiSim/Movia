package app.movia.android.domain.playback

/** Runtime facts which may change during a playback session. */
data class StreamRankingContext(
    val requestedVoice: String? = null,
    val requestedQuality: String? = null,
    val preferredLanguage: String = "ru",
    /** Default quality preference used by Zona-style base calculators. */
    val preferredResolutionHeight: Int = 1080,
    val failedStreamIds: Set<String> = emptySet(),
    val supportedCodecs: Set<String> = emptySet(),
)

object StreamRanker {
    private const val CUSTOM_FILTER_BOOST = 7.0
    private val qualityTiers = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144, 0)

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

    private fun qualityPixels(quality: String?): Int {
        val q = normalized(quality)
        return when {
            q.contains("2160") || q.contains("4k") || q.contains("uhd") -> 2160
            q.contains("1440") || q.contains("2k") -> 1440
            q.contains("1080") || q.contains("fullhd") || q.contains("fhd") -> 1080
            q.contains("720") || q == "hd" -> 720
            q.contains("480") || q == "sd" -> 480
            q.contains("360") -> 360
            q.contains("240") -> 240
            q.contains("144") -> 144
            else -> 0
        }
    }

    private fun candidateResolution(candidate: StreamCandidate): Int =
        candidate.resolutionHeight?.takeIf { it > 0 }
            ?: qualityPixels(candidate.resolution).takeIf { it > 0 }
            ?: qualityPixels(candidate.quality)

    private fun defaultQualityRank(quality: String): Int = when (qualityPixels(quality)) {
        1080 -> 0
        720 -> 1
        2160 -> 2
        1440 -> 3
        480 -> 4
        360 -> 5
        else -> 6
    }

    private fun requestedVoiceMatches(candidate: StreamCandidate, requestedVoice: String?): Boolean {
        val requested = normalized(requestedVoice)
        if (requested.isBlank() || requested == "auto" || requested == "any") return true
        return normalized(candidate.voice) == requested
    }

    private fun requestedQualityMatches(candidate: StreamCandidate, requestedQuality: String?): Boolean {
        val requested = normalized(requestedQuality)
        if (requested.isBlank() || requested == "auto" || requested == "any") return true
        val requestedPixels = qualityPixels(requested)
        return normalized(candidate.quality) == requested ||
            (requestedPixels > 0 && candidateResolution(candidate) == requestedPixels)
    }

    private fun requestedVoicePenalty(candidate: StreamCandidate, requestedVoice: String?): Int =
        if (requestedVoiceMatches(candidate, requestedVoice)) 0 else 1

    private fun requestedQualityPenalty(candidate: StreamCandidate, requestedQuality: String?): Int =
        if (requestedQualityMatches(candidate, requestedQuality)) 0 else 1

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

    private fun isP2p(candidate: StreamCandidate): Boolean {
        val transport = normalized(candidate.transport)
        return transport in setOf("torrent", "p2p", "torrent_p2p", "magnet") ||
            candidate.url.startsWith("magnet:", ignoreCase = true)
    }

    private fun hasPeers(candidate: StreamCandidate): Boolean =
        !isP2p(candidate) || candidate.seeders > 0

    /**
     * A cold P2P candidate has metadata/file-selection/piece startup work that
     * seed count alone cannot represent. Measured startup evidence remains
     * stronger than this tie-break. Cached P2P is already health=1/startup=0.
     */
    private fun coldP2pPenalty(candidate: StreamCandidate): Int =
        if (isP2p(candidate) && candidate.startupLatencyMs == null) 1 else 0

    private fun healthyPool(
        candidates: List<StreamCandidate>,
        failedStreamIds: Set<String>,
    ): List<StreamCandidate> = candidates.filter {
        !failedStreamIds.contains(it.stableStreamId) && !it.isProblematic
    }

    private fun activeRequestedVoice(context: StreamRankingContext): String? =
        context.requestedVoice?.trim()?.takeUnless {
            it.isBlank() || it.equals("Auto", ignoreCase = true) || it.equals("Any", ignoreCase = true)
        }

    private fun activeRequestedQuality(context: StreamRankingContext): String? =
        context.requestedQuality?.trim()?.takeUnless {
            it.isBlank() || it.equals("Auto", ignoreCase = true) || it.equals("Any", ignoreCase = true)
        }

    private fun resolutionBounds(preferredResolutionHeight: Int): IntArray {
        val preferred = qualityTiers.indexOf(preferredResolutionHeight).takeIf { it >= 0 }
            ?: qualityTiers.indexOf(1080)
        fun tier(offset: Int): Int = qualityTiers[(preferred + offset).coerceIn(0, qualityTiers.lastIndex)]
        return intArrayOf(
            tier(+2),
            tier(+1),
            qualityTiers[preferred],
            tier(-1),
            tier(-2),
        )
    }

    private fun inResolutionRange(candidate: StreamCandidate, min: Int, max: Int): Boolean {
        val value = candidateResolution(candidate)
        return value > 0 && value in min..max
    }

    private fun hasNoAdvertisement(candidate: StreamCandidate): Boolean =
        candidate.advertisement?.raw.isNullOrBlank()

    private fun isRussianLanguage(candidate: StreamCandidate): Boolean =
        normalized(candidate.language) == "ru"

    private fun isNonAv1(candidate: StreamCandidate): Boolean =
        normalized(candidate.codec) != "av1"

    /**
     * Zona-compatible strict BEST group. With explicit voice/quality, strict
     * BEST contains custom filters only. Without custom preferences the base
     * filters are applied sequentially and the non-AV1 gate is appended.
     */
    internal fun strictBestGroup(
        candidates: List<StreamCandidate>,
        context: StreamRankingContext,
    ): List<StreamCandidate> {
        var pool = healthyPool(candidates, context.failedStreamIds)
        if (pool.isEmpty()) return emptyList()

        val requestedVoice = activeRequestedVoice(context)
        val requestedQuality = activeRequestedQuality(context)
        if (requestedVoice != null || requestedQuality != null) {
            if (requestedQuality != null) pool = pool.filter { requestedQualityMatches(it, requestedQuality) }
            if (requestedVoice != null) pool = pool.filter { requestedVoiceMatches(it, requestedVoice) }
            return pool
        }

        val bounds = resolutionBounds(context.preferredResolutionHeight)
        val lower2 = bounds[0]
        val lower1 = bounds[1]
        val preferred = bounds[2]
        val upper1 = bounds[3]
        val upper2 = bounds[4]
        val gates: List<(StreamCandidate) -> Boolean> = listOf(
            { !it.isTrailer },
            { isRussianLanguage(it) },
            { inResolutionRange(it, lower2, upper2) },
            { inResolutionRange(it, lower2, upper1) },
            { inResolutionRange(it, lower1, upper1) },
            { inResolutionRange(it, preferred, preferred) },
            { hasNoAdvertisement(it) },
            { isNonAv1(it) },
        )
        for (gate in gates) {
            if (pool.isEmpty()) break
            pool = pool.filter(gate)
        }
        return pool
    }

    private fun baseCompatibilityScore(candidate: StreamCandidate, context: StreamRankingContext): Double {
        val bounds = resolutionBounds(context.preferredResolutionHeight)
        val lower2 = bounds[0]
        val lower1 = bounds[1]
        val preferred = bounds[2]
        val upper1 = bounds[3]
        val upper2 = bounds[4]
        var score = 0.0
        if (!candidate.isTrailer) score += 1.0
        if (isRussianLanguage(candidate)) score += 1.0
        if (inResolutionRange(candidate, lower2, upper2)) score += 1.0
        if (inResolutionRange(candidate, lower2, upper1)) score += 1.0
        if (inResolutionRange(candidate, lower1, upper1)) score += 1.0
        if (inResolutionRange(candidate, preferred, preferred)) score += 1.0
        if (hasNoAdvertisement(candidate)) score += 1.0
        return score
    }

    /**
     * Zona-compatible relaxed BETTER max-score group. Encounter order is kept
     * here exactly; Movia applies health/startup/P2P ranking only inside this
     * already-compatible max-score group.
     */
    internal fun betterGroup(
        candidates: List<StreamCandidate>,
        context: StreamRankingContext,
    ): List<StreamCandidate> {
        val pool = healthyPool(candidates, context.failedStreamIds)
        if (pool.isEmpty()) return emptyList()
        val requestedVoice = activeRequestedVoice(context)
        val requestedQuality = activeRequestedQuality(context)
        val hasCustom = requestedVoice != null || requestedQuality != null
        var bestScore = Double.NEGATIVE_INFINITY
        val best = ArrayList<StreamCandidate>()
        for (candidate in pool) {
            var score = baseCompatibilityScore(candidate, context)
            if (requestedVoice != null && requestedVoiceMatches(candidate, requestedVoice)) score += CUSTOM_FILTER_BOOST
            if (requestedQuality != null && requestedQualityMatches(candidate, requestedQuality)) score += CUSTOM_FILTER_BOOST
            if (!hasCustom && isNonAv1(candidate)) score += 1.0
            when {
                score > bestScore -> {
                    bestScore = score
                    best.clear()
                    best += candidate
                }
                score == bestScore -> best += candidate
            }
        }
        return best
    }

    /**
     * Movia ranking inside a compatibility group: observed health/startup and
     * P2P viability are first-class signals; transport/provider remain stable
     * final tie-breaks. A direct URL has no unconditional priority.
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
                .thenBy { coldP2pPenalty(it) }
                .thenBy { if (hasPeers(it)) 0 else 1 }
                .thenByDescending { it.seeders }
                .thenBy { defaultQualityRank(it.quality) }
                .thenBy { normalized(it.transport) }
                .thenBy { normalized(it.provider) }
                .thenBy { it.stableStreamId },
        )
    }

    /** Strict BEST first; if it is empty, relax immediately because Movia's
     * resolver returns a completed fan-out result rather than a live partial flow. */
    fun selectBest(
        candidates: List<StreamCandidate>,
        requestedVoice: String?,
        requestedQuality: String?,
        failedStreamIds: Set<String> = emptySet(),
        context: StreamRankingContext = StreamRankingContext(),
    ): StreamCandidate? {
        val effectiveContext = context.copy(
            requestedVoice = requestedVoice,
            requestedQuality = requestedQuality,
            failedStreamIds = context.failedStreamIds + failedStreamIds,
        )
        val strict = strictBestGroup(candidates, effectiveContext)
        if (strict.isNotEmpty()) {
            return rankCandidates(strict, context = effectiveContext).firstOrNull()
        }
        val better = betterGroup(candidates, effectiveContext)
        return rankCandidates(better, context = effectiveContext).firstOrNull()
    }

    /** Recovery follows Zona's relaxed-first behavior after a player failure. */
    fun fallbackOrder(
        candidates: List<StreamCandidate>,
        context: StreamRankingContext,
    ): List<StreamCandidate> {
        val better = betterGroup(candidates, context)
        val strict = strictBestGroup(candidates, context)
        val seen = linkedSetOf<String>()
        val ordered = ArrayList<StreamCandidate>()
        fun appendRanked(group: List<StreamCandidate>) {
            for (candidate in rankCandidates(group, context = context)) {
                if (seen.add(candidate.stableStreamId)) ordered += candidate
            }
        }
        appendRanked(better)
        appendRanked(strict)
        appendRanked(healthyPool(candidates, context.failedStreamIds))
        return ordered
    }
}
